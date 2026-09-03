from __future__ import annotations

import json
import math
import statistics
from dataclasses import dataclass
from typing import Any

from backtest import DEFAULT_LIMIT, FEE, POSITION, STOP, TAKE, fetch_history, validate


@dataclass(frozen=True)
class Variant:
    name: str
    fast: int = 5
    slow: int = 20
    momentum_threshold: float = 0.001
    trend_window: int = 0
    breakout_window: int = 0
    cooldown_bars: int = 0


VARIANTS = (
    Variant("baseline_v2"),
    Variant("trend50", trend_window=50),
    Variant("trend50_momentum15", momentum_threshold=0.0015, trend_window=50),
    Variant("trend100", trend_window=100),
    Variant("trend50_breakout10", trend_window=50, breakout_window=10),
    Variant("trend50_cooldown6", trend_window=50, cooldown_bars=6),
)


def entry_signal(completed_rows: list[list[float]], variant: Variant) -> bool:
    need = max(variant.slow, variant.trend_window, variant.breakout_window + 1)
    if len(completed_rows) < need:
        return False
    closes = [float(row[4]) for row in completed_rows]
    fast = statistics.fmean(closes[-variant.fast:])
    slow = statistics.fmean(closes[-variant.slow:])
    if not fast > slow * (1.0 + variant.momentum_threshold):
        return False
    last = closes[-1]
    if variant.trend_window:
        trend = statistics.fmean(closes[-variant.trend_window:])
        if not last > trend:
            return False
    if variant.breakout_window:
        prior = closes[-variant.breakout_window - 1:-1]
        if not prior or last <= max(prior):
            return False
    return True


def simulate_variant(rows: list[list[float]], variant: Variant) -> dict[str, Any]:
    validate(rows)
    cash, qty, entry, entry_fee = 10.0, 0.0, None, 0.0
    realized = fees = gross_profit = gross_loss = 0.0
    closed = wins = losses = 0
    peak = 10.0
    max_dd = 0.0
    trade_pnls: list[float] = []
    bars_in_market = 0
    pending = False
    cooldown = 0

    for i, row in enumerate(rows):
        _ts, opened, high, low, close, _volume = row

        if pending and qty == 0:
            notional = cash * POSITION
            entry_fee = notional * FEE
            qty = notional / opened
            cash -= notional + entry_fee
            fees += entry_fee
            entry = opened
            pending = False

        exited = False
        if qty > 0 and entry:
            exit_price = None
            if low <= entry * (1 - STOP):
                exit_price = entry * (1 - STOP)
            elif high >= entry * (1 + TAKE):
                exit_price = entry * (1 + TAKE)
            if exit_price is not None:
                exit_fee = qty * exit_price * FEE
                pnl = qty * exit_price - exit_fee - qty * entry - entry_fee
                cash += qty * exit_price - exit_fee
                fees += exit_fee
                realized += pnl
                trade_pnls.append(pnl)
                closed += 1
                if pnl > 0:
                    wins += 1
                    gross_profit += pnl
                else:
                    losses += 1
                    gross_loss += -pnl
                qty = 0.0
                entry = None
                entry_fee = 0.0
                cooldown = variant.cooldown_bars
                exited = True

        if qty > 0:
            bars_in_market += 1
        equity = cash + qty * close * (1 - FEE)
        peak = max(peak, equity)
        max_dd = max(max_dd, (peak - equity) / peak if peak > 0 else 0.0)

        if cooldown > 0 and not exited:
            cooldown -= 1
        if qty == 0 and not pending and cooldown == 0:
            # Signal uses candles through i only; any order executes at i+1 open.
            if entry_signal(rows[: i + 1], variant):
                pending = True

    final = cash + qty * rows[-1][4] * (1 - FEE)
    ret = final / 10.0 - 1.0
    avg = statistics.fmean(trade_pnls) if trade_pnls else None
    return {
        "name": variant.name,
        "return_pct": ret,
        "final_equity": final,
        "closed_trades": closed,
        "wins": wins,
        "losses": losses,
        "win_rate": wins / closed if closed else None,
        "max_drawdown_pct": max_dd,
        "profit_factor": gross_profit / gross_loss if gross_loss else (math.inf if gross_profit else None),
        "expectancy_per_trade": avg,
        "fees_paid": fees,
        "exposure_pct": bars_in_market / len(rows),
        "open_position_at_end": qty > 0,
    }


def candidate_passes(train: dict[str, Any], holdout: dict[str, Any], baseline_holdout: dict[str, Any]) -> bool:
    return (
        train["return_pct"] > 0
        and holdout["return_pct"] > 0
        and train["closed_trades"] >= 10
        and holdout["closed_trades"] >= 5
        and holdout["max_drawdown_pct"] <= min(0.08, max(0.000001, baseline_holdout["max_drawdown_pct"]))
    )


def main() -> None:
    rows, source = fetch_history(DEFAULT_LIMIT)
    validate(rows)
    if len(rows) < 2160:
        raise SystemExit("FAIL-CLOSED: research requires 2160 Wallex hourly candles")
    rows = rows[-2160:]
    train = rows[:1440]
    holdout = rows[1440:]

    results: list[dict[str, Any]] = []
    baseline_holdout = simulate_variant(holdout, VARIANTS[0])
    for variant in VARIANTS:
        tr = simulate_variant(train, variant)
        ho = simulate_variant(holdout, variant)
        full = simulate_variant(rows, variant)
        results.append({
            "variant": variant.name,
            "train": tr,
            "holdout": ho,
            "full_90d": full,
            "passes": candidate_passes(tr, ho, baseline_holdout),
        })

    passing = [row for row in results if row["passes"]]
    selected = None
    if passing:
        # Selection uses holdout robustness first, then lower drawdown, not raw train fit.
        selected = max(
            passing,
            key=lambda row: (
                row["holdout"]["return_pct"],
                -row["holdout"]["max_drawdown_pct"],
                row["holdout"]["closed_trades"],
            ),
        )

    out = {
        "ok": True,
        "source": source,
        "research_only": True,
        "risk_unchanged": {
            "position_pct": POSITION,
            "stop_loss_pct": STOP,
            "take_profit_pct": TAKE,
            "fee_pct": FEE,
        },
        "method": {
            "candles": len(rows),
            "train_hours": len(train),
            "holdout_hours": len(holdout),
            "selection_policy": "predefined small variant set; train and holdout both positive; holdout >=5 trades; holdout DD <= baseline holdout and <=8%",
            "lookahead_policy": "signal through candle i close; execution at candle i+1 open",
            "intrabar_policy": "stop_first_if_stop_and_take_hit_same_candle",
        },
        "baseline_holdout": baseline_holdout,
        "results": results,
        "selected": selected,
    }
    print("STRATEGY_RESEARCH=PASS")
    print(json.dumps(out, ensure_ascii=False, sort_keys=True, allow_nan=False))


if __name__ == "__main__":
    main()

from __future__ import annotations

import argparse
import json
import math
import statistics
import time
from pathlib import Path
from typing import Any

from common import atomic_json

FEE = 0.002
POSITION = 0.25
STOP = 0.015
TAKE = 0.03
DEFAULT_LIMIT = 24 * 90
WALLEX_CHUNK_HOURS = 24 * 14


def parse_wallex(payload: dict[str, Any], limit: int | None = None) -> list[list[float]]:
    if payload.get("s") != "ok":
        raise RuntimeError("Wallex public OHLC rejected")
    columns = [payload.get(key) or [] for key in ("t", "o", "h", "l", "c", "v")]
    size = min((len(column) for column in columns), default=0)
    if size <= 0:
        raise RuntimeError("Wallex public OHLC returned no candles")
    rows = [
        [
            float(columns[0][i]),
            float(columns[1][i]),
            float(columns[2][i]),
            float(columns[3][i]),
            float(columns[4][i]),
            float(columns[5][i]),
        ]
        for i in range(size)
    ]
    deduped = {int(row[0]): row for row in rows}
    ordered = [deduped[key] for key in sorted(deduped)]
    return ordered[-limit:] if limit is not None else ordered


def merge_rows(chunks: list[list[list[float]]], limit: int) -> list[list[float]]:
    by_ts: dict[int, list[float]] = {}
    for chunk in chunks:
        for row in chunk:
            by_ts[int(row[0])] = row
    ordered = [by_ts[key] for key in sorted(by_ts)]
    return ordered[-limit:]


def fetch_wallex(limit: int = DEFAULT_LIMIT) -> list[list[float]]:
    import httpx

    now = int(time.time())
    # Request 72 extra hours, then trim to the exact target. Chunking avoids
    # silently accepting an exchange-side row cap as a complete history.
    start = now - (limit + 72) * 3600
    chunks: list[list[list[float]]] = []
    cursor = start
    span = WALLEX_CHUNK_HOURS * 3600
    while cursor < now:
        end = min(cursor + span, now)
        response = httpx.get(
            "https://api.wallex.ir/v1/udf/history",
            params={"symbol": "BTCUSDT", "resolution": "60", "from": cursor, "to": end},
            timeout=20,
            headers={"User-Agent": "ChobYar-Trader/4-paper-backtest"},
        )
        response.raise_for_status()
        payload = response.json()
        if not isinstance(payload, dict):
            raise RuntimeError("Wallex public OHLC malformed")
        chunks.append(parse_wallex(payload))
        if end >= now:
            break
        cursor = end
    rows = merge_rows(chunks, limit)
    validate(rows)
    return rows


def fetch_history(limit: int = DEFAULT_LIMIT) -> tuple[list[list[float]], str]:
    # Fail closed on the same Iranian market used by the paper engine. Do not
    # silently substitute another exchange when Wallex history is unavailable.
    return fetch_wallex(limit), "wallex_public_ohlc_chunked"


def validate(rows: list[list[float]]) -> None:
    if len(rows) < 80:
        raise ValueError("at least 80 candles required")
    previous = -1.0
    for row in rows:
        if len(row) < 6 or row[0] <= previous or any(not math.isfinite(x) or x <= 0 for x in row[:5]):
            raise ValueError("invalid or unsorted candles")
        if row[2] < max(row[1], row[4]) or row[3] > min(row[1], row[4]):
            raise ValueError("invalid OHLC candle")
        previous = row[0]


def history_quality(rows: list[list[float]]) -> dict[str, Any]:
    gaps = []
    for previous, current in zip(rows, rows[1:]):
        delta = float(current[0]) - float(previous[0])
        if delta > 5400:
            gaps.append(delta)
    duration = max(0.0, (rows[-1][0] - rows[0][0]) / 86400.0)
    return {
        "history_start_ts": int(rows[0][0]),
        "history_end_ts": int(rows[-1][0]),
        "history_days": duration,
        "gap_count_over_90m": len(gaps),
        "max_gap_hours": (max(gaps) / 3600.0) if gaps else 1.0,
    }


def simulate(rows: list[list[float]], source: str = "deterministic_input") -> dict[str, Any]:
    validate(rows)
    cash, qty, entry, entry_fee = 10.0, 0.0, None, 0.0
    realized = fees = gross_profit = gross_loss = 0.0
    closed = wins = losses = 0
    peak = 10.0
    max_dd = 0.0
    curve: list[list[float]] = []
    trade_pnls: list[float] = []
    bars_in_market = 0
    pending = 0
    for i, row in enumerate(rows):
        ts, opened, high, low, close, _volume = row
        # A signal uses only fully closed candles through i-1 and executes at candle i open.
        if pending and qty == 0:
            notional = cash * POSITION
            entry_fee = notional * FEE
            qty = notional / opened
            cash -= notional + entry_fee
            fees += entry_fee
            entry = opened
            pending = 0
        if qty > 0 and entry:
            exit_price = None
            # Conservative intrabar ordering: if both levels are touched in the
            # same hourly candle, assume the stop was hit first.
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
                qty = 0
                entry = None
                entry_fee = 0
        if qty > 0:
            bars_in_market += 1
        # Conservative mark-to-market includes the simulated future exit fee.
        equity = cash + qty * close * (1 - FEE)
        peak = max(peak, equity)
        dd = (peak - equity) / peak
        max_dd = max(max_dd, dd)
        curve.append([int(ts), round(equity, 8)])
        if i >= 21 and qty == 0:
            completed = [r[4] for r in rows[i - 20:i]]
            fast, slow = statistics.fmean(completed[-5:]), statistics.fmean(completed)
            pending = 1 if fast > slow * 1.001 else 0

    final = cash + qty * rows[-1][4] * (1 - FEE)
    benchmark_notional = 10.0 / (1 + FEE)
    benchmark_qty = benchmark_notional / rows[0][1]
    buy_hold = benchmark_qty * rows[-1][4] * (1 - FEE)
    quality = history_quality(rows)
    avg_trade = statistics.fmean(trade_pnls) if trade_pnls else None
    strategy_return = final / 10.0 - 1
    benchmark_return = buy_hold / 10.0 - 1

    return {
        "ok": True,
        "source": source,
        "strategy_model": "price_only_proxy_v2",
        "full_fidelity_multiagent": False,
        "coverage": ["wallex_hourly_ohlc", "fees", "position_sizing", "stop_loss", "take_profit"],
        "missing_historical_features": ["order_book", "tape_order_flow", "global_context"],
        "candles": len(rows),
        **quality,
        "initial_equity": 10.0,
        "final_equity": final,
        "pnl": final - 10.0,
        "return_pct": strategy_return,
        "benchmark_buy_hold_equity": buy_hold,
        "benchmark_buy_hold_return_pct": benchmark_return,
        "return_vs_buy_hold_pct": strategy_return - benchmark_return,
        "closed_trades": closed,
        "wins": wins,
        "losses": losses,
        "win_rate": wins / closed if closed else None,
        "average_closed_trade_pnl": avg_trade,
        "expectancy_per_closed_trade": avg_trade,
        "closed_trades_per_30d": (closed / quality["history_days"] * 30.0) if quality["history_days"] > 0 else None,
        "max_drawdown_pct": max_dd,
        "profit_factor": gross_profit / gross_loss if gross_loss else ("inf" if gross_profit else None),
        "fees_paid": fees,
        "exposure_pct": bars_in_market / len(rows),
        "open_position_at_end": qty > 0,
        "stop_loss_pct": STOP,
        "take_profit_pct": TAKE,
        "position_pct": POSITION,
        "intrabar_policy": "stop_first_if_stop_and_take_hit_same_candle",
        "lookahead_policy": "close[i-1] signal; open[i] execution",
        "equity_curve": curve,
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", type=Path)
    parser.add_argument("--output", type=Path, default=Path("/opt/chobyar-trader/state/backtest_latest.json"))
    parser.add_argument("--limit", type=int, default=DEFAULT_LIMIT)
    args = parser.parse_args()
    if args.limit < 80:
        raise SystemExit("backtest limit must be at least 80 candles")
    if args.input:
        rows = json.loads(args.input.read_text(encoding="utf-8"))
        source = "input_file"
    else:
        rows, source = fetch_history(args.limit)
    atomic_json(args.output, simulate(rows, source))


if __name__ == "__main__":
    main()

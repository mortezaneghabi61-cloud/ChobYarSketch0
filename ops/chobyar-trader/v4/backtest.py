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


def parse_wallex(payload: dict[str, Any], limit: int = 720) -> list[list[float]]:
    if payload.get("s") != "ok":
        raise RuntimeError("Wallex public OHLC rejected")
    columns = [payload.get(key) or [] for key in ("t", "o", "h", "l", "c", "v")]
    size = min((len(column) for column in columns), default=0)
    if size < 80:
        raise RuntimeError("Wallex public OHLC returned insufficient candles")
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
    rows.sort(key=lambda row: row[0])
    return rows[-limit:]


def fetch_wallex(limit: int = 720) -> list[list[float]]:
    import httpx

    now = int(time.time())
    # Ask for extra history because exchanges may omit empty candles.
    start = now - max(limit * 3600 * 2, 35 * 24 * 3600)
    response = httpx.get(
        "https://api.wallex.ir/v1/udf/history",
        params={"symbol": "BTCUSDT", "resolution": "60", "from": start, "to": now},
        timeout=20,
        headers={"User-Agent": "ChobYar-Trader/4-paper-backtest"},
    )
    response.raise_for_status()
    payload = response.json()
    if not isinstance(payload, dict):
        raise RuntimeError("Wallex public OHLC malformed")
    return parse_wallex(payload, limit)


def fetch_kraken(limit: int = 720) -> list[list[float]]:
    import httpx

    response = httpx.get(
        "https://api.kraken.com/0/public/OHLC",
        params={"pair": "XBTUSDT", "interval": 60},
        timeout=20,
        headers={"User-Agent": "ChobYar-Trader/4-paper-backtest"},
    )
    response.raise_for_status()
    payload = response.json()
    if payload.get("error"):
        raise RuntimeError("Kraken public OHLC rejected")
    rows = next(value for key, value in payload["result"].items() if key != "last")
    return [[float(row[0]), float(row[1]), float(row[2]), float(row[3]), float(row[4]), float(row[6])] for row in rows[-limit:]]


def fetch_history(limit: int = 720) -> tuple[list[list[float]], str]:
    failures: list[str] = []
    for source, loader in (("wallex_public_ohlc", fetch_wallex), ("kraken_public_ohlc", fetch_kraken)):
        try:
            rows = loader(limit)
            validate(rows)
            return rows, source
        except Exception as exc:
            failures.append(f"{source}:{type(exc).__name__}")
    raise RuntimeError("historical public market data unavailable: " + ",".join(failures))


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


def simulate(rows: list[list[float]], source: str = "deterministic_input") -> dict[str, Any]:
    validate(rows)
    cash, qty, entry, entry_fee = 10.0, 0.0, None, 0.0
    realized = fees = gross_profit = gross_loss = 0.0
    closed = wins = losses = 0
    peak = 10.0
    max_dd = 0.0
    curve = []
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
        equity = cash + qty * close
        peak = max(peak, equity)
        dd = (peak - equity) / peak
        max_dd = max(max_dd, dd)
        curve.append([int(ts), round(equity, 8)])
        if i >= 21 and qty == 0:
            completed = [r[4] for r in rows[i - 20:i]]
            fast, slow = statistics.fmean(completed[-5:]), statistics.fmean(completed)
            pending = 1 if fast > slow * 1.001 else 0
    final = cash + qty * rows[-1][4]
    buy_hold = 10.0 * (rows[-1][4] / rows[0][1])
    return {
        "ok": True,
        "source": source,
        "candles": len(rows),
        "initial_equity": 10.0,
        "final_equity": final,
        "pnl": final - 10.0,
        "return_pct": final / 10.0 - 1,
        "benchmark_buy_hold_equity": buy_hold,
        "benchmark_buy_hold_return_pct": buy_hold / 10.0 - 1,
        "closed_trades": closed,
        "wins": wins,
        "losses": losses,
        "win_rate": wins / closed if closed else None,
        "max_drawdown_pct": max_dd,
        "profit_factor": gross_profit / gross_loss if gross_loss else ("inf" if gross_profit else None),
        "fees_paid": fees,
        "stop_loss_pct": STOP,
        "take_profit_pct": TAKE,
        "position_pct": POSITION,
        "lookahead_policy": "close[i-1] signal; open[i] execution",
        "equity_curve": curve,
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", type=Path)
    parser.add_argument("--output", type=Path, default=Path("/opt/chobyar-trader/state/backtest_latest.json"))
    args = parser.parse_args()
    if args.input:
        rows = json.loads(args.input.read_text(encoding="utf-8"))
        source = "input_file"
    else:
        rows, source = fetch_history()
    atomic_json(args.output, simulate(rows, source))


if __name__ == "__main__":
    main()

from __future__ import annotations

import argparse
import json
import math
import os
import statistics
import time
from collections import deque
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

import httpx

from specialist_council import CouncilContext, run_council

DEFAULT_APP_DIR = Path("/opt/chobyar-trader")
SYMBOL = "BTCUSDT"
OKX_SWAP = "BTC-USDT-SWAP"
BREADTH_SYMBOLS = ("BTC-USDT", "ETH-USDT", "SOL-USDT")
MAX_LOCAL_CYCLE_AGE_SECONDS = 180


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat()


def parse_ts(value: Any) -> float | None:
    if not isinstance(value, str) or not value:
        return None
    try:
        return datetime.fromisoformat(value.replace("Z", "+00:00")).timestamp()
    except ValueError:
        return None


def finite(value: Any) -> float | None:
    try:
        number = float(value)
    except (TypeError, ValueError):
        return None
    return number if math.isfinite(number) else None


def positive(value: Any) -> float | None:
    number = finite(value)
    return number if number is not None and number > 0 else None


def atomic_json(path: Path, payload: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(path.suffix + ".tmp")
    tmp.write_text(json.dumps(payload, ensure_ascii=False, sort_keys=True, separators=(",", ":")) + "\n", encoding="utf-8")
    os.replace(tmp, path)


def append_jsonl(path: Path, payload: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("a", encoding="utf-8") as stream:
        stream.write(json.dumps(payload, ensure_ascii=False, sort_keys=True, separators=(",", ":")) + "\n")


def read_last_cycle(path: Path) -> dict[str, Any]:
    if not path.is_file():
        raise RuntimeError("trader audit log missing")
    rows: deque[dict[str, Any]] = deque(maxlen=600)
    with path.open("r", encoding="utf-8") as stream:
        for line in stream:
            try:
                row = json.loads(line)
            except json.JSONDecodeError:
                continue
            if isinstance(row, dict) and row.get("event") == "cycle":
                rows.append(row)
    if not rows:
        raise RuntimeError("no trader cycle available")
    row = rows[-1]
    ts = parse_ts(row.get("ts"))
    if ts is None or time.time() - ts > MAX_LOCAL_CYCLE_AGE_SECONDS:
        raise RuntimeError("latest trader cycle is stale")
    return row


def fetch_wallex_candles(client: httpx.Client, limit: int = 168) -> list[list[float]]:
    now = int(time.time())
    response = client.get(
        "https://api.wallex.ir/v1/udf/history",
        params={"symbol": SYMBOL, "resolution": "60", "from": now - (limit + 24) * 3600, "to": now},
    )
    response.raise_for_status()
    payload = response.json()
    if not isinstance(payload, dict) or payload.get("s") != "ok":
        raise RuntimeError("Wallex OHLC rejected")
    columns = [payload.get(key) or [] for key in ("t", "o", "h", "l", "c", "v")]
    size = min((len(column) for column in columns), default=0)
    if size < 80:
        raise RuntimeError("insufficient Wallex hourly candles")
    by_ts: dict[int, list[float]] = {}
    for i in range(size):
        row = [float(columns[j][i]) for j in range(6)]
        by_ts[int(row[0])] = row
    rows = [by_ts[key] for key in sorted(by_ts)][-limit:]
    if len(rows) < 80:
        raise RuntimeError("insufficient deduplicated Wallex candles")
    return rows


def okx_json(client: httpx.Client, path: str, params: dict[str, str]) -> list[dict[str, Any]]:
    response = client.get("https://www.okx.com" + path, params=params)
    response.raise_for_status()
    payload = response.json()
    if not isinstance(payload, dict) or str(payload.get("code")) != "0":
        raise RuntimeError("OKX public response rejected")
    rows = payload.get("data") or []
    if not isinstance(rows, list):
        raise RuntimeError("OKX public response malformed")
    return [row for row in rows if isinstance(row, dict)]


def fetch_okx_breadth(client: httpx.Client) -> dict[str, float]:
    rows = okx_json(client, "/api/v5/market/tickers", {"instType": "SPOT"})
    result: dict[str, float] = {}
    wanted = set(BREADTH_SYMBOLS)
    for row in rows:
        inst = str(row.get("instId") or "")
        if inst not in wanted:
            continue
        last = positive(row.get("last"))
        opened = positive(row.get("open24h"))
        if last is not None and opened is not None:
            result[inst] = last / opened - 1.0
    return result


def fetch_okx_open_interest(client: httpx.Client) -> float | None:
    rows = okx_json(client, "/api/v5/public/open-interest", {"instType": "SWAP", "instId": OKX_SWAP})
    for row in rows:
        if row.get("instId") == OKX_SWAP:
            return positive(row.get("oiUsd")) or positive(row.get("oiCcy")) or positive(row.get("oi"))
    return None


def fetch_okx_funding(client: httpx.Client) -> tuple[float | None, float | None, int]:
    rows = okx_json(client, "/api/v5/public/funding-rate-history", {"instId": OKX_SWAP, "limit": "30"})
    rates: list[float] = []
    for row in rows:
        rate = finite(row.get("fundingRate"))
        if rate is None:
            rate = finite(row.get("realizedRate"))
        if rate is not None:
            rates.append(rate)
    if not rates:
        return None, None, 0
    current = rates[0]
    if len(rates) < 5:
        return current, None, len(rates)
    sigma = statistics.pstdev(rates)
    z = (current - statistics.fmean(rates)) / sigma if sigma > 1e-12 else 0.0
    return current, z, len(rates)


def previous_oi_change(state_path: Path, current_oi: float | None, now: float) -> tuple[float | None, dict[str, Any]]:
    previous_oi = previous_ts = None
    if state_path.is_file():
        try:
            previous = json.loads(state_path.read_text(encoding="utf-8"))
            previous_oi = positive(previous.get("oi_value"))
            previous_ts = finite(previous.get("oi_timestamp"))
        except Exception:
            previous_oi = previous_ts = None
    change = None
    if current_oi is not None and previous_oi is not None and previous_ts is not None:
        age = now - previous_ts
        if 60 <= age <= 3600:
            change = current_oi / previous_oi - 1.0
    state = {"oi_value": current_oi, "oi_timestamp": now, "generated_at_utc": utc_now()}
    return change, state


def build_context(cycle: dict[str, Any], candles: list[list[float]], breadth: dict[str, float], funding: float | None, funding_z: float | None, oi_change: float | None) -> CouncilContext:
    local_mid = positive(cycle.get("local_mid"))
    spread = finite(cycle.get("spread_pct"))
    imbalance = finite(cycle.get("orderbook_imbalance"))
    buy_ratio = finite(cycle.get("tape_buy_ratio"))
    if local_mid is None or spread is None or imbalance is None or buy_ratio is None:
        raise RuntimeError("latest trader cycle lacks required local market fields")
    sources = cycle.get("global_sources") or []
    return CouncilContext(
        candles=candles,
        local_mid=local_mid,
        spread_pct=max(0.0, spread),
        book_imbalance=imbalance,
        tape_buy_ratio=buy_ratio,
        global_change_24h=finite(cycle.get("global_change_24h")),
        global_dispersion_pct=finite(cycle.get("global_dispersion_pct")),
        global_source_count=len(sources) if isinstance(sources, list) else 0,
        funding_rate=funding,
        funding_z=funding_z,
        oi_change_pct=oi_change,
        breadth_24h=breadth,
    )


def run(app_dir: Path) -> dict[str, Any]:
    audit_path = app_dir / "logs" / "audit.jsonl"
    latest_path = app_dir / "state" / "v5_shadow_latest.json"
    state_path = app_dir / "state" / "v5_shadow_state.json"
    log_path = app_dir / "logs" / "v5_shadow.jsonl"

    cycle = read_last_cycle(audit_path)
    with httpx.Client(timeout=8.0, headers={"User-Agent": "ChobYar-Trader/5-shadow-public-data"}) as client:
        candles = fetch_wallex_candles(client)
        breadth: dict[str, float] = {}
        funding = funding_z = current_oi = None
        source_errors: list[str] = []
        try:
            breadth = fetch_okx_breadth(client)
        except Exception as exc:
            source_errors.append("okx_breadth:" + type(exc).__name__)
        try:
            current_oi = fetch_okx_open_interest(client)
        except Exception as exc:
            source_errors.append("okx_open_interest:" + type(exc).__name__)
        funding_samples = 0
        try:
            funding, funding_z, funding_samples = fetch_okx_funding(client)
        except Exception as exc:
            source_errors.append("okx_funding:" + type(exc).__name__)

    now = time.time()
    oi_change, oi_state = previous_oi_change(state_path, current_oi, now)
    atomic_json(state_path, oi_state)
    ctx = build_context(cycle, candles, breadth, funding, funding_z, oi_change)
    council = run_council(ctx)
    report = {
        **council,
        "generated_at_utc": utc_now(),
        "symbol": SYMBOL,
        "local_mid": ctx.local_mid,
        "source_health": {
            "wallex_hourly_candles": len(candles),
            "okx_breadth_symbols": sorted(breadth),
            "okx_funding_samples": funding_samples,
            "okx_open_interest_available": current_oi is not None,
            "errors": source_errors,
        },
    }
    atomic_json(latest_path, report)
    append_jsonl(log_path, report)
    return report


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--app-dir", type=Path, default=DEFAULT_APP_DIR)
    args = parser.parse_args()
    report = run(args.app_dir)
    print("V5_SHADOW=PASS")
    print(f"REGIME={report['regime']['label']}")
    print(f"SHADOW_ACTION={report['shadow_consensus']['action']}")
    print(f"RISK_VETO={str(report['shadow_consensus']['risk_veto']).upper()}")
    print(f"SPECIALISTS={len(report['specialists'])}")
    print("EXECUTION_AUTHORITY=NONE")


if __name__ == "__main__":
    main()

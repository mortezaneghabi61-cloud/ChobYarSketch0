from __future__ import annotations

import json
import math
import statistics
from pathlib import Path
from typing import Any, Callable

BYBIT_BASE = "https://api.bybit.com"
BYBIT_LINEAR_SYMBOL = "BTCUSDT"
BYBIT_BREADTH_SYMBOLS = {
    "BTCUSDT": "BTC-USDT",
    "ETHUSDT": "ETH-USDT",
    "SOLUSDT": "SOL-USDT",
}
REQUIRED_BREADTH = tuple(BYBIT_BREADTH_SYMBOLS.values())
MIN_FUNDING_SAMPLES = 5


def finite(value: Any) -> float | None:
    try:
        number = float(value)
    except (TypeError, ValueError):
        return None
    return number if math.isfinite(number) else None


def positive(value: Any) -> float | None:
    number = finite(value)
    return number if number is not None and number > 0 else None


def bybit_json(client: Any, path: str, params: dict[str, str]) -> list[dict[str, Any]]:
    response = client.get(BYBIT_BASE + path, params=params)
    response.raise_for_status()
    payload = response.json()
    if not isinstance(payload, dict) or int(payload.get("retCode", -1)) != 0:
        raise RuntimeError("Bybit public response rejected")
    result = payload.get("result")
    if not isinstance(result, dict):
        raise RuntimeError("Bybit public result malformed")
    rows = result.get("list") or []
    if not isinstance(rows, list):
        raise RuntimeError("Bybit public list malformed")
    return [row for row in rows if isinstance(row, dict)]


def fetch_bybit_breadth(client: Any) -> dict[str, float]:
    rows = bybit_json(client, "/v5/market/tickers", {"category": "spot"})
    result: dict[str, float] = {}
    for row in rows:
        mapped = BYBIT_BREADTH_SYMBOLS.get(str(row.get("symbol") or ""))
        if mapped is None:
            continue
        change = finite(row.get("price24hPcnt"))
        if change is None:
            last = positive(row.get("lastPrice"))
            previous = positive(row.get("prevPrice24h"))
            if last is not None and previous is not None:
                change = last / previous - 1.0
        if change is not None:
            result[mapped] = change
    if not all(symbol in result for symbol in REQUIRED_BREADTH):
        raise RuntimeError("Bybit breadth incomplete")
    return result


def _funding_stats(samples: list[tuple[int, float]]) -> tuple[float | None, float | None, int]:
    if not samples:
        return None, None, 0
    dedup: dict[int, float] = {}
    for timestamp, rate in samples:
        dedup[int(timestamp)] = float(rate)
    ordered = [dedup[key] for key in sorted(dedup, reverse=True)]
    if not ordered:
        return None, None, 0
    current = ordered[0]
    if len(ordered) < MIN_FUNDING_SAMPLES:
        return current, None, len(ordered)
    sigma = statistics.pstdev(ordered)
    z = (current - statistics.fmean(ordered)) / sigma if sigma > 1e-12 else 0.0
    return current, z, len(ordered)


def fetch_bybit_funding(client: Any) -> tuple[float | None, float | None, int]:
    rows = bybit_json(
        client,
        "/v5/market/funding/history",
        {"category": "linear", "symbol": BYBIT_LINEAR_SYMBOL, "limit": "30"},
    )
    samples: list[tuple[int, float]] = []
    for row in rows:
        rate = finite(row.get("fundingRate"))
        timestamp = finite(row.get("fundingRateTimestamp"))
        if rate is not None and timestamp is not None:
            samples.append((int(timestamp), rate))
    return _funding_stats(samples)


def fetch_bybit_open_interest(client: Any) -> float | None:
    rows = bybit_json(
        client,
        "/v5/market/open-interest",
        {
            "category": "linear",
            "symbol": BYBIT_LINEAR_SYMBOL,
            "intervalTime": "5min",
            "limit": "5",
        },
    )
    samples: list[tuple[int, float]] = []
    for row in rows:
        value = positive(row.get("openInterest"))
        timestamp = finite(row.get("timestamp"))
        if value is not None and timestamp is not None:
            samples.append((int(timestamp), value))
    if not samples:
        return None
    return max(samples, key=lambda item: item[0])[1]


def source_aware_previous_oi_change(
    state_path: Path,
    current_oi: float | None,
    now: float,
    source: str,
    utc_now: Callable[[], str],
) -> tuple[float | None, dict[str, Any]]:
    previous: dict[str, Any] = {}
    if state_path.is_file():
        try:
            value = json.loads(state_path.read_text(encoding="utf-8"))
            if isinstance(value, dict):
                previous = value
        except Exception:
            previous = {}

    if current_oi is None or source not in {"okx", "bybit"}:
        return None, previous

    previous_oi = positive(previous.get("oi_value"))
    previous_ts = finite(previous.get("oi_timestamp"))
    previous_source = str(previous.get("oi_source") or "")
    change = None
    if previous_source == source and previous_oi is not None and previous_ts is not None:
        age = now - previous_ts
        if 60 <= age <= 3600:
            change = current_oi / previous_oi - 1.0

    state = {
        "oi_value": current_oi,
        "oi_timestamp": now,
        "oi_source": source,
        "generated_at_utc": utc_now(),
    }
    return change, state


def install(base: Any) -> Callable[[], None]:
    """Install public-data fallbacks on the v5 shadow base module for one run.

    OKX remains primary. Bybit is used only when an OKX public dataset is rejected,
    incomplete, or unavailable. The council/quorum/risk logic is not modified.
    """

    original_breadth = base.fetch_okx_breadth
    original_oi = base.fetch_okx_open_interest
    original_funding = base.fetch_okx_funding
    original_previous_oi = base.previous_oi_change
    original_atomic_json = base.atomic_json

    health: dict[str, Any] = {
        "breadth_source": "none",
        "funding_source": "none",
        "open_interest_source": "none",
        "resolved_breadth_symbols": [],
        "resolved_funding_samples": 0,
        "oi_change_available": False,
        "okx_breadth_symbols": [],
        "okx_funding_samples": 0,
        "okx_open_interest_available": False,
        "errors": [],
    }

    def error(label: str, exc: BaseException | str) -> None:
        suffix = exc if isinstance(exc, str) else type(exc).__name__
        health["errors"].append(f"{label}:{suffix}")

    def breadth(client: Any) -> dict[str, float]:
        try:
            result = original_breadth(client)
            health["okx_breadth_symbols"] = sorted(result)
            if all(symbol in result for symbol in REQUIRED_BREADTH):
                health["breadth_source"] = "okx"
                health["resolved_breadth_symbols"] = sorted(result)
                return result
            error("okx_breadth", "incomplete")
        except Exception as exc:
            error("okx_breadth", exc)
        try:
            result = fetch_bybit_breadth(client)
            health["breadth_source"] = "bybit"
            health["resolved_breadth_symbols"] = sorted(result)
            return result
        except Exception as exc:
            error("bybit_breadth", exc)
            raise RuntimeError("all public breadth sources unavailable") from exc

    def funding(client: Any) -> tuple[float | None, float | None, int]:
        try:
            current, z, samples = original_funding(client)
            health["okx_funding_samples"] = samples
            if current is not None and z is not None and samples >= MIN_FUNDING_SAMPLES:
                health["funding_source"] = "okx"
                health["resolved_funding_samples"] = samples
                return current, z, samples
            error("okx_funding", "incomplete")
        except Exception as exc:
            error("okx_funding", exc)
        try:
            current, z, samples = fetch_bybit_funding(client)
            if current is None or z is None or samples < MIN_FUNDING_SAMPLES:
                raise RuntimeError("Bybit funding incomplete")
            health["funding_source"] = "bybit"
            health["resolved_funding_samples"] = samples
            return current, z, samples
        except Exception as exc:
            error("bybit_funding", exc)
            raise RuntimeError("all public funding sources unavailable") from exc

    def open_interest(client: Any) -> float | None:
        try:
            result = original_oi(client)
            health["okx_open_interest_available"] = result is not None
            if result is not None:
                health["open_interest_source"] = "okx"
                return result
            error("okx_open_interest", "incomplete")
        except Exception as exc:
            error("okx_open_interest", exc)
        try:
            result = fetch_bybit_open_interest(client)
            if result is None:
                raise RuntimeError("Bybit open interest incomplete")
            health["open_interest_source"] = "bybit"
            return result
        except Exception as exc:
            error("bybit_open_interest", exc)
            raise RuntimeError("all public open-interest sources unavailable") from exc

    def previous_oi(state_path: Path, current_oi: float | None, now: float):
        change, state = source_aware_previous_oi_change(
            state_path,
            current_oi,
            now,
            str(health.get("open_interest_source") or "none"),
            base.utc_now,
        )
        health["oi_change_available"] = change is not None
        return change, state

    def atomic_json(path: Path, payload: dict[str, Any]) -> None:
        if path.name == "v5_shadow_latest.json" and isinstance(payload, dict):
            current = payload.get("source_health")
            source_health = dict(current) if isinstance(current, dict) else {}
            source_health.update(
                {
                    "breadth_source": health["breadth_source"],
                    "funding_source": health["funding_source"],
                    "open_interest_source": health["open_interest_source"],
                    "resolved_breadth_symbols": list(health["resolved_breadth_symbols"]),
                    "resolved_funding_samples": int(health["resolved_funding_samples"]),
                    "oi_change_available": bool(health["oi_change_available"]),
                    "okx_breadth_symbols": list(health["okx_breadth_symbols"]),
                    "okx_funding_samples": int(health["okx_funding_samples"]),
                    "okx_open_interest_available": bool(health["okx_open_interest_available"]),
                    "errors": list(health["errors"]),
                }
            )
            payload["source_health"] = source_health
        original_atomic_json(path, payload)

    base.fetch_okx_breadth = breadth
    base.fetch_okx_open_interest = open_interest
    base.fetch_okx_funding = funding
    base.previous_oi_change = previous_oi
    base.atomic_json = atomic_json

    def restore() -> None:
        base.fetch_okx_breadth = original_breadth
        base.fetch_okx_open_interest = original_oi
        base.fetch_okx_funding = original_funding
        base.previous_oi_change = original_previous_oi
        base.atomic_json = original_atomic_json

    return restore

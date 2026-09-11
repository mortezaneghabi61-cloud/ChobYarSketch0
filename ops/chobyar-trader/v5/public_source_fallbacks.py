from __future__ import annotations

import json
import math
import statistics
import time
from pathlib import Path
from typing import Any, Callable

KUCOIN_SPOT_BASE = "https://api.kucoin.com"
KUCOIN_FUTURES_BASE = "https://api-futures.kucoin.com"
KUCOIN_LINEAR_SYMBOL = "XBTUSDTM"
REQUIRED_BREADTH = ("BTC-USDT", "ETH-USDT", "SOL-USDT")
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


def kucoin_json(client: Any, base_url: str, path: str, params: dict[str, str] | None = None) -> Any:
    response = client.get(base_url + path, params=params or {})
    response.raise_for_status()
    payload = response.json()
    if not isinstance(payload, dict) or str(payload.get("code")) != "200000":
        raise RuntimeError("KuCoin public response rejected")
    return payload.get("data")


def fetch_kucoin_breadth(client: Any) -> dict[str, float]:
    data = kucoin_json(client, KUCOIN_SPOT_BASE, "/api/v1/market/allTickers")
    if not isinstance(data, dict) or not isinstance(data.get("ticker"), list):
        raise RuntimeError("KuCoin breadth response malformed")
    wanted = set(REQUIRED_BREADTH)
    result: dict[str, float] = {}
    for row in data["ticker"]:
        if not isinstance(row, dict):
            continue
        symbol = str(row.get("symbol") or "")
        if symbol not in wanted:
            continue
        change = finite(row.get("changeRate"))
        if change is not None:
            result[symbol] = change
    if not wanted.issubset(result):
        raise RuntimeError("KuCoin breadth incomplete")
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


def fetch_kucoin_funding(client: Any, now_ms: int | None = None) -> tuple[float | None, float | None, int]:
    end_ms = int(now_ms if now_ms is not None else time.time() * 1000)
    start_ms = end_ms - 14 * 24 * 3600 * 1000
    data = kucoin_json(
        client,
        KUCOIN_FUTURES_BASE,
        "/api/v1/contract/funding-rates",
        {"symbol": KUCOIN_LINEAR_SYMBOL, "from": str(start_ms), "to": str(end_ms)},
    )
    if not isinstance(data, list):
        raise RuntimeError("KuCoin funding response malformed")
    samples: list[tuple[int, float]] = []
    for row in data:
        if not isinstance(row, dict):
            continue
        rate = finite(row.get("fundingRate"))
        timestamp = finite(row.get("timepoint"))
        if rate is not None and timestamp is not None:
            samples.append((int(timestamp), rate))
    return _funding_stats(samples)


def fetch_kucoin_open_interest(client: Any, now_ms: int | None = None) -> tuple[float | None, float | None]:
    end_ms = int(now_ms if now_ms is not None else time.time() * 1000)
    start_ms = end_ms - 30 * 60 * 1000
    data = kucoin_json(
        client,
        KUCOIN_SPOT_BASE,
        "/api/ua/v1/market/open-interest",
        {
            "symbol": KUCOIN_LINEAR_SYMBOL,
            "interval": "5min",
            "startAt": str(start_ms),
            "endAt": str(end_ms),
            "pageSize": "10",
        },
    )
    if not isinstance(data, list):
        raise RuntimeError("KuCoin open-interest response malformed")
    samples: dict[int, float] = {}
    for row in data:
        if not isinstance(row, dict):
            continue
        value = positive(row.get("openInterest"))
        timestamp = finite(row.get("ts"))
        if value is not None and timestamp is not None:
            samples[int(timestamp)] = value
    ordered = sorted(samples.items(), reverse=True)
    if not ordered:
        return None, None
    current = ordered[0][1]
    change = None
    if len(ordered) >= 2 and ordered[1][1] > 0:
        change = current / ordered[1][1] - 1.0
    return current, change


def source_aware_previous_oi_change(
    state_path: Path,
    current_oi: float | None,
    now: float,
    source: str,
    utc_now: Callable[[], str],
    direct_same_source_change: float | None = None,
) -> tuple[float | None, dict[str, Any]]:
    previous: dict[str, Any] = {}
    if state_path.is_file():
        try:
            value = json.loads(state_path.read_text(encoding="utf-8"))
            if isinstance(value, dict):
                previous = value
        except Exception:
            previous = {}

    if current_oi is None or source not in {"okx", "kucoin"}:
        return None, previous

    if source == "kucoin" and direct_same_source_change is not None:
        change = finite(direct_same_source_change)
    else:
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
    """Install credential-free public-data fallbacks on the v5 shadow base module.

    OKX remains primary. KuCoin is used only when the corresponding OKX public
    dataset is rejected, incomplete, or unavailable. Council/risk thresholds are
    not modified and failures across both venues remain fail-closed.
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
        "kucoin_direct_oi_change": None,
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
            result = fetch_kucoin_breadth(client)
            health["breadth_source"] = "kucoin"
            health["resolved_breadth_symbols"] = sorted(result)
            return result
        except Exception as exc:
            error("kucoin_breadth", exc)
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
            current, z, samples = fetch_kucoin_funding(client)
            if current is None or z is None or samples < MIN_FUNDING_SAMPLES:
                raise RuntimeError("KuCoin funding incomplete")
            health["funding_source"] = "kucoin"
            health["resolved_funding_samples"] = samples
            return current, z, samples
        except Exception as exc:
            error("kucoin_funding", exc)
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
            result, direct_change = fetch_kucoin_open_interest(client)
            if result is None:
                raise RuntimeError("KuCoin open interest incomplete")
            health["open_interest_source"] = "kucoin"
            health["kucoin_direct_oi_change"] = direct_change
            return result
        except Exception as exc:
            error("kucoin_open_interest", exc)
            raise RuntimeError("all public open-interest sources unavailable") from exc

    def previous_oi(state_path: Path, current_oi: float | None, now: float):
        change, state = source_aware_previous_oi_change(
            state_path,
            current_oi,
            now,
            str(health.get("open_interest_source") or "none"),
            base.utc_now,
            finite(health.get("kucoin_direct_oi_change")),
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

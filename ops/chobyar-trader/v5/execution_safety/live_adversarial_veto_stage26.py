from __future__ import annotations

import argparse
import json
import math
import os
import tempfile
from dataclasses import asdict, dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Mapping

from adversarial_market_defense_stage24 import MarketEvidence, PriceObservation, evaluate_adversarial_defense

APP_DIR = Path(os.getenv("CHOBYAR_APP_DIR", "/opt/chobyar-trader"))
STATE_PATH = APP_DIR / "state" / "live_adversarial_stage26.json"
HISTORY_PATH = APP_DIR / "state" / "live_adversarial_stage26_history.json"
SYMBOL = "BTCUSDT"
MAX_SNAPSHOT_AGE_SECONDS = 60
MIN_UNIQUE_SOURCES = 3
MIN_HISTORY_SAMPLES = 3
MAX_HISTORY_GAP_SECONDS = 45
HISTORY_LIMIT = 12
DEPTH_LEVELS = 20

GLOBAL_SOURCES = (
    ("coinbase", "https://api.exchange.coinbase.com/products/BTC-USDT/ticker"),
    ("kraken", "https://api.kraken.com/0/public/Ticker?pair=XBTUSDT"),
    ("okx", "https://www.okx.com/api/v5/market/ticker?instId=BTC-USDT"),
    ("bybit", "https://api.bybit.com/v5/market/tickers?category=spot&symbol=BTCUSDT"),
)
APPROVED_SOURCE_IDS = frozenset(source_id for source_id, _ in GLOBAL_SOURCES)


class Stage26Error(RuntimeError):
    pass


@dataclass(frozen=True)
class DepthSnapshot:
    ts: str
    local_last: float
    bid_depth: float
    ask_depth: float
    levels: dict[str, float]


def _finite_positive(value: object) -> float:
    if isinstance(value, bool):
        raise Stage26Error("numeric_value_invalid")
    try:
        out = float(value)
    except (TypeError, ValueError):
        raise Stage26Error("numeric_value_invalid") from None
    if not math.isfinite(out) or out <= 0:
        raise Stage26Error("numeric_value_invalid")
    return out


def _response_json(response: Any, reason: str) -> object:
    if getattr(response, "status_code", None) != 200:
        raise Stage26Error(f"{reason}_http_{getattr(response, 'status_code', None)}")
    try:
        return response.json()
    except Exception as exc:
        raise Stage26Error(f"{reason}_json_invalid") from exc


def _read_api_key() -> str:
    path = APP_DIR / ".env"
    try:
        lines = path.read_text().splitlines()
    except Exception as exc:
        raise Stage26Error("wallex_api_key_env_unreadable") from exc
    values: dict[str, str] = {}
    for raw in lines:
        line = raw.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        values[key.strip()] = value.strip()
    for name in ("WALLEX_API_KEY", "API_KEY", "WALLEX_KEY"):
        value = values.get(name, "").strip()
        if value:
            return value
    raise Stage26Error("wallex_api_key_missing")


def _parse_global(source_id: str, payload: object) -> float:
    if source_id == "coinbase":
        if not isinstance(payload, Mapping):
            raise Stage26Error("coinbase_schema_invalid")
        return _finite_positive(payload.get("price"))
    if source_id == "kraken":
        if not isinstance(payload, Mapping) or payload.get("error") not in ([], None):
            raise Stage26Error("kraken_schema_invalid")
        result = payload.get("result")
        if not isinstance(result, Mapping) or not result:
            raise Stage26Error("kraken_schema_invalid")
        row = next(iter(result.values()))
        if not isinstance(row, Mapping) or not isinstance(row.get("c"), list) or not row["c"]:
            raise Stage26Error("kraken_schema_invalid")
        return _finite_positive(row["c"][0])
    if source_id == "okx":
        if not isinstance(payload, Mapping) or str(payload.get("code")) != "0":
            raise Stage26Error("okx_schema_invalid")
        data = payload.get("data")
        if not isinstance(data, list) or len(data) != 1 or not isinstance(data[0], Mapping):
            raise Stage26Error("okx_schema_invalid")
        return _finite_positive(data[0].get("last"))
    if source_id == "bybit":
        if not isinstance(payload, Mapping) or int(payload.get("retCode", -1)) != 0:
            raise Stage26Error("bybit_schema_invalid")
        result = payload.get("result")
        rows = result.get("list") if isinstance(result, Mapping) else None
        if not isinstance(rows, list) or len(rows) != 1 or not isinstance(rows[0], Mapping):
            raise Stage26Error("bybit_schema_invalid")
        return _finite_positive(rows[0].get("lastPrice"))
    raise Stage26Error("unknown_source")


def collect_global_prices(client: Any) -> tuple[list[PriceObservation], list[str]]:
    observations: list[PriceObservation] = []
    errors: list[str] = []
    for source_id, url in GLOBAL_SOURCES:
        try:
            payload = _response_json(client.get(url, headers={"Accept": "application/json"}), source_id)
            observations.append(PriceObservation(source_id, _parse_global(source_id, payload)))
        except Exception as exc:
            errors.append(f"{source_id}:{type(exc).__name__}")
    return observations, errors


def _wallex_headers(api_key: str) -> dict[str, str]:
    return {"Accept": "application/json", "X-API-Key": api_key}


def _wallex_market(client: Any, api_key: str) -> tuple[float, float, float]:
    payload = _response_json(
        client.get("https://api.wallex.ir/v1/markets", headers=_wallex_headers(api_key)),
        "wallex_markets",
    )
    if not isinstance(payload, Mapping) or payload.get("success") is not True:
        raise Stage26Error("wallex_markets_rejected")
    result = payload.get("result")
    symbols = result.get("symbols") if isinstance(result, Mapping) else None
    row = symbols.get(SYMBOL) if isinstance(symbols, Mapping) else None
    stats = row.get("stats") if isinstance(row, Mapping) else None
    if not isinstance(stats, Mapping):
        raise Stage26Error("wallex_market_schema_invalid")
    return (
        _finite_positive(stats.get("bidPrice")),
        _finite_positive(stats.get("askPrice")),
        _finite_positive(stats.get("lastPrice")),
    )


def _wallex_depth(client: Any, api_key: str) -> tuple[float, float, dict[str, float]]:
    payload = _response_json(
        client.get(
            "https://api.wallex.ir/v1/depth",
            params={"symbol": SYMBOL},
            headers=_wallex_headers(api_key),
        ),
        "wallex_depth",
    )
    if not isinstance(payload, Mapping) or payload.get("success") is not True:
        raise Stage26Error("wallex_depth_rejected")
    result = payload.get("result")
    if not isinstance(result, Mapping):
        raise Stage26Error("wallex_depth_schema_invalid")
    bids = result.get("bid")
    asks = result.get("ask")
    if not isinstance(bids, list) or not isinstance(asks, list):
        raise Stage26Error("wallex_depth_schema_invalid")
    levels: dict[str, float] = {}
    bid_depth = 0.0
    ask_depth = 0.0
    for side, rows in (("b", bids[:DEPTH_LEVELS]), ("a", asks[:DEPTH_LEVELS])):
        for row in rows:
            if not isinstance(row, Mapping):
                continue
            p = _finite_positive(row.get("price"))
            q = _finite_positive(row.get("quantity"))
            notional = p * q
            levels[f"{side}:{p:.8f}"] = notional
            if side == "b":
                bid_depth += notional
            else:
                ask_depth += notional
    if bid_depth <= 0 or ask_depth <= 0:
        raise Stage26Error("wallex_depth_empty")
    return bid_depth, ask_depth, levels


def _load_history(path: Path) -> list[DepthSnapshot]:
    try:
        raw = json.loads(path.read_text())
    except FileNotFoundError:
        return []
    except Exception as exc:
        raise Stage26Error("history_invalid") from exc
    if not isinstance(raw, list):
        raise Stage26Error("history_invalid")
    out: list[DepthSnapshot] = []
    for item in raw[-HISTORY_LIMIT:]:
        if not isinstance(item, Mapping):
            raise Stage26Error("history_invalid")
        try:
            snap = DepthSnapshot(
                str(item["ts"]),
                _finite_positive(item["local_last"]),
                _finite_positive(item["bid_depth"]),
                _finite_positive(item["ask_depth"]),
                {str(k): _finite_positive(v) for k, v in dict(item["levels"]).items()},
            )
            datetime.fromisoformat(snap.ts)
        except Exception as exc:
            raise Stage26Error("history_invalid") from exc
        out.append(snap)
    return out


def _atomic_json(path: Path, payload: object) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    fd, tmp = tempfile.mkstemp(prefix=".stage26-", dir=str(path.parent), text=True)
    try:
        with os.fdopen(fd, "w") as handle:
            json.dump(payload, handle, sort_keys=True, separators=(",", ":"))
            handle.flush()
            os.fsync(handle.fileno())
        os.chmod(tmp, 0o600)
        os.replace(tmp, path)
        os.chmod(path, 0o600)
    finally:
        if os.path.exists(tmp):
            os.unlink(tmp)


def _snapshot_time(snapshot: DepthSnapshot) -> datetime:
    try:
        dt = datetime.fromisoformat(snapshot.ts)
    except Exception as exc:
        raise Stage26Error("history_timestamp_invalid") from exc
    if dt.tzinfo is None:
        raise Stage26Error("history_timestamp_invalid")
    return dt.astimezone(timezone.utc)


def _consecutive_history(history: list[DepthSnapshot], current_time: datetime) -> list[DepthSnapshot]:
    if not history:
        return []
    ordered = history[-(MIN_HISTORY_SAMPLES - 1):]
    prev_time: datetime | None = None
    for snap in ordered:
        ts = _snapshot_time(snap)
        if prev_time is not None:
            gap = (ts - prev_time).total_seconds()
            if gap <= 0 or gap > MAX_HISTORY_GAP_SECONDS:
                return []
        prev_time = ts
    if prev_time is None:
        return []
    final_gap = (current_time - prev_time).total_seconds()
    if final_gap <= 0 or final_gap > MAX_HISTORY_GAP_SECONDS:
        return []
    return ordered


def _history_metrics(previous: DepthSnapshot | None, current: DepthSnapshot) -> tuple[float | None, float | None]:
    if previous is None:
        return None, None
    prev_total = sum(previous.levels.values())
    disappeared = sum(max(value - current.levels.get(key, 0.0), 0.0) for key, value in previous.levels.items())
    disappearance_ratio = min(1.0, disappeared / prev_total) if prev_total > 0 else None
    prev_imb = (previous.bid_depth - previous.ask_depth) / (previous.bid_depth + previous.ask_depth)
    cur_imb = (current.bid_depth - current.ask_depth) / (current.bid_depth + current.ask_depth)
    depth_flip = min(1.0, abs(cur_imb - prev_imb) / 2.0)
    return disappearance_ratio, depth_flip


def collect_once(
    client: Any,
    *,
    state_path: Path = STATE_PATH,
    history_path: Path = HISTORY_PATH,
    now: datetime | None = None,
    api_key: str | None = None,
) -> dict[str, object]:
    current_time = (now or datetime.now(timezone.utc)).astimezone(timezone.utc)
    try:
        key = api_key or _read_api_key()
        bid, ask, last = _wallex_market(client, key)
        bid_depth, ask_depth, levels = _wallex_depth(client, key)
        history = _load_history(history_path)
        consecutive = _consecutive_history(history, current_time)
        current = DepthSnapshot(current_time.isoformat(), last, bid_depth, ask_depth, levels)
        previous = consecutive[-1] if consecutive else None
        disappearance_ratio, depth_flip_ratio = _history_metrics(previous, current)
        observations, source_errors = collect_global_prices(client)
        evidence = MarketEvidence(
            local_bid=bid,
            local_ask=ask,
            local_last=last,
            global_prices=observations,
            previous_local_last=(previous.local_last if previous else None),
            cancel_ratio=disappearance_ratio,
            depth_flip_ratio=depth_flip_ratio,
            trade_to_quote_ratio=None,
        )
        decision = evaluate_adversarial_defense(market=evidence)
        if len(consecutive) + 1 < MIN_HISTORY_SAMPLES:
            allowed = False
            reason = "history_warmup"
            flags = sorted(set(decision.flags) | {"history_warmup"})
            risk_score = 1.0
            history_to_store = [current] if not consecutive else consecutive + [current]
        else:
            allowed = decision.allowed
            reason = decision.reason
            flags = list(decision.flags)
            risk_score = decision.risk_score
            history_to_store = (history + [current])[-HISTORY_LIMIT:]
        _atomic_json(history_path, [asdict(x) for x in history_to_store])
        payload: dict[str, object] = {
            "version": 1,
            "scope": "BTCUSDT_STAGE26_ADVERSARIAL_VETO",
            "symbol": SYMBOL,
            "observed_at": current_time.isoformat(),
            "allowed": allowed,
            "reason": reason,
            "flags": flags,
            "risk_score": risk_score,
            "price_quorum": decision.price_quorum,
            "source_ids": sorted({x.source_id.lower() for x in observations}),
            "source_errors": source_errors,
            "local_bid": bid,
            "local_ask": ask,
            "local_last": last,
            "orderbook_disappearance_ratio": disappearance_ratio,
            "depth_flip_ratio": depth_flip_ratio,
            "history_samples": len(history_to_store),
        }
    except Exception as exc:
        payload = {
            "version": 1,
            "scope": "BTCUSDT_STAGE26_ADVERSARIAL_VETO",
            "symbol": SYMBOL,
            "observed_at": current_time.isoformat(),
            "allowed": False,
            "reason": "collector_error",
            "flags": ["collector_error"],
            "risk_score": 1.0,
            "price_quorum": 0,
            "source_ids": [],
            "source_errors": [type(exc).__name__],
            "history_samples": 0,
        }
    _atomic_json(state_path, payload)
    return payload


def enforce_live_buy_veto(*, state_path: Path = STATE_PATH, now: datetime | None = None) -> Mapping[str, object]:
    try:
        payload = json.loads(state_path.read_text())
    except FileNotFoundError as exc:
        raise Stage26Error("stage26_snapshot_missing") from exc
    except Exception as exc:
        raise Stage26Error("stage26_snapshot_invalid") from exc
    if not isinstance(payload, Mapping):
        raise Stage26Error("stage26_snapshot_invalid")
    if payload.get("version") != 1:
        raise Stage26Error("stage26_version_mismatch")
    if payload.get("scope") != "BTCUSDT_STAGE26_ADVERSARIAL_VETO" or payload.get("symbol") != SYMBOL:
        raise Stage26Error("stage26_scope_mismatch")
    try:
        observed = datetime.fromisoformat(str(payload.get("observed_at")))
    except Exception as exc:
        raise Stage26Error("stage26_timestamp_invalid") from exc
    if observed.tzinfo is None:
        raise Stage26Error("stage26_timestamp_invalid")
    age = ((now or datetime.now(timezone.utc)).astimezone(timezone.utc) - observed.astimezone(timezone.utc)).total_seconds()
    if age < -5 or age > MAX_SNAPSHOT_AGE_SECONDS:
        raise Stage26Error("stage26_snapshot_stale")
    source_ids = payload.get("source_ids")
    if not isinstance(source_ids, list):
        raise Stage26Error("stage26_sources_invalid")
    normalized = [str(x).strip().lower() for x in source_ids if str(x).strip()]
    if len(normalized) != len(source_ids):
        raise Stage26Error("stage26_sources_invalid")
    if len(normalized) != len(set(normalized)):
        raise Stage26Error("stage26_duplicate_sources")
    if not set(normalized).issubset(APPROVED_SOURCE_IDS):
        raise Stage26Error("stage26_unapproved_source")
    try:
        price_quorum = int(payload.get("price_quorum"))
        history_samples = int(payload.get("history_samples"))
    except (TypeError, ValueError) as exc:
        raise Stage26Error("stage26_snapshot_invalid") from exc
    if price_quorum != len(normalized):
        raise Stage26Error("stage26_quorum_source_mismatch")
    if price_quorum < MIN_UNIQUE_SOURCES:
        raise Stage26Error("stage26_quorum_insufficient")
    if history_samples < MIN_HISTORY_SAMPLES:
        raise Stage26Error("stage26_history_not_ready")
    if payload.get("allowed") is not True:
        raise Stage26Error(f"stage26_blocked_{str(payload.get('reason') or 'unknown')}")
    return payload


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Read-only Stage26 multi-source adversarial market veto")
    parser.add_argument("--collect", action="store_true")
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args(argv)
    if args.collect == args.check:
        raise SystemExit("FAIL-CLOSED: choose exactly one of --collect/--check")
    if args.collect:
        try:
            import httpx
        except ImportError as exc:
            raise SystemExit("FAIL-CLOSED: httpx unavailable") from exc
        with httpx.Client(timeout=8.0, follow_redirects=False) as client:
            out = collect_once(client)
        print(f"STAGE26_ALLOWED={str(out.get('allowed')).upper()}")
        print(f"STAGE26_REASON={out.get('reason')}")
        print(f"PRICE_QUORUM={out.get('price_quorum')}")
        print(f"HISTORY_SAMPLES={out.get('history_samples')}")
        return 0
    try:
        out = enforce_live_buy_veto()
    except Stage26Error as exc:
        raise SystemExit(f"FAIL-CLOSED: {exc}") from None
    print("STAGE26_LIVE_BUY_VETO=PASS")
    print(f"PRICE_QUORUM={out['price_quorum']}")
    print(f"SOURCE_IDS={','.join(out['source_ids'])}")
    print(f"HISTORY_SAMPLES={out['history_samples']}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

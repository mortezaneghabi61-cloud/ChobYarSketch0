from __future__ import annotations

from dataclasses import dataclass
from decimal import Decimal, InvalidOperation
from typing import Mapping, Protocol

from live_safety import evaluate_live_safety
from order_dryrun import MarketRules, OrderIntent, DryRunResult, evaluate_order_dry_run

MARKETS_PATH = "/v1/markets"


class GetClient(Protocol):
    def get(self, path: str): ...


@dataclass(frozen=True)
class MarketMetadata:
    symbol: str
    quantity_step: Decimal
    price_tick: Decimal
    min_notional_tmn: Decimal
    market_max_notional_tmn: Decimal | None


@dataclass(frozen=True)
class Stage4Result:
    metadata: MarketMetadata
    dry_run: DryRunResult


def _decimal(value: object) -> Decimal | None:
    try:
        result = Decimal(str(value))
    except (InvalidOperation, TypeError, ValueError):
        return None
    return result if result.is_finite() else None


def _power10_precision(value: object) -> Decimal | None:
    try:
        places = int(str(value))
    except (TypeError, ValueError):
        return None
    if places < 0 or places > 18:
        return None
    return Decimal(1).scaleb(-places)


def parse_market_metadata(payload: object, symbol: str) -> MarketMetadata:
    wanted = (symbol or "").strip().upper()
    if not wanted or not isinstance(payload, Mapping):
        raise RuntimeError("market_metadata_invalid_payload")
    if payload.get("success") is not True:
        raise RuntimeError("market_metadata_api_failed")

    result = payload.get("result")
    if not isinstance(result, Mapping):
        raise RuntimeError("market_metadata_missing_result")
    symbols = result.get("symbols")
    if not isinstance(symbols, Mapping):
        raise RuntimeError("market_metadata_missing_symbols")
    raw = symbols.get(wanted)
    if not isinstance(raw, Mapping):
        raise RuntimeError("market_metadata_symbol_missing")

    returned_symbol = str(raw.get("symbol") or "").strip().upper()
    if returned_symbol != wanted:
        raise RuntimeError("market_metadata_symbol_mismatch")

    quantity_step = _power10_precision(raw.get("stepSize"))
    price_tick = _power10_precision(raw.get("tickSize"))
    min_notional = _decimal(raw.get("minNotional"))
    max_notional = _decimal(raw.get("maxNotional")) if raw.get("maxNotional") is not None else None

    if quantity_step is None or quantity_step <= 0:
        raise RuntimeError("market_metadata_invalid_step")
    if price_tick is None or price_tick <= 0:
        raise RuntimeError("market_metadata_invalid_tick")
    if min_notional is None or min_notional <= 0:
        raise RuntimeError("market_metadata_invalid_min_notional")
    if max_notional is not None and (max_notional <= 0 or max_notional < min_notional):
        raise RuntimeError("market_metadata_invalid_max_notional")

    return MarketMetadata(wanted, quantity_step, price_tick, min_notional, max_notional)


def fetch_market_metadata(*, env: Mapping[str, str], client: GetClient, symbol: str) -> MarketMetadata:
    safety = evaluate_live_safety(env)
    if not safety.allowed:
        raise RuntimeError(f"stage1_blocked:{safety.reason}")
    response = client.get(MARKETS_PATH)
    if getattr(response, "status_code", None) != 200:
        raise RuntimeError("market_metadata_http_failed")
    try:
        payload = response.json()
    except Exception as exc:
        raise RuntimeError("market_metadata_json_failed") from exc
    return parse_market_metadata(payload, symbol)


def validate_with_live_metadata(
    *,
    env: Mapping[str, str],
    client: GetClient,
    intent: OrderIntent,
) -> Stage4Result:
    """Read-only Stage-4: GET public market metadata, then local Stage-3 validation only."""
    metadata = fetch_market_metadata(env=env, client=client, symbol=intent.symbol)
    safety = evaluate_live_safety(env)
    if not safety.allowed:
        raise RuntimeError(f"stage1_blocked:{safety.reason}")

    local_cap = Decimal(safety.max_order_tmn)
    effective_max = metadata.market_max_notional_tmn
    if effective_max is None or effective_max > local_cap:
        effective_max = local_cap

    rules = MarketRules(
        symbol=metadata.symbol,
        quantity_step=metadata.quantity_step,
        price_tick=metadata.price_tick,
        min_notional_tmn=metadata.min_notional_tmn,
        max_notional_tmn=effective_max,
    )
    dry_run = evaluate_order_dry_run(env=env, intent=intent, rules=rules)
    return Stage4Result(metadata=metadata, dry_run=dry_run)

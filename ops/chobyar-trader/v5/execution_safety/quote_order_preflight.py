from __future__ import annotations

from dataclasses import dataclass
from decimal import Decimal, InvalidOperation
from typing import Mapping, Protocol

from quote_cap import APPROVED_SYMBOL_QUOTES, QuoteCapDecision, evaluate_quote_cap
from spot_eligibility import SpotEligibility, fetch_spot_eligibility
from v5_safety_core import evaluate_v5_safety

MARKETS_PATH = "/v1/markets"


class GetClient(Protocol):
    def get(self, path: str): ...


@dataclass(frozen=True)
class QuoteMarketRules:
    symbol: str
    quote_asset: str
    quantity_step: Decimal
    price_tick: Decimal
    min_notional_quote: Decimal
    max_notional_quote: Decimal | None


@dataclass(frozen=True)
class QuoteOrderIntent:
    symbol: str
    side: str
    quantity: Decimal
    limit_price_quote: Decimal


@dataclass(frozen=True)
class QuoteOrderPreflight:
    allowed: bool
    reason: str
    live_ready: bool
    intent: QuoteOrderIntent
    rules: QuoteMarketRules
    notional_quote: Decimal
    quote_cap: QuoteCapDecision
    spot: SpotEligibility


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


def _aligned(value: Decimal, step: Decimal) -> bool:
    return step > 0 and value % step == 0


def parse_quote_market_rules(payload: object, symbol: str) -> QuoteMarketRules:
    wanted = (symbol or "").strip().upper()
    quote_asset = APPROVED_SYMBOL_QUOTES.get(wanted, "")
    if not wanted or not quote_asset:
        raise RuntimeError("symbol_quote_not_approved")
    if not isinstance(payload, Mapping) or payload.get("success") is not True:
        raise RuntimeError("quote_market_metadata_invalid_payload")

    result = payload.get("result")
    symbols = result.get("symbols") if isinstance(result, Mapping) else None
    raw = symbols.get(wanted) if isinstance(symbols, Mapping) else None
    if not isinstance(raw, Mapping):
        raise RuntimeError("quote_market_symbol_missing")
    if str(raw.get("symbol") or "").strip().upper() != wanted:
        raise RuntimeError("quote_market_symbol_mismatch")

    quantity_step = _power10_precision(raw.get("stepSize"))
    price_tick = _power10_precision(raw.get("tickSize"))
    min_notional = _decimal(raw.get("minNotional"))
    max_notional = _decimal(raw.get("maxNotional")) if raw.get("maxNotional") is not None else None
    if quantity_step is None or quantity_step <= 0:
        raise RuntimeError("quote_market_invalid_step")
    if price_tick is None or price_tick <= 0:
        raise RuntimeError("quote_market_invalid_tick")
    if min_notional is None or min_notional <= 0:
        raise RuntimeError("quote_market_invalid_min_notional")
    if max_notional is not None and (max_notional <= 0 or max_notional < min_notional):
        raise RuntimeError("quote_market_invalid_max_notional")
    return QuoteMarketRules(wanted, quote_asset, quantity_step, price_tick, min_notional, max_notional)


def fetch_quote_market_rules(*, env: Mapping[str, str], client: GetClient, symbol: str) -> QuoteMarketRules:
    safety = evaluate_v5_safety(env)
    if not safety.allowed:
        raise RuntimeError(f"v5_safety_blocked:{safety.reason}")
    response = client.get(MARKETS_PATH)
    if getattr(response, "status_code", None) != 200:
        raise RuntimeError("quote_market_metadata_http_failed")
    try:
        payload = response.json()
    except Exception as exc:
        raise RuntimeError("quote_market_metadata_json_failed") from exc
    return parse_quote_market_rules(payload, symbol)


def run_quote_order_preflight(*, env: Mapping[str, str], client: GetClient, intent: QuoteOrderIntent) -> QuoteOrderPreflight:
    """GET-only + local validation. Never grants order execution authority."""
    safety = evaluate_v5_safety(env)
    if not safety.allowed:
        raise RuntimeError(f"v5_safety_blocked:{safety.reason}")

    symbol = (intent.symbol or "").strip().upper()
    side = (intent.side or "").strip().upper()
    quantity = _decimal(intent.quantity)
    price = _decimal(intent.limit_price_quote)
    if side not in {"BUY", "SELL"}:
        raise RuntimeError("invalid_side")
    if quantity is None or quantity <= 0:
        raise RuntimeError("quantity_must_be_positive")
    if price is None or price <= 0:
        raise RuntimeError("limit_price_quote_must_be_positive")

    rules = fetch_quote_market_rules(env=env, client=client, symbol=symbol)
    if not _aligned(quantity, rules.quantity_step):
        raise RuntimeError("quantity_step_mismatch")
    if not _aligned(price, rules.price_tick):
        raise RuntimeError("price_tick_mismatch")

    notional = quantity * price
    if notional < rules.min_notional_quote:
        raise RuntimeError("below_market_min_notional_quote")
    if rules.max_notional_quote is not None and notional > rules.max_notional_quote:
        raise RuntimeError("above_market_max_notional_quote")

    cap = evaluate_quote_cap(env=env, symbol=symbol, notional_quote=notional)
    if not cap.allowed:
        raise RuntimeError(cap.reason)
    if cap.quote_asset != rules.quote_asset:
        raise RuntimeError("quote_asset_mismatch")

    spot = fetch_spot_eligibility(env=env, client=client, symbol=symbol)
    if not spot.allowed or not spot.is_spot:
        raise RuntimeError("active_spot_required")

    normalized = QuoteOrderIntent(symbol, side, quantity, price)
    return QuoteOrderPreflight(True, "quote_and_spot_preflight_validated_no_submission", False, normalized, rules, notional, cap, spot)

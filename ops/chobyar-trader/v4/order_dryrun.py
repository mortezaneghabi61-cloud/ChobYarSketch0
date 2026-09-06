from __future__ import annotations

from dataclasses import dataclass
from decimal import Decimal, InvalidOperation
from typing import Mapping

from live_safety import evaluate_live_safety


@dataclass(frozen=True)
class MarketRules:
    symbol: str
    quantity_step: Decimal
    price_tick: Decimal
    min_notional_tmn: Decimal
    max_notional_tmn: Decimal


@dataclass(frozen=True)
class OrderIntent:
    symbol: str
    side: str
    quantity: Decimal
    limit_price_tmn: Decimal


@dataclass(frozen=True)
class DryRunResult:
    allowed: bool
    reason: str
    symbol: str
    side: str
    quantity: Decimal
    limit_price_tmn: Decimal
    notional_tmn: Decimal


def _decimal(value: object) -> Decimal | None:
    try:
        result = Decimal(str(value))
    except (InvalidOperation, ValueError, TypeError):
        return None
    if not result.is_finite():
        return None
    return result


def _positive(value: Decimal | None) -> bool:
    return value is not None and value > 0


def _aligned(value: Decimal, step: Decimal) -> bool:
    if step <= 0:
        return False
    return value % step == 0


def evaluate_order_dry_run(
    *,
    env: Mapping[str, str],
    intent: OrderIntent,
    rules: MarketRules,
) -> DryRunResult:
    """Pure Stage-3 validator. Never performs network or exchange mutation."""
    safety = evaluate_live_safety(env)

    symbol = (intent.symbol or "").strip().upper()
    side = (intent.side or "").strip().upper()
    quantity = _decimal(intent.quantity) or Decimal("0")
    price = _decimal(intent.limit_price_tmn) or Decimal("0")
    notional = quantity * price

    def deny(reason: str) -> DryRunResult:
        return DryRunResult(False, reason, symbol, side, quantity, price, notional)

    if not safety.allowed:
        return deny(f"stage1_blocked:{safety.reason}")
    if env.get("LIVE_TRADING_ENABLED", "").strip().lower() != "false":
        return deny("live_execution_must_remain_disabled")
    if env.get("LIVE_DRY_RUN", "").strip().lower() != "true":
        return deny("dry_run_required")

    rule_symbol = (rules.symbol or "").strip().upper()
    if not symbol or symbol != rule_symbol:
        return deny("symbol_not_approved")
    if side not in {"BUY", "SELL"}:
        return deny("invalid_side")

    q_step = _decimal(rules.quantity_step)
    p_tick = _decimal(rules.price_tick)
    min_notional = _decimal(rules.min_notional_tmn)
    max_notional = _decimal(rules.max_notional_tmn)
    if not all(_positive(x) for x in (q_step, p_tick, min_notional, max_notional)):
        return deny("market_rules_invalid")
    assert q_step is not None and p_tick is not None
    assert min_notional is not None and max_notional is not None
    if min_notional > max_notional:
        return deny("market_rules_invalid")

    if not _positive(quantity):
        return deny("quantity_must_be_positive")
    if not _positive(price):
        return deny("limit_price_must_be_positive")
    if not _aligned(quantity, q_step):
        return deny("quantity_step_mismatch")
    if not _aligned(price, p_tick):
        return deny("price_tick_mismatch")
    if notional < min_notional:
        return deny("below_market_min_notional")
    if notional > max_notional:
        return deny("above_market_max_notional")

    stage_cap = Decimal(safety.max_order_tmn)
    if notional > stage_cap:
        return deny("above_stage_cap")

    return DryRunResult(True, "stage3_dry_run_validated_no_submission", symbol, side, quantity, price, notional)

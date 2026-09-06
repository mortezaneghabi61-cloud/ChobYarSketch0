from __future__ import annotations

from dataclasses import dataclass
from decimal import Decimal, InvalidOperation

APPROVED_SYMBOL = "BTCUSDT"
APPROVED_QUOTE_ASSET = "USDT"
APPROVED_MAX_ORDER_USDT = Decimal("10")
APPROVED_MAX_POSITION_PCT = Decimal("0.25")
APPROVED_STOP_LOSS_PCT = Decimal("0.015")
APPROVED_TAKE_PROFIT_PCT = Decimal("0.03")
APPROVED_MAX_DAILY_LOSS_PCT = Decimal("0.03")


@dataclass(frozen=True)
class LiveGuardStage18Request:
    symbol: str
    quote_asset: str
    order_notional_quote: str
    stage17_preflight_passed: bool
    read_permission_enabled: bool
    trade_permission_enabled: bool
    withdrawal_permission_enabled: bool
    exact_ip_allowlist: bool
    leverage_enabled: bool
    margin_enabled: bool
    futures_enabled: bool
    otc_enabled: bool
    max_position_pct: str
    stop_loss_pct: str
    take_profit_pct: str
    max_daily_loss_pct: str


@dataclass(frozen=True)
class LiveGuardStage18Decision:
    allowed: bool
    reason: str
    approved_symbol: str
    approved_quote_asset: str
    approved_max_order_usdt: Decimal
    pre_submit_guard_passed: bool
    ready_for_separate_manual_live_activation_stage: bool
    live_ready: bool
    execution_authority: bool


def _decimal(value: str) -> Decimal | None:
    try:
        parsed = Decimal((value or "").strip())
    except (InvalidOperation, ValueError):
        return None
    if not parsed.is_finite():
        return None
    return parsed


def evaluate_live_guard_stage18(request: LiveGuardStage18Request) -> LiveGuardStage18Decision:
    """Fail-closed pre-submit live Spot guard.

    This stage validates the boundary that a future live executor would have to satisfy.
    It does not create execution authority, submit/cancel orders, modify exchange
    permissions, change .env, or start/stop services.
    """

    def blocked(reason: str) -> LiveGuardStage18Decision:
        return LiveGuardStage18Decision(
            allowed=False,
            reason=reason,
            approved_symbol=APPROVED_SYMBOL,
            approved_quote_asset=APPROVED_QUOTE_ASSET,
            approved_max_order_usdt=APPROVED_MAX_ORDER_USDT,
            pre_submit_guard_passed=False,
            ready_for_separate_manual_live_activation_stage=False,
            live_ready=False,
            execution_authority=False,
        )

    symbol = (request.symbol or "").strip().upper()
    quote_asset = (request.quote_asset or "").strip().upper()
    if not request.stage17_preflight_passed:
        return blocked("stage17_preflight_required")
    if symbol != APPROVED_SYMBOL or quote_asset != APPROVED_QUOTE_ASSET:
        return blocked("only_btcusdt_usdt_spot_is_approved")
    if not request.read_permission_enabled or not request.trade_permission_enabled:
        return blocked("read_and_trade_permissions_required")
    if request.withdrawal_permission_enabled:
        return blocked("withdrawal_permission_must_remain_off")
    if not request.exact_ip_allowlist:
        return blocked("single_expected_ip_allowlist_required")
    if request.leverage_enabled or request.margin_enabled or request.futures_enabled or request.otc_enabled:
        return blocked("non_spot_authority_must_remain_disabled")

    notional = _decimal(request.order_notional_quote)
    if notional is None or notional <= 0:
        return blocked("order_notional_invalid")
    if notional > APPROVED_MAX_ORDER_USDT:
        return blocked("order_notional_exceeds_approved_10_usdt_cap")

    risk = (
        _decimal(request.max_position_pct),
        _decimal(request.stop_loss_pct),
        _decimal(request.take_profit_pct),
        _decimal(request.max_daily_loss_pct),
    )
    if risk != (
        APPROVED_MAX_POSITION_PCT,
        APPROVED_STOP_LOSS_PCT,
        APPROVED_TAKE_PROFIT_PCT,
        APPROVED_MAX_DAILY_LOSS_PCT,
    ):
        return blocked("approved_risk_profile_mismatch")

    return LiveGuardStage18Decision(
        allowed=True,
        reason="stage18_pre_submit_guard_passed_no_execution_authority",
        approved_symbol=APPROVED_SYMBOL,
        approved_quote_asset=APPROVED_QUOTE_ASSET,
        approved_max_order_usdt=APPROVED_MAX_ORDER_USDT,
        pre_submit_guard_passed=True,
        ready_for_separate_manual_live_activation_stage=True,
        live_ready=False,
        execution_authority=False,
    )

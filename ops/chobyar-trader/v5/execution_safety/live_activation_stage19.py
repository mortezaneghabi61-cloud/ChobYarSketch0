from __future__ import annotations

from dataclasses import dataclass
from decimal import Decimal, InvalidOperation

APPROVED_SYMBOL = "BTCUSDT"
APPROVED_QUOTE_ASSET = "USDT"
APPROVED_MAX_ORDER_USDT = Decimal("10")
APPROVED_RISK = (
    Decimal("0.25"),
    Decimal("0.015"),
    Decimal("0.03"),
    Decimal("0.03"),
)


@dataclass(frozen=True)
class LiveActivationStage19Request:
    stage18_guard_passed: bool
    symbol: str
    quote_asset: str
    approved_max_order_usdt: str
    explicit_operator_confirmation: bool
    current_trading_mode: str
    current_live_trading_enabled: str
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
    trader_service_active: bool
    status_service_active: bool
    shadow_timer_active: bool
    open_orders_count: int


@dataclass(frozen=True)
class LiveActivationStage19Decision:
    allowed: bool
    reason: str
    approved_symbol: str
    approved_quote_asset: str
    approved_max_order_usdt: Decimal
    ready_for_operator_live_env_transition: bool
    live_ready: bool
    execution_authority: bool
    order_submission_authority: bool


def _decimal(value: str) -> Decimal | None:
    try:
        parsed = Decimal((value or "").strip())
    except (InvalidOperation, ValueError):
        return None
    if not parsed.is_finite():
        return None
    return parsed


def evaluate_live_activation_stage19(
    request: LiveActivationStage19Request,
) -> LiveActivationStage19Decision:
    """Validate the operator-controlled live activation boundary without activating live.

    Stage 19 deliberately grants no execution or order-submission authority. It only
    proves that the current Paper runtime and exchange posture are safe enough for a
    separate, explicit operator transition after a live-capable runtime exists and is
    independently reviewed.
    """

    def blocked(reason: str) -> LiveActivationStage19Decision:
        return LiveActivationStage19Decision(
            allowed=False,
            reason=reason,
            approved_symbol=APPROVED_SYMBOL,
            approved_quote_asset=APPROVED_QUOTE_ASSET,
            approved_max_order_usdt=APPROVED_MAX_ORDER_USDT,
            ready_for_operator_live_env_transition=False,
            live_ready=False,
            execution_authority=False,
            order_submission_authority=False,
        )

    if not request.stage18_guard_passed:
        return blocked("stage18_guard_required")

    if (request.symbol or "").strip().upper() != APPROVED_SYMBOL:
        return blocked("only_btcusdt_is_approved")
    if (request.quote_asset or "").strip().upper() != APPROVED_QUOTE_ASSET:
        return blocked("only_usdt_quote_is_approved")

    cap = _decimal(request.approved_max_order_usdt)
    if cap != APPROVED_MAX_ORDER_USDT:
        return blocked("approved_live_cap_must_equal_10_usdt")

    if not request.explicit_operator_confirmation:
        return blocked("explicit_operator_confirmation_required")

    # This boundary must be evaluated while the existing runtime is still safely locked.
    if (request.current_trading_mode or "").strip().lower() != "paper":
        return blocked("current_runtime_must_still_be_paper")
    if (request.current_live_trading_enabled or "").strip().lower() != "false":
        return blocked("current_live_gate_must_still_be_false")

    if not request.read_permission_enabled or not request.trade_permission_enabled:
        return blocked("read_and_trade_permissions_required")
    if request.withdrawal_permission_enabled:
        return blocked("withdrawal_permission_must_remain_off")
    if not request.exact_ip_allowlist:
        return blocked("single_expected_ip_allowlist_required")
    if request.leverage_enabled or request.margin_enabled or request.futures_enabled or request.otc_enabled:
        return blocked("non_spot_authority_must_remain_disabled")

    risk = (
        _decimal(request.max_position_pct),
        _decimal(request.stop_loss_pct),
        _decimal(request.take_profit_pct),
        _decimal(request.max_daily_loss_pct),
    )
    if risk != APPROVED_RISK:
        return blocked("approved_risk_profile_mismatch")

    if not request.trader_service_active:
        return blocked("trader_service_must_be_active")
    if not request.status_service_active:
        return blocked("status_service_must_be_active")
    if not request.shadow_timer_active:
        return blocked("shadow_timer_must_be_active")

    if isinstance(request.open_orders_count, bool) or not isinstance(request.open_orders_count, int):
        return blocked("open_orders_count_invalid")
    if request.open_orders_count != 0:
        return blocked("open_orders_must_be_zero_before_transition")

    return LiveActivationStage19Decision(
        allowed=True,
        reason="stage19_operator_transition_boundary_passed_no_execution_authority",
        approved_symbol=APPROVED_SYMBOL,
        approved_quote_asset=APPROVED_QUOTE_ASSET,
        approved_max_order_usdt=APPROVED_MAX_ORDER_USDT,
        ready_for_operator_live_env_transition=True,
        live_ready=False,
        execution_authority=False,
        order_submission_authority=False,
    )

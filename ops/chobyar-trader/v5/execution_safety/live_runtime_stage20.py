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
class LiveRuntimeStage20Request:
    stage19_transition_passed: bool
    explicit_operator_confirmation: bool
    symbol: str
    quote_asset: str
    max_order_usdt: str
    trading_mode: str
    live_trading_enabled: str
    live_execution_armed: str
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
    open_orders_count: int


@dataclass(frozen=True)
class LiveRuntimeStage20Decision:
    allowed: bool
    reason: str
    live_observation_mode: bool
    order_submission_authority: bool
    execution_authority: bool
    approved_max_order_usdt: Decimal


def _decimal(value: str) -> Decimal | None:
    try:
        parsed = Decimal((value or "").strip())
    except (InvalidOperation, ValueError):
        return None
    if not parsed.is_finite():
        return None
    return parsed


def evaluate_live_runtime_stage20(
    request: LiveRuntimeStage20Request,
) -> LiveRuntimeStage20Decision:
    """Validate a disarmed live-observation runtime.

    Stage 20 permits the process to observe the live account only after the Stage-19
    boundary has passed. It deliberately refuses all order-submission authority.
    No HTTP mutation is performed here and no exchange endpoint is referenced.
    """

    def blocked(reason: str) -> LiveRuntimeStage20Decision:
        return LiveRuntimeStage20Decision(
            allowed=False,
            reason=reason,
            live_observation_mode=False,
            order_submission_authority=False,
            execution_authority=False,
            approved_max_order_usdt=APPROVED_MAX_ORDER_USDT,
        )

    if not request.stage19_transition_passed:
        return blocked("stage19_transition_required")
    if not request.explicit_operator_confirmation:
        return blocked("explicit_operator_confirmation_required")
    if (request.symbol or "").strip().upper() != APPROVED_SYMBOL:
        return blocked("only_btcusdt_is_approved")
    if (request.quote_asset or "").strip().upper() != APPROVED_QUOTE_ASSET:
        return blocked("only_usdt_quote_is_approved")
    if _decimal(request.max_order_usdt) != APPROVED_MAX_ORDER_USDT:
        return blocked("approved_live_cap_must_equal_10_usdt")

    if (request.trading_mode or "").strip().lower() != "live":
        return blocked("live_observation_requires_trading_mode_live")
    if (request.live_trading_enabled or "").strip().lower() != "true":
        return blocked("live_observation_requires_live_gate_true")
    if (request.live_execution_armed or "").strip().lower() != "false":
        return blocked("stage20_requires_execution_arm_false")

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

    if isinstance(request.open_orders_count, bool) or not isinstance(request.open_orders_count, int):
        return blocked("open_orders_count_invalid")
    if request.open_orders_count != 0:
        return blocked("open_orders_must_be_zero")

    return LiveRuntimeStage20Decision(
        allowed=True,
        reason="stage20_live_observation_ready_execution_disarmed",
        live_observation_mode=True,
        order_submission_authority=False,
        execution_authority=False,
        approved_max_order_usdt=APPROVED_MAX_ORDER_USDT,
    )

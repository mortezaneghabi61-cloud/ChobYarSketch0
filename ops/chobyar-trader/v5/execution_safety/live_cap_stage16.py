from __future__ import annotations

from dataclasses import dataclass
from decimal import Decimal, InvalidOperation

APPROVED_SYMBOL = "BTCUSDT"
APPROVED_QUOTE_ASSET = "USDT"
OPERATOR_APPROVED_MAX_ORDER_USDT = Decimal("10")


@dataclass(frozen=True)
class LiveCapStage16Request:
    symbol: str
    quote_asset: str
    requested_max_order_quote: str
    stage15_allowed: bool
    trade_permission_enabled: bool
    withdrawal_permission_enabled: bool
    live_trading_enabled: bool
    exact_ip_allowlist: bool
    leverage_enabled: bool
    margin_enabled: bool
    futures_enabled: bool
    otc_enabled: bool


@dataclass(frozen=True)
class LiveCapStage16Decision:
    allowed: bool
    reason: str
    approved_max_order_usdt: Decimal
    ready_for_manual_trade_permission_change: bool
    ready_to_change_live_env: bool
    live_ready: bool
    execution_authority: bool


def evaluate_live_cap_stage16(request: LiveCapStage16Request) -> LiveCapStage16Decision:
    """Validate the operator-approved 10 USDT live-order cap without arming live trading.

    Stage-16 records and enforces the explicit operator approval boundary. It does not
    mutate exchange permissions, environment variables, services, balances, or orders.
    """

    def blocked(reason: str) -> LiveCapStage16Decision:
        return LiveCapStage16Decision(
            allowed=False,
            reason=reason,
            approved_max_order_usdt=OPERATOR_APPROVED_MAX_ORDER_USDT,
            ready_for_manual_trade_permission_change=False,
            ready_to_change_live_env=False,
            live_ready=False,
            execution_authority=False,
        )

    symbol = (request.symbol or "").strip().upper()
    quote_asset = (request.quote_asset or "").strip().upper()

    if not request.stage15_allowed:
        return blocked("stage15_manual_arm_required")
    if symbol != APPROVED_SYMBOL or quote_asset != APPROVED_QUOTE_ASSET:
        return blocked("stage16_only_btcusdt_usdt_allowed")
    if request.trade_permission_enabled:
        return blocked("trade_permission_must_remain_off_during_stage16")
    if request.withdrawal_permission_enabled:
        return blocked("withdrawal_permission_must_remain_off")
    if request.live_trading_enabled:
        return blocked("live_trading_must_remain_disabled_during_stage16")
    if not request.exact_ip_allowlist:
        return blocked("single_expected_ip_allowlist_required")
    if request.leverage_enabled or request.margin_enabled or request.futures_enabled or request.otc_enabled:
        return blocked("non_spot_authority_must_remain_disabled")

    try:
        requested = Decimal((request.requested_max_order_quote or "").strip())
    except (InvalidOperation, ValueError):
        return blocked("requested_quote_cap_invalid")
    if not requested.is_finite() or requested <= 0:
        return blocked("requested_quote_cap_invalid")
    if requested != OPERATOR_APPROVED_MAX_ORDER_USDT:
        return blocked("requested_quote_cap_must_equal_operator_approved_10_usdt")

    return LiveCapStage16Decision(
        allowed=True,
        reason="stage16_operator_approved_10_usdt_cap_confirmed_no_execution_authority",
        approved_max_order_usdt=OPERATOR_APPROVED_MAX_ORDER_USDT,
        ready_for_manual_trade_permission_change=True,
        ready_to_change_live_env=False,
        live_ready=False,
        execution_authority=False,
    )

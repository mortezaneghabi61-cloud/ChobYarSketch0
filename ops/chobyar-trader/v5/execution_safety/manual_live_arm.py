from __future__ import annotations

from dataclasses import dataclass
from decimal import Decimal, InvalidOperation


@dataclass(frozen=True)
class ManualLiveArmRequest:
    symbol: str
    quote_asset: str
    approved_max_order_quote: str
    explicit_confirmation: bool
    final_readiness_passed: bool
    read_permission_enabled: bool
    trade_permission_enabled: bool
    withdrawal_permission_enabled: bool
    exact_ip_allowlist: bool
    leverage_enabled: bool
    margin_enabled: bool
    futures_enabled: bool
    otc_enabled: bool


@dataclass(frozen=True)
class ManualLiveArmDecision:
    allowed: bool
    reason: str
    symbol: str
    quote_asset: str
    approved_max_order_quote: Decimal | None
    ready_to_request_manual_trade_permission: bool
    ready_to_change_live_env: bool
    live_ready: bool
    execution_authority: bool


def evaluate_manual_live_arm_request(request: ManualLiveArmRequest) -> ManualLiveArmDecision:
    """Stage-15 manual live-arm preparation boundary.

    This function never changes exchange permissions, environment variables, services,
    or orders. A successful decision means only that the operator may proceed to a
    separate manual permission/configuration step. It never grants execution authority.
    """
    symbol = (request.symbol or "").strip().upper()
    quote_asset = (request.quote_asset or "").strip().upper()

    def blocked(reason: str) -> ManualLiveArmDecision:
        return ManualLiveArmDecision(
            allowed=False,
            reason=reason,
            symbol=symbol,
            quote_asset=quote_asset,
            approved_max_order_quote=None,
            ready_to_request_manual_trade_permission=False,
            ready_to_change_live_env=False,
            live_ready=False,
            execution_authority=False,
        )

    if not request.final_readiness_passed:
        return blocked("final_readiness_required")
    if symbol != "BTCUSDT" or quote_asset != "USDT":
        return blocked("only_btcusdt_usdt_is_approved_for_stage15")
    if not request.read_permission_enabled:
        return blocked("read_permission_required")
    if request.trade_permission_enabled:
        return blocked("trade_permission_must_still_be_off_before_manual_arm")
    if request.withdrawal_permission_enabled:
        return blocked("withdrawal_permission_must_remain_off")
    if not request.exact_ip_allowlist:
        return blocked("single_expected_ip_allowlist_required")
    if request.leverage_enabled or request.margin_enabled or request.futures_enabled or request.otc_enabled:
        return blocked("non_spot_authority_must_remain_disabled")
    if not request.explicit_confirmation:
        return blocked("explicit_manual_live_arm_confirmation_required")

    try:
        cap = Decimal((request.approved_max_order_quote or "").strip())
    except (InvalidOperation, ValueError):
        return blocked("approved_quote_cap_invalid")
    if not cap.is_finite() or cap <= 0:
        return blocked("approved_quote_cap_must_be_positive")

    return ManualLiveArmDecision(
        allowed=True,
        reason="stage15_manual_arm_prerequisites_confirmed_no_execution_authority",
        symbol=symbol,
        quote_asset=quote_asset,
        approved_max_order_quote=cap,
        ready_to_request_manual_trade_permission=True,
        ready_to_change_live_env=False,
        live_ready=False,
        execution_authority=False,
    )

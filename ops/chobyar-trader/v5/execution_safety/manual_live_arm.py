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

    common = dict(
        symbol=symbol,
        quote_asset=quote_asset,
        ready_to_request_manual_trade_permission=False,
        ready_to_change_live_env=False,
        live_ready=False,
        execution_authority=False,
    )

    if not request.final_readiness_passed:
        return ManualLiveArmDecision(False, "final_readiness_required", None, **common)
    if symbol != "BTCUSDT" or quote_asset != "USDT":
        return ManualLiveArmDecision(False, "only_btcusdt_usdt_is_approved_for_stage15", None, **common)
    if not request.read_permission_enabled:
        return ManualLiveArmDecision(False, "read_permission_required", None, **common)
    if request.trade_permission_enabled:
        return ManualLiveArmDecision(False, "trade_permission_must_still_be_off_before_manual_arm", None, **common)
    if request.withdrawal_permission_enabled:
        return ManualLiveArmDecision(False, "withdrawal_permission_must_remain_off", None, **common)
    if not request.exact_ip_allowlist:
        return ManualLiveArmDecision(False, "single_expected_ip_allowlist_required", None, **common)
    if request.leverage_enabled or request.margin_enabled or request.futures_enabled or request.otc_enabled:
        return ManualLiveArmDecision(False, "non_spot_authority_must_remain_disabled", None, **common)
    if not request.explicit_confirmation:
        return ManualLiveArmDecision(False, "explicit_manual_live_arm_confirmation_required", None, **common)

    try:
        cap = Decimal((request.approved_max_order_quote or "").strip())
    except (InvalidOperation, ValueError):
        return ManualLiveArmDecision(False, "approved_quote_cap_invalid", None, **common)
    if not cap.is_finite() or cap <= 0:
        return ManualLiveArmDecision(False, "approved_quote_cap_must_be_positive", None, **common)

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

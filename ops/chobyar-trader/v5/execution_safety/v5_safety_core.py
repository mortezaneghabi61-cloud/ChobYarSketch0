from __future__ import annotations

from dataclasses import dataclass
from decimal import Decimal, InvalidOperation
from typing import Mapping

APPROVED_RISK = {
    "MAX_POSITION_PCT": "0.25",
    "STOP_LOSS_PCT": "0.015",
    "TAKE_PROFIT_PCT": "0.03",
    "MAX_DAILY_LOSS_PCT": "0.03",
}


@dataclass(frozen=True)
class V5SafetyDecision:
    allowed: bool
    reason: str
    mode: str
    dry_run: bool
    spot_only: bool
    withdrawals_disabled: bool
    leverage_disabled: bool


def _is_true(value: str | None) -> bool:
    return (value or "").strip().lower() == "true"


def _is_false(value: str | None) -> bool:
    return (value or "").strip().lower() == "false"


def _exact_decimal(value: str | None, expected: str) -> bool:
    try:
        return Decimal((value or "").strip()) == Decimal(expected)
    except (InvalidOperation, ValueError):
        return False


def evaluate_v5_safety(env: Mapping[str, str]) -> V5SafetyDecision:
    """Quote-neutral v5 fail-closed gate.

    Stage-12 removes legacy currency-specific order caps from the common safety
    gate. Per-market order caps belong to quote-aware authorities such as
    LIVE_MAX_ORDER_USDT for BTCUSDT. This function never grants live execution.
    """
    mode = (env.get("TRADING_MODE") or "").strip().lower()
    dry_run = _is_true(env.get("LIVE_DRY_RUN"))
    spot_only = _is_true(env.get("SPOT_ONLY"))
    withdrawals_disabled = _is_false(env.get("WITHDRAWALS_ENABLED"))
    leverage_disabled = _is_false(env.get("LEVERAGE_ENABLED"))

    common = dict(
        mode=mode,
        dry_run=dry_run,
        spot_only=spot_only,
        withdrawals_disabled=withdrawals_disabled,
        leverage_disabled=leverage_disabled,
    )

    if mode not in {"paper", "live"}:
        return V5SafetyDecision(False, "invalid_trading_mode", **common)
    if not dry_run:
        return V5SafetyDecision(False, "live_dry_run_must_remain_true", **common)
    if not spot_only:
        return V5SafetyDecision(False, "spot_only_required", **common)
    if not withdrawals_disabled:
        return V5SafetyDecision(False, "withdrawals_must_be_disabled", **common)
    if not leverage_disabled:
        return V5SafetyDecision(False, "leverage_must_be_disabled", **common)

    for key, expected in APPROVED_RISK.items():
        if not _exact_decimal(env.get(key), expected):
            return V5SafetyDecision(False, f"risk_mismatch:{key}", **common)

    if not _is_false(env.get("LIVE_TRADING_ENABLED")):
        return V5SafetyDecision(False, "v5_requires_live_trading_disabled", **common)

    return V5SafetyDecision(True, "v5_quote_neutral_read_and_dry_run_only", **common)

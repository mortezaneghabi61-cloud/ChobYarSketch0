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
class LiveSafetyDecision:
    allowed: bool
    reason: str
    mode: str
    dry_run: bool
    spot_only: bool
    withdrawals_disabled: bool
    leverage_disabled: bool
    max_order_tmn: int


def _is_true(value: str | None) -> bool:
    return (value or "").strip().lower() == "true"


def _is_false(value: str | None) -> bool:
    return (value or "").strip().lower() == "false"


def _positive_int(value: str | None) -> int | None:
    try:
        n = int((value or "").strip())
    except (TypeError, ValueError):
        return None
    return n if n > 0 else None


def _exact_decimal(value: str | None, expected: str) -> bool:
    try:
        return Decimal((value or "").strip()) == Decimal(expected)
    except (InvalidOperation, ValueError):
        return False


def evaluate_live_safety(env: Mapping[str, str]) -> LiveSafetyDecision:
    """Stage-1 policy gate only; never performs exchange/account mutation."""
    mode = (env.get("TRADING_MODE") or "").strip().lower()
    dry_run = _is_true(env.get("LIVE_DRY_RUN"))
    spot_only = _is_true(env.get("SPOT_ONLY"))
    withdrawals_disabled = _is_false(env.get("WITHDRAWALS_ENABLED"))
    leverage_disabled = _is_false(env.get("LEVERAGE_ENABLED"))
    max_order_tmn = _positive_int(env.get("LIVE_MAX_ORDER_TMN")) or 0

    common = dict(
        mode=mode,
        dry_run=dry_run,
        spot_only=spot_only,
        withdrawals_disabled=withdrawals_disabled,
        leverage_disabled=leverage_disabled,
        max_order_tmn=max_order_tmn,
    )

    if mode not in {"paper", "live"}:
        return LiveSafetyDecision(False, "invalid_trading_mode", **common)
    if not dry_run:
        return LiveSafetyDecision(False, "live_dry_run_must_remain_true", **common)
    if not spot_only:
        return LiveSafetyDecision(False, "spot_only_required", **common)
    if not withdrawals_disabled:
        return LiveSafetyDecision(False, "withdrawals_must_be_disabled", **common)
    if not leverage_disabled:
        return LiveSafetyDecision(False, "leverage_must_be_disabled", **common)
    if max_order_tmn <= 0:
        return LiveSafetyDecision(False, "live_max_order_tmn_required", **common)
    if max_order_tmn > 100_000:
        return LiveSafetyDecision(False, "live_max_order_tmn_exceeds_stage1_cap", **common)

    for key, expected in APPROVED_RISK.items():
        if not _exact_decimal(env.get(key), expected):
            return LiveSafetyDecision(False, f"risk_mismatch:{key}", **common)

    # Stage 1 deliberately keeps real order execution impossible.
    if not _is_false(env.get("LIVE_TRADING_ENABLED")):
        return LiveSafetyDecision(False, "stage1_requires_live_trading_disabled", **common)

    return LiveSafetyDecision(True, "stage1_read_and_dry_run_only", **common)

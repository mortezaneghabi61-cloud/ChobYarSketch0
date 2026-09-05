from __future__ import annotations

from dataclasses import dataclass
from decimal import Decimal, InvalidOperation
from typing import Mapping

from v5_safety_core import evaluate_v5_safety

APPROVED_SYMBOL_QUOTES = {
    "BTCUSDT": "USDT",
}

# Stage-7 remains dry-run only. This ceiling prevents an accidentally huge
# per-quote cap from becoming authoritative before live execution exists.
STAGE7_MAX_QUOTE_CAP = Decimal("100")


@dataclass(frozen=True)
class QuoteCapDecision:
    allowed: bool
    reason: str
    symbol: str
    quote_asset: str
    cap_quote: Decimal
    notional_quote: Decimal


def _decimal(value: object) -> Decimal | None:
    try:
        result = Decimal(str(value))
    except (InvalidOperation, TypeError, ValueError):
        return None
    if not result.is_finite():
        return None
    return result


def _deny(reason: str, symbol: str, quote_asset: str, cap: Decimal, notional: Decimal) -> QuoteCapDecision:
    return QuoteCapDecision(False, reason, symbol, quote_asset, cap, notional)


def evaluate_quote_cap(*, env: Mapping[str, str], symbol: str, notional_quote: object) -> QuoteCapDecision:
    """Quote-aware hard-cap authority; pure and dry-run only.

    For BTCUSDT, notional and the hard cap are expressed only in its quote asset,
    USDT. Legacy currency-specific cap state is outside this authority.
    """
    safety = evaluate_v5_safety(env)
    wanted = (symbol or "").strip().upper()
    quote_asset = APPROVED_SYMBOL_QUOTES.get(wanted, "")
    notional = _decimal(notional_quote) or Decimal("0")

    if not safety.allowed:
        return _deny(f"stage1_blocked:{safety.reason}", wanted, quote_asset, Decimal("0"), notional)
    if not wanted or not quote_asset:
        return _deny("symbol_quote_not_approved", wanted, quote_asset, Decimal("0"), notional)
    if (env.get("SYMBOL") or "").strip().upper() != wanted:
        return _deny("symbol_env_mismatch", wanted, quote_asset, Decimal("0"), notional)
    if (env.get("LIVE_TRADING_ENABLED") or "").strip().lower() != "false":
        return _deny("live_execution_must_remain_disabled", wanted, quote_asset, Decimal("0"), notional)
    if (env.get("LIVE_DRY_RUN") or "").strip().lower() != "true":
        return _deny("dry_run_required", wanted, quote_asset, Decimal("0"), notional)

    cap_key = f"LIVE_MAX_ORDER_{quote_asset}"
    cap = _decimal(env.get(cap_key)) or Decimal("0")
    if cap <= 0:
        return _deny(f"quote_cap_required:{cap_key}", wanted, quote_asset, cap, notional)
    if cap > STAGE7_MAX_QUOTE_CAP:
        return _deny("quote_cap_exceeds_stage7_ceiling", wanted, quote_asset, cap, notional)
    if notional <= 0:
        return _deny("notional_quote_must_be_positive", wanted, quote_asset, cap, notional)
    if notional > cap:
        return _deny("above_quote_asset_cap", wanted, quote_asset, cap, notional)

    return QuoteCapDecision(True, "stage7_quote_aware_cap_validated_no_submission", wanted, quote_asset, cap, notional)

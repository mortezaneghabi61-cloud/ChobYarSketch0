from __future__ import annotations

from dataclasses import dataclass
from typing import Any, Mapping

from quote_order_preflight import QuoteOrderIntent, QuoteOrderPreflight, run_quote_order_preflight
from v5_safety_core import evaluate_v5_safety
from wallex_readonly import BalanceRow, fetch_balances_readonly


@dataclass(frozen=True)
class AuthenticatedQuotePreflight:
    allowed: bool
    reason: str
    live_ready: bool
    balances: tuple[BalanceRow, ...]
    order: QuoteOrderPreflight


def run_authenticated_quote_preflight(
    *,
    env: Mapping[str, str],
    api_key: str,
    client: Any,
    intent: QuoteOrderIntent,
) -> AuthenticatedQuotePreflight:
    """Stage-12 authenticated GET-only preflight.

    Combines balances, quote-aware market rules, LIVE_MAX_ORDER_<QUOTE>, and
    active spot eligibility. It never submits or cancels an order and always
    returns live_ready=False.
    """
    safety = evaluate_v5_safety(env)
    if not safety.allowed:
        raise RuntimeError(f"v5_safety_blocked:{safety.reason}")

    key = (api_key or "").strip()
    if not key:
        raise RuntimeError("wallex_api_key_missing")

    balances = fetch_balances_readonly(env=env, api_key=key, client=client)
    order = run_quote_order_preflight(env=env, client=client, intent=intent)
    if not order.allowed or order.live_ready:
        raise RuntimeError("quote_order_preflight_not_readonly")

    return AuthenticatedQuotePreflight(
        allowed=True,
        reason="stage12_authenticated_quote_spot_preflight_no_submission",
        live_ready=False,
        balances=balances,
        order=order,
    )

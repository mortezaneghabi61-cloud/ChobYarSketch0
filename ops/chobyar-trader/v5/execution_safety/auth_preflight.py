from __future__ import annotations

from dataclasses import dataclass
from typing import Any, Mapping

from live_safety import evaluate_live_safety
from market_metadata import MarketMetadata, fetch_market_metadata
from wallex_readonly import BalanceRow, fetch_balances_readonly


@dataclass(frozen=True)
class AuthPreflightResult:
    allowed: bool
    reason: str
    live_ready: bool
    symbol: str
    balances: tuple[BalanceRow, ...]
    market: MarketMetadata


def run_authenticated_preflight(
    *,
    env: Mapping[str, str],
    api_key: str,
    client: Any,
    symbol: str,
) -> AuthPreflightResult:
    """Stage-5 authenticated read-only preflight.

    This function only composes the already-proven GET-only balance reader with
    the public GET-only market metadata reader. It never creates, cancels, or
    mutates orders and deliberately reports live_ready=False.
    """
    safety = evaluate_live_safety(env)
    if not safety.allowed:
        raise RuntimeError(f"stage1_blocked:{safety.reason}")
    if (env.get("LIVE_TRADING_ENABLED") or "").strip().lower() != "false":
        raise RuntimeError("live_execution_must_remain_disabled")
    if (env.get("LIVE_DRY_RUN") or "").strip().lower() != "true":
        raise RuntimeError("dry_run_required")

    wanted = (symbol or "").strip().upper()
    if not wanted:
        raise RuntimeError("preflight_symbol_missing")

    key = (api_key or "").strip()
    if not key:
        raise RuntimeError("wallex_api_key_missing")

    balances = fetch_balances_readonly(env=env, api_key=key, client=client)
    market = fetch_market_metadata(env=env, client=client, symbol=wanted)
    if market.symbol != wanted:
        raise RuntimeError("preflight_market_symbol_mismatch")

    # Stage 5 is intentionally incapable of granting live execution authority.
    return AuthPreflightResult(
        allowed=True,
        reason="stage5_authenticated_readonly_preflight_only",
        live_ready=False,
        symbol=wanted,
        balances=balances,
        market=market,
    )

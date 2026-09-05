from __future__ import annotations

from dataclasses import dataclass
from typing import Any, Mapping

from authenticated_quote_preflight import AuthenticatedQuotePreflight, run_authenticated_quote_preflight
from permission_readiness import ApiPermissionPosture, PermissionReadinessDecision, evaluate_api_permission_readiness
from quote_order_preflight import QuoteOrderIntent


@dataclass(frozen=True)
class Stage13ReadinessReport:
    allowed: bool
    reason: str
    live_ready: bool
    permission: PermissionReadinessDecision
    authenticated: AuthenticatedQuotePreflight


def run_stage13_readiness(
    *,
    env: Mapping[str, str],
    api_key: str,
    client: Any,
    intent: QuoteOrderIntent,
    posture: ApiPermissionPosture,
    expected_server_ip: str,
) -> Stage13ReadinessReport:
    """Read-only Stage-13 readiness report.

    The exchange API-key permission posture is supplied as trusted external
    read-back; this function never changes permissions. Authenticated exchange
    access remains GET-only through Stage-12, and live_ready is always False.
    """

    permission = evaluate_api_permission_readiness(
        posture=posture,
        expected_server_ip=expected_server_ip,
    )
    if not permission.allowed:
        raise RuntimeError(permission.reason)

    authenticated = run_authenticated_quote_preflight(
        env=env,
        api_key=api_key,
        client=client,
        intent=intent,
    )
    if not authenticated.allowed or authenticated.live_ready:
        raise RuntimeError("stage12_authenticated_preflight_not_readonly")

    return Stage13ReadinessReport(
        allowed=True,
        reason="stage13_readonly_permission_and_quote_readiness_confirmed_no_live_authority",
        live_ready=False,
        permission=permission,
        authenticated=authenticated,
    )

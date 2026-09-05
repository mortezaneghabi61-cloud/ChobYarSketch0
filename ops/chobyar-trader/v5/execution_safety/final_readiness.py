from __future__ import annotations

from dataclasses import dataclass
from typing import Mapping

from permission_readiness import ApiPermissionPosture, evaluate_api_permission_readiness
from v5_safety_core import evaluate_v5_safety


@dataclass(frozen=True)
class FinalReadinessDecision:
    allowed: bool
    reason: str
    ready_for_manual_live_decision: bool
    live_ready: bool


def evaluate_final_readiness(
    *,
    env: Mapping[str, str],
    posture: ApiPermissionPosture,
    expected_server_ip: str,
    readonly_probe_passed: bool,
    trader_service_active: bool,
    status_service_active: bool,
    shadow_timer_active: bool,
) -> FinalReadinessDecision:
    """Prove the paper/read-only stack is healthy without granting execution.

    Stage-14 is deliberately non-executing. It requires the Stage-13
    least-privilege posture (Read on, Trade off, Withdrawal off, exact IP
    allowlist), approved paper/risk gates, a successful authenticated read-only
    probe, and healthy runtime services. live_ready is always False.
    """
    safety = evaluate_v5_safety(env)
    if not safety.allowed:
        return FinalReadinessDecision(False, f"v5_safety_blocked:{safety.reason}", False, False)

    permission = evaluate_api_permission_readiness(
        posture=posture,
        expected_server_ip=expected_server_ip,
    )
    if not permission.allowed:
        return FinalReadinessDecision(False, permission.reason, False, False)

    if not readonly_probe_passed:
        return FinalReadinessDecision(False, "readonly_probe_required", False, False)
    if not trader_service_active:
        return FinalReadinessDecision(False, "trader_service_must_be_active", False, False)
    if not status_service_active:
        return FinalReadinessDecision(False, "status_service_must_be_active", False, False)
    if not shadow_timer_active:
        return FinalReadinessDecision(False, "shadow_timer_must_be_active", False, False)

    return FinalReadinessDecision(
        True,
        "stage14_paper_stack_ready_for_manual_live_decision_no_execution_authority",
        True,
        False,
    )

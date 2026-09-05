from __future__ import annotations

from dataclasses import dataclass
from ipaddress import ip_address
from typing import Iterable


@dataclass(frozen=True)
class ApiPermissionPosture:
    """Externally observed exchange API-key posture.

    Stage-13 does not attempt to introspect or mutate Wallex API-key settings.
    The caller must supply values observed from the exchange permission UI or
    another trusted administrative read-back.
    """

    read_enabled: bool
    trade_enabled: bool
    withdrawal_enabled: bool
    allowed_ips: tuple[str, ...]


@dataclass(frozen=True)
class PermissionReadinessDecision:
    allowed: bool
    reason: str
    read_enabled: bool
    trade_enabled: bool
    withdrawal_enabled: bool
    exact_ip_allowlist: bool
    live_ready: bool


def _normalized_ips(values: Iterable[str]) -> tuple[str, ...]:
    normalized: list[str] = []
    for raw in values:
        value = (raw or "").strip()
        if not value:
            continue
        try:
            normalized.append(str(ip_address(value)))
        except ValueError as exc:
            raise RuntimeError("api_ip_allowlist_invalid") from exc
    return tuple(sorted(set(normalized)))


def evaluate_api_permission_readiness(
    *,
    posture: ApiPermissionPosture,
    expected_server_ip: str,
) -> PermissionReadinessDecision:
    """Stage-13 least-privilege readiness gate; pure and non-mutating.

    This stage deliberately requires exchange Trade permission to remain OFF.
    A later explicit live-arm stage may define a separate permission contract,
    but Stage-13 itself never grants live execution authority.
    """

    try:
        expected_ip = str(ip_address((expected_server_ip or "").strip()))
    except ValueError as exc:
        raise RuntimeError("expected_server_ip_invalid") from exc

    allowed_ips = _normalized_ips(posture.allowed_ips)
    exact_ip_allowlist = allowed_ips == (expected_ip,)

    common = dict(
        read_enabled=bool(posture.read_enabled),
        trade_enabled=bool(posture.trade_enabled),
        withdrawal_enabled=bool(posture.withdrawal_enabled),
        exact_ip_allowlist=exact_ip_allowlist,
        live_ready=False,
    )

    if not posture.read_enabled:
        return PermissionReadinessDecision(False, "api_read_permission_required", **common)
    if posture.trade_enabled:
        return PermissionReadinessDecision(False, "api_trade_permission_must_remain_disabled_stage13", **common)
    if posture.withdrawal_enabled:
        return PermissionReadinessDecision(False, "api_withdrawal_permission_must_remain_disabled", **common)
    if not exact_ip_allowlist:
        return PermissionReadinessDecision(False, "api_ip_allowlist_must_match_single_expected_server", **common)

    return PermissionReadinessDecision(
        True,
        "stage13_least_privilege_permission_posture_confirmed_no_live_authority",
        **common,
    )

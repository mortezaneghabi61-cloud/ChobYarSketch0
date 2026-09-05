from __future__ import annotations

import json
import math
import time
from dataclasses import asdict, dataclass
from datetime import datetime
from pathlib import Path
from typing import Any

MAX_V5_REPORT_AGE_SECONDS = 660.0
MAX_FUTURE_SKEW_SECONDS = 60.0
MIN_DIRECTIONAL_SPECIALISTS = 3


@dataclass(frozen=True)
class EntryGateDecision:
    allowed: bool
    reason: str
    v5_action: str | None = None
    pre_meta_action: str | None = None
    age_seconds: float | None = None
    v5_score: float | None = None
    available_directional_specialists: int | None = None
    meta_hold: bool | None = None
    risk_veto: bool | None = None
    data_integrity_healthy: bool | None = None
    decision_fragile: bool | None = None
    tape_vote: int | None = None

    def audit_fields(self) -> dict[str, Any]:
        return asdict(self)


def _finite(value: Any) -> float | None:
    try:
        number = float(value)
    except (TypeError, ValueError):
        return None
    return number if math.isfinite(number) else None


def _parse_ts(value: Any) -> float | None:
    if not isinstance(value, str) or not value:
        return None
    try:
        return datetime.fromisoformat(value.replace("Z", "+00:00")).timestamp()
    except ValueError:
        return None


def _vote(votes: list[dict[str, Any]], agent: str) -> tuple[int | None, bool]:
    for item in votes:
        if not isinstance(item, dict) or item.get("agent") != agent:
            continue
        try:
            direction = int(item.get("vote"))
        except (TypeError, ValueError):
            return None, False
        return direction, item.get("available") is True
    return None, False


def _blocked(reason: str, **fields: Any) -> EntryGateDecision:
    return EntryGateDecision(False, reason, **fields)


def evaluate_entry_gate(
    report_path: Path,
    v4_votes: list[dict[str, Any]],
    *,
    now_ts: float | None = None,
    max_age_seconds: float = MAX_V5_REPORT_AGE_SECONDS,
) -> EntryGateDecision:
    """Fail-closed confirmation gate for NEW Paper BUY entries only.

    The caller remains responsible for applying this only after the proven v4
    supervisor has produced BUY. This function never creates BUY/SELL authority
    and must never be placed in the exit path.
    """
    now_ts = time.time() if now_ts is None else float(now_ts)
    try:
        raw = json.loads(report_path.read_text(encoding="utf-8"))
    except Exception:
        return _blocked("v5_report_unavailable")
    if not isinstance(raw, dict):
        return _blocked("v5_report_malformed")
    if raw.get("ok") is not True or raw.get("mode") != "shadow_observation_only":
        return _blocked("v5_report_contract_invalid")
    if raw.get("execution_authority") is not False:
        return _blocked("v5_execution_authority_violation")

    generated_ts = _parse_ts(raw.get("generated_at_utc"))
    if generated_ts is None:
        return _blocked("v5_report_timestamp_invalid")
    age = now_ts - generated_ts
    if age < -MAX_FUTURE_SKEW_SECONDS:
        return _blocked("v5_report_from_future", age_seconds=age)
    if age > max_age_seconds:
        return _blocked("v5_report_stale", age_seconds=age)

    consensus = raw.get("shadow_consensus")
    meta = raw.get("meta_intelligence")
    specialists = raw.get("specialists")
    if not isinstance(consensus, dict) or not isinstance(meta, dict) or not isinstance(specialists, list):
        return _blocked("v5_report_schema_incomplete", age_seconds=age)
    if len(specialists) < 5:
        return _blocked("v5_specialist_set_incomplete", age_seconds=age)

    action = str(consensus.get("action") or "")
    pre_meta = str(consensus.get("pre_meta_action") or "")
    score = _finite(consensus.get("score"))
    try:
        available = int(consensus.get("available_directional_specialists"))
    except (TypeError, ValueError):
        available = -1
    meta_hold = consensus.get("meta_hold") is True
    risk_veto = consensus.get("risk_veto") is True

    common = {
        "v5_action": action or None,
        "pre_meta_action": pre_meta or None,
        "age_seconds": age,
        "v5_score": score,
        "available_directional_specialists": available if available >= 0 else None,
        "meta_hold": meta_hold,
        "risk_veto": risk_veto,
    }

    if available < MIN_DIRECTIONAL_SPECIALISTS:
        return _blocked("v5_directional_quorum_insufficient", **common)
    if risk_veto:
        return _blocked("v5_risk_veto", **common)
    if meta_hold:
        return _blocked("v5_meta_hold", **common)
    if pre_meta != "BUY":
        return _blocked("v5_pre_meta_action_not_buy", **common)
    if action != "BUY":
        return _blocked("v5_final_action_not_buy", **common)
    if score is None or score < 1.25:
        return _blocked("v5_buy_score_invalid", **common)

    integrity = meta.get("data_integrity")
    execution = meta.get("execution_stress")
    uncertainty = meta.get("epistemic_uncertainty")
    fragility = meta.get("decision_fragility")
    if not all(isinstance(node, dict) for node in (integrity, execution, uncertainty, fragility)):
        return _blocked("v5_meta_schema_incomplete", **common)

    healthy = integrity.get("healthy") is True
    fragile = fragility.get("fragile") is True
    common["data_integrity_healthy"] = healthy
    common["decision_fragile"] = fragile
    if not healthy:
        return _blocked("v5_data_integrity_unhealthy", **common)
    execution_score = _finite(execution.get("score"))
    uncertainty_score = _finite(uncertainty.get("score"))
    if execution_score is None or execution_score >= 0.80:
        return _blocked("v5_execution_stress", **common)
    if uncertainty_score is None or uncertainty_score >= 0.65:
        return _blocked("v5_epistemic_uncertainty", **common)
    if fragile:
        return _blocked("v5_decision_fragile", **common)

    tape_vote, tape_available = _vote(v4_votes, "tape_order_flow")
    common["tape_vote"] = tape_vote
    if not tape_available or tape_vote is None:
        return _blocked("v4_tape_unavailable", **common)
    if tape_vote < 0:
        return _blocked("v4_tape_conflicts_with_buy", **common)

    return EntryGateDecision(True, "v5_confirmed_buy", **common)

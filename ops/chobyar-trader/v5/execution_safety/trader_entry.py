from __future__ import annotations

import trader
from entry_gate_v55 import evaluate_entry_gate
from global_sources import fetch_global_snapshot


def resilient_global_snapshot():
    return fetch_global_snapshot(trader.AUDIT)


# Keep the proven paper engine/risk gates unchanged; replace only the public
# global market-data dependency with the resilient credential-free source set.
trader.global_snapshot = resilient_global_snapshot


# v5.5 is confirmation-only. The original v4 supervisor still owns every
# existing risk/exit decision. Only a NEW BUY candidate is allowed to pass
# through the read-only v5 Meta/Specialist confirmation gate.
_original_supervise = trader.supervise


def v55_supervise(market, broker, votes):
    action, score, reason = _original_supervise(market, broker, votes)
    if action != "BUY":
        return action, score, reason

    decision = evaluate_entry_gate(
        trader.APP_DIR / "state" / "v5_shadow_latest.json",
        votes,
    )
    trader.AUDIT.write(
        "v5_entry_gate",
        v4_score=score,
        v4_reason=reason,
        **decision.audit_fields(),
    )
    if not decision.allowed:
        return "WAIT", score, "v5 entry gate: " + decision.reason
    return "BUY", score, "consensus entry + v5 confirmed"


trader.supervise = v55_supervise


if __name__ == "__main__":
    trader.main()

# Trader task board (handoff IDs)

Status is a shared planning snapshot, not automatically inferred from an agent's claims. A task is DONE only with reproducible test proof and (when required) merged code.

| Task ID | Priority | State | Owner | Next verification |
|---|---|---|---|---|
| TR-HUB-001 | P0 | IN_PROGRESS | ChatGPT coordination | Publish branch, verify remote, test local preflight + handoff, prepare ChatGPT Project instructions. |
| TR-ACC-001 | P0 | EVIDENCE_READY | Unassigned | Reconcile first legacy trade missing entry fee and 16/17 cohort issue; add regression tests to Trader scope in PR. |
| TR-VERSION-001 | P0 | INVESTIGATE | Unassigned | Compare GitHub V6.24 and VPS V6.26 hashes; determine tracked vs runtime-only changes, leave CAD alone. |
| TR-DATA-001 | P1 | RESEARCH | Unassigned | Validate Wallex 1m/1h/1d history, gaps, spread, tape freshness and cross-source data quality. |
| TR-STRATEGY-001 | P1 | BLOCKED_ON_DATA | Unassigned | Fee/slippage-aware time-split and walk-forward; compare no-trade/buy-and-hold baselines. |
| TR-QA-001 | P1 | PLANNED | Unassigned | Independent risk audit + CI; exact-head gates, no LIVE. |

Task claim protocol: add a sanitized event with task ID and owner at START; check latest remote events before acting; do not edit another agent's working branch; record tests and final result at END. Status updates to TASKS.md must be in reviewed PR. When offline, mark local work UNSYNCED.

# Accepted Trader decisions (as of 2026-10-16)

| ID | Decision | Reason |
|---|---|---|
| D-001 | ChobYar-Trader and Android CAD remain separate. | User explicit scope and safety constraint. |
| D-002 | Fail-closed PAPER-only; `TRADING_MODE=paper`; `LIVE_TRADING_ENABLED=false`. No live orders/autopromotion/secrets/credential exposure. | User non-negotiable. |
| D-003 | Risk values previously agreed: MAX_POSITION_PCT=0.25, STOP_LOSS_PCT=0.015, TAKE_PROFIT_PCT=0.03, MAX_DAILY_LOSS_PCT=0.03. Do not alter during coordination work. | Existing fixed policy. |
| D-004 | GitHub `mortezaneghabi61-cloud/ChobYarSketch0` reviewed, exact-head, all required checks; squash-only. Runtime change needs independent test/safety review. | Prevent unverified deployment. |
| D-005 | First reconcile paper PnL/accounting and historical event schema, fix market data, then perform independent fee-inclusive walk-forward research. Do not add agent complexity as a substitute for a tested edge. | Audit 2026-10-16. |
| D-006 | Ruflo coordinates controlled agents only. CodeX/ChatGPT/Work handoffs must record to canonical shared GitHub hub. No proof of profitability from the existence of multiple agents. | Avoid redundant / costly work. |
| D-007 | No public upload of sensitive trading/account data. Only sanitized engineering decisions, aggregate paper measurements and reproducible test references. | GitHub repo public. |

Changes to D-001..D-007 require user-approved decision with evidence; propose changes as PR and record an event.

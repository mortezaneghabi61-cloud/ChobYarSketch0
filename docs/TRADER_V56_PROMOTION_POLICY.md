# ChobYar Trader V5.6 Promotion Policy

This policy separates **reviewable evidence** from **execution authority**.
Passing the gate means only that V5 may be reviewed for Paper execution in a separate, explicit change. It never grants Paper or Live execution authority by itself.

## Hard locks

- `mode` must remain `paper` and `live_locked` must be true.
- V5 must remain `shadow_observation_only` while being evaluated.
- automatic promotion, automatic reweighting, foreign execution, and geo bypass must remain disabled.
- core trader/status and V5 shadow/scorecard services must be active.
- the readiness evaluator is read-only and contains no order-submission surface.

## Evidence required before Paper-execution review

- Forward observation: at least 30 closed trades and 14 days of uptime, positive realized PnL, max drawdown no more than 5%.
- Backtest: full-fidelity multi-agent evidence, at least 80 closed trades across at least 90 days, positive return, max drawdown no more than 10%.
- Data integrity: healthy and score at least 0.90.
- Public-source resilience: breadth, funding, and open-interest source must be explicitly resolved to OKX or the approved KuCoin fallback; three breadth symbols, at least five funding samples, and an available open-interest change are required.
- Specialist council: both 4h and 12h horizons need at least 30 sufficient samples, positive average signed return, and hit rate at least 50%.
- At least two individual specialists must independently meet the same 4h/12h positive-quality standard.

These are minimum evidence gates, not profitability guarantees. A passing result is `ready_for_v5_paper_execution_review=true`; `execution_authority_granted`, `live_authority_granted`, and `automatic_promotion` remain false.

## Current evidence interpretation (2026-09-10)

The current public telemetry is not eligible for promotion: only 6 forward trades have closed with negative realized PnL; the available backtest is a price-only proxy rather than full-fidelity multi-agent and has negative return; data integrity is 0.85; the active runtime report does not prove the V5.4 source-fallback wiring; and specialist-council directional samples are still insufficient.

No risk constants, credentials, order routes, or Live settings are changed by this policy.

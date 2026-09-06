# Stage-24 defensive research sources

This file records public defensive research used to shape the threat model. It intentionally omits offensive operational instructions.

- SEC enforcement material on spoofing/layering and cross-market manipulation.
- FINRA/CFTC/SEC descriptions of deceptive order-book practices.
- OWASP GenAI guidance: prompt injection, data/model poisoning, improper output handling, excessive agency, tool poisoning, untrusted external instructions.
- Academic work on crypto wash trading and artificial transaction generation.
- 2026 research on adversarial poisoning in multi-agent trading systems, showing that corrupted source data/prompts can propagate through analyst/researcher/trader/risk roles and that no tested communication topology is inherently robust.
- Research on crypto liquidation cascades and cross-venue divergence, motivating spread, source-dispersion, and shock gates.

Policy consequence: all external content is untrusted data; all model outputs are proposals; only deterministic, least-privilege code may authorize a financial mutation.

# Stage 24 — Adversarial Market Defense

Stage-24 treats every external market/news/tool input as potentially adversarial. It is a defensive gate only and grants no order authority.

## Threat classes covered

1. Order-book spoofing / layering: transient quote depth can be used to create false demand or supply. Detection requires time-series order-book evidence; a single snapshot is never labeled spoofing.
2. Wash-volume / fake activity: reported volume or transaction counts may not correspond to genuine price discovery. Stage-24 therefore avoids using volume alone as a BUY authority.
3. Cross-venue distortion: local price must be checked against a multi-venue quorum. High source dispersion or local/global divergence blocks entry.
4. Flash-crash / cascade regime: abnormal spread and single-step price shocks block new entries instead of chasing a move.
5. News / social manipulation: external text is data only. It cannot create policy, tool calls, or trading authority.
6. Prompt injection / agent goal hijack: instruction-like text, hidden Unicode controls, and tool-command language in external content are treated as tainted and block the affected decision path.
7. Multi-agent poisoning: no specialist may become a single point of authority; corrupted evidence must not propagate into execution without independent deterministic gates.

## Design rules

- Fail closed on insufficient global price quorum.
- Require at least three valid independent price sources for directional entry validation.
- Never infer spoofing from one order-book snapshot.
- Do not trust raw displayed depth without persistence/cancellation/trade confirmation.
- External text never gets execution credentials or direct tool authority.
- LLM output must be treated as an untrusted proposal and validated by deterministic code before any financial mutation.
- Withdrawals, leverage, margin, futures and OTC remain outside this authority.
- Production live execution remains isolated from this stage until the shadow defense is proven.

## Research basis

The design is informed by public regulator enforcement on spoofing/layering, academic research on crypto wash trading and adversarial multi-agent trading, and OWASP guidance on prompt injection, data poisoning, tool poisoning, excessive agency and improper output handling.

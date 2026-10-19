# Trader handoff — TR-ACC-001 independent CI blocker

- Date: 2026-10-19
- Draft pull request: https://github.com/mortezaneghabi61-cloud/ChobYarSketch0/pull/249 ; isolated fix branch exact head `7dc364cd1cdd160614bfb7814771402c74434995`.
- Code-backed ledger + cohort tests: 12 + 10 locally PASS; V6 paper exploration 15 PASS. Read-only pinned server log tests with 83,483 audit events confirmed 17 trades and net reconstructed PnL -0.08033557319503168 USDT, one fee omission and one historical simulated balance reset.
- CI observed: `ChobYar Trader V6.4 Trade Cohort Analysis` PASS; `PR Merge Gate` FAILED because required `Map ChobYar Java architecture` FAILED.
- Root cause from companion CI job logs: `git ... fatal: remote error: upload-pack: not our ref 356808216896a2024eee5329e026836b9f8e14bf` during `Checkout Code Ontology Companion (pinned)`. Expected/actual PR SHA matched. This is an external pinned companion source resolution failure, NOT evidence that the trader ledger tests fail. Other CAD workflow checks were still running when verified.
- Status: BLOCKED_FOR_MERGE. Do NOT bypass required gates, modify unrelated CAD code, automatically merge/deploy or enable live trading. Separate maintainer review needed to restore Code Ontology source pin and rerun exact-head CI; PR remains Draft.
- Authority: PAPER ONLY; live false. Cause of 2026-09-03T13:51 UTC paper state reset remains UNVERIFIED even though it is detected.

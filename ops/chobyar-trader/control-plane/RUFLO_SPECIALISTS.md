# Ruflo specialist delegation + trading open-source radar (2026-10-16)

User standing direction: use the available Ruflo multi-agent framework for meaningful project tasks, especially proactive GitHub discovery and verification. Coordinator remains accountable. Do not infer successful execution from an installed MCP registration alone.

## Decision framework
1. First read shared GitHub hub state and check task ownership, then query right_hand.github_discover and GitHub for comparable projects.
2. When Ruflo model access and quota are operational, ask one bounded GitHub scout agent to compare actual licensing, source, releases, test evidence and fit. Ask a second QA/security critic only for meaningful risk or integration proposals. Avoid recursive agents and uncontrolled swarms.
3. Use domain roles as appropriate: (a) source scout + open-source licensing (b) Wallex market-data integrity (c) fee-aware strategy research/backtest (d) risk/independent QA. Default maximum concurrent roles is 2 (more only with evidence of benefit/approved budget).
4. Accept only source-grounded and independently testable findings. Use vetted components in isolated lab first; any live exchange/financial credentials remain unavailable. Paper-only and fail-closed.
5. Track actual agents launched, tokens/cost if obtainable, results, rejected alternatives and exact code/test evidence. Never claim an agent ran if its model provider was unconfigured.

## GitHub candidates examined with source README 2026-10-16
| Public repository | Fit | Decision |
|---|---|---|
| https://github.com/HKUDS/Vibe-Trading | Trading/research/MCP; Wallex historical OHLCV loader | First candidate to reuse selected modules. Local isolated Wallex public OHLCV adapter built, 7 offline tests PASS, 30-day 1h history imported 718 bars with one missing. This does not validate trading execution. |
| https://github.com/TauricResearch/TradingAgents | Explicit multi-agent finance analysis, parallel analysts, retrospective tests and checkpointing | Evaluate analyst orchestration and debate patterns in isolated research; model spend may be high; Wallex execution compatibility not established. |
| https://github.com/AI4Finance-Foundation/FinRobot | Finance-domain research and quantitative validation workflows; V2 desktop marketed as production | Evaluate deterministic financial computation and evidence provenance; equity orientation may make parts unsuitable for Wallex crypto. |
| https://github.com/OpenByteInc/QuantDinger | Full AI trading stack + MCP, backtesting, paper/live features | Potential reference only; heavyweight PostgreSQL+Redis+deployment; do not run alongside existing trader or grant live trading authority. |
| https://github.com/virattt/ai-hedge-fund | Educational investment team, paper trading and backtesting | Readme says educational/not real trading; use concepts only, no assumption of deployability. |
| https://github.com/freqtrade/freqtrade | Established crypto backtest/dry-run/bias tools | Strong offline evaluation candidate; Wallex exchange supported adapter not verified; separate executable from Ruflo. |

## Current verified integration status
- `ruflo` and `right_hand` MCP servers registered and enabled in Windows Codex.
- Ruflo v3.56.10 stdio handshake passed, then reduced to 9 advertised coordination/memory tools to reduce Codex context. Paid direct agent_execute is deliberately not exposed. Right-hand's `github_discover` MCP and public GitHub search executed successfully.
- **No proof of successful Ruflo independent model-run agent for Trader yet.** LLM provider/quota may need setup. Do not claim multi-agent analysis happened until MCP tool/cost/evidence verifies it.
- GitHub branch `coordination/trader-hub-20261016` carries coordination instructions until reviewed/merged. Never silently edit main.

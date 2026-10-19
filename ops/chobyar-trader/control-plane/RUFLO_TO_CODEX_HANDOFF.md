# Complete Ruflo → Codex handoff — verified 2026-10-19

**Read this file first for any ChobYar-Trader work.** This is the verified, public-safe handoff for all Ruflo/TradingAgents/Vibe-Trading work done up to this point. It is not a recording of every private conversation and not proof of the live VPS status. The user's directive: the assistant manages the project; proactively checks GitHub for similar/better implementations; uses safe, compatible open-source code rather than reinventing; uses Ruflo specialists when operational and worth the token cost; and leaves a shared handoff for ChatGPT, Work and Codex.

## Architecture / single source of truth
- Trader GitHub: `mortezaneghabi61-cloud/ChobYarSketch0`. This handoff is on branch `coordination/trader-hub-20261016`, pending review before merging into protected main. Trader code lives under `ops/chobyar-trader`. Do not modify Android CAD.
- Start in the TraderHubRepo worktree, NOT in the main CAD working directory. Read `AGENTS.md`, this file, `CODEX_START_HERE.md`, `STATE.json`, `TASKS.md`, decisions and recent task events. Check remote SHA, VPS status and task ownership before changes.
- Codex = code/tests; `right_hand` = memory/brief + GitHub-first discovery; `ruflo` = agent coordination/memory. GitHub branch = sanitized cross-session handoffs; no arbitrary ChatGPT transcripts auto-upload.

## 1. Installed Ruflo / Codex technical configuration
- Windows laptop path `%USERPROFILE%\RufloLab`; Ruflo v3.56.10 installed under `node_modules\ruflo`; launcher `start-ruflo.cmd` uses Node 24 stdio MCP in `TraderLab` working directory. Node v24.21.0 verified 2026-10-16; Codex CLI v0.156.1 verified 2026-10-19.
- `codex.cmd mcp get ruflo`: **enabled**, stdio via `cmd.exe /d /c %USERPROFILE%\RufloLab\start-ruflo.cmd`. `codex.cmd mcp get right_hand`: **enabled**, Python stdio.
- To reduce MCP tool-description context: initially 358, then 55, now **9 tools**. Launcher specifies `agent_spawn,agent_list,agent_status,swarm_init,swarm_status,memory_store,memory_retrieve,memory_search`; verify exact ninth in fresh `tools/list`. Deliberately **NO agent_execute**; direct Ruflo agent execution requires Anthropic API and separately approved credentials/billing.
- Ruflo memory in `TraderLab\data\memory.db`; also `TraderLab\.swarm\memory.db` initialized (schema version 3). Check actual runtime backend before modifying. Key `trader-research/paper-vendor-bridge-20261016` stored with provenance `tool_result`, successfully retrieved in a *new* MCP session on 2026-10-19.
- `agent_list` on 2026-10-19: three `idle` agents, taskCount=0: `trader-oss-scout` (researcher), `trader-qa-risk` (tester), `trader-quote-qa-20261017` (tester added Oct 17, provenance of task unverified). A hierarchical swarm capped at two agents was registered. **Agent registration is not completed model research**; no confirmed agent_execute inference or paid model agent run.
- `right_hand` MCP tools: `get_brief,recall,remember,github_discover`; live MCP handshake + GitHub discovery passed Oct 19. Policy in `%USERPROFILE%\.codex\AGENTS.md` mandates GitHub-first and appropriate Ruflo review. The right-hand local `GITHUB_FIRST_POLICY.md` records detailed policy.

## 2. Actually reused and tested external code (pinned GitHub)
| Repository | Pinned commit | Verified scope |
|---|---|---|
| [Vibe-Trading](https://github.com/HKUDS/Vibe-Trading) | `596fcf3d63eb4d96e067f6dc94742b5f74a230ea` | Actual upstream Wallex loader `agent/backtest/loaders/wallex.py`, Git blob `0d0fc9757b58f267ae07947a477f6e6920665c7e`; executed with network-disabled fake HTTP using 718 real saved Wallex BTCUSDT 1h candles. One missing hour, 15m unsupported/rejected. |
| [TradingAgents](https://github.com/TauricResearch/TradingAgents) | `1394a3f72aa4393e1a98f51b382434c4b4c2d972` | Actual upstream **pure functions only** in `tradingagents/backtest.py`: `iter_grid`, `_canonical`, `_alpha`; date range clamp & parsing tested offline. Full paid LLM graph was NOT run. |
| [Freqtrade](https://github.com/freqtrade/freqtrade) | Not pinned/installed | Candidate for bias-tested dry-run backtests; Wallex connection not confirmed. |

External projects have **sparse shallow source checkouts** at `TraderLab\vendor-research\Vibe-Trading` and `...\TradingAgents`, not fully installed runtime engines. Full Vibe-Trading package declares 100+ dependencies; avoided heavyweight deployment. An isolated `requests` pip attempt failed DNS. No trading credentials transferred.

## 3. Actual files / tests / results on laptop
Local lab `%USERPROFILE%\RufloLab\TraderLab`.
- `tools\wallex_public_candles.py` / `test_wallex_public_candles.py`: no-auth public Wallex historical importer, fail-closed OHLC/closed-candle checks.
- `tools\vendor_bridge.py`: checks pinned vendor Git commits/blobs and exercises *real upstream* Wallex loader and TradingAgents pure functions without internet/model calls.
- `tools\spot_backtest.py` / `test_spot_backtest.py`: limited SPOT research simulation, not production. Signals use prior hourly closed bars and fill next open.
- `tools\reconcile-paper.cjs` / `reconcile-paper.test.cjs`: 17 legacy PAPER trade fee/ledger checks.
- Data: `data\vendor_bridge_verified.json`, `data\baseline_research.json`, `data\wallex_ohlcv\BTCUSDT_1h.candles.json` + quality metadata. `data\paper_trades_sanitized.json` has PRIVATE local paper-trade events; DO NOT publish to public GitHub.
- 14/14 Python regression tests PASSED on 2026-10-19; `right_hand` four-tool MCP smoke test PASSED; Ruflo nine-tool handshake + memory retrieval PASSED.
- Basic Ruflo test scripts at `%USERPROFILE%\RufloLab\mcp-smoke-wrapper.cjs`, `ruflo-agent-list-smoke.cjs`, `ruflo-memory-read.cjs`.
- Codex launcher `TraderLab\open-trader-codex.cmd` performs hub sync/preflight and local tests before starting Codex; its Windows file was inspected on 2026-10-19.

### Strictly limited strategy experiment, not profitability
Public Wallex 718 1h candles (~30 days through 2026-10-16), 216h holdout 2026-10-07 to 16, initial 10 virtual USDT, max stake 25%, **assumed** 0.2% fee per side and 0.05% adverse slippage each side: no-trade **0%**; buy-and-hold 25% **−0.82196%**; SMA20/50 **−1.19162%**. This does not test the live ChobYar trading strategy; too little data and lacks exchange size minimum, spread/fill realism, intrabar stop/target ordering, and multi-regime robustness. No proof of profit.
Historic paper ledger: 17 pairs, first trade entry-fee omission created +0.005 USDT logged discrepancy. All-17 reconstructed PnL ≈−0.080336 USDT vs logged sell sum ≈−0.075336 and later cumulative ≈−0.078685. Legacy `executed` schema mismatch made existing cohort analyzer accept only 16. This must be fixed with tests before strategy changes.
GitHub Trader V6.24 and VPS V6.26 differed on Oct 16. **Verify NOW before further deployment.** No live services enabled in the audited task.

## 4. Useful repeatable zero-model commands
Use local Python at `%USERPROFILE%\.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe`.
```powershell
codex.cmd mcp get ruflo
codex.cmd mcp get right_hand
node %USERPROFILE%\RufloLab\mcp-smoke-wrapper.cjs
node %USERPROFILE%\RufloLab\ruflo-agent-list-smoke.cjs
node %USERPROFILE%\RufloLab\ruflo-memory-read.cjs
& "%USERPROFILE%\.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe" -m unittest discover -s "%USERPROFILE%\RufloLab\TraderLab\tools" -p "test_*.py"
```
Add one useful task at a time. Do not repeatedly reinstall or call paid models for basic tests.

## 5. Required next tasks / fail-closed constraints
Priorities: `TR-ACC-001` ledger/fee invariants; `TR-VERSION-001` GitHub vs VPS diff; `TR-DATA-001` trustworthy Wallex data, liquidity, minimum order and missing bars; `TR-STRATEGY-001` longer walk-forward after genuine fees/spreads, benchmark no-trade & buy-and-hold. Use Ruflo role for research/risk only when helpful and model access explicitly authorized; registered idle roles are not independent LLM completed tasks. Publish small sanitized end-of-task handoff on this GitHub hub branch (or say UNSYNCED).
**Hard policy:** `TRADING_MODE=paper`; `LIVE_TRADING_ENABLED=false`; no live orders, API secrets/credentials, personal account data, leverage, automatic deployments/merges, lowering stop-loss/tape/risk gates, or claiming profits. A 9-tool Ruflo MCP can coordinate but cannot itself magically listen to all ChatGPT chats.

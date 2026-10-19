# CODEX START HERE — ChobYar-Trader (2026-10-16)

**Mission:** reach evidence-backed net profitability without wasting Codex quota or money. A result must be reproducible, fee-inclusive and independently tested. No guarantees.

## 1. Start-of-task protocol (bounded context)
1. Read THIS file, then **`RUFLO_TO_CODEX_HANDOFF.md`**, `STATE.json`, `TASKS.md` and only most recent relevant `events/`. Don't reread the whole 90-message history, huge GitHub repos or CAD code.
2. Verify current GitHub `main` SHA and live VPS status before changing deployed trader logic; 2026-10-16 snapshot may be stale. Read/claim a task ID. No simultaneous conflicting owner.
3. Search GitHub for vetted reusable code first (right_hand.github_discover). Ruflo tracked specialists for source audit and risk/QA are registered but **idle**, not independent model results.
4. Work on one small reversible branch or isolated lab. Run the smallest targeted local test first. Post concise sanitized handoff when finished.

## 2. Verified reproducible offline evidence
- Vibe-Trading pinned commit: `596fcf3d63eb4d96e067f6dc94742b5f74a230ea`. Tested **actual** `agent/backtest/loaders/wallex.py` conversion with network-disabled fixture of 718 hourly public BTCUSDT candles. Data gap: 1 hour, already recorded.
- TradingAgents pinned commit: `1394a3f72aa4393e1a98f51b382434c4b4c2d972`. Tested actual upstream pure `iter_grid`, `_canonical`, `_alpha` without running LangGraph/LLM.
- Offline 216-hour holdout starting 2026-10-07T19:00Z: no-trade 0%; 25%-stake buy-and-hold -0.82196%; SMA20/50 -1.19162% using modeled 0.2% fee EACH side, 0.05% adverse slippage each side and next-hour-open fills. This is **not** the existing live trader strategy and NOT profit evidence. 14/14 local data/backtest tests passed.
- Ruflo v3.56.10 MCP **9 minimal coordination/memory tools** accessible; no paid direct agent execution exposed; 2 specialists registered (researcher + QA) but **0 agent_execute calls**, because Anthropic API billing/auth not approved. Ruflo memory is initialized and a sanitized research entry was stored.
- Original 17 paper trades had legacy accounting mismatch, negative net PnL. GitHub V6.24 vs VPS V6.26 divergence remains. These are the P0 tasks.

## 3. No-token commands (Windows laptop)
Python = `%USERPROFILE%\.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe`
Lab = `%USERPROFILE%\RufloLab\TraderLab`
- Run vendor hash + offline parser & date-grid verification: `"%PY%" "%LAB%\tools\vendor_bridge.py"`
- Run offline baseline research only: `"%PY%" "%LAB%\tools\spot_backtest.py"`
- Run regression tests: `"%PY%" -m unittest discover -s "%LAB%\tools" -p "test_*.py"`
- Run paper ledger reconciliation: `node "%LAB%\tools\reconcile-paper.cjs"` (nonzero on known historical mismatch is expected).
- Open Codex in Trader scope using `"%LAB%\open-trader-codex.cmd"`; don't open it in the CAD checkout.

## 4. Actual priorities
P0 TR-ACC-001: reconcile old exchange fees and equity reset/schema anomalies with automated invariant tests.
P0 TR-VERSION-001: compare GitHub/main vs VPS read-only first, preserve runtime state.
P1 TR-DATA-001: collect longer Wallex data, check missing bars, actual spreads and min-notional; never weaken tape gate.
P1 TR-STRATEGY-001: only after clean data, properly walk-forward test against no trade/buy-hold; paper forward, risk-review.
No wholesale app installation, no multi-agent swarm loops, no expensive external LLM unless there is a specific proven benefit and cost approval.

## 5. Hard boundary
Paper-only: `TRADING_MODE=paper`, `LIVE_TRADING_ENABLED=false`. Do not run real trades, expose API secrets, enable leverage, lower risk guards, deploy/merge automatically, or claim profitability. Public GitHub content must contain only sanitized engineering evidence. Mark failed sync as UNSYNCED.

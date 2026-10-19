# TR-HUB-001 — Ruflo to Codex complete handoff

- Date: 2026-10-19
- Actor: ChatGPT project coordinator
- Scope: Shared Codex context for Ruflo installed tools, pinned TradingAgents/Vibe-Trading research, local evidence, budget and fail-closed safety.
- Document: `control-plane/RUFLO_TO_CODEX_HANDOFF.md` is the canonical consolidated report. `AGENTS.md` and `CODEX_START_HERE.md` point to it at start.
- Laptop verification: `codex mcp get ruflo` and `right_hand` enabled; Ruflo MCP registration of three idle agents confirmed; persistent memory retrieved; right-hand GitHub discovery tested; 14/14 Python data/backtest tests passed.
- Previously pending documents now included: `CODEX_START_HERE.md` and `RESEARCH_BASELINE_2026-10-16.md`. Source research is pinned. No market orders, model-agent inference or VPS runtime changes.
- Publication method: versioned GitHub coordination branch, with remote read-back required to claim synced. Protected main is not changed here.
- Next: `TR-ACC-001`, then `TR-VERSION-001`; work in isolated branch, perform source checks and publish sanitized handoffs.
- Authority: Paper only, live false. The GitHub hub is coordination information, not passive omniscient synchronization of every chat.

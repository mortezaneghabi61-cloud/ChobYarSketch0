# ChobYar-Trader Unified Coordination Hub

Single source of truth for **decision and task coordination**, reused across ChatGPT chats, ChatGPT Work and Codex. This is a public-repo safe, version-controlled handoff protocol; it is NOT a background wiretap of conversations.

### Read order on every Trader task
1. `STATE.json` (verified last-known snapshot, always timestamped; not live)
2. `DECISIONS.md` (accepted constraints)
3. `TASKS.md` (claim/ownership and current priorities)
4. `events/` (last timestamped sanitized handoffs)
5. Compare with live VPS logs and current GitHub main before implementing anything consequential.

### Write after every Trader task
Add an append-only event file using `trader_hub.py handoff` with actor, task ID, result, exact source/commit, test proof, and next action. Commit on the Trader hub branch, push to GitHub. If push fails: explicitly say UNPUBLISHED/NOT SYNCED. Significant decisions should be proposed by PR; never silently overwrite decisions or main. Different sessions own unique events; no collaborative editing of the same draft.

### Authority hierarchy
GitHub protected `main` controls accepted policy and reviewed code. This `coordination/trader-hub-20261016` branch carries the rollout UNTIL reviewed/merged. VPS state/logs are runtime evidence, not automatically current in this file. A comment in chat is not proof of GitHub publication.

### Safeguards
This repository is PUBLIC. No private messages, API secrets, IPs, identities, personal transaction details, `.env`, tokens, SSH keys, wallet data, verbatim chat transcripts or access credentials. No live trading, stop-loss changes, or unsupported release. Keep CAD completely isolated.

### Native ChatGPT workflow
Create one ChatGPT Project named `ChobYar-Trader`, preferably with **default** memory if ChatGPT Work is required. Move eligible relevant chats into it and paste `PROJECT_INSTRUCTIONS_FA.md` in Project Instructions. Its chats can use project context, but Codex stays separate and needs this GitHub protocol. Project-only memory may block Work in the project.

### Codex workflow
Start inside `ops/chobyar-trader` in the isolated Git worktree using the provided launch script, not in CAD root. This directory's AGENTS.md routes sessions through this hub. Run `python control-plane/trader_hub.py preflight` before work and `python control-plane/trader_hub.py handoff ... --publish` after work. Do not claim automatic capture of every spoken word.

### Limitations and fallback
GitHub chat connector currently cannot mutate repository via its integration (403), but laptop git may be able to push. Basic Memory Cloud trial expired; this architecture does not depend on a paid memory provider. If project chats lack a GitHub connector or do not use the instructions, the hub cannot know their text automatically.

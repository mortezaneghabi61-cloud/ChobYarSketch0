# GitHub-first reuse standard for ChobYar-Trader
Owner's standing directive (2026-10-16): the project manager independently scouts, vets and adopts available open-source solutions without requiring each idea or repository URL from the owner.

* Before new technical work: read latest hub state/active task, verify exact GitHub/VPS code. Search GitHub 2-3 targeted phrases for comparable open-source solutions.
* Evaluate genuine fit, project health, pinned versions/commit, license and redistribution obligations, dependency/supply-chain risks, security issues, readme and test coverage; inspect source.
* Decision hierarchy: vetted existing component > safe adaptation > bounded custom code. **Actually integrate** useful vetted components in an isolated development environment and verify tests; don't only list projects.
* Evidence-based handoff: reference URLs and commits, accept/reject reasons, changed files, test evidence and risk. No unverified claims of integration.
* Never give external code Wallex API credentials; all tests paper-only or offline; preserve stop loss, trade gate, position, daily-loss and fail-closed safeguards.
* Discovery-only right_hand.github_discover is available to Codex; other chats may use GitHub connector/web. Failure to search must be marked SEARCH_UNAVAILABLE, not fabricated.
* No GitHub search for unrelated chat, translation or personal matters. Honor user explicit no-internet instruction.

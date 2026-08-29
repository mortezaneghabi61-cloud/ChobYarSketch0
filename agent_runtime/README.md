# ChobYar CAD Professor Agent Runtime

This directory contains the executable OpenAI Agents SDK runtime for ChobYar's CAD professor and principal engineering reviewer.

## What it does

- reads the repository architecture, progress and acceptance documents before substantial work;
- reviews Sketch, constraints, feature modeling, exact B-Rep/topology, persistence/history, pen/touch interaction, rendering and Android integration;
- reasons about Shapr3D workflows as a behavioral/teaching reference while requiring original ChobYar implementation, branding and assets;
- can run only allow-listed Gradle validations;
- is read-only by default;
- with `--apply`, can edit guarded text source/test/doc files and show the resulting diff;
- cannot read/write keystores, env files, credentials, policy files or GitHub workflow files through its tools.

## Local use

Requirements: Python 3.11+ and an `OPENAI_API_KEY` supplied through the environment/secret manager.

```bash
python -m pip install -e agent_runtime pytest
pytest -q agent_runtime/tests
chobyar-agent --task "Audit sketch constraints against the current acceptance contract"
chobyar-agent --task "Fix the root cause of the selected regression and add a test" --apply
```

The runtime stores its latest report under `.agent-state/`, which is intentionally ignored by Git.

## GitHub Actions

The manual workflow `.github/workflows/chobyar-agent.yml` exposes two inputs:

- `task`: the CAD/Android engineering mission;
- `apply_changes`: false for review only; true for guarded edits.

When `apply_changes=true`, repository changes are committed to an `agent/auto-*` branch and opened as a pull request. The workflow never pushes agent-authored code directly to `main`.

The repository must have an Actions secret named `OPENAI_API_KEY`. Never commit the key to the repository.

## Private Shapr3D references

Authenticated Shapr3D account pages, user-owned `.shapr` projects, purchased training material, Gmail-derived information and other private references must stay outside the public repository. For a local/private run, materialize only the references needed for the task under `agent_runtime/private_refs/`; this directory is Git-ignored. The agent may extract engineering conclusions from those references, but must not reproduce or commit private source material.

Direct login credentials are never an agent input. Authentication should be performed by the user in the relevant authenticated browser/session or secret manager, then the resulting authorized session/files can be supplied to the private runtime.

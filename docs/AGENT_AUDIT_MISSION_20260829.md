# Agent audit mission — architecture/parity supervisor review

Read first:
- `AGENTS.md`
- `docs/SHAPR3D_PROFESSOR_AGENT.md`
- `docs/CAD_KERNEL_REWRITE_V2.md`
- `docs/SUPERVISOR_AUDIT_INPUT_20260829.md`
- `docs/manual26100-progress.md`
- relevant production source/tests

## Mission
Perform a read-only senior CAD architecture audit after K3.1. Determine what ChobYar still lacks to approach professional Shapr3D-class modeling behavior, what the repository has too much of, what should be merged/consolidated, what should be removed later, and what should be fixed next.

Do not recommend another parallel core. Work from the merged `CadKernel` V2 + `ir.chobyar.sketch.core` direction.

## Required analysis
1. Map authority/ownership for Sketch geometry, selection, undo/redo, snapping, constraints, feature/history, B-Rep/topology, rendering and persistence.
2. Identify duplicated responsibilities across the Canvas/View inheritance chain and rank them by regression risk.
3. Identify reflection/dynamic coupling that blocks deterministic model ownership.
4. Compare current semantics with a professional Shapr3D-style workflow: stable selection, direct manipulation, constraints/DOF, snapping, feature history, topology rematching, pen/touch, persistence and performance.
5. Separate missing fundamentals from optional/visual features.
6. Explicitly say what should NOT be built yet.
7. Propose the next three bounded migration milestones with exact acceptance criteria and likely files/tests.

## Decision format
Return:
- Executive verdict
- What is missing
- What is excessive/duplicated
- What must be merged/consolidated
- What should be deferred or later deleted
- Top 5 technical risks
- Milestone #1 / #2 / #3, each with scope, files/layers, tests, stop condition
- Whether K3.2 should primarily be stable-id legacy adapter+persistence, SnapService extraction, ConstraintGraph extraction, or another step; choose one and justify it
- A short Shapr3D-parity gap scorecard (0-5) for Sketch model, solver/DOF, snapping, solid kernel, history/persistence, topology selection, pen/touch, rendering/performance

Do not edit repository files. If evidence is insufficient, name the missing evidence rather than guessing.

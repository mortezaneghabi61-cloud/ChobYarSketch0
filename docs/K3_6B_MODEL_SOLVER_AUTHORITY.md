# K3.6b — Model Solver Authority

## Goal
Move constraint solving out of Android View/object-identity ownership and establish a solver-agnostic model boundary before production interaction is switched.

## This slice
- `SketchConstraintSolver` is a replaceable solver contract.
- `DeterministicSketchConstraintSolver` supports the first migration set: Horizontal, Vertical, Parallel, Perpendicular, Coincident and Point-on-Line.
- `SketchDocument.addConstraintsAndSolve(...)` commits solved geometry + new constraints as one Undo step.
- `SketchDocument.translateSelectionAndSolve(...)` is the edit-propagation seam for constrained motion.
- Conflict/unsupported results fail before geometry, constraints or history mutate.

## Why this boundary exists
The production legacy sketch stack still stores relationships by Java object identity and enforces some relationships from View drawing/touch code. That is not an acceptable long-term CAD ownership model. Stable IDs, persistence and Undo/Redo must remain independent of Android Views and independent of the selected solver implementation.

## Solver replacement policy
The deterministic Java solver is migration scaffolding and executable contract evidence, not a claim to be a mature general geometric constraint solver. PlaneGCS/native alternatives are benchmarked separately. Replacing the solver must not require changing `SketchDocument`, stable IDs or project persistence semantics.

## Acceptance
- deterministic convergence for the supported constraint set;
- contradictory constraints fail closed;
- unsupported constraint kinds never pretend to be solved;
- add-constraint + solve = one Undo/Redo transaction;
- constrained move + propagation = one Undo transaction;
- exact-head unit/regression/build gates remain green.

## Next slice
Bridge production H/V, Parallel, Perpendicular and Coincident creation into these model-owned transactions, then remove draw-time/object-identity enforcement as each relationship becomes model-authoritative.

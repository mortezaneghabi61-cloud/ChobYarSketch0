# K3.3 Sketch Authority Migration Seam

K3.3 is intentionally a migration seam, not an authority flip.

## Current authority

- Legacy production canvas remains authoritative for touch/pen interaction, snapping, constraints, dimensions and annotations.
- `SketchDocument` receives a stable-ID geometry mirror after supported committed mutations.
- Schema-v2 persisted IDs are the identity bridge between the two models.

## Mirrored geometry

The bridge mirrors point, line, circle, arc, rectangle, polygon and polyline geometry. Legacy measure/angle/guide annotations remain legacy-owned in K3.3.

## Commit boundaries covered

- sketch gesture completion
- clear, delete, copy, move, offset, rotate, scale, mirror, array
- trim, extend, sketch chamfer/fillet and join
- driving dimensions and line-angle edits
- H/V, perpendicular, parallel, equal, symmetry, midpoint, tangent and concentric constraint applications that immediately move geometry
- Undo / Redo
- project import / reopen
- command-driven mutations

## Acceptance contract

`K33SketchMirrorInstrumentationTest` verifies:

1. Copy / Array / Offset create fresh stable IDs while the source ID survives.
2. Undo / Redo restores exactly the committed stable-ID set.
3. Save / reopen preserves IDs and hydrates a geometry-equivalent `SketchDocument`.

`K3 Sketch Authority Parity Gate` runs these checks on an Android API 35 emulator.

## Explicit non-goals

- Moving SnapService ownership.
- Moving ConstraintGraph or driving-dimension ownership.
- Mirroring relation/annotation objects themselves.
- Making `SketchDocument` the production source of truth yet.

## Rollback seam

Production wiring is one class substitution in `ChobYarActivity`: `K33MirroredCadCanvasView` can be replaced by `Shapr3DGuideCadCanvasView` without changing persistence schema or kernel behavior.

The next authority migration may proceed only after exact-head parity, persistence, solver, emulator and production CAD regression gates remain green.

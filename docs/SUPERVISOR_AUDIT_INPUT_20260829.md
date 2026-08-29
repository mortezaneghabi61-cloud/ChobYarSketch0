# Supervisor audit input — 2026-08-29

This file records bounded evidence for the ChobYar CAD professor agent. It contains no secrets or private Shapr3D material.

## Current direction
- `CadKernel` V2 is the stable application-facing kernel boundary.
- K2.1 extracted OCCT shape ownership into `ShapeStore`.
- K2.2 split indexed direct edit into shared native services.
- K3.1 introduced the pure UI-independent `ir.chobyar.sketch.core` model with stable ids and transactional document history.
- The old View/Canvas hierarchy remains operational and must be migrated incrementally without breaking production.

## Static architecture evidence
A Code Ontology snapshot analyzed 50 source files, 2,818 nodes and 6,851 relationships with no parse warnings. The most concentrated files by declared methods were:

- `CadCanvasView.java`: ~306 methods
- `OcctStableCadCanvasView.java`: ~117 methods
- `ParametricSketchCanvasView.java`: ~108 methods
- `ShaprConstraintSolverCadCanvasView.java`: ~93 methods
- `ShaprSplineEditingCadCanvasView.java`: ~90 methods
- `AdvancedParametricSolidCadCanvasView.java`: ~87 methods
- `ChobYarShaprCanvasView.java`: ~85 methods
- `SmartCadCanvasView.java`: ~85 methods

Static analysis does not prove runtime behavior, especially where reflection/dynamic dispatch exists, but this concentration is a strong signal that View classes still own too much CAD state and behavior.

## Runtime evidence
A successful Android emulator smoke run built and installed the APK, kept `ChobYarActivity` alive, opened the Sketch palette, created a rectangle with a real touch gesture, exercised Fit, and found no fatal exception.

The same log showed startup/UI jank evidence including `Skipped 59 frames` and a ~986 ms `Davey!` frame. Treat emulator timing as directional rather than physical-device acceptance evidence.

## Supervisor constraints
- Do not chase superficial UI parity before model/kernel ownership is correct.
- Do not create a second competing Sketch core.
- Prefer migration/adapters with parity tests over big-bang rewrites.
- Exact B-Rep/topology remains authoritative; display triangulation is derived.
- Millimeters remain model units end-to-end.
- Keep private Shapr3D account/course material out of the public repository.

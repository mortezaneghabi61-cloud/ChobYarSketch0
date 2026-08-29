# ChobYar CAD Core V2

This branch starts a clean, UI-independent modelling core instead of adding more behavior to the legacy canvas hierarchy.

## Rules

- Model units are millimetres.
- Geometry is stored analytically; tessellation is only a rendering/export concern.
- Android Views, gestures and JNI are adapters.
- Undo/redo belongs to the model layer.
- Constraints are attached to entity IDs, not screen objects.
- The kernel API is deterministic and testable without an Android device.

## Layers

1. **core/v2** — vectors, analytic sketch entities, constraints, history.
2. **3D adapter** — maps solved profiles to the existing OCCT B-Rep backend.
3. **interaction adapter** — converts touch/stylus coordinates to model coordinates and produces selections/snaps.
4. **UI** — Shapr3D-like command surface; no geometry authority.

## Next implementation gates

- robust constraint graph + DOF accounting
- exact line/arc/circle intersections and trim
- profile/wire validation
- OCCT extrusion/boolean adapter
- persistent parametric feature tree
- snap index
- Android instrumentation against real touch/stylus input

The old implementation remains untouched on this branch so the new core can be validated independently before replacement.

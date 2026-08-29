# ChobYar CAD Kernel Rewrite V2

## Goal

Replace the accumulated Canvas-driven geometry architecture with one stable CAD core boundary that can survive UI, renderer, persistence, and native-kernel changes independently.

The rewrite is incremental. `main` remains the production baseline until every migration gate is green.

## Why this rewrite is necessary

The current app has grown through many presentation subclasses that also own geometry, commands, selection, persistence, native handles, and rendering behavior. This makes regressions easy and makes it expensive to change any one layer.

The current native boundary also exposes raw `long` shape handles and reports many failures as `0`, `false`, or an empty array. That loses error meaning and encourages UI code to know native details.

The OCCT implementation is additionally compiled as one translation unit by including `occt_brep_jni.cpp` directly from `occt_brep_with_indexed_direct.cpp`. That was useful while bootstrapping, but it is not the final maintainable architecture.

## Target architecture

```text
UI / gestures / tool palettes
        |
        v
Command + selection layer
        |
        v
Sketch / feature model
        |
        v
CadKernel (stable Java contract)
        |
        +---- OcctCadKernelAdapter (migration adapter)
        |
        v
JNI bridge
        |
        v
C++ kernel services
  - ShapeStore / ownership
  - Primitive builders
  - Feature operations
  - Boolean operations
  - Direct editing
  - Topology queries
  - Tessellation
  - Import/export
        |
        v
Open CASCADE
```

## Non-negotiable invariants

1. Model coordinates are millimeters end-to-end.
2. UI code never owns a native `TopoDS_Shape` handle directly.
3. Every kernel operation returns a typed success/failure result; no silent `0`/empty-array failure contracts in V2.
4. Display meshes are derived data. Exact B-Rep/topology remains the source of truth.
5. Editing operations create a new body result; ownership/release is explicit.
6. Sketch constraints and feature history remain independent from Android Views.
7. Rendering must not mutate modeling state.
8. Project persistence stores stable model semantics, not transient renderer/JNI handles.
9. `main` is not switched to V2 until build, instrumentation, native self-test, persistence, and interaction gates all pass.

## Migration phases

### Phase 1 - Stable Java kernel contract

- Add `CadKernel`, `CadKernelResult`, `CadBodyRef`, `CadVector3`, and `CadProfile`.
- Add `OcctCadKernelAdapter` over the mature V1 JNI layer.
- New feature code must call the V2 contract instead of `NativeBRepKernel` directly.
- Existing production paths keep working while callers are migrated.

### Phase 2 - Native ownership and error model

Split native code into real compilation units:

- `core/ShapeStore.*`
- `core/KernelResult.*`
- `ops/PrimitiveOps.*`
- `ops/BooleanOps.*`
- `ops/DirectEditOps.*`
- `ops/TransformOps.*`
- `query/TopologyQuery.*`
- `query/Tessellation.*`
- `io/Exchange.*`
- `jni/CadKernelJni.cpp`

Remove the include-a-cpp translation-unit pattern. Add generation-safe handles or an equivalent ownership mechanism so stale handles cannot resolve to unrelated shapes.

### Phase 3 - Sketch engine extraction

Move geometry entities, snapping, constraints, dimensions, selection IDs, and undoable sketch commands out of `View` subclasses into dependency-free model packages.

### Phase 4 - Feature/parametric model

Unify Extrude, Revolve, Sweep, Loft, Boolean, Fillet, Chamfer, Shell, Push/Pull, Transform, Pattern, and future woodworking features as commands/features over the same model document.

### Phase 5 - Presentation migration

Reduce the Canvas inheritance chain. Views become input/render adapters over model state instead of alternative CAD engines.

### Phase 6 - Production cutover

Switch workspace creation to V2 only after all gates pass, then remove dead V1 presentation/kernel paths in a separate cleanup PR.

## Quality gates

A phase cannot merge merely because it compiles.

Required gates:

- `assembleDebug`
- `assembleReleaseCandidate`
- Java/Android contract tests
- Native OCCT self-test on arm64-v8a
- Sketch primitive + snap + constraint tests
- Extrude/Revolve/Sweep/Loft/Boolean/direct-edit tests
- project save/restore tests
- touch/pinch/fit/selection smoke tests
- no fatal exception in runtime smoke
- startup and interaction jank budget tracked before/after
- no production signing fallback

## Work-budget strategy

Work is reserved for high-leverage implementation batches only. Planning, code review, architecture decisions, GitHub inspection, CI diagnosis, and acceptance review stay in normal Chat/GitHub.

Each Work batch must have:

- one bounded subsystem,
- explicit files allowed to change,
- acceptance tests,
- no unrelated UI redesign,
- one branch/PR,
- a stop condition when the acceptance gate is green.

This prevents long exploratory Work sessions from consuming quota while still allowing large refactors when they are actually needed.

## First acceptance milestone

Milestone K1 is complete when:

1. the V2 Java contract compiles,
2. the OCCT adapter can create/transform/query/release bodies through typed results,
3. at least one production modeling path is migrated to `CadKernel`,
4. old direct JNI calls remain isolated and measurable,
5. CI and runtime smoke are green.

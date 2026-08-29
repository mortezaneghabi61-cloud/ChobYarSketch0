# ChobYar / Shapr3D Workflow Parity Matrix

This is a living engineering matrix. It tracks behavior, not branding or visual copying.

Status values:
- `MISSING`
- `PROTOTYPE`
- `FUNCTIONAL`
- `PROFESSIONAL`
- `REFERENCE_GRADE`
- `NEEDS_REAL_DEVICE`

## Sketch and constraints

| Area | Expected professional behavior | ChobYar evidence to inspect | Primary gate |
|---|---|---|---|
| Line/rectangle/circle/arc | predictable creation, edit, snapping, selection | sketch primitive/snap tests, canvas input | `SketchPrimitivesSnapInstrumentationTest` |
| Spline | point/control editing, stable selection, smooth continuity | spline editing code/tests | spline interaction regression |
| Construction geometry | reference-only geometry participates in constraints without creating profile faces | construction tests | `ConstructionProjectInstrumentationTest` |
| Dimensions | driving dimensions update geometry coherently; invalid states are explicit | dimension/solver path | solver instrumentation |
| Constraints | degrees of freedom reduce correctly; conflicts are detectable | constraint solver | `SketchConstraintSolverInstrumentationTest` |
| Undo/redo | semantic sketch state restores exactly | command/history stack | `SketchUndoRedoInstrumentationTest` |
| Projection/references | projected geometry maintains durable provenance when intended | exact projection + project provenance | reference/provenance tests |

## Solid creation

| Area | Expected professional behavior | ChobYar evidence to inspect | Primary gate |
|---|---|---|---|
| Extrude | profile-to-solid, add/subtract/new-body intent, editable result | kernel + feature path | solid command tests |
| Revolve | axis/profile intent, stable result, history/reference behavior | revolve code | `RevolveThreadContractInstrumentationTest` |
| Sweep | profile/path semantics and error reporting | sweep code | `SweepCommandInstrumentationTest` |
| Loft | multiple profiles, ordering, valid topology | loft code | `LoftCommandInstrumentationTest` |
| Union/Subtract/Intersect | exact Boolean results and keep-original options | OCCT/kernel + command state | Boolean instrumentation tests |

## Direct editing and finishing

| Area | Expected professional behavior | ChobYar evidence to inspect | Primary gate |
|---|---|---|---|
| Push/Pull | selected face moves/offsets with exact topology and predictable intent | topology refs + OCCT edit | `ShellPushPullInstrumentationTest` |
| Fillet | exact edge selection, stable radius operation, failure is explicit | edge descriptors + OCCT | exact edge topology tests |
| Chamfer | exact edge selection and stable distance/angle semantics | topology refs + OCCT | exact edge topology tests |
| Shell | face removal + wall thickness, exact body result | kernel/OCCT | `ShellPushPullInstrumentationTest` |
| Face/edge selection | stable hit target and topology rematching | exact topology index | edge/face topology tests |

## History, references and persistence

| Area | Expected professional behavior | ChobYar evidence to inspect | Primary gate |
|---|---|---|---|
| Project save/restore | reopen preserves geometry, features, selections/references where applicable | project document/repository | persistence instrumentation |
| Associativity | referenced geometry updates coherently instead of silently detaching | project provenance | `AssociativeProjectProvenanceInstrumentationTest` |
| Topology stability | feature edits rematch intended faces/edges where possible | exact descriptors/history | exact topology tests |
| Undo/redo | complete document semantics restore, not just visible mesh | history/model state | workflow regression |

## Navigation, pen and touch

| Area | Expected professional behavior | ChobYar evidence to inspect | Primary gate |
|---|---|---|---|
| Pinch zoom | smooth, centered, no accidental sketch input | touch routing | `PinchZoomInstrumentationTest` |
| Orbit/pan | no conflict with drawing or object manipulation | gesture recognizers | touch contract tests |
| Pen sketching | low-latency stroke/tool interaction and reliable selection | stylus path | physical device + touch instrumentation |
| Selection | hit tolerance matches visual intent; selected body/face/edge is unambiguous | selection pipeline | selected body + touch tests |
| Context UI | controls do not obscure active geometry or system/status areas | workspace layout | production navigation tests |

## Rendering and appearance

| Area | Expected professional behavior | ChobYar evidence to inspect | Primary gate |
|---|---|---|---|
| Tessellation | derived from exact B-Rep and refreshed after edits | OCCT tessellation | native/model tests |
| Camera/render state | rendering does not mutate model | canvas/Filament integration | runtime smoke |
| Materials/appearance | visual-only state persists separately from exact geometry | appearance controller | appearance instrumentation |
| Section view | derived inspection state does not corrupt model | section controller | section instrumentation |

## Production and performance

| Area | Expected professional behavior | ChobYar evidence to inspect | Primary gate |
|---|---|---|---|
| Debug/RC builds | reproducible, installable internal builds | Gradle/Actions | assemble gates |
| Production signing | hard failure if production credentials absent | `app/build.gradle` | production hardening |
| Native arm64 | real OCCT-linked library compiles/packages | CMake/JNI | native gate |
| Startup | no fatal exception, acceptable latency | activity/workspace initialization | runtime smoke |
| Manipulation latency | sketch/direct edit remains responsive on target tablet | render/input path | real-device acceptance |

## Reference-audit procedure
For each row being improved:

1. Locate the current official Shapr3D documentation for the workflow.
2. Inspect user-owned recordings/screenshots/course material if available.
3. Record the expected interaction and model semantics.
4. Reproduce current ChobYar behavior.
5. Assign a status from the scale above.
6. Identify the owning architectural layer.
7. Add an automated regression where feasible.
8. For pen/touch/performance claims, require physical Android hardware evidence before `REFERENCE_GRADE`.

## Current known baseline
`docs/manual26100-progress.md` currently records durable coverage for sketch input/selection, undo/redo, dimensions/constraints, construction geometry, Extrude, Revolve, Boolean keep-original behavior, Fillet, Chamfer, Shell, Push/Pull, Sweep, Loft, exact OCCT edge projection, associative project references and stable exact topology rematching.

Do not interpret this as proof of full reference-grade parity. Each area still needs interaction quality, edge-case, persistence, performance and physical-device validation before being called complete.
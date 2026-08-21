# Manual 26.100 parity progress

This branch is audited against the Shapr3D 26.100 workflow contract while keeping ChobYar-owned UI and implementation.

Verified durable areas now include Sketch input/selection, Undo/Redo, dimensions and constraints, Construction geometry, Extrude, Revolve, Boolean Keep Originals / Keep Target / Keep Tool, Fillet, Chamfer, Shell, Push/Pull, Sweep, Loft, exact OCCT edge projection, associative Project references, and stable exact topology rematching.

## Current exact-topology gate

- Exact Edge descriptors are the primary source for Line/Circle/Arc identity.
- Exact Face descriptors cover Plane, Cylinder, Sphere, Cone and Torus.
- Stable History rematching returns the current OCCT subshape traversal index.
- Fillet / Chamfer / Push-Pull / Shell consume that exact current index when available.
- Nearest-anchor native selection remains fallback-only when no exact descriptor index exists.
- Display triangulation remains a rendering/fallback representation and is not the authoritative exact geometry.

## Regression gate

The consolidated Android API 35 production suite is 20 instrumentation classes / 53 tests. The arm64 native gate separately compiles and packages the real OCCT-linked `libchobyar_brep.so`.

## Next gates

- Extend exact edge descriptors beyond Line/Circle/Arc to additional analytic/parametric curve families where OCCT exposes durable signatures.
- Continue reducing compatibility reflection in production UI wiring.
- Validate installable APK interaction on a physical Android pen device before calling the workflow feature-complete.

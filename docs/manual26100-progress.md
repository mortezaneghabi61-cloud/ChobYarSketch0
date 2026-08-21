# Manual 26.100 parity progress

This project is being audited against the Shapr3D 26.100 manual while keeping ChobYar's Android workspace and interaction model independent.

Verified production areas now include Sketch input/selection, Undo/Redo, constraints, Construction geometry, Extrude, Revolve, Boolean operations including Keep Originals / Keep Target / Keep Tool, Fillet, Chamfer, Shell, Push/Pull, Sweep, Loft, exact OCCT Project-to-Sketch, selected-body projection, associative Project references, exact analytic edge topology, and exact analytic face topology.

Exact face families currently carried by the OCCT descriptor/rematch path:
- Plane
- Cylinder
- Sphere
- Cone
- Torus

The topology references are geometric signatures rather than persisted OCCT traversal indices, so History rebuilds can rematch logical faces/edges after sub-shape renumbering. Display triangulation remains a controlled fallback only for unsupported topology/surface families.

Current regression gate:
- 19 instrumentation classes
- 51 Sketch + 3D + Project + Topology contracts on Android API 35
- arm64 OCCT exact-native compile gate

Next gates:
- Extend exact topology coverage to additional analytic/freeform surface families where practical.
- Make direct-edit selection/commit paths use stable topology targets wherever an operation can otherwise depend on nearest-anchor heuristics.
- Continue on-canvas Move/Rotate and Align workflow parity and real-device interaction validation.

This file records the transition from ad-hoc patch workflows to persisted production code and durable regression coverage.

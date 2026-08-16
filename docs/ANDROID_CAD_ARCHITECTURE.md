# ChobYar 3D — Android CAD architecture

This document is the implementation contract for an independent Android CAD
product that reproduces the complete modeling workflow and interaction quality
of the reference iPad product without using its code, name, assets or private data.

## Non-negotiable product behavior

- One modeling workspace; tools never swap Activities.
- Selection drives the adaptive toolbar.
- Sketch geometry, constraints, B-Rep bodies, display meshes and UI state are
  separate data. A display triangle is never the source of truth.
- Exact dimensions are millimeters internally. The UI may display mm/cm/inch.
- Every feature owns stable input/output references and can be rebuilt in order.
- Pen hover/prediction is preview-only; committed geometry uses final samples.

## Target layers

1. **Document** — projects, items, stable IDs, visibility, units, undo/redo.
2. **Sketch** — planes, curves, profiles, dimensions and a constraint solver.
3. **Feature graph** — Extrude, Revolve, Sweep, Loft, Boolean and direct edits.
4. **OCCT kernel (C++/JNI)** — the only exact B-Rep source of truth and STEP/IGES.
5. **GPU renderer** — triangulation, PBR materials, picking buffers and overlays.
6. **Android workspace** — adaptive tools, Items, History, view cube and dialogs.

## Delivery gates

- G1: create two exact solids, Boolean, mesh, select, hide/show, undo and Fit.
- G2: constrained sketch with editable dimensions and closed-profile detection.
- G3: complete feature history with stable topology references and redo.
- G4: save/open plus STEP, STL, OBJ and DXF import/export.
- G5: GPU renderer, section view, materials, drawings and production readiness.

## Scope lock

Until parity is reached, no product-specific or trade-specific features are
added. Tool availability, adaptive UI, gestures, selection precedence, history
behavior and modeling results follow the reference workflow. Independent naming,
visual assets and implementation are mandatory.

## Current migration rule

Legacy Canvas classes remain only as a compatibility shell while their state is
moved behind explicit APIs. New production UI code must not add reflection.
Geometry operations must call `NativeBRepKernel`; analytic CSG is only a fallback
for unsupported ABI or an unfinished migration path.

## Research references

- Open CASCADE Android sample and CAD Assistant prove OCCT's mobile path.
- OCCT AIS selection separates object display from face/edge selection modes.
- Chinese systems such as ZWCAD, GstarCAD, CAXA and CrownCAD combine parametric
  history with direct/synchronous modeling rather than choosing only one mode.
- Android OpenGL ES 3.x is the renderer baseline; capability checks provide a
  controlled fallback on weaker devices.

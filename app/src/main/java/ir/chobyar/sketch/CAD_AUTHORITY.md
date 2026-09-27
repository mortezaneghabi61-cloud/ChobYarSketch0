# CAD production authority

- Android interaction/render surface: `K33MirroredCadCanvasView` (direct `View` subclass)
- Sketch model authority: `SketchDocument` + deterministic solver
- Construction plane/project relation authority: `ConstructionPlaneDocument` / project model
- Legacy feature-per-subclass Canvas hierarchy: removed
- Rule: new CAD behavior must use model/component authorities; do not add another View subclass layer.

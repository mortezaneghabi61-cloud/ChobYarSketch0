# Reference workflow acceptance — Android workspace V2

These checks convert the supplied interaction recordings into testable product
behavior. Visual similarity is not sufficient; geometry and tool state must
remain correct after every commit, cancel and history rebuild.

## Selection and navigation

- The idle rail contains only Sketch, Add, Transform and Tools. Search, units,
  construction and snapping are not permanent full-height rails.
- Project/import/export actions remain small in the top-left; undo/history and
  Items/snapping/settings remain small in the bottom corners; the view cube is
  the only persistent control in the top-right.
- Selecting geometry replaces the four-command rail with a temporary vertical
  context rail. Deselect/Close restores the idle rail immediately.
- Tap selects one region, body, face, edge or vertex with deterministic priority.
- Box selection has a visible preview and never leaves permanent guide geometry.
- Pen performs precise selection/drawing; a finger or two-finger gesture keeps
  camera navigation available while a modeling tool is active.
- Selection drives a compact adaptive toolbar. Inactive tools and every vertex
  handle are not painted over the workspace.

## Move / Rotate

- Opening the tool creates one session with explicit Done and Cancel actions.
- The gizmo attaches to the current selection and updates when selection changes.
- Axis drags are constrained; rotation uses a ring; the live value is in mm or °.
- Each gesture creates one undo step. Cancel restores the exact pre-session state.
- Camera orbit/pan/zoom does not terminate the active transform session.
- Exact 3D commits operate on the OCCT shape; the display mesh is only a preview.

## Align

- The workflow is primary selection → target face/edge/axis → preview → commit.
- Circular faces expose their analytic center/axis as snap targets.
- Reversing orientation is part of the preview and does not require rebuilding
  the source body manually.

## Sketch and dimensions

- Sketch mode alone reveals the right-side constraint rail. Closing Sketch or
  starting a modeling session removes it instead of leaving permanent clutter.
- A closed loop is selectable as one region and survives the switch to Modeling.
- A valid profile stays linked to the resulting feature instead of becoming a
  detached screen drawing.
- Bare numeric input and visible length values are millimeters.
- Only the live field, selected comparison values and explicit measurements are
  shown; duplicate labels and idle purple guide forests are prohibited.

## Bolt/nut workflow captured in recording 1409

- A stepped axial half-profile revolves into one exact body without leaving the
  sketch workspace or opening another Activity.
- Revolve recovers the closed loop and spare axis from the active sketch,
  presents a 360° body preview in the same viewport, accepts exact angle from
  the session value, and commits only through Done.
- Selecting an end face starts a face-local sketch; polygon/circle centers snap
  to the analytic face center and remain concentric.
- A selected face exposes Sketch, Move/Rotate, Scale, Extrude, Offset Edge,
  Plane/Axis and Delete in the temporary left rail.
- Face extrusion previews the Add/Remove/Intersect result directly on the body.
- Profile extrusion is canvas-native: the preview stays in the 3D workspace,
  its arrow is draggable, tapping the value accepts exact mm, orbit remains
  available, and Done/Cancel own the commit boundary. A modal slider is not an
  acceptable substitute.
- Thread creation requires a true helical feature (pitch, height and turns), not
  repeated screen circles or a decorative texture. External and internal thread
  results must remain valid OCCT B-Reps and support Section View.
- Chamfer/fillet accept multi-edge selection and rebuild after the parent
  revolve/extrude parameter changes.
- A failed sweep/revolve reports the invalid contact and preserves the last
  valid preview instead of corrupting or losing the source profile.

## Thread + reference-image workflow captured in recording 1423

- Revolve exposes Angle and axial Height in the same canvas session. Height=0
  is an ordinary revolve; non-zero Height performs a screw motion.
- Turns are derived from `abs(Angle) / 360` and Pitch is derived from
  `abs(Height) / Turns`. Example: 3600° and 25.2 mm is ten turns at 2.52 mm.
- The rotation ring and axial-height arrow are draggable. Tapping either value
  opens exact degree/mm entry without discarding the preview.
- A helical result is a closed solid, is recorded in History, rebuilds after its
  source profile changes and is generated as an OCCT B-Rep for Boolean/STEP.
- Add > Image imports a reference photo onto the active sketch plane. Exact
  width, U/V position, rotation, opacity and visibility remain editable.
- The reference photo stays registered to its plane while the camera orbits so
  a modeled thread can be compared directly against a measured real part.
- Two closed profiles on different planes Loft into one body; removing a picked
  face with Shell produces an open, consistently thick result.
- Visualization changes never alter model dimensions or feature history.
- Wood, Fabric, Plastic, Metal and Paint presets update the Filament base color,
  metallic response and editable roughness while preserving exact geometry.

## Furniture assembly workflow captured in recording 1425

- Selecting a 3D body opens one Move/Rotate session on the canvas; the modal
  X/Y/Z-only dialog is not the primary workflow.
- Three colored translation arrows and three rotation rings attach to the body
  center. Dragging produces an exact OCCT preview; tapping opens mm/degree entry.
- Done records deterministic Move/Rotate features. Cancel releases preview
  shapes and leaves both the B-Rep and feature history unchanged.
- Copy toggled inside Move/Rotate commits a separately selectable exact body,
  preserves the source body and rebuilds from the source after History changes.
- Align starts from a selected source face, waits for a destination face,
  rotates the source normal to Same/Opposed, translates the source centroid to
  the target centroid, previews the result and supports Flip before Done.
- Camera navigation remains available between manipulator gestures and while
  Align is waiting for the target face.
- Repeated legs/braces support selection as complete bodies and remain editable
  after exact Move/Rotate/Align operations.
- Items exposes body visibility and naming; reference images behave as visible
  document items rather than temporary screen screenshots.

## Drawings (later gate)

- Projection views, section/detail previews, center marks and tolerances reference
  model topology and update after the source feature history changes.

## Delivery rule

A feature is complete only after an installable APK passes the interaction checks
above on a real Android device. Dialog-only stand-ins do not satisfy an on-canvas
tool requirement.

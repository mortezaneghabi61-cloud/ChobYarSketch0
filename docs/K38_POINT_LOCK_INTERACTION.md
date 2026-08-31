# K3.8 Point Lock Interaction Contract

Point-level Lock/Unlock is model-owned by stable entity IDs and `SketchConstraint.FIXED` point indices.

Interaction routing rule: when geometry is already selected, a visible control handle is a stronger touch/pen target than the primitive body. The selected-handle path must be evaluated before empty-space/window-selection routing. This is required for handles that are intentionally off the primitive body, especially an Arc center.

Current supported point targets:

- Line handle 0/1 -> endpoint point 0/1.
- Circle handle 0 -> center point 0.
- Arc handle 0 -> center point 0.
- Circle radius and Arc start/end handles are not point-lock targets in this slice and fail closed.

The production K38 API 35 interaction test remains the acceptance fence for Finger and S Pen behavior, blocked mutation of FIXED points, editable remaining degrees of freedom, and zero legacy object-identity Lock truth.

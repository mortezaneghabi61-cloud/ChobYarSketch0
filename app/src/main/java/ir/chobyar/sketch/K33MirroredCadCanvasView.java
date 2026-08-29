package ir.chobyar.sketch;

import android.content.Context;
import android.view.MotionEvent;

import java.lang.reflect.Method;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import ir.chobyar.sketch.core.LegacySketchStateBridge;
import ir.chobyar.sketch.core.SketchDocument;
import ir.chobyar.sketch.core.SketchEntities;
import ir.chobyar.sketch.core.SketchEntity;
import ir.chobyar.sketch.core.SketchGeometry;
import ir.chobyar.sketch.core.SketchPoint;

/**
 * K3 sketch authority migration canvas.
 *
 * K3.3 established a geometry/stable-id mirror while the legacy view remained
 * authoritative. K3.4 flips authority in deliberately small slices: exact and
 * gesture Create/Move/Delete/Copy plus their Undo/Redo history are validated and
 * committed in {@link SketchDocument}, then replayed through the legacy adapter
 * for rendering/interaction compatibility. Unsupported operations still use
 * the K3.3 full-state mirror and explicitly invalidate transactional history.
 *
 * Snapping, constraints, dimensions and annotations remain legacy-owned here.
 */
public class K33MirroredCadCanvasView extends Shapr3DGuideCadCanvasView {
    private final SketchDocument sketchDocument = new SketchDocument();
    private long mirrorSyncCount;
    private long authorityTransitionCount;
    private boolean authorityHistoryValid;
    private String lastMirrorError = "";

    public K33MirroredCadCanvasView(Context context) {
        super(context);
        syncMirror("constructor");
    }

    public long sketchMirrorSyncCount() { return mirrorSyncCount; }
    public String sketchMirrorError() { return lastMirrorError; }
    public List<SketchEntity> sketchMirrorEntities() { return sketchDocument.entities(); }
    public boolean sketchAuthorityHistoryActive() { return authorityHistoryValid; }
    public boolean sketchAuthorityCanUndo() { return authorityHistoryValid && sketchDocument.canUndo(); }
    public boolean sketchAuthorityCanRedo() { return authorityHistoryValid && sketchDocument.canRedo(); }
    public long sketchAuthorityTransitionCount() { return authorityTransitionCount; }

    /** Non-throwing status used by production telemetry/UI. */
    public boolean assertSketchMirrorParity() {
        try {
            boolean ok = LegacySketchStateBridge.hasParity(sketchDocument, exportSketchProjectState());
            if (!ok) lastMirrorError = "SketchDocument geometry parity mismatch";
            return ok;
        } catch (RuntimeException e) {
            lastMirrorError = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return false;
        }
    }

    /** Strict QA hook. Never use this as a user-facing crash path. */
    public void requireSketchMirrorParity() {
        if (!assertSketchMirrorParity()) throw new IllegalStateException(lastMirrorError);
    }

    /**
     * K3.3 compatibility path. Re-hydration intentionally resets the document's
     * undo/redo history, so every fallback marks transactional authority stale.
     */
    private void syncMirror(String source) {
        try {
            String raw = exportSketchProjectState();
            LegacySketchStateBridge.restoreDocument(sketchDocument, raw);
            mirrorSyncCount++;
            authorityHistoryValid = false;
            lastMirrorError = "";
            if (!LegacySketchStateBridge.hasParity(sketchDocument, raw)) {
                lastMirrorError = "Parity failed after " + source;
            }
        } catch (RuntimeException e) {
            authorityHistoryValid = false;
            lastMirrorError = source + ": " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    /**
     * Starts or resumes one model-authority history chain without changing the
     * user's selection. A full bridge restore is allowed only at the boundary
     * from legacy-owned state into transactional authority.
     *
     * Constraints/Solver are intentionally still legacy-owned. They can mutate
     * geometry through paths that do not pass a K3.4 transactional override
     * (for example persistent coincident enforcement). Before opening the next
     * model transaction, detect that out-of-band drift and rebase the document
     * onto the settled legacy geometry. This drops only the Document history
     * that is no longer truthful; importantly it does not call legacy Undo,
     * which would discard identity-based constraint links.
     */
    private boolean prepareTransactionalDocument(String source) {
        try {
            String raw = exportSketchProjectState();
            if (!authorityHistoryValid || !LegacySketchStateBridge.hasParity(sketchDocument, raw)) {
                LegacySketchStateBridge.restoreDocument(sketchDocument, raw);
                mirrorSyncCount++;
                authorityHistoryValid = true;
            }
            return true;
        } catch (RuntimeException e) {
            authorityHistoryValid = false;
            lastMirrorError = source + ": " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            return false;
        }
    }

    /**
     * Locks and sketch-locks still belong to the legacy constraint layer in
     * K3.4. Query that precondition before mutating SketchDocument. This small
     * reflective seam is intentionally isolated here and can disappear when
     * lock/ConstraintGraph authority moves out of the View hierarchy.
     *
     * Fail closed: if the legacy guard cannot be queried, do not start a model
     * authority transaction; let the proven legacy path decide the edit.
     */
    private boolean legacySelectionLocked() {
        try {
            Method m = ParametricSketchCanvasView.class.getDeclaredMethod("isSelectionLocked");
            m.setAccessible(true);
            return Boolean.TRUE.equals(m.invoke(this));
        } catch (Exception e) {
            lastMirrorError = "legacy-lock-guard: " + e.getClass().getSimpleName();
            return true;
        }
    }

    /**
     * Temporary K3.4 compatibility seam: legacy Copy/Create currently creates
     * its own UUID internally. Authority must choose the new stable id first,
     * so replace that generated id before parity is checked. This reflection
     * disappears when legacy entity construction is replaced by a model-backed
     * renderer.
     */
    private boolean restoreLegacySelectedStableId(String stableId) {
        Object value = selected;
        if (!(value instanceof Entity)) return false;
        Class<?> type = value.getClass();
        while (type != null) {
            try {
                Method m = type.getDeclaredMethod("restoreStableId", String.class);
                m.setAccessible(true);
                m.invoke(value, stableId);
                return stableId.equals(((Entity) value).stableId());
            } catch (NoSuchMethodException e) {
                type = type.getSuperclass();
            } catch (Exception e) {
                lastMirrorError = "legacy-id-injection: " + e.getClass().getSimpleName();
                return false;
            }
        }
        lastMirrorError = "legacy-id-injection: restoreStableId unavailable";
        return false;
    }

    /**
     * Starts a fresh transactional authority chain from the current compatible
     * legacy state and mirrors the stable-id selection into SketchDocument.
     */
    private boolean prepareTransactionalSelection(String source) {
        try {
            // Constraint/lock semantics have not moved yet. Never pre-mutate the
            // new model when the legacy owner is expected to reject the edit.
            if (legacySelectionLocked()) return false;
            if (!prepareTransactionalDocument(source)) return false;

            Set<String> ids = new LinkedHashSet<>();
            for (Object value : smartSelectionSnapshot()) {
                if (value instanceof Entity) ids.add(((Entity) value).stableId());
            }
            if (ids.isEmpty() && selected != null) ids.add(selected.stableId());
            sketchDocument.setSelection(ids);
            return !ids.isEmpty();
        } catch (RuntimeException e) {
            authorityHistoryValid = false;
            lastMirrorError = source + ": " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            return false;
        }
    }

    /**
     * Verifies that the legacy adapter produced exactly the model state already
     * accepted by SketchDocument. A mismatch fails closed by undoing both sides
     * and returning to the proven K3.3 mirror seam.
     */
    private boolean finishTransactionalMutation(String source) {
        try {
            String raw = exportSketchProjectState();
            if (LegacySketchStateBridge.hasParity(sketchDocument, raw)) {
                authorityTransitionCount++;
                lastMirrorError = "";
                return true;
            }
        } catch (RuntimeException ignored) {
            // Roll back below using each side's already-created history entry.
        }

        String reason = "Transactional parity failed after " + source;
        try { if (sketchDocument.canUndo()) sketchDocument.undo(); } catch (RuntimeException ignored) {}
        try { super.undo(); } catch (RuntimeException ignored) {}
        syncMirror("authority-rollback-" + source);
        lastMirrorError = reason;
        return false;
    }

    private boolean finishTransactionalHistoryStep(String source) {
        try {
            String raw = exportSketchProjectState();
            if (LegacySketchStateBridge.hasParity(sketchDocument, raw)) {
                authorityTransitionCount++;
                lastMirrorError = "";
                return true;
            }
        } catch (RuntimeException ignored) {}
        syncMirror("history-fallback-" + source);
        return false;
    }

    private boolean isTransactionalGestureTool(int tool) {
        return tool == TOOL_POINT || tool == TOOL_LINE || tool == TOOL_RECT
                || tool == TOOL_CIRCLE || tool == TOOL_ARC
                || tool == TOOL_POLYGON || tool == TOOL_FREE;
    }

    private boolean isGestureCreateCommitAttempt(int tool, int action, boolean drawingBefore) {
        if (!isTransactionalGestureTool(tool)) return false;
        if (tool == TOOL_POINT) return action == MotionEvent.ACTION_DOWN;
        return action == MotionEvent.ACTION_UP && drawingBefore;
    }

    /**
     * Gesture Create cannot be replayed after a parity failure. Preserve the
     * user's settled legacy geometry and fall back to the K3.3 mirror instead of
     * destructively undoing a pen/touch stroke that cannot be reconstructed.
     */
    private boolean finishTransactionalGesture(String source) {
        try {
            String raw = exportSketchProjectState();
            if (LegacySketchStateBridge.hasParity(sketchDocument, raw)) {
                authorityTransitionCount++;
                lastMirrorError = "";
                return true;
            }
        } catch (RuntimeException ignored) {}
        String reason = "Gesture parity failed after " + source;
        syncMirror("gesture-fallback-" + source);
        lastMirrorError = reason;
        return false;
    }

    /**
     * Adopt the final snap/ortho/constraint-settled candidate produced by the
     * legacy touch engine. The model chooses stable identity before the legacy
     * commit; the legacy engine remains only the candidate geometry calculator.
     */
    private boolean adoptLegacyGestureCandidate(String stableId, String source) {
        try {
            if (!restoreLegacySelectedStableId(stableId)) {
                syncMirror(source + "-id-fallback");
                return false;
            }

            String raw = exportSketchProjectState();
            SketchEntity candidate = LegacySketchStateBridge.entity(raw, stableId);
            if (candidate == null || !candidate.isValid()) {
                syncMirror(source + "-candidate-fallback");
                return false;
            }

            // Legacy-owned constraint enforcement may settle existing geometry
            // during the same gesture. Rebase only the existing entities, then
            // add the newly created candidate as the one Document transaction.
            if (!LegacySketchStateBridge.hasParityExcluding(sketchDocument, raw, stableId)) {
                LegacySketchStateBridge.restoreDocumentExcluding(sketchDocument, raw, stableId);
                mirrorSyncCount++;
                authorityHistoryValid = true;
            }

            sketchDocument.add(candidate);
            sketchDocument.selectOnly(stableId);
            return finishTransactionalGesture(source);
        } catch (RuntimeException e) {
            syncMirror(source + "-exception-fallback");
            lastMirrorError = source + ": " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            return false;
        }
    }

    /**
     * A selection tap, zoom or cancelled gesture must not destroy model history.
     * Only real legacy-owned geometry drift should force a K3.3 re-hydration.
     */
    private void reconcileLegacyTouchIfNeeded(String source) {
        try {
            String raw = exportSketchProjectState();
            if (!LegacySketchStateBridge.hasParity(sketchDocument, raw)) syncMirror(source);
        } catch (RuntimeException e) {
            syncMirror(source + "-error");
        }
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (event == null) return false;

        int action = event.getActionMasked();
        int toolBefore = getTool();
        boolean drawingBefore = drawing;
        int legacyCountBefore = entities.size();

        boolean commitAttempt = isGestureCreateCommitAttempt(toolBefore, action, drawingBefore);
        String authorityId = commitAttempt ? UUID.randomUUID().toString() : null;
        boolean prepared = commitAttempt && prepareTransactionalDocument("gesture-create-prepare");

        boolean handled = super.onTouchEvent(event);

        if (commitAttempt && prepared) {
            boolean legacyCreated = entities.size() == legacyCountBefore + 1 && selected != null;
            if (legacyCreated) {
                adoptLegacyGestureCandidate(authorityId, "gesture-create");
                return handled;
            }
            // Tiny/cancelled gestures may reach ACTION_UP without creating
            // geometry. No model mutation occurred, so keep truthful history.
        }

        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL
                || (toolBefore == TOOL_POINT && action == MotionEvent.ACTION_DOWN)) {
            reconcileLegacyTouchIfNeeded("touch-legacy-mutation");
        }
        return handled;
    }

    @Override public void clearAll() { super.clearAll(); syncMirror("clear"); }

    @Override public void undo() {
        if (authorityHistoryValid && sketchDocument.canUndo()) {
            boolean changed = sketchDocument.undo();
            if (changed) {
                super.undo();
                finishTransactionalHistoryStep("undo");
                return;
            }
        }
        super.undo();
        syncMirror("undo-fallback");
    }

    @Override public boolean redoSketch() {
        if (authorityHistoryValid && sketchDocument.canRedo()) {
            boolean documentChanged = sketchDocument.redo();
            boolean legacyChanged = super.redoSketch();
            if (documentChanged && legacyChanged) {
                finishTransactionalHistoryStep("redo");
                return true;
            }
            syncMirror("redo-fallback");
            return legacyChanged;
        }
        boolean out = super.redoSketch();
        if (out) syncMirror("redo-fallback");
        return out;
    }

    @Override public void deleteSelected() {
        if (!prepareTransactionalSelection("delete-prepare")) {
            super.deleteSelected();
            syncMirror("delete-fallback");
            return;
        }
        int removed = sketchDocument.removeSelected();
        if (removed <= 0) return;
        super.deleteSelected();
        finishTransactionalMutation("delete");
    }

    @Override public void moveSelected(float dx,float dy) {
        if (!prepareTransactionalSelection("move-prepare")) {
            super.moveSelected(dx,dy);
            syncMirror("move-fallback");
            return;
        }
        boolean changed = sketchDocument.translateSelection(dx,dy);
        if (!changed) return;
        super.moveSelected(dx,dy);
        finishTransactionalMutation("move");
    }

    @Override public void copySelected(float dx,float dy) {
        if (selected == null) return;
        String sourceId = selected.stableId();
        if (!prepareTransactionalSelection("copy-prepare")) {
            super.copySelected(dx,dy);
            syncMirror("copy-fallback");
            return;
        }

        try {
            SketchEntity source = sketchDocument.entity(sourceId);
            if (source == null) {
                super.copySelected(dx,dy);
                syncMirror("copy-source-fallback");
                return;
            }

            String newId = UUID.randomUUID().toString();
            SketchEntity duplicate = SketchEntities.duplicateAs(source, newId).translated(dx, dy);
            sketchDocument.add(duplicate); // one model transaction / one Undo entry
            sketchDocument.selectOnly(newId);

            super.copySelected(dx,dy); // legacy rendering/history replay
            if (!restoreLegacySelectedStableId(newId)) {
                finishTransactionalMutation("copy-id-injection");
                return;
            }
            finishTransactionalMutation("copy");
        } catch (RuntimeException e) {
            lastMirrorError = "copy-authority: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            // A validation failure before legacy replay leaves the current model
            // and legacy state unchanged. A later parity failure is rolled back
            // by finishTransactionalMutation above.
        }
    }

    /**
     * K3.4c-a exact Create authority. Parse and validate the same deterministic
     * primitive grammar used by CadCanvasView, create the model entity first,
     * then let the legacy view replay only for rendering/history compatibility.
     * Polygon exact command creation remains on the fallback path; gesture
     * Polygon/Polyline candidates are adopted in K3.4c-b above.
     */
    private SketchEntity exactCreateEntity(String raw, String id) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty()) return null;
        String[] a = s.replace(',', ' ').trim().split("\\s+");
        String cmd = a[0].toUpperCase(java.util.Locale.US);
        try {
            if (("L".equals(cmd) || "LINE".equals(cmd)) && a.length >= 5) {
                return new SketchGeometry.Line(id,
                        new SketchGeometry.Point(Float.parseFloat(a[1]), Float.parseFloat(a[2])),
                        new SketchGeometry.Point(Float.parseFloat(a[3]), Float.parseFloat(a[4])));
            }
            if (("REC".equals(cmd) || "RECT".equals(cmd) || "RECTANG".equals(cmd)) && a.length >= 5) {
                double x=Float.parseFloat(a[1]), y=Float.parseFloat(a[2]);
                double w=Float.parseFloat(a[3]), h=Float.parseFloat(a[4]);
                return new SketchGeometry.Rect(id,
                        new SketchGeometry.Point(x,y),
                        new SketchGeometry.Vector(w,0),
                        new SketchGeometry.Vector(0,h));
            }
            if (("C".equals(cmd) || "CIRCLE".equals(cmd)) && a.length >= 4) {
                return new SketchGeometry.Circle(id,
                        new SketchGeometry.Point(Float.parseFloat(a[1]), Float.parseFloat(a[2])),
                        Math.abs(Float.parseFloat(a[3])));
            }
            if (("PO".equals(cmd) || "POINT".equals(cmd)) && a.length >= 3) {
                return new SketchPoint(id,
                        new SketchGeometry.Point(Float.parseFloat(a[1]), Float.parseFloat(a[2])));
            }
            if (("A".equals(cmd) || "ARC".equals(cmd)) && a.length >= 6) {
                return new SketchGeometry.Arc(id,
                        new SketchGeometry.Point(Float.parseFloat(a[1]), Float.parseFloat(a[2])),
                        Math.abs(Float.parseFloat(a[3])),
                        Float.parseFloat(a[4]), Float.parseFloat(a[5]));
            }
        } catch (RuntimeException ignored) {
            // Preserve the legacy command parser's user-facing error behavior.
        }
        return null;
    }

    private boolean prepareTransactionalCreate(SketchEntity entity, String source) {
        if (entity == null || !entity.isValid()) return false;
        if (!prepareTransactionalDocument(source)) return false;
        try {
            sketchDocument.add(entity); // one Create == one model Undo entry
            sketchDocument.selectOnly(entity.id());
            return true;
        } catch (RuntimeException e) {
            lastMirrorError = source + ": " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            return false;
        }
    }

    // Other unsupported operations deliberately keep the K3.3 fallback.
    @Override public String offsetSelected(float distance) { String out=super.offsetSelected(distance); syncMirror("offset"); return out; }
    @Override public String rotateSelected(float deg) { String out=super.rotateSelected(deg); syncMirror("rotate"); return out; }
    @Override public String scaleSelected(float factor) { String out=super.scaleSelected(factor); syncMirror("scale"); return out; }
    @Override public String mirrorSelected(boolean acrossXAxis,float axisValue) { String out=super.mirrorSelected(acrossXAxis,axisValue); syncMirror("mirror"); return out; }
    @Override public String arraySelected(int count,float dx,float dy) { String out=super.arraySelected(count,dx,dy); syncMirror("array"); return out; }

    // Advanced 2D editing can mutate entities without passing through the base mutators.
    @Override public String trimSelectedLines() { String out=super.trimSelectedLines(); syncMirror("trim"); return out; }
    @Override public String extendSelectedLines() { String out=super.extendSelectedLines(); syncMirror("extend"); return out; }
    @Override public String chamferSelectedLines(float setback) { String out=super.chamferSelectedLines(setback); syncMirror("sketch-chamfer"); return out; }
    @Override public String filletSelectedLines(float radius) { String out=super.filletSelectedLines(radius); syncMirror("sketch-fillet"); return out; }
    @Override public String joinSelectedLines() { String out=super.joinSelectedLines(); syncMirror("join"); return out; }

    // Driving dimensions and constraints remain legacy-owned in this K3.4 slice.
    @Override public String applySelectedDimension(String raw) { String out=super.applySelectedDimension(raw); syncMirror("dimension"); return out; }
    @Override public String setSelectedLineAngle(float degrees) { String out=super.setSelectedLineAngle(degrees); syncMirror("line-angle"); return out; }
    @Override public String setSelectedLinesAngle(float degrees) { String out=super.setSelectedLinesAngle(degrees); syncMirror("lines-angle"); return out; }
    @Override public String applyHorizontalVerticalConstraint() { String out=super.applyHorizontalVerticalConstraint(); syncMirror("constraint-hv"); return out; }
    @Override public String applyPerpendicularConstraint() { String out=super.applyPerpendicularConstraint(); syncMirror("constraint-perpendicular"); return out; }
    @Override public String applyParallelConstraint() { String out=super.applyParallelConstraint(); syncMirror("constraint-parallel"); return out; }
    @Override public String applyEqualConstraint() { String out=super.applyEqualConstraint(); syncMirror("constraint-equal"); return out; }
    @Override public String applySymmetryConstraint() { String out=super.applySymmetryConstraint(); syncMirror("constraint-symmetry"); return out; }
    @Override public String applyMidpointConstraint() { String out=super.applyMidpointConstraint(); syncMirror("constraint-midpoint"); return out; }
    @Override public String applyTangentConstraint() { String out=super.applyTangentConstraint(); syncMirror("constraint-tangent"); return out; }
    @Override public String applyConcentricConstraint() { String out=super.applyConcentricConstraint(); syncMirror("constraint-concentric"); return out; }
    @Override public String disconnectSelectedConnections() { String out=super.disconnectSelectedConnections(); syncMirror("constraint-disconnect"); return out; }

    @Override public String importSketchProjectState(String raw) { String out=super.importSketchProjectState(raw); syncMirror("project-open"); return out; }

    @Override public String executeCommand(String raw) {
        String newId = UUID.randomUUID().toString();
        SketchEntity planned = exactCreateEntity(raw, newId);
        if (planned != null && prepareTransactionalCreate(planned, "create-command-prepare")) {
            int legacyCountBefore = entities.size();
            String out = super.executeCommand(raw);
            boolean legacyCreated = entities.size() == legacyCountBefore + 1 && selected != null;
            if (!legacyCreated) {
                try { if (sketchDocument.canUndo()) sketchDocument.undo(); } catch (RuntimeException ignored) {}
                syncMirror("create-command-rejected");
                return out;
            }

            boolean injected = restoreLegacySelectedStableId(newId);
            boolean committed = injected && finishTransactionalMutation("create-command");
            if (committed) return out;
            if (!injected) finishTransactionalMutation("create-command-id-injection");

            // Fail-safe UX: parity failure rolls both sides back; replay the
            // proven legacy command once and re-enter K3.3 mirror mode.
            String fallback = super.executeCommand(raw);
            syncMirror("create-command-fallback");
            return fallback;
        }

        long before = authorityTransitionCount;
        String out = super.executeCommand(raw);
        // Dynamic dispatch lets supported K3.4 commands use transactional
        // authority. Unsupported commands still invalidate document history.
        if (authorityTransitionCount == before) syncMirror("command");
        return out;
    }
}

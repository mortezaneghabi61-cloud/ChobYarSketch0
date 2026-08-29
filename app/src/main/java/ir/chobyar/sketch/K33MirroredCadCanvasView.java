package ir.chobyar.sketch;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PointF;
import android.view.MotionEvent;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import ir.chobyar.sketch.core.DeterministicSketchConstraintSolver;
import ir.chobyar.sketch.core.LegacySketchStateBridge;
import ir.chobyar.sketch.core.SketchConstraint;
import ir.chobyar.sketch.core.SketchConstraintSolver;
import ir.chobyar.sketch.core.SketchDocument;
import ir.chobyar.sketch.core.SketchEntities;
import ir.chobyar.sketch.core.SketchEntity;
import ir.chobyar.sketch.core.SketchGeometry;
import ir.chobyar.sketch.core.SketchPoint;
import ir.chobyar.sketch.core.SketchSnapService;

/**
 * Sketch authority migration canvas.
 *
 * K3.4 owns exact/gesture Create, Move, Delete, Copy and transactional history
 * in SketchDocument. K3.5 moves Create snapping into the model. K3.6 starts
 * moving persistent constraints/solving out of the legacy View hierarchy.
 * Dimensions, annotations and remaining constraint kinds stay legacy-owned
 * until their dedicated authority slices.
 */
public class K33MirroredCadCanvasView extends Shapr3DGuideCadCanvasView {
    private static final float LEGACY_SNAP_RADIUS_PX = 30f;
    private static final double GUIDE_RADIUS_FACTOR = 0.70d;
    private static final double GRID_RADIUS_FACTOR = 0.58d;
    private static final double GRID_MM = 10.0d;

    private final SketchDocument sketchDocument = new SketchDocument();
    private final SketchSnapService sketchSnapService = new SketchSnapService();
    private final SketchConstraintSolver sketchConstraintSolver = new DeterministicSketchConstraintSolver();
    private final Paint routedSnapPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint routedSnapTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private long mirrorSyncCount;
    private long authorityTransitionCount;
    private long modelSnapCount;
    private boolean authorityHistoryValid;
    private String lastMirrorError = "";
    private String lastModelSnapKind = "";
    private boolean routedSnapVisible;
    private float routedSnapScreenX;
    private float routedSnapScreenY;
    private String routedSnapLabel = "";

    public K33MirroredCadCanvasView(Context context) {
        super(context);
        routedSnapPaint.setColor(Color.rgb(245, 135, 15));
        routedSnapPaint.setStyle(Paint.Style.STROKE);
        routedSnapPaint.setStrokeWidth(2f * getResources().getDisplayMetrics().density);
        routedSnapTextPaint.setColor(Color.rgb(35, 85, 180));
        routedSnapTextPaint.setTextSize(13f * getResources().getDisplayMetrics().scaledDensity);
        syncMirror("constructor");
    }

    public long sketchMirrorSyncCount() { return mirrorSyncCount; }
    public String sketchMirrorError() { return lastMirrorError; }
    public List<SketchEntity> sketchMirrorEntities() { return sketchDocument.entities(); }
    public List<SketchConstraint> sketchConstraints() { return sketchDocument.constraints(); }
    public int sketchConstraintCount() { return sketchDocument.constraintCount(); }
    public boolean sketchAuthorityHistoryActive() { return authorityHistoryValid; }
    public boolean sketchAuthorityCanUndo() { return authorityHistoryValid && sketchDocument.canUndo(); }
    public boolean sketchAuthorityCanRedo() { return authorityHistoryValid && sketchDocument.canRedo(); }
    public long sketchAuthorityTransitionCount() { return authorityTransitionCount; }
    public long sketchModelSnapCount() { return modelSnapCount; }
    public String sketchLastModelSnapKind() { return lastModelSnapKind; }

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

    public void requireSketchMirrorParity() {
        if (!assertSketchMirrorParity()) throw new IllegalStateException(lastMirrorError);
    }

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

    private boolean prepareTransactionalSelection(String source) {
        try {
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

    private List<String> selectedModelLineIds() {
        ArrayList<String> out = new ArrayList<>();
        for (String id : sketchDocument.selectionIds()) {
            SketchEntity entity = sketchDocument.entity(id);
            if (entity instanceof SketchGeometry.Line) out.add(id);
        }
        return out;
    }

    private Entity legacyEntityByStableId(String stableId) {
        if (stableId == null) return null;
        for (Entity entity : entities) {
            if (entity != null && stableId.equals(entity.stableId())) return entity;
        }
        return null;
    }

    /**
     * Legacy is now a compatibility renderer/history adapter for migrated line
     * constraints. Replay the model-solved geometry by stable id so the View can
     * never choose a different mathematical outcome from SketchDocument.
     */
    private void replaySolvedLineGeometryToLegacy() {
        for (SketchEntity value : sketchDocument.entities()) {
            if (!(value instanceof SketchGeometry.Line)) continue;
            SketchGeometry.Line solved = (SketchGeometry.Line) value;
            Entity legacy = legacyEntityByStableId(solved.id());
            if (legacy == null) continue;
            legacy.moveControlPoint(0, (float) solved.a.xMm, (float) solved.a.yMm);
            legacy.moveControlPoint(1, (float) solved.b.xMm, (float) solved.b.yMm);
        }
        invalidate();
    }

    private String modelConstraintFailure(String source, RuntimeException e) {
        lastMirrorError = source + ": " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        return "Constraint could not be solved; geometry was left unchanged";
    }

    private boolean finishTransactionalMutation(String source) {
        try {
            String raw = exportSketchProjectState();
            if (LegacySketchStateBridge.hasParity(sketchDocument, raw)) {
                authorityTransitionCount++;
                lastMirrorError = "";
                return true;
            }
        } catch (RuntimeException ignored) {}

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

    private void reconcileLegacyTouchIfNeeded(String source) {
        try {
            String raw = exportSketchProjectState();
            if (!LegacySketchStateBridge.hasParity(sketchDocument, raw)) syncMirror(source);
        } catch (RuntimeException e) {
            syncMirror(source + "-error");
        }
    }

    private boolean usesModelCreateSnap(int tool) {
        return tool == TOOL_POINT || tool == TOOL_LINE || tool == TOOL_RECT
                || tool == TOOL_CIRCLE || tool == TOOL_ARC || tool == TOOL_POLYGON;
    }

    private static final class RoutedSnap {
        final double xMm;
        final double yMm;
        final double distanceMm;
        final String label;
        final SketchSnapService.Kind modelKind;

        RoutedSnap(double xMm, double yMm, double distanceMm, String label,
                   SketchSnapService.Kind modelKind) {
            this.xMm = xMm;
            this.yMm = yMm;
            this.distanceMm = distanceMm;
            this.label = label == null ? "" : label;
            this.modelKind = modelKind;
        }
    }

    private RoutedSnap modelGuideGridSnap(float rawX, float rawY, float radiusMm) {
        RoutedSnap best = null;
        try {
            SketchSnapService.Result model = sketchSnapService.snap(
                    sketchDocument,
                    new SketchGeometry.Point(rawX, rawY),
                    radiusMm,
                    null);
            if (model != null) {
                best = new RoutedSnap(model.point.xMm, model.point.yMm, model.distanceMm,
                        snapLabel(model.kind), model.kind);
            }
        } catch (RuntimeException e) {
            lastMirrorError = "model-snap: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }

        if (isShowGuides()) {
            for (Entity entity : entities) {
                if (entity == null || !coreIsVisible(entity)) continue;
                String description = entity.describe();
                if (description == null || !description.startsWith("Guide ")) continue;
                PointF p = entity.nearestPoint(rawX, rawY);
                if (p == null) continue;
                double d = Math.hypot(p.x - rawX, p.y - rawY);
                if (d <= radiusMm * GUIDE_RADIUS_FACTOR
                        && (best == null || d < best.distanceMm)) {
                    best = new RoutedSnap(p.x, p.y, d, "Guide", null);
                }
            }
        }

        if (best == null) {
            double gx = Math.rint(rawX / GRID_MM) * GRID_MM;
            double gy = Math.rint(rawY / GRID_MM) * GRID_MM;
            double d = Math.hypot(gx - rawX, gy - rawY);
            if (d <= radiusMm * GRID_RADIUS_FACTOR) {
                best = new RoutedSnap(gx, gy, d, "Grid", null);
            }
        }
        return best;
    }

    private String snapLabel(SketchSnapService.Kind kind) {
        if (kind == null) return "";
        switch (kind) {
            case POINT: return "Point";
            case ENDPOINT: return "Endpoint";
            case INTERSECTION: return "Intersection";
            case MIDPOINT: return "Midpoint";
            case CENTER: return "Center";
            case QUADRANT: return "Quadrant";
            case ON_EDGE:
            default: return "On edge";
        }
    }

    /**
     * K3.5b touch routing. Geometry is chosen by SketchSnapService, while guide
     * and grid remain interaction-layer concerns. Legacy geometric snap is
     * disabled only while the routed Create event is replayed, preventing it
     * from overriding model semantics (notably phantom full-circle Arc snaps).
     */
    private MotionEvent routeCreateSnap(MotionEvent event, int tool) {
        routedSnapVisible = false;
        routedSnapLabel = "";
        lastModelSnapKind = "";
        if (event == null || event.getPointerCount() != 1 || !isSnapEnabled()
                || !usesModelCreateSnap(tool)) return event;
        int action = event.getActionMasked();
        if (action != MotionEvent.ACTION_DOWN && action != MotionEvent.ACTION_MOVE
                && action != MotionEvent.ACTION_UP) return event;

        float rawX = coreScreenToWorldX(event.getX());
        float rawY = coreScreenToWorldY(event.getY());
        float radiusMm = Math.abs(coreScreenToWorldX(event.getX() + LEGACY_SNAP_RADIUS_PX) - rawX);
        if (!(radiusMm > 0f) || Float.isInfinite(radiusMm) || Float.isNaN(radiusMm)) return event;

        RoutedSnap snap = modelGuideGridSnap(rawX, rawY, radiusMm);
        if (snap == null) return event;

        float worldPerPixelX = coreScreenToWorldX(event.getX() + 1f) - rawX;
        float worldPerPixelY = coreScreenToWorldY(event.getY() + 1f) - rawY;
        if (Math.abs(worldPerPixelX) < 1.0e-9f || Math.abs(worldPerPixelY) < 1.0e-9f) return event;

        float screenX = event.getX() + (float)((snap.xMm - rawX) / worldPerPixelX);
        float screenY = event.getY() + (float)((snap.yMm - rawY) / worldPerPixelY);
        MotionEvent routed = MotionEvent.obtain(event);
        routed.setLocation(screenX, screenY);

        routedSnapVisible = action != MotionEvent.ACTION_UP;
        routedSnapScreenX = screenX;
        routedSnapScreenY = screenY;
        routedSnapLabel = snap.label;
        if (snap.modelKind != null) {
            modelSnapCount++;
            lastModelSnapKind = snap.modelKind.name();
        }
        return routed;
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (!routedSnapVisible) return;
        float density = getResources().getDisplayMetrics().density;
        float s = 8f * density;
        canvas.drawRect(routedSnapScreenX - s, routedSnapScreenY - s,
                routedSnapScreenX + s, routedSnapScreenY + s, routedSnapPaint);
        if (!routedSnapLabel.isEmpty()) {
            canvas.drawText(routedSnapLabel, routedSnapScreenX + 11f * density,
                    routedSnapScreenY - 9f * density, routedSnapTextPaint);
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

        boolean routedCreateSnap = event.getPointerCount() == 1 && isSnapEnabled() && usesModelCreateSnap(toolBefore);
        MotionEvent routedEvent = routedCreateSnap ? routeCreateSnap(event, toolBefore) : event;
        if (routedCreateSnap) toggleSnap();
        boolean handled;
        try {
            handled = super.onTouchEvent(routedEvent);
        } finally {
            if (routedCreateSnap) toggleSnap();
            if (routedEvent != event) routedEvent.recycle();
        }

        if (commitAttempt && prepared) {
            boolean legacyCreated = entities.size() == legacyCountBefore + 1 && selected != null;
            if (legacyCreated) {
                adoptLegacyGestureCandidate(authorityId, "gesture-create");
                routedSnapVisible = false;
                return handled;
            }
        }

        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL
                || (toolBefore == TOOL_POINT && action == MotionEvent.ACTION_DOWN)) {
            reconcileLegacyTouchIfNeeded("touch-legacy-mutation");
            routedSnapVisible = false;
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
                replaySolvedLineGeometryToLegacy();
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
        try {
            boolean constrained = sketchDocument.constraintCount() > 0;
            if (constrained) {
                sketchDocument.translateSelectionAndSolve(dx, dy, sketchConstraintSolver);
            } else {
                boolean changed = sketchDocument.translateSelection(dx,dy);
                if (!changed) return;
            }
            super.moveSelected(dx,dy);
            if (constrained) replaySolvedLineGeometryToLegacy();
            finishTransactionalMutation("move");
        } catch (RuntimeException e) {
            lastMirrorError = "move-authority: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
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
            sketchDocument.add(duplicate);
            sketchDocument.selectOnly(newId);

            super.copySelected(dx,dy);
            if (!restoreLegacySelectedStableId(newId)) {
                finishTransactionalMutation("copy-id-injection");
                return;
            }
            finishTransactionalMutation("copy");
        } catch (RuntimeException e) {
            lastMirrorError = "copy-authority: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

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
        } catch (RuntimeException ignored) {}
        return null;
    }

    private boolean prepareTransactionalCreate(SketchEntity entity, String source) {
        if (entity == null || !entity.isValid()) return false;
        if (!prepareTransactionalDocument(source)) return false;
        try {
            sketchDocument.add(entity);
            sketchDocument.selectOnly(entity.id());
            return true;
        } catch (RuntimeException e) {
            lastMirrorError = source + ": " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            return false;
        }
    }

    @Override public String offsetSelected(float distance) { String out=super.offsetSelected(distance); syncMirror("offset"); return out; }
    @Override public String rotateSelected(float deg) { String out=super.rotateSelected(deg); syncMirror("rotate"); return out; }
    @Override public String scaleSelected(float factor) { String out=super.scaleSelected(factor); syncMirror("scale"); return out; }
    @Override public String mirrorSelected(boolean acrossXAxis,float axisValue) { String out=super.mirrorSelected(acrossXAxis,axisValue); syncMirror("mirror"); return out; }
    @Override public String arraySelected(int count,float dx,float dy) { String out=super.arraySelected(count,dx,dy); syncMirror("array"); return out; }

    @Override public String trimSelectedLines() { String out=super.trimSelectedLines(); syncMirror("trim"); return out; }
    @Override public String extendSelectedLines() { String out=super.extendSelectedLines(); syncMirror("extend"); return out; }
    @Override public String chamferSelectedLines(float setback) { String out=super.chamferSelectedLines(setback); syncMirror("sketch-chamfer"); return out; }
    @Override public String filletSelectedLines(float radius) { String out=super.filletSelectedLines(radius); syncMirror("sketch-fillet"); return out; }
    @Override public String joinSelectedLines() { String out=super.joinSelectedLines(); syncMirror("join"); return out; }

    @Override public String applySelectedDimension(String raw) { String out=super.applySelectedDimension(raw); syncMirror("dimension"); return out; }
    @Override public String setSelectedLineAngle(float degrees) { String out=super.setSelectedLineAngle(degrees); syncMirror("line-angle"); return out; }
    @Override public String setSelectedLinesAngle(float degrees) { String out=super.setSelectedLinesAngle(degrees); syncMirror("lines-angle"); return out; }

    @Override public String applyHorizontalVerticalConstraint() {
        if (!prepareTransactionalSelection("constraint-hv-prepare")) {
            String out=super.applyHorizontalVerticalConstraint();
            syncMirror("constraint-hv-fallback");
            return out;
        }
        List<String> ids=selectedModelLineIds();
        if (ids.isEmpty()) return "Select one or more lines for H/V";
        ArrayList<SketchConstraint> incoming=new ArrayList<>();
        for (String id:ids) {
            SketchGeometry.Line line=(SketchGeometry.Line)sketchDocument.entity(id);
            double dx=line.b.xMm-line.a.xMm;
            double dy=line.b.yMm-line.a.yMm;
            incoming.add(Math.abs(dx)>=Math.abs(dy)
                    ? SketchConstraint.horizontal(UUID.randomUUID().toString(),id)
                    : SketchConstraint.vertical(UUID.randomUUID().toString(),id));
        }
        try {
            sketchDocument.addConstraintsAndSolve(incoming,sketchConstraintSolver);
            String out=super.applyHorizontalVerticalConstraint();
            replaySolvedLineGeometryToLegacy();
            finishTransactionalMutation("constraint-hv");
            return out;
        } catch (RuntimeException e) {
            return modelConstraintFailure("constraint-hv",e);
        }
    }

    @Override public String applyPerpendicularConstraint() {
        if (!prepareTransactionalSelection("constraint-perpendicular-prepare")) {
            String out=super.applyPerpendicularConstraint();
            syncMirror("constraint-perpendicular-fallback");
            return out;
        }
        List<String> ids=selectedModelLineIds();
        if (ids.size()!=2) return "Select exactly two lines for Perpendicular";
        try {
            SketchConstraint c=SketchConstraint.perpendicular(UUID.randomUUID().toString(),ids.get(0),ids.get(1));
            sketchDocument.addConstraintsAndSolve(java.util.Collections.singletonList(c),sketchConstraintSolver);
            String out=super.applyPerpendicularConstraint();
            replaySolvedLineGeometryToLegacy();
            finishTransactionalMutation("constraint-perpendicular");
            return out;
        } catch (RuntimeException e) {
            return modelConstraintFailure("constraint-perpendicular",e);
        }
    }

    @Override public String applyParallelConstraint() {
        if (!prepareTransactionalSelection("constraint-parallel-prepare")) {
            String out=super.applyParallelConstraint();
            syncMirror("constraint-parallel-fallback");
            return out;
        }
        List<String> ids=selectedModelLineIds();
        if (ids.size()!=2) return "Select exactly two lines for Parallel";
        try {
            SketchConstraint c=SketchConstraint.parallel(UUID.randomUUID().toString(),ids.get(0),ids.get(1));
            sketchDocument.addConstraintsAndSolve(java.util.Collections.singletonList(c),sketchConstraintSolver);
            String out=super.applyParallelConstraint();
            replaySolvedLineGeometryToLegacy();
            finishTransactionalMutation("constraint-parallel");
            return out;
        } catch (RuntimeException e) {
            return modelConstraintFailure("constraint-parallel",e);
        }
    }

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

            String fallback = super.executeCommand(raw);
            syncMirror("create-command-fallback");
            return fallback;
        }

        long before = authorityTransitionCount;
        String out = super.executeCommand(raw);
        if (authorityTransitionCount == before) syncMirror("command");
        return out;
    }
}

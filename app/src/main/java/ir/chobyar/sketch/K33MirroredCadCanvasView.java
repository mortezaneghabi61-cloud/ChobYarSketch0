package ir.chobyar.sketch;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.PointF;
import android.view.MotionEvent;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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
 * in SketchDocument. K3.5 moves Create snapping into the model. K3.6 moves
 * persistent line constraints/solving out of the legacy View hierarchy.
 * Dimensions, annotations and remaining constraint kinds stay legacy-owned
 * until their dedicated authority slices.
 */
public class K33MirroredCadCanvasView extends Shapr3DGuideCadCanvasView {
    private static final float LEGACY_SNAP_RADIUS_PX = 30f;
    private static final double GUIDE_RADIUS_FACTOR = 0.70d;
    private static final double GRID_RADIUS_FACTOR = 0.58d;
    private static final double GRID_MM = 10.0d;
    private static final float MODEL_PX_PER_MM = 3f;

    public enum ConstraintAnchorPolicy { FIRST_SELECTED, LAST_SELECTED }

    private final SketchDocument sketchDocument = new SketchDocument();
    private final SketchSnapService sketchSnapService = new SketchSnapService();
    private final SketchConstraintSolver sketchConstraintSolver = new DeterministicSketchConstraintSolver();
    private final Paint routedSnapPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint routedSnapTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint modelConstraintPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint modelConstraintTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

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
    private ConstraintAnchorPolicy constraintAnchorPolicy = ConstraintAnchorPolicy.FIRST_SELECTED;

    public K33MirroredCadCanvasView(Context context) {
        super(context);
        routedSnapPaint.setColor(Color.rgb(245, 135, 15));
        routedSnapPaint.setStyle(Paint.Style.STROKE);
        routedSnapPaint.setStrokeWidth(2f * getResources().getDisplayMetrics().density);
        routedSnapTextPaint.setColor(Color.rgb(35, 85, 180));
        routedSnapTextPaint.setTextSize(13f * getResources().getDisplayMetrics().scaledDensity);

        modelConstraintPaint.setColor(Color.rgb(45, 125, 225));
        modelConstraintPaint.setStyle(Paint.Style.STROKE);
        modelConstraintPaint.setStrokeWidth(1.5f * getResources().getDisplayMetrics().density);
        modelConstraintPaint.setPathEffect(new DashPathEffect(new float[]{8f, 7f}, 0f));
        modelConstraintTextPaint.setColor(Color.rgb(35, 105, 205));
        modelConstraintTextPaint.setTextSize(13f * getResources().getDisplayMetrics().scaledDensity);
        modelConstraintTextPaint.setTextAlign(Paint.Align.CENTER);
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
    public ConstraintAnchorPolicy constraintAnchorPolicy() { return constraintAnchorPolicy; }
    public void setConstraintAnchorPolicy(ConstraintAnchorPolicy policy) {
        if (policy == null) throw new NullPointerException("policy");
        constraintAnchorPolicy = policy;
    }
    public int modelConstraintFeedbackCount() { return sketchDocument.constraintCount(); }

    /** Test/diagnostic seam proving migrated constraints did not populate legacy View truth. */
    public int legacyMigratedConstraintTruthCount() {
        return reflectedStoreSize("axisLocks") + reflectedStoreSize("lineRelations")
                + reflectedStoreSize("coincidenceLinks") + legacyPointOnLineTruthCount();
    }

    /** K3.8 fence: production Lock must not write the inherited object-identity map. */
    public int legacySelectionLockTruthCount() {
        try {
            Field field = ParametricSketchCanvasView.class.getDeclaredField("elementLocks");
            field.setAccessible(true);
            Object value = field.get(this);
            if (value instanceof Map) return ((Map<?, ?>) value).size();
        } catch (Exception ignored) {}
        return -1000;
    }

    private int reflectedStoreSize(String fieldName) {
        try {
            Field field = ChobYarShaprCanvasView.class.getDeclaredField(fieldName);
            field.setAccessible(true);
            Object value = field.get(this);
            if (value instanceof Map) return ((Map<?, ?>) value).size();
            if (value instanceof Collection) return ((Collection<?>) value).size();
        } catch (Exception ignored) {}
        return -1000;
    }

    public boolean assertSketchMirrorParity() {
        try {
            boolean ok = LegacySketchStateBridge.hasParity(sketchDocument, exportSketchProjectState());
            if (!ok) lastMirrorError = "SketchDocument geometry/constraint parity mismatch";
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
            boolean preserveHistory = authorityHistoryValid
                    && LegacySketchStateBridge.hasParity(sketchDocument, raw);
            if (!preserveHistory) {
                LegacySketchStateBridge.restoreDocument(sketchDocument, raw);
                mirrorSyncCount++;
                authorityHistoryValid = false;
            }
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
            replayAuthoritativeConstrainedGeometryBeforeDraw();
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

    private int constraintAuthorityScore(String entityId) {
        int score = 0;
        for (SketchConstraint c : sketchDocument.constraintsForEntity(entityId)) {
            if (!c.driving) continue;
            switch (c.kind) {
                case FIXED: score += 1000; break;
                case DISTANCE:
                case RADIUS:
                case ANGLE: score += 250; break;
                case HORIZONTAL:
                case VERTICAL: score += 120; break;
                case COINCIDENT:
                case POINT_ON_ENTITY:
                case MIDPOINT: score += 100; break;
                case PARALLEL:
                case PERPENDICULAR: score += 60; break;
                default: score += 30; break;
            }
        }
        return score;
    }

    private int chooseConstraintAnchorIndex(List<String> ids) {
        if (ids == null || ids.isEmpty()) return -1;
        int max = 0;
        for (String id : ids) max = Math.max(max, constraintAuthorityScore(id));
        if (max <= 0) return constraintAnchorPolicy == ConstraintAnchorPolicy.FIRST_SELECTED ? 0 : ids.size() - 1;
        if (constraintAnchorPolicy == ConstraintAnchorPolicy.FIRST_SELECTED) {
            for (int i = 0; i < ids.size(); i++) if (constraintAuthorityScore(ids.get(i)) == max) return i;
        } else {
            for (int i = ids.size() - 1; i >= 0; i--) if (constraintAuthorityScore(ids.get(i)) == max) return i;
        }
        return 0;
    }

    private Entity legacyEntityByStableId(String stableId) {
        if (stableId == null) return null;
        for (Entity entity : entities) {
            if (entity != null && stableId.equals(entity.stableId())) return entity;
        }
        return null;
    }

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

    private void replayAuthoritativeConstrainedGeometryBeforeDraw() {
        LinkedHashSet<String> constrained = new LinkedHashSet<>();
        for (SketchConstraint c : sketchDocument.constraints()) {
            if (c.kind != SketchConstraint.Kind.HORIZONTAL
                    && c.kind != SketchConstraint.Kind.VERTICAL
                    && c.kind != SketchConstraint.Kind.PARALLEL
                    && c.kind != SketchConstraint.Kind.PERPENDICULAR
                    && c.kind != SketchConstraint.Kind.COINCIDENT
                    && c.kind != SketchConstraint.Kind.POINT_ON_ENTITY
                    && c.kind != SketchConstraint.Kind.MIDPOINT
                    && c.kind != SketchConstraint.Kind.FIXED) continue;
            constrained.addAll(c.referencedEntityIds());
        }
        for (String id : constrained) {
            SketchEntity value = sketchDocument.entity(id);
            if (!(value instanceof SketchGeometry.Line)) continue;
            Entity legacy = legacyEntityByStableId(id);
            if (legacy == null) continue;
            SketchGeometry.Line solved = (SketchGeometry.Line) value;
            legacy.moveControlPoint(0, (float) solved.a.xMm, (float) solved.a.yMm);
            legacy.moveControlPoint(1, (float) solved.b.xMm, (float) solved.b.yMm);
        }
    }

    @Override protected boolean isModelEndpointConstraintAuthorityEnabled() { return true; }

    private boolean validLinePointRef(ConstraintInteractionContract.PointRef ref) {
        if (ref == null || ref.pointIndex < 0 || ref.pointIndex > 1) return false;
        return sketchDocument.entity(ref.entityId) instanceof SketchGeometry.Line;
    }

    private ConstraintInteractionContract.Result applyEndpointConstraintIntent(
            ConstraintInteractionContract.Intent intent, String source) {
        if (intent == null || !validLinePointRef(intent.drivenPoint)) {
            return ConstraintInteractionContract.Result.invalidSelection("Endpoint reference is invalid");
        }
        try {
            SketchConstraint constraint;
            if (intent.kind == ConstraintInteractionContract.Kind.COINCIDENT) {
                if (!validLinePointRef(intent.targetPoint)) {
                    return ConstraintInteractionContract.Result.invalidSelection("Coincident target endpoint is invalid");
                }
                constraint = SketchConstraint.coincident(UUID.randomUUID().toString(),
                        intent.drivenPoint.entityId, intent.drivenPoint.pointIndex,
                        intent.targetPoint.entityId, intent.targetPoint.pointIndex);
            } else {
                if (intent.hostEntityId == null || sketchDocument.entity(intent.hostEntityId) == null
                        || intent.drivenPoint.entityId.equals(intent.hostEntityId)) {
                    return ConstraintInteractionContract.Result.invalidSelection("Point-on-Entity host is invalid");
                }
                constraint = SketchConstraint.pointOnEntity(UUID.randomUUID().toString(),
                        intent.drivenPoint.entityId, intent.drivenPoint.pointIndex, intent.hostEntityId);
            }
            sketchDocument.addConstraintsAndSolve(java.util.Collections.singletonList(constraint), sketchConstraintSolver);
            coreSaveUndo();
            replaySolvedLineGeometryToLegacy();
            if (!finishTransactionalMutation(source)) {
                return ConstraintInteractionContract.Result.unsolvable("Constraint rollback: parity failed");
            }
            return ConstraintInteractionContract.Result.applied();
        } catch (IllegalArgumentException e) {
            lastMirrorError = source + ": " + e.getMessage();
            return ConstraintInteractionContract.Result.invalidSelection(e.getMessage());
        } catch (RuntimeException e) {
            lastMirrorError = source + ": " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            return ConstraintInteractionContract.Result.unsolvable(
                    "Constraint could not be solved; geometry was left unchanged");
        }
    }

    @Override protected ConstraintInteractionContract.Result onModelCoincidentRequested(
            ConstraintInteractionContract.Intent intent) {
        if (intent == null || intent.kind != ConstraintInteractionContract.Kind.COINCIDENT) {
            return ConstraintInteractionContract.Result.invalidSelection("Coincident intent is invalid");
        }
        if (!prepareTransactionalDocument("constraint-coincident-prepare")) {
            return ConstraintInteractionContract.Result.unsolvable(lastMirrorError);
        }
        return applyEndpointConstraintIntent(intent, "constraint-coincident");
    }

    @Override protected ConstraintInteractionContract.Result onModelPointOnEntityRequested(
            ConstraintInteractionContract.Intent intent) {
        if (intent == null || intent.kind != ConstraintInteractionContract.Kind.POINT_ON_ENTITY) {
            return ConstraintInteractionContract.Result.invalidSelection("Point-on-Entity intent is invalid");
        }
        if (!prepareTransactionalDocument("constraint-point-on-entity-prepare")) {
            return ConstraintInteractionContract.Result.unsolvable(lastMirrorError);
        }
        return applyEndpointConstraintIntent(intent, "constraint-point-on-entity");
    }

    public ConstraintInteractionContract.Result applyModelCoincidentForTest(
            String drivenId, int drivenPoint, String targetId, int targetPoint) {
        return onModelCoincidentRequested(ConstraintInteractionContract.Intent.coincident(
                new ConstraintInteractionContract.PointRef(drivenId, drivenPoint),
                new ConstraintInteractionContract.PointRef(targetId, targetPoint)));
    }

    public ConstraintInteractionContract.Result applyModelPointOnEntityForTest(
            String drivenId, int drivenPoint, String hostId) {
        return onModelPointOnEntityRequested(ConstraintInteractionContract.Intent.pointOnEntity(
                new ConstraintInteractionContract.PointRef(drivenId, drivenPoint), hostId));
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

            ArrayList<SketchConstraint> generated = new ArrayList<>();
            RoutedSnap snap = committedCreateSnap;
            if (snap != null && snap.targetEntityId != null && !stableId.equals(snap.targetEntityId)) {
                int drivenPoint = candidatePointIndexAt(candidate, snap.xMm, snap.yMm);
                if (drivenPoint >= 0) {
                    if (snap.modelKind == SketchSnapService.Kind.ENDPOINT && snap.targetPointIndex >= 0) {
                        generated.add(SketchConstraint.coincident(UUID.randomUUID().toString(),
                                stableId, drivenPoint, snap.targetEntityId, snap.targetPointIndex));
                    } else if (snap.modelKind == SketchSnapService.Kind.MIDPOINT) {
                        generated.add(SketchConstraint.midpoint(UUID.randomUUID().toString(),
                                stableId, drivenPoint, snap.targetEntityId));
                    } else if (snap.modelKind == SketchSnapService.Kind.ON_EDGE
                            && modelAutoConstraintsEnabled()) {
                        generated.add(SketchConstraint.pointOnEntity(UUID.randomUUID().toString(),
                                stableId, drivenPoint, snap.targetEntityId));
                    }
                }
            }

            sketchDocument.addWithConstraintsAndSolve(candidate, generated, sketchConstraintSolver);
            sketchDocument.selectOnly(stableId);
            committedCreateSnap = null;
            if (!generated.isEmpty()) replaySolvedLineGeometryToLegacy();
            return finishTransactionalGesture(source);
        } catch (RuntimeException e) {
            syncMirror(source + "-exception-fallback");
            lastMirrorError = source + ": " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            return false;
        }
    }

    private void reconcileLegacyTouchIfNeeded(String source) {
        try {
            replayAuthoritativeConstrainedGeometryBeforeDraw();
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
        final String targetEntityId;
        final int targetPointIndex;

        RoutedSnap(double xMm, double yMm, double distanceMm, String label,
                   SketchSnapService.Kind modelKind, String targetEntityId, int targetPointIndex) {
            this.xMm = xMm;
            this.yMm = yMm;
            this.distanceMm = distanceMm;
            this.label = label == null ? "" : label;
            this.modelKind = modelKind;
            this.targetEntityId = targetEntityId;
            this.targetPointIndex = targetPointIndex;
        }
    }

    private RoutedSnap committedCreateSnap;

    private int pointIndexAt(String entityId, double xMm, double yMm) {
        SketchEntity entity = sketchDocument.entity(entityId);
        if (!(entity instanceof SketchGeometry.Line)) return -1;
        SketchGeometry.Line line = (SketchGeometry.Line) entity;
        double da = Math.hypot(line.a.xMm - xMm, line.a.yMm - yMm);
        double db = Math.hypot(line.b.xMm - xMm, line.b.yMm - yMm);
        return Math.min(da, db) <= 1.0e-6 ? (da <= db ? 0 : 1) : -1;
    }

    private int candidatePointIndexAt(SketchEntity entity, double xMm, double yMm) {
        if (!(entity instanceof SketchGeometry.Line)) return -1;
        SketchGeometry.Line line = (SketchGeometry.Line) entity;
        double da = Math.hypot(line.a.xMm - xMm, line.a.yMm - yMm);
        double db = Math.hypot(line.b.xMm - xMm, line.b.yMm - yMm);
        return da <= db ? 0 : 1;
    }

    private RoutedSnap modelLineExtensionGuideSnap(float rawX, float rawY, float radiusMm) {
        if (!isShowGuides()) return null;
        RoutedSnap best = null;
        for (SketchEntity value : sketchDocument.entities()) {
            if (!(value instanceof SketchGeometry.Line)) continue;
            SketchGeometry.Line line = (SketchGeometry.Line) value;
            double dx = line.b.xMm - line.a.xMm;
            double dy = line.b.yMm - line.a.yMm;
            double length2 = dx * dx + dy * dy;
            if (length2 <= 1.0e-12) continue;
            double t = ((rawX - line.a.xMm) * dx + (rawY - line.a.yMm) * dy) / length2;
            if (t >= 0.0 && t <= 1.0) continue;
            double x = line.a.xMm + t * dx;
            double y = line.a.yMm + t * dy;
            double distance = Math.hypot(x - rawX, y - rawY);
            if (distance <= radiusMm * GUIDE_RADIUS_FACTOR
                    && (best == null || distance < best.distanceMm)) {
                best = new RoutedSnap(x, y, distance, "Guide",
                        SketchSnapService.Kind.ON_EDGE, line.id(), -1);
            }
        }
        return best;
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
                int targetPoint = model.kind == SketchSnapService.Kind.ENDPOINT
                        ? pointIndexAt(model.entityId, model.point.xMm, model.point.yMm) : -1;
                best = new RoutedSnap(model.point.xMm, model.point.yMm, model.distanceMm,
                        snapLabel(model.kind), model.kind, model.entityId, targetPoint);
            }
        } catch (RuntimeException e) {
            lastMirrorError = "model-snap: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }

        RoutedSnap extension = modelLineExtensionGuideSnap(rawX, rawY, radiusMm);
        if (extension != null) {
            boolean magneticDiscrete = best != null
                    && best.modelKind != null
                    && best.modelKind != SketchSnapService.Kind.ON_EDGE
                    && best.distanceMm <= radiusMm * 0.25d;
            if (best == null || (!magneticDiscrete && extension.distanceMm < best.distanceMm)) {
                best = extension;
            }
        }

        if (isShowGuides()) {
            for (Entity entity : entities) {
                if (entity == null || !coreIsVisible(entity)) continue;
                String description = entity.describe();
                if (description == null || !description.startsWith("Guide ")) continue;
                PointF p = entity.nearestPoint(rawX, rawY);
                if (p == null) continue;
                double d = Math.hypot(p.x - rawX, p.y - rawY);
                if (best == null && d <= radiusMm * GUIDE_RADIUS_FACTOR) {
                    best = new RoutedSnap(p.x, p.y, d, "Guide", null, null, -1);
                }
            }
        }

        if (best == null) {
            double gx = Math.rint(rawX / GRID_MM) * GRID_MM;
            double gy = Math.rint(rawY / GRID_MM) * GRID_MM;
            double d = Math.hypot(gx - rawX, gy - rawY);
            if (d <= radiusMm * GRID_RADIUS_FACTOR) {
                best = new RoutedSnap(gx, gy, d, "Grid", null, null, -1);
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

    private MotionEvent routeCreateSnap(MotionEvent event, int tool) {
        routedSnapVisible = false;
        routedSnapLabel = "";
        lastModelSnapKind = "";
        if (event != null && event.getActionMasked() == MotionEvent.ACTION_DOWN) committedCreateSnap = null;
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
            if (action == MotionEvent.ACTION_UP && snap.targetEntityId != null) {
                boolean alwaysAutomatic = snap.modelKind == SketchSnapService.Kind.ENDPOINT
                        || snap.modelKind == SketchSnapService.Kind.MIDPOINT;
                boolean policyAutomatic = snap.modelKind == SketchSnapService.Kind.ON_EDGE
                        && modelAutoConstraintsEnabled();
                if (alwaysAutomatic || policyAutomatic) committedCreateSnap = snap;
            }
        }
        return routed;
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        drawModelConstraintFeedback(canvas);
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

    private void drawModelConstraintFeedback(Canvas canvas) {
        for (ModelConstraintBadgeProjection.Badge badge
                : ModelConstraintBadgeProjection.project(sketchDocument)) {
            float bx = screenX(badge.xMm);
            float by = screenY(badge.yMm);
            String glyph = (badge.kind == ModelConstraintBadgeProjection.BadgeKind.COINCIDENT
                    || badge.kind == ModelConstraintBadgeProjection.BadgeKind.MIDPOINT) ? "●" : "◇";
            canvas.drawText(glyph, bx, by - 12f, modelConstraintTextPaint);
        }
        for (SketchConstraint constraint : sketchDocument.constraints()) {
            SketchEntity primary = sketchDocument.entity(constraint.primaryEntityId);
            if (!(primary instanceof SketchGeometry.Line)) continue;
            SketchGeometry.Line a = (SketchGeometry.Line) primary;
            float ax = screenX((a.a.xMm + a.b.xMm) * 0.5);
            float ay = screenY((a.a.yMm + a.b.yMm) * 0.5);
            if (constraint.kind == SketchConstraint.Kind.HORIZONTAL) {
                canvas.drawText("H", ax, ay - 10f, modelConstraintTextPaint);
            } else if (constraint.kind == SketchConstraint.Kind.VERTICAL) {
                canvas.drawText("V", ax + 10f, ay, modelConstraintTextPaint);
            } else if ((constraint.kind == SketchConstraint.Kind.PARALLEL
                    || constraint.kind == SketchConstraint.Kind.PERPENDICULAR)
                    && constraint.secondaryEntityId != null) {
                SketchEntity secondary = sketchDocument.entity(constraint.secondaryEntityId);
                if (!(secondary instanceof SketchGeometry.Line)) continue;
                SketchGeometry.Line b = (SketchGeometry.Line) secondary;
                float bx = screenX((b.a.xMm + b.b.xMm) * 0.5);
                float by = screenY((b.a.yMm + b.b.yMm) * 0.5);
                canvas.drawLine(ax, ay, bx, by, modelConstraintPaint);
                canvas.drawText(constraint.kind == SketchConstraint.Kind.PARALLEL ? "∥" : "⊥",
                        (ax + bx) * 0.5f, (ay + by) * 0.5f - 7f, modelConstraintTextPaint);
            }
        }
    }

    private float screenX(double mm) { return (float)(mm * MODEL_PX_PER_MM * viewScale + offsetX); }
    private float screenY(double mm) { return (float)(mm * MODEL_PX_PER_MM * viewScale + offsetY); }

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

    @Override public void clearAll() {
        sketchDocument.restoreExternal(java.util.Collections.emptyList(), java.util.Collections.emptySet(), java.util.Collections.emptyList());
        super.clearAll();
        syncMirror("clear");
    }

    @Override public void undo() {
        if (authorityHistoryValid && sketchDocument.canUndo()) {
            boolean changed = sketchDocument.undo();
            if (changed) {
                super.undo();
                replaySolvedLineGeometryToLegacy();
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

    @Override public String applySelectedDimension(String raw) {
        if (!prepareTransactionalSelection("dimension-prepare")) {
            String out=super.applySelectedDimension(raw);
            syncMirror("dimension-fallback");
            return out;
        }
        for (String id : sketchDocument.selectionIds()) {
            if (hasWholeFixed(id)) return "Lock prevents driving dimension";
        }
        String out=super.applySelectedDimension(raw);
        syncMirror("dimension");
        return out;
    }
    @Override public String setSelectedLineAngle(float degrees) { String out=super.setSelectedLineAngle(degrees); syncMirror("line-angle"); return out; }
    @Override public String setSelectedLinesAngle(float degrees) { String out=super.setSelectedLinesAngle(degrees); syncMirror("lines-angle"); return out; }

    @Override public String applyHorizontalVerticalConstraint() {
        if (!prepareTransactionalSelection("constraint-hv-prepare")) return "Select one or more lines for H/V";
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
            coreSaveUndo();
            replaySolvedLineGeometryToLegacy();
            if (!finishTransactionalMutation("constraint-hv")) return "H/V constraint rollback: parity failed";
            return ids.size()+" line(s) constrained H/V";
        } catch (RuntimeException e) {
            return modelConstraintFailure("constraint-hv",e);
        }
    }

    @Override public String applyPerpendicularConstraint() {
        if (!prepareTransactionalSelection("constraint-perpendicular-prepare")) return "Select exactly two lines for Perpendicular";
        List<String> ids=selectedModelLineIds();
        if (ids.size()!=2) return "Select exactly two lines for Perpendicular";
        int anchorIndex=chooseConstraintAnchorIndex(ids);
        String anchor=ids.get(anchorIndex);
        String moving=ids.get(anchorIndex==0?1:0);
        try {
            SketchConstraint c=SketchConstraint.perpendicular(UUID.randomUUID().toString(),anchor,moving);
            sketchDocument.addConstraintsAndSolve(java.util.Collections.singletonList(c),sketchConstraintSolver);
            coreSaveUndo();
            replaySolvedLineGeometryToLegacy();
            if (!finishTransactionalMutation("constraint-perpendicular")) return "Perpendicular constraint rollback: parity failed";
            return "Perpendicular ⊥ applied";
        } catch (RuntimeException e) {
            return modelConstraintFailure("constraint-perpendicular",e);
        }
    }

    @Override public String applyParallelConstraint() {
        if (!prepareTransactionalSelection("constraint-parallel-prepare")) return "Select two or more lines for Parallel";
        List<String> ids=selectedModelLineIds();
        if (ids.size()<2) return "Select two or more lines for Parallel";
        int anchorIndex=chooseConstraintAnchorIndex(ids);
        String anchor=ids.get(anchorIndex);
        ArrayList<SketchConstraint> incoming=new ArrayList<>();
        for (int i=0;i<ids.size();i++) {
            if (i==anchorIndex) continue;
            incoming.add(SketchConstraint.parallel(UUID.randomUUID().toString(),anchor,ids.get(i)));
        }
        try {
            sketchDocument.addConstraintsAndSolve(incoming,sketchConstraintSolver);
            coreSaveUndo();
            replaySolvedLineGeometryToLegacy();
            if (!finishTransactionalMutation("constraint-parallel")) return "Parallel constraint rollback: parity failed";
            return "Parallel ∥ applied to "+ids.size()+" lines";
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

    private boolean hasWholeFixed(String entityId) {
        for (SketchConstraint c : sketchDocument.constraintsForEntity(entityId)) {
            if (c.kind == SketchConstraint.Kind.FIXED
                    && entityId.equals(c.primaryEntityId) && c.fixesWholeEntity()) return true;
        }
        return false;
    }

    @Override public String toggleSelectedLock() {
        if (!prepareTransactionalSelection("constraint-fixed-prepare")) return "Select geometry first";
        Set<String> ids = sketchDocument.selectionIds();
        if (ids.isEmpty()) return "Select geometry first";

        boolean shouldLock = false;
        for (String id : ids) {
            if (!hasWholeFixed(id)) { shouldLock = true; break; }
        }

        try {
            if (shouldLock) {
                ArrayList<SketchConstraint> incoming = new ArrayList<>();
                for (String id : ids) {
                    if (!hasWholeFixed(id)) incoming.add(SketchConstraint.fixed(UUID.randomUUID().toString(), id));
                }
                if (incoming.isEmpty()) return ids.size() + " selection(s) locked";
                sketchDocument.addConstraintsAndSolve(incoming, sketchConstraintSolver);
            } else {
                ArrayList<String> removeIds = new ArrayList<>();
                for (SketchConstraint c : sketchDocument.constraints()) {
                    if (c.kind == SketchConstraint.Kind.FIXED && c.fixesWholeEntity()
                            && ids.contains(c.primaryEntityId)) removeIds.add(c.id);
                }
                if (removeIds.isEmpty()) return ids.size() + " selection(s) unlocked";
                sketchDocument.removeConstraints(removeIds);
            }
            coreSaveUndo();
            if (!finishTransactionalMutation("constraint-fixed")) return "Lock constraint rollback: parity failed";
            invalidate();
            return ids.size() + (shouldLock ? " selection(s) locked" : " selection(s) unlocked");
        } catch (RuntimeException e) {
            return modelConstraintFailure("constraint-fixed", e);
        }
    }

    @Override public String exportSketchProjectState() {
        try {
            JSONObject root=new JSONObject(super.exportSketchProjectState());
            JSONArray rows=new JSONArray();
            for (SketchConstraint c:sketchDocument.constraints()) {
                JSONObject row=new JSONObject();
                row.put("id",c.id);
                row.put("kind",c.kind.name());
                row.put("primaryEntityId",c.primaryEntityId);
                row.put("primaryPointIndex",c.primaryPointIndex);
                if (c.secondaryEntityId!=null) row.put("secondaryEntityId",c.secondaryEntityId);
                row.put("secondaryPointIndex",c.secondaryPointIndex);
                if (!Double.isNaN(c.value)) row.put("value",c.value);
                row.put("driving",c.driving);
                rows.put(row);
            }
            root.put("modelConstraintSchemaVersion",1);
            root.put("modelConstraints",rows);
            return root.toString();
        } catch (Exception e) {
            throw new IllegalStateException("Cannot export model-owned sketch constraints",e);
        }
    }

    @Override public String importSketchProjectState(String raw) {
        try {
            JSONObject incoming=new JSONObject(raw);
            int incomingSchema=incoming.optInt("schemaVersion",-1);
            boolean hasModelConstraints=incomingSchema==2 && incoming.has("modelConstraints");
            if (hasModelConstraints) {
                SketchDocument preflight = new SketchDocument();
                LegacySketchStateBridge.restoreDocument(preflight, raw);
            }
            String out=super.importSketchProjectState(raw);
            if (hasModelConstraints) {
                LegacySketchStateBridge.restoreDocument(sketchDocument,raw);
            } else {
                LegacySketchStateBridge.restoreDocument(sketchDocument,super.exportSketchProjectState());
            }
            mirrorSyncCount++;
            authorityHistoryValid=false;
            lastMirrorError="";
            requireSketchMirrorParity();
            return out;
        } catch (Exception e) {
            authorityHistoryValid=false;
            lastMirrorError="project-open: "+(e.getMessage()==null?e.getClass().getSimpleName():e.getMessage());
            return "Project sketch state could not be restored";
        }
    }

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

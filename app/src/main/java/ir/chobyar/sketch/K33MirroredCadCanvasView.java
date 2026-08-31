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
import ir.chobyar.sketch.core.PointLockInteractionMapping;
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
 * persistent line constraints/solving out of the legacy View hierarchy. K3.10
 * moves Line DISTANCE, Circle/Arc RADIUS and binary Line ANGLE driving
 * dimensions into the same model authority. K3.11 adds model-owned EQUAL for
 * line length and circle/arc radius. Remaining annotations and constraint kinds
 * stay legacy-owned until their dedicated authority slices.
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
    private static final float POINT_LOCK_HANDLE_RADIUS_PX = 18f;
    private static final float POINT_LOCK_DRAG_SLOP_PX = 3f;
    private String pointLockTargetEntityId;
    private int pointLockTargetPointIndex = -1;
    private String pendingPointLockEntityId;
    private int pendingPointLockPointIndex = -1;
    private float pendingPointLockDownX;
    private float pendingPointLockDownY;
    private boolean pendingPointLockMoved;
    private boolean blockedLockedPointGesture;

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
            replayAuthoritativeConstrainedGeometry();
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
                case PERPENDICULAR:
                case EQUAL: score += 60; break;
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

    /** Re-project model-owned history selection into the legacy interaction layer. */
    private void restoreLegacySelectionFromModel() {
        selectedObjects.clear();
        Entity primary = null;
        for (String id : sketchDocument.selectionIds()) {
            Entity legacy = legacyEntityByStableId(id);
            if (legacy == null) continue;
            selectedObjects.add(legacy);
            if (primary == null) primary = legacy;
        }
        selected = selectedObjects.size() == 1 ? primary : null;
        invalidate();
    }

    private SketchGeometry.Point modelPoint(SketchEntity entity, int pointIndex) {
        if (entity instanceof SketchGeometry.Line) {
            SketchGeometry.Line line = (SketchGeometry.Line) entity;
            if (pointIndex == 0) return line.a;
            if (pointIndex == 1) return line.b;
            return null;
        }
        if (pointIndex == 0 && entity instanceof SketchGeometry.Circle) {
            return ((SketchGeometry.Circle) entity).center;
        }
        if (pointIndex == 0 && entity instanceof SketchGeometry.Arc) {
            return ((SketchGeometry.Arc) entity).center;
        }
        return null;
    }

    private void replayModelEntityToLegacy(SketchEntity value) {
        if (value == null) return;
        Entity legacy = legacyEntityByStableId(value.id());
        if (legacy == null) return;
        if (value instanceof SketchGeometry.Line) {
            SketchGeometry.Line solved = (SketchGeometry.Line) value;
            legacy.moveControlPoint(0, (float) solved.a.xMm, (float) solved.a.yMm);
            legacy.moveControlPoint(1, (float) solved.b.xMm, (float) solved.b.yMm);
            return;
        }
        if (value instanceof SketchGeometry.Circle) {
            SketchGeometry.Circle solved = (SketchGeometry.Circle) value;
            legacy.moveControlPoint(0, (float) solved.center.xMm, (float) solved.center.yMm);
            legacy.moveControlPoint(1, (float) (solved.center.xMm + solved.radiusMm),
                    (float) solved.center.yMm);
            return;
        }
        if (value instanceof SketchGeometry.Arc) {
            SketchGeometry.Arc solved = (SketchGeometry.Arc) value;
            if (!coreUpdateReferenceArc(legacy,
                    (float) solved.center.xMm, (float) solved.center.yMm,
                    (float) solved.radiusMm, (float) solved.startDeg, (float) solved.sweepDeg)) {
                throw new IllegalStateException("Arc authority replay target mismatch: " + solved.id());
            }
        }
    }

    private void replayModelEqualMetricToLegacy(SketchEntity value) {
        if (value == null) return;
        Entity legacy = legacyEntityByStableId(value.id());
        if (legacy == null) return;
        if (value instanceof SketchGeometry.Line && legacy instanceof LineEntity) {
            ((LineEntity) legacy).setLength((float) ((SketchGeometry.Line) value).lengthMm());
            return;
        }
        if (!(value instanceof SketchGeometry.Circle) && !(value instanceof SketchGeometry.Arc)) return;
        float radius = (float) (value instanceof SketchGeometry.Circle
                ? ((SketchGeometry.Circle) value).radiusMm
                : ((SketchGeometry.Arc) value).radiusMm);
        if (!coreUpdateEqualRadius(legacy, radius)) {
            throw new IllegalStateException("Equal radius authority replay target mismatch: " + value.id());
        }
    }

    private void replaySolvedLineGeometryToLegacy() {
        for (SketchEntity value : sketchDocument.entities()) replayModelEntityToLegacy(value);
        invalidate();
    }

    private void replayAuthoritativeConstrainedGeometry() {
        LinkedHashSet<String> constrained = new LinkedHashSet<>();
        for (SketchConstraint c : sketchDocument.constraints()) {
            if (c.kind == SketchConstraint.Kind.FIXED && c.fixesPoint()) {
                continue;
            }
            if (c.kind != SketchConstraint.Kind.HORIZONTAL
                    && c.kind != SketchConstraint.Kind.VERTICAL
                    && c.kind != SketchConstraint.Kind.PARALLEL
                    && c.kind != SketchConstraint.Kind.PERPENDICULAR
                    && c.kind != SketchConstraint.Kind.EQUAL
                    && c.kind != SketchConstraint.Kind.COINCIDENT
                    && c.kind != SketchConstraint.Kind.POINT_ON_ENTITY
                    && c.kind != SketchConstraint.Kind.MIDPOINT
                    && c.kind != SketchConstraint.Kind.DISTANCE
                    && c.kind != SketchConstraint.Kind.RADIUS
                    && c.kind != SketchConstraint.Kind.ANGLE
                    && c.kind != SketchConstraint.Kind.FIXED) continue;
            constrained.addAll(c.referencedEntityIds());
        }
        for (String id : constrained) replayModelEntityToLegacy(sketchDocument.entity(id));
    }

    private void replayAuthoritativeConstrainedGeometryBeforeDraw() {
        LinkedHashSet<String> equalOwned = new LinkedHashSet<>();
        for (SketchConstraint c : sketchDocument.constraints()) {
            if (c.kind != SketchConstraint.Kind.EQUAL) continue;
            equalOwned.addAll(c.referencedEntityIds());
        }
        for (String id : equalOwned) replayModelEqualMetricToLegacy(sketchDocument.entity(id));
    }

    @Override protected boolean isModelEndpointConstraintAuthorityEnabled() { return true; }
    @Override protected boolean isModelEqualConstraintAuthorityEnabled() { return true; }

    private static boolean equalRadiusEntity(SketchEntity entity) {
        return entity instanceof SketchGeometry.Circle || entity instanceof SketchGeometry.Arc;
    }

    private static boolean equalCompatible(SketchEntity first, SketchEntity second) {
        return (first instanceof SketchGeometry.Line && second instanceof SketchGeometry.Line)
                || (equalRadiusEntity(first) && equalRadiusEntity(second));
    }

    private boolean hasEqualBetween(String firstId, String secondId) {
        for (SketchConstraint c : sketchDocument.constraints()) {
            if (c.kind != SketchConstraint.Kind.EQUAL || c.secondaryEntityId == null) continue;
            boolean sameOrder = firstId.equals(c.primaryEntityId) && secondId.equals(c.secondaryEntityId);
            boolean reverseOrder = secondId.equals(c.primaryEntityId) && firstId.equals(c.secondaryEntityId);
            if (sameOrder || reverseOrder) return true;
        }
        return false;
    }

    @Override protected String onModelEqualRequested(String firstEntityId, String secondEntityId) {
        if (!prepareTransactionalDocument("constraint-equal-prepare")) return lastMirrorError;
        SketchEntity first = sketchDocument.entity(firstEntityId);
        SketchEntity second = sketchDocument.entity(secondEntityId);
        if (first == null || second == null || firstEntityId.equals(secondEntityId)) {
            return "Equal requires two distinct sketch elements";
        }
        if (!equalCompatible(first, second)) {
            return "Equal requires two lines or two circles/arcs";
        }
        if (hasEqualBetween(firstEntityId, secondEntityId)) return "Equal already applied";

        ArrayList<String> ids = new ArrayList<>();
        ids.add(firstEntityId);
        ids.add(secondEntityId);
        int anchorIndex = chooseConstraintAnchorIndex(ids);
        String referenceId = ids.get(anchorIndex);
        String drivenId = ids.get(anchorIndex == 0 ? 1 : 0);
        try {
            sketchDocument.setSelection(ids);
            SketchConstraint equal = SketchConstraint.equal(UUID.randomUUID().toString(), referenceId, drivenId);
            sketchDocument.addConstraintsAndSolve(java.util.Collections.singletonList(equal), sketchConstraintSolver);
            coreSaveUndo();
            replaySolvedLineGeometryToLegacy();
            if (!finishTransactionalMutation("constraint-equal")) return "Equal constraint rollback: parity failed";
            return "Equal applied";
        } catch (RuntimeException e) {
            return modelConstraintFailure("constraint-equal", e);
        }
    }

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
            replayAuthoritativeConstrainedGeometry();
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
        replayAuthoritativeConstrainedGeometryBeforeDraw();
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
            if (constraint.kind == SketchConstraint.Kind.FIXED && constraint.fixesPoint()) {
                SketchGeometry.Point locked = modelPoint(primary, constraint.primaryPointIndex);
                if (locked != null) {
                    float px = screenX(locked.xMm);
                    float py = screenY(locked.yMm);
                    float rr = 6f * getResources().getDisplayMetrics().density;
                    canvas.drawCircle(px, py, rr, modelConstraintPaint);
                    canvas.drawText("🔒", px + rr + 4f, py - rr, modelConstraintTextPaint);
                }
                continue;
            }
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

    private boolean hasPointFixed(String entityId, int pointIndex) {
        if (entityId == null || pointIndex < 0) return false;
        for (SketchConstraint c : sketchDocument.constraintsForEntity(entityId)) {
            if (c.kind == SketchConstraint.Kind.FIXED && c.fixesPoint()
                    && entityId.equals(c.primaryEntityId) && c.primaryPointIndex == pointIndex) return true;
        }
        return false;
    }

    private boolean activePointLockTargetMatchesSelection() {
        if (pointLockTargetEntityId == null || pointLockTargetPointIndex < 0 || selected == null) return false;
        if (!pointLockTargetEntityId.equals(selected.stableId())) return false;
        return modelPoint(sketchDocument.entity(pointLockTargetEntityId), pointLockTargetPointIndex) != null;
    }

    @Override protected boolean hasModelPointLockTarget() {
        return activePointLockTargetMatchesSelection();
    }

    @Override protected boolean isModelPointLockTargetLocked() {
        return activePointLockTargetMatchesSelection()
                && hasPointFixed(pointLockTargetEntityId, pointLockTargetPointIndex);
    }

    @Override protected PointF modelPointLockTargetWorld() {
        if (!activePointLockTargetMatchesSelection()) return null;
        SketchGeometry.Point point = modelPoint(sketchDocument.entity(pointLockTargetEntityId),
                pointLockTargetPointIndex);
        return point == null ? null : new PointF((float) point.xMm, (float) point.yMm);
    }

    public String pointLockTargetEntityId() {
        return activePointLockTargetMatchesSelection() ? pointLockTargetEntityId : "";
    }

    public int pointLockTargetPointIndex() {
        return activePointLockTargetMatchesSelection() ? pointLockTargetPointIndex : -1;
    }

    public boolean pointLockTargetLocked() {
        return isModelPointLockTargetLocked();
    }

    private void clearPointLockTarget() {
        pointLockTargetEntityId = null;
        pointLockTargetPointIndex = -1;
    }

    private void clearPendingPointLockGesture() {
        pendingPointLockEntityId = null;
        pendingPointLockPointIndex = -1;
        pendingPointLockMoved = false;
    }

    private int selectedControlHandleAt(MotionEvent event) {
        if (event == null || selected == null || event.getPointerCount() != 1) return -1;
        List<ControlPoint> points = selected.controlPoints();
        if (points == null || points.isEmpty()) return -1;
        float bestDistance2 = POINT_LOCK_HANDLE_RADIUS_PX * POINT_LOCK_HANDLE_RADIUS_PX;
        int best = -1;
        for (int i = 0; i < points.size(); i++) {
            ControlPoint point = points.get(i);
            float dx = event.getX() - screenX(point.x);
            float dy = event.getY() - screenY(point.y);
            float d2 = dx * dx + dy * dy;
            if (d2 <= bestDistance2) {
                bestDistance2 = d2;
                best = i;
            }
        }
        return best;
    }

    private boolean beginPointLockGesture(MotionEvent event) {
        clearPendingPointLockGesture();
        blockedLockedPointGesture = false;
        if (selected == null) { clearPointLockTarget(); return false; }
        int handle = selectedControlHandleAt(event);
        String entityId = selected.stableId();
        SketchEntity model = sketchDocument.entity(entityId);
        int pointIndex = model == null ? -1
                : PointLockInteractionMapping.modelPointIndex(model.kind(), handle);
        if (pointIndex < 0) { clearPointLockTarget(); return false; }
        clearPointLockTarget();
        pendingPointLockEntityId = entityId;
        pendingPointLockPointIndex = pointIndex;
        pendingPointLockDownX = event.getX();
        pendingPointLockDownY = event.getY();
        if (hasPointFixed(entityId, pointIndex)) {
            pointLockTargetEntityId = entityId;
            pointLockTargetPointIndex = pointIndex;
            blockedLockedPointGesture = true;
            invalidate();
            return true;
        }
        return false;
    }

    private void updatePendingPointLockGesture(MotionEvent event) {
        if (pendingPointLockEntityId == null || event == null) return;
        float dx = event.getX() - pendingPointLockDownX;
        float dy = event.getY() - pendingPointLockDownY;
        if (dx * dx + dy * dy > POINT_LOCK_DRAG_SLOP_PX * POINT_LOCK_DRAG_SLOP_PX) {
            pendingPointLockMoved = true;
        }
    }

    private boolean consumeBlockedPointLockGesture(MotionEvent event) {
        updatePendingPointLockGesture(event);
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            blockedLockedPointGesture = false;
            clearPendingPointLockGesture();
            invalidate();
        }
        return true;
    }

    private void finishPointLockGestureAfterSuper(MotionEvent event) {
        if (pendingPointLockEntityId == null || event == null) return;
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_MOVE) {
            updatePendingPointLockGesture(event);
            return;
        }
        if (action != MotionEvent.ACTION_UP && action != MotionEvent.ACTION_CANCEL) return;
        if (action == MotionEvent.ACTION_UP && !pendingPointLockMoved && selected != null
                && pendingPointLockEntityId.equals(selected.stableId())) {
            pointLockTargetEntityId = pendingPointLockEntityId;
            pointLockTargetPointIndex = pendingPointLockPointIndex;
        } else if (pendingPointLockMoved) {
            clearPointLockTarget();
        }
        clearPendingPointLockGesture();
        invalidate();
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (event == null) return false;
        int action = event.getActionMasked();
        int toolBefore = getTool();
        boolean drawingBefore = drawing;
        int legacyCountBefore = entities.size();
        if (toolBefore == TOOL_SELECT && event.getPointerCount() == 1) {
            if (action == MotionEvent.ACTION_DOWN && beginPointLockGesture(event)) return true;
            if (blockedLockedPointGesture) return consumeBlockedPointLockGesture(event);
            if (action == MotionEvent.ACTION_MOVE) updatePendingPointLockGesture(event);
        }
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

        if (toolBefore == TOOL_SELECT && event.getPointerCount() == 1) finishPointLockGestureAfterSuper(event);

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
        clearPointLockTarget();
        clearPendingPointLockGesture();
        blockedLockedPointGesture = false;
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
                restoreLegacySelectionFromModel();
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
                restoreLegacySelectionFromModel();
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

    private boolean selectedHasPointFixed() {
        if (selected == null) return false;
        String id = selected.stableId();
        if (id == null || id.trim().isEmpty()) return false;
        for (SketchConstraint c : sketchDocument.constraintsForEntity(id)) {
            if (c.kind == SketchConstraint.Kind.FIXED && c.fixesPoint()
                    && id.equals(c.primaryEntityId)) return true;
        }
        return false;
    }

    @Override public String offsetSelected(float distance) { String out=super.offsetSelected(distance); syncMirror("offset"); return out; }
    @Override public String rotateSelected(float deg) {
        if (!selectedHasPointFixed()) { String out=super.rotateSelected(deg); syncMirror("rotate"); return out; }
        if (!prepareTransactionalSelection("rotate-point-fixed-prepare")) return "Select geometry first";
        try {
            long before=sketchDocument.revision();
            sketchDocument.rotatePointFixedSelectionAndSolve(deg,sketchConstraintSolver);
            if (sketchDocument.revision()==before) return "Rotation unchanged";
            coreSaveUndo();
            replaySolvedLineGeometryToLegacy();
            if (!finishTransactionalMutation("rotate-point-fixed")) return "Rotate rollback: parity failed";
            return "Rotation applied";
        } catch (RuntimeException e) {
            return modelConstraintFailure("rotate-point-fixed",e);
        }
    }
    @Override public String scaleSelected(float factor) {
        if (!selectedHasPointFixed()) { String out=super.scaleSelected(factor); syncMirror("scale"); return out; }
        if (!prepareTransactionalSelection("scale-point-fixed-prepare")) return "Select geometry first";
        try {
            long before=sketchDocument.revision();
            sketchDocument.scalePointFixedSelectionAndSolve(factor,sketchConstraintSolver);
            if (sketchDocument.revision()==before) return "Scale unchanged";
            coreSaveUndo();
            replaySolvedLineGeometryToLegacy();
            if (!finishTransactionalMutation("scale-point-fixed")) return "Scale rollback: parity failed";
            return "Scale applied";
        } catch (RuntimeException e) {
            return modelConstraintFailure("scale-point-fixed",e);
        }
    }
    @Override public String mirrorSelected(boolean acrossXAxis,float axisValue) {
        if (!selectedHasPointFixed()) { String out=super.mirrorSelected(acrossXAxis,axisValue); syncMirror("mirror"); return out; }
        if (!prepareTransactionalSelection("mirror-point-fixed-prepare")) return "Select geometry first";
        try {
            long before=sketchDocument.revision();
            sketchDocument.mirrorPointFixedSelectionAndSolve(acrossXAxis,axisValue,sketchConstraintSolver);
            if (sketchDocument.revision()==before) return "Mirror unchanged";
            coreSaveUndo();
            replaySolvedLineGeometryToLegacy();
            if (!finishTransactionalMutation("mirror-point-fixed")) return "Mirror rollback: parity failed";
            return acrossXAxis ? "Mirrored across Axis X" : "Mirrored across Axis Y";
        } catch (RuntimeException e) {
            return modelConstraintFailure("mirror-point-fixed",e);
        }
    }
    @Override public String arraySelected(int count,float dx,float dy) { String out=super.arraySelected(count,dx,dy); syncMirror("array"); return out; }

    @Override public String trimSelectedLines() { String out=super.trimSelectedLines(); syncMirror("trim"); return out; }
    @Override public String extendSelectedLines() { String out=super.extendSelectedLines(); syncMirror("extend"); return out; }
    @Override public String chamferSelectedLines(float setback) { String out=super.chamferSelectedLines(setback); syncMirror("sketch-chamfer"); return out; }
    @Override public String filletSelectedLines(float radius) { String out=super.filletSelectedLines(radius); syncMirror("sketch-fillet"); return out; }
    @Override public String joinSelectedLines() { String out=super.joinSelectedLines(); syncMirror("join"); return out; }

    private static double parsePositiveDrivingValue(String raw) {
        if (raw == null) throw new IllegalArgumentException("Dimension value is empty");
        String normalized = raw.trim().replace(',', '.');
        if (normalized.isEmpty()) throw new IllegalArgumentException("Dimension value is empty");
        double value = Double.parseDouble(normalized);
        if (!SketchGeometry.finite(value) || value <= 0.0) {
            throw new IllegalArgumentException("Dimension value must be positive and finite");
        }
        return value;
    }

    @Override public String applySelectedDimension(String raw) {
        if (!prepareTransactionalSelection("dimension-prepare")) {
            String out=super.applySelectedDimension(raw);
            syncMirror("dimension-fallback");
            return out;
        }
        if (sketchDocument.selectionIds().size()!=1) {
            String out=super.applySelectedDimension(raw);
            syncMirror("dimension-multi-legacy");
            return out;
        }
        String id=sketchDocument.selectionIds().iterator().next();
        SketchEntity entity=sketchDocument.entity(id);
        if (!(entity instanceof SketchGeometry.Line)
                && !(entity instanceof SketchGeometry.Circle)
                && !(entity instanceof SketchGeometry.Arc)) {
            String out=super.applySelectedDimension(raw);
            syncMirror("dimension-legacy-type");
            return out;
        }
        if (hasWholeFixed(id)) return "Lock prevents driving dimension";
        final double entered;
        try {
            entered=parsePositiveDrivingValue(raw);
        } catch (RuntimeException invalid) {
            String out=super.applySelectedDimension(raw);
            syncMirror("dimension-invalid-legacy");
            return out;
        }
        try {
            SketchConstraint driver;
            String label;
            if (entity instanceof SketchGeometry.Line) {
                driver=SketchConstraint.distance(UUID.randomUUID().toString(),id,entered);
                label="Length";
            } else if (entity instanceof SketchGeometry.Circle) {
                driver=SketchConstraint.radius(UUID.randomUUID().toString(),id,entered*0.5d);
                label="Diameter";
            } else {
                driver=SketchConstraint.radius(UUID.randomUUID().toString(),id,entered);
                label="Radius";
            }
            sketchDocument.setDrivingDimensionAndSolve(driver,sketchConstraintSolver);
            coreSaveUndo();
            replaySolvedLineGeometryToLegacy();
            if (!finishTransactionalMutation("dimension-driving")) {
                return "Driving dimension rollback: parity failed";
            }
            return label+" = "+entered+" mm";
        } catch (RuntimeException e) {
            return modelConstraintFailure("dimension-driving",e);
        }
    }

    /** Single-Line absolute angle remains legacy-owned in K3.10 by design. */
    @Override public String setSelectedLineAngle(float degrees) {
        String out=super.setSelectedLineAngle(degrees);
        syncMirror("line-angle");
        return out;
    }

    @Override public String setSelectedLinesAngle(float degrees) {
        if (degrees<0f || degrees>180f || Float.isNaN(degrees) || Float.isInfinite(degrees)) {
            return super.setSelectedLinesAngle(degrees);
        }
        if (!prepareTransactionalSelection("lines-angle-prepare")) {
            String out=super.setSelectedLinesAngle(degrees);
            syncMirror("lines-angle-fallback");
            return out;
        }
        List<String> ids=selectedModelLineIds();
        if (ids.size()!=2) return "Select exactly two lines for Angle";
        try {
            SketchConstraint driver=SketchConstraint.angle(UUID.randomUUID().toString(),ids.get(0),ids.get(1),degrees);
            sketchDocument.setDrivingDimensionAndSolve(driver,sketchConstraintSolver);
            coreSaveUndo();
            replaySolvedLineGeometryToLegacy();
            if (!finishTransactionalMutation("lines-angle-driving")) {
                return "Angle dimension rollback: parity failed";
            }
            return "Angle between lines = "+degrees+"°";
        } catch (RuntimeException e) {
            return modelConstraintFailure("lines-angle-driving",e);
        }
    }

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

    @Override public String applyEqualConstraint() { String out=super.applyEqualConstraint(); syncMirror("constraint-equal-post"); return out; }
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

    private String toggleActivePointLock() {
        if (!activePointLockTargetMatchesSelection()) return "Select an endpoint or center point first";
        String entityId = pointLockTargetEntityId;
        int pointIndex = pointLockTargetPointIndex;
        if (!prepareTransactionalSelection("constraint-fixed-point-prepare")) {
            clearPointLockTarget();
            return "Select an endpoint or center point first";
        }
        if (!sketchDocument.selectionIds().contains(entityId)
                || modelPoint(sketchDocument.entity(entityId), pointIndex) == null) {
            clearPointLockTarget();
            return "Point selection is no longer valid";
        }
        boolean shouldLock = !hasPointFixed(entityId, pointIndex);
        try {
            if (shouldLock) {
                SketchConstraint fixed = SketchConstraint.fixedPoint(
                        UUID.randomUUID().toString(), entityId, pointIndex);
                sketchDocument.addConstraintsAndSolve(
                        java.util.Collections.singletonList(fixed), sketchConstraintSolver);
            } else {
                ArrayList<String> removeIds = new ArrayList<>();
                for (SketchConstraint c : sketchDocument.constraintsForEntity(entityId)) {
                    if (c.kind == SketchConstraint.Kind.FIXED && c.fixesPoint()
                            && entityId.equals(c.primaryEntityId)
                            && c.primaryPointIndex == pointIndex) removeIds.add(c.id);
                }
                if (!removeIds.isEmpty()) sketchDocument.removeConstraints(removeIds);
            }
            coreSaveUndo();
            if (!finishTransactionalMutation("constraint-fixed-point")) {
                return "Point Lock rollback: parity failed";
            }
            invalidate();
            return (shouldLock ? "Point locked" : "Point unlocked");
        } catch (RuntimeException e) {
            return modelConstraintFailure("constraint-fixed-point", e);
        }
    }

    @Override public String toggleSelectedLock() {
        if (activePointLockTargetMatchesSelection()) return toggleActivePointLock();
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

package ir.chobyar.sketch;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PointF;
import android.graphics.RectF;
import android.view.MotionEvent;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * ChobYar interaction layer inspired by direct CAD sketch workflows.
 *
 * Adds, without changing the existing geometry engine:
 * - automatic H/V sketch inference with guide lines
 * - automatic perpendicular inference when continuing from a previous line
 * - persistent H/V, perpendicular and coincident endpoint relationships
 * - tap-and-drag continuation from the last line endpoint (line chaining)
 * - an on-canvas Move/Rotate gizmo for selected geometry
 * - constraint badges and filled coincidence points
 *
 * User-facing and model-space length values are millimeters.
 */
public class ChobYarShaprCanvasView extends ShaprStyleCadCanvasView {

    public interface WorkspaceListener {
        void onWorkspaceStateChanged(String selectionInfo, boolean exactDimensionAvailable, int activeTool);
    }

    private static final float PX_PER_MM = 3f;
    private static final float AUTO_AXIS_DEG = 7f;
    private static final float AUTO_PERP_DEG = 6f;
    private static final float CHAIN_HIT_PX = 28f;
    private static final float GIZMO_HIT_PX = 24f;
    private static final float GIZMO_ARM_PX = 74f;

    private WorkspaceListener workspaceListener;

    private Field selectedField;
    private Field entitiesField;
    private Field selectedObjectsField;
    private Field viewScaleField;
    private Field offsetXField;
    private Field offsetYField;
    private Field startXField;
    private Field startYField;
    private Method saveUndoMethod;

    private Object lastLineCreated;
    private char pendingAxis = 0; // H / V
    private boolean pendingPerpendicular = false;
    private boolean inferenceVisible = false;
    private char inferenceType = 0; // H, V, P
    private float inferenceStartX;
    private float inferenceStartY;

    private final Map<Object, AxisLock> axisLocks = new IdentityHashMap<>();
    private final List<LineRelation> lineRelations = new ArrayList<>();
    private final List<CoincidentLink> coincidenceLinks = new ArrayList<>();

    private final Paint inferencePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint inferenceTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint constraintPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint constraintTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint coincidencePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    // Gizmo -----------------------------------------------------------------
    private static final int GIZMO_NONE = 0;
    private static final int GIZMO_X = 1;
    private static final int GIZMO_Y = 2;
    private static final int GIZMO_ROTATE = 3;
    private int gizmoMode = GIZMO_NONE;
    private boolean gizmoUndoSaved = false;
    private PointF gizmoCenterWorld;
    private PointF gizmoCenterScreen;
    private float gizmoLastWorldX;
    private float gizmoLastWorldY;
    private float gizmoLastAngle;
    private float gizmoDelta;
    private boolean gizmoVisible = false;
    private int gizmoSessionUndoSteps = 0;

    private final Paint gizmoXPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gizmoYPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gizmoRotatePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gizmoCenterPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gizmoTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public ChobYarShaprCanvasView(Context context) {
        super(context);
        initReflection();
        initPaints();
    }

    private void initPaints() {
        inferencePaint.setColor(Color.rgb(65, 145, 235));
        inferencePaint.setStrokeWidth(2f);
        inferencePaint.setStyle(Paint.Style.STROKE);
        inferenceTextPaint.setColor(Color.rgb(35, 105, 205));
        inferenceTextPaint.setTextSize(25f);
        inferenceTextPaint.setTextAlign(Paint.Align.CENTER);

        constraintPaint.setColor(Color.rgb(45, 125, 225));
        constraintPaint.setStrokeWidth(2.5f);
        constraintPaint.setStyle(Paint.Style.STROKE);
        constraintTextPaint.setColor(Color.rgb(35, 105, 205));
        constraintTextPaint.setTextSize(21f);
        constraintTextPaint.setTextAlign(Paint.Align.CENTER);

        coincidencePaint.setColor(Color.rgb(45, 125, 225));
        coincidencePaint.setStyle(Paint.Style.FILL);

        gizmoXPaint.setColor(Color.rgb(225, 72, 72));
        gizmoXPaint.setStrokeWidth(5f);
        gizmoXPaint.setStyle(Paint.Style.STROKE);
        gizmoYPaint.setColor(Color.rgb(55, 160, 95));
        gizmoYPaint.setStrokeWidth(5f);
        gizmoYPaint.setStyle(Paint.Style.STROKE);
        gizmoRotatePaint.setColor(Color.rgb(70, 115, 225));
        gizmoRotatePaint.setStrokeWidth(4f);
        gizmoRotatePaint.setStyle(Paint.Style.STROKE);
        gizmoCenterPaint.setColor(Color.WHITE);
        gizmoCenterPaint.setStyle(Paint.Style.FILL);
        gizmoTextPaint.setColor(Color.rgb(40, 55, 75));
        gizmoTextPaint.setTextSize(23f);
        gizmoTextPaint.setTextAlign(Paint.Align.CENTER);
    }

    private void initReflection() {
        try {
            selectedField = field(CadCanvasView.class, "selected");
            entitiesField = field(CadCanvasView.class, "entities");
            selectedObjectsField = field(SmartCadCanvasView.class, "selectedObjects");
            viewScaleField = field(CadCanvasView.class, "viewScale");
            offsetXField = field(CadCanvasView.class, "offsetX");
            offsetYField = field(CadCanvasView.class, "offsetY");
            startXField = field(CadCanvasView.class, "startX");
            startYField = field(CadCanvasView.class, "startY");
            saveUndoMethod = CadCanvasView.class.getDeclaredMethod("saveUndo");
            saveUndoMethod.setAccessible(true);
        } catch (Exception ignored) {
        }
    }

    private static Field field(Class<?> owner, String name) throws NoSuchFieldException {
        Field f = owner.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }

    public void setWorkspaceListener(WorkspaceListener listener) {
        workspaceListener = listener;
        dispatchWorkspaceState();
    }

    @Override
    public void setTool(int newTool) {
        super.setTool(newTool);
        pendingAxis = 0;
        pendingPerpendicular = false;
        inferenceVisible = false;
        gizmoMode = GIZMO_NONE;
        gizmoVisible = false;
        dispatchWorkspaceState();
    }

    /** The transform gizmo is contextual: selection alone must not cover geometry. */
    public String showTransformGizmo() {
        if (selectionObjects().isEmpty()) return "اول یک شکل یا پروفایل را انتخاب کن";
        super.setTool(TOOL_SELECT);
        gizmoVisible = true;
        gizmoMode = GIZMO_NONE;
        gizmoSessionUndoSteps = 0;
        invalidate();
        return "Move / Rotate فعال شد";
    }

    public void hideTransformGizmo() {
        finishTransformSession();
    }

    /** Commit all manipulator drags made since the tool was opened. */
    public void finishTransformSession() {
        gizmoVisible=false;gizmoSessionUndoSteps=0;cancelGizmo();invalidate();dispatchWorkspaceState();
    }

    /** Restore the selection to its state before the current transform tool. */
    public void cancelTransformSession() {
        int steps=gizmoSessionUndoSteps;
        gizmoVisible=false;gizmoSessionUndoSteps=0;cancelGizmo();
        for(int i=0;i<steps;i++)super.undo();
        axisLocks.clear();lineRelations.clear();coincidenceLinks.clear();lastLineCreated=null;
        invalidate();dispatchWorkspaceState();
    }

    public boolean isTransformSessionActive(){ return gizmoVisible; }

    @Override
    protected void onDraw(Canvas canvas) {
        enforceConstraints();
        super.onDraw(canvas);
        pruneConstraintState();
        drawInference(canvas);
        drawConstraintBadges(canvas);
        drawGizmo(canvas);
    }

    @Override
    public boolean onTouchEvent(MotionEvent original) {
        final int action = original.getActionMasked();

        // Keep two-finger navigation untouched.
        if (original.getPointerCount() >= 2) {
            cancelGizmo();
            inferenceVisible = false;
            boolean handled = super.onTouchEvent(original);
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) dispatchWorkspaceState();
            return handled;
        }

        // Direct gizmo interaction has priority while selecting geometry.
        if (gizmoVisible && getTool() == TOOL_SELECT && handleGizmoTouch(original)) return true;

        // Shapr-like line chaining: touching an endpoint of the last line and
        // dragging immediately starts the next connected segment.
        if (action == MotionEvent.ACTION_DOWN && getTool() == TOOL_SELECT && isLine(lastLineCreated)
                && nearLastLineEndpoint(original.getX(), original.getY())) {
            super.setTool(TOOL_LINE);
            dispatchWorkspaceState();
        }

        int toolBefore = getTool();
        Object previousLine = isLine(lastLineCreated) ? lastLineCreated : null;
        MotionEvent event = original;
        MotionEvent adjusted = null;

        if (toolBefore == TOOL_LINE && (action == MotionEvent.ACTION_MOVE || action == MotionEvent.ACTION_UP)) {
            adjusted = inferLineMotion(original, previousLine);
            if (adjusted != null) event = adjusted;
        }

        boolean handled = super.onTouchEvent(event);
        if (adjusted != null) adjusted.recycle();

        // Capture relationships after a new line has actually been created.
        if (action == MotionEvent.ACTION_UP && toolBefore == TOOL_LINE && getTool() == TOOL_SELECT) {
            Object created = selectedObject();
            if (isLine(created)) {
                lastLineCreated = created;
                if (pendingAxis == 'H' || pendingAxis == 'V') {
                    axisLocks.put(created, new AxisLock(created, pendingAxis));
                }
                if (pendingPerpendicular && isLine(previousLine) && previousLine != created) {
                    addRelation(previousLine, created, false);
                }
                detectCoincidentLinks(created);
            }
            pendingAxis = 0;
            pendingPerpendicular = false;
            inferenceVisible = false;
        }

        enforceConstraints();
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            dispatchWorkspaceState();
        }
        invalidate();
        return handled;
    }

    // ---------------------------------------------------------------------
    // Automatic sketch inference
    // ---------------------------------------------------------------------

    private MotionEvent inferLineMotion(MotionEvent source, Object previousLine) {
        try {
            if (startXField == null || startYField == null) return null;
            float sx = startXField.getFloat(this);
            float sy = startYField.getFloat(this);
            float wx = screenToWorldX(source.getX());
            float wy = screenToWorldY(source.getY());
            float dx = wx - sx;
            float dy = wy - sy;
            float len = (float) Math.hypot(dx, dy);
            if (len < 0.001f) return null;

            float rawAngle = angleDeg(dx, dy);
            float adjustedX = wx;
            float adjustedY = wy;
            char axis = 0;
            boolean perp = false;

            float hErr = angleDistanceToAxis(rawAngle, 0f);
            float vErr = angleDistanceToAxis(rawAngle, 90f);
            if (hErr <= AUTO_AXIS_DEG) {
                adjustedY = sy;
                axis = 'H';
            } else if (vErr <= AUTO_AXIS_DEG) {
                adjustedX = sx;
                axis = 'V';
            } else if (isLine(previousLine) && startsAtEndpoint(previousLine, sx, sy)) {
                float prevAngle = lineAngle(previousLine);
                float target = nearestDirectedAngle(rawAngle, prevAngle + 90f);
                if (angleDistance(rawAngle, target) <= AUTO_PERP_DEG) {
                    double r = Math.toRadians(target);
                    adjustedX = sx + len * (float) Math.cos(r);
                    adjustedY = sy + len * (float) Math.sin(r);
                    perp = true;
                }
            }

            pendingAxis = axis;
            pendingPerpendicular = perp;
            inferenceVisible = axis != 0 || perp;
            inferenceType = axis != 0 ? axis : (perp ? 'P' : 0);
            inferenceStartX = sx;
            inferenceStartY = sy;

            if (!inferenceVisible) return null;
            PointF screen = worldToScreen(adjustedX, adjustedY);
            MotionEvent copy = MotionEvent.obtain(source);
            copy.setLocation(screen.x, screen.y);
            return copy;
        } catch (Exception e) {
            return null;
        }
    }

    private void drawInference(Canvas canvas) {
        if (!inferenceVisible || getTool() != TOOL_LINE) return;
        PointF s = worldToScreen(inferenceStartX, inferenceStartY);
        if (inferenceType == 'H') {
            canvas.drawLine(0f, s.y, getWidth(), s.y, inferencePaint);
            canvas.drawText("H", clamp(s.x + 34f, 24f, getWidth() - 24f), s.y - 10f, inferenceTextPaint);
        } else if (inferenceType == 'V') {
            canvas.drawLine(s.x, 0f, s.x, getHeight(), inferencePaint);
            canvas.drawText("V", s.x + 18f, clamp(s.y - 28f, 28f, getHeight() - 28f), inferenceTextPaint);
        } else if (inferenceType == 'P') {
            canvas.drawCircle(s.x, s.y, 18f, inferencePaint);
            canvas.drawText("⊥", s.x + 31f, s.y - 18f, inferenceTextPaint);
        }
    }

    private boolean nearLastLineEndpoint(float screenX, float screenY) {
        if (!isLine(lastLineCreated)) return false;
        try {
            PointF a = worldToScreen(getFloat(lastLineCreated, "x1"), getFloat(lastLineCreated, "y1"));
            PointF b = worldToScreen(getFloat(lastLineCreated, "x2"), getFloat(lastLineCreated, "y2"));
            return distScreen(screenX, screenY, a.x, a.y) <= CHAIN_HIT_PX
                    || distScreen(screenX, screenY, b.x, b.y) <= CHAIN_HIT_PX;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean startsAtEndpoint(Object line, float x, float y) {
        try {
            float d1 = distWorld(x, y, getFloat(line, "x1"), getFloat(line, "y1"));
            float d2 = distWorld(x, y, getFloat(line, "x2"), getFloat(line, "y2"));
            return Math.min(d1, d2) <= 0.25f;
        } catch (Exception e) {
            return false;
        }
    }

    // ---------------------------------------------------------------------
    // Persistent constraints
    // ---------------------------------------------------------------------

    /** Apply a persistent H/V relationship to the currently selected line(s). */
    public String applyHorizontalVerticalConstraint() {
        List<Object> lines = selectedLines();
        if (lines.isEmpty()) return "برای H/V یک یا چند خط را انتخاب کن";
        saveUndo();
        for (Object line : lines) {
            alignLineToNearestAxis(line);
            float dx = safeGet(line, "x2") - safeGet(line, "x1");
            float dy = safeGet(line, "y2") - safeGet(line, "y1");
            char axis = Math.abs(dx) >= Math.abs(dy) ? 'H' : 'V';
            axisLocks.put(line, new AxisLock(line, axis));
        }
        enforceConstraints();
        invalidate();
        return lines.size() + " خط با قید H/V قفل شد";
    }

    /** Apply a persistent perpendicular relationship between exactly two lines. */
    public String applyPerpendicularConstraint() {
        List<Object> lines = selectedLines();
        if (lines.size() != 2) return "برای Perpendicular دقیقاً دو خط را انتخاب کن";
        saveUndo();
        normalizeRelation(lines.get(0), lines.get(1), false);
        addRelation(lines.get(0), lines.get(1), false);
        enforceConstraints();
        invalidate();
        return "قید عمود ⊥ اعمال شد";
    }

    /** Apply a persistent parallel relationship between exactly two lines. */
    public String applyParallelConstraint() {
        List<Object> lines = selectedLines();
        if (lines.size() != 2) return "برای Parallel دقیقاً دو خط را انتخاب کن";
        saveUndo();
        normalizeRelation(lines.get(0), lines.get(1), true);
        addRelation(lines.get(0), lines.get(1), true);
        enforceConstraints();
        invalidate();
        return "قید موازی ∥ اعمال شد";
    }

    private void alignLineToNearestAxis(Object line) {
        if (!isLine(line)) return;
        try {
            float x1 = getFloat(line, "x1"), y1 = getFloat(line, "y1");
            float x2 = getFloat(line, "x2"), y2 = getFloat(line, "y2");
            float cx = (x1 + x2) / 2f, cy = (y1 + y2) / 2f;
            float len = distWorld(x1, y1, x2, y2);
            if (Math.abs(x2 - x1) >= Math.abs(y2 - y1)) {
                setLine(line, cx - len / 2f, cy, cx + len / 2f, cy);
            } else {
                setLine(line, cx, cy - len / 2f, cx, cy + len / 2f);
            }
        } catch (Exception ignored) {
        }
    }

    private void addRelation(Object a, Object b, boolean parallel) {
        for (LineRelation r : lineRelations) {
            if (((r.a == a && r.b == b) || (r.a == b && r.b == a)) && r.parallel == parallel) return;
        }
        lineRelations.add(new LineRelation(a, b, parallel));
    }

    private void normalizeRelation(Object a, Object b, boolean parallel) {
        if (!isLine(a) || !isLine(b)) return;
        float aa = lineAngle(a);
        float bb = lineAngle(b);
        float target = parallel ? nearestDirectedAngle(bb, aa) : nearestDirectedAngle(bb, aa + 90f);
        setLineAngleAroundCenter(b, target);
    }

    private void detectCoincidentLinks(Object newLine) {
        if (!isLine(newLine)) return;
        List<Object> all = entities();
        for (Object other : all) {
            if (other == newLine || !isLine(other)) continue;
            for (int ni = 0; ni < 2; ni++) {
                PointF np = endpoint(newLine, ni);
                for (int oi = 0; oi < 2; oi++) {
                    PointF op = endpoint(other, oi);
                    if (np != null && op != null && distWorld(np.x, np.y, op.x, op.y) <= 0.08f) {
                        addCoincident(newLine, ni, other, oi);
                    }
                }
            }
        }
    }

    private void addCoincident(Object a, int ai, Object b, int bi) {
        for (CoincidentLink c : coincidenceLinks) {
            if ((c.a.line == a && c.a.index == ai && c.b.line == b && c.b.index == bi)
                    || (c.a.line == b && c.a.index == bi && c.b.line == a && c.b.index == ai)) return;
        }
        coincidenceLinks.add(new CoincidentLink(new EndpointRef(a, ai), new EndpointRef(b, bi)));
    }

    private void enforceConstraints() {
        pruneConstraintState();
        for (AxisLock lock : axisLocks.values()) lock.enforce();
        for (LineRelation relation : lineRelations) relation.enforce();
        for (CoincidentLink link : coincidenceLinks) link.enforce();
        // Axis constraints get a final pass so endpoint edits cannot leave a
        // supposedly horizontal/vertical line slightly tilted.
        for (AxisLock lock : axisLocks.values()) lock.enforce();
    }

    private void pruneConstraintState() {
        List<Object> all = entities();
        Iterator<Map.Entry<Object, AxisLock>> ai = axisLocks.entrySet().iterator();
        while (ai.hasNext()) if (!containsIdentity(all, ai.next().getKey())) ai.remove();

        Iterator<LineRelation> ri = lineRelations.iterator();
        while (ri.hasNext()) {
            LineRelation r = ri.next();
            if (!containsIdentity(all, r.a) || !containsIdentity(all, r.b)) ri.remove();
        }

        Iterator<CoincidentLink> ci = coincidenceLinks.iterator();
        while (ci.hasNext()) {
            CoincidentLink c = ci.next();
            if (!containsIdentity(all, c.a.line) || !containsIdentity(all, c.b.line)) ci.remove();
        }
        if (lastLineCreated != null && !containsIdentity(all, lastLineCreated)) lastLineCreated = null;
    }

    private void drawConstraintBadges(Canvas canvas) {
        for (AxisLock lock : axisLocks.values()) {
            PointF mid = lineMidpoint(lock.line);
            if (mid == null) continue;
            PointF s = worldToScreen(mid.x, mid.y);
            canvas.drawCircle(s.x, s.y - 19f, 12f, constraintPaint);
            canvas.drawText(String.valueOf(lock.axis), s.x, s.y - 12f, constraintTextPaint);
        }
        for (LineRelation r : lineRelations) {
            PointF mid = lineMidpoint(r.b);
            if (mid == null) continue;
            PointF s = worldToScreen(mid.x, mid.y);
            canvas.drawText(r.parallel ? "∥" : "⊥", s.x + 21f, s.y - 16f, constraintTextPaint);
        }
        for (CoincidentLink c : coincidenceLinks) {
            PointF p = c.a.point();
            if (p == null) continue;
            PointF s = worldToScreen(p.x, p.y);
            canvas.drawCircle(s.x, s.y, 5.5f, coincidencePaint);
        }
    }

    // ---------------------------------------------------------------------
    // On-canvas gizmo
    // ---------------------------------------------------------------------

    private void drawGizmo(Canvas canvas) {
        if (!gizmoVisible) { gizmoCenterWorld=null; gizmoCenterScreen=null; return; }
        if (getTool() != TOOL_SELECT) return;
        List<Object> selection = selectionObjects();
        if (selection.isEmpty()) return;
        PointF center = selectionCenter(selection);
        if (center == null) return;
        PointF s = worldToScreen(center.x, center.y);
        gizmoCenterWorld = center;
        gizmoCenterScreen = s;

        float arm = GIZMO_ARM_PX;
        canvas.drawCircle(s.x, s.y, 10f, gizmoCenterPaint);
        canvas.drawCircle(s.x, s.y, 10f, gizmoRotatePaint);

        // X arrow
        canvas.drawLine(s.x, s.y, s.x + arm, s.y, gizmoXPaint);
        canvas.drawLine(s.x + arm, s.y, s.x + arm - 14f, s.y - 9f, gizmoXPaint);
        canvas.drawLine(s.x + arm, s.y, s.x + arm - 14f, s.y + 9f, gizmoXPaint);

        // Y arrow (screen up = model negative Y direction visually, but the
        // drag itself is constrained to the model Y coordinate).
        canvas.drawLine(s.x, s.y, s.x, s.y - arm, gizmoYPaint);
        canvas.drawLine(s.x, s.y - arm, s.x - 9f, s.y - arm + 14f, gizmoYPaint);
        canvas.drawLine(s.x, s.y - arm, s.x + 9f, s.y - arm + 14f, gizmoYPaint);

        float rr = arm * 0.76f;
        canvas.drawArc(new RectF(s.x - rr, s.y - rr, s.x + rr, s.y + rr), 198f, 78f, false, gizmoRotatePaint);
        canvas.drawCircle(s.x - rr * 0.73f, s.y - rr * 0.69f, 6f, gizmoRotatePaint);

        if (gizmoMode != GIZMO_NONE && Math.abs(gizmoDelta) > 0.001f) {
            String text = gizmoMode == GIZMO_ROTATE
                    ? trim(String.format(Locale.US, "%.1f", gizmoDelta)) + "°"
                    : trim(String.format(Locale.US, "%.2f", gizmoDelta)) + " mm";
            float tx = s.x + (gizmoMode == GIZMO_X ? arm + 38f : 42f);
            float ty = s.y + (gizmoMode == GIZMO_Y ? -arm - 18f : -18f);
            canvas.drawText(text, tx, ty, gizmoTextPaint);
        }
    }

    private boolean handleGizmoTouch(MotionEvent event) {
        if (gizmoCenterScreen == null || selectionObjects().isEmpty()) return false;
        int action = event.getActionMasked();
        float x = event.getX(), y = event.getY();
        PointF s = gizmoCenterScreen;

        if (action == MotionEvent.ACTION_DOWN) {
            if (distancePointToSegment(x, y, s.x + 18f, s.y, s.x + GIZMO_ARM_PX + 12f, s.y) <= GIZMO_HIT_PX) {
                beginGizmo(GIZMO_X, event);
                return true;
            }
            if (distancePointToSegment(x, y, s.x, s.y - 18f, s.x, s.y - GIZMO_ARM_PX - 12f) <= GIZMO_HIT_PX) {
                beginGizmo(GIZMO_Y, event);
                return true;
            }
            float r = GIZMO_ARM_PX * 0.76f;
            float dr = Math.abs(distScreen(x, y, s.x, s.y) - r);
            if (dr <= 17f && x <= s.x + 8f && y <= s.y + 8f) {
                beginGizmo(GIZMO_ROTATE, event);
                return true;
            }
            return false;
        }

        if (gizmoMode == GIZMO_NONE) return false;
        if (action == MotionEvent.ACTION_MOVE) {
            if (!gizmoUndoSaved) {
                saveUndo();
                gizmoUndoSaved = true;
                gizmoSessionUndoSteps++;
            }
            if (gizmoMode == GIZMO_X || gizmoMode == GIZMO_Y) {
                float wx = screenToWorldX(x), wy = screenToWorldY(y);
                float dx = gizmoMode == GIZMO_X ? wx - gizmoLastWorldX : 0f;
                float dy = gizmoMode == GIZMO_Y ? wy - gizmoLastWorldY : 0f;
                for (Object e : selectionObjects()) translate(e, dx, dy);
                gizmoLastWorldX = wx;
                gizmoLastWorldY = wy;
                gizmoDelta += gizmoMode == GIZMO_X ? dx : dy;
            } else {
                float a = angleScreen(s.x, s.y, x, y);
                float delta = normalizeAngleDelta(a - gizmoLastAngle);
                for (Object e : selectionObjects()) rotate(e, gizmoCenterWorld.x, gizmoCenterWorld.y, delta);
                gizmoLastAngle = a;
                gizmoDelta += delta;
            }
            enforceConstraints();
            invalidate();
            return true;
        }

        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            cancelGizmo();
            enforceConstraints();
            dispatchWorkspaceState();
            invalidate();
            return true;
        }
        return true;
    }

    private void beginGizmo(int mode, MotionEvent event) {
        gizmoMode = mode;
        gizmoUndoSaved = false;
        gizmoDelta = 0f;
        gizmoLastWorldX = screenToWorldX(event.getX());
        gizmoLastWorldY = screenToWorldY(event.getY());
        gizmoLastAngle = angleScreen(gizmoCenterScreen.x, gizmoCenterScreen.y, event.getX(), event.getY());
    }

    private void cancelGizmo() {
        gizmoMode = GIZMO_NONE;
        gizmoUndoSaved = false;
        gizmoDelta = 0f;
    }

    // ---------------------------------------------------------------------
    // Workspace state and inherited edit operations
    // ---------------------------------------------------------------------

    @Override
    public void deleteSelected() {
        super.deleteSelected();
        pruneConstraintState();
        dispatchWorkspaceState();
    }

    @Override
    public void clearAll() {
        super.clearAll();
        axisLocks.clear();
        lineRelations.clear();
        coincidenceLinks.clear();
        lastLineCreated = null;
        dispatchWorkspaceState();
    }

    @Override
    public void undo() {
        super.undo();
        // Base undo restores copied entity instances, so stale relationship
        // references are deliberately dropped rather than pointing to dead data.
        axisLocks.clear();
        lineRelations.clear();
        coincidenceLinks.clear();
        lastLineCreated = null;
        dispatchWorkspaceState();
    }

    public void dispatchWorkspaceState() {
        if (workspaceListener != null) {
            workspaceListener.onWorkspaceStateChanged(selectedInfo(), canEditExactDimension(), getTool());
        }
    }

    // ---------------------------------------------------------------------
    // Constraint helper classes
    // ---------------------------------------------------------------------

    private class AxisLock {
        final Object line;
        final char axis;
        float x1, y1, x2, y2;

        AxisLock(Object line, char axis) {
            this.line = line;
            this.axis = axis;
            snapshot();
        }

        void enforce() {
            if (!isLine(line)) return;
            try {
                float nx1 = getFloat(line, "x1"), ny1 = getFloat(line, "y1");
                float nx2 = getFloat(line, "x2"), ny2 = getFloat(line, "y2");
                if (axis == 'H') {
                    float m1 = Math.abs(ny1 - y1);
                    float m2 = Math.abs(ny2 - y2);
                    if (m2 > m1 + 0.0001f) ny1 = ny2;
                    else ny2 = ny1;
                } else {
                    float m1 = Math.abs(nx1 - x1);
                    float m2 = Math.abs(nx2 - x2);
                    if (m2 > m1 + 0.0001f) nx1 = nx2;
                    else nx2 = nx1;
                }
                setLine(line, nx1, ny1, nx2, ny2);
                snapshot();
            } catch (Exception ignored) {
            }
        }

        void snapshot() {
            x1 = safeGet(line, "x1"); y1 = safeGet(line, "y1");
            x2 = safeGet(line, "x2"); y2 = safeGet(line, "y2");
        }
    }

    private class LineRelation {
        final Object a;
        final Object b;
        final boolean parallel;
        float lastA;
        float lastB;

        LineRelation(Object a, Object b, boolean parallel) {
            this.a = a;
            this.b = b;
            this.parallel = parallel;
            lastA = lineAngle(a);
            lastB = lineAngle(b);
        }

        void enforce() {
            if (!isLine(a) || !isLine(b)) return;
            float ca = lineAngle(a), cb = lineAngle(b);
            float da = Math.abs(normalizeAngleDelta(ca - lastA));
            float db = Math.abs(normalizeAngleDelta(cb - lastB));
            if (da > db + 0.05f) {
                float target = parallel ? nearestDirectedAngle(cb, ca) : nearestDirectedAngle(cb, ca + 90f);
                setLineAngleAroundCenter(b, target);
            } else if (db > da + 0.05f) {
                float target = parallel ? nearestDirectedAngle(ca, cb) : nearestDirectedAngle(ca, cb + 90f);
                setLineAngleAroundCenter(a, target);
            } else {
                float target = parallel ? nearestDirectedAngle(cb, ca) : nearestDirectedAngle(cb, ca + 90f);
                if (angleDistance(cb, target) > 0.08f) setLineAngleAroundCenter(b, target);
            }
            lastA = lineAngle(a);
            lastB = lineAngle(b);
        }
    }

    private class EndpointRef {
        final Object line;
        final int index;
        float lastX;
        float lastY;

        EndpointRef(Object line, int index) {
            this.line = line;
            this.index = index;
            PointF p = point();
            if (p != null) { lastX = p.x; lastY = p.y; }
        }

        PointF point() { return endpoint(line, index); }

        float movement() {
            PointF p = point();
            return p == null ? 0f : distWorld(lastX, lastY, p.x, p.y);
        }

        void set(PointF p) {
            setEndpoint(line, index, p.x, p.y);
            lastX = p.x;
            lastY = p.y;
        }

        void snapshot() {
            PointF p = point();
            if (p != null) { lastX = p.x; lastY = p.y; }
        }
    }

    private class CoincidentLink {
        final EndpointRef a;
        final EndpointRef b;

        CoincidentLink(EndpointRef a, EndpointRef b) {
            this.a = a;
            this.b = b;
        }

        void enforce() {
            PointF pa = a.point(), pb = b.point();
            if (pa == null || pb == null) return;
            float ma = a.movement(), mb = b.movement();
            if (distWorld(pa.x, pa.y, pb.x, pb.y) > 0.0005f) {
                if (ma >= mb) b.set(pa);
                else a.set(pb);
            }
            a.snapshot();
            b.snapshot();
        }
    }

    // ---------------------------------------------------------------------
    // Reflection / geometry helpers
    // ---------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private List<Object> entities() {
        try { return entitiesField == null ? new ArrayList<>() : (List<Object>) entitiesField.get(this); }
        catch (Exception e) { return new ArrayList<>(); }
    }

    @SuppressWarnings("unchecked")
    private List<Object> smartSelection() {
        try { return selectedObjectsField == null ? new ArrayList<>() : (List<Object>) selectedObjectsField.get(this); }
        catch (Exception e) { return new ArrayList<>(); }
    }

    private List<Object> selectionObjects() {
        List<Object> smart = smartSelection();
        if (!smart.isEmpty()) return new ArrayList<>(smart);
        Object base = selectedObject();
        List<Object> one = new ArrayList<>();
        if (base != null) one.add(base);
        return one;
    }

    private List<Object> selectedLines() {
        List<Object> out = new ArrayList<>();
        for (Object e : selectionObjects()) if (isLine(e)) out.add(e);
        return out;
    }

    private Object selectedObject() {
        try { return selectedField == null ? null : selectedField.get(this); }
        catch (Exception e) { return null; }
    }

    private void saveUndo() {
        try { if (saveUndoMethod != null) saveUndoMethod.invoke(this); }
        catch (Exception ignored) {}
    }

    private boolean isLine(Object e) {
        return e != null && "LineEntity".equals(e.getClass().getSimpleName());
    }

    private float viewScale() {
        try { return viewScaleField == null ? 1f : viewScaleField.getFloat(this); }
        catch (Exception e) { return 1f; }
    }

    private float offsetX() {
        try { return offsetXField == null ? 0f : offsetXField.getFloat(this); }
        catch (Exception e) { return 0f; }
    }

    private float offsetY() {
        try { return offsetYField == null ? 0f : offsetYField.getFloat(this); }
        catch (Exception e) { return 0f; }
    }

    private float screenToWorldX(float sx) { return (sx - offsetX()) / (PX_PER_MM * viewScale()); }
    private float screenToWorldY(float sy) { return (sy - offsetY()) / (PX_PER_MM * viewScale()); }

    private PointF worldToScreen(float x, float y) {
        float s = PX_PER_MM * viewScale();
        return new PointF(offsetX() + x * s, offsetY() + y * s);
    }

    private PointF endpoint(Object line, int index) {
        if (!isLine(line)) return null;
        try {
            if (index == 0) return new PointF(getFloat(line, "x1"), getFloat(line, "y1"));
            return new PointF(getFloat(line, "x2"), getFloat(line, "y2"));
        } catch (Exception e) {
            return null;
        }
    }

    private PointF lineMidpoint(Object line) {
        PointF a = endpoint(line, 0), b = endpoint(line, 1);
        return a == null || b == null ? null : new PointF((a.x + b.x) / 2f, (a.y + b.y) / 2f);
    }

    private float lineAngle(Object line) {
        PointF a = endpoint(line, 0), b = endpoint(line, 1);
        return a == null || b == null ? 0f : angleDeg(b.x - a.x, b.y - a.y);
    }

    private void setLineAngleAroundCenter(Object line, float angle) {
        PointF a = endpoint(line, 0), b = endpoint(line, 1);
        if (a == null || b == null) return;
        float cx = (a.x + b.x) / 2f, cy = (a.y + b.y) / 2f;
        float half = distWorld(a.x, a.y, b.x, b.y) / 2f;
        double r = Math.toRadians(angle);
        float vx = half * (float) Math.cos(r), vy = half * (float) Math.sin(r);
        setLine(line, cx - vx, cy - vy, cx + vx, cy + vy);
    }

    private void setLine(Object line, float x1, float y1, float x2, float y2) {
        setFloat(line, "x1", x1); setFloat(line, "y1", y1);
        setFloat(line, "x2", x2); setFloat(line, "y2", y2);
    }

    private void setEndpoint(Object line, int index, float x, float y) {
        if (index == 0) { setFloat(line, "x1", x); setFloat(line, "y1", y); }
        else { setFloat(line, "x2", x); setFloat(line, "y2", y); }
    }

    private PointF selectionCenter(List<Object> selection) {
        RectF all = null;
        for (Object e : selection) {
            Object b = call(e, "bounds");
            if (!(b instanceof RectF)) continue;
            RectF r = new RectF((RectF) b);
            if (all == null) all = r; else all.union(r);
        }
        return all == null ? null : new PointF(all.centerX(), all.centerY());
    }

    private void translate(Object e, float dx, float dy) {
        call(e, "translate", new Class<?>[]{float.class, float.class}, dx, dy);
    }

    private void rotate(Object e, float cx, float cy, float deg) {
        call(e, "rotate", new Class<?>[]{float.class, float.class, float.class}, cx, cy, deg);
    }

    private Object call(Object target, String name) { return call(target, name, new Class<?>[0]); }

    private Object call(Object target, String name, Class<?>[] types, Object... args) {
        if (target == null) return null;
        Class<?> c = target.getClass();
        while (c != null) {
            try {
                Method m = c.getDeclaredMethod(name, types);
                m.setAccessible(true);
                return m.invoke(target, args);
            } catch (NoSuchMethodException e) {
                c = c.getSuperclass();
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

    private static Field findField(Class<?> c, String name) {
        Class<?> x = c;
        while (x != null) {
            try { Field f = x.getDeclaredField(name); f.setAccessible(true); return f; }
            catch (Exception e) { x = x.getSuperclass(); }
        }
        return null;
    }

    private static float getFloat(Object o, String name) throws Exception {
        Field f = findField(o.getClass(), name);
        if (f == null) throw new NoSuchFieldException(name);
        return f.getFloat(o);
    }

    private static float safeGet(Object o, String name) {
        try { return getFloat(o, name); } catch (Exception e) { return 0f; }
    }

    private static void setFloat(Object o, String name, float v) {
        try {
            Field f = findField(o.getClass(), name);
            if (f != null) f.setFloat(o, v);
        } catch (Exception ignored) {}
    }

    private static boolean containsIdentity(List<Object> list, Object value) {
        for (Object x : list) if (x == value) return true;
        return false;
    }

    private static float distWorld(float x1, float y1, float x2, float y2) {
        return (float) Math.hypot(x2 - x1, y2 - y1);
    }

    private static float distScreen(float x1, float y1, float x2, float y2) {
        return (float) Math.hypot(x2 - x1, y2 - y1);
    }

    private static float angleDeg(float dx, float dy) {
        float a = (float) Math.toDegrees(Math.atan2(dy, dx));
        while (a < 0f) a += 360f;
        while (a >= 360f) a -= 360f;
        return a;
    }

    private static float angleDistanceToAxis(float angle, float axis) {
        return Math.min(angleDistance(angle, axis), angleDistance(angle, axis + 180f));
    }

    private static float angleDistance(float a, float b) {
        return Math.abs(normalizeAngleDelta(a - b));
    }

    private static float nearestDirectedAngle(float reference, float target) {
        float a = normalize360(target);
        float b = normalize360(target + 180f);
        return angleDistance(reference, a) <= angleDistance(reference, b) ? a : b;
    }

    private static float normalize360(float a) {
        while (a < 0f) a += 360f;
        while (a >= 360f) a -= 360f;
        return a;
    }

    private static float normalizeAngleDelta(float d) {
        while (d > 180f) d -= 360f;
        while (d < -180f) d += 360f;
        return d;
    }

    private static float angleScreen(float cx, float cy, float x, float y) {
        return (float) Math.toDegrees(Math.atan2(y - cy, x - cx));
    }

    private static float distancePointToSegment(float px, float py, float x1, float y1, float x2, float y2) {
        float dx = x2 - x1, dy = y2 - y1;
        float l2 = dx * dx + dy * dy;
        if (l2 <= 0.0001f) return distScreen(px, py, x1, y1);
        float t = ((px - x1) * dx + (py - y1) * dy) / l2;
        t = clamp(t, 0f, 1f);
        return distScreen(px, py, x1 + t * dx, y1 + t * dy);
    }

    private static float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }

    private static String trim(String s) {
        while (s.contains(".") && (s.endsWith("0") || s.endsWith("."))) s = s.substring(0, s.length() - 1);
        return s;
    }
}

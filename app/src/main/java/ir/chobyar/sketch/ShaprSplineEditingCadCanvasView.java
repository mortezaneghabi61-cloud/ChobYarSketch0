package ir.chobyar.sketch;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PointF;
import android.graphics.RectF;
import android.text.InputType;
import android.view.MotionEvent;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Shapr-aligned curve editing layer.
 *
 * Behavior intentionally follows the public Shapr3D Spline workflow:
 * - long-tap a selected spline to add a new spline point;
 * - select a point and use Delete to remove that point;
 * - interior points expose Break, endpoints expose Join;
 * - Fit Point splines expose draggable curvature/tangent handles;
 * - curve definition state is reported as remaining DOF instead of a fake green flag.
 *
 * The current document still stores PolylineEntity compatibility geometry. The
 * authoritative Ellipse/Spline parameters remain in ShaprParametricCurveCadCanvasView.
 */
public class ShaprSplineEditingCadCanvasView extends ShaprParametricCurveCadCanvasView {

    private static final float PX_PER_MM = 3f;
    private static final long LONG_TAP_MS = 460L;

    private Field curveSplinesField, curveEllipsesField;
    private Field entitiesField, selectedField, selectedObjectsField;
    private Field viewScaleField, offsetXField, offsetYField;
    private Method rebuildSplineMethod, rebuildEllipseMethod, createSplineMethod, saveUndoMethod;
    private Method isEntityLockedMethod;

    private Object activeSpline;
    private int activePoint = -1;
    private Object tangentDragSpline;
    private int tangentDragPoint = -1;
    private int tangentDragSide = 0; // -1 incoming, +1 outgoing
    private boolean tangentUndoSaved;

    private Object longTapSpline;
    private PointF longTapWorld;
    private float longTapScreenX, longTapScreenY;
    private long longTapStart;
    private boolean longTapMoved;

    private int badgePressed;
    private final RectF deleteBadge = new RectF();
    private final RectF breakJoinBadge = new RectF();
    private final RectF lockPointBadge = new RectF();

    private final IdentityHashMap<Object, Map<Integer, TangentPair>> fitTangents = new IdentityHashMap<>();
    private final IdentityHashMap<Object, Set<Integer>> lockedSplinePoints = new IdentityHashMap<>();
    private final IdentityHashMap<Object, Boolean> ellipseMajorDriven = new IdentityHashMap<>();
    private final IdentityHashMap<Object, Boolean> ellipseMinorDriven = new IdentityHashMap<>();
    private final IdentityHashMap<Object, Boolean> ellipseAngleDriven = new IdentityHashMap<>();

    private final Paint tangentPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tangentPointPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint badgeFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint badgeStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint badgeText = new Paint(Paint.ANTI_ALIAS_FLAG);

    public ShaprSplineEditingCadCanvasView(Context context) {
        super(context);
        initEditingReflection();
        tangentPaint.setStyle(Paint.Style.STROKE);
        tangentPaint.setStrokeWidth(2.2f * getResources().getDisplayMetrics().density);
        tangentPaint.setColor(Color.rgb(80, 125, 215));
        tangentPointPaint.setStyle(Paint.Style.FILL);
        tangentPointPaint.setColor(Color.rgb(242, 135, 36));
        badgeFill.setStyle(Paint.Style.FILL);
        badgeFill.setColor(Color.argb(248, 255, 255, 255));
        badgeStroke.setStyle(Paint.Style.STROKE);
        badgeStroke.setStrokeWidth(1.5f * getResources().getDisplayMetrics().density);
        badgeStroke.setColor(Color.rgb(110, 145, 205));
        badgeText.setColor(Color.rgb(42, 76, 130));
        badgeText.setTextAlign(Paint.Align.CENTER);
        badgeText.setTextSize(13f * getResources().getDisplayMetrics().scaledDensity);
    }

    private void initEditingReflection() {
        try {
            curveSplinesField = field(ShaprParametricCurveCadCanvasView.class, "splines");
            curveEllipsesField = field(ShaprParametricCurveCadCanvasView.class, "ellipses");
            entitiesField = field(CadCanvasView.class, "entities");
            selectedField = field(CadCanvasView.class, "selected");
            selectedObjectsField = field(SmartCadCanvasView.class, "selectedObjects");
            viewScaleField = field(CadCanvasView.class, "viewScale");
            offsetXField = field(CadCanvasView.class, "offsetX");
            offsetYField = field(CadCanvasView.class, "offsetY");

            rebuildSplineMethod = ShaprParametricCurveCadCanvasView.class.getDeclaredMethod("rebuildSpline", Object.class,
                    Class.forName("ir.chobyar.sketch.ShaprParametricCurveCadCanvasView$SplineParam"));
            rebuildSplineMethod.setAccessible(true);
            rebuildEllipseMethod = ShaprParametricCurveCadCanvasView.class.getDeclaredMethod("rebuildEllipse", Object.class,
                    Class.forName("ir.chobyar.sketch.ShaprParametricCurveCadCanvasView$EllipseParam"));
            rebuildEllipseMethod.setAccessible(true);
            createSplineMethod = ShaprParametricCurveCadCanvasView.class.getDeclaredMethod("createSpline", List.class, boolean.class);
            createSplineMethod.setAccessible(true);
            saveUndoMethod = CadCanvasView.class.getDeclaredMethod("saveUndo");
            saveUndoMethod.setAccessible(true);
            isEntityLockedMethod = ParametricSketchCanvasView.class.getDeclaredMethod("isEntityLocked", Object.class);
            isEntityLockedMethod.setAccessible(true);
        } catch (Exception ignored) {
        }
    }

    private static Field field(Class<?> owner, String name) throws Exception {
        Field f = owner.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        pruneEditingState();
        Object selected = singleSelectedLocal();
        if (selected != null && selected == activeSpline && isSpline(selected) && activePoint >= 0) {
            drawActivePointUI(canvas, selected, activePoint);
        } else {
            clearBadges();
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (handleBadgeTouch(event)) return true;
        if (handleTangentDrag(event)) return true;

        final int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN && getTool() == TOOL_SELECT) {
            Object selected = singleSelectedLocal();
            if (isSpline(selected)) {
                PointF w = world(event.getX(), event.getY());
                int source = nearestSourcePoint(selected, w, handleHitWorld());
                if (source >= 0) {
                    activeSpline = selected;
                    activePoint = source;
                    longTapSpline = null;
                    invalidate();
                    // Do not consume: the parent still owns direct point dragging.
                } else if (distanceToSpline(selected, w) <= curveHitWorld()) {
                    // Shapr pen workflow: long-tap the selected curve itself to add a point.
                    longTapSpline = selected;
                    longTapWorld = w;
                    longTapScreenX = event.getX();
                    longTapScreenY = event.getY();
                    longTapStart = event.getEventTime();
                    longTapMoved = false;
                    return true;
                }
            }
        }

        if (longTapSpline != null) {
            if (action == MotionEvent.ACTION_MOVE) {
                if (Math.hypot(event.getX() - longTapScreenX, event.getY() - longTapScreenY) > 18f * density()) {
                    longTapMoved = true;
                }
                return true;
            }
            if (action == MotionEvent.ACTION_UP) {
                long duration = event.getEventTime() - longTapStart;
                Object target = longTapSpline;
                PointF at = world(event.getX(), event.getY());
                longTapSpline = null;
                if (!longTapMoved && duration >= LONG_TAP_MS) {
                    addSplinePointAt(target, at);
                } else {
                    activeSpline = target;
                    activePoint = nearestSourcePoint(target, at, Float.MAX_VALUE);
                    invalidate();
                }
                return true;
            }
            if (action == MotionEvent.ACTION_CANCEL) {
                longTapSpline = null;
                return true;
            }
        }

        boolean handled = super.onTouchEvent(event);
        if ((action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) && activeSpline != null) {
            // Parent point dragging may have changed the authoritative source point.
            // Custom fit tangents are vectors, so they remain valid after the point moves.
            rebuildWithCustomTangents(activeSpline);
        }
        return handled;
    }

    private boolean handleBadgeTouch(MotionEvent e) {
        if (activeSpline == null || activePoint < 0 || getTool() != TOOL_SELECT) return false;
        int a = e.getActionMasked();
        if (a == MotionEvent.ACTION_DOWN) {
            if (deleteBadge.contains(e.getX(), e.getY())) badgePressed = 1;
            else if (breakJoinBadge.contains(e.getX(), e.getY())) badgePressed = 2;
            else if (lockPointBadge.contains(e.getX(), e.getY())) badgePressed = 3;
            else return false;
            return true;
        }
        if (badgePressed != 0) {
            if (a == MotionEvent.ACTION_UP) {
                int p = badgePressed;
                badgePressed = 0;
                if (p == 1 && deleteBadge.contains(e.getX(), e.getY())) deleteActiveSplinePoint();
                else if (p == 2 && breakJoinBadge.contains(e.getX(), e.getY())) breakOrJoinActivePoint();
                else if (p == 3 && lockPointBadge.contains(e.getX(), e.getY())) toggleActivePointLock();
            } else if (a == MotionEvent.ACTION_CANCEL) badgePressed = 0;
            return true;
        }
        return false;
    }

    private boolean handleTangentDrag(MotionEvent e) {
        if (getTool() != TOOL_SELECT || activeSpline == null || !isFitSpline(activeSpline) || activePoint < 0) return false;
        if (isPointLocked(activeSpline, activePoint) || isEntityLocked(activeSpline)) return false;
        int a = e.getActionMasked();
        PointF point = sourcePoint(activeSpline, activePoint);
        if (point == null) return false;
        TangentPair pair = tangentPair(activeSpline, activePoint, false);
        PointF in = tangentHandlePoint(activeSpline, activePoint, -1, pair);
        PointF out = tangentHandlePoint(activeSpline, activePoint, +1, pair);
        float hit = handleHitWorld();

        if (a == MotionEvent.ACTION_DOWN) {
            PointF w = world(e.getX(), e.getY());
            if (in != null && dist(w, in) <= hit) {
                tangentDragSpline = activeSpline;
                tangentDragPoint = activePoint;
                tangentDragSide = -1;
                tangentUndoSaved = false;
                return true;
            }
            if (out != null && dist(w, out) <= hit) {
                tangentDragSpline = activeSpline;
                tangentDragPoint = activePoint;
                tangentDragSide = +1;
                tangentUndoSaved = false;
                return true;
            }
            return false;
        }

        if (tangentDragSpline == null) return false;
        if (a == MotionEvent.ACTION_MOVE) {
            if (!tangentUndoSaved) {
                saveUndo();
                tangentUndoSaved = true;
            }
            PointF anchor = sourcePoint(tangentDragSpline, tangentDragPoint);
            PointF w = world(e.getX(), e.getY());
            if (anchor != null) {
                TangentPair t = tangentPair(tangentDragSpline, tangentDragPoint, true);
                PointF v = new PointF(w.x - anchor.x, w.y - anchor.y);
                if (tangentDragSide < 0) {
                    t.in = v;
                    if (t.linked) t.out = new PointF(-v.x, -v.y);
                } else {
                    t.out = v;
                    if (t.linked) t.in = new PointF(-v.x, -v.y);
                }
                rebuildWithCustomTangents(tangentDragSpline);
                invalidate();
            }
            return true;
        }
        if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
            tangentDragSpline = null;
            tangentDragPoint = -1;
            tangentDragSide = 0;
            tangentUndoSaved = false;
            dispatchWorkspaceState();
            invalidate();
            return true;
        }
        return false;
    }

    private void drawActivePointUI(Canvas c, Object spline, int index) {
        PointF p = sourcePoint(spline, index);
        if (p == null) return;
        PointF sp = screen(p);

        if (isFitSpline(spline)) {
            TangentPair t = tangentPair(spline, index, false);
            PointF hi = tangentHandlePoint(spline, index, -1, t);
            PointF ho = tangentHandlePoint(spline, index, +1, t);
            if (hi != null) {
                PointF s = screen(hi);
                c.drawLine(sp.x, sp.y, s.x, s.y, tangentPaint);
                c.drawCircle(s.x, s.y, 5.2f * density(), tangentPointPaint);
            }
            if (ho != null) {
                PointF s = screen(ho);
                c.drawLine(sp.x, sp.y, s.x, s.y, tangentPaint);
                c.drawCircle(s.x, s.y, 5.2f * density(), tangentPointPaint);
            }
        }

        float w1 = 78f * density(), w2 = 92f * density(), w3 = 72f * density();
        float h = 36f * density(), gap = 6f * density();
        float total = w1 + w2 + w3 + gap * 2f;
        float left = clamp(sp.x - total / 2f, 6f * density(), Math.max(6f * density(), getWidth() - total - 6f * density()));
        float top = clamp(sp.y - 62f * density(), 6f * density(), Math.max(6f * density(), getHeight() - h - 6f * density()));
        deleteBadge.set(left, top, left + w1, top + h);
        breakJoinBadge.set(deleteBadge.right + gap, top, deleteBadge.right + gap + w2, top + h);
        lockPointBadge.set(breakJoinBadge.right + gap, top, breakJoinBadge.right + gap + w3, top + h);
        drawBadge(c, deleteBadge, "Delete");
        drawBadge(c, breakJoinBadge, isEndpoint(spline, index) ? "Join" : "Break");
        drawBadge(c, lockPointBadge, isPointLocked(spline, index) ? "Unlock" : "Lock");
    }

    private void drawBadge(Canvas c, RectF r, String label) {
        c.drawRoundRect(r, 10f * density(), 10f * density(), badgeFill);
        c.drawRoundRect(r, 10f * density(), 10f * density(), badgeStroke);
        c.drawText(label, r.centerX(), r.centerY() - (badgeText.ascent() + badgeText.descent()) / 2f, badgeText);
    }

    private void clearBadges() {
        deleteBadge.setEmpty();
        breakJoinBadge.setEmpty();
        lockPointBadge.setEmpty();
        badgePressed = 0;
    }

    // ------------------------------------------------------------------
    // Spline point operations
    // ------------------------------------------------------------------

    private void addSplinePointAt(Object spline, PointF tapped) {
        Object param = splineParam(spline);
        List<PointF> points = splinePoints(param);
        if (param == null || points == null || points.size() < 2 || isEntityLocked(spline)) return;
        saveUndo();

        int insert;
        PointF added;
        if (splineFit(param)) {
            List<PointF> sampled = polylinePoints(spline);
            int si = nearestPointIndex(sampled, tapped);
            int seg = sampled.size() <= 1 ? 0 : Math.min(points.size() - 2,
                    (int)Math.floor((double)si * (points.size() - 1) / Math.max(1, sampled.size() - 1)));
            insert = seg + 1;
            added = nearestPointOnPolyline(sampled, tapped);
        } else {
            SegmentHit h = nearestSourceSegment(points, tapped);
            insert = Math.max(1, Math.min(points.size() - 1, h.index + 1));
            added = h.point;
        }
        points.add(insert, new PointF(added.x, added.y));
        shiftPointStateAfterInsert(spline, insert);
        rebuildSpline(spline, param);
        activeSpline = spline;
        activePoint = insert;
        toast("New Spline Point اضافه شد");
        dispatchWorkspaceState();
        invalidate();
    }

    private void deleteActiveSplinePoint() {
        Object spline = activeSpline;
        Object param = splineParam(spline);
        List<PointF> points = splinePoints(param);
        if (param == null || points == null || activePoint < 0 || activePoint >= points.size()) return;
        if (isEntityLocked(spline) || isPointLocked(spline, activePoint)) {
            toast("این نقطه قفل است");
            return;
        }
        if (points.size() <= 3) {
            toast("Spline حداقل سه نقطه لازم دارد");
            return;
        }
        saveUndo();
        int removed = activePoint;
        points.remove(removed);
        shiftPointStateAfterDelete(spline, removed);
        activePoint = Math.min(removed, points.size() - 1);
        rebuildSpline(spline, param);
        toast("Spline Point حذف شد");
        dispatchWorkspaceState();
        invalidate();
    }

    private void breakOrJoinActivePoint() {
        Object spline = activeSpline;
        Object param = splineParam(spline);
        List<PointF> p = splinePoints(param);
        if (param == null || p == null || activePoint < 0 || activePoint >= p.size()) return;
        if (isEntityLocked(spline)) {
            toast("Spline قفل است");
            return;
        }
        if (activePoint == 0 || activePoint == p.size() - 1) joinAtEndpoint(spline, activePoint);
        else breakAtPoint(spline, activePoint);
    }

    private void breakAtPoint(Object spline, int index) {
        Object param = splineParam(spline);
        List<PointF> p = splinePoints(param);
        if (p == null || index <= 0 || index >= p.size() - 1) return;
        List<PointF> a = copy(p.subList(0, index + 1));
        List<PointF> b = copy(p.subList(index, p.size()));
        if (a.size() < 2 || b.size() < 2) return;
        boolean fit = splineFit(param);
        saveUndo();
        Object first = createSpline(a, fit);
        Object second = createSpline(b, fit);
        removeCurveEntity(spline);
        clearCurveState(spline);
        activeSpline = second != null ? second : first;
        activePoint = activeSpline == second ? 0 : Math.max(0, a.size() - 1);
        toast("Spline در نقطه انتخابی Break شد");
        dispatchWorkspaceState();
        invalidate();
    }

    private void joinAtEndpoint(Object spline, int endpointIndex) {
        Object other = nearestJoinCandidate(spline, endpointIndex);
        if (other == null) {
            toast("برای Join یک Spline دوم را هم انتخاب کن یا انتهای آن را نزدیک کن");
            return;
        }
        Object pa = splineParam(spline), pb = splineParam(other);
        if (pa == null || pb == null || splineFit(pa) != splineFit(pb)) {
            toast("Join فقط بین دو Spline هم‌نوع انجام می‌شود");
            return;
        }
        List<PointF> a = copy(splinePoints(pa));
        List<PointF> b = copy(splinePoints(pb));
        if (a.isEmpty() || b.isEmpty()) return;

        boolean aAtStart = endpointIndex == 0;
        PointF joinA = aAtStart ? a.get(0) : a.get(a.size() - 1);
        float dStart = dist(joinA, b.get(0));
        float dEnd = dist(joinA, b.get(b.size() - 1));
        boolean bAtStart = dStart <= dEnd;
        if (aAtStart) reverse(a);
        if (!bAtStart) reverse(b);
        PointF ja = a.get(a.size() - 1), jb = b.get(0);
        PointF mergedPoint = new PointF((ja.x + jb.x) / 2f, (ja.y + jb.y) / 2f);
        a.set(a.size() - 1, mergedPoint);
        b.set(0, mergedPoint);
        List<PointF> merged = new ArrayList<>(a);
        for (int i = 1; i < b.size(); i++) merged.add(new PointF(b.get(i).x, b.get(i).y));

        saveUndo();
        Object joined = createSpline(merged, splineFit(pa));
        removeCurveEntity(spline);
        removeCurveEntity(other);
        clearCurveState(spline);
        clearCurveState(other);
        activeSpline = joined;
        activePoint = a.size() - 1;
        toast("Splineها Join شدند");
        dispatchWorkspaceState();
        invalidate();
    }

    private Object nearestJoinCandidate(Object spline, int endpointIndex) {
        List<Object> sel = selectionLocal();
        for (Object e : sel) if (e != spline && isSpline(e)) return e;
        PointF p = sourcePoint(spline, endpointIndex);
        if (p == null) return null;
        Object best = null;
        float bd = 12f; // mm, intentionally generous for pen workflow.
        for (Object e : splineMap().keySet()) {
            if (e == spline || !containsIdentity(entities(), e)) continue;
            Object param = splineParam(e);
            List<PointF> q = splinePoints(param);
            if (q == null || q.isEmpty()) continue;
            float d0 = dist(p, q.get(0)), d1 = dist(p, q.get(q.size() - 1));
            float d = Math.min(d0, d1);
            if (d < bd) { bd = d; best = e; }
        }
        return best;
    }

    private void toggleActivePointLock() {
        if (activeSpline == null || activePoint < 0) return;
        Set<Integer> set = lockedSplinePoints.get(activeSpline);
        if (set == null) { set = new HashSet<>(); lockedSplinePoints.put(activeSpline, set); }
        if (set.contains(activePoint)) {
            set.remove(activePoint);
            toast("Spline Point باز شد");
        } else {
            set.add(activePoint);
            toast("Spline Point قفل شد");
        }
        dispatchWorkspaceState();
        invalidate();
    }

    // ------------------------------------------------------------------
    // Fit point tangent handles
    // ------------------------------------------------------------------

    private TangentPair tangentPair(Object spline, int index, boolean create) {
        Map<Integer, TangentPair> map = fitTangents.get(spline);
        if (map == null && create) { map = new HashMap<>(); fitTangents.put(spline, map); }
        TangentPair pair = map == null ? null : map.get(index);
        if (pair == null && create) {
            PointF d = defaultTangent(spline, index);
            pair = new TangentPair(new PointF(-d.x, -d.y), new PointF(d.x, d.y), true);
            map.put(index, pair);
        }
        return pair;
    }

    private PointF tangentHandlePoint(Object spline, int index, int side, TangentPair pair) {
        PointF p = sourcePoint(spline, index);
        if (p == null) return null;
        PointF v;
        if (pair != null) v = side < 0 ? pair.in : pair.out;
        else {
            PointF d = defaultTangent(spline, index);
            v = side < 0 ? new PointF(-d.x, -d.y) : d;
        }
        return new PointF(p.x + v.x, p.y + v.y);
    }

    private PointF defaultTangent(Object spline, int index) {
        List<PointF> p = splinePoints(splineParam(spline));
        if (p == null || p.isEmpty()) return new PointF(8f, 0f);
        PointF prev = p.get(Math.max(0, index - 1));
        PointF next = p.get(Math.min(p.size() - 1, index + 1));
        float vx = (next.x - prev.x) * 0.28f;
        float vy = (next.y - prev.y) * 0.28f;
        if (Math.hypot(vx, vy) < 0.2f) return new PointF(8f, 0f);
        return new PointF(vx, vy);
    }

    private void rebuildWithCustomTangents(Object spline) {
        if (!isFitSpline(spline)) return;
        Map<Integer, TangentPair> map = fitTangents.get(spline);
        if (map == null || map.isEmpty()) return;
        Object param = splineParam(spline);
        List<PointF> p = splinePoints(param);
        if (p == null || p.size() < 2) return;
        List<PointF> sampled = new ArrayList<>();
        for (int i = 0; i < p.size() - 1; i++) {
            PointF p0 = p.get(i), p1 = p.get(i + 1);
            TangentPair t0 = map.get(i), t1 = map.get(i + 1);
            PointF m0 = t0 == null ? defaultTangent(spline, i) : t0.out;
            PointF m1 = t1 == null ? defaultTangent(spline, i + 1) : new PointF(-t1.in.x, -t1.in.y);
            for (int j = 0; j < 16; j++) {
                float t = j / 16f, t2 = t * t, t3 = t2 * t;
                float h00 = 2*t3 - 3*t2 + 1;
                float h10 = t3 - 2*t2 + t;
                float h01 = -2*t3 + 3*t2;
                float h11 = t3 - t2;
                sampled.add(new PointF(h00*p0.x + h10*m0.x + h01*p1.x + h11*m1.x,
                        h00*p0.y + h10*m0.y + h01*p1.y + h11*m1.y));
            }
        }
        sampled.add(new PointF(p.get(p.size() - 1).x, p.get(p.size() - 1).y));
        replacePolylinePoints(spline, sampled);
    }

    private static class TangentPair {
        PointF in, out;
        boolean linked;
        TangentPair(PointF in, PointF out, boolean linked) { this.in = in; this.out = out; this.linked = linked; }
    }

    // ------------------------------------------------------------------
    // Adaptive curve editor and curve DOF
    // ------------------------------------------------------------------

    @Override
    public void showCurveEditor() {
        List<Object> selected = selectionLocal();
        if (selected.size() == 2 && isSpline(selected.get(0)) && isSpline(selected.get(1))) {
            activeSpline = selected.get(0);
            activePoint = nearestEndpointToOther(selected.get(0), selected.get(1));
            joinAtEndpoint(activeSpline, activePoint);
            return;
        }
        Object one = singleSelectedLocal();
        if (isEllipse(one)) { showEllipseDrivingEditor(one); return; }
        if (isSpline(one)) {
            activeSpline = one;
            if (activePoint < 0 || activePoint >= splinePointCount(one)) activePoint = 0;
            String[] items = isFitSpline(one)
                    ? new String[]{"＋ New Spline Point (Long tap)", "− Delete selected point", "Break / Join", "Tangent handles: linked / smooth", "Lock / Unlock point"}
                    : new String[]{"＋ New Control Point (Long tap)", "− Delete selected point", "Break / Join", "Lock / Unlock point"};
            new AlertDialog.Builder(getContext()).setTitle(isFitSpline(one) ? "Fit Point Spline" : "Control Point Spline")
                    .setItems(items, (d,w)->{
                        if (w == 0) toast("روی خود Spline نگه دار تا New Spline Point ساخته شود");
                        else if (w == 1) deleteActiveSplinePoint();
                        else if (w == 2) breakOrJoinActivePoint();
                        else if (isFitSpline(one) && w == 3) toggleTangentLink(one, activePoint);
                        else toggleActivePointLock();
                    }).setNegativeButton("بستن", null).show();
            return;
        }
        super.showCurveEditor();
    }

    private void toggleTangentLink(Object spline, int index) {
        TangentPair t = tangentPair(spline, index, true);
        t.linked = !t.linked;
        toast(t.linked ? "Tangent handles linked" : "Tangent handles مستقل");
        invalidate();
    }

    private void showEllipseDrivingEditor(Object ellipse) {
        Object e = ellipseParam(ellipse);
        if (e == null) { super.showCurveEditor(); return; }
        LinearLayout box = new LinearLayout(getContext());
        box.setOrientation(LinearLayout.VERTICAL);
        EditText major = input(fmt(ellipseRx(e) * 2f));
        EditText minor = input(fmt(ellipseRy(e) * 2f));
        EditText angle = input(fmt(ellipseAngle(e)));
        major.setHint("Major axis • mm");
        minor.setHint("Minor axis • mm");
        angle.setHint("Rotation °");
        box.addView(major); box.addView(minor); box.addView(angle);
        new AlertDialog.Builder(getContext()).setTitle("Ellipse • Driving dimensions")
                .setMessage("Major/Minor اندازه را قفل می‌کنند؛ مرکز هنوز قابل جابه‌جایی است.")
                .setView(box)
                .setPositiveButton("اعمال", (d,w)->{
                    try {
                        saveUndo();
                        setFloat(e, "rx", Math.max(.05f, lengthMm(major.getText().toString()) / 2f));
                        setFloat(e, "ry", Math.max(.05f, lengthMm(minor.getText().toString()) / 2f));
                        setFloat(e, "angle", Float.parseFloat(normalizeDigits(angle.getText().toString())));
                        ellipseMajorDriven.put(ellipse, true);
                        ellipseMinorDriven.put(ellipse, true);
                        ellipseAngleDriven.put(ellipse, true);
                        rebuildEllipse(ellipse, e);
                        dispatchWorkspaceState();
                        invalidate();
                    } catch (Exception ex) { toast("مقادیر Ellipse درست نیست"); }
                }).setNegativeButton("لغو", null).show();
    }

    @Override
    public String applySelectedDimension(String raw) {
        Object before = singleSelectedLocal();
        String result = super.applySelectedDimension(raw);
        if (isEllipse(before) && result != null && !result.contains("درست نیست") && !result.contains("وارد کن")) {
            ellipseMajorDriven.put(before, true);
            ellipseMinorDriven.put(before, true);
        }
        return result;
    }

    @Override
    public String selectedInfo() {
        Object s = singleSelectedLocal();
        if (isEllipse(s)) {
            Object e = ellipseParam(s);
            int dof = ellipseDof(s);
            return "Ellipse | Major " + dual(ellipseRx(e)*2f) + " | Minor " + dual(ellipseRy(e)*2f)
                    + " | Rotation " + fmt(ellipseAngle(e)) + "° | DOF " + dof + (dof==0 ? " | Fully-defined" : " | Under-defined");
        }
        if (isSpline(s)) {
            int dof = splineDof(s);
            return (isFitSpline(s) ? "Fit Point Spline" : "Control Point Spline") + " | Points " + splinePointCount(s)
                    + " | DOF " + dof + (dof==0 ? " | Fully-defined" : " | Under-defined");
        }
        return super.selectedInfo();
    }

    @Override
    public String sketchStateSummary() {
        int ellipseFull=0, ellipseUnder=0, splineFull=0, splineUnder=0;
        for (Object e : ellipseMap().keySet()) if (containsIdentity(entities(), e)) { if (ellipseDof(e)==0) ellipseFull++; else ellipseUnder++; }
        for (Object s : splineMap().keySet()) if (containsIdentity(entities(), s)) { if (splineDof(s)==0) splineFull++; else splineUnder++; }
        return super.sketchStateSummary() + "\nCurve DOF:\nEllipse Fully/Under: " + ellipseFull + "/" + ellipseUnder
                + "\nSpline Fully/Under: " + splineFull + "/" + splineUnder;
    }

    private int ellipseDof(Object ellipse) {
        if (ellipse == null) return 0;
        if (isEntityLocked(ellipse)) return 0;
        int dof = 5; // center X/Y, major, minor, rotation.
        if (Boolean.TRUE.equals(ellipseMajorDriven.get(ellipse))) dof--;
        if (Boolean.TRUE.equals(ellipseMinorDriven.get(ellipse))) dof--;
        if (Boolean.TRUE.equals(ellipseAngleDriven.get(ellipse))) dof--;
        return Math.max(0, dof);
    }

    private int splineDof(Object spline) {
        if (spline == null) return 0;
        if (isEntityLocked(spline)) return 0;
        int n = splinePointCount(spline);
        int dof = n * 2;
        Set<Integer> locks = lockedSplinePoints.get(spline);
        if (locks != null) for (Integer i : locks) if (i != null && i >= 0 && i < n) dof -= 2;
        Map<Integer,TangentPair> tangents = fitTangents.get(spline);
        if (tangents != null) {
            // A linked custom Fit tangent has direction + magnitude = 2 DOF.
            // Independent incoming/outgoing handles have 4 DOF.
            for (TangentPair t : tangents.values()) dof += t.linked ? 2 : 4;
        }
        return Math.max(0, dof);
    }

    // ------------------------------------------------------------------
    // Reflection/geometry helpers
    // ------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private IdentityHashMap<Object,Object> splineMap() {
        try { return (IdentityHashMap<Object,Object>) curveSplinesField.get(this); }
        catch (Exception e) { return new IdentityHashMap<>(); }
    }

    @SuppressWarnings("unchecked")
    private IdentityHashMap<Object,Object> ellipseMap() {
        try { return (IdentityHashMap<Object,Object>) curveEllipsesField.get(this); }
        catch (Exception e) { return new IdentityHashMap<>(); }
    }

    private Object splineParam(Object spline) { return splineMap().get(spline); }
    private Object ellipseParam(Object ellipse) { return ellipseMap().get(ellipse); }
    private boolean isSpline(Object e) { return e != null && splineMap().containsKey(e); }
    private boolean isEllipse(Object e) { return e != null && ellipseMap().containsKey(e); }
    private boolean isFitSpline(Object e) { Object p=splineParam(e); return p!=null && splineFit(p); }
    private boolean splineFit(Object p) { return boolField(p,"fit"); }

    @SuppressWarnings("unchecked")
    private List<PointF> splinePoints(Object param) {
        if (param == null) return null;
        try { Field f=findField(param.getClass(),"points"); Object v=f==null?null:f.get(param); return v instanceof List ? (List<PointF>)v : null; }
        catch (Exception e) { return null; }
    }

    private int splinePointCount(Object spline) { List<PointF> p=splinePoints(splineParam(spline)); return p==null?0:p.size(); }
    private PointF sourcePoint(Object spline,int index){List<PointF> p=splinePoints(splineParam(spline));return p==null||index<0||index>=p.size()?null:p.get(index);}
    private boolean isEndpoint(Object spline,int index){int n=splinePointCount(spline);return n>0&&(index==0||index==n-1);}

    @SuppressWarnings("unchecked")
    private List<Object> entities(){try{Object v=entitiesField.get(this);return v instanceof List?(List<Object>)v:new ArrayList<>();}catch(Exception e){return new ArrayList<>();}}
    @SuppressWarnings("unchecked")
    private List<Object> selectionLocal(){try{Object v=selectedObjectsField.get(this);if(v instanceof List&&!((List<?>)v).isEmpty())return new ArrayList<>((List<Object>)v);Object one=selectedField.get(this);List<Object> o=new ArrayList<>();if(one!=null)o.add(one);return o;}catch(Exception e){return new ArrayList<>();}}
    private Object singleSelectedLocal(){List<Object>s=selectionLocal();return s.size()==1?s.get(0):null;}

    private void rebuildSpline(Object e,Object param){try{if(rebuildSplineMethod!=null)rebuildSplineMethod.invoke(this,e,param);rebuildWithCustomTangents(e);}catch(Exception ignored){}}
    private void rebuildEllipse(Object e,Object param){try{if(rebuildEllipseMethod!=null)rebuildEllipseMethod.invoke(this,e,param);}catch(Exception ignored){}}
    private Object createSpline(List<PointF> p,boolean fit){try{return createSplineMethod==null?null:createSplineMethod.invoke(this,p,fit);}catch(Exception e){return null;}}
    private void saveUndo(){try{if(saveUndoMethod!=null)saveUndoMethod.invoke(this);}catch(Exception ignored){}}

    @SuppressWarnings("unchecked")
    private void replacePolylinePoints(Object e,List<PointF> p){
        try{Field f=findField(e.getClass(),"points");Object v=f==null?null:f.get(e);if(v instanceof List){List<PointF>d=(List<PointF>)v;d.clear();for(PointF q:p)d.add(new PointF(q.x,q.y));}}catch(Exception ignored){}
    }

    private void removeCurveEntity(Object e){entities().remove(e);splineMap().remove(e);ellipseMap().remove(e);try{Object one=selectedField.get(this);if(one==e)selectedField.set(this,null);Object v=selectedObjectsField.get(this);if(v instanceof List)((List<?>)v).remove(e);}catch(Exception ignored){}}
    private void clearCurveState(Object e){fitTangents.remove(e);lockedSplinePoints.remove(e);ellipseMajorDriven.remove(e);ellipseMinorDriven.remove(e);ellipseAngleDriven.remove(e);if(activeSpline==e){activeSpline=null;activePoint=-1;}}

    private boolean isEntityLocked(Object e){try{return isEntityLockedMethod!=null&&Boolean.TRUE.equals(isEntityLockedMethod.invoke(this,e));}catch(Exception ex){return false;}}
    private boolean isPointLocked(Object spline,int idx){Set<Integer>s=lockedSplinePoints.get(spline);return s!=null&&s.contains(idx);}

    private int nearestSourcePoint(Object spline,PointF w,float hit){List<PointF>p=splinePoints(splineParam(spline));if(p==null)return-1;int best=-1;float bd=hit;for(int i=0;i<p.size();i++){float d=dist(p.get(i),w);if(d<=bd){bd=d;best=i;}}return best;}
    private float distanceToSpline(Object spline,PointF w){List<PointF>p=polylinePoints(spline);if(p.size()<2)return Float.MAX_VALUE;float best=Float.MAX_VALUE;for(int i=1;i<p.size();i++)best=Math.min(best,distanceToSegment(w,p.get(i-1),p.get(i)));return best;}
    @SuppressWarnings("unchecked") private List<PointF> polylinePoints(Object e){List<PointF>o=new ArrayList<>();try{Field f=findField(e.getClass(),"points");Object v=f==null?null:f.get(e);if(v instanceof List)for(Object q:(List<Object>)v)if(q instanceof PointF){PointF p=(PointF)q;o.add(new PointF(p.x,p.y));}}catch(Exception ignored){}return o;}

    private static class SegmentHit{final int index;final PointF point;SegmentHit(int i,PointF p){index=i;point=p;}}
    private static SegmentHit nearestSourceSegment(List<PointF> p,PointF q){int bi=0;PointF bp=new PointF(p.get(0).x,p.get(0).y);float bd=Float.MAX_VALUE;for(int i=0;i<p.size()-1;i++){PointF pr=projectSegment(q,p.get(i),p.get(i+1));float d=dist(q,pr);if(d<bd){bd=d;bi=i;bp=pr;}}return new SegmentHit(bi,bp);}
    private static PointF nearestPointOnPolyline(List<PointF> p,PointF q){if(p==null||p.size()<2)return new PointF(q.x,q.y);PointF best=new PointF(p.get(0).x,p.get(0).y);float bd=Float.MAX_VALUE;for(int i=0;i<p.size()-1;i++){PointF x=projectSegment(q,p.get(i),p.get(i+1));float d=dist(q,x);if(d<bd){bd=d;best=x;}}return best;}
    private static int nearestPointIndex(List<PointF> p,PointF q){int best=0;float bd=Float.MAX_VALUE;for(int i=0;i<p.size();i++){float d=dist(p.get(i),q);if(d<bd){bd=d;best=i;}}return best;}
    private static PointF projectSegment(PointF p,PointF a,PointF b){float dx=b.x-a.x,dy=b.y-a.y,l2=dx*dx+dy*dy;if(l2<1e-8f)return new PointF(a.x,a.y);float t=((p.x-a.x)*dx+(p.y-a.y)*dy)/l2;t=Math.max(0f,Math.min(1f,t));return new PointF(a.x+t*dx,a.y+t*dy);}
    private static float distanceToSegment(PointF p,PointF a,PointF b){return dist(p,projectSegment(p,a,b));}

    private int nearestEndpointToOther(Object a,Object b){List<PointF>pa=splinePoints(splineParam(a)),pb=splinePoints(splineParam(b));if(pa==null||pb==null||pa.isEmpty()||pb.isEmpty())return 0;float d0=Math.min(dist(pa.get(0),pb.get(0)),dist(pa.get(0),pb.get(pb.size()-1)));float d1=Math.min(dist(pa.get(pa.size()-1),pb.get(0)),dist(pa.get(pa.size()-1),pb.get(pb.size()-1)));return d0<=d1?0:pa.size()-1;}

    private void shiftPointStateAfterInsert(Object spline,int index){
        Set<Integer> old=lockedSplinePoints.get(spline);if(old!=null){Set<Integer>n=new HashSet<>();for(int i:old)n.add(i>=index?i+1:i);lockedSplinePoints.put(spline,n);}
        Map<Integer,TangentPair> tm=fitTangents.get(spline);if(tm!=null){Map<Integer,TangentPair>n=new HashMap<>();for(Map.Entry<Integer,TangentPair>e:tm.entrySet())n.put(e.getKey()>=index?e.getKey()+1:e.getKey(),e.getValue());fitTangents.put(spline,n);}
    }
    private void shiftPointStateAfterDelete(Object spline,int index){
        Set<Integer> old=lockedSplinePoints.get(spline);if(old!=null){Set<Integer>n=new HashSet<>();for(int i:old)if(i!=index)n.add(i>index?i-1:i);lockedSplinePoints.put(spline,n);}
        Map<Integer,TangentPair> tm=fitTangents.get(spline);if(tm!=null){Map<Integer,TangentPair>n=new HashMap<>();for(Map.Entry<Integer,TangentPair>e:tm.entrySet())if(e.getKey()!=index)n.put(e.getKey()>index?e.getKey()-1:e.getKey(),e.getValue());fitTangents.put(spline,n);}
    }

    private void pruneEditingState(){
        List<Object> all=entities();
        pruneMapKeys(fitTangents,all);pruneMapKeys(lockedSplinePoints,all);pruneMapKeys(ellipseMajorDriven,all);pruneMapKeys(ellipseMinorDriven,all);pruneMapKeys(ellipseAngleDriven,all);
        Object sel=singleSelectedLocal();if(activeSpline!=null&&(!containsIdentity(all,activeSpline)||sel!=activeSpline)){activeSpline=null;activePoint=-1;}
        if(activeSpline!=null&&activePoint>=splinePointCount(activeSpline))activePoint=Math.max(0,splinePointCount(activeSpline)-1);
    }
    private static <T> void pruneMapKeys(IdentityHashMap<Object,T> map,List<Object>all){List<Object>dead=new ArrayList<>();for(Object k:map.keySet())if(!containsIdentity(all,k))dead.add(k);for(Object k:dead)map.remove(k);}

    private PointF world(float sx,float sy){float k=PX_PER_MM*Math.max(.0001f,viewScale());return new PointF((sx-offsetX())/k,(sy-offsetY())/k);}
    private PointF screen(PointF p){float k=PX_PER_MM*viewScale();return new PointF(offsetX()+p.x*k,offsetY()+p.y*k);}
    private float viewScale(){try{return viewScaleField.getFloat(this);}catch(Exception e){return 1f;}}
    private float offsetX(){try{return offsetXField.getFloat(this);}catch(Exception e){return 0f;}}
    private float offsetY(){try{return offsetYField.getFloat(this);}catch(Exception e){return 0f;}}
    private float handleHitWorld(){return 20f*density()/(PX_PER_MM*Math.max(.05f,viewScale()));}
    private float curveHitWorld(){return 16f*density()/(PX_PER_MM*Math.max(.05f,viewScale()));}
    private float density(){return getResources().getDisplayMetrics().density;}

    private static boolean containsIdentity(List<Object>list,Object x){for(Object e:list)if(e==x)return true;return false;}
    private static List<PointF>copy(List<PointF>src){List<PointF>o=new ArrayList<>();if(src!=null)for(PointF p:src)o.add(new PointF(p.x,p.y));return o;}
    private static void reverse(List<PointF>p){for(int i=0,j=p.size()-1;i<j;i++,j--){PointF t=p.get(i);p.set(i,p.get(j));p.set(j,t);}}
    private static float dist(PointF a,PointF b){return(float)Math.hypot(b.x-a.x,b.y-a.y);}
    private static float clamp(float v,float a,float b){return Math.max(a,Math.min(b,v));}
    private static Field findField(Class<?>c,String n){for(Class<?>x=c;x!=null;x=x.getSuperclass())try{Field f=x.getDeclaredField(n);f.setAccessible(true);return f;}catch(Exception ignored){}return null;}
    private static boolean boolField(Object o,String n){try{Field f=findField(o.getClass(),n);return f!=null&&f.getBoolean(o);}catch(Exception e){return false;}}
    private static float floatField(Object o,String n){try{Field f=findField(o.getClass(),n);return f==null?0f:f.getFloat(o);}catch(Exception e){return 0f;}}
    private static void setFloat(Object o,String n,float v){try{Field f=findField(o.getClass(),n);if(f!=null)f.setFloat(o,v);}catch(Exception ignored){}}
    private static float ellipseRx(Object e){return Math.abs(floatField(e,"rx"));}
    private static float ellipseRy(Object e){return Math.abs(floatField(e,"ry"));}
    private static float ellipseAngle(Object e){return floatField(e,"angle");}

    private EditText input(String text){EditText e=new EditText(getContext());e.setSingleLine(true);e.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);e.setText(text);e.setSelectAllOnFocus(true);return e;}
    private static float lengthMm(String raw){String s=normalizeDigits(raw).toLowerCase(Locale.US).trim().replace('،','.').replace(',','.');boolean cm=s.endsWith("cm")||s.endsWith("سانتیمتر")||s.endsWith("سانتی‌متر");s=s.replace("میلی‌متر","").replace("میلیمتر","").replace("سانتی‌متر","").replace("سانتیمتر","").replace("mm","").replace("cm","").trim();float v=Float.parseFloat(s);return cm?v*10f:v;}
    private static String normalizeDigits(String s){if(s==null)return"";StringBuilder b=new StringBuilder();for(int i=0;i<s.length();i++){char c=s.charAt(i);if(c>='۰'&&c<='۹')b.append((char)('0'+c-'۰'));else if(c>='٠'&&c<='٩')b.append((char)('0'+c-'٠'));else b.append(c);}return b.toString();}
    private static String dual(float mm){return fmt(mm)+" mm";}
    private static String fmt(float v){String s=String.format(Locale.US,"%.2f",v);while(s.contains(".")&&(s.endsWith("0")||s.endsWith(".")))s=s.substring(0,s.length()-1);return s;}
    private void toast(String s){Toast.makeText(getContext(),s,Toast.LENGTH_SHORT).show();}
}

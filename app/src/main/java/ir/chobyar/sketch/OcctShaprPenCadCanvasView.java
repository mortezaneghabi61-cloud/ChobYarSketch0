package ir.chobyar.sketch;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.PointF;
import android.view.MotionEvent;
import android.widget.Toast;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Pen-first sketch interaction layer.
 *
 * The goal is Shapr-like interaction standards rather than visual copying:
 * Automatic Line/Arc is a pen gesture tool, connected strokes continue from
 * prior endpoints, and a small pen wiggle flips the inferred primitive.
 */
public class OcctShaprPenCadCanvasView extends OcctShaprCadCanvasView {
    private static final float PX_PER_MM = 3f;
    private static final float CONNECT_MM = 3f;

    private Field entitiesField, selectedField, selectedObjectsField;
    private Field viewScaleField, offsetXField, offsetYField;
    private Method saveUndoMethod;
    private Constructor<?> lineCtor, arcCtor;

    private boolean automaticMode;
    private boolean internalToolChange;
    private final List<PointF> autoStroke = new ArrayList<>();
    private PointF lastEnd;
    private PointF chainStart;
    private float lastSampleX, lastSampleY;

    public OcctShaprPenCadCanvasView(Context context) {
        super(context);
        initPenReflection();
    }

    private void initPenReflection() {
        try {
            entitiesField = field(CadCanvasView.class, "entities");
            selectedField = field(CadCanvasView.class, "selected");
            selectedObjectsField = field(SmartCadCanvasView.class, "selectedObjects");
            viewScaleField = field(CadCanvasView.class, "viewScale");
            offsetXField = field(CadCanvasView.class, "offsetX");
            offsetYField = field(CadCanvasView.class, "offsetY");
            saveUndoMethod = CadCanvasView.class.getDeclaredMethod("saveUndo");
            saveUndoMethod.setAccessible(true);

            Class<?> line = Class.forName("ir.chobyar.sketch.CadCanvasView$LineEntity");
            lineCtor = line.getDeclaredConstructor(float.class, float.class, float.class, float.class);
            lineCtor.setAccessible(true);

            Class<?> arc = Class.forName("ir.chobyar.sketch.CadCanvasView$ArcEntity");
            arcCtor = arc.getDeclaredConstructor(float.class, float.class, float.class, float.class, float.class);
            arcCtor.setAccessible(true);
        } catch (Exception ignored) {}
    }

    private static Field field(Class<?> owner, String name) throws Exception {
        Field f = owner.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }

    @Override
    public void showShaprSketchMenu() {
        String[] items = {
                "⌁ Automatic Line / Arc • Pen",
                "╱ Line", "⌒ Arc", "〰 Spline", "▭ Rectangle", "○ Circle", "⬭ Ellipse", "⬡ Polygon",
                "⧉ Offset Edge", "↗ Move / Rotate Sketch", "⠿ Pattern Sketch", "⌫ Trim", "⎘ Project Sketch",
                "⌖ Measure", "⌁ Constraints", "🔒 Lock / Unlock", "┄ Make Construction", "⌫ Delete"
        };
        new AlertDialog.Builder(getContext()).setTitle("Sketch").setItems(items, (d, w) -> {
            if (w == 0) {
                startAutomaticLineArc();
                return;
            }
            stopAutomatic();
            if (w == 1) activateBase(TOOL_LINE, "Line activated");
            else if (w == 2) activateBase(TOOL_ARC, "Arc activated");
            else if (w == 3) invokeParentVoid("startSpline");
            else if (w == 4) activateBase(TOOL_RECT, "Rectangle activated");
            else if (w == 5) activateBase(TOOL_CIRCLE, "Circle activated");
            else if (w == 6) invokeParentVoid("startEllipse");
            else if (w == 7) activateBase(TOOL_POLYGON, "Polygon activated");
            else if (w == 8) invokeParentVoid("offsetDialog");
            else if (w == 9) invokeParentVoid("transformDialog");
            else if (w == 10) invokeParentVoid("patternMenu");
            else if (w == 11) toast(trimSelectedLines());
            else if (w == 12) toast(invokeParentString("projectReference"));
            else if (w == 13) showSketchMeasureInspector();
            else if (w == 14) showSmartConstraintMenu();
            else if (w == 15) toast(toggleSelectedLock());
            else if (w == 16) toast(invokeParentString("toggleConstruction"));
            else { deleteSelected(); dispatchWorkspaceState(); }
        }).setNegativeButton("Close", null).show();
    }

    private void startAutomaticLineArc() {
        internalToolChange = true;
        super.setTool(TOOL_SELECT);
        internalToolChange = false;
        automaticMode = true;
        autoStroke.clear();
        lastEnd = null;
        chainStart = null;
        toast("Automatic Line/Arc text • text S Pen text; Wiggle text Line/Arc text change text");
    }

    private void stopAutomatic() {
        automaticMode = false;
        autoStroke.clear();
        lastEnd = null;
        chainStart = null;
    }

    private void activateBase(int tool, String message) {
        internalToolChange = true;
        super.setTool(tool);
        internalToolChange = false;
        toast(message);
    }

    @Override
    public void setTool(int newTool) {
        if (!internalToolChange) stopAutomatic();
        super.setTool(newTool);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!automaticMode) return super.onTouchEvent(event);
        if (event.getPointerCount() > 1) return super.onTouchEvent(event);

        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            autoStroke.clear();
            PointF p = world(event.getX(), event.getY());
            if (lastEnd != null && distance(p, lastEnd) <= CONNECT_MM) {
                p = new PointF(lastEnd.x, lastEnd.y);
            } else {
                lastEnd = null;
                chainStart = new PointF(p.x, p.y);
            }
            autoStroke.add(p);
            lastSampleX = event.getX();
            lastSampleY = event.getY();
            return true;
        }

        if (action == MotionEvent.ACTION_MOVE) {
            float dx = event.getX() - lastSampleX;
            float dy = event.getY() - lastSampleY;
            if (dx * dx + dy * dy >= 25f) {
                autoStroke.add(world(event.getX(), event.getY()));
                lastSampleX = event.getX();
                lastSampleY = event.getY();
            }
            return true;
        }

        if (action == MotionEvent.ACTION_UP) {
            autoStroke.add(world(event.getX(), event.getY()));
            if (autoStroke.size() < 2) return true;

            PointF first = autoStroke.get(0);
            PointF end = autoStroke.get(autoStroke.size() - 1);
            if (chainStart != null && distance(end, chainStart) <= CONNECT_MM && distance(first, end) > CONNECT_MM) {
                end = new PointF(chainStart.x, chainStart.y);
                autoStroke.set(autoStroke.size() - 1, end);
            }

            double chord = distance(first, end);
            if (chord < 0.6) {
                autoStroke.clear();
                return true;
            }

            boolean curve = inferArc(autoStroke);
            boolean wiggle = hasWiggle(autoStroke);
            if (wiggle) curve = !curve;

            Object created = curve ? addBestFitArc(autoStroke) : addLine(first, end);
            if (created != null) {
                lastEnd = new PointF(end.x, end.y);
                toast((wiggle ? "Wiggle → " : "") + (curve ? "Arc" : "Line"));
                dispatchWorkspaceState();
            }
            autoStroke.clear();
            return true;
        }

        if (action == MotionEvent.ACTION_CANCEL) {
            autoStroke.clear();
            return true;
        }
        return true;
    }

    private boolean inferArc(List<PointF> points) {
        PointF a = points.get(0), b = points.get(points.size() - 1);
        double chord = distance(a, b);
        if (chord < 1e-6) return false;
        double max = 0;
        for (int i = 1; i < points.size() - 1; i++) {
            max = Math.max(max, pointLineDistance(points.get(i), a, b));
        }
        return max > Math.max(0.7, chord * 0.035);
    }

    /** Detects the side-to-side pen wiggle described by the pen Automatic tool. */
    private boolean hasWiggle(List<PointF> points) {
        if (points.size() < 7) return false;
        PointF a = points.get(0), b = points.get(points.size() - 1);
        double chord = distance(a, b);
        if (chord < 4) return false;
        double dx = b.x - a.x, dy = b.y - a.y;
        double len = Math.hypot(dx, dy);
        int sign = 0, flips = 0;
        double threshold = Math.max(0.55, chord * 0.018);
        for (int i = 1; i < points.size() - 1; i++) {
            PointF p = points.get(i);
            double side = ((p.x - a.x) * dy - (p.y - a.y) * dx) / len;
            int s = side > threshold ? 1 : (side < -threshold ? -1 : 0);
            if (s != 0) {
                if (sign != 0 && s != sign) flips++;
                sign = s;
            }
        }
        return flips >= 2;
    }

    private Object addLine(PointF a, PointF b) {
        if (lineCtor == null) return null;
        try {
            saveUndo();
            Object e = lineCtor.newInstance(a.x, a.y, b.x, b.y);
            addEntity(e);
            return e;
        } catch (Exception ex) {
            toast("Line created text");
            return null;
        }
    }

    private Object addBestFitArc(List<PointF> points) {
        if (arcCtor == null || points.size() < 3) return null;
        PointF a = points.get(0), c = points.get(points.size() - 1);
        int best = 1;
        double max = -1;
        for (int i = 1; i < points.size() - 1; i++) {
            double d = pointLineDistance(points.get(i), a, c);
            if (d > max) { max = d; best = i; }
        }
        PointF b = points.get(best);
        CircleFit fit = circleThrough(a, b, c);
        if (fit == null || fit.r > 100000f || fit.r < 0.05f) return addLine(a, c);

        float start = angle(fit.cx, fit.cy, a);
        float mid = angle(fit.cx, fit.cy, b);
        float end = angle(fit.cx, fit.cy, c);
        float sweep = sweepThrough(start, mid, end);
        if (Math.abs(sweep) < 2f || Math.abs(sweep) > 358f) return addLine(a, c);

        try {
            saveUndo();
            Object e = arcCtor.newInstance(fit.cx, fit.cy, fit.r, start, sweep);
            addEntity(e);
            return e;
        } catch (Exception ex) {
            return addLine(a, c);
        }
    }

    private void addEntity(Object e) throws Exception {
        Method setLayer = findMethod(e.getClass(), "setLayer", String.class);
        if (setLayer != null) setLayer.invoke(e, getCurrentLayer());
        entities().add(e);
        selectedField.set(this, e);
        Object multi = selectedObjectsField.get(this);
        if (multi instanceof List) {
            @SuppressWarnings("unchecked") List<Object> list = (List<Object>) multi;
            list.clear(); list.add(e);
        }
        invalidate();
    }

    @SuppressWarnings("unchecked")
    private List<Object> entities() throws Exception { return (List<Object>) entitiesField.get(this); }
    private void saveUndo() throws Exception { if (saveUndoMethod != null) saveUndoMethod.invoke(this); }

    private PointF world(float sx, float sy) {
        try {
            float k = PX_PER_MM * viewScaleField.getFloat(this);
            return new PointF((sx - offsetXField.getFloat(this)) / k, (sy - offsetYField.getFloat(this)) / k);
        } catch (Exception e) {
            return new PointF(sx / PX_PER_MM, sy / PX_PER_MM);
        }
    }

    private void invokeParentVoid(String name) {
        try {
            Method m = OcctShaprCadCanvasView.class.getDeclaredMethod(name);
            m.setAccessible(true);
            m.invoke(this);
        } catch (Exception e) { toast("text Tools text is unavailable"); }
    }

    private String invokeParentString(String name) {
        try {
            Method m = OcctShaprCadCanvasView.class.getDeclaredMethod(name);
            m.setAccessible(true);
            Object r = m.invoke(this);
            return r == null ? "" : String.valueOf(r);
        } catch (Exception e) { return "text Tools text is unavailable"; }
    }

    private static Method findMethod(Class<?> c, String name, Class<?>... types) {
        for (Class<?> x = c; x != null; x = x.getSuperclass()) {
            try { Method m = x.getDeclaredMethod(name, types); m.setAccessible(true); return m; }
            catch (Exception ignored) {}
        }
        return null;
    }

    private static double pointLineDistance(PointF p, PointF a, PointF b) {
        double dx = b.x - a.x, dy = b.y - a.y;
        double l = Math.hypot(dx, dy);
        if (l < 1e-9) return distance(p, a);
        return Math.abs((p.x - a.x) * dy - (p.y - a.y) * dx) / l;
    }

    private static double distance(PointF a, PointF b) { return Math.hypot(b.x - a.x, b.y - a.y); }
    private static float angle(float cx, float cy, PointF p) { return (float)Math.toDegrees(Math.atan2(p.y - cy, p.x - cx)); }
    private static float norm360(float a) { a %= 360f; return a < 0 ? a + 360f : a; }

    private static float sweepThrough(float start, float mid, float end) {
        float m = norm360(mid - start);
        float e = norm360(end - start);
        return m <= e ? e : -(360f - e);
    }

    private static CircleFit circleThrough(PointF a, PointF b, PointF c) {
        double d = 2d * (a.x * (b.y - c.y) + b.x * (c.y - a.y) + c.x * (a.y - b.y));
        if (Math.abs(d) < 1e-8) return null;
        double a2 = a.x * a.x + a.y * a.y;
        double b2 = b.x * b.x + b.y * b.y;
        double c2 = c.x * c.x + c.y * c.y;
        double ux = (a2 * (b.y - c.y) + b2 * (c.y - a.y) + c2 * (a.y - b.y)) / d;
        double uy = (a2 * (c.x - b.x) + b2 * (a.x - c.x) + c2 * (b.x - a.x)) / d;
        float cx = (float)ux, cy = (float)uy;
        return new CircleFit(cx, cy, (float)Math.hypot(a.x - cx, a.y - cy));
    }

    private static final class CircleFit {
        final float cx, cy, r;
        CircleFit(float x, float y, float radius) { cx = x; cy = y; r = radius; }
    }

    private void toast(String text) { if (text != null && !text.isEmpty()) Toast.makeText(getContext(), text, Toast.LENGTH_SHORT).show(); }
}

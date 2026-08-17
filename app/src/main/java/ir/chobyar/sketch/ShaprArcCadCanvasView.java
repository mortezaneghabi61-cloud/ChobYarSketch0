package ir.chobyar.sketch;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PointF;
import android.view.MotionEvent;
import android.widget.Toast;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Arc workflow aligned to Shapr3D's public Android/touch+pen behavior.
 *
 * Shapr's current Arc tool on touch/pen is a Line/Arc line-type that is drawn
 * directly with the pen; it is not a fixed semicircle primitive. This layer
 * therefore replaces the legacy TOOL_ARC semicircle gesture with a circular
 * arc fitted from the pen stroke. The resulting document entity is a true
 * ArcEntity (center/radius/start/sweep), so radius dimensions, center snaps and
 * downstream arc constraints continue to operate on circular geometry.
 */
public class ShaprArcCadCanvasView extends ShaprSplineEditingCadCanvasView {

    private static final float PX_PER_MM = 3f;
    private static final float MIN_ARC_CHORD_MM = 0.8f;
    private static final float MIN_ARC_SAG_MM = 0.12f;
    private static final float SNAP_RADIUS_PX = 30f;

    private Field entitiesField, selectedField, selectedObjectsField;
    private Field viewScaleField, offsetXField, offsetYField;
    private Method saveUndoMethod;
    private Constructor<?> arcCtor;

    private boolean arcMode;
    private boolean internalToolChange;
    private final List<PointF> arcStroke = new ArrayList<>();
    private float lastSampleSx, lastSampleSy;

    private final Paint previewPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint guidePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public ShaprArcCadCanvasView(Context context) {
        super(context);
        initArcReflection();

        previewPaint.setStyle(Paint.Style.STROKE);
        previewPaint.setStrokeCap(Paint.Cap.ROUND);
        previewPaint.setStrokeJoin(Paint.Join.ROUND);
        previewPaint.setStrokeWidth(2.2f * density());
        previewPaint.setColor(Color.rgb(55, 125, 225));

        guidePaint.setStyle(Paint.Style.STROKE);
        guidePaint.setStrokeWidth(1.6f * density());
        guidePaint.setColor(Color.rgb(154, 92, 205));

        handlePaint.setStyle(Paint.Style.FILL);
        handlePaint.setColor(Color.rgb(242, 135, 36));

        labelPaint.setColor(Color.rgb(44, 76, 130));
        labelPaint.setTextSize(13f * getResources().getDisplayMetrics().scaledDensity);
        labelPaint.setTextAlign(Paint.Align.CENTER);
    }

    private void initArcReflection() {
        try {
            entitiesField = field(CadCanvasView.class, "entities");
            selectedField = field(CadCanvasView.class, "selected");
            selectedObjectsField = field(SmartCadCanvasView.class, "selectedObjects");
            viewScaleField = field(CadCanvasView.class, "viewScale");
            offsetXField = field(CadCanvasView.class, "offsetX");
            offsetYField = field(CadCanvasView.class, "offsetY");
            saveUndoMethod = CadCanvasView.class.getDeclaredMethod("saveUndo");
            saveUndoMethod.setAccessible(true);

            Class<?> arc = Class.forName("ir.chobyar.sketch.CadCanvasView$ArcEntity");
            arcCtor = arc.getDeclaredConstructor(float.class, float.class, float.class, float.class, float.class);
            arcCtor.setAccessible(true);
        } catch (Exception ignored) {
        }
    }

    private static Field field(Class<?> owner, String name) throws Exception {
        Field f = owner.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }

    /** Intercepts the legacy fixed-semicircle tool and activates Shapr pen Arc. */
    @Override
    public void setTool(int newTool) {
        if (newTool == TOOL_ARC && !internalToolChange) {
            startShaprArc();
            return;
        }
        arcMode = false;
        arcStroke.clear();
        super.setTool(newTool);
    }

    private void startShaprArc() {
        arcMode = false;
        arcStroke.clear();
        internalToolChange = true;
        super.setTool(TOOL_SELECT);
        internalToolChange = false;
        arcMode = true;
        toast("Arc • با قلم قوس را بکش؛ دیگر نیم‌دایره ثابت نیست");
        invalidate();
    }

    public boolean isShaprArcMode() {
        return arcMode;
    }

    public void finishShaprArc() {
        arcMode = false;
        arcStroke.clear();
        internalToolChange = true;
        super.setTool(TOOL_SELECT);
        internalToolChange = false;
        invalidate();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!arcMode) return super.onTouchEvent(event);
        if (event.getPointerCount() > 1) return super.onTouchEvent(event);

        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            arcStroke.clear();
            PointF p = snapGuidePoint(world(event.getX(), event.getY()));
            arcStroke.add(p);
            lastSampleSx = event.getX();
            lastSampleSy = event.getY();
            invalidate();
            return true;
        }

        if (action == MotionEvent.ACTION_MOVE) {
            float dx = event.getX() - lastSampleSx;
            float dy = event.getY() - lastSampleSy;
            if (dx * dx + dy * dy >= 16f) {
                arcStroke.add(world(event.getX(), event.getY()));
                lastSampleSx = event.getX();
                lastSampleSy = event.getY();
                invalidate();
            }
            return true;
        }

        if (action == MotionEvent.ACTION_UP) {
            PointF end = snapGuidePoint(world(event.getX(), event.getY()));
            arcStroke.add(end);

            // In Shapr touch/pen the Arc tool is completed by selecting outside
            // the sketch. A pen tap without a drawn chord serves that role here.
            if (strokeLength(arcStroke) < MIN_ARC_CHORD_MM) {
                finishShaprArc();
                toast("Arc تمام شد");
                return true;
            }

            Object made = addPenArc(arcStroke);
            arcStroke.clear();
            invalidate();
            if (made != null) {
                dispatchWorkspaceState();
            }
            return true;
        }

        if (action == MotionEvent.ACTION_CANCEL) {
            arcStroke.clear();
            invalidate();
            return true;
        }
        return true;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        if (arcMode) {
            if (arcStroke.size() > 1) {
                for (int i = 1; i < arcStroke.size(); i++) {
                    PointF a = screen(arcStroke.get(i - 1));
                    PointF b = screen(arcStroke.get(i));
                    canvas.drawLine(a.x, a.y, b.x, b.y, previewPaint);
                }
            }
            canvas.drawText("Arc • Pen", getWidth() * .5f, 34f * density(), labelPaint);
        }

        Object selected = singleSelected();
        if (selected != null && isArc(selected)) drawSelectedArcGuides(canvas, selected);
    }

    private Object addPenArc(List<PointF> stroke) {
        if (arcCtor == null || stroke.size() < 3) return null;
        PointF a = stroke.get(0);
        PointF c = stroke.get(stroke.size() - 1);
        double chord = distance(a, c);
        if (chord < MIN_ARC_CHORD_MM) return null;

        int bulgeIndex = -1;
        double sag = -1d;
        for (int i = 1; i < stroke.size() - 1; i++) {
            double d = pointLineDistance(stroke.get(i), a, c);
            if (d > sag) {
                sag = d;
                bulgeIndex = i;
            }
        }
        if (bulgeIndex < 0 || sag < MIN_ARC_SAG_MM) {
            toast("برای Arc کمی انحنا بده");
            return null;
        }

        PointF b = stroke.get(bulgeIndex);
        CircleFit fit = circleThrough(a, b, c);
        if (fit == null || fit.r < 0.05f || fit.r > 100000f) {
            toast("قوس معتبر ساخته نشد");
            return null;
        }

        float start = angle(fit.cx, fit.cy, a);
        float mid = angle(fit.cx, fit.cy, b);
        float end = angle(fit.cx, fit.cy, c);
        float sweep = sweepThrough(start, mid, end);
        if (Math.abs(sweep) < 1f || Math.abs(sweep) > 359f) {
            toast("زاویه Arc معتبر نیست");
            return null;
        }

        try {
            saveUndo();
            Object arc = arcCtor.newInstance(fit.cx, fit.cy, fit.r, start, sweep);
            Method setLayer = findMethod(arc.getClass(), "setLayer", String.class);
            if (setLayer != null) setLayer.invoke(arc, getCurrentLayer());
            entities().add(arc);
            selectOne(arc);
            toast("Arc • R " + dual(fit.r) + " • " + fmt(Math.abs(sweep)) + "°");
            return arc;
        } catch (Exception e) {
            toast("Arc ساخته نشد");
            return null;
        }
    }

    /**
     * Shapr guidepoints include endpoints, midpoints, arc centers and profile
     * centers. Reuse the document entities' own snapPoints so Arc endpoints can
     * connect to the same logical points rather than an arbitrary screen pixel.
     */
    private PointF snapGuidePoint(PointF raw) {
        if (!isSnapEnabled()) return raw;
        float limit = SNAP_RADIUS_PX / (PX_PER_MM * Math.max(.02f, viewScale()));
        PointF best = raw;
        float bd = limit;
        try {
            for (Object e : entities()) {
                Method m = findMethod(e.getClass(), "snapPoints");
                if (m == null) continue;
                Object v = m.invoke(e);
                if (!(v instanceof List)) continue;
                for (Object sp : (List<?>) v) {
                    Field xf = findField(sp.getClass(), "x");
                    Field yf = findField(sp.getClass(), "y");
                    if (xf == null || yf == null) continue;
                    PointF p = new PointF(xf.getFloat(sp), yf.getFloat(sp));
                    float d = (float) distance(raw, p);
                    if (d < bd) {
                        bd = d;
                        best = p;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return new PointF(best.x, best.y);
    }

    private void drawSelectedArcGuides(Canvas canvas, Object arc) {
        Float cx = number(arc, "x"), cy = number(arc, "y"), r = number(arc, "r");
        Float start = number(arc, "start"), sweep = number(arc, "sweep");
        if (cx == null || cy == null || r == null || start == null || sweep == null) return;

        PointF center = screen(cx, cy);
        PointF a = screen(arcPoint(cx, cy, r, start));
        PointF b = screen(arcPoint(cx, cy, r, start + sweep));
        canvas.drawLine(center.x, center.y, a.x, a.y, guidePaint);
        canvas.drawLine(center.x, center.y, b.x, b.y, guidePaint);
        dot(canvas, center);
        dot(canvas, a);
        dot(canvas, b);
        canvas.drawText("R " + dual(r), center.x, center.y - 18f * density(), labelPaint);
        canvas.drawText(fmt(Math.abs(sweep)) + "°", (a.x + b.x) * .5f, (a.y + b.y) * .5f - 10f * density(), labelPaint);
    }

    @Override
    public String selectedInfo() {
        Object s = singleSelected();
        if (s != null && isArc(s)) {
            Float r = number(s, "r"), sweep = number(s, "sweep");
            if (r != null && sweep != null) {
                float arcLen = (float) (Math.abs(Math.toRadians(sweep)) * r);
                return "Arc | Radius " + dual(r) + " | Angle " + fmt(Math.abs(sweep)) + "° | Length " + dual(arcLen);
            }
        }
        return super.selectedInfo();
    }

    private boolean isArc(Object o) {
        return o != null && "ArcEntity".equals(o.getClass().getSimpleName());
    }

    private Float number(Object o, String name) {
        try {
            Field f = findField(o.getClass(), name);
            return f == null ? null : f.getFloat(o);
        } catch (Exception e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private List<Object> entities() throws Exception {
        Object v = entitiesField.get(this);
        return v instanceof List ? (List<Object>) v : new ArrayList<>();
    }

    @SuppressWarnings("unchecked")
    private Object singleSelected() {
        try {
            Object multi = selectedObjectsField.get(this);
            if (multi instanceof List && ((List<?>) multi).size() == 1) return ((List<Object>) multi).get(0);
            return selectedField.get(this);
        } catch (Exception e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private void selectOne(Object e) throws Exception {
        selectedField.set(this, e);
        Object multi = selectedObjectsField.get(this);
        if (multi instanceof List) {
            List<Object> l = (List<Object>) multi;
            l.clear();
            l.add(e);
        }
    }

    private void saveUndo() throws Exception {
        if (saveUndoMethod != null) saveUndoMethod.invoke(this);
    }

    private float viewScale() {
        try { return viewScaleField.getFloat(this); }
        catch (Exception e) { return 1f; }
    }

    private PointF world(float sx, float sy) {
        try {
            float k = PX_PER_MM * Math.max(.0001f, viewScaleField.getFloat(this));
            return new PointF((sx - offsetXField.getFloat(this)) / k, (sy - offsetYField.getFloat(this)) / k);
        } catch (Exception e) {
            return new PointF(sx / PX_PER_MM, sy / PX_PER_MM);
        }
    }

    private PointF screen(PointF p) { return screen(p.x, p.y); }
    private PointF screen(float x, float y) {
        try {
            float k = PX_PER_MM * viewScaleField.getFloat(this);
            return new PointF(offsetXField.getFloat(this) + x * k, offsetYField.getFloat(this) + y * k);
        } catch (Exception e) {
            return new PointF(x * PX_PER_MM, y * PX_PER_MM);
        }
    }

    private void dot(Canvas c, PointF p) {
        c.drawCircle(p.x, p.y, 5.2f * density(), handlePaint);
    }

    private float density() { return getResources().getDisplayMetrics().density; }

    private static PointF arcPoint(float cx, float cy, float r, float deg) {
        double a = Math.toRadians(deg);
        return new PointF(cx + r * (float) Math.cos(a), cy + r * (float) Math.sin(a));
    }

    private static float strokeLength(List<PointF> p) {
        float s = 0f;
        for (int i = 1; i < p.size(); i++) s += (float) distance(p.get(i - 1), p.get(i));
        return s;
    }

    private static double pointLineDistance(PointF p, PointF a, PointF b) {
        double dx = b.x - a.x, dy = b.y - a.y;
        double len = Math.hypot(dx, dy);
        if (len < 1e-9) return distance(p, a);
        return Math.abs((p.x - a.x) * dy - (p.y - a.y) * dx) / len;
    }

    private static double distance(PointF a, PointF b) {
        return Math.hypot(b.x - a.x, b.y - a.y);
    }

    private static float angle(float cx, float cy, PointF p) {
        return (float) Math.toDegrees(Math.atan2(p.y - cy, p.x - cx));
    }

    private static float norm360(float a) {
        a %= 360f;
        return a < 0 ? a + 360f : a;
    }

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
        float cx = (float) ux, cy = (float) uy;
        return new CircleFit(cx, cy, (float) Math.hypot(a.x - cx, a.y - cy));
    }

    private static class CircleFit {
        final float cx, cy, r;
        CircleFit(float cx, float cy, float r) { this.cx = cx; this.cy = cy; this.r = r; }
    }

    private static Field findField(Class<?> c, String n) {
        for (Class<?> x = c; x != null; x = x.getSuperclass()) {
            try {
                Field f = x.getDeclaredField(n);
                f.setAccessible(true);
                return f;
            } catch (Exception ignored) {}
        }
        return null;
    }

    private static Method findMethod(Class<?> c, String n, Class<?>... t) {
        for (Class<?> x = c; x != null; x = x.getSuperclass()) {
            try {
                Method m = x.getDeclaredMethod(n, t);
                m.setAccessible(true);
                return m;
            } catch (Exception ignored) {}
        }
        return null;
    }

    private static String fmt(float v) {
        String s = String.format(Locale.US, "%.2f", v);
        while (s.contains(".") && (s.endsWith("0") || s.endsWith("."))) s = s.substring(0, s.length() - 1);
        return s;
    }

    private static String dual(float mm) {
        return fmt(mm) + " mm";
    }

    private void toast(String s) {
        Toast.makeText(getContext(), s, Toast.LENGTH_SHORT).show();
    }
}

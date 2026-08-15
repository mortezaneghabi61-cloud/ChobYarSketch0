package ir.chobyar.sketch;

import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PointF;
import android.view.MotionEvent;
import android.widget.Toast;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Snaps / Guides layer aligned to the current public Shapr3D sketch workflow.
 *
 * Implemented in this phase:
 * - Grid snapping
 * - Sketch Guidelines (purple alignment/extension/perpendicular/tangent guides)
 * - Sketch Guidepoints (endpoint, midpoint, center and intersections)
 * - visible guide points and Snapping Hints
 * - smarter line/curve intersection snapping
 * - optional Auto-constraining handoff: geometry is placed exactly on the
 *   inferred relation so the existing constraint layer can capture H/V,
 *   perpendicular, tangent and coincident relations.
 *
 * 3D Guidepoints and Distant Edges are intentionally not faked here; they need
 * projected OCCT body topology and are the next 3D snapping phase.
 */
public class ShaprSnappingCadCanvasView extends ShaprArcCadCanvasView {

    private static final float PX_PER_MM = 3f;
    private static final float GRID_MM = 10f;
    private static final float GUIDE_HIT_PX = 22f;
    private static final float POINT_HIT_PX = 30f;
    private static final float TANGENT_HIT_PX = 34f;
    private static final String PREFS = "shapr_snap_settings";

    private Field entitiesField, selectedField, viewScaleField, offsetXField, offsetYField;
    private Field automaticModeField, curveModeField;

    private boolean snapGrid = true;
    private boolean snapSketchGuidelines = true;
    private boolean snapSketchGuidepoints = true;
    private boolean showGuidepoints = true;
    private boolean showHints = true;
    private boolean autoConstraining = true;

    private PointF gestureStart;
    private SnapCandidate lastCandidate;
    private final List<GuideLine> activeGuides = new ArrayList<>();

    private final Paint guidePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint guidePointPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint guidePointFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hintBg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hintText = new Paint(Paint.ANTI_ALIAS_FLAG);

    public ShaprSnappingCadCanvasView(Context context) {
        super(context);
        initReflection();
        loadSettings();

        guidePaint.setStyle(Paint.Style.STROKE);
        guidePaint.setStrokeWidth(1.7f * density());
        guidePaint.setColor(Color.rgb(151, 83, 205));

        guidePointPaint.setStyle(Paint.Style.STROKE);
        guidePointPaint.setStrokeWidth(1.6f * density());
        guidePointPaint.setColor(Color.rgb(151, 83, 205));

        guidePointFill.setStyle(Paint.Style.FILL);
        guidePointFill.setColor(Color.argb(238, 255, 255, 255));

        hintBg.setStyle(Paint.Style.FILL);
        hintBg.setColor(Color.argb(242, 255, 255, 255));

        hintText.setColor(Color.rgb(92, 48, 150));
        hintText.setTextSize(12.5f * getResources().getDisplayMetrics().scaledDensity);
        hintText.setTextAlign(Paint.Align.CENTER);
    }

    private void initReflection() {
        try {
            entitiesField = field(CadCanvasView.class, "entities");
            selectedField = field(CadCanvasView.class, "selected");
            viewScaleField = field(CadCanvasView.class, "viewScale");
            offsetXField = field(CadCanvasView.class, "offsetX");
            offsetYField = field(CadCanvasView.class, "offsetY");
            automaticModeField = field(OcctShaprPenCadCanvasView.class, "automaticMode");
            curveModeField = field(ShaprParametricCurveCadCanvasView.class, "curveMode");
        } catch (Exception ignored) {
        }
    }

    private static Field field(Class<?> owner, String name) throws Exception {
        Field f = owner.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }

    private void loadSettings() {
        SharedPreferences p = getContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        snapGrid = p.getBoolean("grid", true);
        snapSketchGuidelines = p.getBoolean("guidelines", true);
        snapSketchGuidepoints = p.getBoolean("guidepoints", true);
        showGuidepoints = p.getBoolean("show_points", true);
        showHints = p.getBoolean("hints", true);
        autoConstraining = p.getBoolean("auto_constraints", true);
    }

    private void saveSettings() {
        getContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean("grid", snapGrid)
                .putBoolean("guidelines", snapSketchGuidelines)
                .putBoolean("guidepoints", snapSketchGuidepoints)
                .putBoolean("show_points", showGuidepoints)
                .putBoolean("hints", showHints)
                .putBoolean("auto_constraints", autoConstraining)
                .apply();
    }

    /** Shapr-style Snaps / Guides popover for the sketch-plane features implemented now. */
    public void showShaprSnappingOptions() {
        String[] items = {
                "Grid",
                "Sketch Guidelines",
                "Sketch Guidepoints",
                "Show Guide Points",
                "Snapping Hints"
        };
        boolean[] checked = {snapGrid, snapSketchGuidelines, snapSketchGuidepoints, showGuidepoints, showHints};
        new AlertDialog.Builder(getContext())
                .setTitle("Snaps / Guides")
                .setMultiChoiceItems(items, checked, (d, which, on) -> {
                    if (which == 0) snapGrid = on;
                    else if (which == 1) snapSketchGuidelines = on;
                    else if (which == 2) snapSketchGuidepoints = on;
                    else if (which == 3) showGuidepoints = on;
                    else showHints = on;
                    saveSettings();
                    invalidate();
                })
                .setNeutralButton("3D Guidepoints", (d, w) -> Toast.makeText(getContext(),
                        "3D Guidepoints و Distant Edges در مرحله بعد با Projection از OCCT اضافه می‌شوند", Toast.LENGTH_LONG).show())
                .setPositiveButton("بستن", null)
                .show();
    }

    /** Current Shapr Constraint Settings exposes Auto-constraining as a switch. */
    public void showShaprConstraintSettings() {
        String[] items = {"Auto-constraining"};
        boolean[] checked = {autoConstraining};
        new AlertDialog.Builder(getContext())
                .setTitle("Constraint Settings")
                .setMultiChoiceItems(items, checked, (d, which, on) -> {
                    autoConstraining = on;
                    saveSettings();
                })
                .setNeutralButton("Constraints", (d, w) -> showSmartConstraintMenu())
                .setPositiveButton("بستن", null)
                .show();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getPointerCount() > 1) {
            clearTransientSnap();
            return super.onTouchEvent(event);
        }

        boolean sketchGesture = isSketchCreationGesture();
        int action = event.getActionMasked();
        MotionEvent forwarded = event;

        if (sketchGesture && isSnapEnabled()) {
            PointF raw = world(event.getX(), event.getY());
            SnapCandidate c = findBestSnap(raw, action != MotionEvent.ACTION_DOWN);
            lastCandidate = c;
            if (c != null) {
                PointF s = screen(c.p);
                forwarded = MotionEvent.obtain(event);
                forwarded.setLocation(s.x, s.y);
            }

            if (action == MotionEvent.ACTION_DOWN) {
                gestureStart = c == null ? raw : new PointF(c.p.x, c.p.y);
            }
            invalidate();
        }

        boolean handled = super.onTouchEvent(forwarded);
        if (forwarded != event) forwarded.recycle();

        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            if (showHints && lastCandidate != null) {
                final SnapCandidate keep = lastCandidate;
                postDelayed(() -> {
                    if (lastCandidate == keep) {
                        lastCandidate = null;
                        activeGuides.clear();
                        invalidate();
                    }
                }, 650L);
            } else {
                lastCandidate = null;
                activeGuides.clear();
            }
            gestureStart = null;
        }
        return handled;
    }

    private boolean isSketchCreationGesture() {
        if (getTool() != TOOL_SELECT) return true;
        if (isShaprArcMode()) return true;
        try {
            if (automaticModeField != null && automaticModeField.getBoolean(this)) return true;
        } catch (Exception ignored) {
        }
        try {
            if (curveModeField != null && curveModeField.getInt(this) != 0) return true;
        } catch (Exception ignored) {
        }
        return false;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (snapSketchGuidepoints && showGuidepoints) drawAllGuidepoints(canvas);
        if (snapSketchGuidelines) drawActiveGuidelines(canvas);
        if (showHints && lastCandidate != null) drawHint(canvas, lastCandidate);
    }

    private SnapCandidate findBestSnap(PointF raw, boolean allowDirectional) {
        activeGuides.clear();
        SnapCandidate best = null;

        if (snapSketchGuidepoints) {
            best = better(best, nearestNotablePoint(raw));
            best = better(best, nearestIntersection(raw));
        }

        if (snapSketchGuidelines) {
            if (allowDirectional && gestureStart != null) {
                best = better(best, horizontalVerticalGuide(raw));
                best = better(best, perpendicularGuide(raw));
                best = better(best, tangentGuide(raw));
            }
            best = better(best, alignmentGuide(raw));
            best = better(best, lineExtensionGuide(raw));
        }

        if (snapGrid) {
            PointF g = new PointF(Math.round(raw.x / GRID_MM) * GRID_MM, Math.round(raw.y / GRID_MM) * GRID_MM);
            float px = screenDistance(raw, g);
            if (px <= 15f * density()) best = better(best, new SnapCandidate(g, "Grid", 1, px));
        }
        return best;
    }

    private SnapCandidate nearestNotablePoint(PointF raw) {
        SnapCandidate best = null;
        try {
            for (Object e : entities()) {
                Method m = findMethod(e.getClass(), "snapPoints");
                if (m == null) continue;
                Object v = m.invoke(e);
                if (!(v instanceof List)) continue;
                for (Object sp : (List<?>) v) {
                    Float x = number(sp, "x"), y = number(sp, "y");
                    if (x == null || y == null) continue;
                    PointF p = new PointF(x, y);
                    float d = screenDistance(raw, p);
                    if (d > POINT_HIT_PX * density()) continue;
                    String rawLabel = text(sp, "label");
                    String label = normalizePointLabel(rawLabel);
                    int priority = label.startsWith("Intersection") ? 8 : label.startsWith("Endpoint") ? 7 : 6;
                    best = better(best, new SnapCandidate(p, label, priority, d));
                }
            }
        } catch (Exception ignored) {
        }
        return best;
    }

    /** Extra intersection points: line-line, line-circle/arc and circle-circle/arc. */
    private SnapCandidate nearestIntersection(PointF raw) {
        List<Object> es;
        try { es = entities(); } catch (Exception e) { return null; }
        SnapCandidate best = null;
        for (int i = 0; i < es.size(); i++) {
            for (int j = i + 1; j < es.size(); j++) {
                List<PointF> xs = intersections(es.get(i), es.get(j));
                for (PointF p : xs) {
                    float d = screenDistance(raw, p);
                    if (d <= POINT_HIT_PX * density()) {
                        best = better(best, new SnapCandidate(p, "Intersection • تقاطع", 9, d));
                    }
                }
            }
        }
        return best;
    }

    private SnapCandidate horizontalVerticalGuide(PointF raw) {
        if (gestureStart == null) return null;
        float dx = Math.abs(screen(raw.x, raw.y).x - screen(gestureStart.x, raw.y).x);
        float dy = Math.abs(screen(raw.x, raw.y).y - screen(raw.x, gestureStart.y).y);
        float limit = GUIDE_HIT_PX * density();
        SnapCandidate best = null;
        if (dx <= limit) {
            PointF p = new PointF(gestureStart.x, raw.y);
            activeGuides.add(GuideLine.vertical(gestureStart.x));
            best = new SnapCandidate(p, "Vertical • عمودی", 5, dx);
        }
        if (dy <= limit) {
            PointF p = new PointF(raw.x, gestureStart.y);
            activeGuides.add(GuideLine.horizontal(gestureStart.y));
            SnapCandidate h = new SnapCandidate(p, "Horizontal • افقی", 5, dy);
            best = better(best, h);
        }
        return best;
    }

    private SnapCandidate alignmentGuide(PointF raw) {
        SnapCandidate best = null;
        float limit = GUIDE_HIT_PX * density();
        try {
            for (Object e : entities()) {
                Method m = findMethod(e.getClass(), "snapPoints");
                if (m == null) continue;
                Object v = m.invoke(e);
                if (!(v instanceof List)) continue;
                for (Object sp : (List<?>) v) {
                    Float x = number(sp, "x"), y = number(sp, "y");
                    if (x == null || y == null) continue;
                    PointF screenRaw = screen(raw);
                    PointF screenP = screen(x, y);
                    float dx = Math.abs(screenRaw.x - screenP.x);
                    float dy = Math.abs(screenRaw.y - screenP.y);
                    if (dx <= limit) {
                        activeGuides.add(GuideLine.vertical(x));
                        best = better(best, new SnapCandidate(new PointF(x, raw.y), "Sketch Guideline", 3, dx));
                    }
                    if (dy <= limit) {
                        activeGuides.add(GuideLine.horizontal(y));
                        best = better(best, new SnapCandidate(new PointF(raw.x, y), "Sketch Guideline", 3, dy));
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return best;
    }

    private SnapCandidate lineExtensionGuide(PointF raw) {
        SnapCandidate best = null;
        float limit = GUIDE_HIT_PX * density();
        try {
            for (Object e : entities()) {
                for (Segment s : segmentsOf(e)) {
                    PointF q = projectionInfinite(raw, s.a, s.b);
                    float d = screenDistance(raw, q);
                    if (d <= limit) {
                        activeGuides.add(GuideLine.infinite(s.a, s.b));
                        best = better(best, new SnapCandidate(q, "Sketch Guideline", 4, d));
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return best;
    }

    private SnapCandidate perpendicularGuide(PointF raw) {
        if (gestureStart == null) return null;
        SnapCandidate best = null;
        float limit = GUIDE_HIT_PX * density();
        try {
            for (Object e : entities()) {
                for (Segment s : segmentsOf(e)) {
                    if (pointSegmentDistance(gestureStart, s.a, s.b) > 1.2f) continue;
                    float vx = s.b.x - s.a.x, vy = s.b.y - s.a.y;
                    float len = (float)Math.hypot(vx, vy);
                    if (len < 1e-5f) continue;
                    float nx = -vy / len, ny = vx / len;
                    PointF q = projectToRay(raw, gestureStart, nx, ny);
                    float d = screenDistance(raw, q);
                    if (d <= limit) {
                        activeGuides.add(GuideLine.infinite(gestureStart,
                                new PointF(gestureStart.x + nx, gestureStart.y + ny)));
                        best = better(best, new SnapCandidate(q, "Perpendicular • عمود", 6, d));
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return best;
    }

    private SnapCandidate tangentGuide(PointF raw) {
        if (gestureStart == null) return null;
        SnapCandidate best = null;
        try {
            for (Object e : entities()) {
                CircleGeom c = circleOf(e);
                if (c == null) continue;
                float dx = gestureStart.x - c.cx, dy = gestureStart.y - c.cy;
                double d2 = dx * dx + dy * dy;
                double r2 = c.r * c.r;
                if (d2 <= r2 + 1e-6) continue;
                double l = Math.sqrt(d2 - r2);
                double base = Math.atan2(dy, dx);
                double alpha = Math.acos(c.r / Math.sqrt(d2));
                for (int sign : new int[]{-1, 1}) {
                    double a = base + sign * alpha;
                    PointF t = new PointF(c.cx + c.r * (float)Math.cos(a), c.cy + c.r * (float)Math.sin(a));
                    if (c.arc && !angleOnArc(c, t)) continue;
                    float dd = screenDistance(raw, t);
                    if (dd <= TANGENT_HIT_PX * density()) {
                        activeGuides.add(new GuideLine(gestureStart, t, false, false));
                        best = better(best, new SnapCandidate(t, "Tangent • مماس", 8, dd));
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return best;
    }

    private void drawAllGuidepoints(Canvas canvas) {
        try {
            for (Object e : entities()) {
                Method m = findMethod(e.getClass(), "snapPoints");
                if (m == null) continue;
                Object v = m.invoke(e);
                if (!(v instanceof List)) continue;
                for (Object sp : (List<?>) v) {
                    Float x = number(sp, "x"), y = number(sp, "y");
                    if (x == null || y == null) continue;
                    PointF p = screen(x, y);
                    float r = 3.6f * density();
                    canvas.drawCircle(p.x, p.y, r, guidePointFill);
                    canvas.drawCircle(p.x, p.y, r, guidePointPaint);
                }
            }
        } catch (Exception ignored) {
        }
    }

    private void drawActiveGuidelines(Canvas canvas) {
        for (GuideLine g : activeGuides) {
            if (g.vertical) {
                PointF p = screen(g.a.x, 0f);
                canvas.drawLine(p.x, 0, p.x, getHeight(), guidePaint);
            } else if (g.horizontal) {
                PointF p = screen(0f, g.a.y);
                canvas.drawLine(0, p.y, getWidth(), p.y, guidePaint);
            } else if (g.infinite) {
                PointF a = g.a, b = g.b;
                float dx = b.x - a.x, dy = b.y - a.y;
                float len = (float)Math.hypot(dx, dy);
                if (len < 1e-5f) continue;
                dx /= len; dy /= len;
                PointF s1 = screen(a.x - dx * 10000f, a.y - dy * 10000f);
                PointF s2 = screen(a.x + dx * 10000f, a.y + dy * 10000f);
                canvas.drawLine(s1.x, s1.y, s2.x, s2.y, guidePaint);
            } else {
                PointF a = screen(g.a), b = screen(g.b);
                canvas.drawLine(a.x, a.y, b.x, b.y, guidePaint);
            }
        }
    }

    private void drawHint(Canvas canvas, SnapCandidate c) {
        PointF p = screen(c.p);
        String label = c.label;
        float pad = 7f * density();
        float w = hintText.measureText(label) + pad * 2f;
        float h = 26f * density();
        float cx = clamp(p.x + w * .52f + 12f * density(), w * .5f + 4f, getWidth() - w * .5f - 4f);
        float cy = clamp(p.y - 24f * density(), h * .5f + 4f, getHeight() - h * .5f - 4f);
        canvas.drawRoundRect(cx - w/2f, cy - h/2f, cx + w/2f, cy + h/2f, 8f*density(), 8f*density(), hintBg);
        canvas.drawText(label, cx, cy - (hintText.ascent() + hintText.descent())/2f, hintText);
        canvas.drawCircle(p.x, p.y, 5.2f * density(), guidePointPaint);
    }

    private List<PointF> intersections(Object a, Object b) {
        List<PointF> out = new ArrayList<>();
        List<Segment> sa = segmentsOf(a), sb = segmentsOf(b);
        CircleGeom ca = circleOf(a), cb = circleOf(b);
        for (Segment x : sa) for (Segment y : sb) {
            PointF p = segmentIntersection(x.a, x.b, y.a, y.b);
            if (p != null) addUnique(out, p);
        }
        if (ca != null) for (Segment s : sb) for (PointF p : lineCircleIntersections(s, ca)) addUnique(out, p);
        if (cb != null) for (Segment s : sa) for (PointF p : lineCircleIntersections(s, cb)) addUnique(out, p);
        if (ca != null && cb != null) for (PointF p : circleCircleIntersections(ca, cb)) addUnique(out, p);
        return out;
    }

    private List<Segment> segmentsOf(Object e) {
        List<Segment> out = new ArrayList<>();
        if (e == null) return out;
        String n = e.getClass().getSimpleName();
        if ("LineEntity".equals(n)) {
            Float x1=number(e,"x1"),y1=number(e,"y1"),x2=number(e,"x2"),y2=number(e,"y2");
            if (x1!=null&&y1!=null&&x2!=null&&y2!=null) out.add(new Segment(new PointF(x1,y1),new PointF(x2,y2)));
            return out;
        }
        try {
            Field p = findField(e.getClass(), "points");
            if (p != null && p.get(e) instanceof List) {
                @SuppressWarnings("unchecked") List<PointF> pts=(List<PointF>)p.get(e);
                for(int i=1;i<pts.size();i++) out.add(new Segment(pts.get(i-1),pts.get(i)));
                Field cl=findField(e.getClass(),"closed");
                if(cl!=null&&cl.getBoolean(e)&&pts.size()>2) out.add(new Segment(pts.get(pts.size()-1),pts.get(0)));
            }
        } catch (Exception ignored) {}
        return out;
    }

    private CircleGeom circleOf(Object e) {
        if (e == null) return null;
        String n=e.getClass().getSimpleName();
        if (!"CircleEntity".equals(n) && !"ArcEntity".equals(n)) return null;
        Float x=number(e,"x"),y=number(e,"y"),r=number(e,"r");
        if(x==null||y==null||r==null) return null;
        if("ArcEntity".equals(n)) {
            Float s=number(e,"start"),w=number(e,"sweep");
            return new CircleGeom(x,y,r,true,s==null?0:s,w==null?360:w);
        }
        return new CircleGeom(x,y,r,false,0,360);
    }

    private List<PointF> lineCircleIntersections(Segment s, CircleGeom c) {
        List<PointF> out=new ArrayList<>();
        float dx=s.b.x-s.a.x,dy=s.b.y-s.a.y,fx=s.a.x-c.cx,fy=s.a.y-c.cy;
        double A=dx*dx+dy*dy,B=2*(fx*dx+fy*dy),C=fx*fx+fy*fy-c.r*c.r;
        double disc=B*B-4*A*C;if(A<1e-10||disc<-1e-8)return out;disc=Math.max(0,disc);double root=Math.sqrt(disc);
        for(double t:new double[]{(-B-root)/(2*A),(-B+root)/(2*A)}) if(t>=-1e-5&&t<=1.00001){PointF p=new PointF(s.a.x+(float)t*dx,s.a.y+(float)t*dy);if(!c.arc||angleOnArc(c,p))addUnique(out,p);}return out;
    }

    private List<PointF> circleCircleIntersections(CircleGeom a,CircleGeom b){
        List<PointF> out=new ArrayList<>();double dx=b.cx-a.cx,dy=b.cy-a.cy,d=Math.hypot(dx,dy);if(d<1e-8||d>a.r+b.r+1e-5||d<Math.abs(a.r-b.r)-1e-5)return out;double x=(a.r*a.r-b.r*b.r+d*d)/(2*d);double h2=a.r*a.r-x*x;if(h2<-1e-6)return out;double h=Math.sqrt(Math.max(0,h2));double ux=dx/d,uy=dy/d;double px=a.cx+x*ux,py=a.cy+x*uy;for(int sign:new int[]{-1,1}){PointF p=new PointF((float)(px-sign*h*uy),(float)(py+sign*h*ux));if((!a.arc||angleOnArc(a,p))&&(!b.arc||angleOnArc(b,p)))addUnique(out,p);}return out;
    }

    private static PointF segmentIntersection(PointF a,PointF b,PointF c,PointF d){float rX=b.x-a.x,rY=b.y-a.y,sX=d.x-c.x,sY=d.y-c.y;float den=rX*sY-rY*sX;if(Math.abs(den)<1e-7f)return null;float qx=c.x-a.x,qy=c.y-a.y;float t=(qx*sY-qy*sX)/den,u=(qx*rY-qy*rX)/den;if(t<-1e-5||t>1.00001||u<-1e-5||u>1.00001)return null;return new PointF(a.x+t*rX,a.y+t*rY);}

    private static boolean angleOnArc(CircleGeom c, PointF p){float a=(float)Math.toDegrees(Math.atan2(p.y-c.cy,p.x-c.cx));float rel=norm360(a-c.start);if(c.sweep>=0)return rel<=c.sweep+0.2f;return norm360(c.start-a)<=-c.sweep+0.2f;}
    private static float norm360(float a){a%=360f;return a<0?a+360f:a;}

    private static PointF projectionInfinite(PointF p,PointF a,PointF b){float dx=b.x-a.x,dy=b.y-a.y,l2=dx*dx+dy*dy;if(l2<1e-10f)return new PointF(a.x,a.y);float t=((p.x-a.x)*dx+(p.y-a.y)*dy)/l2;return new PointF(a.x+t*dx,a.y+t*dy);}
    private static PointF projectToRay(PointF p,PointF o,float dx,float dy){float t=(p.x-o.x)*dx+(p.y-o.y)*dy;return new PointF(o.x+t*dx,o.y+t*dy);}
    private static float pointSegmentDistance(PointF p,PointF a,PointF b){PointF q=projectionInfinite(p,a,b);float dx=b.x-a.x,dy=b.y-a.y,l2=dx*dx+dy*dy;if(l2<1e-10f)return distance(p,a);float t=((q.x-a.x)*dx+(q.y-a.y)*dy)/l2;if(t<0)return distance(p,a);if(t>1)return distance(p,b);return distance(p,q);}

    private SnapCandidate better(SnapCandidate a,SnapCandidate b){if(b==null)return a;if(a==null)return b;float as=a.distancePx-a.priority*2.4f,bs=b.distancePx-b.priority*2.4f;return bs<as?b:a;}

    private void addUnique(List<PointF> list,PointF p){for(PointF q:list)if(distance(p,q)<0.03f)return;list.add(p);}

    private String normalizePointLabel(String s){if(s==null)s="";String l=s.toLowerCase(Locale.ROOT);if(l.contains("تقاطع")||l.contains("intersection"))return"Intersection • تقاطع";if(l.contains("میانه")||l.contains("وسط")||l.contains("mid"))return"Midpoint • میانه";if(l.contains("مرکز")||l.contains("center")||l.contains("centre"))return"Center • مرکز";if(l.contains("ابتدا")||l.contains("انتها")||l.contains("endpoint")||l.contains("start")||l.contains("end"))return"Endpoint • انتها";if(l.contains("ربع")||l.contains("quadrant"))return"Quadrant";return s.isEmpty()?"Guidepoint":s;}

    private void clearTransientSnap(){gestureStart=null;lastCandidate=null;activeGuides.clear();invalidate();}

    @SuppressWarnings("unchecked") private List<Object> entities() throws Exception {Object v=entitiesField.get(this);return v instanceof List?(List<Object>)v:new ArrayList<>();}
    private Float number(Object o,String n){try{Field f=findField(o.getClass(),n);return f==null?null:f.getFloat(o);}catch(Exception e){return null;}}
    private String text(Object o,String n){try{Field f=findField(o.getClass(),n);Object v=f==null?null:f.get(o);return v==null?"":String.valueOf(v);}catch(Exception e){return"";}}
    private static Field findField(Class<?> c,String n){for(Class<?> x=c;x!=null;x=x.getSuperclass())try{Field f=x.getDeclaredField(n);f.setAccessible(true);return f;}catch(Exception ignored){}return null;}
    private static Method findMethod(Class<?> c,String n,Class<?>...types){for(Class<?> x=c;x!=null;x=x.getSuperclass())try{Method m=x.getDeclaredMethod(n,types);m.setAccessible(true);return m;}catch(Exception ignored){}return null;}

    private float viewScale(){try{return viewScaleField.getFloat(this);}catch(Exception e){return 1f;}}
    private PointF world(float sx,float sy){try{float k=PX_PER_MM*Math.max(.0001f,viewScaleField.getFloat(this));return new PointF((sx-offsetXField.getFloat(this))/k,(sy-offsetYField.getFloat(this))/k);}catch(Exception e){return new PointF(sx/PX_PER_MM,sy/PX_PER_MM);}}
    private PointF screen(PointF p){return screen(p.x,p.y);}private PointF screen(float x,float y){try{float k=PX_PER_MM*viewScaleField.getFloat(this);return new PointF(offsetXField.getFloat(this)+x*k,offsetYField.getFloat(this)+y*k);}catch(Exception e){return new PointF(x*PX_PER_MM,y*PX_PER_MM);}}
    private float screenDistance(PointF a,PointF b){PointF x=screen(a),y=screen(b);return(float)Math.hypot(x.x-y.x,x.y-y.y);}
    private float density(){return getResources().getDisplayMetrics().density;}
    private static float distance(PointF a,PointF b){return(float)Math.hypot(b.x-a.x,b.y-a.y);}
    private static float clamp(float v,float a,float b){return Math.max(a,Math.min(b,v));}

    private static final class SnapCandidate{final PointF p;final String label;final int priority;final float distancePx;SnapCandidate(PointF p,String label,int priority,float distancePx){this.p=p;this.label=label;this.priority=priority;this.distancePx=distancePx;}}
    private static final class Segment{final PointF a,b;Segment(PointF a,PointF b){this.a=new PointF(a.x,a.y);this.b=new PointF(b.x,b.y);}}
    private static final class CircleGeom{final float cx,cy,r;final boolean arc;final float start,sweep;CircleGeom(float cx,float cy,float r,boolean arc,float start,float sweep){this.cx=cx;this.cy=cy;this.r=r;this.arc=arc;this.start=start;this.sweep=sweep;}}
    private static final class GuideLine{final PointF a,b;final boolean infinite,vertical,horizontal;GuideLine(PointF a,PointF b,boolean infinite,boolean vertical){this(a,b,infinite,vertical,false);}GuideLine(PointF a,PointF b,boolean infinite,boolean vertical,boolean horizontal){this.a=new PointF(a.x,a.y);this.b=new PointF(b.x,b.y);this.infinite=infinite;this.vertical=vertical;this.horizontal=horizontal;}static GuideLine vertical(float x){return new GuideLine(new PointF(x,0),new PointF(x,1),false,true,false);}static GuideLine horizontal(float y){return new GuideLine(new PointF(0,y),new PointF(1,y),false,false,true);}static GuideLine infinite(PointF a,PointF b){return new GuideLine(a,b,true,false,false);}}
}

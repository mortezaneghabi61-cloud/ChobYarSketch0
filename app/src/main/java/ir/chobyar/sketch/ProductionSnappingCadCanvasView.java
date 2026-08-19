package ir.chobyar.sketch;

import android.content.Context;
import android.graphics.PointF;
import android.view.MotionEvent;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Production interaction pass for the sketch snap engine.
 * Keeps the proven sketch/constraint code underneath, while fixing three UX
 * problems that are very noticeable with a pen: fixed grid spacing, snap
 * jumping, and O(n^2) intersection work on every pointer move in dense sketches.
 */
public class ProductionSnappingCadCanvasView extends ShaprSnappingCadCanvasView {
    private static final float PX_PER_MM = 3f;
    private static final int FULL_INTERSECTION_LIMIT = 48;
    private static final int MAX_NEAR_SEGMENTS = 36;
    private static final float NEAR_INTERSECTION_PX = 64f;
    private static final float RELEASE_DP = 42f;

    private Field snapGridField, snapGuidelinesField, snapGuidepointsField, showHintsField;
    private Field gestureStartField, lastPointerWorldField, lastCandidateField, activeGuidesField;
    private Field entitiesField, viewScaleField, offsetXField, offsetYField;
    private Method baseFindBestSnapMethod, baseSketchGestureMethod;
    private Constructor<?> snapCandidateCtor;

    private Object lockedCandidate;
    private float pointerPrecision = 1f;
    private PointF cachedRaw;
    private boolean cachedDirectional;
    private Object cachedCandidate;
    private long cachedAtNs;

    public ProductionSnappingCadCanvasView(Context context) {
        super(context);
        initProductionReflection();
    }

    private void initProductionReflection() {
        try {
            snapGridField = field(ShaprSnappingCadCanvasView.class, "snapGrid");
            snapGuidelinesField = field(ShaprSnappingCadCanvasView.class, "snapSketchGuidelines");
            snapGuidepointsField = field(ShaprSnappingCadCanvasView.class, "snapSketchGuidepoints");
            showHintsField = field(ShaprSnappingCadCanvasView.class, "showHints");
            gestureStartField = field(ShaprSnappingCadCanvasView.class, "gestureStart");
            lastPointerWorldField = field(ShaprSnappingCadCanvasView.class, "lastPointerWorld");
            lastCandidateField = field(ShaprSnappingCadCanvasView.class, "lastCandidate");
            activeGuidesField = field(ShaprSnappingCadCanvasView.class, "activeGuides");
            entitiesField = field(CadCanvasView.class, "entities");
            viewScaleField = field(CadCanvasView.class, "viewScale");
            offsetXField = field(CadCanvasView.class, "offsetX");
            offsetYField = field(CadCanvasView.class, "offsetY");
            baseFindBestSnapMethod = ShaprSnappingCadCanvasView.class.getDeclaredMethod("findBestSnap", PointF.class, boolean.class);
            baseFindBestSnapMethod.setAccessible(true);
            baseSketchGestureMethod = ShaprSnappingCadCanvasView.class.getDeclaredMethod("isSketchCreationGesture");
            baseSketchGestureMethod.setAccessible(true);
            Class<?> candidate = Class.forName("ir.chobyar.sketch.ShaprSnappingCadCanvasView$SnapCandidate");
            snapCandidateCtor = candidate.getDeclaredConstructor(PointF.class, String.class, int.class, float.class);
            snapCandidateCtor.setAccessible(true);
        } catch (Exception ignored) {
        }
    }

    private static Field field(Class<?> owner, String name) throws Exception {
        Field f = owner.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getPointerCount() > 1 || !isProductionSketchGesture() || !isSnapEnabled()) {
            if (event.getPointerCount() > 1) lockedCandidate = null;
            return super.onTouchEvent(event);
        }

        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            pointerPrecision = precisionFor(event);
            lockedCandidate = null;
            clearCache();
        }

        PointF raw = world(event.getX(), event.getY());
        Object fresh = findBestSnap(raw, action != MotionEvent.ACTION_DOWN);
        Object chosen = stabilize(raw, fresh);
        PointF target = candidatePoint(chosen);
        if (target == null) target = raw;

        List<Object> guides = copyActiveGuides();
        boolean grid = bool(snapGridField, true);
        boolean guide = bool(snapGuidelinesField, true);
        boolean points = bool(snapGuidepointsField, true);

        MotionEvent forwarded = MotionEvent.obtain(event);
        PointF screen = screen(target);
        forwarded.setLocation(screen.x, screen.y);

        setBool(snapGridField, false);
        setBool(snapGuidelinesField, false);
        setBool(snapGuidepointsField, false);
        boolean handled = super.onTouchEvent(forwarded);
        forwarded.recycle();
        setBool(snapGridField, grid);
        setBool(snapGuidelinesField, guide);
        setBool(snapGuidepointsField, points);

        restoreTransientState(raw, chosen, guides, action);

        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            lockedCandidate = null;
            pointerPrecision = 1f;
            clearCache();
        }
        return handled;
    }

    private Object findBestSnap(PointF raw, boolean directional) {
        long now = System.nanoTime();
        if (cachedRaw != null && cachedDirectional == directional
                && Math.abs(cachedRaw.x - raw.x) < 0.0001f
                && Math.abs(cachedRaw.y - raw.y) < 0.0001f
                && now - cachedAtNs < 12_000_000L) return cachedCandidate;

        boolean grid = bool(snapGridField, true);
        boolean guidepoints = bool(snapGuidepointsField, true);
        int count = entityCount();
        Object best = null;

        try {
            setBool(snapGridField, false);
            if (count > FULL_INTERSECTION_LIMIT) setBool(snapGuidepointsField, false);
            if (baseFindBestSnapMethod != null) best = baseFindBestSnapMethod.invoke(this, raw, directional);
        } catch (Exception ignored) {
        } finally {
            setBool(snapGridField, grid);
            setBool(snapGuidepointsField, guidepoints);
        }

        if (guidepoints && count > FULL_INTERSECTION_LIMIT) {
            best = better(best, fastGuidepoint(raw));
            best = better(best, fastNearbyIntersection(raw));
        }
        if (grid) best = better(best, adaptiveGrid(raw));

        cachedRaw = new PointF(raw.x, raw.y);
        cachedDirectional = directional;
        cachedCandidate = best;
        cachedAtNs = now;
        return best;
    }

    private Object adaptiveGrid(PointF raw) {
        float step = adaptiveGridMm();
        PointF p = new PointF(Math.round(raw.x / step) * step, Math.round(raw.y / step) * step);
        float d = screenDistance(raw, p);
        float spacing = step * PX_PER_MM * Math.max(.0001f, viewScale());
        float limit = Math.min(hitPx(15f), spacing * .42f);
        if (d > limit) return null;
        return newCandidate(p, "Grid • " + fmtGrid(step) + " mm", 1, d);
    }

    private float adaptiveGridMm() {
        float desired = (34f * density()) / (PX_PER_MM * Math.max(.0001f, viewScale()));
        desired = clamp(desired, .1f, 1000f);
        double pow = Math.pow(10d, Math.floor(Math.log10(desired)));
        double m = desired / pow;
        double nice = m <= 1d ? 1d : m <= 2d ? 2d : m <= 5d ? 5d : 10d;
        return (float)(nice * pow);
    }

    private Object fastGuidepoint(PointF raw) {
        Object best = null;
        try {
            for (Object e : entities()) {
                Method m = findMethod(e.getClass(), "snapPoints");
                Object v = m == null ? null : m.invoke(e);
                if (!(v instanceof List)) continue;
                for (Object sp : (List<?>)v) {
                    Float x = number(sp, "x"), y = number(sp, "y");
                    if (x == null || y == null) continue;
                    PointF p = new PointF(x, y);
                    float d = screenDistance(raw, p);
                    if (d > hitPx(30f)) continue;
                    String label = normalizePointLabel(text(sp, "label"));
                    int priority = label.startsWith("Endpoint") ? 8
                            : label.startsWith("Center") || label.startsWith("Midpoint") ? 7 : 6;
                    best = better(best, newCandidate(p, label, priority, d));
                }
            }
        } catch (Exception ignored) {
        }
        return best;
    }

    private Object fastNearbyIntersection(PointF raw) {
        List<Segment> near = new ArrayList<>();
        float limit = hitPx(NEAR_INTERSECTION_PX);
        try {
            for (Object e : entities()) {
                for (Segment s : segmentsOf(e)) {
                    if (screenDistance(raw, nearestOnSegment(raw, s.a, s.b)) <= limit) {
                        near.add(s);
                        if (near.size() >= MAX_NEAR_SEGMENTS) break;
                    }
                }
                if (near.size() >= MAX_NEAR_SEGMENTS) break;
            }
        } catch (Exception ignored) {
        }
        Object best = null;
        for (int i = 0; i < near.size(); i++) {
            for (int j = i + 1; j < near.size(); j++) {
                PointF p = intersection(near.get(i), near.get(j));
                if (p == null) continue;
                float d = screenDistance(raw, p);
                if (d <= hitPx(30f)) best = better(best,
                        newCandidate(p, "Intersection • تقاطع", 10, d));
            }
        }
        return best;
    }

    private Object stabilize(PointF raw, Object fresh) {
        if (lockedCandidate == null) {
            lockedCandidate = fresh;
            return fresh;
        }
        PointF lp = candidatePoint(lockedCandidate);
        if (lp == null) {
            lockedCandidate = fresh;
            return fresh;
        }
        float d = screenDistance(raw, lp);
        if (d <= hitPx(RELEASE_DP)) {
            Object held = newCandidate(lp, candidateLabel(lockedCandidate), candidatePriority(lockedCandidate), d);
            if (fresh != null && (candidatePriority(fresh) >= candidatePriority(held) + 2
                    || score(fresh) + hitPx(5f) < score(held))) {
                lockedCandidate = fresh;
                return fresh;
            }
            return held;
        }
        lockedCandidate = fresh;
        return fresh;
    }

    private void restoreTransientState(PointF raw, Object chosen, List<Object> guides, int action) {
        try {
            if (lastPointerWorldField != null) lastPointerWorldField.set(this, new PointF(raw.x, raw.y));
            if (lastCandidateField != null) lastCandidateField.set(this, chosen);
            if (activeGuidesField != null) {
                @SuppressWarnings("unchecked") List<Object> list = (List<Object>)activeGuidesField.get(this);
                list.clear();
                list.addAll(guides);
            }
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                if (gestureStartField != null) gestureStartField.set(this, null);
                if (bool(showHintsField, true) && chosen != null) {
                    final Object keep = chosen;
                    postDelayed(() -> {
                        try {
                            if (lastCandidateField != null && lastCandidateField.get(this) == keep) {
                                lastCandidateField.set(this, null);
                                invalidate();
                            }
                        } catch (Exception ignored) {
                        }
                    }, 600L);
                }
            }
        } catch (Exception ignored) {
        }
        invalidate();
    }

    private List<Object> copyActiveGuides() {
        List<Object> out = new ArrayList<>();
        try {
            Object v = activeGuidesField == null ? null : activeGuidesField.get(this);
            if (v instanceof List) out.addAll((List<?>)v);
        } catch (Exception ignored) {
        }
        return out;
    }

    private boolean isProductionSketchGesture() {
        try { return baseSketchGestureMethod != null && Boolean.TRUE.equals(baseSketchGestureMethod.invoke(this)); }
        catch (Exception e) { return false; }
    }

    private int entityCount() {
        try { return entities().size(); } catch (Exception e) { return 0; }
    }

    private float precisionFor(MotionEvent event) {
        int idx = Math.max(0, Math.min(event.getActionIndex(), event.getPointerCount() - 1));
        int type = event.getToolType(idx);
        if (type == MotionEvent.TOOL_TYPE_STYLUS || type == MotionEvent.TOOL_TYPE_ERASER) return .70f;
        if (type == MotionEvent.TOOL_TYPE_MOUSE) return .78f;
        return 1f;
    }

    private List<Segment> segmentsOf(Object e) {
        List<Segment> out = new ArrayList<>();
        if (e == null) return out;
        String n = e.getClass().getSimpleName();
        if ("LineEntity".equals(n)) {
            Float x1=number(e,"x1"), y1=number(e,"y1"), x2=number(e,"x2"), y2=number(e,"y2");
            if (x1!=null&&y1!=null&&x2!=null&&y2!=null) out.add(new Segment(new PointF(x1,y1),new PointF(x2,y2)));
            return out;
        }
        try {
            Field p = findField(e.getClass(), "points");
            if (p != null && p.get(e) instanceof List) {
                @SuppressWarnings("unchecked") List<PointF> pts = (List<PointF>)p.get(e);
                for (int i=1;i<pts.size();i++) out.add(new Segment(pts.get(i-1),pts.get(i)));
                Field closed = findField(e.getClass(), "closed");
                if (closed != null && closed.getBoolean(e) && pts.size()>2)
                    out.add(new Segment(pts.get(pts.size()-1),pts.get(0)));
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    private static PointF intersection(Segment a, Segment b) {
        float rx=a.b.x-a.a.x, ry=a.b.y-a.a.y, sx=b.b.x-b.a.x, sy=b.b.y-b.a.y;
        float den=rx*sy-ry*sx;
        if (Math.abs(den)<1e-7f) return null;
        float qx=b.a.x-a.a.x, qy=b.a.y-a.a.y;
        float t=(qx*sy-qy*sx)/den, u=(qx*ry-qy*rx)/den;
        if (t<-.00001f||t>1.00001f||u<-.00001f||u>1.00001f) return null;
        return new PointF(a.a.x+t*rx,a.a.y+t*ry);
    }

    private static PointF nearestOnSegment(PointF p, PointF a, PointF b) {
        float dx=b.x-a.x, dy=b.y-a.y, l2=dx*dx+dy*dy;
        if (l2<1e-10f) return new PointF(a.x,a.y);
        float t=((p.x-a.x)*dx+(p.y-a.y)*dy)/l2;
        t=clamp(t,0f,1f);
        return new PointF(a.x+t*dx,a.y+t*dy);
    }

    private Object newCandidate(PointF p, String label, int priority, float distancePx) {
        try { return snapCandidateCtor == null ? null : snapCandidateCtor.newInstance(p,label,priority,distancePx); }
        catch (Exception e) { return null; }
    }

    private Object better(Object a, Object b) {
        if (b == null) return a;
        if (a == null) return b;
        return score(b) < score(a) ? b : a;
    }

    private float score(Object c) { return candidateDistance(c) - candidatePriority(c)*2.8f*density(); }
    private float candidateDistance(Object c) { return floatField(c,"distancePx",Float.POSITIVE_INFINITY); }
    private int candidatePriority(Object c) { return intField(c,"priority",0); }
    private String candidateLabel(Object c) { Object v=objectField(c,"label"); return v==null?"Snap":String.valueOf(v); }
    private PointF candidatePoint(Object c) { Object v=objectField(c,"p"); return v instanceof PointF?(PointF)v:null; }

    private Object objectField(Object o, String name) {
        if (o == null) return null;
        try { Field f=findField(o.getClass(),name); return f==null?null:f.get(o); }
        catch (Exception e) { return null; }
    }
    private float floatField(Object o,String name,float fallback) {
        try { Field f=findField(o.getClass(),name); return f==null?fallback:f.getFloat(o); }
        catch (Exception e) { return fallback; }
    }
    private int intField(Object o,String name,int fallback) {
        try { Field f=findField(o.getClass(),name); return f==null?fallback:f.getInt(o); }
        catch (Exception e) { return fallback; }
    }

    private String normalizePointLabel(String s) {
        if (s==null) s="";
        String l=s.toLowerCase(Locale.ROOT);
        if(l.contains("تقاطع")||l.contains("intersection"))return "Intersection • تقاطع";
        if(l.contains("میانه")||l.contains("وسط")||l.contains("mid"))return "Midpoint • میانه";
        if(l.contains("مرکز")||l.contains("center")||l.contains("centre"))return "Center • مرکز";
        if(l.contains("ابتدا")||l.contains("انتها")||l.contains("endpoint")||l.contains("start")||l.contains("end"))return "Endpoint • انتها";
        return s.isEmpty()?"Guidepoint":s;
    }

    private String fmtGrid(float mm) {
        if(mm>=10f)return String.format(Locale.US,"%.0f",mm);
        if(mm>=1f)return String.format(Locale.US,"%.1f",mm).replace(".0","");
        return String.format(Locale.US,"%.2f",mm).replaceAll("0+$","").replaceAll("\\.$","");
    }

    private void clearCache() { cachedRaw=null;cachedCandidate=null;cachedAtNs=0L; }
    private float hitPx(float dp) { return dp*density()*pointerPrecision; }
    private float density() { return getResources().getDisplayMetrics().density; }
    private float viewScale() { try{return viewScaleField==null?1f:viewScaleField.getFloat(this);}catch(Exception e){return 1f;} }
    private PointF world(float sx,float sy) { try{float k=PX_PER_MM*Math.max(.0001f,viewScale());return new PointF((sx-offsetXField.getFloat(this))/k,(sy-offsetYField.getFloat(this))/k);}catch(Exception e){return new PointF(sx/PX_PER_MM,sy/PX_PER_MM);} }
    private PointF screen(PointF p) { try{float k=PX_PER_MM*viewScale();return new PointF(offsetXField.getFloat(this)+p.x*k,offsetYField.getFloat(this)+p.y*k);}catch(Exception e){return new PointF(p.x*PX_PER_MM,p.y*PX_PER_MM);} }
    private float screenDistance(PointF a,PointF b) { PointF x=screen(a),y=screen(b);return(float)Math.hypot(x.x-y.x,x.y-y.y); }

    @SuppressWarnings("unchecked") private List<Object> entities() throws Exception { Object v=entitiesField==null?null:entitiesField.get(this);return v instanceof List?(List<Object>)v:new ArrayList<>(); }
    private Float number(Object o,String n) { try{Field f=findField(o.getClass(),n);return f==null?null:f.getFloat(o);}catch(Exception e){return null;} }
    private String text(Object o,String n) { try{Field f=findField(o.getClass(),n);Object v=f==null?null:f.get(o);return v==null?"":String.valueOf(v);}catch(Exception e){return"";} }
    private boolean bool(Field f,boolean fallback) { try{return f==null?fallback:f.getBoolean(this);}catch(Exception e){return fallback;} }
    private void setBool(Field f,boolean value) { try{if(f!=null)f.setBoolean(this,value);}catch(Exception ignored){} }
    private static Field findField(Class<?> c,String n) { for(Class<?> x=c;x!=null;x=x.getSuperclass())try{Field f=x.getDeclaredField(n);f.setAccessible(true);return f;}catch(Exception ignored){}return null; }
    private static Method findMethod(Class<?> c,String n,Class<?>...t) { for(Class<?> x=c;x!=null;x=x.getSuperclass())try{Method m=x.getDeclaredMethod(n,t);m.setAccessible(true);return m;}catch(Exception ignored){}return null; }
    private static float clamp(float v,float a,float b) { return Math.max(a,Math.min(b,v)); }

    private static final class Segment {
        final PointF a,b;
        Segment(PointF a,PointF b){this.a=new PointF(a.x,a.y);this.b=new PointF(b.x,b.y);}
    }
}

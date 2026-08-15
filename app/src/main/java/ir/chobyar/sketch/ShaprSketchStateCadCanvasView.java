package ir.chobyar.sketch;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PointF;
import android.graphics.RectF;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Sketch-state visualization following Shapr3D's public sketch-state semantics:
 * under-defined = blue, fully-defined = green, selected = orange, error = red.
 *
 * The current skachmori constraint solver is still evolving, so this layer is
 * deliberately conservative: an element is only reported fully-defined when
 * the existing parametric layer says it is locked/fixed. That avoids showing a
 * false green state while more general constraint/DOF solving is implemented.
 */
public class ShaprSketchStateCadCanvasView extends OcctShaprPenCadCanvasView {

    private static final float PX_PER_MM = 3f;
    private static final int UNDER_DEFINED = Color.rgb(55, 125, 225);
    private static final int FULLY_DEFINED = Color.rgb(46, 155, 92);
    private static final int SELECTED = Color.rgb(28, 104, 220);
    private static final int ERROR = Color.rgb(220, 62, 62);

    private final Paint statePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pointPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private Field entitiesField;
    private Field selectedField;
    private Field selectedObjectsField;
    private Field viewScaleField;
    private Field offsetXField;
    private Field offsetYField;
    private Method isEntityLockedMethod;
    private Method isVisibleMethod;

    public ShaprSketchStateCadCanvasView(Context context) {
        super(context);
        initStateReflection();
        statePaint.setStyle(Paint.Style.STROKE);
        statePaint.setStrokeCap(Paint.Cap.ROUND);
        statePaint.setStrokeJoin(Paint.Join.ROUND);
        pointPaint.setStyle(Paint.Style.FILL);
    }

    private void initStateReflection() {
        try {
            entitiesField = field(CadCanvasView.class, "entities");
            selectedField = field(CadCanvasView.class, "selected");
            selectedObjectsField = field(SmartCadCanvasView.class, "selectedObjects");
            viewScaleField = field(CadCanvasView.class, "viewScale");
            offsetXField = field(CadCanvasView.class, "offsetX");
            offsetYField = field(CadCanvasView.class, "offsetY");

            isEntityLockedMethod = ParametricSketchCanvasView.class
                    .getDeclaredMethod("isEntityLocked", Object.class);
            isEntityLockedMethod.setAccessible(true);

            for (Method m : CadCanvasView.class.getDeclaredMethods()) {
                if ("isVisible".equals(m.getName()) && m.getParameterTypes().length == 1) {
                    isVisibleMethod = m;
                    isVisibleMethod.setAccessible(true);
                    break;
                }
            }
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
        drawSketchStates(canvas);
    }

    @Override
    public String selectedInfo() {
        String base = super.selectedInfo();
        List<Object> selected = selection();
        if (selected.isEmpty()) return base;

        int green = 0, blue = 0, red = 0;
        for (Object e : selected) {
            if (isGeometryError(e)) red++;
            else if (isLocked(e)) green++;
            else blue++;
        }

        String state;
        if (red > 0) state = "Sketch Error";
        else if (green == selected.size()) state = "Fully-defined";
        else if (blue == selected.size()) state = "Under-defined";
        else state = "Partially-defined";
        return base + " | " + state;
    }

    /** Public inspector used by the Tools menu without adding permanent chrome. */
    public String sketchStateSummary() {
        int full = 0, under = 0, error = 0;
        for (Object e : entities()) {
            if (!isSketchEntity(e) || !isVisible(e)) continue;
            if (isGeometryError(e)) error++;
            else if (isLocked(e)) full++;
            else under++;
        }
        return "Fully-defined: " + full + "\n"
                + "Under-defined: " + under + "\n"
                + "Errors: " + error + "\n\n"
                + "سبز = Fully-defined\nآبی روشن = Under-defined\nآبی پررنگ = Selected\nقرمز = Error";
    }

    private void drawSketchStates(Canvas c) {
        float scale = number(viewScaleField, 1f);
        float k = PX_PER_MM * Math.max(0.0001f, scale);
        float ox = number(offsetXField, 0f);
        float oy = number(offsetYField, 0f);
        statePaint.setStrokeWidth(Math.max(1.8f, 1.65f * getResources().getDisplayMetrics().density));

        List<Object> selected = selection();
        for (Object e : entities()) {
            if (!isSketchEntity(e) || !isVisible(e)) continue;
            int color = isGeometryError(e) ? ERROR
                    : containsIdentity(selected, e) ? SELECTED
                    : isLocked(e) ? FULLY_DEFINED
                    : UNDER_DEFINED;
            statePaint.setColor(color);
            pointPaint.setColor(color);
            drawEntityOverlay(c, e, k, ox, oy);
        }
    }

    private void drawEntityOverlay(Canvas c, Object e, float k, float ox, float oy) {
        String type = e.getClass().getSimpleName();
        if ("LineEntity".equals(type)) {
            c.drawLine(sx(num(e,"x1"),k,ox), sy(num(e,"y1"),k,oy),
                    sx(num(e,"x2"),k,ox), sy(num(e,"y2"),k,oy), statePaint);
            drawPoint(c, num(e,"x1"), num(e,"y1"), k, ox, oy);
            drawPoint(c, num(e,"x2"), num(e,"y2"), k, ox, oy);
            return;
        }
        if ("CircleEntity".equals(type)) {
            float x=num(e,"x"), y=num(e,"y"), r=Math.abs(num(e,"r"));
            c.drawCircle(sx(x,k,ox), sy(y,k,oy), r*k, statePaint);
            drawPoint(c,x,y,k,ox,oy);
            return;
        }
        if ("ArcEntity".equals(type)) {
            float x=num(e,"x"), y=num(e,"y"), r=Math.abs(num(e,"r"));
            RectF box=new RectF(sx(x-r,k,ox),sy(y-r,k,oy),sx(x+r,k,ox),sy(y+r,k,oy));
            c.drawArc(box,num(e,"start"),num(e,"sweep"),false,statePaint);
            drawPoint(c,x,y,k,ox,oy);
            return;
        }
        if ("RectEntity".equals(type)) {
            drawPath(c, pointArray(e,"p"), true, k, ox, oy);
            return;
        }
        if ("PolygonEntity".equals(type)) {
            drawPath(c, points(e,"points"), true, k, ox, oy);
            return;
        }
        if ("PolylineEntity".equals(type)) {
            drawPath(c, points(e,"points"), bool(e,"closed"), k, ox, oy);
        }
    }

    private void drawPath(Canvas c,List<PointF> p,boolean closed,float k,float ox,float oy){
        if(p==null||p.size()<2)return;
        for(int i=1;i<p.size();i++){
            PointF a=p.get(i-1),b=p.get(i);
            c.drawLine(sx(a.x,k,ox),sy(a.y,k,oy),sx(b.x,k,ox),sy(b.y,k,oy),statePaint);
        }
        if(closed&&p.size()>2){PointF a=p.get(p.size()-1),b=p.get(0);c.drawLine(sx(a.x,k,ox),sy(a.y,k,oy),sx(b.x,k,ox),sy(b.y,k,oy),statePaint);}
        for(PointF q:p)drawPoint(c,q.x,q.y,k,ox,oy);
    }

    private void drawPoint(Canvas c,float x,float y,float k,float ox,float oy){
        c.drawCircle(sx(x,k,ox),sy(y,k,oy),2.35f*getResources().getDisplayMetrics().density,pointPaint);
    }

    private boolean isLocked(Object e) {
        try { return isEntityLockedMethod != null && Boolean.TRUE.equals(isEntityLockedMethod.invoke(this,e)); }
        catch (Exception ignored) { return false; }
    }

    private boolean isVisible(Object e) {
        try { return isVisibleMethod == null || Boolean.TRUE.equals(isVisibleMethod.invoke(this,e)); }
        catch (Exception ignored) { return true; }
    }

    private boolean isGeometryError(Object e) {
        if(e==null)return true;
        String type=e.getClass().getSimpleName();
        if("LineEntity".equals(type)) return dist(num(e,"x1"),num(e,"y1"),num(e,"x2"),num(e,"y2")) < 1e-4f;
        if("CircleEntity".equals(type)||"ArcEntity".equals(type)) return Math.abs(num(e,"r")) < 1e-4f;
        if("RectEntity".equals(type)) return degenerate(pointArray(e,"p"),true);
        if("PolygonEntity".equals(type)) return degenerate(points(e,"points"),true);
        if("PolylineEntity".equals(type)) return degenerate(points(e,"points"),bool(e,"closed"));
        return false;
    }

    private static boolean degenerate(List<PointF> p,boolean closed){
        if(p==null||p.size()<2)return true;
        float len=0f;for(int i=1;i<p.size();i++)len+=dist(p.get(i-1).x,p.get(i-1).y,p.get(i).x,p.get(i).y);
        if(closed&&p.size()>2)len+=dist(p.get(p.size()-1).x,p.get(p.size()-1).y,p.get(0).x,p.get(0).y);
        return len<1e-4f;
    }

    private static boolean isSketchEntity(Object e){
        if(e==null)return false;String n=e.getClass().getSimpleName();
        return "LineEntity".equals(n)||"RectEntity".equals(n)||"CircleEntity".equals(n)||"ArcEntity".equals(n)||"PolygonEntity".equals(n)||"PolylineEntity".equals(n);
    }

    @SuppressWarnings("unchecked") private List<Object> entities(){
        try{Object v=entitiesField==null?null:entitiesField.get(this);return v instanceof List?(List<Object>)v:new ArrayList<>();}
        catch(Exception e){return new ArrayList<>();}
    }

    @SuppressWarnings("unchecked") private List<Object> selection(){
        try{
            Object m=selectedObjectsField==null?null:selectedObjectsField.get(this);
            if(m instanceof List&&!((List<?>)m).isEmpty())return new ArrayList<>((List<Object>)m);
            Object one=selectedField==null?null:selectedField.get(this);List<Object> out=new ArrayList<>();if(one!=null)out.add(one);return out;
        }catch(Exception e){return new ArrayList<>();}
    }

    private static boolean containsIdentity(List<Object> list,Object target){for(Object x:list)if(x==target)return true;return false;}
    private static float sx(float x,float k,float ox){return x*k+ox;}
    private static float sy(float y,float k,float oy){return y*k+oy;}
    private static float dist(float x1,float y1,float x2,float y2){return(float)Math.hypot(x2-x1,y2-y1);}
    private float number(Field f,float fallback){try{return f==null?fallback:f.getFloat(this);}catch(Exception e){return fallback;}}
    private static float num(Object o,String n){try{Field f=findField(o.getClass(),n);Object v=f==null?null:f.get(o);return v instanceof Number?((Number)v).floatValue():0f;}catch(Exception e){return 0f;}}
    private static boolean bool(Object o,String n){try{Field f=findField(o.getClass(),n);return f!=null&&f.getBoolean(o);}catch(Exception e){return false;}}
    private static Field findField(Class<?> c,String n){for(Class<?> x=c;x!=null;x=x.getSuperclass())try{Field f=x.getDeclaredField(n);f.setAccessible(true);return f;}catch(Exception ignored){}return null;}
    private static List<PointF> pointArray(Object o,String n){List<PointF> out=new ArrayList<>();try{Field f=findField(o.getClass(),n);Object v=f==null?null:f.get(o);if(v instanceof PointF[])for(PointF p:(PointF[])v)out.add(new PointF(p.x,p.y));}catch(Exception ignored){}return out;}
    @SuppressWarnings("unchecked") private static List<PointF> points(Object o,String n){List<PointF> out=new ArrayList<>();try{Field f=findField(o.getClass(),n);Object v=f==null?null:f.get(o);if(v instanceof List)for(Object q:(List<Object>)v)if(q instanceof PointF){PointF p=(PointF)q;out.add(new PointF(p.x,p.y));}}catch(Exception ignored){}return out;}
}

package ir.chobyar.sketch;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PointF;
import android.graphics.RectF;
import android.text.InputType;
import android.view.MotionEvent;
import android.widget.EditText;
import android.widget.Toast;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Spatial sketch foundation.
 *
 * Every sketch owns a real 3D plane (origin + U/V axes + normal). Existing 2D
 * geometry remains local to that plane, which is exactly the representation a
 * future native B-Rep solid kernel needs. A spatial overview renders sketches
 * and current extrusions in model XYZ so plane placement can already be tested.
 *
 * This is deliberately a bridge: the preview is spatial 3D, while Boolean B-Rep
 * solids will be provided by the native solid kernel in the next architecture
 * layer rather than faked here.
 */
public class SpatialCadCanvasView extends EasyCadCanvasView {

    private final Map<String, Geometry3D.Plane3D> planeByLayer = new LinkedHashMap<>();
    private Geometry3D.Plane3D activePlane = Geometry3D.xy();
    private Geometry3D.Plane3D pendingPlane;

    private Field entitiesField;

    private boolean overview3D = false;
    private float cameraYaw = 38f;
    private float cameraPitch = 24f;
    private float spatialScale = 1.25f;
    private boolean orbiting = false;
    private float orbitLastX, orbitLastY;
    private final RectF overviewCard = new RectF();

    private final Paint cardFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cardStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint spatialPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint planePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint axisX = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint axisY = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint axisZ = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint spatialText = new Paint(Paint.ANTI_ALIAS_FLAG);

    public SpatialCadCanvasView(Context context) {
        super(context);
        initSpatialReflection();
        initSpatialPaints();
        planeByLayer.put(getCurrentLayer(), activePlane);
    }

    private void initSpatialReflection() {
        try {
            entitiesField = CadCanvasView.class.getDeclaredField("entities");
            entitiesField.setAccessible(true);
        } catch (Exception ignored) {}
    }

    private void initSpatialPaints() {
        cardFill.setColor(Color.argb(242, 252, 253, 255));
        cardFill.setStyle(Paint.Style.FILL);
        cardStroke.setColor(Color.rgb(180, 191, 207));
        cardStroke.setStyle(Paint.Style.STROKE);
        cardStroke.setStrokeWidth(2f);

        spatialPaint.setColor(Color.rgb(32, 67, 112));
        spatialPaint.setStyle(Paint.Style.STROKE);
        spatialPaint.setStrokeWidth(2.4f);
        spatialPaint.setStrokeCap(Paint.Cap.ROUND);
        spatialPaint.setStrokeJoin(Paint.Join.ROUND);

        planePaint.setColor(Color.argb(135, 95, 135, 205));
        planePaint.setStyle(Paint.Style.STROKE);
        planePaint.setStrokeWidth(1.4f);

        axisX.setColor(Color.rgb(205, 65, 65)); axisX.setStrokeWidth(3f);
        axisY.setColor(Color.rgb(50, 155, 85)); axisY.setStrokeWidth(3f);
        axisZ.setColor(Color.rgb(55, 105, 215)); axisZ.setStrokeWidth(3f);

        spatialText.setColor(Color.rgb(45, 58, 78));
        spatialText.setTextSize(22f);
        spatialText.setTextAlign(Paint.Align.CENTER);
    }

    // ------------------------------------------------------------------
    // Sketch plane ownership
    // ------------------------------------------------------------------

    @Override
    public String createSketchSpace(String requestedName) {
        Geometry3D.Plane3D plane = pendingPlane != null ? pendingPlane : activePlane;
        String result = super.createSketchSpace(requestedName);
        activePlane = plane;
        planeByLayer.put(getCurrentLayer(), plane);
        pendingPlane = null;
        invalidate();
        return result + " | " + plane.label;
    }

    @Override
    public String switchSketchSpace(int index) {
        String result = super.switchSketchSpace(index);
        Geometry3D.Plane3D p = planeByLayer.get(getCurrentLayer());
        if (p == null) {
            p = activePlane == null ? Geometry3D.xy() : activePlane;
            planeByLayer.put(getCurrentLayer(), p);
        }
        activePlane = p;
        invalidate();
        return result + " | " + activePlane.label;
    }

    public String activePlaneLabel() {
        return activePlane == null ? "XY" : activePlane.label;
    }

    public boolean is3DOverview() { return overview3D; }

    public String toggle3DOverview() {
        overview3D = !overview3D;
        orbiting = false;
        invalidate();
        return overview3D
                ? "نمای فضایی 3D روشن شد — با یک انگشت بچرخان"
                : "بازگشت به Sketch دوبعدی";
    }

    public void showPlaneManager() {
        String[] items = {
                "＋ Sketch جدید روی XY / بالا",
                "＋ Sketch جدید روی XZ / روبرو",
                "＋ Sketch جدید روی YZ / بغل",
                "＋ صفحه موازی با فاصله دقیق",
                overview3D ? "□ بستن نمای فضایی 3D" : "◇ نمایش فضایی 3D",
                "◎ نمای ایزومتریک",
                "⌂ نمای روبرو",
                "⌃ نمای بالا"
        };
        new AlertDialog.Builder(getContext())
                .setTitle("صفحه Sketch / Plane")
                .setMessage("صفحه فعال: " + activePlaneLabel() + "\nهر Sketch مختصات دوبعدی خودش را روی یک صفحه واقعی XYZ نگه می‌دارد.")
                .setItems(items, (d, which) -> {
                    if (which == 0) createSketchOnPlane(Geometry3D.xy(), "Sketch XY");
                    else if (which == 1) createSketchOnPlane(Geometry3D.xz(), "Sketch XZ");
                    else if (which == 2) createSketchOnPlane(Geometry3D.yz(), "Sketch YZ");
                    else if (which == 3) showOffsetPlaneDialog();
                    else if (which == 4) toast(toggle3DOverview());
                    else if (which == 5) { overview3D=true; cameraYaw=38f; cameraPitch=24f; invalidate(); }
                    else if (which == 6) { overview3D=true; cameraYaw=0f; cameraPitch=0f; invalidate(); }
                    else { overview3D=true; cameraYaw=0f; cameraPitch=90f; invalidate(); }
                })
                .setNegativeButton("بستن", null)
                .show();
    }

    private void createSketchOnPlane(Geometry3D.Plane3D plane, String baseName) {
        pendingPlane = plane;
        String result = createSketchSpace(baseName + " " + (planeByLayer.size()+1));
        overview3D = false;
        toast(result);
    }

    private void showOffsetPlaneDialog() {
        EditText input = new EditText(getContext());
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL | InputType.TYPE_NUMBER_FLAG_SIGNED);
        input.setHint("مثال: 12.5");
        input.setText("10");
        input.setSelectAllOnFocus(true);
        new AlertDialog.Builder(getContext())
                .setTitle("صفحه موازی — فاصله cm")
                .setMessage("صفحه جدید موازی صفحه فعال ساخته می‌شود. مقدار مثبت در جهت Normal است.")
                .setView(input)
                .setPositiveButton("ساخت", (d,w) -> {
                    try {
                        float cm = Float.parseFloat(normalizeDigits(input.getText().toString().trim()));
                        Geometry3D.Plane3D base = activePlane == null ? Geometry3D.xy() : activePlane;
                        Geometry3D.Plane3D p = base.offset(cm*10f, base.label + " + " + fmt(cm) + "cm");
                        createSketchOnPlane(p, "Offset Plane");
                    } catch (Exception e) { toast("فاصله درست وارد نشده"); }
                })
                .setNegativeButton("لغو", null)
                .show();
    }

    // ------------------------------------------------------------------
    // Spatial overview / orbit
    // ------------------------------------------------------------------

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (overview3D) drawSpatialOverview(canvas);
    }

    private void drawSpatialOverview(Canvas canvas) {
        float left = Math.max(84f, getWidth()*0.10f);
        float top = Math.max(210f, getHeight()*0.16f);
        float right = Math.min(getWidth()-84f, getWidth()*0.92f);
        float bottom = Math.min(getHeight()-105f, getHeight()*0.88f);
        if (right-left < 250f || bottom-top < 220f) {
            left=70f; right=getWidth()-70f; top=205f; bottom=getHeight()-100f;
        }
        overviewCard.set(left,top,right,bottom);
        canvas.drawRoundRect(overviewCard,22f,22f,cardFill);
        canvas.drawRoundRect(overviewCard,22f,22f,cardStroke);

        canvas.save();
        canvas.clipRect(overviewCard);
        drawSpatialPlanes(canvas);
        drawSpatialEntities(canvas);
        drawSpatialAxes(canvas);
        canvas.restore();

        canvas.drawText("3D • Sketch Planes — " + activePlaneLabel(), overviewCard.centerX(), overviewCard.top+30f, spatialText);
        canvas.drawText("یک انگشت: Orbit", overviewCard.centerX(), overviewCard.bottom-18f, spatialText);
    }

    private void drawSpatialPlanes(Canvas c) {
        for (Map.Entry<String,Geometry3D.Plane3D> e : planeByLayer.entrySet()) {
            Geometry3D.Plane3D p=e.getValue();
            float s=55f;
            Geometry3D.Vec3 a=p.point(-s,-s), b=p.point(s,-s), d=p.point(-s,s), z=p.point(s,s);
            drawWorldLine(c,a,b,planePaint); drawWorldLine(c,b,z,planePaint);
            drawWorldLine(c,z,d,planePaint); drawWorldLine(c,d,a,planePaint);
        }
    }

    private void drawSpatialAxes(Canvas c) {
        Geometry3D.Vec3 o=new Geometry3D.Vec3(0,0,0);
        drawWorldLine(c,o,new Geometry3D.Vec3(70,0,0),axisX);
        drawWorldLine(c,o,new Geometry3D.Vec3(0,70,0),axisY);
        drawWorldLine(c,o,new Geometry3D.Vec3(0,0,70),axisZ);
        PointF x=project(new Geometry3D.Vec3(75,0,0));
        PointF y=project(new Geometry3D.Vec3(0,75,0));
        PointF z=project(new Geometry3D.Vec3(0,0,75));
        c.drawText("X",x.x,x.y,spatialText); c.drawText("Y",y.x,y.y,spatialText); c.drawText("Z",z.x,z.y,spatialText);
    }

    private void drawSpatialEntities(Canvas c) {
        for (Object e : entities()) {
            String layer = entityLayer(e);
            Geometry3D.Plane3D plane = planeByLayer.get(layer);
            if (plane == null) plane = Geometry3D.xy();
            String type=e.getClass().getSimpleName();
            if ("GuideEntity".equals(type) || "MeasureEntity".equals(type) || "AngleEntity".equals(type)) continue;
            if ("LineEntity".equals(type)) {
                Geometry3D.Vec3 a=plane.point(safeGet(e,"x1"),safeGet(e,"y1"));
                Geometry3D.Vec3 b=plane.point(safeGet(e,"x2"),safeGet(e,"y2"));
                drawWorldLine(c,a,b,spatialPaint);
            } else if ("RectEntity".equals(type)) {
                PointF[] p = pointArray(e,"p");
                if (p!=null) drawClosedLocal(c,plane,p,e);
            } else if ("PolygonEntity".equals(type) || "PolylineEntity".equals(type)) {
                List<PointF> pts=points(e);
                if (pts.size()>1) drawLocalPolyline(c,plane,pts,"PolygonEntity".equals(type),e);
            } else if ("CircleEntity".equals(type)) {
                drawCircleLocal(c,plane,safeGet(e,"x"),safeGet(e,"y"),Math.abs(safeGet(e,"r")),e);
            } else if ("ArcEntity".equals(type)) {
                drawArcLocal(c,plane,safeGet(e,"x"),safeGet(e,"y"),Math.abs(safeGet(e,"r")),safeGet(e,"start"),safeGet(e,"sweep"));
            } else if ("PointEntity".equals(type)) {
                PointF p=project(plane.point(safeGet(e,"x"),safeGet(e,"y")));
                c.drawCircle(p.x,p.y,4f,spatialPaint);
            }
        }
    }

    private void drawClosedLocal(Canvas c, Geometry3D.Plane3D plane, PointF[] pts, Object entity) {
        List<PointF> list=new ArrayList<>();
        for(PointF p:pts)list.add(p);
        drawLocalPolyline(c,plane,list,true,entity);
    }

    private void drawLocalPolyline(Canvas c, Geometry3D.Plane3D plane, List<PointF> pts, boolean closed, Object entity) {
        if(pts.size()<2)return;
        for(int i=0;i<pts.size()-1;i++) drawWorldLine(c,plane.point(pts.get(i).x,pts.get(i).y),plane.point(pts.get(i+1).x,pts.get(i+1).y),spatialPaint);
        if(closed)drawWorldLine(c,plane.point(pts.get(pts.size()-1).x,pts.get(pts.size()-1).y),plane.point(pts.get(0).x,pts.get(0).y),spatialPaint);
        float h=extrusion(entity);
        if(closed&&h>0.01f){
            Geometry3D.Vec3 n=plane.normal.mul(h);
            for(int i=0;i<pts.size();i++){
                Geometry3D.Vec3 a=plane.point(pts.get(i).x,pts.get(i).y), b=plane.point(pts.get((i+1)%pts.size()).x,pts.get((i+1)%pts.size()).y);
                drawWorldLine(c,a.add(n),b.add(n),spatialPaint);
                drawWorldLine(c,a,a.add(n),spatialPaint);
            }
        }
    }

    private void drawCircleLocal(Canvas c, Geometry3D.Plane3D plane, float cx,float cy,float r,Object entity) {
        if(r<=0)return;
        int n=40;
        Geometry3D.Vec3 first=null,prev=null;
        float h=extrusion(entity);
        Geometry3D.Vec3 off=plane.normal.mul(h);
        Geometry3D.Vec3 topFirst=null,topPrev=null;
        for(int i=0;i<=n;i++){
            double a=2*Math.PI*i/n;
            Geometry3D.Vec3 p=plane.point(cx+(float)Math.cos(a)*r,cy+(float)Math.sin(a)*r);
            if(first==null)first=p;
            if(prev!=null)drawWorldLine(c,prev,p,spatialPaint);
            prev=p;
            if(h>0.01f){
                Geometry3D.Vec3 tp=p.add(off);
                if(topFirst==null)topFirst=tp;
                if(topPrev!=null)drawWorldLine(c,topPrev,tp,spatialPaint);
                if(i%10==0)drawWorldLine(c,p,tp,spatialPaint);
                topPrev=tp;
            }
        }
    }

    private void drawArcLocal(Canvas c, Geometry3D.Plane3D plane,float cx,float cy,float r,float start,float sweep) {
        if(r<=0)return;
        int n=32; Geometry3D.Vec3 prev=null;
        for(int i=0;i<=n;i++){
            double a=Math.toRadians(start+sweep*i/n);
            Geometry3D.Vec3 p=plane.point(cx+(float)Math.cos(a)*r,cy+(float)Math.sin(a)*r);
            if(prev!=null)drawWorldLine(c,prev,p,spatialPaint);
            prev=p;
        }
    }

    private void drawWorldLine(Canvas c, Geometry3D.Vec3 a, Geometry3D.Vec3 b, Paint paint) {
        PointF p=project(a), q=project(b);
        c.drawLine(p.x,p.y,q.x,q.y,paint);
    }

    private PointF project(Geometry3D.Vec3 p) {
        double yaw=Math.toRadians(cameraYaw), pitch=Math.toRadians(cameraPitch);
        double x1=p.x*Math.cos(yaw)-p.y*Math.sin(yaw);
        double y1=p.x*Math.sin(yaw)+p.y*Math.cos(yaw);
        double z1=p.z;
        double y2=y1*Math.cos(pitch)-z1*Math.sin(pitch);
        float scale=spatialScale*Math.min(overviewCard.width(),overviewCard.height())/260f;
        return new PointF(overviewCard.centerX()+(float)x1*scale, overviewCard.centerY()+(float)y2*scale);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (overview3D && event.getPointerCount()==1) {
            int a=event.getActionMasked();
            float x=event.getX(),y=event.getY();
            if(a==MotionEvent.ACTION_DOWN && overviewCard.contains(x,y)){
                orbiting=true; orbitLastX=x; orbitLastY=y; return true;
            }
            if(orbiting){
                if(a==MotionEvent.ACTION_MOVE){
                    cameraYaw += (x-orbitLastX)*0.45f;
                    cameraPitch = clamp(cameraPitch+(y-orbitLastY)*0.35f,-85f,85f);
                    orbitLastX=x; orbitLastY=y; invalidate(); return true;
                }
                if(a==MotionEvent.ACTION_UP||a==MotionEvent.ACTION_CANCEL){orbiting=false;return true;}
                return true;
            }
        }
        return super.onTouchEvent(event);
    }

    // ------------------------------------------------------------------
    // Reflection helpers
    // ------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private List<Object> entities() {
        try { return entitiesField == null ? new ArrayList<>() : (List<Object>)entitiesField.get(this); }
        catch (Exception e) { return new ArrayList<>(); }
    }

    private static String entityLayer(Object e) {
        Object v=call(e,"getLayer");
        return v==null?"":String.valueOf(v);
    }

    private static float extrusion(Object e) {
        Object v=call(e,"getExtrusion");
        return v instanceof Number ? ((Number)v).floatValue() : 0f;
    }

    private static Object call(Object target,String name) {
        if(target==null)return null;
        Class<?> c=target.getClass();
        while(c!=null){
            try{Method m=c.getDeclaredMethod(name);m.setAccessible(true);return m.invoke(target);}
            catch(NoSuchMethodException e){c=c.getSuperclass();}
            catch(Exception e){return null;}
        }
        return null;
    }

    private static Field findField(Class<?> c,String name) {
        Class<?> x=c;
        while(x!=null){
            try{Field f=x.getDeclaredField(name);f.setAccessible(true);return f;}
            catch(Exception e){x=x.getSuperclass();}
        }
        return null;
    }

    private static float safeGet(Object o,String name) {
        try{Field f=findField(o.getClass(),name);return f==null?0f:f.getFloat(o);}catch(Exception e){return 0f;}
    }

    private static PointF[] pointArray(Object o,String name) {
        try{Field f=findField(o.getClass(),name);Object v=f==null?null:f.get(o);return v instanceof PointF[]?(PointF[])v:null;}catch(Exception e){return null;}
    }

    @SuppressWarnings("unchecked")
    private static List<PointF> points(Object o) {
        try{
            Field f=findField(o.getClass(),"points"); Object v=f==null?null:f.get(o);
            if(v instanceof List)return new ArrayList<>((List<PointF>)v);
        }catch(Exception ignored){}
        return new ArrayList<>();
    }

    private static String normalizeDigits(String s) {
        if(s==null)return"";StringBuilder b=new StringBuilder();
        for(int i=0;i<s.length();i++){char c=s.charAt(i);if(c>='۰'&&c<='۹')b.append((char)('0'+c-'۰'));else if(c>='٠'&&c<='٩')b.append((char)('0'+c-'٠'));else b.append(c);}return b.toString();
    }

    private static String fmt(float v){return String.format(Locale.US,"%.1f",v);}
    private static float clamp(float v,float min,float max){return Math.max(min,Math.min(max,v));}
    private void toast(String s){Toast.makeText(getContext(),s,Toast.LENGTH_SHORT).show();}
}

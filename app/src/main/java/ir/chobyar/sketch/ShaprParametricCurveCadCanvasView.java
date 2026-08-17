package ir.chobyar.sketch;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PointF;
import android.text.InputType;
import android.view.MotionEvent;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;

/**
 * Shapr-style parametric curve layer.
 *
 * Public Shapr3D curve semantics mirrored here:
 * - Ellipse is driven by major/minor axes and can be edited numerically.
 * - Spline has Fit Point and Control Point variants.
 * - Fit points lie on the curve; control points drive the curve through a control polygon.
 * - Selected curve points are directly draggable with pen/touch.
 *
 * The existing sketch document still stores a PolylineEntity for compatibility with
 * selection, snapping and downstream tools; the authoritative curve parameters live
 * in this layer and continuously regenerate that polyline approximation.
 */
public class ShaprParametricCurveCadCanvasView extends ShaprConstraintSolverCadCanvasView {

    private static final float PX_PER_MM = 3f;
    private static final int MODE_NONE = 0;
    private static final int MODE_ELLIPSE = 1;
    private static final int MODE_SPLINE_FIT = 2;
    private static final int MODE_SPLINE_CONTROL = 3;

    private Field entitiesField, selectedField, selectedObjectsField;
    private Field viewScaleField, offsetXField, offsetYField;
    private Method saveUndoMethod;
    private Constructor<?> polylineCtor;

    private final IdentityHashMap<Object, EllipseParam> ellipses = new IdentityHashMap<>();
    private final IdentityHashMap<Object, SplineParam> splines = new IdentityHashMap<>();

    private int curveMode = MODE_NONE;
    private PointF ellipseCenter;
    private final List<PointF> penStroke = new ArrayList<>();
    private final List<PointF> controlBuild = new ArrayList<>();
    private long lastControlTapMs;

    private Object draggedCurve;
    private int draggedPoint = -1;
    private int draggedKind = 0; // ellipse: 1 center,2 major,3 minor; spline: 10 point
    private boolean dragUndoSaved;

    private final Paint guidePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pointPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public ShaprParametricCurveCadCanvasView(Context context) {
        super(context);
        initCurveReflection();
        guidePaint.setStyle(Paint.Style.STROKE);
        guidePaint.setStrokeWidth(2f * getResources().getDisplayMetrics().density);
        guidePaint.setColor(Color.rgb(75,120,205));
        pointPaint.setStyle(Paint.Style.FILL);
        pointPaint.setColor(Color.rgb(242,135,36));
        textPaint.setColor(Color.rgb(35,80,160));
        textPaint.setTextSize(11f * getResources().getDisplayMetrics().scaledDensity);
        textPaint.setTextAlign(Paint.Align.CENTER);
    }

    private void initCurveReflection() {
        try {
            entitiesField = field(CadCanvasView.class,"entities");
            selectedField = field(CadCanvasView.class,"selected");
            selectedObjectsField = field(SmartCadCanvasView.class,"selectedObjects");
            viewScaleField = field(CadCanvasView.class,"viewScale");
            offsetXField = field(CadCanvasView.class,"offsetX");
            offsetYField = field(CadCanvasView.class,"offsetY");
            saveUndoMethod = CadCanvasView.class.getDeclaredMethod("saveUndo");
            saveUndoMethod.setAccessible(true);
            Class<?> p = Class.forName("ir.chobyar.sketch.CadCanvasView$PolylineEntity");
            polylineCtor = p.getDeclaredConstructor(List.class, boolean.class);
            polylineCtor.setAccessible(true);
        } catch (Exception ignored) {}
    }

    private static Field field(Class<?> c,String n)throws Exception{Field f=c.getDeclaredField(n);f.setAccessible(true);return f;}

    @Override
    public void showShaprSketchMenu() {
        String[] items={
                "⌁ Automatic Line / Arc • Pen","╱ Line","⌒ Arc","〰 Spline","▭ Rectangle","○ Circle","⬭ Ellipse","⬡ Polygon",
                "⧉ Offset Edge","↗ Move / Rotate Sketch","⠿ Pattern Sketch","⌫ Trim","⎘ Project Sketch","⌖ Measure",
                "⌁ Constraints","🔒 Lock / Unlock","┄ Make Construction","⌫ Delete"};
        new AlertDialog.Builder(getContext()).setTitle("Sketch").setItems(items,(d,w)->{
            if(w==0){stopCurveMode();invokePen("startAutomaticLineArc");}
            else if(w==1){stopCurveMode();setTool(TOOL_LINE);}
            else if(w==2){stopCurveMode();setTool(TOOL_ARC);}
            else if(w==3)showSplineTypeMenu();
            else if(w==4){stopCurveMode();setTool(TOOL_RECT);}
            else if(w==5){stopCurveMode();setTool(TOOL_CIRCLE);}
            else if(w==6)startEllipseMode();
            else if(w==7){stopCurveMode();setTool(TOOL_POLYGON);}
            else if(w==8){stopCurveMode();invokeParent("offsetDialog");}
            else if(w==9){stopCurveMode();invokeParent("transformDialog");}
            else if(w==10){stopCurveMode();invokeParent("patternMenu");}
            else if(w==11){stopCurveMode();toast(trimSelectedLines());}
            else if(w==12){stopCurveMode();toast(invokeParentString("projectReference"));}
            else if(w==13){stopCurveMode();showSketchMeasureInspector();}
            else if(w==14){stopCurveMode();showSmartConstraintMenu();}
            else if(w==15){stopCurveMode();toast(toggleSelectedLock());}
            else if(w==16){stopCurveMode();toast(invokeParentString("toggleConstruction"));}
            else {stopCurveMode();deleteSelected();dispatchWorkspaceState();}
        }).setNegativeButton("بستن",null).show();
    }

    private void showSplineTypeMenu(){
        String[] a={"Fit Point Spline","Control Point Spline"};
        new AlertDialog.Builder(getContext()).setTitle("Spline").setItems(a,(d,w)->{
            if(w==0)startFitSplineMode();else startControlSplineMode();
        }).setNegativeButton("بستن",null).show();
    }

    private void startEllipseMode(){
        stopParentAutomatic();
        super.setTool(TOOL_SELECT);
        curveMode=MODE_ELLIPSE;ellipseCenter=null;
        toast("Ellipse: مرکز را لمس کن و بکش • محورهای بزرگ/کوچک بعداً دقیق قابل ویرایش‌اند");
    }

    private void startFitSplineMode(){
        stopParentAutomatic();
        super.setTool(TOOL_SELECT);
        curveMode=MODE_SPLINE_FIT;penStroke.clear();controlBuild.clear();
        toast("Fit Point Spline: مسیر را با قلم بکش و قلم را بردار");
    }

    private void startControlSplineMode(){
        stopParentAutomatic();
        super.setTool(TOOL_SELECT);
        curveMode=MODE_SPLINE_CONTROL;controlBuild.clear();lastControlTapMs=0;
        toast("Control Point Spline: نقاط را یکی‌یکی بزن • روی نقطه آخر دوبار بزن تا تمام شود");
    }

    private void stopCurveMode(){curveMode=MODE_NONE;ellipseCenter=null;penStroke.clear();controlBuild.clear();draggedCurve=null;draggedPoint=-1;draggedKind=0;}
    private void stopParentAutomatic(){try{Method m=OcctShaprPenCadCanvasView.class.getDeclaredMethod("stopAutomatic");m.setAccessible(true);m.invoke(this);}catch(Exception ignored){}}

    @Override
    public void setTool(int tool){if(curveMode!=MODE_NONE)stopCurveMode();super.setTool(tool);}

    @Override
    public boolean onTouchEvent(MotionEvent e){
        if(handleCurveHandleDrag(e))return true;
        if(curveMode==MODE_NONE)return super.onTouchEvent(e);
        if(e.getPointerCount()>1)return super.onTouchEvent(e);
        int a=e.getActionMasked();
        if(curveMode==MODE_ELLIPSE){
            if(a==MotionEvent.ACTION_DOWN){ellipseCenter=world(e.getX(),e.getY());return true;}
            if(a==MotionEvent.ACTION_UP&&ellipseCenter!=null){
                PointF q=world(e.getX(),e.getY());float rx=Math.abs(q.x-ellipseCenter.x),ry=Math.abs(q.y-ellipseCenter.y);
                if(rx<0.5f||ry<0.5f){toast("Ellipse خیلی کوچک است");ellipseCenter=null;return true;}
                Object obj=createEllipse(ellipseCenter.x,ellipseCenter.y,rx,ry,0f);
                ellipseCenter=null;curveMode=MODE_NONE;if(obj!=null)toast("Ellipse پارامتریک ساخته شد");dispatchWorkspaceState();return true;
            }
            if(a==MotionEvent.ACTION_CANCEL){stopCurveMode();return true;}
            return true;
        }
        if(curveMode==MODE_SPLINE_FIT){
            if(a==MotionEvent.ACTION_DOWN){penStroke.clear();penStroke.add(world(e.getX(),e.getY()));return true;}
            if(a==MotionEvent.ACTION_MOVE){PointF p=world(e.getX(),e.getY());if(penStroke.isEmpty()||dist(p,penStroke.get(penStroke.size()-1))>0.8f)penStroke.add(p);return true;}
            if(a==MotionEvent.ACTION_UP){penStroke.add(world(e.getX(),e.getY()));List<PointF> fit=simplify(penStroke,10);if(fit.size()>=3){Object obj=createSpline(fit,true);if(obj!=null)toast("Fit Point Spline ساخته شد");}else toast("برای Spline مسیر بلندتری بکش");penStroke.clear();curveMode=MODE_NONE;dispatchWorkspaceState();return true;}
            if(a==MotionEvent.ACTION_CANCEL){stopCurveMode();return true;}
        }
        if(curveMode==MODE_SPLINE_CONTROL){
            if(a==MotionEvent.ACTION_UP){
                PointF p=world(e.getX(),e.getY());long now=System.currentTimeMillis();boolean finish=false;
                if(!controlBuild.isEmpty()&&dist(p,controlBuild.get(controlBuild.size()-1))<2.0f&&now-lastControlTapMs<420)finish=true;
                else {controlBuild.add(p);lastControlTapMs=now;invalidate();}
                if(finish&&controlBuild.size()>=3){Object obj=createSpline(controlBuild,false);controlBuild.clear();curveMode=MODE_NONE;if(obj!=null)toast("Control Point Spline ساخته شد");dispatchWorkspaceState();}
                return true;
            }
            if(a==MotionEvent.ACTION_CANCEL){stopCurveMode();return true;}
            return true;
        }
        return true;
    }

    private boolean handleCurveHandleDrag(MotionEvent e){
        if(curveMode!=MODE_NONE||getTool()!=TOOL_SELECT)return false;
        int a=e.getActionMasked();
        if(a==MotionEvent.ACTION_DOWN){
            Object sel=singleSelected();if(sel==null)return false;
            EllipseParam ep=ellipses.get(sel);SplineParam sp=splines.get(sel);
            PointF w=world(e.getX(),e.getY());float hit=16f/(PX_PER_MM*Math.max(.05f,viewScale()));
            if(ep!=null){
                PointF c=new PointF(ep.cx,ep.cy),ma=ellipseAxisPoint(ep,true),mi=ellipseAxisPoint(ep,false);
                if(dist(w,c)<=hit){beginCurveDrag(sel,1,-1);return true;}
                if(dist(w,ma)<=hit){beginCurveDrag(sel,2,-1);return true;}
                if(dist(w,mi)<=hit){beginCurveDrag(sel,3,-1);return true;}
            }else if(sp!=null){
                int idx=nearestPoint(sp.points,w,hit);if(idx>=0){beginCurveDrag(sel,10,idx);return true;}
            }
            return false;
        }
        if(draggedCurve==null)return false;
        if(a==MotionEvent.ACTION_MOVE){
            if(!dragUndoSaved){saveUndo();dragUndoSaved=true;}
            PointF w=world(e.getX(),e.getY());EllipseParam ep=ellipses.get(draggedCurve);SplineParam sp=splines.get(draggedCurve);
            if(ep!=null){
                if(draggedKind==1){ep.cx=w.x;ep.cy=w.y;}
                else if(draggedKind==2){float dx=w.x-ep.cx,dy=w.y-ep.cy;ep.rx=Math.max(.1f,(float)Math.hypot(dx,dy));ep.angle=(float)Math.toDegrees(Math.atan2(dy,dx));}
                else if(draggedKind==3){double r=Math.toRadians(ep.angle+90f);float vx=(float)Math.cos(r),vy=(float)Math.sin(r);ep.ry=Math.max(.1f,Math.abs((w.x-ep.cx)*vx+(w.y-ep.cy)*vy));}
                rebuildEllipse(draggedCurve,ep);
            }else if(sp!=null&&draggedPoint>=0&&draggedPoint<sp.points.size()){sp.points.set(draggedPoint,new PointF(w.x,w.y));rebuildSpline(draggedCurve,sp);}
            invalidate();return true;
        }
        if(a==MotionEvent.ACTION_UP||a==MotionEvent.ACTION_CANCEL){draggedCurve=null;draggedPoint=-1;draggedKind=0;dragUndoSaved=false;dispatchWorkspaceState();invalidate();return true;}
        return true;
    }

    private void beginCurveDrag(Object o,int kind,int point){draggedCurve=o;draggedKind=kind;draggedPoint=point;dragUndoSaved=false;}

    private Object createEllipse(float cx,float cy,float rx,float ry,float angle){
        try{saveUndo();EllipseParam p=new EllipseParam(cx,cy,rx,ry,angle);Object e=polylineCtor.newInstance(sampleEllipse(p,128),true);setLayer(e);entities().add(e);ellipses.put(e,p);selectOne(e);invalidate();return e;}catch(Exception ex){return null;}
    }

    private Object createSpline(List<PointF> points,boolean fit){
        try{saveUndo();SplineParam p=new SplineParam(copy(points),fit);List<PointF> sampled=fit?sampleFit(p.points):sampleControl(p.points);Object e=polylineCtor.newInstance(sampled,false);setLayer(e);entities().add(e);splines.put(e,p);selectOne(e);invalidate();return e;}catch(Exception ex){return null;}
    }

    private void rebuildEllipse(Object e,EllipseParam p){replacePolylinePoints(e,sampleEllipse(p,128));}
    private void rebuildSpline(Object e,SplineParam p){replacePolylinePoints(e,p.fit?sampleFit(p.points):sampleControl(p.points));}

    @SuppressWarnings("unchecked")
    private void replacePolylinePoints(Object e,List<PointF> p){
        try{Field f=findField(e.getClass(),"points");Object v=f==null?null:f.get(e);if(v instanceof List){List<PointF> dst=(List<PointF>)v;dst.clear();for(PointF q:p)dst.add(new PointF(q.x,q.y));}}catch(Exception ignored){}
    }

    private static class EllipseParam{float cx,cy,rx,ry,angle;EllipseParam(float cx,float cy,float rx,float ry,float a){this.cx=cx;this.cy=cy;this.rx=rx;this.ry=ry;this.angle=a;}}
    private static class SplineParam{final List<PointF> points;final boolean fit;SplineParam(List<PointF> p,boolean fit){this.points=p;this.fit=fit;}}

    @Override
    protected void onDraw(Canvas c){
        super.onDraw(c);
        if(curveMode==MODE_SPLINE_CONTROL&&controlBuild.size()>0)drawControlBuild(c);
        Object s=singleSelected();if(s==null)return;
        EllipseParam ep=ellipses.get(s);if(ep!=null){drawEllipseHandles(c,ep);return;}
        SplineParam sp=splines.get(s);if(sp!=null)drawSplineHandles(c,sp);
    }

    private void drawEllipseHandles(Canvas c,EllipseParam e){
        PointF center=screen(e.cx,e.cy),ma=screen(ellipseAxisPoint(e,true)),mi=screen(ellipseAxisPoint(e,false));
        c.drawLine(center.x,center.y,ma.x,ma.y,guidePaint);c.drawLine(center.x,center.y,mi.x,mi.y,guidePaint);
        dot(c,center);dot(c,ma);dot(c,mi);
        c.drawText(dual(e.rx*2f),ma.x,ma.y-10f,textPaint);c.drawText(dual(e.ry*2f),mi.x,mi.y-10f,textPaint);
    }

    private void drawSplineHandles(Canvas c,SplineParam s){
        if(!s.fit&&s.points.size()>1){for(int i=1;i<s.points.size();i++){PointF a=screen(s.points.get(i-1)),b=screen(s.points.get(i));c.drawLine(a.x,a.y,b.x,b.y,guidePaint);}}
        for(PointF p:s.points)dot(c,screen(p));
        if(!s.points.isEmpty()){PointF q=screen(s.points.get(0));c.drawText(s.fit?"Fit Point":"Control Point",q.x,q.y-18f,textPaint);}
    }

    private void drawControlBuild(Canvas c){
        for(int i=1;i<controlBuild.size();i++){PointF a=screen(controlBuild.get(i-1)),b=screen(controlBuild.get(i));c.drawLine(a.x,a.y,b.x,b.y,guidePaint);}for(PointF p:controlBuild)dot(c,screen(p));
    }
    private void dot(Canvas c,PointF p){c.drawCircle(p.x,p.y,5.5f*getResources().getDisplayMetrics().density,pointPaint);}

    public void showCurveEditor(){
        Object s=singleSelected();EllipseParam ep=ellipses.get(s);SplineParam sp=splines.get(s);
        if(ep!=null){showEllipseEditor(s,ep);return;}if(sp!=null){showSplineEditor(s,sp);return;}toast("اول Ellipse یا Spline را انتخاب کن");
    }

    private void showEllipseEditor(Object obj,EllipseParam e){
        LinearLayout box=new LinearLayout(getContext());box.setOrientation(LinearLayout.VERTICAL);
        EditText major=input(fmt(e.rx*2f)),minor=input(fmt(e.ry*2f)),ang=input(fmt(e.angle));major.setHint("Major axis • mm");minor.setHint("Minor axis • mm");ang.setHint("Rotation °");box.addView(major);box.addView(minor);box.addView(ang);
        new AlertDialog.Builder(getContext()).setTitle("Ellipse • Major / Minor Axis").setView(box).setPositiveButton("اعمال",(d,w)->{
            try{saveUndo();e.rx=Math.max(.05f,lengthMm(major.getText().toString())/2f);e.ry=Math.max(.05f,lengthMm(minor.getText().toString())/2f);e.angle=Float.parseFloat(normalizeDigits(ang.getText().toString()));rebuildEllipse(obj,e);invalidate();dispatchWorkspaceState();}
            catch(Exception ex){toast("مقادیر Ellipse درست نیست");}
        }).setNegativeButton("لغو",null).show();
    }

    private void showSplineEditor(Object obj,SplineParam s){
        String[] a={s.fit?"نوع: Fit Point":"نوع: Control Point","＋ افزودن نقطه در انتها","− حذف آخرین نقطه"};
        new AlertDialog.Builder(getContext()).setTitle("Spline • "+(s.fit?"Fit":"Control")).setItems(a,(d,w)->{
            if(w==0)return;
            if(w==1){PointF p=s.points.isEmpty()?new PointF(0,0):s.points.get(s.points.size()-1);s.points.add(new PointF(p.x+10f,p.y));rebuildSpline(obj,s);invalidate();}
            else if(s.points.size()>3){s.points.remove(s.points.size()-1);rebuildSpline(obj,s);invalidate();}else toast("Spline حداقل سه نقطه لازم دارد");
        }).setNegativeButton("بستن",null).show();
    }

    @Override
    public boolean canEditExactDimension(){Object s=singleSelected();if(s!=null&&ellipses.containsKey(s))return true;if(s!=null&&splines.containsKey(s))return false;return super.canEditExactDimension();}

    @Override
    public String exactDimensionTitle(){Object s=singleSelected();if(s!=null&&ellipses.containsKey(s))return"محور بزرگ و کوچک Ellipse — mm";return super.exactDimensionTitle();}
    @Override
    public String exactDimensionHint(){Object s=singleSelected();if(s!=null&&ellipses.containsKey(s))return"Major Minor؛ مثال: 10cm 6cm یا 100mm 60mm";return super.exactDimensionHint();}
    @Override
    public String exactDimensionCurrentValue(){Object s=singleSelected();EllipseParam e=ellipses.get(s);if(e!=null)return cm(e.rx*2f)+" "+cm(e.ry*2f);return super.exactDimensionCurrentValue();}

    @Override
    public String applySelectedDimension(String raw){
        Object s=singleSelected();EllipseParam e=ellipses.get(s);if(e==null)return super.applySelectedDimension(raw);
        try{String[] a=raw.trim().split("[ ,×xX]+");if(a.length<2)return"دو مقدار Major و Minor وارد کن";saveUndo();e.rx=Math.max(.05f,lengthMm(a[0])/2f);e.ry=Math.max(.05f,lengthMm(a[1])/2f);rebuildEllipse(s,e);invalidate();dispatchWorkspaceState();return"Ellipse = "+dual(e.rx*2f)+" × "+dual(e.ry*2f);}catch(Exception ex){return"مقدار Ellipse درست نیست";}
    }

    @Override
    public String selectedInfo(){
        Object s=singleSelected();EllipseParam e=ellipses.get(s);if(e!=null)return"Ellipse | Major "+dual(e.rx*2f)+" | Minor "+dual(e.ry*2f)+" | Rotation "+fmt(e.angle)+"° | Under-defined";
        SplineParam p=splines.get(s);if(p!=null)return(p.fit?"Fit Point Spline":"Control Point Spline")+" | Points "+p.points.size()+" | Under-defined";
        return super.selectedInfo();
    }

    @Override
    public String sketchStateSummary(){return super.sketchStateSummary()+"\nParametric Ellipse: "+ellipses.size()+"\nParametric Spline: "+splines.size();}

    @Override
    public void deleteSelected(){List<Object> before=selection();super.deleteSelected();for(Object e:before){ellipses.remove(e);splines.remove(e);}}
    @Override
    public void clearAll(){super.clearAll();ellipses.clear();splines.clear();stopCurveMode();}
    @Override
    public void undo(){super.undo();ellipses.clear();splines.clear();stopCurveMode();}

    private static List<PointF> sampleEllipse(EllipseParam e,int n){List<PointF> o=new ArrayList<>();double a=Math.toRadians(e.angle),ca=Math.cos(a),sa=Math.sin(a);for(int i=0;i<n;i++){double t=2d*Math.PI*i/n,x=e.rx*Math.cos(t),y=e.ry*Math.sin(t);o.add(new PointF(e.cx+(float)(x*ca-y*sa),e.cy+(float)(x*sa+y*ca)));}return o;}
    private static PointF ellipseAxisPoint(EllipseParam e,boolean major){double a=Math.toRadians(e.angle+(major?0:90));float r=major?e.rx:e.ry;return new PointF(e.cx+r*(float)Math.cos(a),e.cy+r*(float)Math.sin(a));}

    private static List<PointF> sampleFit(List<PointF> p){if(p.size()<3)return copy(p);List<PointF> o=new ArrayList<>();for(int i=0;i<p.size()-1;i++){PointF p0=p.get(Math.max(0,i-1)),p1=p.get(i),p2=p.get(i+1),p3=p.get(Math.min(p.size()-1,i+2));for(int j=0;j<12;j++){float t=j/12f,t2=t*t,t3=t2*t;float x=.5f*((2*p1.x)+(-p0.x+p2.x)*t+(2*p0.x-5*p1.x+4*p2.x-p3.x)*t2+(-p0.x+3*p1.x-3*p2.x+p3.x)*t3);float y=.5f*((2*p1.y)+(-p0.y+p2.y)*t+(2*p0.y-5*p1.y+4*p2.y-p3.y)*t2+(-p0.y+3*p1.y-3*p2.y+p3.y)*t3);o.add(new PointF(x,y));}}o.add(new PointF(p.get(p.size()-1).x,p.get(p.size()-1).y));return o;}

    private static List<PointF> sampleControl(List<PointF> c){
        if(c.size()<4)return sampleFit(c);List<PointF> o=new ArrayList<>();
        List<PointF> p=new ArrayList<>();p.add(c.get(0));p.add(c.get(0));p.addAll(c);p.add(c.get(c.size()-1));p.add(c.get(c.size()-1));
        for(int i=0;i<=p.size()-4;i++){PointF p0=p.get(i),p1=p.get(i+1),p2=p.get(i+2),p3=p.get(i+3);for(int j=0;j<12;j++){float t=j/12f,t2=t*t,t3=t2*t;float b0=(1-3*t+3*t2-t3)/6f,b1=(4-6*t2+3*t3)/6f,b2=(1+3*t+3*t2-3*t3)/6f,b3=t3/6f;o.add(new PointF(b0*p0.x+b1*p1.x+b2*p2.x+b3*p3.x,b0*p0.y+b1*p1.y+b2*p2.y+b3*p3.y));}}return o;
    }

    private static List<PointF> simplify(List<PointF> src,int max){List<PointF> o=new ArrayList<>();if(src.size()<=max)return copy(src);for(int i=0;i<max;i++){int idx=Math.round(i*(src.size()-1f)/(max-1f));PointF p=src.get(idx);o.add(new PointF(p.x,p.y));}return o;}
    private static List<PointF> copy(List<PointF> s){List<PointF> o=new ArrayList<>();for(PointF p:s)o.add(new PointF(p.x,p.y));return o;}
    private static int nearestPoint(List<PointF> p,PointF q,float r){int best=-1;float bd=r;for(int i=0;i<p.size();i++){float d=dist(p.get(i),q);if(d<=bd){bd=d;best=i;}}return best;}

    @SuppressWarnings("unchecked") private List<Object> entities(){try{Object v=entitiesField.get(this);return v instanceof List?(List<Object>)v:new ArrayList<>();}catch(Exception e){return new ArrayList<>();}}
    @SuppressWarnings("unchecked") private List<Object> selection(){try{Object v=selectedObjectsField.get(this);if(v instanceof List&&!((List<?>)v).isEmpty())return new ArrayList<>((List<Object>)v);Object one=selectedField.get(this);List<Object> o=new ArrayList<>();if(one!=null)o.add(one);return o;}catch(Exception e){return new ArrayList<>();}}
    private Object singleSelected(){List<Object> s=selection();return s.size()==1?s.get(0):null;}
    @SuppressWarnings("unchecked") private void selectOne(Object e)throws Exception{selectedField.set(this,e);Object v=selectedObjectsField.get(this);if(v instanceof List){List<Object> l=(List<Object>)v;l.clear();l.add(e);}}
    private void setLayer(Object e)throws Exception{Method m=findMethod(e.getClass(),"setLayer",String.class);if(m!=null)m.invoke(e,getCurrentLayer());}
    private void saveUndo(){try{if(saveUndoMethod!=null)saveUndoMethod.invoke(this);}catch(Exception ignored){}}
    private PointF world(float sx,float sy){float k=PX_PER_MM*Math.max(.0001f,viewScale());return new PointF((sx-offsetX())/k,(sy-offsetY())/k);}
    private PointF screen(PointF p){return screen(p.x,p.y);}private PointF screen(float x,float y){float k=PX_PER_MM*viewScale();return new PointF(offsetX()+x*k,offsetY()+y*k);}
    private float viewScale(){try{return viewScaleField.getFloat(this);}catch(Exception e){return 1f;}}private float offsetX(){try{return offsetXField.getFloat(this);}catch(Exception e){return 0f;}}private float offsetY(){try{return offsetYField.getFloat(this);}catch(Exception e){return 0f;}}

    private void invokePen(String name){try{Method m=OcctShaprPenCadCanvasView.class.getDeclaredMethod(name);m.setAccessible(true);m.invoke(this);}catch(Exception e){toast("ابزار در دسترس نیست");}}
    private void invokeParent(String name){try{Method m=OcctShaprCadCanvasView.class.getDeclaredMethod(name);m.setAccessible(true);m.invoke(this);}catch(Exception e){toast("ابزار در دسترس نیست");}}
    private String invokeParentString(String name){try{Method m=OcctShaprCadCanvasView.class.getDeclaredMethod(name);m.setAccessible(true);Object r=m.invoke(this);return r==null?"":String.valueOf(r);}catch(Exception e){return"ابزار در دسترس نیست";}}

    private EditText input(String s){EditText e=new EditText(getContext());e.setSingleLine(true);e.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);e.setText(s);e.setSelectAllOnFocus(true);return e;}
    private static float lengthMm(String raw){String s=normalizeDigits(raw).toLowerCase(Locale.US).trim().replace('،','.').replace(',','.');boolean cm=s.endsWith("cm")||s.endsWith("سانتیمتر")||s.endsWith("سانتی‌متر");s=s.replace("میلی‌متر","").replace("میلیمتر","").replace("سانتی‌متر","").replace("سانتیمتر","").replace("mm","").replace("cm","").trim();float v=Float.parseFloat(s);return cm?v*10f:v;}
    private static String normalizeDigits(String s){if(s==null)return"";StringBuilder b=new StringBuilder();for(int i=0;i<s.length();i++){char c=s.charAt(i);if(c>='۰'&&c<='۹')b.append((char)('0'+c-'۰'));else if(c>='٠'&&c<='٩')b.append((char)('0'+c-'٠'));else b.append(c);}return b.toString();}
    private static String cm(float mm){return fmt(mm);}private static String dual(float mm){return fmt(mm)+" mm";}
    private static String fmt(float v){String s=String.format(Locale.US,"%.2f",v);while(s.contains(".")&&(s.endsWith("0")||s.endsWith(".")))s=s.substring(0,s.length()-1);return s;}
    private static float dist(PointF a,PointF b){return(float)Math.hypot(b.x-a.x,b.y-a.y);}
    private static Field findField(Class<?> c,String n){for(Class<?> x=c;x!=null;x=x.getSuperclass())try{Field f=x.getDeclaredField(n);f.setAccessible(true);return f;}catch(Exception ignored){}return null;}
    private static Method findMethod(Class<?> c,String n,Class<?>...t){for(Class<?> x=c;x!=null;x=x.getSuperclass())try{Method m=x.getDeclaredMethod(n,t);m.setAccessible(true);return m;}catch(Exception ignored){}return null;}
    private void toast(String s){Toast.makeText(getContext(),s,Toast.LENGTH_SHORT).show();}
}

package ir.chobyar.sketch;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PointF;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.widget.Toast;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Driving-dimension + DOF layer for the sketch workspace.
 *
 * The interaction contract follows Shapr3D's public sketch semantics:
 * dimensions and geometric constraints drive the sketch, and an item is only
 * Fully-defined when it has no remaining freedom. This is deliberately a real
 * incremental solver for the primitives/constraints skachmori currently owns,
 * not a fake "green when dimensioned" flag. Unsupported higher-order curve DOF
 * remains under-defined until the native/parametric curve model is expanded.
 */
public class ShaprConstraintSolverCadCanvasView extends ShaprSketchStateCadCanvasView {

    private static final float PX_PER_MM = 3f;
    private static final int UNDER = Color.rgb(55,125,225);
    private static final int FULL = Color.rgb(46,155,92);
    private static final int SELECTED_COLOR = Color.rgb(242,135,36);
    private static final int ERROR = Color.rgb(220,62,62);

    private Field entitiesField, selectedField, selectedObjectsField;
    private Field viewScaleField, offsetXField, offsetYField;
    private Field axisLocksField, lineRelationsField, coincidenceLinksField;
    private Field pointOnLineLinksField, midpointRelationsField, equalRelationsField;
    private Method isEntityLockedMethod, isVisibleMethod, saveUndoMethod;
    private Method enforceBaseConstraintsMethod, enforcePointLinksMethod, enforceLabRelationsMethod;

    private final IdentityHashMap<Object, LineDrive> lineDrives = new IdentityHashMap<>();
    private final IdentityHashMap<Object, Float> radiusDrives = new IdentityHashMap<>();
    private final IdentityHashMap<Object, float[]> rectDrives = new IdentityHashMap<>();
    private final IdentityHashMap<Object, Float> polygonRadiusDrives = new IdentityHashMap<>();
    private final List<PairAngleDrive> pairAngleDrives = new ArrayList<>();
    private final List<ConcentricDrive> concentricDrives = new ArrayList<>();

    private final Paint solverStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint solverPoint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public ShaprConstraintSolverCadCanvasView(Context context) {
        super(context);
        initSolverReflection();
        solverStroke.setStyle(Paint.Style.STROKE);
        solverStroke.setStrokeCap(Paint.Cap.ROUND);
        solverStroke.setStrokeJoin(Paint.Join.ROUND);
        solverPoint.setStyle(Paint.Style.FILL);
    }

    private void initSolverReflection() {
        try {
            entitiesField = field(CadCanvasView.class,"entities");
            selectedField = field(CadCanvasView.class,"selected");
            selectedObjectsField = field(SmartCadCanvasView.class,"selectedObjects");
            viewScaleField = field(CadCanvasView.class,"viewScale");
            offsetXField = field(CadCanvasView.class,"offsetX");
            offsetYField = field(CadCanvasView.class,"offsetY");

            axisLocksField = field(ChobYarShaprCanvasView.class,"axisLocks");
            lineRelationsField = field(ChobYarShaprCanvasView.class,"lineRelations");
            coincidenceLinksField = field(ChobYarShaprCanvasView.class,"coincidenceLinks");
            pointOnLineLinksField = field(ParametricSketchCanvasView.class,"pointOnLineLinks");
            midpointRelationsField = field(ShaprLabCanvasView.class,"midpointRelations");
            equalRelationsField = field(ShaprLabCanvasView.class,"equalRelations");

            isEntityLockedMethod = ParametricSketchCanvasView.class.getDeclaredMethod("isEntityLocked",Object.class);
            isEntityLockedMethod.setAccessible(true);
            saveUndoMethod = CadCanvasView.class.getDeclaredMethod("saveUndo");
            saveUndoMethod.setAccessible(true);

            enforceBaseConstraintsMethod = ChobYarShaprCanvasView.class.getDeclaredMethod("enforceConstraints");
            enforceBaseConstraintsMethod.setAccessible(true);
            enforcePointLinksMethod = ParametricSketchCanvasView.class.getDeclaredMethod("enforcePointLinks");
            enforcePointLinksMethod.setAccessible(true);
            enforceLabRelationsMethod = ShaprLabCanvasView.class.getDeclaredMethod("enforceRelations");
            enforceLabRelationsMethod.setAccessible(true);

            for(Method m:CadCanvasView.class.getDeclaredMethods()){
                if("isVisible".equals(m.getName())&&m.getParameterTypes().length==1){isVisibleMethod=m;m.setAccessible(true);break;}
            }
        } catch (Exception ignored) {}
    }

    private static Field field(Class<?> owner,String name)throws Exception{
        Field f=owner.getDeclaredField(name);f.setAccessible(true);return f;
    }

    // ------------------------------------------------------------------
    // Driving dimensions
    // ------------------------------------------------------------------

    @Override
    public String applySelectedDimension(String raw) {
        Object before = singleSelected();
        String result = super.applySelectedDimension(raw);
        if (before != null && containsIdentity(entities(),before)) captureDrivingDimension(before);
        solveNow(4);
        invalidate();
        dispatchWorkspaceState();
        return result + (before == null ? "" : " • Driving");
    }

    @Override
    public String setSelectedLineAngle(float degrees) {
        Object line = singleSelected();
        String result = super.setSelectedLineAngle(degrees);
        if (isLine(line)) {
            LineDrive d = lineDrive(line);
            d.angle = lineAngle(line);
            d.snapshot();
            removePairAnglesFor(line);
        }
        solveNow(4);
        invalidate();
        return result + " • Driving";
    }

    @Override
    public String setSelectedLinesAngle(float degrees) {
        List<Object> s = selectedLines();
        String result = super.setSelectedLinesAngle(degrees);
        if (s.size()==2) {
            Object fixed=s.get(0),moving=s.get(1);
            removePairAnglesFor(moving);
            LineDrive own=lineDrives.get(moving);
            if(own!=null) own.angle=Float.NaN;
            pairAngleDrives.add(new PairAngleDrive(fixed,moving,normalizeDelta(lineAngle(moving)-lineAngle(fixed))));
        }
        solveNow(4);
        invalidate();
        return result + " • Driving";
    }

    private void captureDrivingDimension(Object e) {
        String t=e.getClass().getSimpleName();
        if("LineEntity".equals(t)){
            LineDrive d=lineDrive(e);d.length=lineLength(e);d.snapshot();return;
        }
        if("CircleEntity".equals(t)||"ArcEntity".equals(t)){
            radiusDrives.put(e,Math.abs(num(e,"r")));return;
        }
        if("RectEntity".equals(t)){
            PointF[] p=pointArrayRaw(e,"p");
            if(p!=null&&p.length>=4)rectDrives.put(e,new float[]{dist(p[0],p[1]),dist(p[1],p[2])});
            return;
        }
        if("PolygonEntity".equals(t)){
            List<PointF> p=points(e,"points");
            if(!p.isEmpty()){PointF c=centroid(p);polygonRadiusDrives.put(e,dist(c,p.get(0)));}
        }
    }

    private LineDrive lineDrive(Object line){
        LineDrive d=lineDrives.get(line);if(d==null){d=new LineDrive(line);lineDrives.put(line,d);}return d;
    }

    private class LineDrive {
        final Object line;
        float length=Float.NaN;
        float angle=Float.NaN;
        float x1,y1,x2,y2;
        LineDrive(Object line){this.line=line;snapshot();}
        void snapshot(){x1=num(line,"x1");y1=num(line,"y1");x2=num(line,"x2");y2=num(line,"y2");}
        void enforce(){
            if(!isLine(line))return;
            PointF a=endpoint(line,0),b=endpoint(line,1);if(a==null||b==null)return;
            float currentLen=dist(a,b);if(currentLen<1e-7f)return;
            float targetLen=Float.isNaN(length)?currentLen:length;
            float targetAngle=Float.isNaN(angle)?lineAngle(line):angle;
            float ma=(float)Math.hypot(a.x-x1,a.y-y1),mb=(float)Math.hypot(b.x-x2,b.y-y2);
            double r=Math.toRadians(targetAngle);float vx=targetLen*(float)Math.cos(r),vy=targetLen*(float)Math.sin(r);
            if(ma<=mb){setEndpoint(line,1,a.x+vx,a.y+vy);}else{setEndpoint(line,0,b.x-vx,b.y-vy);}
            snapshot();
        }
    }

    private static class PairAngleDrive {
        final Object fixed,moving;final float signedDelta;
        PairAngleDrive(Object fixed,Object moving,float signedDelta){this.fixed=fixed;this.moving=moving;this.signedDelta=signedDelta;}
    }

    private static class ConcentricDrive {
        final Object anchor,moving;
        ConcentricDrive(Object anchor,Object moving){this.anchor=anchor;this.moving=moving;}
    }

    // ------------------------------------------------------------------
    // Constraint menu aligned to the current Shapr constraint set
    // ------------------------------------------------------------------

    @Override
    public void showSmartConstraintMenu() {
        final List<Object> s=selection();
        final List<Object> lines=selectedLines();
        final List<Object> curves=selectedCurves();
        final List<String> labels=new ArrayList<>();
        final List<Runnable> actions=new ArrayList<>();

        if(s.isEmpty()){
            labels.add("اول Sketch element را انتخاب کن");actions.add(()->toast("اول خط، دایره یا قوس را انتخاب کن"));
        }else{
            if(!lines.isEmpty()) add(labels,actions,"H/V — افقی / عمودی",()->toast(applyHorizontalVerticalConstraint()));
            if(lines.size()==2&&s.size()==2){
                add(labels,actions,"Parallel — موازی",()->toast(applyParallelConstraint()));
                add(labels,actions,"Perpendicular — عمود",()->toast(applyPerpendicularConstraint()));
                add(labels,actions,"Coincident — اتصال",()->toast(invokePrivateString(ParametricSketchCanvasView.class,"applyManualCoincident")));
                add(labels,actions,"Midpoint — اتصال به وسط",()->toast(applyMidpointConstraint()));
            }
            if(curves.size()==2&&s.size()==2){
                add(labels,actions,"Concentric — هم‌مرکز",()->toast(applyConcentricConstraint()));
                add(labels,actions,"Equal — شعاع برابر",()->toast(applyEqualConstraint()));
            }else if(lines.size()>=2&&lines.size()==s.size()){
                add(labels,actions,"Equal — طول برابر",()->toast(applyEqualConstraint()));
            }
            if(s.size()==2&&lines.size()==1&&curves.size()==1)
                add(labels,actions,"Tangent — مماس",()->toast(applyTangentConstraint()));
            if(lines.size()==3&&s.size()==3)
                add(labels,actions,"Symmetry — تقارن",()->toast(applySymmetryConstraint()));
            if(hasDisconnectableConnection(s))
                add(labels,actions,"Disconnect — قطع اتصال نقطه",()->toast(disconnectSelectedConnections()));
            add(labels,actions,isLocked(s)?"Unlock — باز کردن":"Lock — قفل",()->toast(toggleSelectedLock()));
            add(labels,actions,"Make Construction",()->toast(invokePrivateString(OcctShaprCadCanvasView.class,"toggleConstruction")));
        }

        new AlertDialog.Builder(getContext()).setTitle("Constraints")
                .setItems(labels.toArray(new String[0]),(d,w)->actions.get(w).run())
                .setNegativeButton("بستن",null).show();
    }

    private static void add(List<String> labels,List<Runnable> actions,String label,Runnable action){labels.add(label);actions.add(action);}

    public String applyConcentricConstraint(){
        List<Object> c=selectedCurves();
        if(c.size()!=2)return"برای Concentric دو Circle/Arc را انتخاب کن";
        saveUndo();Object a=c.get(0),b=c.get(1);setFloat(b,"x",num(a,"x"));setFloat(b,"y",num(a,"y"));
        for(ConcentricDrive d:concentricDrives)if((d.anchor==a&&d.moving==b)||(d.anchor==b&&d.moving==a)){solveNow(3);return"Concentric از قبل فعال است";}
        concentricDrives.add(new ConcentricDrive(a,b));solveNow(4);invalidate();dispatchWorkspaceState();return"Concentric اعمال شد";
    }

    public String disconnectSelectedConnections(){
        List<Object>s=selection();if(s.isEmpty())return"اول نقطه/المان متصل را انتخاب کن";
        saveUndo();int removed=0;
        removed+=removeRelations(coincidenceLinksField,s,"a","b");
        removed+=removePointOnLineRelations(s);
        removed+=removeMidpointRelations(s);
        solveNow(3);invalidate();dispatchWorkspaceState();
        return removed==0?"اتصال Coincident/Midpoint پیدا نشد":removed+" اتصال Coincident/Midpoint قطع شد";
    }

    private int removeRelations(Field listField,List<Object>s,String...endpointFields){
        int n=0;try{Object v=listField==null?null:listField.get(this);if(!(v instanceof List))return 0;Iterator<?>it=((List<?>)v).iterator();while(it.hasNext()){
            Object r=it.next();boolean hit=false;for(String ef:endpointFields){Object ep=obj(r,ef);Object line=obj(ep,"line");if(containsIdentity(s,line)){hit=true;break;}}
            if(hit){it.remove();n++;}
        }}catch(Exception ignored){}return n;
    }

    private int removePointOnLineRelations(List<Object>s){
        int n=0;try{Object v=pointOnLineLinksField==null?null:pointOnLineLinksField.get(this);if(!(v instanceof List))return 0;Iterator<?>it=((List<?>)v).iterator();while(it.hasNext()){
            Object r=it.next(),ep=obj(r,"endpoint"),line=obj(ep,"line"),host=obj(r,"host");if(containsIdentity(s,line)||containsIdentity(s,host)){it.remove();n++;}
        }}catch(Exception ignored){}return n;
    }

    private int removeMidpointRelations(List<Object>s){
        int n=0;try{Object v=midpointRelationsField==null?null:midpointRelationsField.get(this);if(!(v instanceof List))return 0;Iterator<?>it=((List<?>)v).iterator();while(it.hasNext()){
            Object r=it.next(),line=obj(r,"endpointLine"),host=obj(r,"host");if(containsIdentity(s,line)||containsIdentity(s,host)){it.remove();n++;}
        }}catch(Exception ignored){}return n;
    }

    // ------------------------------------------------------------------
    // Solver passes
    // ------------------------------------------------------------------

    private void solveNow(int iterations){
        pruneSolverState();
        for(int i=0;i<iterations;i++){
            invoke(enforceBaseConstraintsMethod);
            invoke(enforcePointLinksMethod);
            invoke(enforceLabRelationsMethod);
            enforceConcentric();
            enforcePairAngles();
            enforceDrivingDimensions();
        }
    }

    private void enforceDrivingDimensions(){
        for(LineDrive d:new ArrayList<>(lineDrives.values()))d.enforce();
        for(Map.Entry<Object,Float>e:new ArrayList<>(radiusDrives.entrySet()))if(containsIdentity(entities(),e.getKey()))setFloat(e.getKey(),"r",Math.abs(e.getValue()));
        for(Map.Entry<Object,float[]>e:new ArrayList<>(rectDrives.entrySet()))enforceRect(e.getKey(),e.getValue());
        for(Map.Entry<Object,Float>e:new ArrayList<>(polygonRadiusDrives.entrySet()))enforcePolygonRadius(e.getKey(),e.getValue());
    }

    private void enforcePairAngles(){
        for(PairAngleDrive d:pairAngleDrives){
            if(!isLine(d.fixed)||!isLine(d.moving))continue;
            float target=normalize360(lineAngle(d.fixed)+d.signedDelta);float len=lineLength(d.moving);
            PointF a=endpoint(d.moving,0),b=endpoint(d.moving,1);if(a==null||b==null||len<1e-7f)continue;
            int pivot=commonEndpoint(d.moving,d.fixed);double r=Math.toRadians(target);float vx=len*(float)Math.cos(r),vy=len*(float)Math.sin(r);
            if(pivot==0)setEndpoint(d.moving,1,a.x+vx,a.y+vy);
            else if(pivot==1)setEndpoint(d.moving,0,b.x-vx,b.y-vy);
            else{float cx=(a.x+b.x)/2f,cy=(a.y+b.y)/2f;setEndpoint(d.moving,0,cx-vx/2f,cy-vy/2f);setEndpoint(d.moving,1,cx+vx/2f,cy+vy/2f);}
        }
    }

    private void enforceConcentric(){for(ConcentricDrive d:concentricDrives){setFloat(d.moving,"x",num(d.anchor,"x"));setFloat(d.moving,"y",num(d.anchor,"y"));}}

    private void enforceRect(Object e,float[]wh){
        PointF[]p=pointArrayRaw(e,"p");if(p==null||p.length<4||wh==null||wh.length<2)return;
        float w=wh[0],h=wh[1],cw=dist(p[0],p[1]),ch=dist(p[1],p[2]);if(cw<1e-7f||ch<1e-7f)return;
        float ux=(p[1].x-p[0].x)/cw,uy=(p[1].y-p[0].y)/cw,vx=(p[2].x-p[1].x)/ch,vy=(p[2].y-p[1].y)/ch;
        p[1].set(p[0].x+ux*w,p[0].y+uy*w);p[2].set(p[1].x+vx*h,p[1].y+vy*h);p[3].set(p[0].x+vx*h,p[0].y+vy*h);
    }

    private void enforcePolygonRadius(Object e,float target){
        List<PointF>p=pointsRef(e,"points");if(p.isEmpty())return;PointF c=centroid(p);float r=dist(c,p.get(0));if(r<1e-7f)return;float f=target/r;
        for(PointF q:p){q.x=c.x+(q.x-c.x)*f;q.y=c.y+(q.y-c.y)*f;}
    }

    private void pruneSolverState(){
        List<Object>all=entities();lineDrives.keySet().removeIf(e->!containsIdentity(all,e));radiusDrives.keySet().removeIf(e->!containsIdentity(all,e));rectDrives.keySet().removeIf(e->!containsIdentity(all,e));polygonRadiusDrives.keySet().removeIf(e->!containsIdentity(all,e));
        pairAngleDrives.removeIf(d->!containsIdentity(all,d.fixed)||!containsIdentity(all,d.moving));concentricDrives.removeIf(d->!containsIdentity(all,d.anchor)||!containsIdentity(all,d.moving));
    }

    private void invoke(Method m){try{if(m!=null)m.invoke(this);}catch(Exception ignored){}}

    // ------------------------------------------------------------------
    // Degrees of freedom
    // ------------------------------------------------------------------

    private IdentityHashMap<Object,Integer> computeDof(){
        List<Object>all=entities();IdentityHashMap<Object,Integer>map=new IdentityHashMap<>();
        for(Object e:all)if(isSketch(e))map.put(e,initialDof(e));
        for(int pass=0;pass<Math.max(4,all.size()+1);pass++){
            boolean changed=false;
            for(Object e:new ArrayList<>(map.keySet())){
                int next=entityDof(e,map);Integer old=map.get(e);if(old==null||old!=next){map.put(e,next);changed=true;}
            }
            if(!changed)break;
        }
        return map;
    }

    private int initialDof(Object e){if(isLockedEntity(e))return 0;String t=e.getClass().getSimpleName();if("LineEntity".equals(t))return 4;if("CircleEntity".equals(t))return 3;if("ArcEntity".equals(t))return 5;if("RectEntity".equals(t))return 5;if("PolygonEntity".equals(t))return 4;if("PolylineEntity".equals(t))return Math.max(1,points(e,"points").size()*2);return 1;}

    private int entityDof(Object e,IdentityHashMap<Object,Integer>map){
        if(isLockedEntity(e))return 0;String t=e.getClass().getSimpleName();
        if("LineEntity".equals(t))return lineDof(e,map);
        if("CircleEntity".equals(t))return circleDof(e,map,3);
        if("ArcEntity".equals(t))return circleDof(e,map,5);
        if("RectEntity".equals(t))return Math.max(0,5-(rectDrives.containsKey(e)?2:0));
        if("PolygonEntity".equals(t))return Math.max(0,4-(polygonRadiusDrives.containsKey(e)?1:0));
        return initialDof(e);
    }

    private int lineDof(Object line,IdentityHashMap<Object,Integer>map){
        int fixedEndpoints=0,slideEndpoints=0;
        for(int i=0;i<2;i++){
            if(endpointFixedByRelation(line,i,map))fixedEndpoints++;
            else if(endpointSlidesOnFullHost(line,i,map))slideEndpoints++;
        }
        if(fixedEndpoints>=2)return 0;
        boolean len=lineLengthConstrained(line);boolean dir=lineDirectionConstrained(line,map);
        int pos=fixedEndpoints==1?0:(slideEndpoints>0?1:2);
        int rot=dir?0:1;int size=len?0:1;
        if(tangentCompletesLine(line,map,fixedEndpoints))return 0;
        return Math.max(0,pos+rot+size);
    }

    private int circleDof(Object e,IdentityHashMap<Object,Integer>map,int base){
        int center=2;for(ConcentricDrive d:concentricDrives){if(d.moving==e&&dofOf(map,d.anchor)==0){center=0;break;}if(d.anchor==e&&dofOf(map,d.moving)==0){center=0;break;}}
        boolean radius=radiusDrives.containsKey(e)||equalContains(e);int extra=base==3?1:3;return center+Math.max(0,extra-(radius?1:0));
    }

    private boolean lineLengthConstrained(Object line){LineDrive d=lineDrives.get(line);return(d!=null&&!Float.isNaN(d.length))||equalContains(line);}
    private boolean lineDirectionConstrained(Object line,IdentityHashMap<Object,Integer>map){
        LineDrive d=lineDrives.get(line);if(d!=null&&!Float.isNaN(d.angle))return true;if(axisContains(line))return true;
        for(Object r:list(lineRelationsField)){Object a=obj(r,"a"),b=obj(r,"b");if(a==line&&dofOf(map,b)==0)return true;if(b==line&&dofOf(map,a)==0)return true;}
        for(PairAngleDrive p:pairAngleDrives){if(p.moving==line&&dofOf(map,p.fixed)==0)return true;}
        return false;
    }

    private boolean endpointFixedByRelation(Object line,int index,IdentityHashMap<Object,Integer>map){
        for(Object c:list(coincidenceLinksField)){
            Object a=obj(c,"a"),b=obj(c,"b"),al=obj(a,"line"),bl=obj(b,"line");int ai=intField(a,"index",-1),bi=intField(b,"index",-1);
            if(al==line&&ai==index&&dofOf(map,bl)==0)return true;if(bl==line&&bi==index&&dofOf(map,al)==0)return true;
        }
        for(Object m:list(midpointRelationsField)){Object el=obj(m,"endpointLine"),host=obj(m,"host");int ei=intField(m,"endpointIndex",-1);if(el==line&&ei==index&&dofOf(map,host)==0)return true;}
        return false;
    }

    private boolean endpointSlidesOnFullHost(Object line,int index,IdentityHashMap<Object,Integer>map){
        for(Object p:list(pointOnLineLinksField)){Object ep=obj(p,"endpoint"),el=obj(ep,"line"),host=obj(p,"host");int ei=intField(ep,"index",-1);if(el==line&&ei==index&&dofOf(map,host)==0)return true;}
        return false;
    }

    private boolean tangentCompletesLine(Object line,IdentityHashMap<Object,Integer>map,int fixedEndpoints){
        if(fixedEndpoints<1)return false;
        try{Field f=field(ShaprLabCanvasView.class,"tangentRelations");for(Object t:list(f)){Object l=obj(t,"line"),curve=obj(t,"curve");if(l==line&&dofOf(map,curve)==0)return true;}}catch(Exception ignored){}
        return false;
    }

    private boolean equalContains(Object e){for(Object r:list(equalRelationsField)){Object items=obj(r,"items");if(items instanceof List&&containsIdentity((List<?>)items,e))return true;}return false;}
    private boolean axisContains(Object e){Object m=value(axisLocksField);return m instanceof Map&&((Map<?,?>)m).containsKey(e);}
    private int dofOf(IdentityHashMap<Object,Integer>map,Object e){Integer d=map.get(e);return d==null?99:d;}

    // ------------------------------------------------------------------
    // State/status rendering using solved DOF
    // ------------------------------------------------------------------

    @Override
    protected void onDraw(Canvas canvas){
        solveNow(4);
        super.onDraw(canvas);
        solveNow(2);
        if (!is3DOverview()) drawSolverErrors(canvas);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event){boolean h=super.onTouchEvent(event);if(event.getActionMasked()==MotionEvent.ACTION_UP||event.getActionMasked()==MotionEvent.ACTION_CANCEL){solveNow(5);dispatchWorkspaceState();invalidate();}return h;}

    @Override
    public String selectedInfo(){
        String base=super.selectedInfo();base=base.replaceAll(" \\| (Fully-defined|Under-defined|Partially-defined|Sketch Error)$","");
        List<Object>s=selection();if(s.isEmpty())return base;IdentityHashMap<Object,Integer>d=computeDof();int total=0;boolean err=false;for(Object e:s){total+=dofOf(d,e);if(hasSolverError(e))err=true;}
        String state=err?"Sketch Error":total==0?"Fully-defined":"Under-defined";return base+" | "+state+" | DOF "+total;
    }

    @Override
    public String sketchStateSummary(){
        IdentityHashMap<Object,Integer>d=computeDof();int full=0,under=0,error=0,totalDof=0;for(Object e:d.keySet()){if(!isVisible(e))continue;if(hasSolverError(e))error++;else if(dofOf(d,e)==0)full++;else{under++;totalDof+=dofOf(d,e);}}
        return "Fully-defined: "+full+"\nUnder-defined: "+under+"\nRemaining DOF: "+totalDof+"\nErrors: "+error+"\n\nDriving dimensions: "+(lineDrives.size()+radiusDrives.size()+rectDrives.size()+polygonRadiusDrives.size())+"\nConcentric: "+concentricDrives.size()+"\nAngle drives: "+pairAngleDrives.size();
    }

    private void drawSolverErrors(Canvas c){
        float scale=floatField(viewScaleField,1f),k=PX_PER_MM*Math.max(.0001f,scale),ox=floatField(offsetXField,0f),oy=floatField(offsetYField,0f);
        solverStroke.setStrokeWidth(Math.max(1.8f,1.65f*getResources().getDisplayMetrics().density));
        solverStroke.setColor(ERROR);solverPoint.setColor(ERROR);
        for(Object e:computeDof().keySet())if(isVisible(e)&&hasSolverError(e))drawOverlay(c,e,k,ox,oy);
    }

    private boolean hasSolverError(Object e){
        LineDrive ld=lineDrives.get(e);if(ld!=null){if(!Float.isNaN(ld.length)&&Math.abs(lineLength(e)-ld.length)>.05f)return true;if(!Float.isNaN(ld.angle)&&angleDistance(lineAngle(e),ld.angle)>.25f)return true;}
        Float r=radiusDrives.get(e);if(r!=null&&Math.abs(Math.abs(num(e,"r"))-r)>.05f)return true;
        for(ConcentricDrive d:concentricDrives)if((d.anchor==e||d.moving==e)&&dist(num(d.anchor,"x"),num(d.anchor,"y"),num(d.moving,"x"),num(d.moving,"y"))>.05f)return true;
        return false;
    }

    private void drawOverlay(Canvas c,Object e,float k,float ox,float oy){
        String t=e.getClass().getSimpleName();
        if("LineEntity".equals(t)){PointF a=endpoint(e,0),b=endpoint(e,1);if(a!=null&&b!=null){c.drawLine(sx(a.x,k,ox),sy(a.y,k,oy),sx(b.x,k,ox),sy(b.y,k,oy),solverStroke);dot(c,a,k,ox,oy);dot(c,b,k,ox,oy);}return;}
        if("CircleEntity".equals(t)){float x=num(e,"x"),y=num(e,"y"),r=Math.abs(num(e,"r"));c.drawCircle(sx(x,k,ox),sy(y,k,oy),r*k,solverStroke);dot(c,new PointF(x,y),k,ox,oy);return;}
        if("ArcEntity".equals(t)){float x=num(e,"x"),y=num(e,"y"),r=Math.abs(num(e,"r"));c.drawArc(new RectF(sx(x-r,k,ox),sy(y-r,k,oy),sx(x+r,k,ox),sy(y+r,k,oy)),num(e,"start"),num(e,"sweep"),false,solverStroke);dot(c,new PointF(x,y),k,ox,oy);return;}
        if("RectEntity".equals(t)){PointF[]p=pointArrayRaw(e,"p");if(p!=null)drawPoints(c,arrayToList(p),true,k,ox,oy);return;}
        if("PolygonEntity".equals(t)){drawPoints(c,points(e,"points"),true,k,ox,oy);return;}
        if("PolylineEntity".equals(t))drawPoints(c,points(e,"points"),bool(e,"closed"),k,ox,oy);
    }

    private void drawPoints(Canvas c,List<PointF>p,boolean closed,float k,float ox,float oy){if(p==null||p.size()<2)return;for(int i=1;i<p.size();i++)c.drawLine(sx(p.get(i-1).x,k,ox),sy(p.get(i-1).y,k,oy),sx(p.get(i).x,k,ox),sy(p.get(i).y,k,oy),solverStroke);if(closed&&p.size()>2)c.drawLine(sx(p.get(p.size()-1).x,k,ox),sy(p.get(p.size()-1).y,k,oy),sx(p.get(0).x,k,ox),sy(p.get(0).y,k,oy),solverStroke);for(PointF q:p)dot(c,q,k,ox,oy);}
    private void dot(Canvas c,PointF p,float k,float ox,float oy){c.drawCircle(sx(p.x,k,ox),sy(p.y,k,oy),4.5f*getResources().getDisplayMetrics().density,solverPoint);}

    // ------------------------------------------------------------------
    // Lifetime
    // ------------------------------------------------------------------

    @Override public void clearAll(){super.clearAll();clearSolver();}
    @Override public void undo(){super.undo();clearSolver();}
    @Override public void deleteSelected(){List<Object>s=selection();super.deleteSelected();for(Object e:s){lineDrives.remove(e);radiusDrives.remove(e);rectDrives.remove(e);polygonRadiusDrives.remove(e);}pairAngleDrives.removeIf(d->containsIdentity(s,d.fixed)||containsIdentity(s,d.moving));concentricDrives.removeIf(d->containsIdentity(s,d.anchor)||containsIdentity(s,d.moving));}
    private void clearSolver(){lineDrives.clear();radiusDrives.clear();rectDrives.clear();polygonRadiusDrives.clear();pairAngleDrives.clear();concentricDrives.clear();}
    private void removePairAnglesFor(Object e){pairAngleDrives.removeIf(d->d.fixed==e||d.moving==e);}

    // ------------------------------------------------------------------
    // Reflection / geometry helpers
    // ------------------------------------------------------------------

    @SuppressWarnings("unchecked") private List<Object> entities(){try{Object v=entitiesField==null?null:entitiesField.get(this);return v instanceof List?(List<Object>)v:new ArrayList<>();}catch(Exception e){return new ArrayList<>();}}
    @SuppressWarnings("unchecked") private List<Object> selection(){try{Object v=selectedObjectsField==null?null:selectedObjectsField.get(this);if(v instanceof List&&!((List<?>)v).isEmpty())return new ArrayList<>((List<Object>)v);Object one=selectedField==null?null:selectedField.get(this);List<Object>o=new ArrayList<>();if(one!=null)o.add(one);return o;}catch(Exception e){return new ArrayList<>();}}
    private Object singleSelected(){List<Object>s=selection();return s.size()==1?s.get(0):null;}
    private List<Object> selectedLines(){List<Object>o=new ArrayList<>();for(Object e:selection())if(isLine(e))o.add(e);return o;}
    private List<Object> selectedCurves(){List<Object>o=new ArrayList<>();for(Object e:selection())if(isCurve(e))o.add(e);return o;}
    private boolean isLocked(List<Object>s){for(Object e:s)if(isLockedEntity(e))return true;return false;}
    private boolean isLockedEntity(Object e){try{return isEntityLockedMethod!=null&&Boolean.TRUE.equals(isEntityLockedMethod.invoke(this,e));}catch(Exception x){return false;}}
    private boolean isVisible(Object e){try{return isVisibleMethod==null||Boolean.TRUE.equals(isVisibleMethod.invoke(this,e));}catch(Exception x){return true;}}
    private void saveUndo(){try{if(saveUndoMethod!=null)saveUndoMethod.invoke(this);}catch(Exception ignored){}}
    private Object value(Field f){try{return f==null?null:f.get(this);}catch(Exception e){return null;}}
    @SuppressWarnings("unchecked") private List<Object> list(Field f){Object v=value(f);return v instanceof List?(List<Object>)v:new ArrayList<>();}
    private static Object obj(Object o,String n){try{Field f=findField(o==null?null:o.getClass(),n);return f==null?null:f.get(o);}catch(Exception e){return null;}}
    private static int intField(Object o,String n,int fallback){try{Field f=findField(o==null?null:o.getClass(),n);return f==null?fallback:f.getInt(o);}catch(Exception e){return fallback;}}
    private static Field findField(Class<?> c,String n){for(Class<?>x=c;x!=null;x=x.getSuperclass())try{Field f=x.getDeclaredField(n);f.setAccessible(true);return f;}catch(Exception ignored){}return null;}
    private String invokePrivateString(Class<?>owner,String name){try{Method m=owner.getDeclaredMethod(name);m.setAccessible(true);Object r=m.invoke(this);return r==null?"":String.valueOf(r);}catch(Exception e){return"این Constraint برای انتخاب فعلی قابل اجرا نیست";}}
    private void toast(String s){Toast.makeText(getContext(),s,Toast.LENGTH_SHORT).show();}

    private boolean hasDisconnectableConnection(List<Object>s){
        for(Object c:list(coincidenceLinksField)){Object a=obj(obj(c,"a"),"line"),b=obj(obj(c,"b"),"line");if(containsIdentity(s,a)||containsIdentity(s,b))return true;}
        for(Object p:list(pointOnLineLinksField)){Object a=obj(obj(p,"endpoint"),"line"),b=obj(p,"host");if(containsIdentity(s,a)||containsIdentity(s,b))return true;}
        for(Object m:list(midpointRelationsField)){Object a=obj(m,"endpointLine"),b=obj(m,"host");if(containsIdentity(s,a)||containsIdentity(s,b))return true;}return false;
    }

    private static boolean isLine(Object e){return e!=null&&"LineEntity".equals(e.getClass().getSimpleName());}
    private static boolean isCurve(Object e){if(e==null)return false;String n=e.getClass().getSimpleName();return"CircleEntity".equals(n)||"ArcEntity".equals(n);}
    private static boolean isSketch(Object e){if(e==null)return false;String n=e.getClass().getSimpleName();return"LineEntity".equals(n)||"CircleEntity".equals(n)||"ArcEntity".equals(n)||"RectEntity".equals(n)||"PolygonEntity".equals(n)||"PolylineEntity".equals(n);}
    private static PointF endpoint(Object e,int i){if(!isLine(e))return null;return i==0?new PointF(num(e,"x1"),num(e,"y1")):new PointF(num(e,"x2"),num(e,"y2"));}
    private static float lineLength(Object e){PointF a=endpoint(e,0),b=endpoint(e,1);return a==null||b==null?0:dist(a,b);}
    private static float lineAngle(Object e){PointF a=endpoint(e,0),b=endpoint(e,1);return a==null||b==null?0:normalize360((float)Math.toDegrees(Math.atan2(b.y-a.y,b.x-a.x)));}
    private static void setEndpoint(Object e,int i,float x,float y){if(i==0){setFloat(e,"x1",x);setFloat(e,"y1",y);}else{setFloat(e,"x2",x);setFloat(e,"y2",y);}}
    private static int commonEndpoint(Object moving,Object fixed){for(int mi=0;mi<2;mi++){PointF m=endpoint(moving,mi);for(int fi=0;fi<2;fi++){PointF f=endpoint(fixed,fi);if(m!=null&&f!=null&&dist(m,f)<.15f)return mi;}}return-1;}
    private static float num(Object o,String n){try{Field f=findField(o==null?null:o.getClass(),n);Object v=f==null?null:f.get(o);return v instanceof Number?((Number)v).floatValue():0;}catch(Exception e){return 0;}}
    private static void setFloat(Object o,String n,float v){try{Field f=findField(o==null?null:o.getClass(),n);if(f!=null)f.setFloat(o,v);}catch(Exception ignored){}}
    private static boolean bool(Object o,String n){try{Field f=findField(o==null?null:o.getClass(),n);return f!=null&&f.getBoolean(o);}catch(Exception e){return false;}}
    private static PointF[] pointArrayRaw(Object o,String n){try{Field f=findField(o.getClass(),n);Object v=f==null?null:f.get(o);return v instanceof PointF[]?(PointF[])v:null;}catch(Exception e){return null;}}
    @SuppressWarnings("unchecked") private static List<PointF> pointsRef(Object o,String n){try{Field f=findField(o.getClass(),n);Object v=f==null?null:f.get(o);return v instanceof List?(List<PointF>)v:new ArrayList<>();}catch(Exception e){return new ArrayList<>();}}
    private static List<PointF> points(Object o,String n){List<PointF>out=new ArrayList<>();for(PointF p:pointsRef(o,n))out.add(new PointF(p.x,p.y));return out;}
    private static List<PointF> arrayToList(PointF[]p){List<PointF>o=new ArrayList<>();if(p!=null)for(PointF q:p)o.add(q);return o;}
    private static PointF centroid(List<PointF>p){float x=0,y=0;if(p.isEmpty())return new PointF();for(PointF q:p){x+=q.x;y+=q.y;}return new PointF(x/p.size(),y/p.size());}
    private static float dist(PointF a,PointF b){return(float)Math.hypot(b.x-a.x,b.y-a.y);}
    private static float dist(float x1,float y1,float x2,float y2){return(float)Math.hypot(x2-x1,y2-y1);}
    private static float normalize360(float a){a%=360f;return a<0?a+360f:a;}
    private static float normalizeDelta(float a){while(a>180)a-=360;while(a<-180)a+=360;return a;}
    private static float angleDistance(float a,float b){return Math.abs(normalizeDelta(a-b));}
    private static boolean containsIdentity(List<?>l,Object e){for(Object x:l)if(x==e)return true;return false;}
    private float floatField(Field f,float fallback){try{return f==null?fallback:f.getFloat(this);}catch(Exception e){return fallback;}}
    private static float sx(float x,float k,float ox){return x*k+ox;}
    private static float sy(float y,float k,float oy){return y*k+oy;}
}

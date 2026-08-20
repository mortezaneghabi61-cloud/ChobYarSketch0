package ir.chobyar.sketch;

import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.PointF;
import android.view.MotionEvent;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Shapr3D-aligned off-plane snapping layer.
 *
 * Adds the two Snaps / Guides options that require real 3D model geometry:
 * 3D Guidepoints and Distant Edges. Data comes from the active exact OCCT body
 * handles. Body triangulation is converted into logical CAD references by
 * OcctSnapTopology rather than exposing raw triangle vertices.
 */
public class Shapr3DGuideCadCanvasView extends ShaprSnappingCadCanvasView {
    private static final String PREFS="shapr_snap_settings";
    private static final float PX_PER_MM=3f;
    private static final float POINT_HIT_PX=30f;
    private static final float EDGE_HIT_PX=26f;
    private static final float OFF_PLANE_TOL_MM=.05f;

    private boolean snap3DGuidepoints=true;
    private boolean snapDistantEdges=true;

    private Field viewScaleField,offsetXField,offsetYField,activePlaneField,nativeByBodyField,selectedBodyProjectField;
    private Field baseGridField,baseGuidelinesField,baseGuidepointsField,baseShowPointsField,baseHintsField;
    private Method baseSaveSettingsMethod,baseSketchGestureMethod,baseFindBestSnapMethod;

    private final Map<Long,OcctSnapTopology.Result> topologyCache=new HashMap<>();
    private ExternalCandidate externalCandidate;

    private final Paint pointFill=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pointStroke=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint distantPaint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hintFill=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hintText=new Paint(Paint.ANTI_ALIAS_FLAG);

    interface ProjectDescriptorProvider { double[] descriptors(Object sourceBody); }
    private static final class ProjectReference {
        final int id;final String tag;final Object sourceBody;final Geometry3D.Plane3D plane;final ProjectDescriptorProvider testProvider;
        boolean active=true;
        ProjectReference(int id,String tag,Object sourceBody,Geometry3D.Plane3D plane,ProjectDescriptorProvider testProvider){
            this.id=id;this.tag=tag;this.sourceBody=sourceBody;this.plane=plane;this.testProvider=testProvider;
        }
    }
    private final List<ProjectReference> projectReferences=new ArrayList<>();
    private int projectReferenceSerial=1;
    private boolean refreshingProjectReferences=false;

    public Shapr3DGuideCadCanvasView(Context context){
        super(context);
        initReflection();
        SharedPreferences p=context.getSharedPreferences(PREFS,Context.MODE_PRIVATE);
        snap3DGuidepoints=p.getBoolean("guide3d",true);
        snapDistantEdges=p.getBoolean("distant_edges",true);

        pointFill.setStyle(Paint.Style.FILL);pointFill.setColor(Color.argb(242,255,255,255));
        pointStroke.setStyle(Paint.Style.STROKE);pointStroke.setStrokeWidth(1.7f*density());pointStroke.setColor(Color.rgb(145,78,202));
        distantPaint.setStyle(Paint.Style.STROKE);distantPaint.setStrokeWidth(1.45f*density());distantPaint.setColor(Color.argb(155,145,78,202));
        distantPaint.setPathEffect(new DashPathEffect(new float[]{6f*density(),5f*density()},0));
        hintFill.setStyle(Paint.Style.FILL);hintFill.setColor(Color.argb(244,255,255,255));
        hintText.setColor(Color.rgb(88,43,148));hintText.setTextAlign(Paint.Align.CENTER);hintText.setTextSize(12.5f*getResources().getDisplayMetrics().scaledDensity);
    }

    private void initReflection(){
        try{
            viewScaleField=field(CadCanvasView.class,"viewScale");
            offsetXField=field(CadCanvasView.class,"offsetX");
            offsetYField=field(CadCanvasView.class,"offsetY");
            activePlaneField=field(SpatialCadCanvasView.class,"activePlane");
            nativeByBodyField=field(OcctModelCadCanvasView.class,"nativeByBody");
            selectedBodyProjectField=field(SolidCadCanvasView.class,"selectedBody");

            baseGridField=field(ShaprSnappingCadCanvasView.class,"snapGrid");
            baseGuidelinesField=field(ShaprSnappingCadCanvasView.class,"snapSketchGuidelines");
            baseGuidepointsField=field(ShaprSnappingCadCanvasView.class,"snapSketchGuidepoints");
            baseShowPointsField=field(ShaprSnappingCadCanvasView.class,"showGuidepoints");
            baseHintsField=field(ShaprSnappingCadCanvasView.class,"showHints");
            baseSaveSettingsMethod=ShaprSnappingCadCanvasView.class.getDeclaredMethod("saveSettings");baseSaveSettingsMethod.setAccessible(true);
            baseSketchGestureMethod=ShaprSnappingCadCanvasView.class.getDeclaredMethod("isSketchCreationGesture");baseSketchGestureMethod.setAccessible(true);
            baseFindBestSnapMethod=ShaprSnappingCadCanvasView.class.getDeclaredMethod("findBestSnap",PointF.class,boolean.class);baseFindBestSnapMethod.setAccessible(true);
        }catch(Exception ignored){}
    }

    private static Field field(Class<?> c,String name)throws Exception{Field f=c.getDeclaredField(name);f.setAccessible(true);return f;}

    @Override
    public void showShaprSnappingOptions(){
        String[] items={"Grid","Sketch Guidelines","Sketch Guidepoints","3D Guidepoints","Distant Edges","Show Guide Points","Snapping Hints"};
        boolean[] checked={baseBool(baseGridField,true),baseBool(baseGuidelinesField,true),baseBool(baseGuidepointsField,true),snap3DGuidepoints,snapDistantEdges,baseBool(baseShowPointsField,true),baseBool(baseHintsField,true)};
        new AlertDialog.Builder(getContext()).setTitle("Snaps / Guides")
                .setMultiChoiceItems(items,checked,(d,w,on)->{
                    if(w==0)setBase(baseGridField,on);
                    else if(w==1)setBase(baseGuidelinesField,on);
                    else if(w==2)setBase(baseGuidepointsField,on);
                    else if(w==3)snap3DGuidepoints=on;
                    else if(w==4)snapDistantEdges=on;
                    else if(w==5)setBase(baseShowPointsField,on);
                    else setBase(baseHintsField,on);
                    saveAllSettings();invalidate();
                })
                .setPositiveButton("بستن",null).show();
    }

    private boolean baseBool(Field f,boolean fallback){try{return f==null?fallback:f.getBoolean(this);}catch(Exception e){return fallback;}}
    private void setBase(Field f,boolean v){try{if(f!=null)f.setBoolean(this,v);}catch(Exception ignored){}}
    private void saveAllSettings(){
        try{if(baseSaveSettingsMethod!=null)baseSaveSettingsMethod.invoke(this);}catch(Exception ignored){}
        getContext().getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit().putBoolean("guide3d",snap3DGuidepoints).putBoolean("distant_edges",snapDistantEdges).apply();
    }

    @Override
    public String executeCommand(String raw){
        if(raw!=null){
            String s=raw.trim().replace(',',' ');
            if("PROJECT3D".equalsIgnoreCase(s)||"PROJECTBODY".equalsIgnoreCase(s))return projectExactBodyEdges();
            if("PROJECTREF".equalsIgnoreCase(s)||"PROJECTASSOC".equalsIgnoreCase(s))return projectSelectedBodyReference();
        }
        return super.executeCommand(raw);
    }

    /** Manual 26.100 Project: exact OCCT Line/Circle/Arc edges -> Construction sketch geometry. */
    public String projectExactBodyEdges(){
        List<double[]> batches=new ArrayList<>();
        for(long handle:nativeHandles()){
            double[] d=NativeBRepKernel.occtEdgeDescriptors(handle);
            if(d!=null&&d.length>=NativeBRepKernel.OCCT_EDGE_RECORD_SIZE)batches.add(d);
        }
        if(batches.isEmpty())return "Project 3D • Shape دقیق OCCT آماده نیست";
        return projectDescriptorBatches(batches);
    }

    /** Package-visible deterministic mapper used by x86 emulator tests. */
    String projectExactDescriptorsForTest(double[] descriptors){
        List<double[]> batches=new ArrayList<>();
        if(descriptors!=null)batches.add(descriptors);
        return projectDescriptorBatches(batches);
    }

    private String projectDescriptorBatches(List<double[]> batches){
        return projectDescriptorBatches(batches,activePlane(),"",true);
    }

    private String projectDescriptorBatches(List<double[]> batches,Geometry3D.Plane3D plane,String referenceTag,boolean saveUndo){
        if(plane==null)return "Project 3D • Sketch Plane فعال نیست";
        Set<String> lineKeys=new HashSet<>(),circleKeys=new HashSet<>(),arcKeys=new HashSet<>();
        int lines=0,circles=0,arcs=0,unsupported=0,skipped=0;
        boolean undoSaved=false,changed=false;
        final int n=NativeBRepKernel.OCCT_EDGE_RECORD_SIZE;
        for(double[] d:batches){
            if(d==null)continue;
            for(int i=0;i+n-1<d.length;i+=n){
                int kind=(int)Math.round(d[i]);
                Geometry3D.Vec3 p1=new Geometry3D.Vec3((float)d[i+2],(float)d[i+3],(float)d[i+4]);
                Geometry3D.Vec3 p2=new Geometry3D.Vec3((float)d[i+5],(float)d[i+6],(float)d[i+7]);
                if(kind==NativeBRepKernel.OCCT_EDGE_LINE){
                    PointF a=toLocal(plane,p1),b=toLocal(plane,p2);
                    if(Math.hypot(a.x-b.x,a.y-b.y)<1.0e-4){skipped++;continue;}
                    String key=projectLineKey(a,b);if(!lineKeys.add(key)){skipped++;continue;}
                    if(saveUndo&&!undoSaved){coreSaveUndo();undoSaved=true;}
                    Entity made=coreAddConstructionLine(a.x,a.y,b.x,b.y);if(!referenceTag.isEmpty())coreSetReferenceTag(made,referenceTag);lines++;changed=true;continue;
                }
                if(kind==NativeBRepKernel.OCCT_EDGE_CIRCLE||kind==NativeBRepKernel.OCCT_EDGE_ARC){
                    Geometry3D.Vec3 center3=new Geometry3D.Vec3((float)d[i+8],(float)d[i+9],(float)d[i+10]);
                    Geometry3D.Vec3 normal3=new Geometry3D.Vec3((float)d[i+11],(float)d[i+12],(float)d[i+13]);
                    float radius=Math.abs((float)d[i+14]);
                    if(radius<1.0e-5f||normal3.length()<1.0e-6f){unsupported++;continue;}
                    float alignment=normal3.normalized().dot(plane.normal.normalized());
                    if(Math.abs(Math.abs(alignment)-1f)>1.0e-4f){unsupported++;continue;}
                    PointF c=toLocal(plane,center3);
                    if(kind==NativeBRepKernel.OCCT_EDGE_CIRCLE){
                        String key=projectCircleKey(c,radius);if(!circleKeys.add(key)){skipped++;continue;}
                        if(saveUndo&&!undoSaved){coreSaveUndo();undoSaved=true;}
                        Entity made=coreAddConstructionCircle(c.x,c.y,radius);if(!referenceTag.isEmpty())coreSetReferenceTag(made,referenceTag);circles++;changed=true;continue;
                    }
                    PointF startPoint=toLocal(plane,p1);
                    float start=(float)Math.toDegrees(Math.atan2(startPoint.y-c.y,startPoint.x-c.x));
                    float span=(float)Math.toDegrees(Math.abs(d[i+16]-d[i+15]));
                    if(!(span>1.0e-4f)||span>=359.999f){unsupported++;continue;}
                    float sweep=alignment>=0f?span:-span;
                    String key=projectArcKey(c,radius,start,sweep);if(!arcKeys.add(key)){skipped++;continue;}
                    if(saveUndo&&!undoSaved){coreSaveUndo();undoSaved=true;}
                    Entity made=coreAddConstructionArc(c.x,c.y,radius,start,sweep);if(!referenceTag.isEmpty())coreSetReferenceTag(made,referenceTag);arcs++;changed=true;continue;
                }
                unsupported++;
            }
        }
        if(changed)invalidate();
        return "Project 3D • Line "+lines+" • Circle "+circles+" • Arc "+arcs+" • Unsupported "+unsupported+" • Skipped "+skipped;
    }

    /** Creates an associative Project tied to the currently selected History Body. */
    public String projectSelectedBodyReference(){
        Object body=selectedBodyForProject();if(body==null)return "Project Reference • اول Body را انتخاب کن";
        return createProjectReference(body,activePlane(),null);
    }

    String projectAssociativeForTest(Object sourceBody,ProjectDescriptorProvider provider){
        if(sourceBody==null||provider==null)return "Project Reference • منبع تست نامعتبر";
        return createProjectReference(sourceBody,activePlane(),provider);
    }

    private String createProjectReference(Object sourceBody,Geometry3D.Plane3D plane,ProjectDescriptorProvider provider){
        if(plane==null)return "Project Reference • Sketch Plane فعال نیست";
        double[] d=provider==null?nativeDescriptorsForBody(sourceBody):provider.descriptors(sourceBody);
        if(d==null||d.length<NativeBRepKernel.OCCT_EDGE_RECORD_SIZE)return "Project Reference • Shape دقیق منبع آماده نیست";
        int id=projectReferenceSerial++;String tag="PROJECTREF:"+id;
        ProjectReference ref=new ProjectReference(id,tag,sourceBody,plane,provider);projectReferences.add(ref);
        List<double[]> batches=new ArrayList<>();batches.add(d);
        String result=projectDescriptorBatches(batches,plane,tag,true);
        if(coreCountReferenceTag(tag)==0){projectReferences.remove(ref);return result;}
        return result+" • Associative #"+id;
    }

    private void refreshProjectReferences(){
        if(refreshingProjectReferences||projectReferences.isEmpty())return;
        refreshingProjectReferences=true;
        try{
            for(ProjectReference ref:projectReferences){
                if(!ref.active)continue;
                coreRemoveReferenceTag(ref.tag);
                double[] d=ref.testProvider==null?nativeDescriptorsForBody(ref.sourceBody):ref.testProvider.descriptors(ref.sourceBody);
                if(d==null||d.length<NativeBRepKernel.OCCT_EDGE_RECORD_SIZE)continue;
                List<double[]> batches=new ArrayList<>();batches.add(d);
                projectDescriptorBatches(batches,ref.plane,ref.tag,false);
            }
            invalidate();
        }finally{refreshingProjectReferences=false;}
    }

    private void syncProjectActivationFromSketch(){for(ProjectReference ref:projectReferences)ref.active=coreHasReferenceTag(ref.tag);}
    int associativeProjectEntityCountForTest(){int n=0;for(ProjectReference ref:projectReferences)if(ref.active)n+=coreCountReferenceTag(ref.tag);return n;}

    @Override
    public String rebuildHistory(){String result=super.rebuildHistory();refreshProjectReferences();return result;}

    @Override
    public String undoLastFeature(){String result=super.undoLastFeature();refreshProjectReferences();return result;}

    @Override
    public void undo(){super.undo();syncProjectActivationFromSketch();}

    @Override
    public boolean redoSketch(){boolean ok=super.redoSketch();if(ok)syncProjectActivationFromSketch();return ok;}

    @Override
    public void clearAll(){super.clearAll();projectReferences.clear();projectReferenceSerial=1;refreshingProjectReferences=false;}

    private static String projectLineKey(PointF a,PointF b){String x=projectPointKey(a),y=projectPointKey(b);return x.compareTo(y)<=0?x+"|"+y:y+"|"+x;}
    private static String projectPointKey(PointF p){return Math.round(p.x*100f)+":"+Math.round(p.y*100f);}
    private static String projectCircleKey(PointF c,float r){return projectPointKey(c)+":"+Math.round(r*100f);}
    private static String projectArcKey(PointF c,float r,float start,float sweep){return projectCircleKey(c,r)+":"+Math.round(start*100f)+":"+Math.round(sweep*100f);}

    @Override
    public boolean onTouchEvent(MotionEvent event){
        if(event.getPointerCount()>1||is3DOverview()){
            externalCandidate=null;return super.onTouchEvent(event);
        }
        boolean sketchGesture=isSketchGesture();
        if(!sketchGesture||!isSnapEnabled()||(!snap3DGuidepoints&&!snapDistantEdges)){
            externalCandidate=null;return super.onTouchEvent(event);
        }

        int action=event.getActionMasked();
        PointF raw=world(event.getX(),event.getY());
        ExternalCandidate ext=bestExternal(raw);
        Object base=baseCandidate(raw,action!=MotionEvent.ACTION_DOWN);
        if(ext!=null && beatsBase(ext,base)){
            externalCandidate=ext;
            PointF s=screen(ext.local);
            MotionEvent forwarded=MotionEvent.obtain(event);forwarded.setLocation(s.x,s.y);
            boolean handled=super.onTouchEvent(forwarded);forwarded.recycle();
            if(action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_CANCEL)lingerExternal(ext);
            invalidate();return handled;
        }
        externalCandidate=null;
        return super.onTouchEvent(event);
    }

    private boolean isSketchGesture(){try{return baseSketchGestureMethod!=null && Boolean.TRUE.equals(baseSketchGestureMethod.invoke(this));}catch(Exception e){return false;}}
    private Object baseCandidate(PointF raw,boolean directional){try{return baseFindBestSnapMethod==null?null:baseFindBestSnapMethod.invoke(this,raw,directional);}catch(Exception e){return null;}}
    private boolean beatsBase(ExternalCandidate ext,Object base){
        if(base==null)return true;
        try{
            Field df=findField(base.getClass(),"distancePx"),pf=findField(base.getClass(),"priority");
            if(df==null||pf==null)return true;
            float baseScore=df.getFloat(base)-pf.getInt(base)*2.4f;
            return ext.distancePx-ext.priority*2.4f<baseScore;
        }catch(Exception e){return true;}
    }

    private void lingerExternal(ExternalCandidate keep){
        postDelayed(()->{if(externalCandidate==keep){externalCandidate=null;invalidate();}},650L);
    }

    @Override
    protected void onDraw(Canvas canvas){
        super.onDraw(canvas);
        if(is3DOverview())return;
        // Off-plane topology is a transient snapping aid. Keeping every OCCT
        // edge and point visible while idle recreated the purple line forest
        // from the old workspace and obscured the user's sketch.
        // Merely choosing Line/Arc/etc. must not reveal every projected edge.
        // Show these references only during the actual pen/finger gesture.
        if(!isSnapGestureActive()&&externalCandidate==null)return;
        if(snapDistantEdges)drawDistantEdges(canvas);
        if(snap3DGuidepoints && baseBool(baseShowPointsField,true))draw3DGuidepoints(canvas);
        if(externalCandidate!=null && baseBool(baseHintsField,true))drawExternalHint(canvas,externalCandidate);
    }

    private ExternalCandidate bestExternal(PointF raw){
        ExternalCandidate best=null;
        if(snap3DGuidepoints)best=better(best,nearest3DGuidepoint(raw));
        if(snapDistantEdges)best=better(best,nearestDistantEdge(raw));
        return best;
    }

    private ExternalCandidate nearest3DGuidepoint(PointF raw){
        Geometry3D.Plane3D plane=activePlane();if(plane==null)return null;
        ExternalCandidate best=null;
        for(long handle:nativeHandles()){
            OcctSnapTopology.Result r=topology(handle);
            for(OcctSnapTopology.Notable n:r.points){
                PointF local=toLocal(plane,n.p);float d=screenDistance(raw,local);
                if(d>POINT_HIT_PX*density())continue;
                String label;int priority;
                if(n.kind==OcctSnapTopology.HOLE_CENTER){label="Hole Center • مرکز سوراخ";priority=11;}
                else if(n.kind==OcctSnapTopology.VERTEX){label="3D Vertex • رأس";priority=10;}
                else if(n.kind==OcctSnapTopology.EDGE_MIDPOINT){label="3D Edge Midpoint • میانه لبه";priority=9;}
                else{label="Face Center • مرکز سطح";priority=8;}
                best=better(best,new ExternalCandidate(local,label,priority,d));
            }
        }
        return best;
    }

    private ExternalCandidate nearestDistantEdge(PointF raw){
        Geometry3D.Plane3D plane=activePlane();if(plane==null)return null;
        ExternalCandidate best=null;
        for(long handle:nativeHandles()){
            for(OcctSnapTopology.EdgeChain chain:topology(handle).edges){
                List<Geometry3D.Vec3> p=chain.points;
                for(int i=1;i<p.size();i++){
                    Geometry3D.Vec3 a=p.get(i-1),b=p.get(i);
                    if(Math.max(offPlane(plane,a),offPlane(plane,b))<=OFF_PLANE_TOL_MM)continue;
                    PointF la=toLocal(plane,a),lb=toLocal(plane,b);
                    if(screenDistance(la,lb)<2f)continue;
                    PointF q=nearestOnSegment(raw,la,lb);float d=screenDistance(raw,q);
                    if(d<=EDGE_HIT_PX*density())best=better(best,new ExternalCandidate(q,"Distant Edge • لبه خارج صفحه",7,d));
                }
            }
        }
        return best;
    }

    private void draw3DGuidepoints(Canvas c){
        Geometry3D.Plane3D plane=activePlane();if(plane==null)return;int count=0;
        for(long handle:nativeHandles())for(OcctSnapTopology.Notable n:topology(handle).points){
            if(count++>900)return;PointF s=screen(toLocal(plane,n.p));float r=n.kind==OcctSnapTopology.HOLE_CENTER?5f*density():3.8f*density();
            c.drawCircle(s.x,s.y,r,pointFill);c.drawCircle(s.x,s.y,r,pointStroke);
            if(n.kind==OcctSnapTopology.HOLE_CENTER){c.drawLine(s.x-r*1.5f,s.y,s.x+r*1.5f,s.y,pointStroke);c.drawLine(s.x,s.y-r*1.5f,s.x,s.y+r*1.5f,pointStroke);}
        }
    }

    private void drawDistantEdges(Canvas c){
        Geometry3D.Plane3D plane=activePlane();if(plane==null)return;int segments=0;
        for(long handle:nativeHandles())for(OcctSnapTopology.EdgeChain chain:topology(handle).edges){
            List<Geometry3D.Vec3> p=chain.points;
            for(int i=1;i<p.size();i++){
                if(segments++>2800)return;Geometry3D.Vec3 a=p.get(i-1),b=p.get(i);
                if(Math.max(offPlane(plane,a),offPlane(plane,b))<=OFF_PLANE_TOL_MM)continue;
                PointF sa=screen(toLocal(plane,a)),sb=screen(toLocal(plane,b));
                if(Math.hypot(sa.x-sb.x,sa.y-sb.y)>1.5)c.drawLine(sa.x,sa.y,sb.x,sb.y,distantPaint);
            }
        }
    }

    private void drawExternalHint(Canvas c,ExternalCandidate x){
        PointF p=screen(x.local);float pad=7f*density(),w=hintText.measureText(x.label)+pad*2,h=26f*density();
        float cx=clamp(p.x+w*.52f+12f*density(),w*.5f+4f,getWidth()-w*.5f-4f);float cy=clamp(p.y-24f*density(),h*.5f+4f,getHeight()-h*.5f-4f);
        c.drawRoundRect(cx-w/2,cy-h/2,cx+w/2,cy+h/2,8f*density(),8f*density(),hintFill);c.drawText(x.label,cx,cy-(hintText.ascent()+hintText.descent())/2,hintText);c.drawCircle(p.x,p.y,5.2f*density(),pointStroke);
    }

    private OcctSnapTopology.Result topology(long handle){
        pruneCache();OcctSnapTopology.Result r=topologyCache.get(handle);if(r==null){r=OcctSnapTopology.analyze(handle);topologyCache.put(handle,r);}return r;
    }

    private void pruneCache(){Set<Long> live=new HashSet<>(nativeHandlesRaw());topologyCache.keySet().retainAll(live);}

    private Object selectedBodyForProject(){try{return selectedBodyProjectField==null?null:selectedBodyProjectField.get(this);}catch(Exception e){return null;}}

    @SuppressWarnings("unchecked")
    private double[] nativeDescriptorsForBody(Object body){
        if(body==null)return new double[0];
        try{
            Object v=nativeByBodyField==null?null:nativeByBodyField.get(this);if(!(v instanceof Map))return new double[0];
            Object rec=((Map<Object,Object>)v).get(body);if(rec==null)return new double[0];
            Field h=findField(rec.getClass(),"handle");if(h==null)return new double[0];long handle=h.getLong(rec);
            return handle==0L?new double[0]:NativeBRepKernel.occtEdgeDescriptors(handle);
        }catch(Exception e){return new double[0];}
    }

    private List<Long> nativeHandles(){return nativeHandlesRaw();}
    @SuppressWarnings("unchecked")
    private List<Long> nativeHandlesRaw(){
        List<Long> out=new ArrayList<>();
        try{
            Object v=nativeByBodyField==null?null:nativeByBodyField.get(this);if(!(v instanceof Map))return out;
            for(Object rec:((Map<Object,Object>)v).values()){
                if(rec==null)continue;Field h=findField(rec.getClass(),"handle");if(h!=null){long x=h.getLong(rec);if(x!=0L&&!out.contains(x))out.add(x);}
            }
        }catch(Exception ignored){}
        return out;
    }

    private Geometry3D.Plane3D activePlane(){try{Object p=activePlaneField==null?null:activePlaneField.get(this);return p instanceof Geometry3D.Plane3D?(Geometry3D.Plane3D)p:Geometry3D.xy();}catch(Exception e){return Geometry3D.xy();}}
    private static PointF toLocal(Geometry3D.Plane3D p,Geometry3D.Vec3 world){Geometry3D.Vec3 d=world.sub(p.origin);return new PointF(d.dot(p.u),d.dot(p.v));}
    private static float offPlane(Geometry3D.Plane3D p,Geometry3D.Vec3 world){return Math.abs(world.sub(p.origin).dot(p.normal));}

    private PointF world(float sx,float sy){try{float k=PX_PER_MM*Math.max(.0001f,viewScaleField.getFloat(this));return new PointF((sx-offsetXField.getFloat(this))/k,(sy-offsetYField.getFloat(this))/k);}catch(Exception e){return new PointF(sx/PX_PER_MM,sy/PX_PER_MM);}}
    private PointF screen(PointF p){try{float k=PX_PER_MM*viewScaleField.getFloat(this);return new PointF(offsetXField.getFloat(this)+p.x*k,offsetYField.getFloat(this)+p.y*k);}catch(Exception e){return new PointF(p.x*PX_PER_MM,p.y*PX_PER_MM);}}
    private float screenDistance(PointF a,PointF b){PointF x=screen(a),y=screen(b);return(float)Math.hypot(x.x-y.x,x.y-y.y);}
    private static PointF nearestOnSegment(PointF p,PointF a,PointF b){float dx=b.x-a.x,dy=b.y-a.y,l2=dx*dx+dy*dy;if(l2<1e-9f)return new PointF(a.x,a.y);float t=((p.x-a.x)*dx+(p.y-a.y)*dy)/l2;t=clamp(t,0,1);return new PointF(a.x+t*dx,a.y+t*dy);}

    private ExternalCandidate better(ExternalCandidate a,ExternalCandidate b){if(b==null)return a;if(a==null)return b;return b.distancePx-b.priority*2.4f<a.distancePx-a.priority*2.4f?b:a;}
    private static Field findField(Class<?> c,String n){for(Class<?> x=c;x!=null;x=x.getSuperclass())try{Field f=x.getDeclaredField(n);f.setAccessible(true);return f;}catch(Exception ignored){}return null;}
    private float density(){return getResources().getDisplayMetrics().density;}
    private static float clamp(float v,float a,float b){return Math.max(a,Math.min(b,v));}

    private static final class ExternalCandidate{
        final PointF local;final String label;final int priority;final float distancePx;
        ExternalCandidate(PointF local,String label,int priority,float distancePx){this.local=local;this.label=label;this.priority=priority;this.distancePx=distancePx;}
    }
}

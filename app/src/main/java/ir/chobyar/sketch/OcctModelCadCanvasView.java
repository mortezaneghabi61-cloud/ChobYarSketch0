package ir.chobyar.sketch;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PointF;
import android.graphics.RectF;
import android.widget.Toast;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Production bridge between the app's Sketch/History model and OCCT TopoDS_Shape.
 *
 * Exact native history now covers Extrude, Revolve, Sweep, Loft and downstream
 * Union/Subtract/Intersect. Circle profiles remain analytic OCCT circles instead
 * of being converted to display polygons. OCCT returns the triangulation used by
 * this view, so the display mesh is derived from the same exact B-Rep shape.
 *
 * Direct Face/Edge edits are still replayed by the legacy editing layer. Bodies
 * with active Direct Edit features are deliberately not advertised as synced
 * native shapes until that edit stack is migrated, preventing stale geometry.
 */
public class OcctModelCadCanvasView extends NativeBRepCadCanvasView {

    private static final class NativeRecord {
        final long handle;
        final String kind;
        final double[] mesh;
        NativeRecord(long handle,String kind,double[] mesh){
            this.handle=handle;this.kind=kind;this.mesh=mesh==null?new double[0]:mesh;
        }
        int triangleCount(){return mesh.length/9;}
    }

    private static final class ProfileRecord {
        final int type;
        final double[] data;
        ProfileRecord(int type,double[] data){this.type=type;this.data=data;}
    }

    private final IdentityHashMap<Object,NativeRecord> nativeByBody=new IdentityHashMap<>();

    private Field historyField;
    private Field formHistoryField;
    private Field selectedBodyField;
    private Field overviewCardField;
    private Field directOpsByBodyField;

    private Method profileFromSourcesMethod;
    private Method advancedProfileMethod;
    private Method path3DMethod;
    private Method axisForMethod;
    private Method projectMethod;

    private String lastHistorySignature="";
    private int nativeFeatureCount=0;
    private int nativeFailureCount=0;
    // Triangle diagonals are a diagnostics view, never part of the production viewport.
    private boolean showNativeMesh=false;
    private long lastSignatureCheckNs=0L;

    private final Paint nativeMeshPaint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint nativeTextPaint=new Paint(Paint.ANTI_ALIAS_FLAG);

    public OcctModelCadCanvasView(Context context){
        super(context);
        initOcctModelReflection();
        nativeMeshPaint.setColor(Color.argb(190,35,105,220));
        nativeMeshPaint.setStyle(Paint.Style.STROKE);
        nativeMeshPaint.setStrokeWidth(1.35f);
        nativeMeshPaint.setStrokeJoin(Paint.Join.ROUND);
        nativeTextPaint.setColor(Color.rgb(25,80,175));
        nativeTextPaint.setTextSize(19f);
        nativeTextPaint.setTextAlign(Paint.Align.CENTER);
    }

    private void initOcctModelReflection(){
        try{
            historyField=field(ParametricHistorySolidCadCanvasView.class,"history");
            formHistoryField=field(AdvancedParametricSolidCadCanvasView.class,"formHistory");
            selectedBodyField=field(SolidCadCanvasView.class,"selectedBody");
            overviewCardField=field(SpatialCadCanvasView.class,"overviewCard");
            directOpsByBodyField=field(DirectModelCadCanvasView.class,"directOpsByBody");

            profileFromSourcesMethod=ParametricHistorySolidCadCanvasView.class.getDeclaredMethod("profileFromSources",List.class);
            profileFromSourcesMethod.setAccessible(true);
            advancedProfileMethod=AdvancedParametricSolidCadCanvasView.class.getDeclaredMethod("profile",Object.class);
            advancedProfileMethod.setAccessible(true);
            path3DMethod=AdvancedParametricSolidCadCanvasView.class.getDeclaredMethod("path3D",Object.class);
            path3DMethod.setAccessible(true);
            Class<?> planeClass=Geometry3D.Plane3D.class;
            axisForMethod=AdvancedParametricSolidCadCanvasView.class.getDeclaredMethod("axisFor",Object.class,planeClass,boolean.class);
            axisForMethod.setAccessible(true);
            projectMethod=SpatialCadCanvasView.class.getDeclaredMethod("project",Geometry3D.Vec3.class);
            projectMethod.setAccessible(true);
        }catch(Exception ignored){}
    }

    private static Field field(Class<?> owner,String name)throws NoSuchFieldException{
        Field f=owner.getDeclaredField(name);f.setAccessible(true);return f;
    }

    @Override
    public String extrudeSelectedBody(float heightCm){
        String result=super.extrudeSelectedBody(heightCm);
        lastHistorySignature="";
        syncNativeHistory(true);
        return withSelectedNativeResult(result);
    }

    @Override
    public String createRevolve(Object profileEntity,Object axisEntity,boolean xAxis,float angleDeg){
        return createRevolve(profileEntity,axisEntity,xAxis,angleDeg,0f);
    }

    @Override
    public String createRevolve(Object profileEntity,Object axisEntity,boolean xAxis,float angleDeg,float heightMm){
        String result=super.createRevolve(profileEntity,axisEntity,xAxis,angleDeg,heightMm);
        lastHistorySignature="";
        syncNativeHistory(true);
        return withSelectedNativeResult(result);
    }

    @Override
    public String createSweep(Object profileEntity,Object pathEntity){
        String result=super.createSweep(profileEntity,pathEntity);
        lastHistorySignature="";
        syncNativeHistory(true);
        return withSelectedNativeResult(result);
    }

    @Override
    public String createLoft(Object first,Object second){
        String result=super.createLoft(first,second);
        lastHistorySignature="";
        syncNativeHistory(true);
        return withSelectedNativeResult(result);
    }

    private String withSelectedNativeResult(String result){
        NativeRecord record=nativeByBody.get(selectedBody());
        if(record!=null && result!=null && !result.trim().isEmpty()){
            return result+" | OCCT "+record.kind+" • "+record.triangleCount()+"△ Mesh";
        }
        return result;
    }

    @Override
    public String rebuildHistory(){
        String result=super.rebuildHistory();
        lastHistorySignature="";
        syncNativeHistory(true);
        return result+(nativeFeatureCount>0?" • OCCT "+nativeFeatureCount:" ");
    }

    @Override
    public String undoLastFeature(){
        String result=super.undoLastFeature();
        lastHistorySignature="";
        syncNativeHistory(true);
        return result;
    }

    @Override
    public void clearAll(){
        super.clearAll();
        nativeByBody.clear();
        lastHistorySignature="";
        nativeFeatureCount=0;
        nativeFailureCount=0;
    }

    @Override
    public String selectedInfo(){
        maybeSyncNativeHistory();
        String base=super.selectedInfo();
        NativeRecord record=nativeByBody.get(selectedBody());
        if(record==null)return base;
        return base+" | OCCT "+record.kind+" | "+record.triangleCount()+"△";
    }

    @Override
    public void showSolidManager(){
        syncNativeHistory(false);
        String selected=bodyName(selectedBody());
        NativeRecord r=nativeByBody.get(selectedBody());
        String state=!NativeBRepKernel.occtAvailable()
                ?"OCCT روی این ABI فعال نیست"
                :"OCCT synced: "+nativeFeatureCount+" Feature"+(nativeFailureCount>0?" • "+nativeFailureCount+" منتظر مهاجرت/خطا":"");
        if(r!=null)state+="\nSelected: "+selected+" • "+r.kind+" • "+r.triangleCount()+" triangles";
        else if(selectedBody()!=null && hasDirectEdits(selectedBody()))state+="\nSelected: Direct Edit فعال دارد؛ شکل Native عمداً موقتاً غیرفعال است.";

        String[] items={
                "▣ ابزارهای Solid / Native قبلی",
                "↻ Sync Sketch/History → OCCT",
                "◆ اطلاعات B-Rep انتخاب‌شده",
                showNativeMesh?"△ مخفی‌کردن OCCT Mesh":"△ نمایش OCCT Mesh",
                "✓ وضعیت مهاجرت Exact Model"
        };
        new AlertDialog.Builder(getContext())
                .setTitle("Solid 3D • OCCT Model")
                .setMessage(state+"\n\nExtrude • Revolve • Sweep • Loft • Boolean مستقیماً به TopoDS_Shape تبدیل می‌شوند.")
                .setItems(items,(d,w)->{
                    if(w==0)OcctModelCadCanvasView.super.showSolidManager();
                    else if(w==1){lastHistorySignature="";syncNativeHistory(true);toast(nativeSyncSummary());invalidate();}
                    else if(w==2)showSelectedNativeInfo();
                    else if(w==3){showNativeMesh=!showNativeMesh;invalidate();toast(showNativeMesh?"OCCT Mesh روشن":"OCCT Mesh مخفی");}
                    else showMigrationStatus();
                })
                .setNegativeButton("بستن",null).show();
    }

    @Override
    protected void onDraw(Canvas canvas){
        super.onDraw(canvas);
        maybeSyncNativeHistory();
        if(showNativeMesh && is3DOverview())drawSelectedNativeMesh(canvas);
    }

    private void maybeSyncNativeHistory(){
        long now=System.nanoTime();
        if(now-lastSignatureCheckNs<80_000_000L)return;
        lastSignatureCheckNs=now;
        syncNativeHistory(false);
    }

    private synchronized void syncNativeHistory(boolean force){
        if(!NativeBRepKernel.occtAvailable()){
            releaseNativeRecords();
            lastHistorySignature="";
            nativeFeatureCount=0;
            nativeFailureCount=0;
            return;
        }

        List<Object> baseHistory=history();
        List<Object> forms=formHistory();
        String signature=historySignature(baseHistory,forms);
        if(!force && signature.equals(lastHistorySignature))return;

        releaseNativeRecords();
        nativeFeatureCount=0;
        nativeFailureCount=0;

        // 1) Independent body-producing features first.
        for(Object feature:baseHistory){
            if(feature==null||!"ExtrudeFeature".equals(feature.getClass().getSimpleName()))continue;
            Object out=value(feature,"outputBody");
            long handle=buildExtrudeFeature(feature);
            if(!storeNative(out,handle,extrudeKind(feature)))nativeFailureCount++;
        }

        for(Object feature:forms){
            if(feature==null)continue;
            Object out=value(feature,"outputBody");
            String type=feature.getClass().getSimpleName();
            long handle=buildFormFeature(feature,type);
            String kind="RevolveFeature".equals(type)?"Exact Revolve"
                    :"SweepFeature".equals(type)?"Exact Sweep"
                    :"LoftFeature".equals(type)?"Exact Loft":"Form";
            if(!storeNative(out,handle,kind))nativeFailureCount++;
        }

        // 2) Boolean history can depend on Extrude, Form or an earlier Boolean.
        // Resolve by dependency availability rather than by the two legacy list orders.
        List<Object> pending=new ArrayList<>();
        for(Object feature:baseHistory)if(feature!=null&&"BooleanFeature".equals(feature.getClass().getSimpleName()))pending.add(feature);
        int guard=pending.size()+2;
        while(!pending.isEmpty()&&guard-->0){
            boolean progress=false;
            Iterator<Object> it=pending.iterator();
            while(it.hasNext()){
                Object feature=it.next();
                Object left=value(feature,"leftBody"),right=value(feature,"rightBody"),out=value(feature,"outputBody");
                NativeRecord a=nativeByBody.get(left),b=nativeByBody.get(right);
                if(a==null||b==null)continue;
                String op=String.valueOf(value(feature,"operation"));
                long handle=NativeBRepKernel.occtBoolean(booleanCode(op),a.handle,b.handle);
                if(storeNative(out,handle,friendlyBoolean(op))){
                    it.remove();progress=true;
                }else{
                    it.remove();nativeFailureCount++;progress=true;
                }
            }
            if(!progress)break;
        }
        nativeFailureCount+=pending.size();
        lastHistorySignature=signature;
    }

    /** Exact-body source contract used by Project 3D and 3D guide layers. */
    protected synchronized boolean hasSelectedSolidBody(){
        return selectedBody()!=null;
    }

    protected synchronized long selectedExactNativeHandle(){
        syncNativeHistory(false);
        NativeRecord record=nativeByBody.get(selectedBody());
        return record==null?0L:record.handle;
    }

    protected synchronized List<Long> exactNativeHandlesSnapshot(){
        syncNativeHistory(false);
        List<Long> out=new ArrayList<>();
        for(NativeRecord record:nativeByBody.values()){
            if(record!=null&&record.handle!=0L&&!out.contains(record.handle))out.add(record.handle);
        }
        return out;
    }

    /** Snapshot of the exact OCCT display tessellation for the GPU renderer. */
    public synchronized double[] gpuMesh(){
        syncNativeHistory(false);
        int length=0;
        for(Map.Entry<Object,NativeRecord> entry:nativeByBody.entrySet()){
            NativeRecord record=entry.getValue();Object visible=value(entry.getKey(),"visible");
            if(record!=null&&!Boolean.FALSE.equals(visible))length+=record.mesh.length;
        }
        if(length==0)return new double[0];
        double[] combined=new double[length];int offset=0;
        for(Map.Entry<Object,NativeRecord> entry:nativeByBody.entrySet()){
            NativeRecord record=entry.getValue();Object visible=value(entry.getKey(),"visible");
            if(record==null||record.mesh.length==0||Boolean.FALSE.equals(visible))continue;
            System.arraycopy(record.mesh,0,combined,offset,record.mesh.length);offset+=record.mesh.length;
        }
        return combined;
    }

    /** Writes every visible exact body as STEP (0) or binary STL (1). */
    public synchronized boolean exportVisibleCad(String path,int format){
        syncNativeHistory(false);List<Long> handles=new ArrayList<>();
        for(Map.Entry<Object,NativeRecord> entry:nativeByBody.entrySet()){
            Object visible=value(entry.getKey(),"visible");NativeRecord record=entry.getValue();
            if(record!=null&&!Boolean.FALSE.equals(visible)&&record.handle!=0L)handles.add(record.handle);
        }
        long[] ids=new long[handles.size()];for(int i=0;i<ids.length;i++)ids[i]=handles.get(i);
        return NativeBRepKernel.occtExport(ids,path,format);
    }

    private boolean storeNative(Object body,long handle,String kind){
        if(body==null||handle==0L)return false;
        if(hasDirectEdits(body)){
            NativeBRepKernel.occtRelease(handle);
            return false;
        }
        double[] mesh=NativeBRepKernel.occtTriangulate(handle,0.35);
        nativeByBody.put(body,new NativeRecord(handle,kind,mesh));
        nativeFeatureCount++;
        return true;
    }

    private long buildExtrudeFeature(Object feature){
        try{
            @SuppressWarnings("unchecked")
            List<Object> sources=(List<Object>)value(feature,"sourceEntities");
            Object planeObject=value(feature,"plane");
            Geometry3D.Plane3D plane=planeObject instanceof Geometry3D.Plane3D?(Geometry3D.Plane3D)planeObject:null;
            float heightMm=number(feature,"heightMm");
            if(sources==null||sources.isEmpty()||plane==null||Math.abs(heightMm)<1e-6f)return 0L;

            if(sources.size()==1 && "CircleEntity".equals(sources.get(0).getClass().getSimpleName())){
                Object circle=sources.get(0);
                float x=number(circle,"x"),y=number(circle,"y"),r=Math.abs(number(circle,"r"));
                if(r<=0f)return 0L;
                Geometry3D.Vec3 center=plane.point(x,y);
                return NativeBRepKernel.occtCreateCylinderAxis(center,plane.normal,r,heightMm);
            }

            Object profile=profileFromSourcesMethod==null?null:profileFromSourcesMethod.invoke(this,sources);
            if(profile==null)return 0L;
            Object pointsObject=value(profile,"points");
            if(!(pointsObject instanceof List))return 0L;
            @SuppressWarnings("unchecked")
            List<PointF> points=(List<PointF>)pointsObject;
            double[] xyz=pointsOnPlane(points,plane);
            if(xyz.length<9)return 0L;
            return NativeBRepKernel.occtCreatePrism(xyz,plane.normal.mul(heightMm));
        }catch(Exception e){return 0L;}
    }

    private long buildFormFeature(Object feature,String type){
        try{
            if("RevolveFeature".equals(type)){
                Object profileEntity=value(feature,"profileEntity");
                Object planeValue=value(feature,"profilePlane");
                Geometry3D.Plane3D plane=planeValue instanceof Geometry3D.Plane3D?(Geometry3D.Plane3D)planeValue:null;
                Object axisEntity=value(feature,"axisEntity");
                boolean xAxis=bool(feature,"xAxis");
                float angle=number(feature,"angleDeg");
                float height=number(feature,"heightMm");
                ProfileRecord profile=profileRecord(profileEntity);
                if(profile==null||plane==null)return 0L;
                Object axis=axisForMethod==null?null:axisForMethod.invoke(this,axisEntity,plane,xAxis);
                Geometry3D.Vec3 origin=vecValue(axis,"origin");
                Geometry3D.Vec3 direction=vecValue(axis,"direction");
                if(origin==null||direction==null||direction.length()<1e-6f){
                    origin=plane.origin;direction=xAxis?plane.u:plane.v;
                }
                if(Math.abs(height)>1e-6f)return NativeBRepKernel.occtCreateHelicalRevolve(profile.type,profile.data,origin,direction,angle,height);
                return NativeBRepKernel.occtCreateRevolve(profile.type,profile.data,origin,direction,angle);
            }

            if("SweepFeature".equals(type)){
                Object profileEntity=value(feature,"profileEntity");
                Object pathEntity=value(feature,"pathEntity");
                ProfileRecord profile=profileRecord(profileEntity);
                double[] path=pathRecord(pathEntity);
                if(profile==null||path.length<6)return 0L;
                return NativeBRepKernel.occtCreateSweep(profile.type,profile.data,path);
            }

            if("LoftFeature".equals(type)){
                ProfileRecord first=profileRecord(value(feature,"first"));
                ProfileRecord second=profileRecord(value(feature,"second"));
                if(first==null||second==null)return 0L;
                return NativeBRepKernel.occtCreateLoft(first.type,first.data,second.type,second.data);
            }
        }catch(Exception ignored){}
        return 0L;
    }

    private ProfileRecord profileRecord(Object entity){
        if(entity==null||advancedProfileMethod==null)return null;
        try{
            Object profile=advancedProfileMethod.invoke(this,entity);
            if(profile==null)return null;
            Object planeObject=value(profile,"plane");
            Geometry3D.Plane3D plane=planeObject instanceof Geometry3D.Plane3D?(Geometry3D.Plane3D)planeObject:null;
            if(plane==null)return null;

            if("CircleEntity".equals(entity.getClass().getSimpleName())){
                float x=number(entity,"x"),y=number(entity,"y"),r=Math.abs(number(entity,"r"));
                if(r<=0f)return null;
                Geometry3D.Vec3 c=plane.point(x,y);
                double[] data={c.x,c.y,c.z,plane.normal.x,plane.normal.y,plane.normal.z,plane.u.x,plane.u.y,plane.u.z,r};
                return new ProfileRecord(NativeBRepKernel.OCCT_PROFILE_CIRCLE,data);
            }

            Object pointsObject=value(profile,"points");
            if(!(pointsObject instanceof List))return null;
            @SuppressWarnings("unchecked")
            List<PointF> points=(List<PointF>)pointsObject;
            double[] xyz=pointsOnPlane(points,plane);
            return xyz.length>=9?new ProfileRecord(NativeBRepKernel.OCCT_PROFILE_POLYGON,xyz):null;
        }catch(Exception e){return null;}
    }

    private double[] pathRecord(Object pathEntity){
        if(pathEntity==null||path3DMethod==null)return new double[0];
        try{
            Object out=path3DMethod.invoke(this,pathEntity);
            if(!(out instanceof List))return new double[0];
            @SuppressWarnings("unchecked")
            List<Geometry3D.Vec3> path=(List<Geometry3D.Vec3>)out;
            if(path.size()<2)return new double[0];
            double[] xyz=new double[path.size()*3];
            for(int i=0;i<path.size();i++){
                Geometry3D.Vec3 p=path.get(i);int k=i*3;
                xyz[k]=p.x;xyz[k+1]=p.y;xyz[k+2]=p.z;
            }
            return xyz;
        }catch(Exception e){return new double[0];}
    }

    private static double[] pointsOnPlane(List<PointF> points,Geometry3D.Plane3D plane){
        if(points==null||plane==null||points.size()<3)return new double[0];
        double[] xyz=new double[points.size()*3];
        for(int i=0;i<points.size();i++){
            PointF p=points.get(i);
            Geometry3D.Vec3 world=plane.point(p.x,p.y);
            int k=i*3;xyz[k]=world.x;xyz[k+1]=world.y;xyz[k+2]=world.z;
        }
        return xyz;
    }

    private String extrudeKind(Object feature){
        Object source=value(feature,"sourceEntities");
        if(source instanceof List){
            List<?> list=(List<?>)source;
            if(list.size()==1 && list.get(0)!=null && "CircleEntity".equals(list.get(0).getClass().getSimpleName()))return"Exact Cylinder";
        }
        return"Exact Prism";
    }

    private void drawSelectedNativeMesh(Canvas canvas){
        Object body=selectedBody();
        NativeRecord record=nativeByBody.get(body);
        if(record==null||record.mesh.length<9)return;
        RectF card=overviewCard();
        if(card==null||card.isEmpty())return;
        canvas.save();
        canvas.clipRect(card);
        int triangles=Math.min(record.triangleCount(),6000);
        for(int i=0;i<triangles;i++){
            int k=i*9;
            PointF a=project(record.mesh[k],record.mesh[k+1],record.mesh[k+2]);
            PointF b=project(record.mesh[k+3],record.mesh[k+4],record.mesh[k+5]);
            PointF c=project(record.mesh[k+6],record.mesh[k+7],record.mesh[k+8]);
            canvas.drawLine(a.x,a.y,b.x,b.y,nativeMeshPaint);
            canvas.drawLine(b.x,b.y,c.x,c.y,nativeMeshPaint);
            canvas.drawLine(c.x,c.y,a.x,a.y,nativeMeshPaint);
        }
        canvas.restore();
        canvas.drawText("OCCT B-Rep Mesh • "+record.kind+" • "+record.triangleCount()+"△",
                card.centerX(),card.bottom-42f,nativeTextPaint);
    }

    private void showSelectedNativeInfo(){
        syncNativeHistory(false);
        Object body=selectedBody();
        NativeRecord r=nativeByBody.get(body);
        if(body==null){toast("اول یک Body را انتخاب کن");return;}
        if(r==null){
            toast(hasDirectEdits(body)?"این Body Direct Edit فعال دارد؛ مهاجرت Native آن مرحله بعد است":"این Body هنوز به OCCT مهاجرت نکرده");
            return;
        }
        double[] stats=NativeBRepKernel.occtShapeStats(r.handle);
        StringBuilder msg=new StringBuilder();
        msg.append("Body: ").append(bodyName(body));
        msg.append("\nSource: ").append(r.kind);
        msg.append("\nMesh: ").append(r.triangleCount()).append(" triangle");
        if(stats.length>=4){
            msg.append("\n\nVolume: ").append(dualVolume(stats[0]));
            msg.append("\nFace: ").append((int)stats[1]);
            msg.append("   Edge: ").append((int)stats[2]);
            msg.append("   Solid: ").append((int)stats[3]);
        }
        msg.append("\n\n").append(NativeBRepKernel.occtShapeSummary(r.handle));
        new AlertDialog.Builder(getContext()).setTitle("OCCT TopoDS_Shape")
                .setMessage(msg.toString()).setPositiveButton("باشه",null).show();
    }

    private void showMigrationStatus(){
        String msg="✓ Sketch Plane → XYZ واقعی\n"
                +"✓ Rectangle/Polygon/closed-lines Extrude → OCCT Prism\n"
                +"✓ Circle Extrude → analytic OCCT Cylinder\n"
                +"✓ Revolve → BRepPrimAPI_MakeRevol\n"
                +"✓ Sweep → BRepOffsetAPI_MakePipe\n"
                +"✓ Loft → BRepOffsetAPI_ThruSections\n"
                +"✓ Circle profile در Formها → analytic OCCT Circle\n"
                +"✓ History Union/Subtract/Intersect → BRepAlgoAPI\n"
                +"✓ TopoDS_Shape → OCCT triangulation → نمایش در workspace\n"
                +"✓ واحد داخلی و رابط کاربری: mm\n\n"
                +"مرحله باقی‌مانده در این شاخه: Direct Editهای Face/Edge، Fillet/Chamfer/Shell و Transform باید از backend قدیمی به خود OCCT Shape History منتقل شوند.";
        new AlertDialog.Builder(getContext()).setTitle("Exact Model Migration")
                .setMessage(msg).setPositiveButton("باشه",null).show();
    }

    private String nativeSyncSummary(){
        if(!NativeBRepKernel.occtAvailable())return"OCCT فعال نیست";
        return "OCCT Sync • "+nativeFeatureCount+" Feature"+(nativeFailureCount>0?" • "+nativeFailureCount+" منتظر/خطا":"");
    }

    private void releaseNativeRecords(){
        for(NativeRecord r:new ArrayList<>(nativeByBody.values()))if(r!=null)NativeBRepKernel.occtRelease(r.handle);
        nativeByBody.clear();
    }

    @SuppressWarnings("unchecked")
    private List<Object> history(){
        try{Object h=historyField==null?null:historyField.get(this);return h instanceof List?(List<Object>)h:new ArrayList<>();}
        catch(Exception e){return new ArrayList<>();}
    }

    @SuppressWarnings("unchecked")
    private List<Object> formHistory(){
        try{Object h=formHistoryField==null?null:formHistoryField.get(this);return h instanceof List?(List<Object>)h:new ArrayList<>();}
        catch(Exception e){return new ArrayList<>();}
    }

    @SuppressWarnings("unchecked")
    private boolean hasDirectEdits(Object body){
        if(body==null||directOpsByBodyField==null)return false;
        try{
            Object mapObj=directOpsByBodyField.get(this);
            if(!(mapObj instanceof Map))return false;
            Object ops=((Map<Object,Object>)mapObj).get(body);
            return ops instanceof List && !((List<?>)ops).isEmpty();
        }catch(Exception e){return false;}
    }

    private String historySignature(List<Object> base,List<Object> forms){
        StringBuilder s=new StringBuilder();
        s.append("B").append(base.size()).append('|');
        for(Object f:base){
            if(f==null)continue;
            String type=f.getClass().getSimpleName();
            Object out=value(f,"outputBody");
            s.append(type).append(':').append(System.identityHashCode(out)).append(':');
            if("ExtrudeFeature".equals(type)){
                s.append(number(f,"heightMm")).append(':').append(String.valueOf(value(f,"signature")));
            }else if("BooleanFeature".equals(type)){
                s.append(String.valueOf(value(f,"operation"))).append(':')
                        .append(System.identityHashCode(value(f,"leftBody"))).append(':')
                        .append(System.identityHashCode(value(f,"rightBody")));
            }
            s.append(':').append(bodyGeometrySignature(out)).append(':').append(hasDirectEdits(out)).append('|');
        }
        s.append("F").append(forms.size()).append('|');
        for(Object f:forms){
            if(f==null)continue;
            Object out=value(f,"outputBody");
            s.append(f.getClass().getSimpleName()).append(':').append(System.identityHashCode(out)).append(':');
            if("RevolveFeature".equals(f.getClass().getSimpleName()))s.append(number(f,"angleDeg")).append(':').append(number(f,"heightMm"));
            s.append(':').append(bodyGeometrySignature(out)).append(':').append(hasDirectEdits(out)).append('|');
        }
        return s.toString();
    }

    private String bodyGeometrySignature(Object body){
        SolidCSG c=bodyCsg(body);
        if(c==null)return"0";
        long count=0;
        double sx=0,sy=0,sz=0,sq=0;
        for(SolidCSG.Polygon p:c.polygons())for(SolidCSG.Vertex v:p.vertices){
            count++;sx+=v.pos.x;sy+=v.pos.y;sz+=v.pos.z;
            sq+=v.pos.x*v.pos.x+v.pos.y*v.pos.y+v.pos.z*v.pos.z;
        }
        return c.polygons().size()+":"+count+":"+Math.round(sx*100)+":"+Math.round(sy*100)+":"+Math.round(sz*100)+":"+Math.round(sq*10);
    }

    private SolidCSG bodyCsg(Object body){
        Object c=value(body,"csg");return c instanceof SolidCSG?(SolidCSG)c:null;
    }

    private Object selectedBody(){
        try{return selectedBodyField==null?null:selectedBodyField.get(this);}catch(Exception e){return null;}
    }

    private RectF overviewCard(){
        try{
            Object o=overviewCardField==null?null:overviewCardField.get(this);
            return o instanceof RectF?new RectF((RectF)o):new RectF();
        }catch(Exception e){return new RectF();}
    }

    private PointF project(double x,double y,double z){
        try{
            Object p=projectMethod==null?null:projectMethod.invoke(this,new Geometry3D.Vec3((float)x,(float)y,(float)z));
            if(p instanceof PointF)return(PointF)p;
        }catch(Exception ignored){}
        return new PointF();
    }

    private static Object value(Object o,String name){
        if(o==null)return null;
        try{Field f=findField(o.getClass(),name);return f==null?null:f.get(o);}catch(Exception e){return null;}
    }

    private static float number(Object o,String name){
        Object v=value(o,name);return v instanceof Number?((Number)v).floatValue():0f;
    }

    private static boolean bool(Object o,String name){
        Object v=value(o,name);return v instanceof Boolean && (Boolean)v;
    }

    private static Geometry3D.Vec3 vecValue(Object o,String name){
        Object v=value(o,name);return v instanceof Geometry3D.Vec3?(Geometry3D.Vec3)v:null;
    }

    private static Field findField(Class<?> c,String name){
        Class<?> x=c;
        while(x!=null){
            try{Field f=x.getDeclaredField(name);f.setAccessible(true);return f;}
            catch(Exception e){x=x.getSuperclass();}
        }
        return null;
    }

    private static String bodyName(Object body){
        Object n=value(body,"name");return n==null?"Body":String.valueOf(n);
    }

    private static int booleanCode(String op){
        if("UNION".equals(op))return NativeBRepKernel.OCCT_UNION;
        if("SUBTRACT".equals(op))return NativeBRepKernel.OCCT_SUBTRACT;
        return NativeBRepKernel.OCCT_INTERSECT;
    }

    private static String friendlyBoolean(String op){
        if("UNION".equals(op))return"Exact Union";
        if("SUBTRACT".equals(op))return"Exact Subtract";
        return"Exact Intersect";
    }

    private static String dualVolume(double mm3){return num(mm3)+" mm³";}
    private static String num(double v){
        String s=String.format(Locale.US,"%.4f",v);
        while(s.contains(".")&&(s.endsWith("0")||s.endsWith(".")))s=s.substring(0,s.length()-1);
        return s;
    }

    private void toast(String text){Toast.makeText(getContext(),text,Toast.LENGTH_LONG).show();}
}

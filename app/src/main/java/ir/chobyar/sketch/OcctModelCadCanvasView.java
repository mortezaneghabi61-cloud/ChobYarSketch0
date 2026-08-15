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
import java.util.List;
import java.util.Locale;

/**
 * First production bridge between the app's own parametric Sketch/History model
 * and the OCCT TopoDS_Shape store.
 *
 * Extrude features are rebuilt as exact OCCT prisms. A single Circle source is
 * preserved as an analytic OCCT cylinder on the real sketch Plane normal rather
 * than as a many-sided polygon. History Boolean features are then evaluated from
 * those exact input handles. OCCT also returns the triangulation used here as a
 * display overlay, so exact B-Rep geometry and rendering now share one source.
 *
 * Revolve/Sweep/Loft and direct-edit features still use their existing backend;
 * this class deliberately migrates Extrude + Boolean first without destabilizing
 * the rest of the modeling workflow.
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

    private final IdentityHashMap<Object,NativeRecord> nativeByBody=new IdentityHashMap<>();
    private Field historyField;
    private Field selectedBodyField;
    private Field overviewCardField;
    private Method profileFromSourcesMethod;
    private Method projectMethod;

    private String lastHistorySignature="";
    private int nativeFeatureCount=0;
    private int nativeFailureCount=0;
    private boolean showNativeMesh=true;
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
            selectedBodyField=field(SolidCadCanvasView.class,"selectedBody");
            overviewCardField=field(SpatialCadCanvasView.class,"overviewCard");
            profileFromSourcesMethod=ParametricHistorySolidCadCanvasView.class.getDeclaredMethod("profileFromSources",List.class);
            profileFromSourcesMethod.setAccessible(true);
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
        Object selected=selectedBody();
        NativeRecord record=nativeByBody.get(selected);
        if(record!=null && result!=null && result.contains("ساخته شد")){
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
                :"OCCT synced: "+nativeFeatureCount+" Feature"+(nativeFailureCount>0?" • "+nativeFailureCount+" خطا":"");
        if(r!=null)state+="\nSelected: "+selected+" • "+r.kind+" • "+r.triangleCount()+" triangles";

        String[] items={
                "▣ ابزارهای Solid / Native قبلی",
                "↻ Sync Sketch/History → OCCT",
                "◆ اطلاعات B-Rep انتخاب‌شده",
                showNativeMesh?"△ مخفی‌کردن OCCT Mesh":"△ نمایش OCCT Mesh",
                "✓ وضعیت مهاجرت Exact Model"
        };
        new AlertDialog.Builder(getContext())
                .setTitle("Solid 3D • OCCT Model")
                .setMessage(state+"\n\nExtrude و Booleanهای History مستقیماً از Sketch به TopoDS_Shape ساخته می‌شوند.")
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
        List<Object> history=history();
        String signature=historySignature(history);
        if(!force && signature.equals(lastHistorySignature))return;

        releaseNativeRecords();
        nativeFeatureCount=0;
        nativeFailureCount=0;

        for(Object feature:history){
            if(feature==null)continue;
            String type=feature.getClass().getSimpleName();
            if("ExtrudeFeature".equals(type)){
                Object out=value(feature,"outputBody");
                long handle=buildExtrudeFeature(feature);
                if(out!=null && handle!=0L){
                    nativeByBody.put(out,new NativeRecord(handle,extrudeKind(feature),NativeBRepKernel.occtTriangulate(handle,0.35)));
                    nativeFeatureCount++;
                }else nativeFailureCount++;
            }else if("BooleanFeature".equals(type)){
                Object left=value(feature,"leftBody"),right=value(feature,"rightBody"),out=value(feature,"outputBody");
                NativeRecord a=nativeByBody.get(left),b=nativeByBody.get(right);
                String op=String.valueOf(value(feature,"operation"));
                long handle=(a==null||b==null)?0L:NativeBRepKernel.occtBoolean(booleanCode(op),a.handle,b.handle);
                if(out!=null && handle!=0L){
                    nativeByBody.put(out,new NativeRecord(handle,friendlyBoolean(op),NativeBRepKernel.occtTriangulate(handle,0.35)));
                    nativeFeatureCount++;
                }else nativeFailureCount++;
            }
        }
        lastHistorySignature=signature;
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
            if(points.size()<3)return 0L;
            double[] xyz=new double[points.size()*3];
            for(int i=0;i<points.size();i++){
                PointF p=points.get(i);
                Geometry3D.Vec3 world=plane.point(p.x,p.y);
                int k=i*3;xyz[k]=world.x;xyz[k+1]=world.y;xyz[k+2]=world.z;
            }
            return NativeBRepKernel.occtCreatePrism(xyz,plane.normal.mul(heightMm));
        }catch(Exception e){return 0L;}
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
        if(r==null){toast("این Body هنوز به OCCT مهاجرت نکرده");return;}
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
                +"✓ History Union/Subtract/Intersect → BRepAlgoAPI\n"
                +"✓ TopoDS_Shape → OCCT triangulation → نمایش در workspace\n"
                +"✓ واحد داخلی: mm؛ UI: cm + mm\n\n"
                +"در حال حاضر Revolve / Sweep / Loft و Direct Edit هنوز روی backend قبلی‌اند. مرحله بعد آن‌ها را به همین Shape history منتقل می‌کند.";
        new AlertDialog.Builder(getContext()).setTitle("Exact Model Migration")
                .setMessage(msg).setPositiveButton("باشه",null).show();
    }

    private String nativeSyncSummary(){
        if(!NativeBRepKernel.occtAvailable())return"OCCT فعال نیست";
        return "OCCT Sync • "+nativeFeatureCount+" Feature"+(nativeFailureCount>0?" • "+nativeFailureCount+" خطا":"");
    }

    private void releaseNativeRecords(){
        for(NativeRecord r:new ArrayList<>(nativeByBody.values()))if(r!=null)NativeBRepKernel.occtRelease(r.handle);
        nativeByBody.clear();
    }

    @SuppressWarnings("unchecked")
    private List<Object> history(){
        try{
            Object h=historyField==null?null:historyField.get(this);
            return h instanceof List?(List<Object>)h:new ArrayList<>();
        }catch(Exception e){return new ArrayList<>();}
    }

    private String historySignature(List<Object> history){
        StringBuilder s=new StringBuilder();
        s.append(history.size()).append('|');
        for(Object f:history){
            if(f==null)continue;
            String type=f.getClass().getSimpleName();
            s.append(type).append(':').append(System.identityHashCode(value(f,"outputBody"))).append(':');
            if("ExtrudeFeature".equals(type)){
                s.append(number(f,"heightMm")).append(':').append(String.valueOf(value(f,"signature")));
            }else if("BooleanFeature".equals(type)){
                s.append(String.valueOf(value(f,"operation"))).append(':')
                        .append(System.identityHashCode(value(f,"leftBody"))).append(':')
                        .append(System.identityHashCode(value(f,"rightBody")));
            }
            s.append('|');
        }
        return s.toString();
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
        Object v=value(o,name);
        return v instanceof Number?((Number)v).floatValue():0f;
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

    private static String dualVolume(double mm3){return num(mm3/1000.0)+" cm³ / "+num(mm3)+" mm³";}
    private static String num(double v){
        String s=String.format(Locale.US,"%.4f",v);
        while(s.contains(".")&&(s.endsWith("0")||s.endsWith(".")))s=s.substring(0,s.length()-1);
        return s;
    }

    private void toast(String text){Toast.makeText(getContext(),text,Toast.LENGTH_LONG).show();}
}

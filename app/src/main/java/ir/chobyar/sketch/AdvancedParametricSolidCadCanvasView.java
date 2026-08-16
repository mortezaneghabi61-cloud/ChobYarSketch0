package ir.chobyar.sketch;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.PointF;
import android.text.InputType;
import android.widget.EditText;
import android.widget.Toast;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Shapr-style form tools on top of the parametric History prototype.
 *
 * Revolve, Sweep and Loft create actual polygonal SolidCSG bodies, retain their
 * source sketch entities and rebuild when those sketches change. This keeps the
 * interaction model parametric while the future B-Rep kernel can replace only
 * the geometric backend.
 */
public class AdvancedParametricSolidCadCanvasView extends ParametricHistorySolidCadCanvasView {

    private static final int CIRCLE_SEGMENTS = 72;

    private abstract static class FormFeature {
        final int id;
        final String kind;
        final List<Object> sourceEntities = new ArrayList<>();
        Object outputBody;
        boolean broken;
        String warning="";
        FormFeature(int id,String kind){this.id=id;this.kind=kind;}
        abstract String detail();
        abstract SolidCSG build(AdvancedParametricSolidCadCanvasView owner);
    }

    private static final class RevolveFeature extends FormFeature {
        final Object profileEntity;
        final Geometry3D.Plane3D profilePlane;
        final Object axisEntity;
        final boolean xAxis;
        float angleDeg;
        RevolveFeature(int id,Object profile,Geometry3D.Plane3D plane,Object axis,boolean xAxis,float angleDeg){
            super(id,"Revolve");this.profileEntity=profile;this.profilePlane=plane;this.axisEntity=axis;this.xAxis=xAxis;this.angleDeg=angleDeg;
            sourceEntities.add(profile);if(axis!=null)sourceEntities.add(axis);
        }
        @Override String detail(){return "Revolve • "+fmt(angleDeg)+"°"+(axisEntity!=null?" • محور خط":" • محور "+(xAxis?"X":"Y"))+(broken?" • ⚠":"");}
        @Override SolidCSG build(AdvancedParametricSolidCadCanvasView o){
            Profile p=o.profile(profileEntity);if(p==null)return null;
            Axis3D axis=o.axisFor(axisEntity,profilePlane,xAxis);if(axis==null)return null;
            int steps=Math.max(12,Math.min(128,(int)Math.ceil(Math.abs(angleDeg)/5f)));
            return SolidCSG.revolve(p.points,profilePlane,axis.origin,axis.direction,angleDeg,steps);
        }
    }

    private static final class SweepFeature extends FormFeature {
        final Object profileEntity;
        final Object pathEntity;
        SweepFeature(int id,Object profile,Object path){super(id,"Sweep");profileEntity=profile;pathEntity=path;sourceEntities.add(profile);sourceEntities.add(path);}
        @Override String detail(){return "Sweep • پروفایل + مسیر"+(broken?" • ⚠":"");}
        @Override SolidCSG build(AdvancedParametricSolidCadCanvasView o){
            Profile p=o.profile(profileEntity);if(p==null)return null;
            List<Geometry3D.Vec3> path=o.path3D(pathEntity);if(path.size()<2)return null;
            return SolidCSG.sweep(p.points,p.plane,path);
        }
    }

    private static final class LoftFeature extends FormFeature {
        final Object first;
        final Object second;
        LoftFeature(int id,Object a,Object b){super(id,"Loft");first=a;second=b;sourceEntities.add(a);sourceEntities.add(b);}
        @Override String detail(){return "Loft • دو پروفایل"+(broken?" • ⚠":"");}
        @Override SolidCSG build(AdvancedParametricSolidCadCanvasView o){
            Profile a=o.profile(first),b=o.profile(second);if(a==null||b==null)return null;
            return SolidCSG.loft(a.points,a.plane,b.points,b.plane,64);
        }
    }

    private static final class Profile {
        final Object entity;
        final List<PointF> points;
        final String layer;
        final Geometry3D.Plane3D plane;
        Profile(Object entity,List<PointF> points,String layer,Geometry3D.Plane3D plane){this.entity=entity;this.points=points;this.layer=layer;this.plane=plane;}
    }

    private static final class Axis3D {
        final Geometry3D.Vec3 origin,direction;
        Axis3D(Geometry3D.Vec3 origin,Geometry3D.Vec3 direction){this.origin=origin;this.direction=direction.normalized();}
    }

    private final List<FormFeature> formHistory=new ArrayList<>();
    private int formSerial=1;
    private boolean rebuildingForms=false;

    private Field selectedField;
    private Field selectedObjectsField;
    private Field entitiesField;
    private Field planeByLayerField;
    private Field activePlaneField;
    private Field bodiesField;
    private Field selectedBodyField;
    private Field selectedFaceField;
    private Field bodySerialField;
    private Constructor<?> solidBodyConstructor;

    public AdvancedParametricSolidCadCanvasView(Context context){
        super(context);
        initFormReflection();
    }

    private void initFormReflection(){
        try{
            selectedField=field(CadCanvasView.class,"selected");
            selectedObjectsField=field(SmartCadCanvasView.class,"selectedObjects");
            entitiesField=field(CadCanvasView.class,"entities");
            planeByLayerField=field(SpatialCadCanvasView.class,"planeByLayer");
            activePlaneField=field(SpatialCadCanvasView.class,"activePlane");
            bodiesField=field(SolidCadCanvasView.class,"bodies");
            selectedBodyField=field(SolidCadCanvasView.class,"selectedBody");
            selectedFaceField=field(SolidCadCanvasView.class,"selectedFace");
            bodySerialField=field(SolidCadCanvasView.class,"bodySerial");
            Class<?> bodyClass=Class.forName("ir.chobyar.sketch.SolidCadCanvasView$SolidBody");
            solidBodyConstructor=bodyClass.getDeclaredConstructor(int.class,String.class,SolidCSG.class);
            solidBodyConstructor.setAccessible(true);
        }catch(Exception ignored){}
    }

    private static Field field(Class<?> owner,String name)throws NoSuchFieldException{Field f=owner.getDeclaredField(name);f.setAccessible(true);return f;}

    // ------------------------------------------------------------------
    // Adaptive Solid menu
    // ------------------------------------------------------------------

    @Override
    public void showSolidManager(){
        String[] items={
                "⬆ Extrude / Boolean / Bodies / Face",
                "⟳ Revolve / دوران پروفایل",
                "➜ Sweep / حرکت پروفایل روی مسیر",
                "≋ Loft / اتصال دو پروفایل",
                "⏱ History کامل",
                "↻ بازسازی همه Featureها",
                is3DOverview()?"□ برگشت به Sketch 2D":"◇ نمایش 3D"
        };
        new AlertDialog.Builder(getContext())
                .setTitle("Solid 3D • Form")
                .setMessage("ابزار حرفه‌ای، مسیر ساده:\nRevolve: پروفایل بسته + در صورت نیاز یک خط محور\nSweep: پروفایل بسته + Line/Polyline مسیر\nLoft: دو پروفایل بسته روی Sketch/Planeهای مختلف")
                .setItems(items,(d,w)->{
                    if(w==0)super.showSolidManager();
                    else if(w==1)startRevolve();
                    else if(w==2)startSweep();
                    else if(w==3)startLoft();
                    else if(w==4)showHistoryManager();
                    else if(w==5)toast(rebuildHistory());
                    else toast(toggle3DOverview());
                })
                .setNegativeButton("بستن",null).show();
    }

    private void startRevolve(){
        List<Object> s=selection();
        Object profile=null,axis=null;
        for(Object e:s){
            if(isClosedProfile(e)&&profile==null)profile=e;
            else if(isLine(e)&&axis==null)axis=e;
        }
        if(profile==null){
            AutoProfile auto=autoProfile(s,true);
            if(auto!=null){profile=auto.profile;axis=auto.axis;}
        }
        if(profile==null){toast("مقطع بسته پیدا نشد؛ یک خط از محیط مقطع را انتخاب کن و دوباره Revolve را بزن");return;}
        Object finalProfile=profile,finalAxis=axis;
        if(axis!=null){showRevolveAngle(finalProfile,finalAxis,false);return;}
        String[] axes={"محور Y همان Sketch — پیشنهاد معمول","محور X همان Sketch"};
        new AlertDialog.Builder(getContext()).setTitle("Revolve • محور")
                .setMessage("اگر خط محور را همراه پروفایل انتخاب کنی، همان خط محور دوران می‌شود.")
                .setItems(axes,(d,w)->showRevolveAngle(finalProfile,null,w==1))
                .setNegativeButton("لغو",null).show();
    }

    public void showRevolveTool(){startRevolve();}
    public void showSweepTool(){startSweep();}
    public void showLoftTool(){startLoft();}

    @Override public void showInteractiveExtrude(){
        if(!hasSelectedClosedProfile()){
            AutoProfile auto=autoProfile(selection(),false);
            if(auto!=null)try{selectOne(auto.profile);}catch(Exception ignored){}
        }
        super.showInteractiveExtrude();
    }

    private void showRevolveAngle(Object profile,Object axis,boolean xAxis){
        EditText input=new EditText(getContext());input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL|InputType.TYPE_NUMBER_FLAG_SIGNED);
        input.setText("360");input.setSelectAllOnFocus(true);
        new AlertDialog.Builder(getContext()).setTitle("Revolve • زاویه")
                .setMessage("360° برای دوران کامل. زاویه کمتر هم Body قطاعی می‌سازد.")
                .setView(input)
                .setPositiveButton("ساخت Body",(d,w)->{
                    try{float a=Float.parseFloat(normalizeDigits(input.getText().toString()));toast(createRevolve(profile,axis,xAxis,a));}
                    catch(Exception e){toast("زاویه درست وارد نشده");}
                }).setNegativeButton("لغو",null).show();
    }

    public String createRevolve(Object profileEntity,Object axisEntity,boolean xAxis,float angleDeg){
        if(Math.abs(angleDeg)<0.01f||Math.abs(angleDeg)>360f)return"زاویه Revolve باید بین 0 و 360 درجه باشد";
        Profile p=profile(profileEntity);if(p==null)return"پروفایل بسته معتبر نیست";
        Axis3D axis=axisFor(axisEntity,p.plane,xAxis);if(axis==null)return"محور Revolve معتبر نیست";
        RevolveFeature f=new RevolveFeature(formSerial++,profileEntity,p.plane,axisEntity,xAxis,angleDeg);
        SolidCSG csg=f.build(this);if(csg==null||csg.isEmpty())return"Revolve نتوانست Body بسازد؛ پروفایل و محور را بررسی کن";
        Object body=addBody("Revolve "+f.id,csg);if(body==null)return"ساخت Body انجام نشد";
        f.outputBody=body;formHistory.add(f);setOverview3D();invalidate();
        return "Revolve ساخته شد • "+fmt(angleDeg)+"°";
    }

    private void startSweep(){
        List<Object>s=selection();Object profile=null,path=null;
        for(Object e:s){if(isClosedProfile(e)&&profile==null)profile=e;else if(isPath(e)&&path==null)path=e;}
        if(profile==null||path==null){toast("برای Sweep دو چیز انتخاب کن: یک پروفایل بسته + یک Line یا Polyline مسیر");return;}
        toast(createSweep(profile,path));
    }

    public String createSweep(Object profileEntity,Object pathEntity){
        Profile p=profile(profileEntity);List<Geometry3D.Vec3> path=path3D(pathEntity);
        if(p==null)return"پروفایل Sweep معتبر نیست";
        if(path.size()<2)return"مسیر Sweep معتبر نیست";
        SweepFeature f=new SweepFeature(formSerial++,profileEntity,pathEntity);
        SolidCSG csg=f.build(this);if(csg==null||csg.isEmpty())return"Sweep نتوانست Body بسازد";
        Object body=addBody("Sweep "+f.id,csg);if(body==null)return"ساخت Body انجام نشد";
        f.outputBody=body;formHistory.add(f);setOverview3D();invalidate();
        return "Sweep ساخته شد • پروفایل روی مسیر حرکت کرد";
    }

    private void startLoft(){
        List<Object> profiles=new ArrayList<>();for(Object e:selection())if(isClosedProfile(e))profiles.add(e);
        if(profiles.size()!=2){toast("برای Loft دقیقاً دو پروفایل بسته را انتخاب کن؛ بهتر است روی دو Plane/Face متفاوت باشند");return;}
        toast(createLoft(profiles.get(0),profiles.get(1)));
    }

    public String createLoft(Object first,Object second){
        Profile a=profile(first),b=profile(second);if(a==null||b==null)return"دو پروفایل Loft معتبر نیستند";
        if(a.layer.equals(b.layer))return"برای Loft بهتر است دو پروفایل روی دو Sketch/Plane متفاوت باشند";
        LoftFeature f=new LoftFeature(formSerial++,first,second);
        SolidCSG csg=f.build(this);if(csg==null||csg.isEmpty())return"Loft نتوانست Body بسازد";
        Object body=addBody("Loft "+f.id,csg);if(body==null)return"ساخت Body انجام نشد";
        f.outputBody=body;formHistory.add(f);setOverview3D();invalidate();
        return "Loft ساخته شد • دو پروفایل به هم متصل شدند";
    }

    // ------------------------------------------------------------------
    // Parametric rebuild + History
    // ------------------------------------------------------------------

    @Override
    public String rebuildHistory(){
        if(rebuildingForms)return super.rebuildHistory();
        rebuildingForms=true;int ok=0,broken=0;
        try{
            for(FormFeature f:formHistory){
                if(!sourcesStillExist(f.sourceEntities)){f.broken=true;f.warning="Sketch/مسیر منبع حذف شده";broken++;continue;}
                SolidCSG c=f.build(this);
                if(c==null||c.isEmpty()){f.broken=true;f.warning="هندسه Feature نامعتبر شد";broken++;continue;}
                setBodyCsg(f.outputBody,c);f.broken=false;f.warning="";ok++;
            }
        }finally{rebuildingForms=false;}
        String base=super.rebuildHistory();
        return base+" • Form "+ok+(broken>0?" • "+broken+" Form خطا":"");
    }

    @Override
    public void showHistoryManager(){
        if(formHistory.isEmpty()){super.showHistoryManager();return;}
        String[] rows=new String[formHistory.size()+1];
        rows[0]="⏱ Extrude / Boolean History";
        for(int i=0;i<formHistory.size();i++){
            FormFeature f=formHistory.get(i);
            rows[i+1]=(i+1)+". "+f.detail()+(f.broken&&!f.warning.isEmpty()?" — "+f.warning:"");
        }
        new AlertDialog.Builder(getContext()).setTitle("History • همه Featureها")
                .setMessage("Featureهای Form هم به Sketchهای منبع وابسته‌اند و بعد از تغییر Sketch دوباره محاسبه می‌شوند.")
                .setItems(rows,(d,w)->{if(w==0)super.showHistoryManager();else editFormFeature(formHistory.get(w-1));})
                .setNeutralButton("بازسازی همه",(d,w)->toast(rebuildHistory()))
                .setNegativeButton("بستن",null).show();
    }

    private void editFormFeature(FormFeature f){
        if(f instanceof RevolveFeature){
            RevolveFeature r=(RevolveFeature)f;EditText input=new EditText(getContext());input.setSingleLine(true);input.setText(fmt(r.angleDeg));input.setSelectAllOnFocus(true);
            new AlertDialog.Builder(getContext()).setTitle("ویرایش "+r.detail())
                    .setMessage("زاویه Revolve را عوض کن؛ Body و عملیات وابسته دوباره محاسبه می‌شوند.")
                    .setView(input).setPositiveButton("اعمال",(d,w)->{try{r.angleDeg=Float.parseFloat(normalizeDigits(input.getText().toString()));toast(rebuildHistory());}catch(Exception e){toast("زاویه درست نیست");}})
                    .setNegativeButton("بستن",null).show();
            return;
        }
        new AlertDialog.Builder(getContext()).setTitle(f.detail())
                .setMessage(f instanceof SweepFeature?"Sweep به پروفایل و مسیر انتخاب‌شده وابسته است. مسیر یا پروفایل را ویرایش کن تا Body خودکار بازسازی شود.":"Loft به هر دو پروفایل منبع وابسته است. هر کدام را ویرایش کنی Body دوباره ساخته می‌شود.")
                .setPositiveButton("بازسازی",(d,w)->toast(rebuildHistory())).setNegativeButton("بستن",null).show();
    }

    @Override
    public void clearAll(){super.clearAll();formHistory.clear();formSerial=1;}

    // ------------------------------------------------------------------
    // Profile/path extraction
    // ------------------------------------------------------------------

    private Profile profile(Object e){
        if(e==null||!entities().contains(e))return null;
        String type=e.getClass().getSimpleName();String layer=entityLayer(e);Geometry3D.Plane3D plane=planeForLayer(layer);
        if("RectEntity".equals(type)){
            PointF[]p=pointArray(e,"p");if(p==null)return null;List<PointF>out=new ArrayList<>();for(PointF q:p)out.add(new PointF(q.x,q.y));return new Profile(e,out,layer,plane);
        }
        if("CircleEntity".equals(type)){
            float cx=getFloat(e,"x"),cy=getFloat(e,"y"),r=Math.abs(getFloat(e,"r"));if(r<1e-5f)return null;List<PointF>out=new ArrayList<>();
            for(int i=0;i<CIRCLE_SEGMENTS;i++){double a=2*Math.PI*i/CIRCLE_SEGMENTS;out.add(new PointF(cx+(float)Math.cos(a)*r,cy+(float)Math.sin(a)*r));}return new Profile(e,out,layer,plane);
        }
        if("PolygonEntity".equals(type)){List<PointF>p=points(e);return p.size()>=3?new Profile(e,p,layer,plane):null;}
        if("PolylineEntity".equals(type)&&boolField(e,"closed")){List<PointF>p=points(e);return p.size()>=3?new Profile(e,p,layer,plane):null;}
        return null;
    }

    /** A Shapr-like region resolver: one picked edge is enough to recover the
     * closed loop from its Sketch layer.  A spare line is treated as a revolve
     * axis, so woodworking profiles do not require fragile multi-selection. */
    private static final class AutoProfile{final Object profile,axis;AutoProfile(Object p,Object a){profile=p;axis=a;}}

    private boolean hasSelectedClosedProfile(){for(Object e:selection())if(isClosedProfile(e))return true;return false;}

    private AutoProfile autoProfile(List<Object> picked,boolean allowAxis){
        String layer=null;Object explicitAxis=null;
        for(Object e:picked){if(e==null)continue;if(layer==null)layer=entityLayer(e);if(isClosedProfile(e))return new AutoProfile(e,null);}
        if(layer==null)layer=getCurrentLayer();
        List<Object> lines=new ArrayList<>();
        for(Object e:entities())if(isLine(e)&&layer.equals(entityLayer(e)))lines.add(e);
        if(lines.size()<3)return null;
        // Prefer exactly what the user selected when it already forms a loop.
        List<Object> selectedLines=new ArrayList<>();for(Object e:picked)if(isLine(e))selectedLines.add(e);
        List<PointF> loop=stitchLoop(selectedLines);
        if(loop==null)loop=stitchLoop(lines);
        if(loop==null&&allowAxis){
            // Typical turning Sketch = one closed perimeter plus one axis line.
            for(int skip=0;skip<lines.size();skip++){
                List<Object> candidate=new ArrayList<>(lines);Object spare=candidate.remove(skip);List<PointF> test=stitchLoop(candidate);
                if(test!=null){loop=test;explicitAxis=spare;break;}
            }
        }
        if(loop==null)return null;
        Object profile=addPolyline(loop,true);if(profile==null)return null;
        return new AutoProfile(profile,explicitAxis);
    }

    private List<PointF> stitchLoop(List<Object> lines){
        if(lines==null||lines.size()<3)return null;boolean[] used=new boolean[lines.size()];
        PointF first=lineEnd(lines.get(0),0),current=lineEnd(lines.get(0),1);if(first==null||current==null)return null;
        List<PointF> out=new ArrayList<>();out.add(first);out.add(current);used[0]=true;
        for(int step=1;step<lines.size();step++){
            int found=-1;PointF next=null;
            for(int i=0;i<lines.size();i++)if(!used[i]){PointF a=lineEnd(lines.get(i),0),b=lineEnd(lines.get(i),1);if(a==null||b==null)continue;
                if(distance(current,a)<=0.35f){found=i;next=b;break;}if(distance(current,b)<=0.35f){found=i;next=a;break;}}
            if(found<0)return null;used[found]=true;current=next;out.add(current);
        }
        if(distance(current,first)>0.35f)return null;out.remove(out.size()-1);return out.size()>=3?out:null;
    }

    private static PointF lineEnd(Object e,int which){return which==0?new PointF(getFloat(e,"x1"),getFloat(e,"y1")):new PointF(getFloat(e,"x2"),getFloat(e,"y2"));}
    private static float distance(PointF a,PointF b){return(float)Math.hypot(a.x-b.x,a.y-b.y);}

    private Axis3D axisFor(Object line,Geometry3D.Plane3D plane,boolean xAxis){
        if(line!=null&&isLine(line)){
            Geometry3D.Plane3D lp=planeForLayer(entityLayer(line));
            Geometry3D.Vec3 a=lp.point(getFloat(line,"x1"),getFloat(line,"y1"));
            Geometry3D.Vec3 b=lp.point(getFloat(line,"x2"),getFloat(line,"y2"));
            Geometry3D.Vec3 d=b.sub(a);return d.length()<1e-5f?null:new Axis3D(a,d);
        }
        return new Axis3D(plane.origin,xAxis?plane.u:plane.v);
    }

    private List<Geometry3D.Vec3> path3D(Object e){
        List<Geometry3D.Vec3>out=new ArrayList<>();if(e==null||!entities().contains(e))return out;
        Geometry3D.Plane3D p=planeForLayer(entityLayer(e));String t=e.getClass().getSimpleName();
        if("LineEntity".equals(t)){out.add(p.point(getFloat(e,"x1"),getFloat(e,"y1")));out.add(p.point(getFloat(e,"x2"),getFloat(e,"y2")));}
        else if("PolylineEntity".equals(t)&&!boolField(e,"closed"))for(PointF q:points(e))out.add(p.point(q.x,q.y));
        return out;
    }

    private boolean sourcesStillExist(List<Object>s){List<Object>all=entities();for(Object e:s)if(!all.contains(e))return false;return true;}
    private static boolean isLine(Object e){return e!=null&&"LineEntity".equals(e.getClass().getSimpleName());}
    private static boolean isPath(Object e){if(e==null)return false;String t=e.getClass().getSimpleName();return"LineEntity".equals(t)||("PolylineEntity".equals(t)&&!boolField(e,"closed"));}
    private static boolean isClosedProfile(Object e){if(e==null)return false;String t=e.getClass().getSimpleName();return"RectEntity".equals(t)||"CircleEntity".equals(t)||"PolygonEntity".equals(t)||("PolylineEntity".equals(t)&&boolField(e,"closed"));}

    // ------------------------------------------------------------------
    // Reflection helpers
    // ------------------------------------------------------------------

    @SuppressWarnings("unchecked") private List<Object>selection(){
        try{List<Object>m=(List<Object>)selectedObjectsField.get(this);if(m!=null&&!m.isEmpty())return new ArrayList<>(m);Object e=selectedField.get(this);List<Object>o=new ArrayList<>();if(e!=null)o.add(e);return o;}catch(Exception e){return new ArrayList<>();}
    }
    @SuppressWarnings("unchecked") private List<Object>entities(){try{return(List<Object>)entitiesField.get(this);}catch(Exception e){return new ArrayList<>();}}
    @SuppressWarnings("unchecked") private List<Object>bodies(){try{return(List<Object>)bodiesField.get(this);}catch(Exception e){return new ArrayList<>();}}

    private Object addBody(String name,SolidCSG csg){
        try{int id=bodySerialField.getInt(this);Object b=solidBodyConstructor.newInstance(id,name,csg);bodySerialField.setInt(this,id+1);bodies().add(b);selectedBodyField.set(this,b);selectedFaceField.set(this,null);return b;}catch(Exception e){return null;}
    }
    private void setBodyCsg(Object body,SolidCSG csg){try{Field f=findField(body.getClass(),"csg");if(f!=null)f.set(body,csg);}catch(Exception ignored){}}
    private void setOverview3D(){try{Field f=field(SpatialCadCanvasView.class,"overview3D");f.setBoolean(this,true);}catch(Exception ignored){}}

    @SuppressWarnings("unchecked") private Geometry3D.Plane3D planeForLayer(String layer){
        try{Map<String,Geometry3D.Plane3D>m=(Map<String,Geometry3D.Plane3D>)planeByLayerField.get(this);Geometry3D.Plane3D p=m.get(layer);if(p!=null)return p;Object a=activePlaneField.get(this);return a instanceof Geometry3D.Plane3D?(Geometry3D.Plane3D)a:Geometry3D.xy();}catch(Exception e){return Geometry3D.xy();}
    }
    private static String entityLayer(Object e){Object v=call(e,"getLayer");return v==null?"":String.valueOf(v);}
    private static Object call(Object target,String name){if(target==null)return null;Class<?>c=target.getClass();while(c!=null){try{Method m=c.getDeclaredMethod(name);m.setAccessible(true);return m.invoke(target);}catch(NoSuchMethodException e){c=c.getSuperclass();}catch(Exception e){return null;}}return null;}
    private static Field findField(Class<?>c,String name){Class<?>x=c;while(x!=null){try{Field f=x.getDeclaredField(name);f.setAccessible(true);return f;}catch(Exception e){x=x.getSuperclass();}}return null;}
    private static float getFloat(Object o,String n){try{Field f=findField(o.getClass(),n);return f==null?0f:f.getFloat(o);}catch(Exception e){return 0f;}}
    private static boolean boolField(Object o,String n){try{Field f=findField(o.getClass(),n);return f!=null&&f.getBoolean(o);}catch(Exception e){return false;}}
    private static PointF[] pointArray(Object o,String n){try{Field f=findField(o.getClass(),n);Object v=f==null?null:f.get(o);return v instanceof PointF[]?(PointF[])v:null;}catch(Exception e){return null;}}
    @SuppressWarnings("unchecked") private static List<PointF>points(Object o){try{Field f=findField(o.getClass(),"points");Object v=f==null?null:f.get(o);if(v instanceof List){List<PointF>out=new ArrayList<>();for(PointF p:(List<PointF>)v)out.add(new PointF(p.x,p.y));return out;}}catch(Exception ignored){}return new ArrayList<>();}

    private static String normalizeDigits(String s){if(s==null)return"";StringBuilder b=new StringBuilder();for(int i=0;i<s.length();i++){char c=s.charAt(i);if(c>='۰'&&c<='۹')b.append((char)('0'+c-'۰'));else if(c>='٠'&&c<='٩')b.append((char)('0'+c-'٠'));else b.append(c);}return b.toString().trim();}
    private static String fmt(float v){String s=String.format(Locale.US,"%.2f",v);while(s.contains(".")&&(s.endsWith("0")||s.endsWith(".")))s=s.substring(0,s.length()-1);return s;}
    private void toast(String s){if(s!=null&&!s.trim().isEmpty())Toast.makeText(getContext(),s,Toast.LENGTH_SHORT).show();}
}

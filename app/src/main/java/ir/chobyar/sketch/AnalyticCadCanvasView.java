package ir.chobyar.sketch;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.PointF;
import android.text.InputType;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Analytic curved-geometry workspace.
 *
 * Common curved solids now have an exact mathematical master model. Rendering
 * and the legacy Boolean engine still consume a tessellated SolidCSG copy, but
 * radius/diameter/height/area/volume are no longer derived from facet count.
 * Circle Extrude is recognized as a true cylinder; exact Cylinder, Cone/Frustum
 * and Sphere primitives can also be created directly.
 */
public class AnalyticCadCanvasView extends BRepDirectCadCanvasView {

    private static final int PREVIEW_SEGMENTS=96;

    private final IdentityHashMap<Object,AnalyticSolidKernel.Primitive> analyticByBody=new IdentityHashMap<>();

    private Field selectedBodyField;
    private Field selectedFaceField;
    private Field bodiesField;
    private Field bodySerialField;
    private Field activePlaneField;
    private Field planeByLayerField;
    private Field selectedField;
    private Field selectedObjectsField;
    private Constructor<?> bodyConstructor;

    public AnalyticCadCanvasView(Context context){
        super(context);
        initReflection();
    }

    private void initReflection(){
        try{
            selectedBodyField=field(SolidCadCanvasView.class,"selectedBody");
            selectedFaceField=field(SolidCadCanvasView.class,"selectedFace");
            bodiesField=field(SolidCadCanvasView.class,"bodies");
            bodySerialField=field(SolidCadCanvasView.class,"bodySerial");
            activePlaneField=field(SpatialCadCanvasView.class,"activePlane");
            planeByLayerField=field(SpatialCadCanvasView.class,"planeByLayer");
            selectedField=field(CadCanvasView.class,"selected");
            selectedObjectsField=field(SmartCadCanvasView.class,"selectedObjects");
            Class<?> bodyClass=Class.forName("ir.chobyar.sketch.SolidCadCanvasView$SolidBody");
            bodyConstructor=bodyClass.getDeclaredConstructor(int.class,String.class,SolidCSG.class);
            bodyConstructor.setAccessible(true);
        }catch(Exception ignored){}
    }

    private static Field field(Class<?> owner,String name)throws NoSuchFieldException{Field f=owner.getDeclaredField(name);f.setAccessible(true);return f;}

    // ------------------------------------------------------------------
    // Adaptive Solid menu
    // ------------------------------------------------------------------

    @Override
    public void showSolidManager(){
        Object body=selectedBody();
        AnalyticSolidKernel.Primitive primitive=currentPrimitive(body);
        String selected=body==null?"Body انتخاب نشده":bodyName(body)+(primitive==null?"":" • "+primitive.kind);
        String[] items={
                "▣ ابزارهای Solid/Form معمولی — Extrude, Boolean, Revolve, Sweep, Loft",
                "◎ Cylinder دقیق / استوانه",
                "△ Cone/Frustum دقیق / مخروط",
                "● Sphere دقیق / کره",
                "∿ Analytic Inspector / هندسه منحنی دقیق",
                "↻ بازسازی نمایش از مدل تحلیلی",
                is3DOverview()?"□ برگشت به Sketch 2D":"◇ نمایش 3D"
        };
        new AlertDialog.Builder(getContext())
                .setTitle("Solid 3D • Analytic")
                .setMessage(selected+"\n\nدایره، استوانه، مخروط و کره در این لایه با پارامتر ریاضی نگه‌داری می‌شوند؛ چندضلعی فقط برای نمایش/CSG فعلی ساخته می‌شود.")
                .setItems(items,(d,w)->{
                    if(w==0)AnalyticCadCanvasView.super.showSolidManager();
                    else if(w==1)showCylinderDialog();
                    else if(w==2)showConeDialog();
                    else if(w==3)showSphereDialog();
                    else if(w==4)showAnalyticInspector();
                    else if(w==5)toast(rebuildSelectedAnalytic());
                    else toast(toggle3DOverview());
                })
                .setNegativeButton("بستن",null).show();
    }

    @Override
    public String extrudeSelectedBody(float heightCm){
        CircleSeed seed=circleSeedFromSelection(heightCm*10f);
        String result=super.extrudeSelectedBody(heightCm);
        Object body=selectedBody();
        if(body!=null&&seed!=null&&!result.contains("ممکن نشد")&&!result.contains("انتخاب کن")){
            Geometry3D.Vec3 axis=seed.axis;
            float h=seed.heightMm;
            if(h<0){h=-h;axis=axis.mul(-1f);}
            analyticByBody.put(body,new AnalyticSolidKernel.Cylinder(seed.center,axis,seed.radiusMm,h));
        }else if(body!=null){
            AnalyticSolidKernel.Primitive p=AnalyticSolidKernel.recognize(bodyCsg(body));
            if(p!=null)analyticByBody.put(body,p);
        }
        return result;
    }

    @Override
    public String rebuildHistory(){
        String r=super.rebuildHistory();
        refreshRecognizedBodies();
        return r+" • Analytic sync";
    }

    @Override
    public String selectedInfo(){
        String base=super.selectedInfo();
        Object body=selectedBody();
        AnalyticSolidKernel.Primitive p=currentPrimitive(body);
        if(body!=null&&p!=null)return base+" | "+shortInfo(p);
        return base;
    }

    @Override
    public void clearAll(){
        super.clearAll();analyticByBody.clear();
    }

    // ------------------------------------------------------------------
    // Exact primitive creation
    // ------------------------------------------------------------------

    private void showCylinderDialog(){
        LinearLayout box=form();
        EditText diameter=input(box,"قطر / Diameter (mm)","40");
        EditText height=input(box,"ارتفاع / Height (mm)","60");
        new AlertDialog.Builder(getContext())
                .setTitle("Cylinder دقیق")
                .setMessage("مرکز استوانه روی Origin صفحه Sketch فعال ساخته می‌شود. پارامتر اصلی دقیق می‌ماند.")
                .setView(box)
                .setPositiveButton("ساخت",(d,w)->{
                    try{
                        float dia=parseLengthMm(diameter.getText().toString()),h=parseLengthMm(height.getText().toString());
                        toast(createCylinder(dia,h));
                    }catch(Exception e){toast("قطر یا ارتفاع درست وارد نشده");}
                }).setNegativeButton("لغو",null).show();
    }

    private String createCylinder(float diameterMm,float signedHeightMm){
        if(diameterMm<=0||Math.abs(signedHeightMm)<1e-5f)return"قطر و ارتفاع باید معتبر باشند";
        Geometry3D.Plane3D p=activePlane();Geometry3D.Vec3 axis=p.normal;float h=signedHeightMm;
        if(h<0){h=-h;axis=axis.mul(-1f);}
        AnalyticSolidKernel.Cylinder exact=new AnalyticSolidKernel.Cylinder(p.origin,axis,diameterMm*0.5f,h);
        return addAnalyticBody("Cylinder",exact);
    }

    private void showConeDialog(){
        LinearLayout box=form();
        EditText d1=input(box,"قطر پایین / Base diameter", "50mm");
        EditText d2=input(box,"قطر بالا / Top diameter — صفر = نوک", "0mm");
        EditText h=input(box,"ارتفاع / Height", "70mm");
        new AlertDialog.Builder(getContext())
                .setTitle("Cone / Frustum دقیق")
                .setMessage("برای مخروط کامل قطر بالا را صفر بگذار؛ برای مخروط ناقص هر دو قطر را بده.")
                .setView(box)
                .setPositiveButton("ساخت",(d,w)->{
                    try{toast(createCone(parseLengthMm(d1.getText().toString()),parseLengthMm(d2.getText().toString()),parseLengthMm(h.getText().toString())));}
                    catch(Exception e){toast("ابعاد مخروط درست وارد نشده");}
                }).setNegativeButton("لغو",null).show();
    }

    private String createCone(float baseDiameterMm,float topDiameterMm,float signedHeightMm){
        if(baseDiameterMm<0||topDiameterMm<0||(baseDiameterMm<=0&&topDiameterMm<=0)||Math.abs(signedHeightMm)<1e-5f)return"ابعاد مخروط معتبر نیست";
        Geometry3D.Plane3D p=activePlane();Geometry3D.Vec3 axis=p.normal;float h=signedHeightMm;
        float r0=baseDiameterMm*0.5f,r1=topDiameterMm*0.5f;
        if(h<0){h=-h;axis=axis.mul(-1f);float t=r0;r0=r1;r1=t;}
        return addAnalyticBody("Cone",new AnalyticSolidKernel.Cone(p.origin,axis,r0,r1,h));
    }

    public void showSphereDialog(){
        LinearLayout box=form();
        EditText d=input(box,"قطر / Diameter (mm)","50");
        EditText centerX=input(box,"مرکز X (mm)",num(activePlane().origin.x));
        EditText centerY=input(box,"مرکز Y (mm)",num(activePlane().origin.y));
        EditText centerZ=input(box,"مرکز Z (mm)",num(activePlane().origin.z));
        new AlertDialog.Builder(getContext()).setTitle("Sphere دقیق")
                .setMessage("قطر و جای دقیق مرکز کره را وارد کن. بعداً نیز با انتخاب Body و «ابعاد دقیق» قابل ویرایش است.")
                .setView(box)
                .setPositiveButton("ساخت",(x,w)->{
                    try{
                        float dia=parseLengthMm(d.getText().toString());
                        Geometry3D.Vec3 center=new Geometry3D.Vec3(
                                parseLengthMm(centerX.getText().toString()),
                                parseLengthMm(centerY.getText().toString()),
                                parseLengthMm(centerZ.getText().toString()));
                        toast(createProjectSphere(center,dia));
                    }catch(Exception e){toast("قطر یا مختصات درست وارد نشده");}
                }).setNegativeButton("لغو",null).show();
    }

    private String createSphere(float diameterMm){
        if(diameterMm<=0)return"قطر باید بزرگ‌تر از صفر باشد";
        return addAnalyticBody("Sphere",new AnalyticSolidKernel.Sphere(activePlane().origin,diameterMm*0.5f));
    }

    /** Deterministic primitive bridge for bundled editable projects and model restore. */
    final String createProjectSphere(Geometry3D.Vec3 center,float diameterMm){
        if(center==null||diameterMm<=0f)return"Sphere پروژه معتبر نیست";
        return addAnalyticBody("Sphere",new AnalyticSolidKernel.Sphere(center,diameterMm*.5f));
    }

    /** User-facing parameter editor for the exact primitive selected on canvas. */
    public void showSelectedAnalyticEditor(){
        Object body=selectedBody();
        if(body==null){toast("اول یک Body را انتخاب کن");return;}
        AnalyticSolidKernel.Primitive primitive=currentPrimitive(body);
        if(!(primitive instanceof AnalyticSolidKernel.Sphere)){showAnalyticInspector();return;}
        AnalyticSolidKernel.Sphere sphere=(AnalyticSolidKernel.Sphere)primitive;
        LinearLayout box=form();
        EditText diameter=input(box,"قطر / Diameter (mm)",num(sphere.radiusMm*2f));
        EditText centerX=input(box,"مرکز X (mm)",num(sphere.center.x));
        EditText centerY=input(box,"مرکز Y (mm)",num(sphere.center.y));
        EditText centerZ=input(box,"مرکز Z (mm)",num(sphere.center.z));
        new AlertDialog.Builder(getContext()).setTitle("ویرایش دقیق • "+bodyName(body))
                .setMessage("قطر یا محل کره را تغییر بده؛ مدل و History ذخیره‌شده با هم به‌روز می‌شوند.")
                .setView(box).setPositiveButton("اعمال",(dialog,which)->{
                    try{
                        float dia=parseLengthMm(diameter.getText().toString());if(dia<=0f)throw new IllegalArgumentException();
                        Geometry3D.Vec3 center=new Geometry3D.Vec3(
                                parseLengthMm(centerX.getText().toString()),
                                parseLengthMm(centerY.getText().toString()),
                                parseLengthMm(centerZ.getText().toString()));
                        AnalyticSolidKernel.Sphere edited=new AnalyticSolidKernel.Sphere(center,dia*.5f);
                        analyticByBody.put(body,edited);setBodyCsg(body,edited.tessellate(PREVIEW_SEGMENTS));
                        clearFace();ensure3D();invalidate();toast("قطر و مختصات کره به‌روز شد");
                    }catch(Exception e){toast("قطر یا مختصات معتبر نیست");}
                }).setNegativeButton("لغو",null).show();
    }

    /** Reconcile exact metadata with the displayed solid before persistence. */
    final void refreshAnalyticBodiesForPersistence(){refreshRecognizedBodies();}

    private String addAnalyticBody(String prefix,AnalyticSolidKernel.Primitive exact){
        if(bodyConstructor==null||bodiesField==null||bodySerialField==null)return"ساخت Body تحلیلی آماده نیست";
        SolidCSG mesh=exact.tessellate(PREVIEW_SEGMENTS);if(mesh==null||mesh.isEmpty())return"هندسه تحلیلی Body نساخت";
        try{
            int id=bodySerialField.getInt(this);
            Object body=bodyConstructor.newInstance(id,prefix+" "+id,mesh);
            bodies().add(body);bodySerialField.setInt(this,id+1);
            selectedBodyField.set(this,body);if(selectedFaceField!=null)selectedFaceField.set(this,null);
            analyticByBody.put(body,exact);ensure3D();invalidate();
            return prefix+" دقیق ساخته شد • "+shortInfo(exact);
        }catch(Exception e){return"ساخت Body تحلیلی انجام نشد";}
    }

    // ------------------------------------------------------------------
    // Analytic inspector and exact formulas
    // ------------------------------------------------------------------

    public void showAnalyticInspector(){
        Object body=selectedBody();if(body==null){ensure3D();toast("اول روی یک Body بزن");return;}
        AnalyticSolidKernel.Primitive p=currentPrimitive(body);
        if(p==null){
            new AlertDialog.Builder(getContext()).setTitle("Analytic Inspector")
                    .setMessage("این Body در حال حاضر به یک Primitive تحلیلی ساده تبدیل نمی‌شود.\n\nBoolean/Loft/Sweep پیچیده هنوز از B-Rep توپولوژی + CSG استفاده می‌کند؛ مرحله بعدی انتقال سطوح تحلیلی از میان Booleanهاست.")
                    .setPositiveButton("باشه",null).show();return;
        }
        String msg=analyticDescription(p)+"\n\nSurface area: "+areaDual(p.areaMm2())+"\nVolume: "+volumeDual(p.volumeMm3())+"\n\nپارامترها مستقل از تعداد Polygonهای نمایش هستند.";
        new AlertDialog.Builder(getContext()).setTitle("Analytic • "+bodyName(body)).setMessage(msg).setPositiveButton("باشه",null).show();
    }

    private String rebuildSelectedAnalytic(){
        Object body=selectedBody();if(body==null)return"اول یک Body را انتخاب کن";
        AnalyticSolidKernel.Primitive p=currentPrimitive(body);if(p==null)return"برای این Body مدل تحلیلی ساده پیدا نشد";
        setBodyCsg(body,p.tessellate(PREVIEW_SEGMENTS));clearFace();ensure3D();invalidate();
        return"نمای Polygonal از مدل تحلیلی دوباره ساخته شد • "+p.kind;
    }

    private AnalyticSolidKernel.Primitive currentPrimitive(Object body){
        if(body==null)return null;
        AnalyticSolidKernel.Primitive recognized=AnalyticSolidKernel.recognize(bodyCsg(body));
        if(recognized!=null){analyticByBody.put(body,recognized);return recognized;}
        return analyticByBody.get(body);
    }

    private void refreshRecognizedBodies(){
        for(Object body:bodies()){
            AnalyticSolidKernel.Primitive p=AnalyticSolidKernel.recognize(bodyCsg(body));
            if(p!=null)analyticByBody.put(body,p);
        }
    }

    private static String shortInfo(AnalyticSolidKernel.Primitive p){
        if(p instanceof AnalyticSolidKernel.Cylinder){AnalyticSolidKernel.Cylinder c=(AnalyticSolidKernel.Cylinder)p;return"Ø "+dual(c.radiusMm*2f)+" • H "+dual(c.heightMm);}
        if(p instanceof AnalyticSolidKernel.Cone){AnalyticSolidKernel.Cone c=(AnalyticSolidKernel.Cone)p;return"Ø1 "+dual(c.baseRadiusMm*2f)+" • Ø2 "+dual(c.topRadiusMm*2f)+" • H "+dual(c.heightMm);}
        AnalyticSolidKernel.Sphere s=(AnalyticSolidKernel.Sphere)p;return"Ø "+dual(s.radiusMm*2f);
    }

    private static String analyticDescription(AnalyticSolidKernel.Primitive p){
        if(p instanceof AnalyticSolidKernel.Cylinder){
            AnalyticSolidKernel.Cylinder c=(AnalyticSolidKernel.Cylinder)p;
            return"Cylinder / استوانه\nDiameter: "+dual(c.radiusMm*2f)+"\nRadius: "+dual(c.radiusMm)+"\nHeight: "+dual(c.heightMm)+"\nSurface: Plane + Cylinder";
        }
        if(p instanceof AnalyticSolidKernel.Cone){
            AnalyticSolidKernel.Cone c=(AnalyticSolidKernel.Cone)p;
            return"Cone / Frustum\nBase diameter: "+dual(c.baseRadiusMm*2f)+"\nTop diameter: "+dual(c.topRadiusMm*2f)+"\nHeight: "+dual(c.heightMm)+"\nSurface: Plane + Cone";
        }
        AnalyticSolidKernel.Sphere s=(AnalyticSolidKernel.Sphere)p;
        return"Sphere / کره\nDiameter: "+dual(s.radiusMm*2f)+"\nRadius: "+dual(s.radiusMm)+"\nSurface: Sphere";
    }

    // ------------------------------------------------------------------
    // Circle -> exact cylinder bridge
    // ------------------------------------------------------------------

    private static final class CircleSeed{
        final Geometry3D.Vec3 center,axis;final float radiusMm,heightMm;
        CircleSeed(Geometry3D.Vec3 center,Geometry3D.Vec3 axis,float radiusMm,float heightMm){this.center=center;this.axis=axis;this.radiusMm=radiusMm;this.heightMm=heightMm;}
    }

    private CircleSeed circleSeedFromSelection(float heightMm){
        Object circle=null;
        for(Object e:selection())if(e!=null&&"CircleEntity".equals(e.getClass().getSimpleName())){if(circle!=null)return null;circle=e;}
        if(circle==null)return null;
        float x=getFloat(circle,"x"),y=getFloat(circle,"y"),r=Math.abs(getFloat(circle,"r"));if(r<=0f)return null;
        String layer=entityLayer(circle);Geometry3D.Plane3D plane=planeForLayer(layer);
        return new CircleSeed(plane.point(x,y),plane.normal,r,heightMm);
    }

    @SuppressWarnings("unchecked")
    private List<Object> selection(){
        List<Object> out=new ArrayList<>();
        try{
            Object v=selectedObjectsField==null?null:selectedObjectsField.get(this);
            if(v instanceof List)out.addAll((List<Object>)v);
            Object one=selectedField==null?null:selectedField.get(this);
            if(one!=null&&!out.contains(one))out.add(one);
        }catch(Exception ignored){}
        return out;
    }

    @SuppressWarnings("unchecked")
    private Geometry3D.Plane3D planeForLayer(String layer){
        try{
            Object m=planeByLayerField==null?null:planeByLayerField.get(this);
            if(m instanceof Map){Object p=((Map<String,Geometry3D.Plane3D>)m).get(layer);if(p instanceof Geometry3D.Plane3D)return(Geometry3D.Plane3D)p;}
        }catch(Exception ignored){}
        return activePlane();
    }

    private Geometry3D.Plane3D activePlane(){try{Object p=activePlaneField==null?null:activePlaneField.get(this);return p instanceof Geometry3D.Plane3D?(Geometry3D.Plane3D)p:Geometry3D.xy();}catch(Exception e){return Geometry3D.xy();}}

    // ------------------------------------------------------------------
    // UI + reflection helpers
    // ------------------------------------------------------------------

    private LinearLayout form(){LinearLayout b=new LinearLayout(getContext());b.setOrientation(LinearLayout.VERTICAL);b.setPadding(dp(20),dp(8),dp(20),0);return b;}
    private EditText input(LinearLayout parent,String label,String initial){TextView t=new TextView(getContext());t.setText(label);t.setTextSize(13f);parent.addView(t);EditText e=new EditText(getContext());e.setSingleLine(true);e.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);e.setText(initial);e.setSelectAllOnFocus(true);parent.addView(e);return e;}

    @SuppressWarnings("unchecked") private List<Object>bodies(){try{Object v=bodiesField==null?null:bodiesField.get(this);return v instanceof List?(List<Object>)v:new ArrayList<>();}catch(Exception e){return new ArrayList<>();}}
    private Object selectedBody(){try{return selectedBodyField==null?null:selectedBodyField.get(this);}catch(Exception e){return null;}}
    private SolidCSG bodyCsg(Object body){try{Field f=findField(body.getClass(),"csg");Object v=f==null?null:f.get(body);return v instanceof SolidCSG?(SolidCSG)v:null;}catch(Exception e){return null;}}
    private void setBodyCsg(Object body,SolidCSG csg){try{Field f=findField(body.getClass(),"csg");if(f!=null)f.set(body,csg);}catch(Exception ignored){}}
    private String bodyName(Object body){try{Field f=findField(body.getClass(),"name");Object v=f==null?null:f.get(body);return v==null?"Body":String.valueOf(v);}catch(Exception e){return"Body";}}
    private static Field findField(Class<?> c,String name){Class<?> x=c;while(x!=null){try{Field f=x.getDeclaredField(name);f.setAccessible(true);return f;}catch(Exception e){x=x.getSuperclass();}}return null;}
    private static float getFloat(Object o,String name){try{Field f=findField(o.getClass(),name);return f==null?0f:f.getFloat(o);}catch(Exception e){return 0f;}}
    private static String entityLayer(Object o){Object v=call(o,"getLayer");return v==null?"":String.valueOf(v);}
    private static Object call(Object target,String name){if(target==null)return null;Class<?> c=target.getClass();while(c!=null){try{Method m=c.getDeclaredMethod(name);m.setAccessible(true);return m.invoke(target);}catch(NoSuchMethodException e){c=c.getSuperclass();}catch(Exception e){return null;}}return null;}
    private void clearFace(){try{if(selectedFaceField!=null)selectedFaceField.set(this,null);}catch(Exception ignored){}}
    private void ensure3D(){if(!is3DOverview())toggle3DOverview();}
    private int dp(int v){return Math.round(v*getResources().getDisplayMetrics().density);}

    private static float parseLengthMm(String raw){String s=normalizeDigits(raw).trim().toLowerCase(Locale.US).replace(" ","");if(s.isEmpty())return 0f;if(s.endsWith("mm"))return Float.parseFloat(s.substring(0,s.length()-2));if(s.endsWith("cm"))return Float.parseFloat(s.substring(0,s.length()-2))*10f;return Float.parseFloat(s);}
    private static String normalizeDigits(String s){if(s==null)return"";StringBuilder b=new StringBuilder();for(int i=0;i<s.length();i++){char c=s.charAt(i);if(c>='۰'&&c<='۹')b.append((char)('0'+c-'۰'));else if(c>='٠'&&c<='٩')b.append((char)('0'+c-'٠'));else b.append(c);}return b.toString();}
    private static String num(double v){String s=String.format(Locale.US,"%.3f",v);while(s.contains(".")&&(s.endsWith("0")||s.endsWith(".")))s=s.substring(0,s.length()-1);return s;}
    private static String dual(double mm){return num(mm)+" mm";}
    private static String areaDual(double mm2){return num(mm2)+" mm²";}
    private static String volumeDual(double mm3){return num(mm3)+" mm³";}
    private void toast(String s){if(s!=null&&!s.trim().isEmpty())Toast.makeText(getContext(),s,Toast.LENGTH_SHORT).show();}
}

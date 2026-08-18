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
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
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
        float heightMm;
        RevolveFeature(int id,Object profile,Geometry3D.Plane3D plane,Object axis,boolean xAxis,float angleDeg,float heightMm){
            super(id,"Revolve");this.profileEntity=profile;this.profilePlane=plane;this.axisEntity=axis;this.xAxis=xAxis;this.angleDeg=angleDeg;this.heightMm=heightMm;
            sourceEntities.add(profile);if(axis!=null)sourceEntities.add(axis);
        }
        @Override String detail(){
            String thread=Math.abs(heightMm)<1e-5f?"":" • H "+fmt(heightMm)+" mm • "+fmt(Math.abs(angleDeg)/360f)+" دور • Pitch "+fmt(pitchMm())+" mm";
            return "Revolve • "+fmt(angleDeg)+"°"+thread+(axisEntity!=null?" • محور خط":" • محور "+(xAxis?"X":"Y"))+(broken?" • ⚠":"");
        }
        float pitchMm(){float turns=Math.abs(angleDeg)/360f;return turns<1e-5f?0f:Math.abs(heightMm)/turns;}
        @Override SolidCSG build(AdvancedParametricSolidCadCanvasView o){
            Profile p=o.profile(profileEntity);if(p==null)return null;
            Axis3D axis=o.axisFor(axisEntity,profilePlane,xAxis);if(axis==null)return null;
            int steps=o.revolveSteps(angleDeg,heightMm,p.points.size());
            return SolidCSG.helicalRevolve(p.points,profilePlane,axis.origin,axis.direction,angleDeg,heightMm,steps);
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
    /** Synthetic region -> original sketch edges. Identity semantics are
     * intentional because sketch entities do not have stable value equality. */
    private final IdentityHashMap<Object,List<Object>> autoProfileSources=new IdentityHashMap<>();
    private int formSerial=1;
    private boolean rebuildingForms=false;
    private Object interactiveRevolveProfile;
    private Object interactiveRevolveAxis;
    private boolean interactiveRevolveXAxis;
    private float interactiveRevolveAngle=360f;
    private float interactiveRevolveHeightMm=0f;
    private boolean interactiveRevolveActive;

    private final Paint revolveRingPaint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint revolveHeightPaint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint revolveHandleFill=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint revolveTextPaint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private PointF revolveRingCenterScreen;
    private final List<PointF> revolveRingScreen=new ArrayList<>();
    private PointF revolveHeightBaseScreen,revolveHeightTipScreen;
    private int revolveDragMode;
    private boolean revolveDragMoved;
    private float revolveDragStartX,revolveDragStartY,revolveDragStartAngle,revolveDragStartHeight;
    private float revolveHeightPixelsPerMm=3f;

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
    private Constructor<?> autoPolylineConstructor;
    private Method saveUndoMethod;

    public AdvancedParametricSolidCadCanvasView(Context context){
        super(context);
        initFormReflection();
        float density=getResources().getDisplayMetrics().density;
        revolveRingPaint.setColor(Color.rgb(31,111,235));revolveRingPaint.setStyle(Paint.Style.STROKE);revolveRingPaint.setStrokeWidth(2.5f*density);
        revolveRingPaint.setStrokeCap(Paint.Cap.ROUND);revolveRingPaint.setStrokeJoin(Paint.Join.ROUND);
        revolveHeightPaint.setColor(Color.rgb(29,145,255));revolveHeightPaint.setStyle(Paint.Style.STROKE);revolveHeightPaint.setStrokeWidth(3f*density);revolveHeightPaint.setStrokeCap(Paint.Cap.ROUND);
        revolveHandleFill.setColor(Color.WHITE);revolveHandleFill.setStyle(Paint.Style.FILL);
        revolveTextPaint.setColor(Color.rgb(29,88,170));revolveTextPaint.setTextSize(13f*density);revolveTextPaint.setTextAlign(Paint.Align.CENTER);
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
            Class<?> polylineClass=Class.forName("ir.chobyar.sketch.CadCanvasView$PolylineEntity");
            autoPolylineConstructor=polylineClass.getDeclaredConstructor(List.class,boolean.class);autoPolylineConstructor.setAccessible(true);
            saveUndoMethod=CadCanvasView.class.getDeclaredMethod("saveUndo");saveUndoMethod.setAccessible(true);
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

    public String beginInteractiveRevolveSession(){
        List<Object> selected=selection();Object profile=null,axis=null;
        for(Object e:selected){
            if(isClosedProfile(e)&&profile==null)profile=e;
            else if(isLine(e)&&axis==null)axis=e;
        }
        if(profile==null){
            AutoProfile auto=autoProfile(selected,true);
            if(auto!=null){profile=auto.profile;axis=auto.axis;}
        }
        if(profile==null)return "پروفایل بسته پیدا نشد؛ داخل مقطع یا یک خط از محیطش را انتخاب کن";
        interactiveRevolveProfile=profile;interactiveRevolveAxis=axis;interactiveRevolveXAxis=false;
        interactiveRevolveAngle=360f;interactiveRevolveHeightMm=0f;interactiveRevolveActive=true;showModelOverview();
        if(!refreshInteractiveRevolve(true)){clearInteractiveRevolve();return "Revolve نتوانست پیش‌نمایش بسازد؛ محور نباید از داخل پروفایل بگذرد";}
        dispatchWorkspaceState();
        return "Revolve • 360° • Height 0 mm • برای رزوه Height و چند دور را وارد کن";
    }

    public boolean isInteractiveRevolveActive(){return interactiveRevolveActive;}

    public String commitInteractiveRevolve(){
        if(!interactiveRevolveActive)return "Revolve فعال نیست";
        Object profile=interactiveRevolveProfile,axis=interactiveRevolveAxis;boolean xAxis=interactiveRevolveXAxis;
        float angle=interactiveRevolveAngle,height=interactiveRevolveHeightMm;clearInteractiveRevolve();
        String result=createRevolve(profile,axis,xAxis,angle,height);dispatchWorkspaceState();return result;
    }

    public void cancelInteractiveRevolve(){clearInteractiveRevolve();dispatchWorkspaceState();}

    public void showInteractiveRevolveAngleEditor(){
        if(!interactiveRevolveActive)return;
        LinearLayout box=revolveInputs(interactiveRevolveAngle,interactiveRevolveHeightMm);
        EditText angle=(EditText)box.getChildAt(1),height=(EditText)box.getChildAt(3);
        new AlertDialog.Builder(getContext()).setTitle("Revolve • Angle + Height")
                .setMessage("Height=0 دوران عادی است. برای رزوه: Angle=360×تعداد دور و Height=گام×تعداد دور.")
                .setView(box).setPositiveButton("اعمال",(d,w)->{
                    try{
                        float value=parseAngle(angle),lead=parseMillimeters(height);
                        validateRevolve(value,lead);
                        interactiveRevolveAngle=value;interactiveRevolveHeightMm=lead;
                        if(!refreshInteractiveRevolve(true))toast("این زاویه برای پروفایل فعلی معتبر نیست");
                    }catch(Exception e){toast("زاویه/ارتفاع درست نیست • مثال رزوه 10 دور: 3600° و 25.2 mm");}
                }).setNegativeButton("لغو",null).show();
    }

    public String interactiveRevolveSummary(){
        if(!interactiveRevolveActive)return "Revolve";
        float turns=Math.abs(interactiveRevolveAngle)/360f;
        if(Math.abs(interactiveRevolveHeightMm)<1e-5f)return "Revolve  "+fmt(interactiveRevolveAngle)+"°";
        float pitch=turns<1e-5f?0f:Math.abs(interactiveRevolveHeightMm)/turns;
        return fmt(interactiveRevolveAngle)+"°  •  H "+fmt(interactiveRevolveHeightMm)+" mm  •  P "+fmt(pitch)+" mm";
    }

    private boolean refreshInteractiveRevolve(boolean fit){
        Profile p=profile(interactiveRevolveProfile);if(p==null)return false;
        Axis3D axis=axisFor(interactiveRevolveAxis,p.plane,interactiveRevolveXAxis);if(axis==null)return false;
        int steps=revolveSteps(interactiveRevolveAngle,interactiveRevolveHeightMm,p.points.size());
        SolidCSG preview=SolidCSG.helicalRevolve(p.points,p.plane,axis.origin,axis.direction,interactiveRevolveAngle,interactiveRevolveHeightMm,steps);
        if(preview==null||preview.isEmpty())return false;
        setFormPreview(preview,interactiveRevolveSummary(),fit);dispatchWorkspaceState();return true;
    }

    private void clearInteractiveRevolve(){
        interactiveRevolveActive=false;interactiveRevolveProfile=null;interactiveRevolveAxis=null;interactiveRevolveHeightMm=0f;
        revolveRingScreen.clear();revolveRingCenterScreen=null;revolveHeightBaseScreen=null;revolveHeightTipScreen=null;revolveDragMode=0;clearFormPreview();
    }

    @Override public String beginInteractiveExtrudeSession(){
        // ChobYarActivity starts the session directly.  Resolve the whole
        // region here (not only in showInteractiveExtrude), so touching one
        // edge of a closed outline behaves like selecting a Shapr-style face.
        if(!hasSelectedClosedProfile()){
            AutoProfile auto=autoProfile(selection(),false);
            if(auto!=null)selectAutoProfile(auto.profile);
        }
        return super.beginInteractiveExtrudeSession();
    }

    @Override public void showInteractiveExtrude(){toast(beginInteractiveExtrudeSession());}

    @Override protected List<Object> historySourceEntities(){
        List<Object> selected=super.historySourceEntities();
        List<Object> expanded=new ArrayList<>();
        for(Object entity:selected){
            List<Object> sources=autoProfileSources.get(entity);
            if(sources==null)expanded.add(entity);
            else for(Object source:sources)if(!expanded.contains(source))expanded.add(source);
        }
        return expanded;
    }

    private void showRevolveAngle(Object profile,Object axis,boolean xAxis){
        LinearLayout box=revolveInputs(360f,0f);EditText angle=(EditText)box.getChildAt(1),height=(EditText)box.getChildAt(3);
        new AlertDialog.Builder(getContext()).setTitle("Revolve • Angle + Height")
                .setMessage("Height صفر: دوران معمولی. Height غیرصفر: دوران مارپیچی برای رزوه.")
                .setView(box)
                .setPositiveButton("ساخت Body",(d,w)->{
                    try{float a=parseAngle(angle),h=parseMillimeters(height);validateRevolve(a,h);toast(createRevolve(profile,axis,xAxis,a,h));}
                    catch(Exception e){toast("زاویه یا Height درست وارد نشده");}
                }).setNegativeButton("لغو",null).show();
    }

    public String createRevolve(Object profileEntity,Object axisEntity,boolean xAxis,float angleDeg){
        return createRevolve(profileEntity,axisEntity,xAxis,angleDeg,0f);
    }

    public String createRevolve(Object profileEntity,Object axisEntity,boolean xAxis,float angleDeg,float heightMm){
        try{validateRevolve(angleDeg,heightMm);}catch(Exception e){return"زاویه Revolve باید 0.01 تا 36000 درجه و Height معتبر باشد";}
        Profile p=profile(profileEntity);if(p==null)return"پروفایل بسته معتبر نیست";
        Axis3D axis=axisFor(axisEntity,p.plane,xAxis);if(axis==null)return"محور Revolve معتبر نیست";
        RevolveFeature f=new RevolveFeature(formSerial++,profileEntity,p.plane,axisEntity,xAxis,angleDeg,heightMm);
        SolidCSG csg=f.build(this);if(csg==null||csg.isEmpty())return"Revolve نتوانست Body بسازد؛ پروفایل و محور را بررسی کن";
        Object body=addBody("Revolve "+f.id,csg);if(body==null)return"ساخت Body انجام نشد";
        f.outputBody=body;formHistory.add(f);setOverview3D();invalidate();
        if(Math.abs(heightMm)<1e-5f)return "Revolve ساخته شد • "+fmt(angleDeg)+"°";
        return "رزوه ساخته شد • "+fmt(Math.abs(angleDeg)/360f)+" دور • Height "+fmt(heightMm)+" mm • Pitch "+fmt(f.pitchMm())+" mm";
    }

    private LinearLayout revolveInputs(float angleDeg,float heightMm){
        LinearLayout box=new LinearLayout(getContext());box.setOrientation(LinearLayout.VERTICAL);
        int pad=(int)(16f*getResources().getDisplayMetrics().density);box.setPadding(pad,0,pad,0);
        TextView angleLabel=new TextView(getContext());angleLabel.setText("Angle • درجه (360 × تعداد دور)");box.addView(angleLabel);
        EditText angle=new EditText(getContext());angle.setSingleLine(true);angle.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL|InputType.TYPE_NUMBER_FLAG_SIGNED);
        angle.setText(fmt(angleDeg));angle.setSelectAllOnFocus(true);box.addView(angle);
        TextView heightLabel=new TextView(getContext());heightLabel.setText("Height • پیشروی کل روی محور (mm)");box.addView(heightLabel);
        EditText height=new EditText(getContext());height.setSingleLine(true);height.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL|InputType.TYPE_NUMBER_FLAG_SIGNED);
        height.setText(fmt(heightMm)+" mm");height.setSelectAllOnFocus(true);box.addView(height);
        return box;
    }

    private static float parseAngle(EditText input){
        String s=normalizeDigits(input.getText().toString()).trim().toLowerCase(Locale.US).replace("°","").replace("deg","").trim();
        return Float.parseFloat(s);
    }

    private static float parseMillimeters(EditText input){
        String s=normalizeDigits(input.getText().toString()).trim().toLowerCase(Locale.US).replace(" ","");
        if(s.endsWith("mm"))s=s.substring(0,s.length()-2);
        else if(s.endsWith("cm"))return Float.parseFloat(s.substring(0,s.length()-2))*10f;
        return Float.parseFloat(s);
    }

    private static void validateRevolve(float angleDeg,float heightMm){
        if(!Float.isFinite(angleDeg)||!Float.isFinite(heightMm)||Math.abs(angleDeg)<.01f||Math.abs(angleDeg)>36000f||Math.abs(heightMm)>100000f)throw new IllegalArgumentException();
    }

    /** Keeps dense circular profiles responsive while preserving thread flanks. */
    private int revolveSteps(float angleDeg,float heightMm,int profilePoints){
        float turns=Math.max(1f,Math.abs(angleDeg)/360f);
        int degreesPerStep=Math.abs(heightMm)<1e-5f?5:10;
        int desired=(int)Math.ceil(Math.abs(angleDeg)/degreesPerStep);
        if(profilePoints>24&&turns>2f)desired=(int)Math.ceil(Math.abs(angleDeg)/15f);
        return Math.max(12,Math.min(1440,desired));
    }

    // ------------------------------------------------------------------
    // On-canvas Revolve + Height manipulator
    // ------------------------------------------------------------------

    @Override protected void onDraw(Canvas canvas){
        super.onDraw(canvas);
        if(interactiveRevolveActive&&is3DOverview())drawInteractiveRevolveManipulator(canvas);
    }

    private void drawInteractiveRevolveManipulator(Canvas canvas){
        Profile p=profile(interactiveRevolveProfile);if(p==null||p.points.isEmpty())return;
        Axis3D axis=axisFor(interactiveRevolveAxis,p.plane,interactiveRevolveXAxis);if(axis==null)return;
        Geometry3D.Vec3 center=profileWorldCenter(p);
        Geometry3D.Vec3 axial=axis.direction.mul(axis.direction.dot(center.sub(axis.origin)));
        Geometry3D.Vec3 ringOrigin=axis.origin.add(axial);
        Geometry3D.Vec3 radial=center.sub(ringOrigin);
        float radius=radial.length();
        if(radius<1e-3f){radial=p.plane.u.sub(axis.direction.mul(p.plane.u.dot(axis.direction)));radius=Math.max(12f,profileRadius(p));}
        radial=radial.normalized().mul(Math.max(4f,radius));
        Geometry3D.Vec3 tangent=axis.direction.cross(radial).normalized().mul(radial.length());

        revolveRingScreen.clear();Path ring=new Path();
        for(int i=0;i<=72;i++){
            double a=2d*Math.PI*i/72d;
            Geometry3D.Vec3 point=ringOrigin.add(radial.mul((float)Math.cos(a))).add(tangent.mul((float)Math.sin(a)));
            PointF q=projectSolidPoint(point);revolveRingScreen.add(q);if(i==0)ring.moveTo(q.x,q.y);else ring.lineTo(q.x,q.y);
        }
        revolveRingCenterScreen=projectSolidPoint(ringOrigin);
        RectF card=solidViewport();canvas.save();if(card!=null&&!card.isEmpty())canvas.clipRect(card);
        canvas.drawPath(ring,revolveRingPaint);

        // Draw the actual simultaneous rotation/translation path.  This is the
        // direct visual explanation of pitch: one turn advances along the axis.
        int helixSamples=Math.max(36,Math.min(420,(int)Math.ceil(Math.abs(interactiveRevolveAngle)/12f)));
        Path helix=new Path();
        double total=Math.toRadians(interactiveRevolveAngle);
        for(int i=0;i<=helixSamples;i++){
            float t=(float)i/helixSamples;double a=total*t;
            Geometry3D.Vec3 point=ringOrigin
                    .add(radial.mul((float)Math.cos(a)))
                    .add(tangent.mul((float)Math.sin(a)))
                    .add(axis.direction.mul(interactiveRevolveHeightMm*t));
            PointF q=projectSolidPoint(point);if(i==0)helix.moveTo(q.x,q.y);else helix.lineTo(q.x,q.y);
        }
        canvas.drawPath(helix,revolveHeightPaint);

        float displayHeight=Math.abs(interactiveRevolveHeightMm)<.01f?Math.max(12f,radial.length()*.75f):interactiveRevolveHeightMm;
        Geometry3D.Vec3 heightStart=center,heightEnd=center.add(axis.direction.mul(displayHeight));
        revolveHeightBaseScreen=projectSolidPoint(heightStart);revolveHeightTipScreen=projectSolidPoint(heightEnd);
        float dx=revolveHeightTipScreen.x-revolveHeightBaseScreen.x,dy=revolveHeightTipScreen.y-revolveHeightBaseScreen.y;
        float screenLength=Math.max(1f,(float)Math.hypot(dx,dy));float ux=dx/screenLength,uy=dy/screenLength;
        revolveHeightPixelsPerMm=Math.max(.2f,screenLength/Math.max(1f,Math.abs(displayHeight)));
        canvas.drawLine(revolveHeightBaseScreen.x,revolveHeightBaseScreen.y,revolveHeightTipScreen.x,revolveHeightTipScreen.y,revolveHeightPaint);
        float wing=11f*getResources().getDisplayMetrics().density;
        canvas.drawLine(revolveHeightTipScreen.x,revolveHeightTipScreen.y,revolveHeightTipScreen.x-ux*wing-uy*wing*.55f,revolveHeightTipScreen.y-uy*wing+ux*wing*.55f,revolveHeightPaint);
        canvas.drawLine(revolveHeightTipScreen.x,revolveHeightTipScreen.y,revolveHeightTipScreen.x-ux*wing+uy*wing*.55f,revolveHeightTipScreen.y-uy*wing-ux*wing*.55f,revolveHeightPaint);
        float handle=7f*getResources().getDisplayMetrics().density;
        canvas.drawCircle(revolveHeightTipScreen.x,revolveHeightTipScreen.y,handle,revolveHandleFill);canvas.drawCircle(revolveHeightTipScreen.x,revolveHeightTipScreen.y,handle,revolveHeightPaint);
        canvas.restore();
        canvas.drawText(fmt(interactiveRevolveAngle)+"°",revolveRingCenterScreen.x,revolveRingCenterScreen.y-12f,revolveTextPaint);
        canvas.drawText("H "+fmt(interactiveRevolveHeightMm)+" mm",revolveHeightTipScreen.x,revolveHeightTipScreen.y-16f,revolveTextPaint);
    }

    @Override public boolean onTouchEvent(MotionEvent event){
        if(interactiveRevolveActive&&is3DOverview()&&event.getPointerCount()==1&&handleInteractiveRevolveTouch(event))return true;
        return super.onTouchEvent(event);
    }

    private boolean handleInteractiveRevolveTouch(MotionEvent event){
        int action=event.getActionMasked();float x=event.getX(),y=event.getY();float density=getResources().getDisplayMetrics().density;
        if(action==MotionEvent.ACTION_DOWN){
            float heightDistance=revolveHeightBaseScreen==null||revolveHeightTipScreen==null?Float.MAX_VALUE:
                    distanceToScreenSegment(x,y,revolveHeightBaseScreen,revolveHeightTipScreen);
            float ringDistance=distanceToScreenPolyline(x,y,revolveRingScreen);
            float hit=28f*density;if(heightDistance>hit&&ringDistance>hit)return false;
            revolveDragMode=heightDistance<=ringDistance?2:1;revolveDragMoved=false;revolveDragStartX=x;revolveDragStartY=y;
            revolveDragStartAngle=interactiveRevolveAngle;revolveDragStartHeight=interactiveRevolveHeightMm;return true;
        }
        if(revolveDragMode==0)return false;
        if(action==MotionEvent.ACTION_MOVE){
            float dx=x-revolveDragStartX,dy=y-revolveDragStartY;if(dx*dx+dy*dy>16f)revolveDragMoved=true;
            if(revolveDragMode==2&&revolveHeightBaseScreen!=null&&revolveHeightTipScreen!=null){
                float vx=revolveHeightTipScreen.x-revolveHeightBaseScreen.x,vy=revolveHeightTipScreen.y-revolveHeightBaseScreen.y;
                float len=Math.max(1f,(float)Math.hypot(vx,vy));float along=(dx*vx+dy*vy)/len;
                interactiveRevolveHeightMm=clamp(revolveDragStartHeight+along/revolveHeightPixelsPerMm,-100000f,100000f);
            }else if(revolveDragMode==1&&revolveRingCenterScreen!=null){
                double start=Math.atan2(revolveDragStartY-revolveRingCenterScreen.y,revolveDragStartX-revolveRingCenterScreen.x);
                double now=Math.atan2(y-revolveRingCenterScreen.y,x-revolveRingCenterScreen.x);double delta=Math.toDegrees(now-start);
                if(delta>180d)delta-=360d;if(delta<-180d)delta+=360d;
                float sign=revolveDragStartAngle<0f?-1f:1f;
                interactiveRevolveAngle=sign*clamp(Math.abs(revolveDragStartAngle)+(float)delta,1f,36000f);
            }
            refreshInteractiveRevolve(false);return true;
        }
        if(action==MotionEvent.ACTION_UP){boolean edit=!revolveDragMoved;revolveDragMode=0;if(edit)showInteractiveRevolveAngleEditor();return true;}
        if(action==MotionEvent.ACTION_CANCEL){revolveDragMode=0;return true;}return true;
    }

    private Geometry3D.Vec3 profileWorldCenter(Profile profile){
        Geometry3D.Vec3 c=new Geometry3D.Vec3(0,0,0);for(PointF q:profile.points)c=c.add(profile.plane.point(q.x,q.y));return c.mul(1f/profile.points.size());
    }

    private float profileRadius(Profile profile){
        PointF c=new PointF();for(PointF q:profile.points){c.x+=q.x;c.y+=q.y;}c.x/=profile.points.size();c.y/=profile.points.size();
        float r=0f;for(PointF q:profile.points)r=Math.max(r,(float)Math.hypot(q.x-c.x,q.y-c.y));return r;
    }

    private static float distanceToScreenPolyline(float x,float y,List<PointF> path){
        float best=Float.MAX_VALUE;for(int i=1;i<path.size();i++)best=Math.min(best,distanceToScreenSegment(x,y,path.get(i-1),path.get(i)));return best;
    }

    private static float distanceToScreenSegment(float x,float y,PointF a,PointF b){
        float dx=b.x-a.x,dy=b.y-a.y,l2=dx*dx+dy*dy;if(l2<1e-6f)return(float)Math.hypot(x-a.x,y-a.y);
        float t=Math.max(0f,Math.min(1f,((x-a.x)*dx+(y-a.y)*dy)/l2));return(float)Math.hypot(x-(a.x+t*dx),y-(a.y+t*dy));
    }

    private static float clamp(float value,float min,float max){return Math.max(min,Math.min(max,value));}

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
            RevolveFeature r=(RevolveFeature)f;LinearLayout box=revolveInputs(r.angleDeg,r.heightMm);
            EditText angle=(EditText)box.getChildAt(1),height=(EditText)box.getChildAt(3);
            new AlertDialog.Builder(getContext()).setTitle("ویرایش "+r.detail())
                    .setMessage("Angle و Height را عوض کن؛ Body و عملیات وابسته دوباره محاسبه می‌شوند.")
                    .setView(box).setPositiveButton("اعمال",(d,w)->{try{float a=parseAngle(angle),h=parseMillimeters(height);validateRevolve(a,h);r.angleDeg=a;r.heightMm=h;toast(rebuildHistory());}catch(Exception e){toast("زاویه یا Height درست نیست");}})
                    .setNegativeButton("بستن",null).show();
            return;
        }
        new AlertDialog.Builder(getContext()).setTitle(f.detail())
                .setMessage(f instanceof SweepFeature?"Sweep به پروفایل و مسیر انتخاب‌شده وابسته است. مسیر یا پروفایل را ویرایش کن تا Body خودکار بازسازی شود.":"Loft به هر دو پروفایل منبع وابسته است. هر کدام را ویرایش کنی Body دوباره ساخته می‌شود.")
                .setPositiveButton("بازسازی",(d,w)->toast(rebuildHistory())).setNegativeButton("بستن",null).show();
    }

    @Override
    public void clearAll(){super.clearAll();formHistory.clear();autoProfileSources.clear();formSerial=1;}

    // ------------------------------------------------------------------
    // Profile/path extraction
    // ------------------------------------------------------------------

    private Profile profile(Object e){
        if(e==null||!entities().contains(e))return null;
        String type=e.getClass().getSimpleName();String layer=entityLayer(e);Geometry3D.Plane3D plane=planeForLayer(layer);
        List<Object> derivedSources=autoProfileSources.get(e);
        if(derivedSources!=null){
            for(Object source:derivedSources)if(!entities().contains(source))return null;
            List<PointF> fresh=stitchLoop(derivedSources);
            return fresh==null?null:new Profile(e,fresh,layer,plane);
        }
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
    private static final class LoopMatch{
        final List<PointF> points;final List<Object> lines;
        LoopMatch(List<PointF> points,List<Object> lines){this.points=points;this.lines=lines;}
    }

    private boolean hasSelectedClosedProfile(){for(Object e:selection())if(isClosedProfile(e))return true;return false;}

    private AutoProfile autoProfile(List<Object> picked,boolean allowAxis){
        String layer=null;Object explicitAxis=null;
        for(Object e:picked){if(e==null)continue;if(layer==null)layer=entityLayer(e);if(isClosedProfile(e))return new AutoProfile(e,null);}
        if(layer==null)layer=getCurrentLayer();

        // When there is one ordinary closed entity on the active Sketch, the
        // user should not have to reselect it after pressing Done.  A selected
        // Line is still retained as the explicit Revolve axis.
        List<Object> closed=new ArrayList<>();
        for(Object e:entities())if(isClosedProfile(e)&&!boolField(e,"construction")&&layer.equals(entityLayer(e)))closed.add(e);
        if(closed.size()==1&&picked.isEmpty())return new AutoProfile(closed.get(0),null);

        List<Object> lines=new ArrayList<>();
        for(Object e:entities())if(isLine(e)&&!boolField(e,"construction")&&layer.equals(entityLayer(e)))lines.add(e);
        List<Object> selectedLines=new ArrayList<>();for(Object e:picked)if(isLine(e))selectedLines.add(e);
        if(lines.size()<3){
            if(allowAxis&&closed.size()==1&&!selectedLines.isEmpty())return new AutoProfile(closed.get(0),selectedLines.get(0));
            return null;
        }

        LoopMatch match=null;
        // First respect an explicitly multi-selected loop.
        List<PointF> exact=stitchLoop(selectedLines);
        if(exact!=null)match=new LoopMatch(exact,new ArrayList<>(selectedLines));
        // The common mobile workflow is a single tap on any boundary edge.
        if(match==null)for(Object seed:selectedLines){match=findLoopContaining(lines,seed);if(match!=null)break;}

        if(match!=null&&allowAxis){
            for(Object line:selectedLines)if(!containsIdentity(match.lines,line)){explicitAxis=line;break;}
        }
        if(match==null){
            if(allowAxis&&!selectedLines.isEmpty()&&closed.size()==1)
                return new AutoProfile(closed.get(0),selectedLines.get(0));
            List<Object> candidates=new ArrayList<>(lines);
            if(allowAxis&&!selectedLines.isEmpty()){
                // If the picked line is not part of a loop, it is almost always
                // the centerline in a turning profile.
                explicitAxis=selectedLines.get(0);removeIdentity(candidates,explicitAxis);
            }else if(!selectedLines.isEmpty())return null;
            match=findUniqueLoop(candidates);
        }
        if(match==null)return null;
        Object profile=findExistingAutoProfile(match.lines);
        if(profile==null)profile=addAutoPolyline(match.points,layer,match.lines);
        if(profile==null)return null;
        return new AutoProfile(profile,explicitAxis);
    }

    private Object addAutoPolyline(List<PointF> loop,String layer,List<Object> sources){
        try{
            if(autoPolylineConstructor==null)return null;if(saveUndoMethod!=null)saveUndoMethod.invoke(this);
            List<PointF> copy=new ArrayList<>();for(PointF p:loop)copy.add(new PointF(p.x,p.y));
            Object entity=autoPolylineConstructor.newInstance(copy,true);Method setLayer=findMethod(entity.getClass(),"setLayer",String.class);
            if(setLayer!=null)setLayer.invoke(entity,layer);entities().add(entity);
            autoProfileSources.put(entity,new ArrayList<>(sources));selectAutoProfile(entity);invalidate();return entity;
        }catch(Exception e){return null;}
    }

    private Object findExistingAutoProfile(List<Object> sources){
        for(Map.Entry<Object,List<Object>> entry:autoProfileSources.entrySet()){
            if(entities().contains(entry.getKey())&&sameIdentitySet(entry.getValue(),sources))return entry.getKey();
        }
        return null;
    }

    @SuppressWarnings("unchecked") private void selectAutoProfile(Object entity){
        try{selectedField.set(this,entity);Object v=selectedObjectsField.get(this);if(v instanceof List){List<Object>s=(List<Object>)v;s.clear();s.add(entity);}}
        catch(Exception ignored){}
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

    /** Finds a simple closed cycle that contains the edge the user touched. */
    private LoopMatch findLoopContaining(List<Object> all,Object seed){
        if(seed==null||!containsIdentity(all,seed)||all.size()>256)return null;
        for(int direction=0;direction<2;direction++){
            PointF start=lineEnd(seed,direction),next=lineEnd(seed,1-direction);
            if(start==null||next==null||distance(start,next)<1e-5f)continue;
            List<Object> used=new ArrayList<>();used.add(seed);
            List<PointF> points=new ArrayList<>();points.add(start);points.add(next);
            LoopMatch found=searchLoop(all,start,next,used,points);
            if(found!=null)return found;
        }
        return null;
    }

    private LoopMatch searchLoop(List<Object> all,PointF start,PointF current,List<Object> used,List<PointF> points){
        if(used.size()>=3&&distance(current,start)<=0.35f){
            List<PointF> result=new ArrayList<>(points);result.remove(result.size()-1);
            return result.size()>=3?new LoopMatch(result,new ArrayList<>(used)):null;
        }
        if(used.size()>=all.size()||used.size()>=256)return null;
        for(Object line:all){
            if(containsIdentity(used,line))continue;
            PointF a=lineEnd(line,0),b=lineEnd(line,1);PointF next=null;
            if(a!=null&&distance(current,a)<=0.35f)next=b;
            else if(b!=null&&distance(current,b)<=0.35f)next=a;
            if(next==null)continue;
            used.add(line);points.add(next);
            LoopMatch found=searchLoop(all,start,next,used,points);
            points.remove(points.size()-1);used.remove(used.size()-1);
            if(found!=null)return found;
        }
        return null;
    }

    /** Returns a loop only when the unselected Sketch has one unambiguous region. */
    private LoopMatch findUniqueLoop(List<Object> lines){
        LoopMatch unique=null;
        for(Object seed:lines){
            LoopMatch found=findLoopContaining(lines,seed);if(found==null)continue;
            if(unique==null)unique=found;
            else if(!sameIdentitySet(unique.lines,found.lines))return null;
        }
        return unique;
    }

    private static boolean containsIdentity(List<Object> list,Object target){for(Object value:list)if(value==target)return true;return false;}
    private static void removeIdentity(List<Object> list,Object target){for(int i=list.size()-1;i>=0;i--)if(list.get(i)==target)list.remove(i);}
    private static boolean sameIdentitySet(List<Object>a,List<Object>b){
        if(a.size()!=b.size())return false;for(Object value:a)if(!containsIdentity(b,value))return false;return true;
    }

    private static PointF lineEnd(Object e,int which){return which==0?new PointF(getFloat(e,"x1"),getFloat(e,"y1")):new PointF(getFloat(e,"x2"),getFloat(e,"y2"));}
    private static float distance(PointF a,PointF b){return(float)Math.hypot(a.x-b.x,a.y-b.y);}
    private static Method findMethod(Class<?> c,String name,Class<?>...types){for(Class<?> x=c;x!=null;x=x.getSuperclass())try{Method m=x.getDeclaredMethod(name,types);m.setAccessible(true);return m;}catch(Exception ignored){}return null;}

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

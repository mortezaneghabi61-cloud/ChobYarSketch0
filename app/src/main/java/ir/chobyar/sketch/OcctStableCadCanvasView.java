package ir.chobyar.sketch;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PointF;
import android.text.InputType;
import android.view.MotionEvent;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Stable-topology layer for exact OCCT direct modeling.
 *
 * Every referenced Face/Edge receives a logical ID (B#:F# / B#:E#). Direct
 * features keep that reference instead of a raw transient OCCT index. During
 * History replay OcctTopologyRef rematches the logical topology on the current
 * TopoDS_Shape before applying Fillet/Chamfer/PushPull/Shell. This lets upstream
 * Extrude/Revolve/Sweep/Loft/Boolean changes rebuild direct edits deterministically
 * for common modeling cases without depending on OCCT's temporary sub-shape order.
 */
public class OcctStableCadCanvasView extends OcctDirectCadCanvasView {

    private enum Kind { FILLET, CHAMFER, PUSH_PULL, SHELL, MOVE, ROTATE, SCALE, MIRROR, PATTERN }

    private static final class StableEdit {
        final int id;
        final Kind kind;
        double value;
        Geometry3D.Vec3 vector;
        final OcctTopologyRef.Ref target;
        boolean broken;
        String warning="";

        StableEdit(int id,Kind kind,double value,Geometry3D.Vec3 vector,OcctTopologyRef.Ref target){
            this.id=id;this.kind=kind;this.value=value;this.vector=vector;this.target=target;
        }

        String label(){
            String prefix="D"+id+" • ";
            switch(kind){
                case FILLET:return prefix+"Fillet • "+dual(value)+topologySuffix();
                case CHAMFER:return prefix+"Chamfer • "+dual(value)+topologySuffix();
                case PUSH_PULL:return prefix+"Push/Pull • "+signedDual(value)+topologySuffix();
                case SHELL:return prefix+"Shell • "+dual(value)+topologySuffix();
                case MOVE:return prefix+"Move • X "+dual(vector.x)+" • Y "+dual(vector.y)+" • Z "+dual(vector.z);
                case ROTATE:return prefix+"Rotate "+axisName(vector)+" • "+num(value)+"°";
                case SCALE:return prefix+"Scale • ×"+num(value);
                case MIRROR:return prefix+"Mirror • plane "+axisName(vector);
                default:return prefix+"Linear Pattern • "+(int)value+" copies • step "+dual(vector.length());
            }
        }

        private String topologySuffix(){return target==null?"":" • "+target.id;}
    }

    private static final class TimelineEntry {
        final Object body;
        final StableEdit edit;
        TimelineEntry(Object body,StableEdit edit){this.body=body;this.edit=edit;}
    }

    private static final class ManualCopy {
        final Object source,output;final Geometry3D.Vec3 move,rotate;
        ManualCopy(Object source,Object output,Geometry3D.Vec3 move,Geometry3D.Vec3 rotate){this.source=source;this.output=output;this.move=move;this.rotate=rotate;}
    }

    private final IdentityHashMap<Object,List<StableEdit>> stableByBody=new IdentityHashMap<>();
    private final List<TimelineEntry> timeline=new ArrayList<>();
    private final List<ManualCopy> manualCopies=new ArrayList<>();
    private int directSerial=1;
    private int topologySerial=1;

    private Field nativeByBodyField;
    private Constructor<?> nativeRecordConstructor;
    private Field recordHandleField;
    private Field recordKindField;
    private Field selectedBodyField;
    private Field selectedFaceField;
    private Field bodiesField;
    private Method parentSyncMethod;
    private Method projectMethod;

    private boolean edgePickMode=false;
    private boolean edgeMoved=false;
    private float edgeDownX,edgeDownY;
    private Geometry3D.Vec3 edgeA,edgeB,edgeAnchor;
    private OcctTopologyRef.Ref selectedEdgeRef;
    private Object selectedEdgeBody;

    private final Paint stableEdgePaint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint featurePreviewPaint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private long featurePreviewHandle=0L;
    private double[] featurePreviewMesh=new double[0];

    private boolean bodyTransformActive;
    private boolean bodyTransformCopy;
    private Object bodyTransformBody;
    private Geometry3D.Vec3 bodyTransformMove=new Geometry3D.Vec3(0,0,0);
    private Geometry3D.Vec3 bodyTransformRotate=new Geometry3D.Vec3(0,0,0);
    private Geometry3D.Vec3 bodyTransformCenter;
    private final PointF[] bodyAxisTips={new PointF(),new PointF(),new PointF()};
    @SuppressWarnings("unchecked")
    private final List<PointF>[] bodyRotationRings=new List[]{new ArrayList<>(),new ArrayList<>(),new ArrayList<>()};
    private PointF bodyGizmoOrigin;
    private int bodyGizmoDrag;
    private boolean bodyGizmoMoved;
    private float bodyGizmoDownX,bodyGizmoDownY,bodyGizmoStartValue;
    private long lastBodyPreviewNs;
    private final Paint[] bodyAxisPaint={new Paint(Paint.ANTI_ALIAS_FLAG),new Paint(Paint.ANTI_ALIAS_FLAG),new Paint(Paint.ANTI_ALIAS_FLAG)};
    private final Paint bodyGizmoText=new Paint(Paint.ANTI_ALIAS_FLAG);

    private boolean alignActive,alignReady,alignFlip=true;
    private Object alignBody;
    private Geometry3D.Vec3 alignSourcePoint,alignSourceNormal,alignTargetPoint,alignTargetNormal;
    private Geometry3D.Vec3 alignRotationAxis=new Geometry3D.Vec3(0,0,1),alignMove=new Geometry3D.Vec3(0,0,0);
    private float alignRotationDeg;
    private final Paint alignPaint=new Paint(Paint.ANTI_ALIAS_FLAG);

    public OcctStableCadCanvasView(Context context){
        super(context);
        stableEdgePaint.setColor(Color.rgb(0,115,210));
        stableEdgePaint.setStrokeWidth(9f);
        stableEdgePaint.setStrokeCap(Paint.Cap.ROUND);
        featurePreviewPaint.setColor(Color.rgb(0,120,225));
        featurePreviewPaint.setStrokeWidth(2.4f);
        featurePreviewPaint.setStyle(Paint.Style.STROKE);
        int[] colors={Color.rgb(220,67,73),Color.rgb(34,157,93),Color.rgb(43,111,222)};float density=getResources().getDisplayMetrics().density;
        for(int i=0;i<3;i++){bodyAxisPaint[i].setColor(colors[i]);bodyAxisPaint[i].setStrokeWidth(3f*density);bodyAxisPaint[i].setStyle(Paint.Style.STROKE);bodyAxisPaint[i].setStrokeCap(Paint.Cap.ROUND);}
        bodyGizmoText.setColor(Color.rgb(39,54,75));bodyGizmoText.setTextSize(12f*density);bodyGizmoText.setTextAlign(Paint.Align.CENTER);
        alignPaint.setColor(Color.rgb(139,83,205));alignPaint.setStrokeWidth(3f*density);alignPaint.setStyle(Paint.Style.STROKE);alignPaint.setStrokeCap(Paint.Cap.ROUND);
        initStableReflection();
    }

    private void initStableReflection(){
        try{
            nativeByBodyField=field(OcctModelCadCanvasView.class,"nativeByBody");
            selectedBodyField=field(SolidCadCanvasView.class,"selectedBody");
            selectedFaceField=field(SolidCadCanvasView.class,"selectedFace");
            bodiesField=field(SolidCadCanvasView.class,"bodies");
            parentSyncMethod=OcctModelCadCanvasView.class.getDeclaredMethod("syncNativeHistory",boolean.class);
            parentSyncMethod.setAccessible(true);
            projectMethod=SpatialCadCanvasView.class.getDeclaredMethod("project",Geometry3D.Vec3.class);
            projectMethod.setAccessible(true);
            for(Class<?> c:OcctModelCadCanvasView.class.getDeclaredClasses()){
                if(!"NativeRecord".equals(c.getSimpleName()))continue;
                nativeRecordConstructor=c.getDeclaredConstructor(long.class,String.class,double[].class);
                nativeRecordConstructor.setAccessible(true);
                recordHandleField=field(c,"handle");
                recordKindField=field(c,"kind");
                break;
            }
        }catch(Exception ignored){}
    }

    private static Field field(Class<?> owner,String name)throws NoSuchFieldException{
        Field f=owner.getDeclaredField(name);f.setAccessible(true);return f;
    }

    // ------------------------------------------------------------------
    // Exact direct-modeling UI using stable Face/Edge references
    // ------------------------------------------------------------------

    @Override
    public void showDirectManager(){
        pruneDeadBodies();
        Object body=selectedBody();Object record=body==null?null:ensureNativeRecord(body);
        String state=body==null?"Body انتخاب نشده"
                :record==null?bodyName(body)+" • Shape دقیق آماده نیست"
                :bodyName(body)+" • OCCT Exact • Topology naming روشن";
        String edge=selectedEdgeRef==null||selectedEdgeBody!=body?"Edge انتخاب نشده":"Edge: "+selectedEdgeRef.id;
        SolidCSG.Polygon face=selectedFace();
        String faceState=face==null?"Face انتخاب نشده":"Face آماده ثبت شناسه پایدار";
        String[] items={
                "⌁ انتخاب Edge دقیق / Stable Edge ID",
                "⌒ Fillet روی Edge انتخاب‌شده",
                "◩ Chamfer روی Edge انتخاب‌شده",
                "↕ Push/Pull روی Face انتخاب‌شده",
                "▱ Shell — بازکردن Face انتخاب‌شده",
                "↔ Move Body در X / Y / Z",
                "⟳ Rotate Body حول X / Y / Z",
                "⇲ Scale Body دقیق",
                "⇋ Mirror حول صفحه مرکزی",
                "⠿ Linear Pattern",
                "⏱ History پارامتریک + OCCT",
                "↶ Undo آخرین Direct Feature",
                "⌘ ابزار Exact قبلی / Inspector"
        };
        new AlertDialog.Builder(getContext()).setTitle("Edit 3D • Stable OCCT")
                .setMessage(state+"\n"+edge+"\n"+faceState
                        +"\n\nFace/Edge به شناسه منطقی متصل می‌شود و بعد از تغییر Featureهای قبلی دوباره روی Shape جدید پیدا می‌شود.")
                .setItems(items,(d,w)->{
                    if(w==0)beginEdgePick();
                    else if(w==1)askEdgeFeature(Kind.FILLET);
                    else if(w==2)askEdgeFeature(Kind.CHAMFER);
                    else if(w==3)askFaceFeature(Kind.PUSH_PULL);
                    else if(w==4)askFaceFeature(Kind.SHELL);
                    else if(w==5)showMoveDialog(null);
                    else if(w==6)showRotateAxis(null);
                    else if(w==7)showScaleTool();
                    else if(w==8)showMirrorTool();
                    else if(w==9)showLinearPatternTool();
                    else if(w==10)showHistoryManager();
                    else if(w==11)toast(undoStable());
                    else OcctStableCadCanvasView.super.showDirectManager();
                }).setNegativeButton("بستن",null).show();
    }

    @Override
    public void showFinishManager(){showDirectManager();}

    private void beginEdgePick(){
        Object body=selectedBody();if(body==null){ensure3D();toast("اول Body را انتخاب کن");return;}
        if(ensureNativeRecord(body)==null){toast("Shape دقیق این Body آماده نیست");return;}
        ensure3D();edgePickMode=true;edgeA=edgeB=edgeAnchor=null;selectedEdgeRef=null;selectedEdgeBody=null;invalidate();
        toast("روی لبه بزن؛ برایش Stable Edge ID ساخته می‌شود");
    }

    private void askEdgeFeature(Kind kind){
        Object body=selectedBody();
        if(body==null||selectedEdgeRef==null||selectedEdgeBody!=body){beginEdgePick();return;}
        String title=kind==Kind.FILLET?"Fillet — شعاع":"Chamfer — فاصله";
        askLength(title,"مثال: 5mm یا 0.5cm","5mm",false,v->{
            if(v<=0){toast("مقدار باید بزرگ‌تر از صفر باشد");return;}
            recordStable(body,new StableEdit(directSerial++,kind,v,null,selectedEdgeRef));
        });
    }

    public void showSelectedFillet(){showEdgeFeaturePreview(Kind.FILLET);}
    public void showSelectedChamfer(){showEdgeFeaturePreview(Kind.CHAMFER);}
    public void showSelectedPushPull(){showFaceFeaturePreview(Kind.PUSH_PULL);}
    public void showSelectedShell(){showFaceFeaturePreview(Kind.SHELL);}

    private void showEdgeFeaturePreview(Kind kind){
        Object body=selectedBody();
        if(body==null||selectedEdgeRef==null||selectedEdgeBody!=body){toast("اول Edge را لمس کن");return;}
        Object record=ensureNativeRecord(body);if(record==null){toast("Shape دقیق آماده نیست");return;}
        LinearLayout box=new LinearLayout(getContext());box.setOrientation(LinearLayout.VERTICAL);box.setPadding(dp(20),dp(6),dp(20),0);
        TextView value=new TextView(getContext());value.setTextSize(18f);
        SeekBar slider=new SeekBar(getContext());slider.setMax(200);slider.setProgress(20);
        EditText exact=new EditText(getContext());exact.setSingleLine();exact.setText("2mm");exact.setSelectAllOnFocus(true);
        box.addView(value);box.addView(slider);box.addView(exact);
        final double[] amount={2.0};
        Runnable preview=()->{value.setText((kind==Kind.FILLET?"Radius  ":"Distance  ")+num(amount[0])+" mm");updateEdgePreview(record,kind,amount[0]);};
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onProgressChanged(SeekBar s,int progress,boolean fromUser){if(fromUser){amount[0]=Math.max(.1,progress/10.0);exact.setText(num(amount[0])+"mm");}}
            public void onStartTrackingTouch(SeekBar s){}
            public void onStopTrackingTouch(SeekBar s){preview.run();}
        });
        preview.run();
        AlertDialog dialog=new AlertDialog.Builder(getContext()).setTitle(kind==Kind.FILLET?"Fillet":"Chamfer")
                .setMessage(selectedEdgeRef.id+" • پیش‌نمایش دقیق OCCT").setView(box)
                .setPositiveButton("Done",null).setNegativeButton("Cancel",null).create();
        dialog.setOnShowListener(x->{
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
                try{double mm=Math.abs(parseLengthMm(exact.getText().toString()));if(mm<=0)throw new IllegalArgumentException();
                    clearFeaturePreview();dialog.dismiss();recordStable(body,new StableEdit(directSerial++,kind,mm,null,selectedEdgeRef));
                }catch(Exception e){exact.setError("مثال: 2mm یا 0.2cm");}
            });
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(v->{clearFeaturePreview();dialog.dismiss();});
        });
        dialog.setOnCancelListener(x->clearFeaturePreview());dialog.show();
    }

    private void updateEdgePreview(Object record,Kind kind,double mm){
        clearFeaturePreview();long base=recordHandle(record);if(base==0L)return;
        OcctTopologyRef.Resolution resolved=OcctTopologyRef.resolve(base,selectedEdgeRef);
        if(resolved==null||!resolved.confident())return;
        featurePreviewHandle=kind==Kind.FILLET
                ?NativeBRepKernel.occtFillet(base,resolved.anchor,mm,false)
                :NativeBRepKernel.occtChamfer(base,resolved.anchor,mm,false);
        if(featurePreviewHandle!=0L)featurePreviewMesh=NativeBRepKernel.occtTriangulate(featurePreviewHandle,.24);
        invalidate();
    }

    private void showFaceFeaturePreview(Kind kind){
        Object body=selectedBody();SolidCSG.Polygon face=selectedFace();
        if(body==null||face==null){toast("اول Face را لمس کن");return;}
        Object record=ensureNativeRecord(body);if(record==null){toast("Shape دقیق آماده نیست");return;}
        String id=nextTopologyId(body,OcctTopologyRef.FACE);
        OcctTopologyRef.Ref ref=OcctTopologyRef.captureFace(recordHandle(record),face.centroid(),id);
        if(ref==null){toast("Face دقیق پیدا نشد");return;}

        LinearLayout box=new LinearLayout(getContext());box.setOrientation(LinearLayout.VERTICAL);box.setPadding(dp(20),dp(6),dp(20),0);
        TextView value=new TextView(getContext());value.setTextSize(18f);
        SeekBar slider=new SeekBar(getContext());
        EditText exact=new EditText(getContext());exact.setSingleLine();exact.setSelectAllOnFocus(true);
        final double[] amount={kind==Kind.PUSH_PULL?10.0:2.0};
        if(kind==Kind.PUSH_PULL){slider.setMax(4000);slider.setProgress(2100);exact.setText("10mm");}
        else{slider.setMax(200);slider.setProgress(20);exact.setText("2mm");}
        box.addView(value);box.addView(slider);box.addView(exact);
        Runnable preview=()->{value.setText((kind==Kind.PUSH_PULL?"Offset  ":"Thickness  ")+num(amount[0])+" mm");updateFacePreview(record,ref,kind,amount[0]);};
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onProgressChanged(SeekBar s,int progress,boolean fromUser){if(fromUser){amount[0]=kind==Kind.PUSH_PULL?(progress-2000)/10.0:Math.max(.1,progress/10.0);exact.setText(num(amount[0])+"mm");}}
            public void onStartTrackingTouch(SeekBar s){}
            public void onStopTrackingTouch(SeekBar s){preview.run();}
        });
        preview.run();
        String title=kind==Kind.PUSH_PULL?"Offset Face / Push-Pull":"Shell";
        AlertDialog dialog=new AlertDialog.Builder(getContext()).setTitle(title)
                .setMessage(ref.id+" • پیش‌نمایش B-Rep دقیق").setView(box)
                .setPositiveButton("Done",null).setNegativeButton("Cancel",null).create();
        dialog.setOnShowListener(x->{
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
                try{double mm=parseLengthMm(exact.getText().toString());if(kind==Kind.SHELL)mm=Math.abs(mm);
                    if(Math.abs(mm)<1e-8)throw new IllegalArgumentException();clearFeaturePreview();dialog.dismiss();
                    recordStable(body,new StableEdit(directSerial++,kind,mm,null,ref));
                }catch(Exception e){exact.setError(kind==Kind.PUSH_PULL?"مثال: -5mm یا 1cm":"مثال: 2mm");}
            });
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(v->{clearFeaturePreview();dialog.dismiss();});
        });
        dialog.setOnCancelListener(x->clearFeaturePreview());dialog.show();
    }

    private void updateFacePreview(Object record,OcctTopologyRef.Ref ref,Kind kind,double mm){
        clearFeaturePreview();long base=recordHandle(record);if(base==0L||Math.abs(mm)<1e-8)return;
        OcctTopologyRef.Resolution resolved=OcctTopologyRef.resolve(base,ref);
        if(resolved==null||!resolved.confident())return;
        featurePreviewHandle=kind==Kind.PUSH_PULL
                ?NativeBRepKernel.occtPushPullFace(base,resolved.anchor,mm)
                :NativeBRepKernel.occtShell(base,resolved.anchor,Math.abs(mm));
        if(featurePreviewHandle!=0L)featurePreviewMesh=NativeBRepKernel.occtTriangulate(featurePreviewHandle,.24);
        invalidate();
    }

    private void clearFeaturePreview(){
        if(featurePreviewHandle!=0L)NativeBRepKernel.occtRelease(featurePreviewHandle);
        featurePreviewHandle=0L;featurePreviewMesh=new double[0];invalidate();
    }

    private void askFaceFeature(Kind kind){
        Object body=selectedBody();SolidCSG.Polygon face=selectedFace();
        if(body==null||face==null){ensure3D();toast("اول Face موردنظر را لمس کن");return;}
        Object record=ensureNativeRecord(body);if(record==null){toast("Shape دقیق آماده نیست");return;}
        String id=nextTopologyId(body,OcctTopologyRef.FACE);
        OcctTopologyRef.Ref ref=OcctTopologyRef.captureFace(recordHandle(record),face.centroid(),id);
        if(ref==null){toast("برای این Face شناسه پایدار ساخته نشد");return;}
        if(kind==Kind.PUSH_PULL){
            askLength("Push/Pull Face","مثبت = بیرون، منفی = داخل؛ "+ref.id,"10mm",true,v->{
                if(Math.abs(v)<1e-8){toast("فاصله نباید صفر باشد");return;}
                recordStable(body,new StableEdit(directSerial++,Kind.PUSH_PULL,v,null,ref));
            });
        }else{
            askLength("Shell — ضخامت","Face بازشونده: "+ref.id+" • مثال 2mm","2mm",false,v->{
                if(v<=0){toast("ضخامت باید بزرگ‌تر از صفر باشد");return;}
                recordStable(body,new StableEdit(directSerial++,Kind.SHELL,v,null,ref));
            });
        }
    }

    private interface LengthConsumer{void accept(double mm);}
    private void askLength(String title,String message,String initial,boolean signed,LengthConsumer consumer){
        EditText input=new EditText(getContext());input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setText(initial);input.setSelectAllOnFocus(true);
        new AlertDialog.Builder(getContext()).setTitle(title+" • mm").setMessage(message).setView(input)
                .setPositiveButton("اعمال",(d,w)->{
                    try{double v=parseLengthMm(input.getText().toString());if(!signed)v=Math.abs(v);consumer.accept(v);}
                    catch(Exception e){toast("اندازه درست وارد نشده");}
                }).setNegativeButton("لغو",null).show();
    }

    private void showMoveDialog(StableEdit editing){
        Object body=editing==null?selectedBody():bodyFor(editing);if(body==null){toast("اول Body را انتخاب کن");return;}
        Geometry3D.Vec3 old=editing==null?new Geometry3D.Vec3(0,0,0):editing.vector;
        LinearLayout box=new LinearLayout(getContext());box.setOrientation(LinearLayout.VERTICAL);box.setPadding(dp(20),dp(8),dp(20),0);
        EditText x=axisInput(box,"X",mmText(old.x)),y=axisInput(box,"Y",mmText(old.y)),z=axisInput(box,"Z",mmText(old.z));
        new AlertDialog.Builder(getContext()).setTitle((editing==null?"Move Body":"ویرایش D"+editing.id)+" • mm")
                .setView(box).setPositiveButton("اعمال",(d,w)->{
                    try{
                        Geometry3D.Vec3 v=new Geometry3D.Vec3((float)parseLengthMm(x.getText().toString()),
                                (float)parseLengthMm(y.getText().toString()),(float)parseLengthMm(z.getText().toString()));
                        if(v.length()<1e-8){toast("حرکت صفر است");return;}
                        if(editing==null)recordStable(body,new StableEdit(directSerial++,Kind.MOVE,0,v,null));
                        else{editing.vector=v;toast(rebuildAllStable());}
                    }catch(Exception e){toast("مقدار X/Y/Z درست نیست");}
                }).setNegativeButton("لغو",null).show();
    }

    /** Entry used by the production workspace: transform stays on the canvas. */
    public void showBodyMoveTool(){
        String result=beginBodyTransformSession();toast(result);
    }

    public String beginBodyTransformSession(){
        Object body=selectedBody();if(body==null)return "اول Body را انتخاب کن";
        Object record=ensureNativeRecord(body);if(record==null)return "Shape دقیق این Body آماده نیست";
        clearFeaturePreview();bodyTransformBody=body;bodyTransformActive=true;
        bodyTransformCopy=false;
        bodyTransformMove=new Geometry3D.Vec3(0,0,0);bodyTransformRotate=new Geometry3D.Vec3(0,0,0);
        bodyTransformCenter=bodyCenter(bodyCsg(body));bodyGizmoDrag=0;ensure3D();invalidate();dispatchWorkspaceState();
        return "Move / Rotate • فلش‌های X/Y/Z یا حلقه‌ها را بکش";
    }

    public boolean isBodyTransformSessionActive(){return bodyTransformActive;}

    public String bodyTransformSummary(){
        if(!bodyTransformActive)return "Move / Rotate";
        String copy=bodyTransformCopy?"Copy • ":"";
        if(bodyTransformRotate.length()>1e-4f)return copy+"R  "+num(bodyTransformRotate.x)+"°  "+num(bodyTransformRotate.y)+"°  "+num(bodyTransformRotate.z)+"°";
        return copy+"X "+mmText(bodyTransformMove.x)+"  Y "+mmText(bodyTransformMove.y)+"  Z "+mmText(bodyTransformMove.z);
    }

    public String toggleBodyTransformCopy(){if(!bodyTransformActive)return "Move / Rotate فعال نیست";bodyTransformCopy=!bodyTransformCopy;invalidate();dispatchWorkspaceState();return bodyTransformCopy?"Copy روشن شد • بدنهٔ جدید ساخته می‌شود":"Copy خاموش شد";}
    public boolean isBodyTransformCopy(){return bodyTransformCopy;}

    public String commitBodyTransformSession(){
        if(!bodyTransformActive||bodyTransformBody==null)return "Move / Rotate فعال نیست";
        Object body=bodyTransformBody;Geometry3D.Vec3 move=bodyTransformMove,rotate=bodyTransformRotate;boolean copy=bodyTransformCopy;
        if(copy){
            String result=commitExactBodyCopy(body,move,rotate);endBodyTransformPreview();return result;
        }
        endBodyTransformPreview();int count=0;
        if(move.length()>1e-5f){recordStable(body,new StableEdit(directSerial++,Kind.MOVE,0,move,null));count++;}
        Geometry3D.Vec3[] axes={new Geometry3D.Vec3(1,0,0),new Geometry3D.Vec3(0,1,0),new Geometry3D.Vec3(0,0,1)};
        float[] angles={rotate.x,rotate.y,rotate.z};
        for(int i=0;i<3;i++)if(Math.abs(angles[i])>1e-4f){recordStable(body,new StableEdit(directSerial++,Kind.ROTATE,angles[i],axes[i],null));count++;}
        return count==0?"Transform بدون تغییر بسته شد":"Move / Rotate دقیق ثبت شد • "+count+" Feature";
    }

    public void cancelBodyTransformSession(){if(!bodyTransformActive)return;endBodyTransformPreview();dispatchWorkspaceState();}

    private void endBodyTransformPreview(){
        clearFeaturePreview();bodyTransformActive=false;bodyTransformCopy=false;bodyTransformBody=null;bodyTransformCenter=null;bodyGizmoDrag=0;invalidate();
    }

    public void showBodyTransformExactEditor(){
        if(!bodyTransformActive)return;
        LinearLayout box=new LinearLayout(getContext());box.setOrientation(LinearLayout.VERTICAL);box.setPadding(dp(18),dp(6),dp(18),0);
        EditText x=axisInput(box,"Move X",mmText(bodyTransformMove.x)),y=axisInput(box,"Move Y",mmText(bodyTransformMove.y)),z=axisInput(box,"Move Z",mmText(bodyTransformMove.z));
        EditText rx=axisInput(box,"Rotate X",num(bodyTransformRotate.x)+"°"),ry=axisInput(box,"Rotate Y",num(bodyTransformRotate.y)+"°"),rz=axisInput(box,"Rotate Z",num(bodyTransformRotate.z)+"°");
        new AlertDialog.Builder(getContext()).setTitle("Move / Rotate • مقدار دقیق")
                .setMessage("جابجایی بر حسب mm و دوران بر حسب درجه است.").setView(box)
                .setPositiveButton("پیش‌نمایش",(d,w)->{try{
                    bodyTransformMove=new Geometry3D.Vec3((float)parseLengthMm(x.getText().toString()),(float)parseLengthMm(y.getText().toString()),(float)parseLengthMm(z.getText().toString()));
                    bodyTransformRotate=new Geometry3D.Vec3(parseAngleDeg(rx),parseAngleDeg(ry),parseAngleDeg(rz));refreshBodyTransformPreview(true);dispatchWorkspaceState();
                }catch(Exception e){toast("مقادیر Move / Rotate درست نیست");}}).setNegativeButton("لغو",null).show();
    }

    private static float parseAngleDeg(EditText e){
        String s=normalizeDigits(e.getText().toString()).toLowerCase(Locale.US).replace("°","").replace("deg","").trim();return Float.parseFloat(s);
    }

    public String beginAlignSession(){
        Object body=selectedBody();SolidCSG.Polygon face=selectedFace();
        if(body==null||face==null)return "برای Align ابتدا Face مبدأ را انتخاب کن";
        if(ensureNativeRecord(body)==null)return "Shape دقیق Face مبدأ آماده نیست";
        cancelBodyTransformSession();clearFeaturePreview();alignActive=true;alignReady=false;alignFlip=true;alignBody=body;
        alignSourcePoint=face.centroid();alignSourceNormal=face.plane.normal.normalized();alignTargetPoint=null;alignTargetNormal=null;
        invalidate();dispatchWorkspaceState();return "Align • حالا Face مقصد را لمس کن";
    }

    public boolean isAlignSessionActive(){return alignActive;}
    public boolean isAlignPreviewReady(){return alignActive&&alignReady;}
    public String alignSummary(){return !alignReady?"Align • Face مقصد":"Align "+(alignFlip?"Opposed":"Same")+" • R "+num(alignRotationDeg)+"°";}

    public String flipAlignSession(){
        if(!alignReady)return "ابتدا Face مقصد را انتخاب کن";alignFlip=!alignFlip;
        return refreshAlignPreview()?"Align • جهت برعکس شد":"پیش‌نمایش Align ساخته نشد";
    }

    public String commitAlignSession(){
        if(!alignActive||!alignReady||alignBody==null)return "Align هنوز مقصد معتبر ندارد";
        Object body=alignBody;Geometry3D.Vec3 axis=alignRotationAxis,move=alignMove;float angle=alignRotationDeg;
        endAlignSession();try{selectedBodyField.set(this,body);}catch(Exception ignored){}
        int count=0;if(Math.abs(angle)>1e-4f){recordStable(body,new StableEdit(directSerial++,Kind.ROTATE,angle,axis,null));count++;}
        if(move.length()>1e-5f){recordStable(body,new StableEdit(directSerial++,Kind.MOVE,0,move,null));count++;}
        return count==0?"دو Face از قبل هم‌راستا بودند":"Align دقیق ثبت شد • "+count+" Feature";
    }

    public void cancelAlignSession(){if(!alignActive)return;endAlignSession();dispatchWorkspaceState();}

    private void endAlignSession(){
        clearFeaturePreview();alignActive=false;alignReady=false;alignBody=null;alignSourcePoint=null;alignSourceNormal=null;alignTargetPoint=null;alignTargetNormal=null;invalidate();
    }

    private boolean captureAlignTarget(Object body,SolidCSG.Polygon face){
        if(!alignActive||body==null||face==null||body==alignBody)return false;
        alignTargetPoint=face.centroid();alignTargetNormal=face.plane.normal.normalized();alignReady=refreshAlignPreview();dispatchWorkspaceState();return alignReady;
    }

    private boolean refreshAlignPreview(){
        if(!alignActive||alignBody==null||alignSourcePoint==null||alignTargetPoint==null)return false;
        Geometry3D.Vec3 desired=alignFlip?alignTargetNormal.mul(-1f):alignTargetNormal;
        float dot=Math.max(-1f,Math.min(1f,alignSourceNormal.dot(desired)));Geometry3D.Vec3 axis=alignSourceNormal.cross(desired);
        float angle=(float)Math.toDegrees(Math.acos(dot));
        if(axis.length()<1e-5f){axis=Math.abs(alignSourceNormal.z)<.9f?alignSourceNormal.cross(new Geometry3D.Vec3(0,0,1)):alignSourceNormal.cross(new Geometry3D.Vec3(0,1,0));}
        axis=axis.normalized();Geometry3D.Vec3 center=bodyCenter(bodyCsg(alignBody));
        Geometry3D.Vec3 turned=rotatePoint(alignSourcePoint,center,axis,Math.toRadians(angle));
        alignRotationAxis=axis;alignRotationDeg=angle;alignMove=alignTargetPoint.sub(turned);
        Object record=ensureNativeRecord(alignBody);if(record==null)return false;clearFeaturePreview();long current=recordHandle(record);List<Long> temp=new ArrayList<>();
        if(Math.abs(angle)>1e-4f){long next=NativeBRepKernel.occtRotate(current,axis,angle);if(next==0L)return false;temp.add(next);current=next;}
        if(alignMove.length()>1e-5f){long next=NativeBRepKernel.occtTranslate(current,alignMove);if(next==0L){for(long h:temp)NativeBRepKernel.occtRelease(h);return false;}temp.add(next);current=next;}
        if(temp.isEmpty())return true;featurePreviewHandle=current;featurePreviewMesh=NativeBRepKernel.occtTriangulate(current,.22);
        for(int i=0;i<temp.size()-1;i++)NativeBRepKernel.occtRelease(temp.get(i));invalidate();return featurePreviewMesh.length>=9;
    }

    private static Geometry3D.Vec3 rotatePoint(Geometry3D.Vec3 point,Geometry3D.Vec3 origin,Geometry3D.Vec3 axis,double angle){
        Geometry3D.Vec3 r=point.sub(origin);float c=(float)Math.cos(angle),s=(float)Math.sin(angle);
        return origin.add(r.mul(c).add(axis.cross(r).mul(s)).add(axis.mul(axis.dot(r)*(1f-c))));
    }

    private EditText axisInput(LinearLayout parent,String axis,String initial){
        TextView t=new TextView(getContext());t.setText(axis+"  (mm)");parent.addView(t);
        EditText e=new EditText(getContext());e.setSingleLine(true);e.setText(initial);e.setSelectAllOnFocus(true);parent.addView(e);return e;
    }

    private void showRotateAxis(StableEdit editing){
        Object body=editing==null?selectedBody():bodyFor(editing);if(body==null){toast("اول Body را انتخاب کن");return;}
        if(editing!=null){showRotateAngle(body,editing.vector,editing);return;}
        String[] axes={"X","Y","Z"};
        new AlertDialog.Builder(getContext()).setTitle("Rotate Body • محور")
                .setItems(axes,(d,w)->showRotateAngle(body,w==0?new Geometry3D.Vec3(1,0,0):w==1?new Geometry3D.Vec3(0,1,0):new Geometry3D.Vec3(0,0,1),null))
                .setNegativeButton("لغو",null).show();
    }

    private void showRotateAngle(Object body,Geometry3D.Vec3 axis,StableEdit editing){
        EditText input=new EditText(getContext());input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL|InputType.TYPE_NUMBER_FLAG_SIGNED);
        input.setText(editing==null?"90":num(editing.value));input.setSelectAllOnFocus(true);
        new AlertDialog.Builder(getContext()).setTitle("Rotate حول "+axisName(axis))
                .setMessage("زاویه بر حسب درجه؛ مرکز دوران = مرکز جرم Body")
                .setView(input).setPositiveButton("اعمال",(d,w)->{
                    try{
                        double deg=Double.parseDouble(normalizeDigits(input.getText().toString()));if(Math.abs(deg)<1e-8){toast("زاویه صفر است");return;}
                        if(editing==null)recordStable(body,new StableEdit(directSerial++,Kind.ROTATE,deg,axis,null));
                        else{editing.value=deg;toast(rebuildAllStable());}
                    }catch(Exception e){toast("زاویه درست نیست");}
                }).setNegativeButton("لغو",null).show();
    }

    public void showScaleTool(){
        Object body=selectedBody();if(body==null){toast("اول Body را انتخاب کن");return;}
        EditText input=new EditText(getContext());input.setSingleLine();input.setText("1.25");input.setSelectAllOnFocus(true);
        new AlertDialog.Builder(getContext()).setTitle("Scale Body • ضریب دقیق").setMessage("مثال: 2 یعنی دو برابر؛ 0.5 یعنی نصف")
                .setView(input).setPositiveButton("اعمال",(d,w)->{try{double factor=Double.parseDouble(normalizeDigits(input.getText().toString()));
                    if(factor<=1e-6)throw new IllegalArgumentException();recordStable(body,new StableEdit(directSerial++,Kind.SCALE,factor,null,null));}
                    catch(Exception e){toast("ضریب Scale درست نیست");}}).setNegativeButton("لغو",null).show();
    }

    public void showMirrorTool(){
        Object body=selectedBody();if(body==null){toast("اول Body را انتخاب کن");return;}
        String[] planes={"YZ • برعکس‌کردن X","XZ • برعکس‌کردن Y","XY • برعکس‌کردن Z"};
        new AlertDialog.Builder(getContext()).setTitle("Mirror Body • صفحه از مرکز Body").setItems(planes,(d,w)->{
            Geometry3D.Vec3 normal=w==0?new Geometry3D.Vec3(1,0,0):w==1?new Geometry3D.Vec3(0,1,0):new Geometry3D.Vec3(0,0,1);
            recordStable(body,new StableEdit(directSerial++,Kind.MIRROR,0,normal,null));
        }).setNegativeButton("لغو",null).show();
    }

    public void showLinearPatternTool(){
        Object body=selectedBody();if(body==null){toast("اول Body را انتخاب کن");return;}
        LinearLayout box=new LinearLayout(getContext());box.setOrientation(LinearLayout.VERTICAL);box.setPadding(dp(20),dp(8),dp(20),0);
        EditText x=axisInput(box,"Step X","50mm"),y=axisInput(box,"Step Y","0mm"),z=axisInput(box,"Step Z","0mm");
        EditText count=axisInput(box,"تعداد نسخه‌ها","3");
        new AlertDialog.Builder(getContext()).setTitle("Linear Pattern • OCCT Exact").setMessage("فاصله هر تکرار و تعداد کل نسخه‌ها")
                .setView(box).setPositiveButton("ساخت",(d,w)->{try{Geometry3D.Vec3 step=new Geometry3D.Vec3((float)parseLengthMm(x.getText().toString()),
                    (float)parseLengthMm(y.getText().toString()),(float)parseLengthMm(z.getText().toString()));
                    int n=Integer.parseInt(normalizeDigits(count.getText().toString()).trim());if(step.length()<1e-8||n<2||n>256)throw new IllegalArgumentException();
                    recordStable(body,new StableEdit(directSerial++,Kind.PATTERN,n,step,null));}catch(Exception e){toast("فاصله یا تعداد Pattern درست نیست");}})
                .setNegativeButton("لغو",null).show();
    }

    // ------------------------------------------------------------------
    // Stable feature recording / deterministic replay
    // ------------------------------------------------------------------

    private void recordStable(Object body,StableEdit edit){
        Object record=ensureNativeRecord(body);if(record==null){toast("Shape دقیق پیدا نشد");return;}
        long old=recordHandle(record),next=applyKernel(old,edit);
        if(next==0L){toast(failure(edit));return;}
        if(!replaceRecord(body,record,next,appendKind(recordKind(record),edit))){NativeBRepKernel.occtRelease(next);toast("ثبت Shape جدید انجام نشد");return;}
        List<StableEdit> list=stableByBody.get(body);if(list==null){list=new ArrayList<>();stableByBody.put(body,list);}list.add(edit);
        timeline.add(new TimelineEntry(body,edit));edit.broken=false;edit.warning="";
        updateFallbackFromNative(body);clearSubSelection();ensure3D();invalidate();dispatchWorkspaceState();
        toast(edit.label()+" • ثبت شد");
    }

    private long applyKernel(long handle,StableEdit edit){
        if(handle==0L||edit==null)return 0L;
        Geometry3D.Vec3 anchor=null;int subshapeIndex=-1;
        if(edit.target!=null){
            OcctTopologyRef.Resolution r=OcctTopologyRef.resolve(handle,edit.target);
            if(r==null||r.score>180.0){edit.broken=true;edit.warning="Topology دوباره پیدا نشد";return 0L;}
            anchor=r.anchor;subshapeIndex=r.subshapeIndex;
        }
        switch(edit.kind){
            case FILLET:return subshapeIndex>=0
                    ?NativeIndexedDirectKernel.filletByIndex(handle,subshapeIndex,edit.value)
                    :NativeBRepKernel.occtFillet(handle,anchor,edit.value,false);
            case CHAMFER:return subshapeIndex>=0
                    ?NativeIndexedDirectKernel.chamferByIndex(handle,subshapeIndex,edit.value)
                    :NativeBRepKernel.occtChamfer(handle,anchor,edit.value,false);
            case PUSH_PULL:return subshapeIndex>=0
                    ?NativeIndexedDirectKernel.pushPullFaceByIndex(handle,subshapeIndex,edit.value)
                    :NativeBRepKernel.occtPushPullFace(handle,anchor,edit.value);
            case SHELL:return subshapeIndex>=0
                    ?NativeIndexedDirectKernel.shellByIndex(handle,subshapeIndex,edit.value)
                    :NativeBRepKernel.occtShell(handle,anchor,edit.value);
            case MOVE:return NativeBRepKernel.occtTranslate(handle,edit.vector);
            case ROTATE:return NativeBRepKernel.occtRotate(handle,edit.vector,edit.value);
            case SCALE:return NativeBRepKernel.occtScale(handle,edit.value);
            case MIRROR:return NativeBRepKernel.occtMirror(handle,edit.vector);
            default:return NativeBRepKernel.occtLinearPattern(handle,edit.vector,(int)edit.value);
        }
    }

    private String failure(StableEdit e){
        String target=e.target==null?"":" • "+e.target.id;
        if(e.kind==Kind.FILLET)return"Fillet ساخته نشد؛ شعاع را کمتر کن"+target;
        if(e.kind==Kind.CHAMFER)return"Chamfer ساخته نشد؛ فاصله را کمتر کن"+target;
        if(e.kind==Kind.PUSH_PULL)return"Push/Pull روی این Face/فاصله ساخته نشد"+target;
        if(e.kind==Kind.SHELL)return"Shell با این ضخامت ساخته نشد"+target;
        return"Transform دقیق انجام نشد";
    }

    private String rebuildAllStable(){
        forceParentSync();return replayAllAfterParentSync();
    }

    private String replayAllAfterParentSync(){
        pruneDeadBodies();int ok=0,broken=0;
        for(Object body:new ArrayList<>(stableByBody.keySet())){
            if(isManualCopyOutput(body))continue;
            if(replayOneBody(body))ok++;else broken++;
        }
        int copyFailures=rebuildManualCopies();broken+=copyFailures;ok+=manualCopies.size()-copyFailures;
        for(Object body:new ArrayList<>(stableByBody.keySet()))if(isManualCopyOutput(body)){if(replayOneBody(body))ok++;else broken++;}
        invalidate();dispatchWorkspaceState();
        return "Stable History بازسازی شد • "+ok+" Body"+(broken>0?" • "+broken+" خطا":"");
    }

    private boolean isManualCopyOutput(Object body){for(ManualCopy copy:manualCopies)if(copy.output==body)return true;return false;}

    private int rebuildManualCopies(){
        int failed=0;Map<Object,Object> map=nativeMap();if(map==null)return manualCopies.size();
        for(ManualCopy copy:manualCopies){
            Object sourceRecord=map.get(copy.source);if(sourceRecord==null){failed++;continue;}
            long handle=transformedCopyHandle(recordHandle(sourceRecord),copy.move,copy.rotate);if(handle==0L){failed++;continue;}
            double[] mesh=NativeBRepKernel.occtTriangulate(handle,.24);if(mesh.length<9){NativeBRepKernel.occtRelease(handle);failed++;continue;}
            try{Object old=map.remove(copy.output);if(old!=null)NativeBRepKernel.occtRelease(recordHandle(old));map.put(copy.output,nativeRecordConstructor.newInstance(handle,"Exact Transform Copy",mesh));updateFallbackFromNative(copy.output);}
            catch(Exception e){NativeBRepKernel.occtRelease(handle);failed++;}
        }
        return failed;
    }

    private boolean replayOneBody(Object body){
        Object record=nativeRecord(body);List<StableEdit> edits=stableByBody.get(body);
        if(record==null||edits==null||edits.isEmpty())return record!=null;
        long base=recordHandle(record),current=base;List<Long> generated=new ArrayList<>();
        StableEdit failed=null;
        for(StableEdit e:edits){
            e.broken=false;e.warning="";
            long next=applyKernel(current,e);
            if(next==0L){failed=e;if(e.warning.isEmpty())e.warning="OCCT Feature نامعتبر شد";break;}
            generated.add(next);current=next;
        }
        if(failed!=null){
            for(long h:generated)NativeBRepKernel.occtRelease(h);
            updateFallbackFromNative(body);return false;
        }
        if(!replaceRecordWithoutRelease(body,record,current,"Stable Direct × "+edits.size())){
            for(long h:generated)NativeBRepKernel.occtRelease(h);return false;
        }
        NativeBRepKernel.occtRelease(base);for(int i=0;i<generated.size()-1;i++)NativeBRepKernel.occtRelease(generated.get(i));
        updateFallbackFromNative(body);return true;
    }

    @Override
    public String rebuildHistory(){
        String base=super.rebuildHistory();
        String stable=replayAllAfterParentSync();
        return timeline.isEmpty()?base:base+" • "+stable;
    }

    @Override
    public String undoLastFeature(){
        pruneDeadBodies();if(timeline.isEmpty())return super.undoLastFeature();
        TimelineEntry last=timeline.remove(timeline.size()-1);List<StableEdit> list=stableByBody.get(last.body);
        if(list!=null){list.remove(last.edit);if(list.isEmpty())stableByBody.remove(last.body);}
        rebuildAllStable();return "D"+last.edit.id+" برگشت • "+last.edit.kind.name();
    }

    private String undoStable(){return undoLastFeature();}

    // ------------------------------------------------------------------
    // One History surface for Sketch/Form/Boolean + exact direct features
    // ------------------------------------------------------------------

    @Override
    public void showHistoryManager(){
        pruneDeadBodies();
        if(timeline.isEmpty()){super.showHistoryManager();return;}
        String[] rows=new String[timeline.size()+1];
        rows[0]="⏱ Sketch • Extrude • Revolve • Sweep • Loft • Boolean";
        for(int i=0;i<timeline.size();i++){
            TimelineEntry e=timeline.get(i);StableEdit f=e.edit;
            rows[i+1]=f.label()+" • "+bodyName(e.body)+(f.broken?" • ⚠ "+f.warning:"");
        }
        new AlertDialog.Builder(getContext()).setTitle("History • Parametric + OCCT")
                .setMessage("Direct Featureها Stable Face/Edge ID دارند. اگر Feature قبلی تغییر کند، Shape پایه ساخته و Topology دوباره تطبیق داده می‌شود.")
                .setItems(rows,(d,w)->{if(w==0)OcctStableCadCanvasView.super.showHistoryManager();else editStableEntry(timeline.get(w-1));})
                .setNeutralButton("بازسازی همه",(d,w)->toast(rebuildHistory()))
                .setNegativeButton("بستن",null).show();
    }

    private void editStableEntry(TimelineEntry entry){
        StableEdit e=entry.edit;
        String topo=e.target==null?"Body Feature":OcctTopologyRef.debug(e.target);
        String[] actions={"✎ ویرایش پارامتر","↻ بازسازی History","⌫ حذف Feature"};
        new AlertDialog.Builder(getContext()).setTitle(e.label()).setMessage(topo+(e.broken?"\n⚠ "+e.warning:""))
                .setItems(actions,(d,w)->{
                    if(w==0)editParameter(e);
                    else if(w==1)toast(rebuildHistory());
                    else confirmDelete(entry);
                }).setNegativeButton("بستن",null).show();
    }

    private void editParameter(StableEdit e){
        if(e.kind==Kind.MOVE){showMoveDialog(e);return;}
        if(e.kind==Kind.ROTATE){showRotateAxis(e);return;}
        if(e.kind==Kind.MIRROR){toast("برای تغییر صفحه Mirror، Feature را حذف و دوباره بساز");return;}
        if(e.kind==Kind.PATTERN){toast("ویرایش Pattern از پنل اختصاصی در مرحله بعد افزوده می‌شود");return;}
        if(e.kind==Kind.SCALE){
            EditText input=new EditText(getContext());input.setSingleLine();input.setText(num(e.value));input.setSelectAllOnFocus(true);
            new AlertDialog.Builder(getContext()).setTitle("ویرایش Scale D"+e.id).setView(input).setPositiveButton("اعمال",(d,w)->{
                try{double factor=Double.parseDouble(normalizeDigits(input.getText().toString()));if(factor<=1e-6)throw new IllegalArgumentException();e.value=factor;toast(rebuildAllStable());}
                catch(Exception ex){toast("ضریب درست نیست");}}).setNegativeButton("لغو",null).show();return;
        }
        boolean signed=e.kind==Kind.PUSH_PULL;
        askLength("ویرایش D"+e.id,e.target==null?"":e.target.shortLabel(),mmText(e.value),signed,v->{
            if(!signed&&v<=0){toast("مقدار باید مثبت باشد");return;}
            if(signed&&Math.abs(v)<1e-8){toast("فاصله نباید صفر باشد");return;}
            e.value=v;toast(rebuildAllStable());
        });
    }

    private void confirmDelete(TimelineEntry entry){
        new AlertDialog.Builder(getContext()).setTitle("حذف "+entry.edit.label()+"؟")
                .setMessage("Feature حذف می‌شود و تمام Direct Featureهای باقی‌مانده از Shape پایه دوباره محاسبه می‌شوند.")
                .setPositiveButton("حذف",(d,w)->{
                    timeline.remove(entry);List<StableEdit> list=stableByBody.get(entry.body);
                    if(list!=null){list.remove(entry.edit);if(list.isEmpty())stableByBody.remove(entry.body);}toast(rebuildAllStable());
                }).setNegativeButton("لغو",null).show();
    }

    @Override
    public void clearAll(){stableByBody.clear();timeline.clear();directSerial=1;topologySerial=1;clearSubSelection();super.clearAll();}

    // ------------------------------------------------------------------
    // Edge picking and stable-ID visualization
    // ------------------------------------------------------------------

    @Override
    public boolean onTouchEvent(MotionEvent event){
        if(bodyTransformActive&&event.getPointerCount()==1&&handleBodyGizmoTouch(event))return true;
        int action=event.getActionMasked();
        if(edgePickMode){
            if(action==MotionEvent.ACTION_DOWN){edgeDownX=event.getX();edgeDownY=event.getY();edgeMoved=false;}
            else if(action==MotionEvent.ACTION_MOVE&&Math.hypot(event.getX()-edgeDownX,event.getY()-edgeDownY)>10f)edgeMoved=true;
        }
        boolean handled=super.onTouchEvent(event);
        if(edgePickMode&&action==MotionEvent.ACTION_UP&&!edgeMoved&&is3DOverview()){
            if(pickWorkspaceEdge(event.getX(),event.getY())){edgePickMode=false;toast("Edge انتخاب شد • "+selectedEdgeRef.id);}
            else toast("لبه دقیق نزدیک محل لمس پیدا نشد");
            invalidate();dispatchWorkspaceState();
        }
        return handled;
    }

    @Override
    protected void onDraw(Canvas canvas){
        super.onDraw(canvas);
        if(is3DOverview()&&edgeA!=null&&edgeB!=null&&selectedEdgeBody==selectedBody()){
            PointF a=project(edgeA),b=project(edgeB);canvas.drawLine(a.x,a.y,b.x,b.y,stableEdgePaint);
        }
        if(is3DOverview()&&featurePreviewMesh.length>=9){
            for(int i=0;i+8<featurePreviewMesh.length;i+=9){
                PointF a=project(new Geometry3D.Vec3((float)featurePreviewMesh[i],(float)featurePreviewMesh[i+1],(float)featurePreviewMesh[i+2]));
                PointF b=project(new Geometry3D.Vec3((float)featurePreviewMesh[i+3],(float)featurePreviewMesh[i+4],(float)featurePreviewMesh[i+5]));
                PointF c=project(new Geometry3D.Vec3((float)featurePreviewMesh[i+6],(float)featurePreviewMesh[i+7],(float)featurePreviewMesh[i+8]));
                canvas.drawLine(a.x,a.y,b.x,b.y,featurePreviewPaint);canvas.drawLine(b.x,b.y,c.x,c.y,featurePreviewPaint);canvas.drawLine(c.x,c.y,a.x,a.y,featurePreviewPaint);
            }
        }
        if(bodyTransformActive&&is3DOverview())drawBodyTransformGizmo(canvas);
        if(alignActive&&alignSourcePoint!=null){
            PointF a=project(alignSourcePoint);canvas.drawCircle(a.x,a.y,9f,alignPaint);
            if(alignTargetPoint!=null){PointF b=project(alignTargetPoint);canvas.drawCircle(b.x,b.y,9f,alignPaint);canvas.drawLine(a.x,a.y,b.x,b.y,alignPaint);}
        }
    }

    private void drawBodyTransformGizmo(Canvas canvas){
        if(bodyTransformCenter==null)return;Geometry3D.Vec3 gizmoCenter=bodyTransformCenter.add(bodyTransformMove);bodyGizmoOrigin=project(gizmoCenter);
        float axisLength=Math.max(22f,bodyRadius(bodyCsg(bodyTransformBody))*.72f);
        Geometry3D.Vec3[] axes={new Geometry3D.Vec3(1,0,0),new Geometry3D.Vec3(0,1,0),new Geometry3D.Vec3(0,0,1)};
        for(int i=0;i<3;i++){
            bodyAxisTips[i]=project(gizmoCenter.add(axes[i].mul(axisLength)));
            drawArrow(canvas,bodyGizmoOrigin,bodyAxisTips[i],bodyAxisPaint[i]);
            canvas.drawText(i==0?"X":i==1?"Y":"Z",bodyAxisTips[i].x,bodyAxisTips[i].y-10f,bodyGizmoText);
        }
        for(int axis=0;axis<3;axis++){
            List<PointF> points=bodyRotationRings[axis];points.clear();Path path=new Path();
            Geometry3D.Vec3 u=axis==0?new Geometry3D.Vec3(0,1,0):new Geometry3D.Vec3(1,0,0);
            Geometry3D.Vec3 v=axis==2?new Geometry3D.Vec3(0,1,0):new Geometry3D.Vec3(0,0,1);
            float radius=axisLength*.72f;
            for(int i=0;i<=48;i++){
                double a=2*Math.PI*i/48d;PointF q=project(gizmoCenter.add(u.mul((float)Math.cos(a)*radius)).add(v.mul((float)Math.sin(a)*radius)));
                points.add(q);if(i==0)path.moveTo(q.x,q.y);else path.lineTo(q.x,q.y);
            }
            Paint ring=bodyAxisPaint[axis];float old=ring.getStrokeWidth();ring.setStrokeWidth(old*.62f);canvas.drawPath(path,ring);ring.setStrokeWidth(old);
        }
        canvas.drawText(bodyTransformSummary(),bodyGizmoOrigin.x,bodyGizmoOrigin.y-28f,bodyGizmoText);
    }

    private boolean handleBodyGizmoTouch(MotionEvent event){
        int action=event.getActionMasked();float x=event.getX(),y=event.getY();float density=getResources().getDisplayMetrics().density;
        if(action==MotionEvent.ACTION_DOWN){
            if(bodyGizmoOrigin==null)return false;float best=Float.MAX_VALUE;int picked=0;
            for(int i=0;i<3;i++){float d=distanceToSegment(x,y,bodyGizmoOrigin.x,bodyGizmoOrigin.y,bodyAxisTips[i].x,bodyAxisTips[i].y);if(d<best){best=d;picked=i+1;}}
            for(int i=0;i<3;i++){float d=distanceToPolyline(x,y,bodyRotationRings[i]);if(d<best){best=d;picked=i+4;}}
            if(best>25f*density)return false;bodyGizmoDrag=picked;bodyGizmoMoved=false;bodyGizmoDownX=x;bodyGizmoDownY=y;
            bodyGizmoStartValue=transformComponent(picked);return true;
        }
        if(bodyGizmoDrag==0)return false;
        if(action==MotionEvent.ACTION_MOVE){
            float dx=x-bodyGizmoDownX,dy=y-bodyGizmoDownY;if(dx*dx+dy*dy>16f)bodyGizmoMoved=true;
            if(bodyGizmoDrag<=3){
                PointF tip=bodyAxisTips[bodyGizmoDrag-1];float vx=tip.x-bodyGizmoOrigin.x,vy=tip.y-bodyGizmoOrigin.y,len=Math.max(1f,(float)Math.hypot(vx,vy));
                float axisMm=Math.max(22f,bodyRadius(bodyCsg(bodyTransformBody))*.72f);float delta=((dx*vx+dy*vy)/len)/(len/axisMm);
                setTransformComponent(bodyGizmoDrag,bodyGizmoStartValue+delta);
            }else{
                double a0=Math.atan2(bodyGizmoDownY-bodyGizmoOrigin.y,bodyGizmoDownX-bodyGizmoOrigin.x),a1=Math.atan2(y-bodyGizmoOrigin.y,x-bodyGizmoOrigin.x);
                double delta=Math.toDegrees(a1-a0);if(delta>180)delta-=360;if(delta<-180)delta+=360;setTransformComponent(bodyGizmoDrag,bodyGizmoStartValue+(float)delta);
            }
            long now=System.nanoTime();if(now-lastBodyPreviewNs>55_000_000L){lastBodyPreviewNs=now;refreshBodyTransformPreview(false);}invalidate();return true;
        }
        if(action==MotionEvent.ACTION_UP){boolean edit=!bodyGizmoMoved;bodyGizmoDrag=0;refreshBodyTransformPreview(true);dispatchWorkspaceState();if(edit)showBodyTransformExactEditor();return true;}
        if(action==MotionEvent.ACTION_CANCEL){bodyGizmoDrag=0;return true;}return true;
    }

    private void refreshBodyTransformPreview(boolean force){
        if(!bodyTransformActive||bodyTransformBody==null)return;Object record=ensureNativeRecord(bodyTransformBody);if(record==null)return;
        clearFeaturePreview();long current=recordHandle(record);List<Long> temporary=new ArrayList<>();
        if(bodyTransformMove.length()>1e-5f){long next=NativeBRepKernel.occtTranslate(current,bodyTransformMove);if(next==0L)return;temporary.add(next);current=next;}
        Geometry3D.Vec3[] axes={new Geometry3D.Vec3(1,0,0),new Geometry3D.Vec3(0,1,0),new Geometry3D.Vec3(0,0,1)};
        float[] angle={bodyTransformRotate.x,bodyTransformRotate.y,bodyTransformRotate.z};
        for(int i=0;i<3;i++)if(Math.abs(angle[i])>1e-4f){long next=NativeBRepKernel.occtRotate(current,axes[i],angle[i]);if(next==0L){for(long h:temporary)NativeBRepKernel.occtRelease(h);return;}temporary.add(next);current=next;}
        if(temporary.isEmpty()){invalidate();return;}featurePreviewHandle=current;featurePreviewMesh=NativeBRepKernel.occtTriangulate(current,force ? .22 : .38);
        for(int i=0;i<temporary.size()-1;i++)NativeBRepKernel.occtRelease(temporary.get(i));invalidate();
    }

    private String commitExactBodyCopy(Object source,Geometry3D.Vec3 move,Geometry3D.Vec3 rotate){
        Object sourceRecord=ensureNativeRecord(source);if(sourceRecord==null)return "Shape دقیق برای Copy آماده نیست";
        long handle=transformedCopyHandle(recordHandle(sourceRecord),move,rotate);if(handle==0L)return "Copy دقیق ساخته نشد";
        double[] mesh=NativeBRepKernel.occtTriangulate(handle,.24);SolidCSG csg=csgFromMesh(mesh);
        if(csg==null||csg.isEmpty()){NativeBRepKernel.occtRelease(handle);return "مش Copy ساخته نشد";}
        Object output=addIndependentBody(bodyName(source)+" Copy",csg);if(output==null){NativeBRepKernel.occtRelease(handle);return "Body جدید ساخته نشد";}
        try{
            Object nativeRecord=nativeRecordConstructor.newInstance(handle,"Exact Transform Copy",mesh);Map<Object,Object> map=nativeMap();if(map==null)throw new IllegalStateException();map.put(output,nativeRecord);
            manualCopies.add(new ManualCopy(source,output,move,rotate));selectedBodyField.set(this,output);clearSubSelection();invalidate();dispatchWorkspaceState();
            return "Copy دقیق ساخته شد • Body مستقل";
        }catch(Exception e){NativeBRepKernel.occtRelease(handle);return "ثبت Copy دقیق انجام نشد";}
    }

    private long transformedCopyHandle(long base,Geometry3D.Vec3 move,Geometry3D.Vec3 rotate){
        if(base==0L)return 0L;long current=base;boolean owned=false;
        if(move!=null&&move.length()>1e-5f){long next=NativeBRepKernel.occtTranslate(current,move);if(next==0L)return 0L;current=next;owned=true;}
        Geometry3D.Vec3[] axes={new Geometry3D.Vec3(1,0,0),new Geometry3D.Vec3(0,1,0),new Geometry3D.Vec3(0,0,1)};
        float[] angles=rotate==null?new float[]{0,0,0}:new float[]{rotate.x,rotate.y,rotate.z};
        for(int i=0;i<3;i++)if(Math.abs(angles[i])>1e-4f){long next=NativeBRepKernel.occtRotate(current,axes[i],angles[i]);if(next==0L){if(owned)NativeBRepKernel.occtRelease(current);return 0L;}if(owned)NativeBRepKernel.occtRelease(current);current=next;owned=true;}
        if(!owned)current=NativeBRepKernel.occtScale(base,1.0);return current;
    }

    private static SolidCSG csgFromMesh(double[] mesh){
        if(mesh==null||mesh.length<9)return null;List<SolidCSG.Polygon> polygons=new ArrayList<>();
        for(int i=0;i+8<mesh.length;i+=9){List<SolidCSG.Vertex> vertices=new ArrayList<>(3);
            vertices.add(new SolidCSG.Vertex(new Geometry3D.Vec3((float)mesh[i],(float)mesh[i+1],(float)mesh[i+2])));
            vertices.add(new SolidCSG.Vertex(new Geometry3D.Vec3((float)mesh[i+3],(float)mesh[i+4],(float)mesh[i+5])));
            vertices.add(new SolidCSG.Vertex(new Geometry3D.Vec3((float)mesh[i+6],(float)mesh[i+7],(float)mesh[i+8])));polygons.add(new SolidCSG.Polygon(vertices));}
        return SolidCSG.fromPolygons(polygons);
    }

    private float transformComponent(int picked){
        if(picked==1)return bodyTransformMove.x;if(picked==2)return bodyTransformMove.y;if(picked==3)return bodyTransformMove.z;
        if(picked==4)return bodyTransformRotate.x;if(picked==5)return bodyTransformRotate.y;return bodyTransformRotate.z;
    }

    private void setTransformComponent(int picked,float value){
        if(picked==1)bodyTransformMove=new Geometry3D.Vec3(value,bodyTransformMove.y,bodyTransformMove.z);
        else if(picked==2)bodyTransformMove=new Geometry3D.Vec3(bodyTransformMove.x,value,bodyTransformMove.z);
        else if(picked==3)bodyTransformMove=new Geometry3D.Vec3(bodyTransformMove.x,bodyTransformMove.y,value);
        else if(picked==4)bodyTransformRotate=new Geometry3D.Vec3(value,bodyTransformRotate.y,bodyTransformRotate.z);
        else if(picked==5)bodyTransformRotate=new Geometry3D.Vec3(bodyTransformRotate.x,value,bodyTransformRotate.z);
        else bodyTransformRotate=new Geometry3D.Vec3(bodyTransformRotate.x,bodyTransformRotate.y,value);
    }

    private static void drawArrow(Canvas canvas,PointF a,PointF b,Paint paint){
        canvas.drawLine(a.x,a.y,b.x,b.y,paint);float dx=b.x-a.x,dy=b.y-a.y,len=Math.max(1f,(float)Math.hypot(dx,dy));dx/=len;dy/=len;float wing=13f;
        canvas.drawLine(b.x,b.y,b.x-dx*wing-dy*wing*.55f,b.y-dy*wing+dx*wing*.55f,paint);canvas.drawLine(b.x,b.y,b.x-dx*wing+dy*wing*.55f,b.y-dy*wing-dx*wing*.55f,paint);
    }

    private static float distanceToPolyline(float x,float y,List<PointF> p){float best=Float.MAX_VALUE;for(int i=1;i<p.size();i++)best=Math.min(best,distanceToSegment(x,y,p.get(i-1).x,p.get(i-1).y,p.get(i).x,p.get(i).y));return best;}

    private boolean pickWorkspaceEdge(float sx,float sy){
        Object body=selectedBody();SolidCSG csg=bodyCsg(body);if(body==null||csg==null)return false;
        float best=38f;Geometry3D.Vec3 ba=null,bb=null;
        for(SolidCSG.Polygon p:csg.polygons()){
            int n=p.vertices.size();for(int i=0;i<n;i++){
                Geometry3D.Vec3 a=p.vertices.get(i).pos,b=p.vertices.get((i+1)%n).pos;PointF pa=project(a),pb=project(b);
                float d=distanceToSegment(sx,sy,pa.x,pa.y,pb.x,pb.y);if(d<best){best=d;ba=a;bb=b;}
            }
        }
        if(ba==null)return false;
        Object record=ensureNativeRecord(body);if(record==null)return false;
        Geometry3D.Vec3 mid=ba.add(bb).mul(0.5f);String id=nextTopologyId(body,OcctTopologyRef.EDGE);
        OcctTopologyRef.Ref ref=OcctTopologyRef.captureEdge(recordHandle(record),mid,ba,bb,id);if(ref==null)return false;
        edgeA=ba;edgeB=bb;edgeAnchor=mid;selectedEdgeRef=ref;selectedEdgeBody=body;return true;
    }

    @Override
    protected void onTopologyPicked(Object body,String kind,Geometry3D.Vec3 anchor,
                                    Geometry3D.Vec3 selectedA,Geometry3D.Vec3 selectedB){
        if(alignActive&&"FACE".equals(kind)){
            SolidCSG.Polygon target=selectedFace();
            if(captureAlignTarget(body,target)){toast("Align • پیش‌نمایش آماده است");return;}
            if(body==alignBody)toast("Face مقصد باید روی Body دیگری باشد");
        }
        if(body==null||!"EDGE".equals(kind)||anchor==null||selectedA==null||selectedB==null){
            selectedEdgeRef=null;selectedEdgeBody=null;edgeA=edgeB=edgeAnchor=null;return;
        }
        Object record=ensureNativeRecord(body);if(record==null)return;
        String id=nextTopologyId(body,OcctTopologyRef.EDGE);
        OcctTopologyRef.Ref ref=OcctTopologyRef.captureEdge(recordHandle(record),anchor,selectedA,selectedB,id);
        if(ref==null)return;
        edgeA=selectedA;edgeB=selectedB;edgeAnchor=anchor;selectedEdgeRef=ref;selectedEdgeBody=body;
    }

    @Override
    public String selectedInfo(){
        String base=super.selectedInfo();
        if(selectedEdgeRef!=null&&selectedEdgeBody==selectedBody())return base+" | "+selectedEdgeRef.id;
        return base;
    }

    private static float distanceToSegment(float px,float py,float ax,float ay,float bx,float by){
        float dx=bx-ax,dy=by-ay,den=dx*dx+dy*dy;if(den<1e-8f)return(float)Math.hypot(px-ax,py-ay);
        float t=((px-ax)*dx+(py-ay)*dy)/den;t=Math.max(0f,Math.min(1f,t));return(float)Math.hypot(px-(ax+t*dx),py-(ay+t*dy));
    }

    // ------------------------------------------------------------------
    // NativeRecord bridge
    // ------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private Map<Object,Object> nativeMap(){
        try{Object v=nativeByBodyField==null?null:nativeByBodyField.get(this);return v instanceof Map?(Map<Object,Object>)v:null;}
        catch(Exception e){return null;}
    }

    private Object nativeRecord(Object body){Map<Object,Object> m=nativeMap();return m==null||body==null?null:m.get(body);}
    private Object ensureNativeRecord(Object body){Object r=nativeRecord(body);if(r!=null)return r;forceParentSync();return nativeRecord(body);}
    private void forceParentSync(){try{if(parentSyncMethod!=null)parentSyncMethod.invoke(this,true);}catch(Exception ignored){}}
    private long recordHandle(Object r){try{return recordHandleField==null||r==null?0L:recordHandleField.getLong(r);}catch(Exception e){return 0L;}}
    private String recordKind(Object r){try{Object v=recordKindField==null||r==null?null:recordKindField.get(r);return v==null?"OCCT":String.valueOf(v);}catch(Exception e){return"OCCT";}}

    private boolean replaceRecord(Object body,Object old,long handle,String kind){
        if(!replaceRecordWithoutRelease(body,old,handle,kind))return false;NativeBRepKernel.occtRelease(recordHandle(old));return true;
    }

    private boolean replaceRecordWithoutRelease(Object body,Object old,long handle,String kind){
        try{
            Map<Object,Object> map=nativeMap();if(map==null||nativeRecordConstructor==null||handle==0L)return false;
            double[] mesh=NativeBRepKernel.occtTriangulate(handle,0.28);Object next=nativeRecordConstructor.newInstance(handle,kind,mesh);map.put(body,next);return true;
        }catch(Exception e){return false;}
    }

    private void updateFallbackFromNative(Object body){
        Object record=nativeRecord(body);if(record==null||body==null)return;double[] mesh=NativeBRepKernel.occtTriangulate(recordHandle(record),0.28);if(mesh.length<9)return;
        List<SolidCSG.Polygon> polys=new ArrayList<>();for(int i=0;i+8<mesh.length;i+=9){
            List<SolidCSG.Vertex> v=new ArrayList<>(3);v.add(new SolidCSG.Vertex(new Geometry3D.Vec3((float)mesh[i],(float)mesh[i+1],(float)mesh[i+2])));
            v.add(new SolidCSG.Vertex(new Geometry3D.Vec3((float)mesh[i+3],(float)mesh[i+4],(float)mesh[i+5])));
            v.add(new SolidCSG.Vertex(new Geometry3D.Vec3((float)mesh[i+6],(float)mesh[i+7],(float)mesh[i+8])));polys.add(new SolidCSG.Polygon(v));
        }
        try{Field f=findField(body.getClass(),"csg");if(f!=null)f.set(body,SolidCSG.fromPolygons(polys));}catch(Exception ignored){}
    }

    private Object selectedBody(){try{return selectedBodyField==null?null:selectedBodyField.get(this);}catch(Exception e){return null;}}
    private SolidCSG.Polygon selectedFace(){try{Object v=selectedFaceField==null?null:selectedFaceField.get(this);return v instanceof SolidCSG.Polygon?(SolidCSG.Polygon)v:null;}catch(Exception e){return null;}}
    private SolidCSG bodyCsg(Object body){try{Field f=body==null?null:findField(body.getClass(),"csg");Object v=f==null?null:f.get(body);return v instanceof SolidCSG?(SolidCSG)v:null;}catch(Exception e){return null;}}

    private static Geometry3D.Vec3 bodyCenter(SolidCSG csg){
        if(csg==null)return new Geometry3D.Vec3(0,0,0);double x=0,y=0,z=0;int n=0;
        for(SolidCSG.Polygon p:csg.polygons())for(SolidCSG.Vertex v:p.vertices){x+=v.pos.x;y+=v.pos.y;z+=v.pos.z;n++;}
        return n==0?new Geometry3D.Vec3(0,0,0):new Geometry3D.Vec3((float)(x/n),(float)(y/n),(float)(z/n));
    }

    private static float bodyRadius(SolidCSG csg){
        if(csg==null)return 30f;Geometry3D.Vec3 center=bodyCenter(csg);float radius=0f;
        for(SolidCSG.Polygon p:csg.polygons())for(SolidCSG.Vertex v:p.vertices)radius=Math.max(radius,v.pos.sub(center).length());
        return Math.max(8f,radius);
    }

    @SuppressWarnings("unchecked")
    private List<Object> bodies(){try{Object v=bodiesField==null?null:bodiesField.get(this);return v instanceof List?(List<Object>)v:new ArrayList<>();}catch(Exception e){return new ArrayList<>();}}

    private void pruneDeadBodies(){
        List<Object> alive=bodies();
        Iterator<Map.Entry<Object,List<StableEdit>>> it=stableByBody.entrySet().iterator();
        while(it.hasNext())if(!alive.contains(it.next().getKey()))it.remove();
        for(int i=timeline.size()-1;i>=0;i--)if(!alive.contains(timeline.get(i).body))timeline.remove(i);
        for(int i=manualCopies.size()-1;i>=0;i--){ManualCopy copy=manualCopies.get(i);if(!alive.contains(copy.source)||!alive.contains(copy.output))manualCopies.remove(i);}
    }

    private Object bodyFor(StableEdit edit){for(TimelineEntry e:timeline)if(e.edit==edit)return e.body;return null;}

    private void clearSubSelection(){
        edgePickMode=false;edgeA=edgeB=edgeAnchor=null;selectedEdgeRef=null;selectedEdgeBody=null;
        try{if(selectedFaceField!=null)selectedFaceField.set(this,null);}catch(Exception ignored){}
    }

    private PointF project(Geometry3D.Vec3 p){try{Object v=projectMethod.invoke(this,p);return v instanceof PointF?(PointF)v:new PointF();}catch(Exception e){return new PointF();}}
    private void ensure3D(){if(!is3DOverview())toggle3DOverview();}

    private String nextTopologyId(Object body,int kind){return "B"+bodyId(body)+":"+(kind==OcctTopologyRef.FACE?"F":"E")+(topologySerial++);}
    private static int bodyId(Object body){try{Field f=findField(body.getClass(),"id");return f==null?0:f.getInt(body);}catch(Exception e){return 0;}}
    private static String bodyName(Object body){if(body==null)return"Body";try{Field f=findField(body.getClass(),"name");Object v=f==null?null:f.get(body);return v==null?"Body":String.valueOf(v);}catch(Exception e){return"Body";}}
    private static Field findField(Class<?> c,String name){Class<?> x=c;while(x!=null){try{Field f=x.getDeclaredField(name);f.setAccessible(true);return f;}catch(Exception e){x=x.getSuperclass();}}return null;}

    // ------------------------------------------------------------------
    // Units / formatting
    // ------------------------------------------------------------------

    private static double parseLengthMm(String raw){
        String s=normalizeDigits(raw).toLowerCase(Locale.US).trim().replace('،','.').replace(',','.');
        boolean cm=s.endsWith("cm")||s.endsWith("سانتیمتر")||s.endsWith("سانتی‌متر");
        s=s.replace("میلی‌متر","").replace("میلیمتر","").replace("سانتی‌متر","").replace("سانتیمتر","").replace("mm","").replace("cm","").trim();
        double v=Double.parseDouble(s);return cm?v*10.0:v;
    }

    private static String normalizeDigits(String s){if(s==null)return"";StringBuilder b=new StringBuilder();for(int i=0;i<s.length();i++){char c=s.charAt(i);if(c>='۰'&&c<='۹')b.append((char)('0'+c-'۰'));else if(c>='٠'&&c<='٩')b.append((char)('0'+c-'٠'));else b.append(c);}return b.toString().trim();}
    private static String dual(double mm){return num(mm)+" mm";}
    private static String signedDual(double mm){return(mm>=0?"+":"")+dual(mm);}
    private static String num(double v){String s=String.format(Locale.US,"%.3f",v);while(s.contains(".")&&(s.endsWith("0")||s.endsWith(".")))s=s.substring(0,s.length()-1);return s;}
    private static String mmText(double mm){return num(mm)+"mm";}
    private static String axisName(Geometry3D.Vec3 a){if(a==null)return"?";if(Math.abs(a.x)>0.999f)return"X";if(Math.abs(a.y)>0.999f)return"Y";if(Math.abs(a.z)>0.999f)return"Z";return"("+num(a.x)+", "+num(a.y)+", "+num(a.z)+")";}
    private static String appendKind(String base,StableEdit e){return(base==null?"OCCT":base)+" + D"+e.id+"/"+e.kind.name();}
    private int dp(int v){return Math.round(v*getResources().getDisplayMetrics().density);}
    private void toast(String s){if(s!=null&&!s.trim().isEmpty())Toast.makeText(getContext(),s,Toast.LENGTH_LONG).show();}
}

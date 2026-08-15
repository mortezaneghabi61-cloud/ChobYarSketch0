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
 * Exact direct-modeling layer for the OCCT workspace.
 *
 * The older Java DirectModel layer remains available as a compatibility/debug
 * fallback, but the normal Edit 3D path in this class applies Fillet, Chamfer,
 * planar Face Push/Pull, Shell and Body transforms directly to TopoDS_Shape.
 * Edge/Face selection is still driven by the app workspace; selection anchors
 * are resolved to the nearest exact OCCT sub-shape in native code.
 */
public class OcctDirectCadCanvasView extends OcctModelCadCanvasView {

    private enum ExactKind {
        FILLET_EDGE, CHAMFER_EDGE, PUSH_PULL_FACE, SHELL_FACE, MOVE, ROTATE
    }

    private static final class ExactEdit {
        final ExactKind kind;
        double value;
        final Geometry3D.Vec3 anchor;
        final Geometry3D.Vec3 vector;

        ExactEdit(ExactKind kind,double value,Geometry3D.Vec3 anchor,Geometry3D.Vec3 vector){
            this.kind=kind;this.value=value;this.anchor=anchor;this.vector=vector;
        }

        String label(){
            switch(kind){
                case FILLET_EDGE:return "Fillet Edge • "+dual(value);
                case CHAMFER_EDGE:return "Chamfer Edge • "+dual(value);
                case PUSH_PULL_FACE:return "Push/Pull Face • "+signedDual(value);
                case SHELL_FACE:return "Shell • "+dual(value);
                case MOVE:return "Move • X "+dual(vector.x)+" • Y "+dual(vector.y)+" • Z "+dual(vector.z);
                default:return "Rotate "+axisName(vector)+" • "+num(value)+"°";
            }
        }
    }

    private final IdentityHashMap<Object,List<ExactEdit>> exactEditsByBody=new IdentityHashMap<>();

    private Field nativeByBodyField;
    private Constructor<?> nativeRecordConstructor;
    private Field recordHandleField;
    private Field recordKindField;
    private Field selectedBodyField;
    private Field selectedFaceField;
    private Method parentSyncMethod;
    private Method projectMethod;

    private boolean exactEdgePickMode=false;
    private boolean exactEdgeMoved=false;
    private float exactEdgeDownX,exactEdgeDownY;
    private Geometry3D.Vec3 exactEdgeA,exactEdgeB,exactEdgeAnchor;
    private Object exactEdgeBody;

    private final Paint exactEdgePaint=new Paint(Paint.ANTI_ALIAS_FLAG);

    public OcctDirectCadCanvasView(Context context){
        super(context);
        exactEdgePaint.setColor(Color.rgb(25,112,225));
        exactEdgePaint.setStrokeWidth(8f);
        exactEdgePaint.setStrokeCap(Paint.Cap.ROUND);
        initExactReflection();
    }

    private void initExactReflection(){
        try{
            nativeByBodyField=field(OcctModelCadCanvasView.class,"nativeByBody");
            selectedBodyField=field(SolidCadCanvasView.class,"selectedBody");
            selectedFaceField=field(SolidCadCanvasView.class,"selectedFace");
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

    @Override
    public void showDirectManager(){
        Object body=selectedBody();
        Object record=body==null?null:ensureNativeRecord(body);
        String state=body==null?"Body انتخاب نشده"
                :record==null?bodyName(body)+" • هنوز Shape دقیق آماده نیست"
                :bodyName(body)+" • OCCT TopoDS_Shape آماده";
        String edge=exactEdgeAnchor==null||exactEdgeBody!=body?"Edge انتخاب نشده":"Edge دقیق آماده Fillet/Chamfer";
        String face=selectedFace()==null?"Face انتخاب نشده":"Face آماده Push/Pull یا Shell";
        String[] items={
                "⌁ انتخاب Edge دقیق با لمس روی مدل",
                "⌒ Fillet روی Edge انتخاب‌شده",
                "◩ Chamfer روی Edge انتخاب‌شده",
                "↕ Push/Pull روی Face انتخاب‌شده",
                "▱ Shell — بازکردن Face انتخاب‌شده",
                "↔ Move Body در X / Y / Z",
                "⟳ Rotate Body حول X / Y / Z",
                "⏱ Exact Direct History",
                "↶ Undo آخرین Exact Edit",
                "⌘ Inspector / ابزارهای سازگاری قبلی"
        };
        new AlertDialog.Builder(getContext())
                .setTitle("Edit 3D • OCCT Exact")
                .setMessage(state+"\n"+edge+"\n"+face
                        +"\n\nEdge یا Face را انتخاب کن و عملیات را بزن. محاسبه روی B-Rep واقعی انجام می‌شود؛ واحد طول cm/mm است.")
                .setItems(items,(d,w)->{
                    if(w==0)beginExactEdgePick();
                    else if(w==1)askExactEdge(ExactKind.FILLET_EDGE);
                    else if(w==2)askExactEdge(ExactKind.CHAMFER_EDGE);
                    else if(w==3)askExactFacePushPull();
                    else if(w==4)askExactShell();
                    else if(w==5)showExactMoveDialog();
                    else if(w==6)showExactRotateAxis();
                    else if(w==7)showExactHistory();
                    else if(w==8)toast(undoExactEdit());
                    else OcctDirectCadCanvasView.super.showDirectManager();
                })
                .setNegativeButton("بستن",null).show();
    }

    /** Finish now opens the exact direct editor as well. */
    @Override
    public void showFinishManager(){showDirectManager();}

    private void beginExactEdgePick(){
        Object body=selectedBody();
        if(body==null){ensure3D();toast("اول روی Body بزن");return;}
        if(ensureNativeRecord(body)==null){toast("Shape دقیق این Body هنوز آماده نیست");return;}
        ensure3D();
        exactEdgePickMode=true;
        exactEdgeA=exactEdgeB=exactEdgeAnchor=null;
        exactEdgeBody=null;
        invalidate();
        toast("انتخاب Edge روشن شد — روی خود لبه بزن");
    }

    private void askExactEdge(ExactKind kind){
        Object body=selectedBody();
        if(body==null||exactEdgeAnchor==null||exactEdgeBody!=body){beginExactEdgePick();return;}
        String title=kind==ExactKind.FILLET_EDGE?"Fillet Edge — شعاع":"Chamfer Edge — فاصله";
        askLength(title,"مثال: 5mm یا 0.5cm","5mm",false,v->{
            if(v<=0){toast("مقدار باید بزرگ‌تر از صفر باشد");return;}
            recordExact(body,new ExactEdit(kind,v,exactEdgeAnchor,null));
        });
    }

    private void askExactFacePushPull(){
        Object body=selectedBody();
        SolidCSG.Polygon face=selectedFace();
        if(body==null||face==null){ensure3D();toast("اول روی Face موردنظر بزن");return;}
        Geometry3D.Vec3 anchor=face.centroid();
        askLength("Push/Pull Face","مثبت = بیرون، منفی = داخل؛ مثال 12mm یا -0.5cm","10mm",true,v->{
            if(Math.abs(v)<1e-7){toast("فاصله نباید صفر باشد");return;}
            recordExact(body,new ExactEdit(ExactKind.PUSH_PULL_FACE,v,anchor,null));
        });
    }

    private void askExactShell(){
        Object body=selectedBody();
        SolidCSG.Polygon face=selectedFace();
        if(body==null||face==null){ensure3D();toast("اول Faceای که باید باز شود را انتخاب کن");return;}
        Geometry3D.Vec3 anchor=face.centroid();
        askLength("Shell — ضخامت دیواره","Face انتخاب‌شده حذف می‌شود؛ مثال 2mm یا 0.2cm","2mm",false,v->{
            if(v<=0){toast("ضخامت باید بزرگ‌تر از صفر باشد");return;}
            recordExact(body,new ExactEdit(ExactKind.SHELL_FACE,v,anchor,null));
        });
    }

    private interface LengthConsumer{void accept(double mm);}

    private void askLength(String title,String message,String initial,boolean signed,LengthConsumer consumer){
        EditText input=new EditText(getContext());
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setText(initial);input.setSelectAllOnFocus(true);
        new AlertDialog.Builder(getContext()).setTitle(title+" • cm/mm")
                .setMessage(message).setView(input)
                .setPositiveButton("اعمال",(d,w)->{
                    try{
                        double v=parseLengthMm(input.getText().toString());
                        if(!signed)v=Math.abs(v);
                        consumer.accept(v);
                    }catch(Exception e){toast("اندازه درست وارد نشده");}
                }).setNegativeButton("لغو",null).show();
    }

    private void showExactMoveDialog(){
        Object body=selectedBody();
        if(body==null){toast("اول Body را انتخاب کن");return;}
        LinearLayout box=new LinearLayout(getContext());box.setOrientation(LinearLayout.VERTICAL);box.setPadding(dp(20),dp(8),dp(20),0);
        EditText x=axisInput(box,"X","0mm"),y=axisInput(box,"Y","0mm"),z=axisInput(box,"Z","0mm");
        new AlertDialog.Builder(getContext()).setTitle("Move Body • OCCT")
                .setMessage("مقدار مثبت یا منفی؛ cm یا mm")
                .setView(box).setPositiveButton("حرکت",(d,w)->{
                    try{
                        Geometry3D.Vec3 v=new Geometry3D.Vec3((float)parseLengthMm(x.getText().toString()),
                                (float)parseLengthMm(y.getText().toString()),(float)parseLengthMm(z.getText().toString()));
                        if(v.length()<1e-7){toast("حرکت صفر است");return;}
                        recordExact(body,new ExactEdit(ExactKind.MOVE,0,null,v));
                    }catch(Exception e){toast("مقدار X/Y/Z درست نیست");}
                }).setNegativeButton("لغو",null).show();
    }

    private EditText axisInput(LinearLayout parent,String axis,String initial){
        TextView t=new TextView(getContext());t.setText(axis+"  (cm/mm)");parent.addView(t);
        EditText e=new EditText(getContext());e.setSingleLine(true);e.setText(initial);e.setSelectAllOnFocus(true);parent.addView(e);return e;
    }

    private void showExactRotateAxis(){
        Object body=selectedBody();if(body==null){toast("اول Body را انتخاب کن");return;}
        String[] axes={"X","Y","Z"};
        new AlertDialog.Builder(getContext()).setTitle("Rotate Body • محور")
                .setItems(axes,(d,w)->showExactRotateAngle(body,w)).setNegativeButton("لغو",null).show();
    }

    private void showExactRotateAngle(Object body,int axisIndex){
        EditText input=new EditText(getContext());input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL|InputType.TYPE_NUMBER_FLAG_SIGNED);
        input.setText("90");input.setSelectAllOnFocus(true);
        Geometry3D.Vec3 axis=axisIndex==0?new Geometry3D.Vec3(1,0,0):axisIndex==1?new Geometry3D.Vec3(0,1,0):new Geometry3D.Vec3(0,0,1);
        new AlertDialog.Builder(getContext()).setTitle("Rotate حول "+axisName(axis))
                .setMessage("زاویه بر حسب درجه؛ مرکز دوران = مرکز جرم Body")
                .setView(input).setPositiveButton("چرخش",(d,w)->{
                    try{
                        double deg=Double.parseDouble(normalizeDigits(input.getText().toString()));
                        if(Math.abs(deg)<1e-8){toast("زاویه صفر است");return;}
                        recordExact(body,new ExactEdit(ExactKind.ROTATE,deg,null,axis));
                    }catch(Exception e){toast("زاویه درست نیست");}
                }).setNegativeButton("لغو",null).show();
    }

    private void recordExact(Object body,ExactEdit edit){
        Object record=ensureNativeRecord(body);
        if(record==null){toast("Shape دقیق برای این Body موجود نیست");return;}
        long oldHandle=recordHandle(record);
        long next=applyKernel(oldHandle,edit);
        if(next==0L){toast(failureText(edit.kind));return;}
        if(!replaceRecord(body,record,next,appendKind(recordKind(record),edit.kind))){
            NativeBRepKernel.occtRelease(next);toast("به‌روزرسانی Shape انجام نشد");return;
        }
        List<ExactEdit> list=exactEditsByBody.get(body);
        if(list==null){list=new ArrayList<>();exactEditsByBody.put(body,list);}list.add(edit);
        clearSubSelection();
        updateFallbackFromNative(body);
        ensure3D();invalidate();
        toast(edit.label()+" • OCCT Exact");
    }

    private long applyKernel(long handle,ExactEdit e){
        if(handle==0L||e==null)return 0L;
        switch(e.kind){
            case FILLET_EDGE:return NativeBRepKernel.occtFillet(handle,e.anchor,e.value,false);
            case CHAMFER_EDGE:return NativeBRepKernel.occtChamfer(handle,e.anchor,e.value,false);
            case PUSH_PULL_FACE:return NativeBRepKernel.occtPushPullFace(handle,e.anchor,e.value);
            case SHELL_FACE:return NativeBRepKernel.occtShell(handle,e.anchor,e.value);
            case MOVE:return NativeBRepKernel.occtTranslate(handle,e.vector);
            default:return NativeBRepKernel.occtRotate(handle,e.vector,e.value);
        }
    }

    private String failureText(ExactKind kind){
        if(kind==ExactKind.FILLET_EDGE)return"Fillet برای این Edge/شعاع توسط OCCT ساخته نشد؛ شعاع را کمتر کن";
        if(kind==ExactKind.CHAMFER_EDGE)return"Chamfer برای این Edge/فاصله ساخته نشد؛ مقدار را کمتر کن";
        if(kind==ExactKind.PUSH_PULL_FACE)return"Push/Pull فقط روی Face تخت معتبر اجرا می‌شود یا فاصله نامعتبر است";
        if(kind==ExactKind.SHELL_FACE)return"Shell با این ضخامت/Face ساخته نشد؛ ضخامت را کمتر کن";
        return"Transform دقیق انجام نشد";
    }

    public void showExactHistory(){
        Object body=selectedBody();if(body==null){toast("اول Body را انتخاب کن");return;}
        List<ExactEdit> list=exactEditsByBody.get(body);
        if(list==null||list.isEmpty()){toast("Exact Direct History خالی است");return;}
        String[] rows=new String[list.size()];for(int i=0;i<list.size();i++)rows[i]=(i+1)+". "+list.get(i).label();
        new AlertDialog.Builder(getContext()).setTitle("Exact Direct History • "+bodyName(body))
                .setMessage("Feature را لمس کن تا حذف شود؛ Shape از History دوباره ساخته می‌شود.")
                .setItems(rows,(d,w)->confirmDeleteExact(body,w))
                .setNeutralButton("بازسازی",(d,w)->toast(rebuildExactBody(body)))
                .setNegativeButton("بستن",null).show();
    }

    private void confirmDeleteExact(Object body,int index){
        List<ExactEdit> list=exactEditsByBody.get(body);if(list==null||index<0||index>=list.size())return;
        new AlertDialog.Builder(getContext()).setTitle("حذف "+list.get(index).label()+"؟")
                .setPositiveButton("حذف",(d,w)->{list.remove(index);if(list.isEmpty())exactEditsByBody.remove(body);toast(rebuildExactBody(body));})
                .setNegativeButton("لغو",null).show();
    }

    private String undoExactEdit(){
        Object body=selectedBody();if(body==null)return"اول Body را انتخاب کن";
        List<ExactEdit> list=exactEditsByBody.get(body);if(list==null||list.isEmpty())return"Exact Undo خالی است";
        list.remove(list.size()-1);if(list.isEmpty())exactEditsByBody.remove(body);
        String r=rebuildExactBody(body);return r.startsWith("✓")?"آخرین Exact Edit برگشت":r;
    }

    private String rebuildExactBody(Object body){
        forceParentSync();
        Object record=nativeRecord(body);if(record==null)return"Shape پایه OCCT پیدا نشد";
        List<ExactEdit> edits=exactEditsByBody.get(body);
        if(edits==null||edits.isEmpty()){
            updateFallbackFromNative(body);clearSubSelection();invalidate();return"✓ Shape پایه بازسازی شد";
        }
        long base=recordHandle(record),current=base;
        List<Long> generated=new ArrayList<>();
        for(ExactEdit e:edits){
            long next=applyKernel(current,e);
            if(next==0L){for(long h:generated)NativeBRepKernel.occtRelease(h);return"خطا در بازسازی: "+e.label();}
            generated.add(next);current=next;
        }
        if(!replaceRecordWithoutRelease(body,record,current,"Exact Direct × "+edits.size())){
            for(long h:generated)NativeBRepKernel.occtRelease(h);return"خطا در ثبت Shape بازسازی‌شده";
        }
        NativeBRepKernel.occtRelease(base);
        for(int i=0;i<generated.size()-1;i++)NativeBRepKernel.occtRelease(generated.get(i));
        updateFallbackFromNative(body);clearSubSelection();invalidate();
        return"✓ Exact Direct بازسازی شد • "+edits.size()+" Feature";
    }

    private void replayAllExact(){
        for(Object body:new ArrayList<>(exactEditsByBody.keySet()))rebuildExactBody(body);
    }

    @Override
    public String rebuildHistory(){
        String result=super.rebuildHistory();
        replayExactAfterFreshParentSync();
        return result+(exactEditsByBody.isEmpty()?"":" • ExactDirect "+exactEditsByBody.size());
    }

    private void replayExactAfterFreshParentSync(){
        // super.rebuildHistory() has already rebuilt the parent native records;
        // replay without forcing another parent rebuild for every body.
        for(Object body:new ArrayList<>(exactEditsByBody.keySet())){
            Object record=nativeRecord(body);if(record==null)continue;
            List<ExactEdit> edits=exactEditsByBody.get(body);if(edits==null||edits.isEmpty())continue;
            long base=recordHandle(record),current=base;List<Long> generated=new ArrayList<>();boolean ok=true;
            for(ExactEdit e:edits){long next=applyKernel(current,e);if(next==0L){ok=false;break;}generated.add(next);current=next;}
            if(ok&&replaceRecordWithoutRelease(body,record,current,"Exact Direct × "+edits.size())){
                NativeBRepKernel.occtRelease(base);for(int i=0;i<generated.size()-1;i++)NativeBRepKernel.occtRelease(generated.get(i));updateFallbackFromNative(body);
            }else for(long h:generated)NativeBRepKernel.occtRelease(h);
        }
    }

    @Override
    public void clearAll(){exactEditsByBody.clear();super.clearAll();clearSubSelection();}

    // ------------------------------------------------------------------
    // Edge picking / highlighting
    // ------------------------------------------------------------------

    @Override
    public boolean onTouchEvent(MotionEvent event){
        int action=event.getActionMasked();
        if(exactEdgePickMode){
            if(action==MotionEvent.ACTION_DOWN){exactEdgeDownX=event.getX();exactEdgeDownY=event.getY();exactEdgeMoved=false;}
            else if(action==MotionEvent.ACTION_MOVE&&Math.hypot(event.getX()-exactEdgeDownX,event.getY()-exactEdgeDownY)>10f)exactEdgeMoved=true;
        }
        boolean handled=super.onTouchEvent(event);
        if(exactEdgePickMode&&action==MotionEvent.ACTION_UP&&!exactEdgeMoved&&is3DOverview()){
            if(pickNearestWorkspaceEdge(event.getX(),event.getY())){
                exactEdgePickMode=false;toast("Edge انتخاب شد — حالا Fillet یا Chamfer را بزن");
            }else toast("لبه نزدیک محل لمس پیدا نشد");
            invalidate();
        }
        return handled;
    }

    @Override
    protected void onDraw(Canvas canvas){
        super.onDraw(canvas);
        if(is3DOverview()&&exactEdgeA!=null&&exactEdgeB!=null&&exactEdgeBody==selectedBody()){
            PointF a=project(exactEdgeA),b=project(exactEdgeB);canvas.drawLine(a.x,a.y,b.x,b.y,exactEdgePaint);
        }
    }

    private boolean pickNearestWorkspaceEdge(float sx,float sy){
        Object body=selectedBody();SolidCSG csg=bodyCsg(body);if(body==null||csg==null)return false;
        float best=38f;Geometry3D.Vec3 ba=null,bb=null;
        for(SolidCSG.Polygon p:csg.polygons()){
            int n=p.vertices.size();for(int i=0;i<n;i++){
                Geometry3D.Vec3 a=p.vertices.get(i).pos,b=p.vertices.get((i+1)%n).pos;
                PointF pa=project(a),pb=project(b);float d=distanceToSegment(sx,sy,pa.x,pa.y,pb.x,pb.y);
                if(d<best){best=d;ba=a;bb=b;}
            }
        }
        if(ba==null)return false;
        exactEdgeA=ba;exactEdgeB=bb;exactEdgeAnchor=ba.add(bb).mul(0.5f);exactEdgeBody=body;return true;
    }

    private static float distanceToSegment(float px,float py,float ax,float ay,float bx,float by){
        float dx=bx-ax,dy=by-ay;float den=dx*dx+dy*dy;if(den<1e-8f)return(float)Math.hypot(px-ax,py-ay);
        float t=((px-ax)*dx+(py-ay)*dy)/den;t=Math.max(0f,Math.min(1f,t));
        return(float)Math.hypot(px-(ax+t*dx),py-(ay+t*dy));
    }

    // ------------------------------------------------------------------
    // Parent NativeRecord bridge
    // ------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private Map<Object,Object> nativeMap(){
        try{Object v=nativeByBodyField==null?null:nativeByBodyField.get(this);return v instanceof Map?(Map<Object,Object>)v:null;}
        catch(Exception e){return null;}
    }

    private Object nativeRecord(Object body){Map<Object,Object> m=nativeMap();return m==null||body==null?null:m.get(body);}

    private Object ensureNativeRecord(Object body){
        Object r=nativeRecord(body);if(r!=null)return r;forceParentSync();return nativeRecord(body);
    }

    private void forceParentSync(){try{if(parentSyncMethod!=null)parentSyncMethod.invoke(this,true);}catch(Exception ignored){}}

    private long recordHandle(Object record){try{return recordHandleField==null||record==null?0L:recordHandleField.getLong(record);}catch(Exception e){return 0L;}}
    private String recordKind(Object record){try{Object v=recordKindField==null||record==null?null:recordKindField.get(record);return v==null?"OCCT":String.valueOf(v);}catch(Exception e){return"OCCT";}}

    private boolean replaceRecord(Object body,Object oldRecord,long newHandle,String kind){
        if(!replaceRecordWithoutRelease(body,oldRecord,newHandle,kind))return false;
        NativeBRepKernel.occtRelease(recordHandle(oldRecord));return true;
    }

    private boolean replaceRecordWithoutRelease(Object body,Object oldRecord,long newHandle,String kind){
        try{
            Map<Object,Object> map=nativeMap();if(map==null||nativeRecordConstructor==null||newHandle==0L)return false;
            double[] mesh=NativeBRepKernel.occtTriangulate(newHandle,0.28);
            Object next=nativeRecordConstructor.newInstance(newHandle,kind,mesh);map.put(body,next);return true;
        }catch(Exception e){return false;}
    }

    private void updateFallbackFromNative(Object body){
        Object record=nativeRecord(body);if(record==null||body==null)return;
        double[] mesh=NativeBRepKernel.occtTriangulate(recordHandle(record),0.28);if(mesh.length<9)return;
        List<SolidCSG.Polygon> polys=new ArrayList<>();
        for(int i=0;i+8<mesh.length;i+=9){
            List<SolidCSG.Vertex> v=new ArrayList<>(3);
            v.add(new SolidCSG.Vertex(new Geometry3D.Vec3((float)mesh[i],(float)mesh[i+1],(float)mesh[i+2])));
            v.add(new SolidCSG.Vertex(new Geometry3D.Vec3((float)mesh[i+3],(float)mesh[i+4],(float)mesh[i+5])));
            v.add(new SolidCSG.Vertex(new Geometry3D.Vec3((float)mesh[i+6],(float)mesh[i+7],(float)mesh[i+8])));
            polys.add(new SolidCSG.Polygon(v));
        }
        try{Field f=findField(body.getClass(),"csg");if(f!=null)f.set(body,SolidCSG.fromPolygons(polys));}catch(Exception ignored){}
    }

    private Object selectedBody(){try{return selectedBodyField==null?null:selectedBodyField.get(this);}catch(Exception e){return null;}}
    private SolidCSG.Polygon selectedFace(){try{Object v=selectedFaceField==null?null:selectedFaceField.get(this);return v instanceof SolidCSG.Polygon?(SolidCSG.Polygon)v:null;}catch(Exception e){return null;}}
    private SolidCSG bodyCsg(Object body){try{Field f=findField(body.getClass(),"csg");Object v=f==null?null:f.get(body);return v instanceof SolidCSG?(SolidCSG)v:null;}catch(Exception e){return null;}}

    private void clearSubSelection(){
        exactEdgeA=exactEdgeB=exactEdgeAnchor=null;exactEdgeBody=null;exactEdgePickMode=false;
        try{if(selectedFaceField!=null)selectedFaceField.set(this,null);}catch(Exception ignored){}
    }

    private PointF project(Geometry3D.Vec3 p){try{Object v=projectMethod.invoke(this,p);return v instanceof PointF?(PointF)v:new PointF();}catch(Exception e){return new PointF();}}
    private void ensure3D(){if(!is3DOverview())toggle3DOverview();}

    private static Field findField(Class<?> c,String name){Class<?> x=c;while(x!=null){try{Field f=x.getDeclaredField(name);f.setAccessible(true);return f;}catch(Exception e){x=x.getSuperclass();}}return null;}
    private static String bodyName(Object body){if(body==null)return"Body";try{Field f=findField(body.getClass(),"name");Object v=f==null?null:f.get(body);return v==null?"Body":String.valueOf(v);}catch(Exception e){return"Body";}}

    // ------------------------------------------------------------------
    // Unit / formatting helpers
    // ------------------------------------------------------------------

    private static double parseLengthMm(String raw){
        String s=normalizeDigits(raw).toLowerCase(Locale.US).trim().replace('،','.').replace(',','.');
        boolean mm=s.endsWith("mm")||s.endsWith("میلیمتر")||s.endsWith("میلی‌متر");
        boolean cm=s.endsWith("cm")||s.endsWith("سانتیمتر")||s.endsWith("سانتی‌متر");
        s=s.replace("میلی‌متر","").replace("میلیمتر","").replace("سانتی‌متر","").replace("سانتیمتر","").replace("mm","").replace("cm","").trim();
        double v=Double.parseDouble(s);return mm?v:v*10.0; // bare number stays cm for compatibility
    }

    private static String normalizeDigits(String s){
        if(s==null)return"";StringBuilder b=new StringBuilder();for(int i=0;i<s.length();i++){
            char c=s.charAt(i);if(c>='۰'&&c<='۹')b.append((char)('0'+c-'۰'));else if(c>='٠'&&c<='٩')b.append((char)('0'+c-'٠'));else b.append(c);
        }return b.toString().trim();
    }

    private static String dual(double mm){return num(mm/10.0)+" cm / "+num(mm)+" mm";}
    private static String signedDual(double mm){return(mm>=0?"+":"")+dual(mm);}
    private static String num(double v){String s=String.format(Locale.US,"%.3f",v);while(s.contains(".")&&(s.endsWith("0")||s.endsWith(".")))s=s.substring(0,s.length()-1);return s;}
    private static String axisName(Geometry3D.Vec3 a){if(a==null)return"?";if(Math.abs(a.x)>0.9f)return"X";if(Math.abs(a.y)>0.9f)return"Y";return"Z";}
    private static String appendKind(String base,ExactKind k){return(base==null?"OCCT":base)+" + "+k.name();}
    private int dp(int v){return Math.round(v*getResources().getDisplayMetrics().density);}
    private void toast(String s){if(s!=null&&!s.trim().isEmpty())Toast.makeText(getContext(),s,Toast.LENGTH_LONG).show();}
}

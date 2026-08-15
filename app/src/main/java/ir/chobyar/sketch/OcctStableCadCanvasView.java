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

    private enum Kind { FILLET, CHAMFER, PUSH_PULL, SHELL, MOVE, ROTATE }

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
                default:return prefix+"Rotate "+axisName(vector)+" • "+num(value)+"°";
            }
        }

        private String topologySuffix(){return target==null?"":" • "+target.id;}
    }

    private static final class TimelineEntry {
        final Object body;
        final StableEdit edit;
        TimelineEntry(Object body,StableEdit edit){this.body=body;this.edit=edit;}
    }

    private final IdentityHashMap<Object,List<StableEdit>> stableByBody=new IdentityHashMap<>();
    private final List<TimelineEntry> timeline=new ArrayList<>();
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

    public OcctStableCadCanvasView(Context context){
        super(context);
        stableEdgePaint.setColor(Color.rgb(0,115,210));
        stableEdgePaint.setStrokeWidth(9f);
        stableEdgePaint.setStrokeCap(Paint.Cap.ROUND);
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
                    else if(w==7)showHistoryManager();
                    else if(w==8)toast(undoStable());
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
        new AlertDialog.Builder(getContext()).setTitle(title+" • cm/mm").setMessage(message).setView(input)
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
        new AlertDialog.Builder(getContext()).setTitle((editing==null?"Move Body":"ویرایش D"+editing.id)+" • cm/mm")
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

    private EditText axisInput(LinearLayout parent,String axis,String initial){
        TextView t=new TextView(getContext());t.setText(axis+"  (cm/mm)");parent.addView(t);
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
        Geometry3D.Vec3 anchor=null;
        if(edit.target!=null){
            OcctTopologyRef.Resolution r=OcctTopologyRef.resolve(handle,edit.target);
            if(r==null||r.score>180.0){edit.broken=true;edit.warning="Topology دوباره پیدا نشد";return 0L;}
            anchor=r.anchor;
        }
        switch(edit.kind){
            case FILLET:return NativeBRepKernel.occtFillet(handle,anchor,edit.value,false);
            case CHAMFER:return NativeBRepKernel.occtChamfer(handle,anchor,edit.value,false);
            case PUSH_PULL:return NativeBRepKernel.occtPushPullFace(handle,anchor,edit.value);
            case SHELL:return NativeBRepKernel.occtShell(handle,anchor,edit.value);
            case MOVE:return NativeBRepKernel.occtTranslate(handle,edit.vector);
            default:return NativeBRepKernel.occtRotate(handle,edit.vector,edit.value);
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
            if(replayOneBody(body))ok++;else broken++;
        }
        invalidate();dispatchWorkspaceState();
        return "Stable History بازسازی شد • "+ok+" Body"+(broken>0?" • "+broken+" خطا":"");
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
    }

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

    @SuppressWarnings("unchecked")
    private List<Object> bodies(){try{Object v=bodiesField==null?null:bodiesField.get(this);return v instanceof List?(List<Object>)v:new ArrayList<>();}catch(Exception e){return new ArrayList<>();}}

    private void pruneDeadBodies(){
        List<Object> alive=bodies();
        Iterator<Map.Entry<Object,List<StableEdit>>> it=stableByBody.entrySet().iterator();
        while(it.hasNext())if(!alive.contains(it.next().getKey()))it.remove();
        for(int i=timeline.size()-1;i>=0;i--)if(!alive.contains(timeline.get(i).body))timeline.remove(i);
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
        boolean mm=s.endsWith("mm")||s.endsWith("میلیمتر")||s.endsWith("میلی‌متر");
        s=s.replace("میلی‌متر","").replace("میلیمتر","").replace("سانتی‌متر","").replace("سانتیمتر","").replace("mm","").replace("cm","").trim();
        double v=Double.parseDouble(s);return mm?v:v*10.0;
    }

    private static String normalizeDigits(String s){if(s==null)return"";StringBuilder b=new StringBuilder();for(int i=0;i<s.length();i++){char c=s.charAt(i);if(c>='۰'&&c<='۹')b.append((char)('0'+c-'۰'));else if(c>='٠'&&c<='٩')b.append((char)('0'+c-'٠'));else b.append(c);}return b.toString().trim();}
    private static String dual(double mm){return num(mm/10.0)+" cm / "+num(mm)+" mm";}
    private static String signedDual(double mm){return(mm>=0?"+":"")+dual(mm);}
    private static String num(double v){String s=String.format(Locale.US,"%.3f",v);while(s.contains(".")&&(s.endsWith("0")||s.endsWith(".")))s=s.substring(0,s.length()-1);return s;}
    private static String mmText(double mm){return num(mm)+"mm";}
    private static String axisName(Geometry3D.Vec3 a){if(a==null)return"?";if(Math.abs(a.x)>0.9f)return"X";if(Math.abs(a.y)>0.9f)return"Y";return"Z";}
    private static String appendKind(String base,StableEdit e){return(base==null?"OCCT":base)+" + D"+e.id+"/"+e.kind.name();}
    private int dp(int v){return Math.round(v*getResources().getDisplayMetrics().density);}
    private void toast(String s){if(s!=null&&!s.trim().isEmpty())Toast.makeText(getContext(),s,Toast.LENGTH_LONG).show();}
}
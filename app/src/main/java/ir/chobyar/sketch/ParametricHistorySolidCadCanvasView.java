package ir.chobyar.sketch;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.PointF;
import android.text.InputType;
import android.view.MotionEvent;
import android.widget.EditText;
import android.widget.Toast;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Parametric history layer for ChobYar Solid 3D.
 *
 * The important behavior is dependency-based rather than snapshot-only: an
 * Extrude keeps references to the sketch entities that created it, and Boolean
 * features keep references to their input bodies. When source sketch geometry
 * changes, Extrude is rebuilt and downstream Boolean features are evaluated in
 * order. This is the first real History tree for the Android prototype.
 */
public class ParametricHistorySolidCadCanvasView extends DualUnitSolidCadCanvasView {

    private static final float LINE_JOIN_TOL_MM = 0.30f;
    private static final int CIRCLE_SEGMENTS = 72;

    private abstract static class Feature {
        final int id;
        final String kind;
        Object outputBody;
        boolean broken;
        String warning = "";
        Feature(int id, String kind) { this.id=id; this.kind=kind; }
        abstract String detail();
    }

    private static final class ExtrudeFeature extends Feature {
        final List<Object> sourceEntities;
        final String sourceLayer;
        final Geometry3D.Plane3D plane;
        float heightMm;
        String signature = "";
        ExtrudeFeature(int id, List<Object> src, String layer, Geometry3D.Plane3D plane, float heightMm) {
            super(id,"Extrude");
            this.sourceEntities=new ArrayList<>(src);
            this.sourceLayer=layer;
            this.plane=plane;
            this.heightMm=heightMm;
        }
        @Override String detail() { return "Extrude • "+dual(heightMm)+(broken?" • ⚠":""); }
    }

    private static final class BooleanFeature extends Feature {
        final String operation;
        final Object leftBody;
        final Object rightBody;
        BooleanFeature(int id,String operation,Object left,Object right) {
            super(id,operation);
            this.operation=operation;
            this.leftBody=left;
            this.rightBody=right;
        }
        @Override String detail() {
            String fa="UNION".equals(operation)?"Union":"SUBTRACT".equals(operation)?"Subtract":"Intersect";
            return fa+(broken?" • ⚠":"");
        }
    }

    private static final class Profile {
        final List<PointF> points;
        final String layer;
        Profile(List<PointF> points,String layer){this.points=points;this.layer=layer;}
    }

    private final List<Feature> history = new ArrayList<>();
    private final ArrayDeque<Feature> redoHistory = new ArrayDeque<>();
    private final IdentityHashMap<Object,Feature> producerByBody = new IdentityHashMap<>();
    private int featureSerial=1;
    private boolean rebuilding=false;

    private Field selectedField;
    private Field selectedObjectsField;
    private Field entitiesField;
    private Field planeByLayerField;
    private Field activePlaneField;
    private Field bodiesField;
    private Field selectedBodyField;
    private Field selectedFaceField;
    private Method applyBooleanMethod;
    private Method showBodiesDialogMethod;

    public ParametricHistorySolidCadCanvasView(Context context) {
        super(context);
        initHistoryReflection();
    }

    private void initHistoryReflection() {
        try {
            selectedField=field(CadCanvasView.class,"selected");
            selectedObjectsField=field(SmartCadCanvasView.class,"selectedObjects");
            entitiesField=field(CadCanvasView.class,"entities");
            planeByLayerField=field(SpatialCadCanvasView.class,"planeByLayer");
            activePlaneField=field(SpatialCadCanvasView.class,"activePlane");
            bodiesField=field(SolidCadCanvasView.class,"bodies");
            selectedBodyField=field(SolidCadCanvasView.class,"selectedBody");
            selectedFaceField=field(SolidCadCanvasView.class,"selectedFace");
            Class<?> bodyClass=Class.forName("ir.chobyar.sketch.SolidCadCanvasView$SolidBody");
            applyBooleanMethod=SolidCadCanvasView.class.getDeclaredMethod("applyBoolean",String.class,bodyClass,bodyClass);
            applyBooleanMethod.setAccessible(true);
            showBodiesDialogMethod=SolidCadCanvasView.class.getDeclaredMethod("showBodiesDialog");
            showBodiesDialogMethod.setAccessible(true);
        } catch (Exception ignored) {}
    }

    private static Field field(Class<?> owner,String name) throws NoSuchFieldException {
        Field f=owner.getDeclaredField(name);f.setAccessible(true);return f;
    }

    // ------------------------------------------------------------------
    // Feature creation
    // ------------------------------------------------------------------

    @Override
    public String extrudeSelectedBody(float heightCm) {
        if(rebuilding) return super.extrudeSelectedBody(heightCm);
        // Derived region resolvers may temporarily select a synthetic closed
        // profile so the solid kernel receives one clean contour.  History must
        // still depend on the user's original sketch edges, otherwise editing a
        // line would leave the Extrude detached from its Sketch.
        List<Object> sources=historySourceEntities();
        Profile p=profileFromSources(sources);
        Geometry3D.Plane3D plane=p==null?activePlane():planeForLayer(p.layer);
        String result=super.extrudeSelectedBody(heightCm);
        Object body=selectedBody();
        if(body!=null && p!=null && result!=null && result.contains("ساخته شد")){
            ExtrudeFeature f=new ExtrudeFeature(featureSerial++,sources,p.layer,plane,heightCm*10f);
            f.outputBody=body;
            f.signature=profileSignature(p.points);
            history.add(f);
            producerByBody.put(body,f);
            redoHistory.clear();
        }
        return result;
    }

    /**
     * Hook for higher-level region resolvers.  The default is the literal
     * selection; subclasses can expand a generated region back to the sketch
     * entities that define it.
     */
    protected List<Object> historySourceEntities(){return selectionObjects();}

    /** Solid menu with History-aware Boolean operations. */
    @Override
    public void showSolidManager() {
        String bodyName=bodyName(selectedBody());
        String[] items={
                "⬆ Extrude / تبدیل Sketch بسته به Body",
                "▣ Bodies ("+bodyCount()+")",
                "▱ Sketch روی Face انتخاب‌شده",
                "∪ Union / یکی‌کردن",
                "− Subtract / کم‌کردن",
                "∩ Intersect / اشتراک",
                "⏱ History / تاریخچه پارامتریک ("+history.size()+")",
                "↶ Undo آخرین Feature",
                is3DOverview()?"□ برگشت به Sketch 2D":"◇ نمایش 3D"
        };
        new AlertDialog.Builder(getContext())
                .setTitle("Solid 3D • Parametric")
                .setMessage((bodyName.isEmpty()?"هیچ Body انتخاب نشده":"Body: "+bodyName)
                        +"\nتغییر Sketchهای منبع، Extrude و Booleanهای وابسته را دوباره محاسبه می‌کند.")
                .setItems(items,(d,w)->{
                    if(w==0)showHistoryExtrudeDialog();
                    else if(w==1)invokeBodiesDialog();
                    else if(w==2)toast(sketchOnSelectedFace());
                    else if(w==3)startHistoryBoolean("UNION");
                    else if(w==4)startHistoryBoolean("SUBTRACT");
                    else if(w==5)startHistoryBoolean("INTERSECT");
                    else if(w==6)showHistoryManager();
                    else if(w==7)toast(undoLastFeature());
                    else toast(toggle3DOverview());
                })
                .setNegativeButton("بستن",null).show();
    }

    private void showHistoryExtrudeDialog() {
        EditText input=new EditText(getContext());
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setText("20");input.setSelectAllOnFocus(true);
        new AlertDialog.Builder(getContext())
                .setTitle("Extrude — mm")
                .setMessage("اندازه را به میلی‌متر وارد کن. عدد منفی جهت Extrude را برعکس می‌کند.")
                .setView(input)
                .setPositiveButton("ساخت Body",(d,w)->{
                    try{float mm=parseLengthMm(input.getText().toString());toast(extrudeSelectedBody(mm/10f));}
                    catch(Exception e){toast("ارتفاع درست وارد نشده");}
                })
                .setNegativeButton("لغو",null).show();
    }

    private void startHistoryBoolean(String op) {
        List<Object> bodies=bodies();
        if(bodies.size()<2){toast("برای Boolean حداقل دو Body لازم است");return;}
        Object primary=selectedBody();
        if(primary==null){
            String[] names=new String[bodies.size()];
            for(int i=0;i<bodies.size();i++)names[i]=bodyName(bodies.get(i));
            new AlertDialog.Builder(getContext()).setTitle("Body اصلی")
                    .setItems(names,(d,w)->chooseHistoryBoolean(op,bodies.get(w)))
                    .setNegativeButton("لغو",null).show();
        }else chooseHistoryBoolean(op,primary);
    }

    private void chooseHistoryBoolean(String op,Object primary) {
        List<Object> options=new ArrayList<>();
        for(Object b:bodies())if(b!=primary)options.add(b);
        if(options.isEmpty()){toast("Body دوم وجود ندارد");return;}
        String[] names=new String[options.size()];
        for(int i=0;i<options.size();i++)names[i]=bodyName(options.get(i));
        new AlertDialog.Builder(getContext())
                .setTitle(op+" — Body دوم")
                .setMessage("Body اصلی: "+bodyName(primary))
                .setItems(names,(d,w)->toast(applyHistoryBoolean(op,primary,options.get(w))))
                .setNegativeButton("لغو",null).show();
    }

    private String applyHistoryBoolean(String op,Object left,Object right) {
        try{
            Object before=selectedBody();
            setSelectedBody(left);
            String result=String.valueOf(applyBooleanMethod.invoke(this,op,left,right));
            Object out=selectedBody();
            if(out!=null && out!=left && out!=right && !result.contains("تغییر نکردند")){
                BooleanFeature f=new BooleanFeature(featureSerial++,op,left,right);
                f.outputBody=out;
                history.add(f);
                producerByBody.put(out,f);
                redoHistory.clear();
            } else if(before!=null) setSelectedBody(before);
            return result;
        }catch(Exception e){return "Boolean انجام نشد";}
    }

    /**
     * Non-modal Boolean entry for the command bar and instrumentation.
     * Body numbers are 1-based and refer to the current Items/Bodies order.
     * The operation still goes through the parametric History path so Undo,
     * Redo and rebuild keep the exact same dependency graph as the UI tool.
     */
    public String applyHistoryBooleanByIndex(String operation,int leftNumber,int rightNumber) {
        String op=operation==null?"":operation.trim().toUpperCase(Locale.US);
        if(!"UNION".equals(op)&&!"SUBTRACT".equals(op)&&!"INTERSECT".equals(op))
  return "عملیات Boolean نامعتبر است";
        List<Object> current=bodies();
        if(current.size()<2)return "برای Boolean حداقل دو Body لازم است";
        if(leftNumber<1||rightNumber<1||leftNumber>current.size()||rightNumber>current.size())
  return "شماره Body باید بین 1 تا "+current.size()+" باشد";
        if(leftNumber==rightNumber)return "دو Body متفاوت لازم است";
        return applyHistoryBoolean(op,current.get(leftNumber-1),current.get(rightNumber-1));
    }

    @Override
    public String executeCommand(String raw) {
        if(raw!=null){
  String s=normalizeDigits(raw).trim().replace(',',' ');
  if(!s.isEmpty()){
      String[] a=s.split("\\s+");
      String op=a[0].toUpperCase(Locale.US);
      boolean booleanOp="UNION".equals(op)||"SUBTRACT".equals(op)||"INTERSECT".equals(op);
      // Preserve the existing selection/dialog workflow for a bare
      // UNION/SUBTRACT/INTERSECT command.  Supplying body numbers
      // makes the command deterministic and non-modal.
      if(booleanOp&&a.length>1){
          if(a.length!=3)return op+" — دو شماره Body لازم است؛ مثال: "+op+" 1 2";
          try{
              return applyHistoryBooleanByIndex(op,Integer.parseInt(a[1]),Integer.parseInt(a[2]));
          }catch(NumberFormatException e){
              return "شماره Body باید عدد صحیح باشد";
          }
      }
  }
        }
        return super.executeCommand(raw);
    }

    // ------------------------------------------------------------------
    // Parametric rebuild
    // ------------------------------------------------------------------

    public String rebuildHistory() {
        if(rebuilding)return"";
        rebuilding=true;
        int rebuilt=0,broken=0;
        try{
            for(Feature f:history){
                if(f instanceof ExtrudeFeature){
                    ExtrudeFeature e=(ExtrudeFeature)f;
                    Profile p=profileFromSources(e.sourceEntities);
                    if(p==null||p.points.size()<3){
                        e.broken=true;e.warning="Sketch منبع پیدا نشد";broken++;continue;
                    }
                    String sig=profileSignature(p.points);
                    // Always evaluate here; signature is retained so callers can tell whether geometry changed.
                    SolidCSG csg=SolidCSG.extrude(p.points,e.plane,e.heightMm);
                    if(csg.isEmpty()){
                        e.broken=true;e.warning="Extrude نامعتبر شد";broken++;continue;
                    }
                    setBodyCsg(e.outputBody,csg);
                    e.signature=sig;e.broken=false;e.warning="";rebuilt++;
                } else if(f instanceof BooleanFeature){
                    BooleanFeature b=(BooleanFeature)f;
                    SolidCSG a=bodyCsg(b.leftBody),c=bodyCsg(b.rightBody);
                    if(a==null||c==null||a.isEmpty()||c.isEmpty()){
                        b.broken=true;b.warning="ورودی Boolean معتبر نیست";broken++;continue;
                    }
                    SolidCSG result;
                    if("UNION".equals(b.operation))result=a.union(c);
                    else if("SUBTRACT".equals(b.operation))result=a.subtract(c);
                    else result=a.intersect(c);
                    if(result.isEmpty()){
                        b.broken=true;b.warning="نتیجه Boolean خالی است";broken++;continue;
                    }
                    setBodyCsg(b.outputBody,result);
                    b.broken=false;b.warning="";rebuilt++;
                }
            }
        }finally{rebuilding=false;invalidate();}
        return "History بازسازی شد • "+rebuilt+" Feature"+(broken>0?" • "+broken+" خطا":"");
    }

    private void rebuildQuietly(){
        if(history.isEmpty()||rebuilding)return;
        rebuildHistory();
    }

    @Override
    public String applySelectedDimension(String raw) {
        String r=super.applySelectedDimension(raw);
        if(!looksLikeError(r))rebuildQuietly();
        return r;
    }

    @Override
    public void moveSelected(float dx,float dy){super.moveSelected(dx,dy);rebuildQuietly();}

    @Override
    public String rotateSelected(float deg){String r=super.rotateSelected(deg);if(!looksLikeError(r))rebuildQuietly();return r;}

    @Override
    public String scaleSelected(float factor){String r=super.scaleSelected(factor);if(!looksLikeError(r))rebuildQuietly();return r;}

    @Override
    public String mirrorSelected(boolean acrossXAxis,float axisValue){String r=super.mirrorSelected(acrossXAxis,axisValue);if(!looksLikeError(r))rebuildQuietly();return r;}

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        boolean handled=super.onTouchEvent(event);
        if(event.getActionMasked()==MotionEvent.ACTION_UP && !is3DOverview())rebuildQuietly();
        return handled;
    }

    // ------------------------------------------------------------------
    // History UI / editing
    // ------------------------------------------------------------------

    public void showHistoryManager() {
        if(history.isEmpty()){toast("History هنوز خالی است");return;}
        String[] rows=new String[history.size()];
        for(int i=0;i<history.size();i++){
            Feature f=history.get(i);
            rows[i]=(i+1)+". "+f.detail()+(f.broken&&!f.warning.isEmpty()?" — "+f.warning:"");
        }
        new AlertDialog.Builder(getContext())
                .setTitle("History • پارامتریک")
                .setMessage("Feature را لمس کن. تغییر Sketch منبع، عملیات بعدی را به ترتیب بازسازی می‌کند.")
                .setItems(rows,(d,w)->showFeatureEditor(w))
                .setNeutralButton("بازسازی همه",(d,w)->toast(rebuildHistory()))
                .setNegativeButton("بستن",null).show();
    }

    private void showFeatureEditor(int index) {
        if(index<0||index>=history.size())return;
        Feature f=history.get(index);
        if(f instanceof ExtrudeFeature){showExtrudeFeatureEditor((ExtrudeFeature)f,index);return;}
        new AlertDialog.Builder(getContext())
                .setTitle(f.detail())
                .setMessage("این Feature به Bodyهای قبلی وابسته است. با تغییر Extrude یا Sketchهای قبل از آن، نتیجه خودکار دوباره محاسبه می‌شود.")
                .setPositiveButton("بازسازی از اینجا",(d,w)->toast(rebuildHistory()))
                .setNegativeButton("بستن",null).show();
    }

    private void showExtrudeFeatureEditor(ExtrudeFeature f,int index) {
        EditText input=new EditText(getContext());
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setText(num(f.heightMm));input.setSelectAllOnFocus(true);
        new AlertDialog.Builder(getContext())
                .setTitle("ویرایش Extrude #"+f.id)
                .setMessage("فعلی: "+dual(f.heightMm)+"\nمثال: 18 mm")
                .setView(input)
                .setPositiveButton("اعمال و بازسازی",(d,w)->{
                    try{f.heightMm=parseLengthMm(input.getText().toString());toast(rebuildHistory());}
                    catch(Exception e){toast("اندازه درست وارد نشده");}
                })
                .setNegativeButton("لغو",null).show();
    }

    public String undoLastFeature() {
        if(history.isEmpty())return"History خالی است";
        Feature f=history.remove(history.size()-1);
        redoHistory.addLast(f);
        producerByBody.remove(f.outputBody);
        List<Object> bs=bodies();
        bs.remove(f.outputBody);
        if(f instanceof BooleanFeature){
            BooleanFeature b=(BooleanFeature)f;
            if(!bs.contains(b.leftBody))bs.add(b.leftBody);
            if(!bs.contains(b.rightBody))bs.add(b.rightBody);
            setSelectedBody(b.leftBody);
        }else setSelectedBody(bs.isEmpty()?null:bs.get(bs.size()-1));
        setSelectedFace(null);
        invalidate();
        return "Feature آخر برگشت: "+f.kind;
    }

    public String redoLastFeature(){
        if(redoHistory.isEmpty())return "Redo خالی است";
        Feature f=redoHistory.removeLast();
        List<Object> bs=bodies();
        if(f instanceof BooleanFeature){
            BooleanFeature b=(BooleanFeature)f;
            bs.remove(b.leftBody);bs.remove(b.rightBody);
        }
        if(!bs.contains(f.outputBody))bs.add(f.outputBody);
        history.add(f);producerByBody.put(f.outputBody,f);
        setSelectedBody(f.outputBody);setSelectedFace(null);
        String rebuilt=rebuildHistory();post(this::fitAll);invalidate();
        return "Feature دوباره اجرا شد: "+f.kind+" • "+rebuilt;
    }

    public boolean canRedoFeature(){return !redoHistory.isEmpty();}

    @Override
    public String undoSolid(){return undoLastFeature();}

    @Override
    public void clearAll(){
        super.clearAll();
        history.clear();redoHistory.clear();producerByBody.clear();featureSerial=1;
    }

    // ------------------------------------------------------------------
    // Profile dependency extraction
    // ------------------------------------------------------------------

    private Profile profileFromSources(List<Object> src) {
        if(src==null||src.isEmpty())return null;
        // Construction geometry cannot be a History profile source.
        for(Object e:src)if(Boolean.TRUE.equals(call(e,"isConstruction")))return null;
        List<Object> current=entities();
        for(Object e:src)if(!current.contains(e))return null;
        if(src.size()==1){
            Object e=src.get(0);String type=e.getClass().getSimpleName();String layer=entityLayer(e);
            if("RectEntity".equals(type)){
                PointF[] p=pointArray(e,"p");if(p==null)return null;List<PointF> out=new ArrayList<>();for(PointF q:p)out.add(new PointF(q.x,q.y));return new Profile(out,layer);
            }
            if("CircleEntity".equals(type)){
                float cx=safeGet(e,"x"),cy=safeGet(e,"y"),r=Math.abs(safeGet(e,"r"));if(r<=0)return null;
                List<PointF> out=new ArrayList<>();for(int i=0;i<CIRCLE_SEGMENTS;i++){double a=2*Math.PI*i/CIRCLE_SEGMENTS;out.add(new PointF(cx+(float)Math.cos(a)*r,cy+(float)Math.sin(a)*r));}return new Profile(out,layer);
            }
            if("PolygonEntity".equals(type)){
                List<PointF> pts=points(e);return pts.size()>=3?new Profile(pts,layer):null;
            }
            if("PolylineEntity".equals(type)&&booleanField(e,"closed")){
                List<PointF> pts=points(e);return pts.size()>=3?new Profile(pts,layer):null;
            }
            return null;
        }
        for(Object e:src)if(!"LineEntity".equals(e.getClass().getSimpleName()))return null;
        String layer=entityLayer(src.get(0));for(Object e:src)if(!layer.equals(entityLayer(e)))return null;
        List<PointF> loop=stitchLines(src);return loop==null?null:new Profile(loop,layer);
    }

    private List<PointF> stitchLines(List<Object> lines) {
        if(lines.size()<3)return null;
        boolean[] used=new boolean[lines.size()];
        PointF a=endpoint(lines.get(0),0),b=endpoint(lines.get(0),1);if(a==null||b==null)return null;
        List<PointF> out=new ArrayList<>();out.add(a);out.add(b);used[0]=true;PointF current=b;
        for(int step=1;step<lines.size();step++){
            int found=-1;PointF next=null;
            for(int i=1;i<lines.size();i++)if(!used[i]){
                PointF p0=endpoint(lines.get(i),0),p1=endpoint(lines.get(i),1);
                if(dist(current,p0)<=LINE_JOIN_TOL_MM){found=i;next=p1;break;}
                if(dist(current,p1)<=LINE_JOIN_TOL_MM){found=i;next=p0;break;}
            }
            if(found<0)return null;used[found]=true;out.add(next);current=next;
        }
        if(dist(current,out.get(0))>LINE_JOIN_TOL_MM)return null;
        out.remove(out.size()-1);return out.size()>=3?out:null;
    }

    private String profileSignature(List<PointF> pts){
        StringBuilder b=new StringBuilder();
        for(PointF p:pts)b.append(Math.round(p.x*100f)).append(':').append(Math.round(p.y*100f)).append(';');
        return b.toString();
    }

    // ------------------------------------------------------------------
    // Reflection / body helpers
    // ------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private List<Object> selectionObjects(){
        try{
            if(selectedObjectsField!=null){List<Object> s=(List<Object>)selectedObjectsField.get(this);if(s!=null&&!s.isEmpty())return new ArrayList<>(s);}
            Object e=selectedField==null?null:selectedField.get(this);List<Object> one=new ArrayList<>();if(e!=null)one.add(e);return one;
        }catch(Exception e){return new ArrayList<>();}
    }

    @SuppressWarnings("unchecked")
    private List<Object> entities(){try{return entitiesField==null?new ArrayList<>(): (List<Object>)entitiesField.get(this);}catch(Exception e){return new ArrayList<>();}}

    @SuppressWarnings("unchecked")
    private List<Object> bodies(){try{return bodiesField==null?new ArrayList<>(): (List<Object>)bodiesField.get(this);}catch(Exception e){return new ArrayList<>();}}

    private Object selectedBody(){try{return selectedBodyField==null?null:selectedBodyField.get(this);}catch(Exception e){return null;}}
    private void setSelectedBody(Object b){try{if(selectedBodyField!=null)selectedBodyField.set(this,b);}catch(Exception ignored){}}
    private void setSelectedFace(Object f){try{if(selectedFaceField!=null)selectedFaceField.set(this,f);}catch(Exception ignored){}}

    private void invokeBodiesDialog(){try{showBodiesDialogMethod.invoke(this);}catch(Exception e){toast("Bodies باز نشد");}}

    private SolidCSG bodyCsg(Object body){
        try{Field f=findField(body.getClass(),"csg");Object v=f==null?null:f.get(body);return v instanceof SolidCSG?(SolidCSG)v:null;}catch(Exception e){return null;}
    }
    private void setBodyCsg(Object body,SolidCSG csg){try{Field f=findField(body.getClass(),"csg");if(f!=null)f.set(body,csg);}catch(Exception ignored){}}
    private String bodyName(Object body){try{if(body==null)return"";Field f=findField(body.getClass(),"name");Object v=f==null?null:f.get(body);return v==null?"Body":String.valueOf(v);}catch(Exception e){return"Body";}}

    @SuppressWarnings("unchecked")
    private Geometry3D.Plane3D planeForLayer(String layer){
        try{Map<String,Geometry3D.Plane3D> m=(Map<String,Geometry3D.Plane3D>)planeByLayerField.get(this);Geometry3D.Plane3D p=m.get(layer);return p==null?activePlane():p;}catch(Exception e){return activePlane();}
    }
    private Geometry3D.Plane3D activePlane(){try{Object v=activePlaneField.get(this);return v instanceof Geometry3D.Plane3D?(Geometry3D.Plane3D)v:Geometry3D.xy();}catch(Exception e){return Geometry3D.xy();}}

    private static String entityLayer(Object e){Object v=call(e,"getLayer");return v==null?"":String.valueOf(v);}
    private static Object call(Object target,String name){if(target==null)return null;Class<?> c=target.getClass();while(c!=null){try{Method m=c.getDeclaredMethod(name);m.setAccessible(true);return m.invoke(target);}catch(NoSuchMethodException e){c=c.getSuperclass();}catch(Exception e){return null;}}return null;}
    private static Field findField(Class<?> c,String name){Class<?> x=c;while(x!=null){try{Field f=x.getDeclaredField(name);f.setAccessible(true);return f;}catch(Exception e){x=x.getSuperclass();}}return null;}
    private static float safeGet(Object o,String name){try{Field f=findField(o.getClass(),name);return f==null?0f:f.getFloat(o);}catch(Exception e){return 0f;}}
    private static boolean booleanField(Object o,String name){try{Field f=findField(o.getClass(),name);return f!=null&&f.getBoolean(o);}catch(Exception e){return false;}}
    private static PointF[] pointArray(Object o,String name){try{Field f=findField(o.getClass(),name);Object v=f==null?null:f.get(o);return v instanceof PointF[]?(PointF[])v:null;}catch(Exception e){return null;}}
    @SuppressWarnings("unchecked") private static List<PointF> points(Object o){try{Field f=findField(o.getClass(),"points");Object v=f==null?null:f.get(o);if(v instanceof List){List<PointF> out=new ArrayList<>();for(PointF p:(List<PointF>)v)out.add(new PointF(p.x,p.y));return out;}}catch(Exception ignored){}return new ArrayList<>();}
    private static PointF endpoint(Object line,int i){return i==0?new PointF(safeGet(line,"x1"),safeGet(line,"y1")):new PointF(safeGet(line,"x2"),safeGet(line,"y2"));}
    private static float dist(PointF a,PointF b){return(float)Math.hypot(a.x-b.x,a.y-b.y);}

    private static float parseLengthMm(String raw){
        String s=normalizeDigits(raw).trim().toLowerCase(Locale.US).replace(" ","");
        if(s.endsWith("mm"))return Float.parseFloat(s.substring(0,s.length()-2));
        if(s.endsWith("cm"))return Float.parseFloat(s.substring(0,s.length()-2))*10f;
        return Float.parseFloat(s);
    }
    private static String normalizeDigits(String s){if(s==null)return"";StringBuilder b=new StringBuilder();for(int i=0;i<s.length();i++){char c=s.charAt(i);if(c>='۰'&&c<='۹')b.append((char)('0'+c-'۰'));else if(c>='٠'&&c<='٩')b.append((char)('0'+c-'٠'));else b.append(c);}return b.toString();}
    private static String num(float v){String s=String.format(Locale.US,"%.2f",v);while(s.contains(".")&&(s.endsWith("0")||s.endsWith(".")))s=s.substring(0,s.length()-1);return s;}
    private static String dual(float mm){return num(mm)+" mm";}
    private static boolean looksLikeError(String s){if(s==null)return true;return s.contains("اول")||s.contains("نیست")||s.contains("نشده")||s.contains("درست نیست")||s.contains("نامعتبر");}
    private void toast(String s){if(s!=null&&!s.trim().isEmpty())Toast.makeText(getContext(),s,Toast.LENGTH_SHORT).show();}
}

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
import android.widget.Toast;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * First volumetric-solid layer of ChobYar.
 *
 * Closed sketch profiles become real polygonal volumes. The body keeps explicit
 * planar faces, supports face picking and Sketch-on-Face, and Boolean Union /
 * Subtract / Intersect are evaluated by a BSP CSG kernel instead of being a
 * visual-only 2.5D effect. Curves are tessellated for now; an exact B-Rep kernel
 * can replace SolidCSG later without changing the interaction model.
 */
public class SolidCadCanvasView extends SpatialCadCanvasView {
    private boolean gpuBodyRendering;

    private static final float LINE_JOIN_TOL_MM = 0.30f;
    private static final int CIRCLE_SEGMENTS = 56;

    private static final class SolidBody {
        final int id;
        String name;
        SolidCSG csg;
        boolean visible = true;
        SolidBody(int id, String name, SolidCSG csg) { this.id=id; this.name=name; this.csg=csg; }
        SolidBody copy() { SolidBody b=new SolidBody(id,name,csg.copy()); b.visible=visible; return b; }
    }

    private static final class ProfileData {
        final List<PointF> points;
        final String layer;
        ProfileData(List<PointF> points,String layer){this.points=points;this.layer=layer;}
    }

    private static final class FaceRender {
        final SolidBody body;
        final SolidCSG.Polygon polygon;
        final float depth;
        final List<PointF> screen;
        FaceRender(SolidBody body,SolidCSG.Polygon polygon,float depth,List<PointF> screen){
            this.body=body;this.polygon=polygon;this.depth=depth;this.screen=screen;
        }
    }

    private final List<SolidBody> bodies = new ArrayList<>();
    private final ArrayDeque<List<SolidBody>> solidUndo = new ArrayDeque<>();
    private int bodySerial = 1;
    private SolidBody selectedBody;
    private SolidCSG.Polygon selectedFace;
    private Geometry3D.Vec3 selectedEdgeA,selectedEdgeB,selectedVertex;

    private Field selectedField;
    private Field selectedObjectsField;
    private Field planeByLayerField;
    private Field activePlaneField;
    private Field pendingPlaneField;
    private Field overview3DField;
    private Field overviewCardField;
    private Field cameraYawField;
    private Field cameraPitchField;
    private Method projectMethod;

    private boolean solidGesture = false;
    private boolean solidOrbit = false;
    private boolean solidGesturePen = false;
    private float solidDownX, solidDownY, solidLastX, solidLastY;

    private final Paint bodyFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bodyWire = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint selectedWire = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint faceFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bodyText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint extrudePreviewFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint extrudePreviewWire = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint extrudeHandle = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint extrudeHandleFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private SolidCSG extrudePreview;
    private SolidCSG formPreview;
    private String formPreviewLabel="";
    private float extrudePreviewHeightMm = 20f;
    private ProfileData extrudeSessionProfile;
    private Geometry3D.Plane3D extrudeSessionPlane;
    private Geometry3D.Vec3 extrudeSessionOrigin;
    private boolean extrudeSessionActive;
    private boolean extrudeDragging;
    private boolean extrudeDragMoved;
    private float extrudeDragStartX;
    private float extrudeDragStartY;
    private float extrudeDragStartHeightMm;
    private float extrudeDragUx;
    private float extrudeDragUy;
    private float extrudePixelsPerMm;
    private PointF extrudeBaseScreen;
    private PointF extrudeTipScreen;

    public SolidCadCanvasView(Context context) {
        super(context);
        initSolidReflection();
        initSolidPaints();
    }

    private void initSolidReflection() {
        try {
            selectedField = field(CadCanvasView.class,"selected");
            selectedObjectsField = field(SmartCadCanvasView.class,"selectedObjects");
            planeByLayerField = field(SpatialCadCanvasView.class,"planeByLayer");
            activePlaneField = field(SpatialCadCanvasView.class,"activePlane");
            pendingPlaneField = field(SpatialCadCanvasView.class,"pendingPlane");
            overview3DField = field(SpatialCadCanvasView.class,"overview3D");
            overviewCardField = field(SpatialCadCanvasView.class,"overviewCard");
            cameraYawField = field(SpatialCadCanvasView.class,"cameraYaw");
            cameraPitchField = field(SpatialCadCanvasView.class,"cameraPitch");
            projectMethod = SpatialCadCanvasView.class.getDeclaredMethod("project", Geometry3D.Vec3.class);
            projectMethod.setAccessible(true);
        } catch (Exception ignored) {}
    }

    private static Field field(Class<?> owner,String name) throws NoSuchFieldException {
        Field f=owner.getDeclaredField(name);f.setAccessible(true);return f;
    }

    private void initSolidPaints() {
        bodyFill.setColor(Color.argb(68, 76, 137, 210));
        bodyFill.setStyle(Paint.Style.FILL);
        bodyWire.setColor(Color.rgb(35, 73, 120));
        bodyWire.setStyle(Paint.Style.STROKE);
        bodyWire.setStrokeWidth(2.0f);
        selectedWire.setColor(Color.rgb(25, 112, 225));
        selectedWire.setStyle(Paint.Style.STROKE);
        selectedWire.setStrokeWidth(4.2f);
        faceFill.setColor(Color.argb(118, 255, 165, 38));
        faceFill.setStyle(Paint.Style.FILL);
        bodyText.setColor(Color.rgb(35, 58, 88));
        bodyText.setTextSize(22f);
        bodyText.setTextAlign(Paint.Align.CENTER);
        extrudePreviewFill.setColor(Color.argb(105,55,145,230));
        extrudePreviewFill.setStyle(Paint.Style.FILL);
        extrudePreviewWire.setColor(Color.rgb(0,105,210));
        extrudePreviewWire.setStyle(Paint.Style.STROKE);
        extrudePreviewWire.setStrokeWidth(3.2f);
        extrudeHandle.setColor(Color.rgb(0,105,210));
        extrudeHandle.setStyle(Paint.Style.STROKE);
        extrudeHandle.setStrokeWidth(4.2f);
        extrudeHandle.setStrokeCap(Paint.Cap.ROUND);
        extrudeHandleFill.setColor(Color.WHITE);
        extrudeHandleFill.setStyle(Paint.Style.FILL);
    }

    // ------------------------------------------------------------------
    // Public solid workflow
    // ------------------------------------------------------------------

    public void showSolidManager() {
        String selected = selectedBody == null ? "هیچ Body انتخاب نشده" : selectedBody.name;
        String face = selectedFace == null ? "Face انتخاب نشده" : "Face انتخاب شده — آماده Sketch";
        String[] items = {
                "⬆ Extrude / تبدیل Sketch بسته به Body",
                "▣ Bodies ("+bodies.size()+")",
                "▱ Sketch روی Face انتخاب‌شده",
                "∪ Union / یکی‌کردن",
                "− Subtract / کم‌کردن",
                "∩ Intersect / اشتراک",
                "⌫ حذف Body انتخاب‌شده",
                "↶ Undo عملیات Solid",
                is3DOverview()?"□ برگشت به Sketch 2D":"◇ نمایش 3D"
        };
        new AlertDialog.Builder(getContext())
                .setTitle("Solid 3D")
                .setMessage(selected+"\n"+face+"\n\nبرای انتخاب Body یا Face در نمای 3D روی آن بزن.")
                .setItems(items,(d,w)->{
                    if(w==0)showExtrudeDialog();
                    else if(w==1)showBodiesDialog();
                    else if(w==2)toast(sketchOnSelectedFace());
                    else if(w==3)startBoolean("UNION");
                    else if(w==4)startBoolean("SUBTRACT");
                    else if(w==5)startBoolean("INTERSECT");
                    else if(w==6)toast(deleteSelectedBody());
                    else if(w==7)toast(undoSolid());
                    else toast(toggle3DOverview());
                })
                .setNegativeButton("بستن",null)
                .show();
    }

    public String extrudeSelectedBody(float heightCm) {
        if (Math.abs(heightCm) < 0.0001f) return "ارتفاع Extrude نباید صفر باشد";
        ProfileData profile = profileFromSelection();
        if (profile == null || profile.points.size() < 3)
            return "یک سطح بسته انتخاب کن؛ مستطیل، دایره، چندضلعی یا حلقه خطوط متصل";
        Geometry3D.Plane3D plane = planeForLayer(profile.layer);
        SolidCSG solid = SolidCSG.extrude(profile.points, plane, heightCm*10f);
        if (solid.isEmpty()) return "ساخت Body ممکن نشد";
        saveSolidUndo();
        SolidBody body = new SolidBody(bodySerial,"Body "+bodySerial,solid);
        bodySerial++;
        bodies.add(body);
        selectedBody=body;
        selectedFace=null;
        clearSubSelection();
        setOverview(true);
        // A sketch can be heavily zoomed or panned. Reusing that camera made a
        // valid extrusion look enormous, clipped or completely off-screen.
        // Fit after layout/draw has switched to the 3D workspace so bounds are
        // evaluated against the real body and the final viewport size.
        post(this::fitAll);
        invalidate();
        return body.name+" ساخته شد | Extrude = "+fmt(heightCm*10f)+" mm";
    }

    /** Selection-first Boolean entry used by the production adaptive toolbar. */
    public void startBooleanTool(String operation){startBoolean(operation);}

    /**
     * Start a canvas-native extrusion. The body preview and arrow stay in the
     * workspace, so the user can still orbit with a finger instead of being
     * trapped inside a modal slider.
     */
    public void showInteractiveExtrude(){
        toast(beginInteractiveExtrudeSession());
    }

    public String beginInteractiveExtrudeSession(){
        ProfileData profile=profileFromSelection();
        if(profile==null||profile.points.size()<3)return "اول داخل یک پروفایل بسته را انتخاب کن";
        Geometry3D.Plane3D plane=planeForLayer(profile.layer);
        float cx=0f,cy=0f;for(PointF p:profile.points){cx+=p.x;cy+=p.y;}
        cx/=profile.points.size();cy/=profile.points.size();
        extrudeSessionProfile=profile;extrudeSessionPlane=plane;extrudeSessionOrigin=plane.point(cx,cy);
        extrudeSessionActive=true;extrudeDragging=false;extrudePreviewHeightMm=20f;
        setOverview(true);
        rebuildExtrudePreview(true);dispatchWorkspaceState();
        return "Extrude فعال شد • فلش آبی را بکش یا روی آن بزن";
    }

    public boolean isInteractiveExtrudeActive(){return extrudeSessionActive;}

    public String commitInteractiveExtrude(){
        if(!extrudeSessionActive)return "Extrude فعال نیست";
        float mm=extrudePreviewHeightMm;clearExtrudeSession();
        String result=extrudeSelectedBody(mm/10f);dispatchWorkspaceState();return result;
    }

    public void cancelInteractiveExtrude(){clearExtrudeSession();dispatchWorkspaceState();}

    private void rebuildExtrudePreview(boolean fit){
        if(!extrudeSessionActive||extrudeSessionProfile==null||extrudeSessionPlane==null)return;
        if(Math.abs(extrudePreviewHeightMm)<.1f)extrudePreviewHeightMm=extrudePreviewHeightMm<0f?-.1f:.1f;
        extrudePreview=SolidCSG.extrude(extrudeSessionProfile.points,extrudeSessionPlane,extrudePreviewHeightMm);
        if(fit)fitPreview();invalidate();
    }

    private void promptInteractiveExtrudeValue(){
        if(!extrudeSessionActive)return;
        LinearLayout box=new LinearLayout(getContext());box.setOrientation(LinearLayout.VERTICAL);
        int pad=Math.round(18f*getResources().getDisplayMetrics().density);box.setPadding(pad,pad/2,pad,pad/2);
        EditText exact=new EditText(getContext());exact.setSingleLine();exact.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        exact.setText(fmt(extrudePreviewHeightMm)+"mm");exact.setSelectAllOnFocus(true);box.addView(exact);
        new AlertDialog.Builder(getContext()).setTitle("Extrude • mm")
                .setMessage("مقدار منفی جهت حجم را برعکس می‌کند.")
                .setView(box).setPositiveButton("اعمال",(d,w)->{
                    try{extrudePreviewHeightMm=parseLengthInput(exact.getText().toString());rebuildExtrudePreview(true);}
                    catch(Exception e){toast("اندازه درست نیست • مثال: 20 mm");}
                }).setNegativeButton("لغو",null).show();
    }

    private float parseLengthInput(String raw){
        String s=normalizeDigits(raw==null?"":raw).trim().toLowerCase(Locale.US).replace(" ","");
        if(s.endsWith("mm"))return Float.parseFloat(s.substring(0,s.length()-2));
        if(s.endsWith("cm"))return Float.parseFloat(s.substring(0,s.length()-2))*10f;
        return Float.parseFloat(s);
    }

    private void clearExtrudeSession(){
        extrudePreview=null;extrudeSessionProfile=null;extrudeSessionPlane=null;extrudeSessionOrigin=null;
        extrudeSessionActive=false;extrudeDragging=false;extrudeBaseScreen=null;extrudeTipScreen=null;invalidate();
    }

    private void fitPreview(){
        fitSolidPreview(extrudePreview);
    }

    protected void setFormPreview(SolidCSG preview,String label,boolean fit){
        formPreview=preview;formPreviewLabel=label==null?"":label;
        if(fit)fitSolidPreview(preview);invalidate();
    }

    protected void clearFormPreview(){formPreview=null;formPreviewLabel="";invalidate();}

    /** Creates a separately selectable body for exact Copy/Pattern workflows. */
    protected final Object addIndependentBody(String requestedName,SolidCSG csg){
        if(csg==null||csg.isEmpty())return null;saveSolidUndo();
        String name=requestedName==null||requestedName.trim().isEmpty()?"Body "+bodySerial:requestedName.trim();
        SolidBody body=new SolidBody(bodySerial++,name,csg);bodies.add(body);selectedBody=body;selectedFace=null;clearSubSelection();setOverview(true);invalidate();return body;
    }

    protected void showModelOverview(){setOverview(true);}

    private void fitSolidPreview(SolidCSG preview){
        if(preview==null||preview.isEmpty())return;
        float minX=Float.POSITIVE_INFINITY,minY=Float.POSITIVE_INFINITY,minZ=Float.POSITIVE_INFINITY;
        float maxX=Float.NEGATIVE_INFINITY,maxY=Float.NEGATIVE_INFINITY,maxZ=Float.NEGATIVE_INFINITY;
        for(SolidCSG.Polygon p:preview.polygons())for(SolidCSG.Vertex v:p.vertices){Geometry3D.Vec3 q=v.pos;
            minX=Math.min(minX,q.x);minY=Math.min(minY,q.y);minZ=Math.min(minZ,q.z);
            maxX=Math.max(maxX,q.x);maxY=Math.max(maxY,q.y);maxZ=Math.max(maxZ,q.z);}
        final float a=minX,b=minY,c=minZ,d=maxX,e=maxY,f=maxZ;post(()->fitSpatialBounds(a,b,c,d,e,f));
    }

    public String sketchOnSelectedFace() {
        if (selectedBody == null || selectedFace == null) return "در نمای 3D اول یک Face را لمس کن";
        if (selectedFace.vertices.size() < 3) return "Face معتبر نیست";
        Geometry3D.Vec3 origin = selectedFace.vertices.get(0).pos;
        Geometry3D.Vec3 u = null;
        for(int i=1;i<selectedFace.vertices.size();i++){
            Geometry3D.Vec3 edge=selectedFace.vertices.get(i).pos.sub(origin);
            if(edge.length()>1e-4f){u=edge.normalized();break;}
        }
        if(u==null)return "جهت Face پیدا نشد";
        Geometry3D.Vec3 n=selectedFace.plane.normal.normalized();
        Geometry3D.Vec3 v=n.cross(u).normalized();
        Geometry3D.Plane3D facePlane=new Geometry3D.Plane3D(origin,u,v,"Face • "+selectedBody.name);
        try {
            if(pendingPlaneField!=null)pendingPlaneField.set(this,facePlane);
            String result=createSketchSpace("Sketch on "+selectedBody.name);
            setOverview(false);
            selectedFace=null;
            invalidate();
            return result;
        } catch(Exception e){return "ساخت Sketch روی Face انجام نشد";}
    }

    public String deleteSelectedBody() {
        if(selectedBody==null)return "اول یک Body را انتخاب کن";
        saveSolidUndo();
        bodies.remove(selectedBody);
        selectedBody=null;selectedFace=null;invalidate();
        return "Body حذف شد";
    }

    public String undoSolid() {
        if(solidUndo.isEmpty())return "Undo سه‌بعدی خالی است";
        bodies.clear();
        List<SolidBody> snap=solidUndo.removeLast();
        for(SolidBody b:snap)bodies.add(b.copy());
        selectedBody=null;selectedFace=null;invalidate();
        return "Undo Solid";
    }

    public int bodyCount(){return bodies.size();}

    /** Stable, reflection-free bridge used by the production Items panel. */
    public String[] itemRows(){
        String[] rows=new String[bodies.size()];
        for(int i=0;i<bodies.size();i++){
            SolidBody b=bodies.get(i);
            rows[i]=(b.visible?"◉ ":"○ ")+(b==selectedBody?"● ":"")+b.name+"  •  "+b.csg.polygons().size()+" faces";
        }
        return rows;
    }

    public String selectItem(int index){
        if(index<0||index>=bodies.size())return "Body پیدا نشد";
        selectedBody=bodies.get(index);selectedFace=null;clearSubSelection();setOverview(true);invalidate();
        return selectedBody.name+" انتخاب شد";
    }

    public String toggleItemVisibility(int index){
        if(index<0||index>=bodies.size())return "Body پیدا نشد";
        SolidBody b=bodies.get(index);b.visible=!b.visible;
        if(!b.visible&&selectedBody==b){selectedBody=null;selectedFace=null;}
        invalidate();return b.name+(b.visible?" نمایش داده شد":" مخفی شد");
    }

    public String renameItem(int index,String newName){
        if(index<0||index>=bodies.size())return "Body پیدا نشد";
        String clean=newName==null?"":newName.trim();
        if(clean.isEmpty())return "نام نمی‌تواند خالی باشد";
        bodies.get(index).name=clean;invalidate();return "نام به «"+clean+"» تغییر کرد";
    }

    @Override
    public void fitAll() {
        if (!is3DOverview() || bodies.isEmpty()) { super.fitAll(); return; }
        float minX=Float.POSITIVE_INFINITY,minY=Float.POSITIVE_INFINITY,minZ=Float.POSITIVE_INFINITY;
        float maxX=Float.NEGATIVE_INFINITY,maxY=Float.NEGATIVE_INFINITY,maxZ=Float.NEGATIVE_INFINITY;
        boolean found=false;
        for(SolidBody body:bodies)if(body.visible&&body.csg!=null)for(SolidCSG.Polygon polygon:body.csg.polygons())
            for(SolidCSG.Vertex vertex:polygon.vertices){Geometry3D.Vec3 p=vertex.pos;found=true;
                minX=Math.min(minX,p.x);minY=Math.min(minY,p.y);minZ=Math.min(minZ,p.z);
                maxX=Math.max(maxX,p.x);maxY=Math.max(maxY,p.y);maxZ=Math.max(maxZ,p.z);}
        if(found)fitSpatialBounds(minX,minY,minZ,maxX,maxY,maxZ);else super.fitAll();
    }

    // ------------------------------------------------------------------
    // Commands used by the existing Shapr-like 3D menu
    // ------------------------------------------------------------------

    @Override
    public String executeCommand(String raw) {
        if(raw==null)return"";
        String s=normalizeDigits(raw).trim().replace(',',' ');
        if(s.isEmpty())return"";
        String[] a=s.split("\\s+");
        String cmd=a[0].toUpperCase(Locale.US);
        try{
            if("P".equals(cmd)||"PUSHPULL".equals(cmd)||"EXTRUDE".equals(cmd)){
                if(a.length<2)return"ارتفاع Extrude را به mm وارد کن";
                return extrudeSelectedBody(Float.parseFloat(a[1])/10f);
            }
            if("UNION".equals(cmd)||"SUBTRACT".equals(cmd)||"INTERSECT".equals(cmd)){
                startBoolean(cmd);
                return cmd+" — Body دوم را انتخاب کن";
            }
            if("PROJECT".equals(cmd))return sketchOnSelectedFace();
            if("BODIES".equals(cmd)){showBodiesDialog();return"Bodies";}
            if("SOLID".equals(cmd)){showSolidManager();return"Solid 3D";}
        }catch(Exception e){return"فرمت فرمان سه‌بعدی درست نیست";}
        return super.executeCommand(raw);
    }

    @Override
    public String selectedInfo() {
        if(is3DOverview()&&selectedBody!=null){
            int faces=selectedBody.csg.polygons().size();
            return selectedBody.name+" | "+faces+" Face | "+selectionKind();
        }
        return super.selectedInfo();
    }

    public String selectionKind(){
        if(selectedVertex!=null)return "VERTEX";
        if(selectedEdgeA!=null&&selectedEdgeB!=null)return "EDGE";
        if(selectedFace!=null)return "FACE";
        if(selectedBody!=null)return "BODY";
        return "SKETCH";
    }

    /** Exact selection state for the production workspace; no message parsing. */
    public boolean hasWorkspaceSelection(){
        if(is3DOverview())return selectedBody!=null||selectedFace!=null||selectedVertex!=null
                ||(selectedEdgeA!=null&&selectedEdgeB!=null);
        return !selectionObjects().isEmpty();
    }

    /** Clears either a sketch selection or any 3D sub-selection in one action. */
    public void clearWorkspaceSelection(){
        clearSmartSelection();
        selectedBody=null;selectedFace=null;selectedEdgeA=null;selectedEdgeB=null;selectedVertex=null;
        invalidate();dispatchWorkspaceState();
    }

    private void clearSubSelection(){selectedEdgeA=null;selectedEdgeB=null;selectedVertex=null;}

    // ------------------------------------------------------------------
    // UI dialogs
    // ------------------------------------------------------------------

    private void showExtrudeDialog() {
        EditText input=new EditText(getContext());
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL|InputType.TYPE_NUMBER_FLAG_SIGNED);
        input.setText("20");input.setSelectAllOnFocus(true);
        new AlertDialog.Builder(getContext())
                .setTitle("Extrude — ارتفاع mm")
                .setMessage("سطح بسته را انتخاب کن. عدد منفی Extrude را به سمت دیگر Plane می‌برد.")
                .setView(input)
                .setPositiveButton("ساخت Body",(d,w)->{
                    try{toast(extrudeSelectedBody(Float.parseFloat(normalizeDigits(input.getText().toString().trim()))/10f));}
                    catch(Exception e){toast("ارتفاع درست وارد نشده");}
                })
                .setNegativeButton("لغو",null).show();
    }

    private void showBodiesDialog() {
        if(bodies.isEmpty()){toast("هنوز Body ساخته نشده");return;}
        String[] names=new String[bodies.size()];
        for(int i=0;i<bodies.size();i++){
            SolidBody b=bodies.get(i);
            names[i]=(b==selectedBody?"● ":"○ ")+b.name+"  •  "+b.csg.polygons().size()+" faces"+(b.visible?"":"  مخفی");
        }
        new AlertDialog.Builder(getContext())
                .setTitle("Bodies")
                .setItems(names,(d,w)->{selectedBody=bodies.get(w);selectedFace=null;setOverview(true);invalidate();toast(selectedBody.name+" انتخاب شد");})
                .setNegativeButton("بستن",null).show();
    }

    private void startBoolean(String op) {
        if(bodies.size()<2){toast("برای Boolean حداقل دو Body لازم است");return;}
        if(selectedBody==null){
            String[] names=bodyNames(null);
            new AlertDialog.Builder(getContext()).setTitle("Body اصلی را انتخاب کن")
                    .setItems(names,(d,w)->{selectedBody=bodies.get(w);chooseBooleanTool(op);}).setNegativeButton("لغو",null).show();
            return;
        }
        chooseBooleanTool(op);
    }

    private void chooseBooleanTool(String op) {
        List<SolidBody> options=new ArrayList<>();
        for(SolidBody b:bodies)if(b!=selectedBody)options.add(b);
        if(options.isEmpty()){toast("Body دوم وجود ندارد");return;}
        String[] names=new String[options.size()];
        for(int i=0;i<options.size();i++)names[i]=options.get(i).name;
        new AlertDialog.Builder(getContext())
                .setTitle(op+" — Body دوم")
                .setMessage("Body اصلی: "+selectedBody.name)
                .setItems(names,(d,w)->toast(applyBoolean(op,selectedBody,options.get(w))))
                .setNegativeButton("لغو",null).show();
    }

    private String[] bodyNames(SolidBody exclude) {
        List<String> n=new ArrayList<>();for(SolidBody b:bodies)if(b!=exclude)n.add(b.name);return n.toArray(new String[0]);
    }

    private String applyBoolean(String op,SolidBody a,SolidBody b) {
        if(a==null||b==null||a==b)return"دو Body متفاوت لازم است";
        saveSolidUndo();
        SolidCSG result;
        if("UNION".equals(op))result=a.csg.union(b.csg);
        else if("SUBTRACT".equals(op))result=a.csg.subtract(b.csg);
        else result=a.csg.intersect(b.csg);
        if(result.isEmpty()){
            if(!solidUndo.isEmpty())solidUndo.removeLast();
            return "نتیجه "+op+" خالی شد؛ Bodyها تغییر نکردند";
        }
        bodies.remove(a);bodies.remove(b);
        String label="UNION".equals(op)?"Union":"SUBTRACT".equals(op)?"Subtract":"Intersect";
        SolidBody out=new SolidBody(bodySerial,label+" "+bodySerial,result);bodySerial++;
        bodies.add(out);selectedBody=out;selectedFace=null;setOverview(true);invalidate();
        post(this::fitAll);
        return label+" انجام شد | "+result.polygons().size()+" Face";
    }

    // ------------------------------------------------------------------
    // Drawing and face picking
    // ------------------------------------------------------------------

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if(is3DOverview()){
            if(gpuBodyRendering)drawGpuSelection(canvas);else drawSolidBodies(canvas);
            drawFormPreview(canvas);
            drawExtrudePreview(canvas);
        }
    }

    public void setGpuBodyRendering(boolean enabled){gpuBodyRendering=enabled;invalidate();}

    private void drawGpuSelection(Canvas canvas){
        RectF card=overviewCard();if(card==null||card.isEmpty())return;
        canvas.save();canvas.clipRect(card);
        if(selectedFace!=null){
            List<PointF> points=new ArrayList<>();for(SolidCSG.Vertex vertex:selectedFace.vertices)points.add(project(vertex.pos));
            Path selectedPath=path(points);if(selectedPath!=null){canvas.drawPath(selectedPath,faceFill);canvas.drawPath(selectedPath,selectedWire);}
        }
        canvas.restore();
        drawTopologySelection(canvas);
        if(selectedBody!=null)canvas.drawText(selectedBody.name+(selectedFace!=null?" • Face انتخاب شد":" • Body انتخاب شد"),card.centerX(),card.top+58f,bodyText);
    }

    private void drawExtrudePreview(Canvas canvas){
        if(extrudePreview==null||extrudePreview.isEmpty())return;
        RectF card=overviewCard();if(card==null||card.isEmpty())return;
        canvas.save();canvas.clipRect(card);
        for(SolidCSG.Polygon polygon:extrudePreview.polygons()){
            List<PointF> points=new ArrayList<>();for(SolidCSG.Vertex vertex:polygon.vertices)points.add(project(vertex.pos));
            Path shape=path(points);if(shape!=null){canvas.drawPath(shape,extrudePreviewFill);canvas.drawPath(shape,extrudePreviewWire);}
        }
        canvas.restore();
        drawExtrudeManipulator(canvas);
    }

    private void drawFormPreview(Canvas canvas){
        if(formPreview==null||formPreview.isEmpty())return;
        RectF card=overviewCard();if(card==null||card.isEmpty())return;
        canvas.save();canvas.clipRect(card);
        for(SolidCSG.Polygon polygon:formPreview.polygons()){
            List<PointF> points=new ArrayList<>();for(SolidCSG.Vertex vertex:polygon.vertices)points.add(project(vertex.pos));
            Path shape=path(points);if(shape!=null){canvas.drawPath(shape,extrudePreviewFill);canvas.drawPath(shape,extrudePreviewWire);}
        }
        canvas.restore();
        if(!formPreviewLabel.isEmpty())canvas.drawText(formPreviewLabel,card.centerX(),card.bottom-52f,bodyText);
    }

    private void drawExtrudeManipulator(Canvas canvas){
        if(!extrudeSessionActive||extrudeSessionOrigin==null||extrudeSessionPlane==null)return;
        Geometry3D.Vec3 end=extrudeSessionOrigin.add(extrudeSessionPlane.normal.mul(extrudePreviewHeightMm));
        PointF base=project(extrudeSessionOrigin),tip=project(end);extrudeBaseScreen=base;extrudeTipScreen=tip;
        float dx=tip.x-base.x,dy=tip.y-base.y,len=(float)Math.hypot(dx,dy);
        if(len<2f){dx=0f;dy=-1f;len=1f;}else{dx/=len;dy/=len;}
        canvas.drawLine(base.x,base.y,tip.x,tip.y,extrudeHandle);
        float wing=13f*getResources().getDisplayMetrics().density;
        canvas.drawLine(tip.x,tip.y,tip.x-dx*wing-dy*wing*.55f,tip.y-dy*wing+dx*wing*.55f,extrudeHandle);
        canvas.drawLine(tip.x,tip.y,tip.x-dx*wing+dy*wing*.55f,tip.y-dy*wing-dx*wing*.55f,extrudeHandle);
        float r=9f*getResources().getDisplayMetrics().density;
        canvas.drawCircle(tip.x,tip.y,r,extrudeHandleFill);canvas.drawCircle(tip.x,tip.y,r,extrudeHandle);
        String text=fmt(extrudePreviewHeightMm)+" mm";float tw=bodyText.measureText(text);
        float left=tip.x-tw*.5f-12f,top=tip.y-r-43f,right=tip.x+tw*.5f+12f,bottom=top+32f;
        Paint chip=new Paint(Paint.ANTI_ALIAS_FLAG);chip.setColor(Color.argb(238,255,255,255));canvas.drawRoundRect(left,top,right,bottom,12f,12f,chip);
        canvas.drawText(text,tip.x,bottom-8f,bodyText);
    }

    private void drawSolidBodies(Canvas canvas) {
        RectF card=overviewCard();if(card==null||card.isEmpty())return;
        List<FaceRender> render=new ArrayList<>();
        for(SolidBody body:bodies){
            if(!body.visible)continue;
            for(SolidCSG.Polygon p:body.csg.polygons()){
                List<PointF> screen=new ArrayList<>();
                float depth=0f;
                for(SolidCSG.Vertex v:p.vertices){screen.add(project(v.pos));depth+=cameraDepth(v.pos);}
                if(!screen.isEmpty())depth/=screen.size();
                render.add(new FaceRender(body,p,depth,screen));
            }
        }
        Collections.sort(render, Comparator.comparingDouble(r->r.depth));
        canvas.save();canvas.clipRect(card);
        for(FaceRender r:render){
            Path path=path(r.screen);if(path==null)continue;
            if(r.polygon==selectedFace&&r.body==selectedBody)canvas.drawPath(path,faceFill);else canvas.drawPath(path,bodyFill);
            canvas.drawPath(path,r.body==selectedBody?selectedWire:bodyWire);
        }
        canvas.restore();
        if(selectedBody!=null){
            String t=selectedBody.name+(selectedFace!=null?" • Face انتخاب شد — Solid > Sketch on Face":" • برای Face روی سطح بزن");
            canvas.drawText(t,card.centerX(),card.top+58f,bodyText);
        }
        drawTopologySelection(canvas);
    }

    private void drawTopologySelection(Canvas canvas){
        Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);p.setColor(Color.rgb(255,145,25));p.setStrokeWidth(6f);p.setStrokeCap(Paint.Cap.ROUND);
        if(selectedEdgeA!=null&&selectedEdgeB!=null){PointF a=project(selectedEdgeA),b=project(selectedEdgeB);canvas.drawLine(a.x,a.y,b.x,b.y,p);}
        if(selectedVertex!=null){PointF q=project(selectedVertex);p.setStyle(Paint.Style.FILL);canvas.drawCircle(q.x,q.y,9f*getResources().getDisplayMetrics().density,p);}
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if(extrudeSessionActive&&is3DOverview()&&event.getPointerCount()==1&&handleExtrudeTouch(event))return true;
        if(is3DOverview()&&event.getPointerCount()==1){
            RectF card=overviewCard();float x=event.getX(),y=event.getY();int a=event.getActionMasked();
            int pointer=event.getActionIndex()<event.getPointerCount()?event.getActionIndex():0;
            int pointerType=event.getToolType(pointer);
            boolean pen=pointerType==MotionEvent.TOOL_TYPE_STYLUS||pointerType==MotionEvent.TOOL_TYPE_ERASER;
            if(a==MotionEvent.ACTION_DOWN&&card!=null&&card.contains(x,y)){
                solidGesture=true;solidOrbit=false;solidGesturePen=pen;solidDownX=x;solidDownY=y;solidLastX=x;solidLastY=y;return true;
            }
            if(solidGesture){
                if(a==MotionEvent.ACTION_MOVE){
                    // Pen is the precision selector/manipulator; a finger owns
                    // orbit. This keeps a slightly moving S Pen tap from
                    // unexpectedly rotating the entire workspace.
                    if(solidGesturePen)return true;
                    float move=(float)Math.hypot(x-solidDownX,y-solidDownY);
                    if(move>8f)solidOrbit=true;
                    if(solidOrbit){orbitBy(x-solidLastX,y-solidLastY);solidLastX=x;solidLastY=y;invalidate();}
                    return true;
                }
                if(a==MotionEvent.ACTION_UP){
                    if(!solidOrbit)pickFace(x,y);
                    solidGesture=false;solidOrbit=false;solidGesturePen=false;return true;
                }
                if(a==MotionEvent.ACTION_CANCEL){solidGesture=false;solidOrbit=false;solidGesturePen=false;return true;}
                return true;
            }
        }
        return super.onTouchEvent(event);
    }

    private boolean handleExtrudeTouch(MotionEvent event){
        int action=event.getActionMasked();float x=event.getX(),y=event.getY();
        if(action==MotionEvent.ACTION_DOWN){
            if(extrudeBaseScreen==null||extrudeTipScreen==null)return false;
            float hit=distanceToSegment(x,y,extrudeBaseScreen.x,extrudeBaseScreen.y,extrudeTipScreen.x,extrudeTipScreen.y);
            float tipHit=(float)Math.hypot(x-extrudeTipScreen.x,y-extrudeTipScreen.y);
            float threshold=28f*getResources().getDisplayMetrics().density;
            if(Math.min(hit,tipHit)>threshold)return false;
            float vx=extrudeTipScreen.x-extrudeBaseScreen.x,vy=extrudeTipScreen.y-extrudeBaseScreen.y;
            float length=Math.max(1f,(float)Math.hypot(vx,vy));
            extrudeDragUx=vx/length;extrudeDragUy=vy/length;
            extrudePixelsPerMm=Math.max(.25f,length/Math.max(.1f,Math.abs(extrudePreviewHeightMm)));
            extrudeDragging=true;extrudeDragMoved=false;extrudeDragStartX=x;extrudeDragStartY=y;
            extrudeDragStartHeightMm=extrudePreviewHeightMm;return true;
        }
        if(!extrudeDragging)return false;
        if(action==MotionEvent.ACTION_MOVE){
            float dx=x-extrudeDragStartX,dy=y-extrudeDragStartY;
            if(dx*dx+dy*dy>16f)extrudeDragMoved=true;
            float along=dx*extrudeDragUx+dy*extrudeDragUy;
            float sign=extrudeDragStartHeightMm<0f?-1f:1f;
            float magnitude=Math.max(.1f,Math.abs(extrudeDragStartHeightMm)+along/extrudePixelsPerMm);
            extrudePreviewHeightMm=sign*magnitude;rebuildExtrudePreview(false);return true;
        }
        if(action==MotionEvent.ACTION_UP){
            boolean edit=!extrudeDragMoved;extrudeDragging=false;
            if(edit)promptInteractiveExtrudeValue();return true;
        }
        if(action==MotionEvent.ACTION_CANCEL){extrudeDragging=false;return true;}
        return true;
    }

    private void pickFace(float sx,float sy) {
        SolidBody hitBody=null;SolidCSG.Polygon hitFace=null;float bestDepth=-Float.MAX_VALUE;
        Geometry3D.Vec3 hitVertex=null,hitEdgeA=null,hitEdgeB=null;
        SolidBody vertexBody=null,edgeBody=null;
        float bestVertex=Float.MAX_VALUE,bestEdge=Float.MAX_VALUE;
        float density=getResources().getDisplayMetrics().density;
        for(SolidBody body:bodies){
            if(!body.visible)continue;
            for(SolidCSG.Polygon p:body.csg.polygons()){
                List<PointF> q=new ArrayList<>();float depth=0;
                for(int i=0;i<p.vertices.size();i++){
                    Geometry3D.Vec3 va=p.vertices.get(i).pos,vb=p.vertices.get((i+1)%p.vertices.size()).pos;
                    PointF a=project(va),b=project(vb);q.add(a);depth+=cameraDepth(va);
                    float vd=(float)Math.hypot(sx-a.x,sy-a.y);if(vd<bestVertex){bestVertex=vd;hitVertex=va;vertexBody=body;}
                    float ed=distanceToSegment(sx,sy,a.x,a.y,b.x,b.y);if(ed<bestEdge){bestEdge=ed;hitEdgeA=va;hitEdgeB=vb;edgeBody=body;}
                }
                if(q.size()<3||!pointInPolygon(sx,sy,q))continue;
                depth/=q.size();
                if(depth>bestDepth){bestDepth=depth;hitBody=body;hitFace=p;}
            }
        }
        selectedBody=hitBody;selectedFace=null;clearSubSelection();
        if(hitVertex!=null&&bestVertex<=14f*density){selectedBody=vertexBody;selectedVertex=hitVertex;}
        else if(hitEdgeA!=null&&bestEdge<=12f*density){selectedBody=edgeBody;selectedEdgeA=hitEdgeA;selectedEdgeB=hitEdgeB;}
        else selectedFace=hitFace;
        Geometry3D.Vec3 anchor=selectedVertex!=null?selectedVertex
                :selectedEdgeA!=null?selectedEdgeA.add(selectedEdgeB).mul(.5f)
                :selectedFace!=null?selectedFace.centroid():null;
        onTopologyPicked(selectedBody,selectionKind(),anchor,selectedEdgeA,selectedEdgeB);
        invalidate();dispatchWorkspaceState();
        if(hitBody!=null)toast(hitBody.name+" • "+selectionKind()+" انتخاب شد");
    }

    /** Exact-kernel layers override this to bind the visual hit to B-Rep topology. */
    protected void onTopologyPicked(Object body,String kind,Geometry3D.Vec3 anchor,
                                    Geometry3D.Vec3 edgeStart,Geometry3D.Vec3 edgeEnd){}

    private static float distanceToSegment(float px,float py,float ax,float ay,float bx,float by){
        float dx=bx-ax,dy=by-ay,l2=dx*dx+dy*dy;if(l2<1e-8f)return(float)Math.hypot(px-ax,py-ay);
        float t=Math.max(0f,Math.min(1f,((px-ax)*dx+(py-ay)*dy)/l2));
        return(float)Math.hypot(px-(ax+t*dx),py-(ay+t*dy));
    }

    private static Path path(List<PointF> p) {
        if(p==null||p.size()<3)return null;Path x=new Path();x.moveTo(p.get(0).x,p.get(0).y);for(int i=1;i<p.size();i++)x.lineTo(p.get(i).x,p.get(i).y);x.close();return x;
    }

    private static boolean pointInPolygon(float x,float y,List<PointF> p) {
        boolean inside=false;for(int i=0,j=p.size()-1;i<p.size();j=i++){
            PointF a=p.get(i),b=p.get(j);boolean hit=((a.y>y)!=(b.y>y))&&(x<(b.x-a.x)*(y-a.y)/(b.y-a.y+1e-12f)+a.x);if(hit)inside=!inside;
        }return inside;
    }

    // ------------------------------------------------------------------
    // Sketch profile extraction
    // ------------------------------------------------------------------

    private ProfileData profileFromSelection() {
        List<Object> sel=selectionObjects();
        if(sel.isEmpty())return null;
        // Construction geometry is reference-only and must never define a Solid profile.
        for(Object e:sel)if(Boolean.TRUE.equals(call(e,"isConstruction")))return null;
        if(sel.size()==1){
            Object e=sel.get(0);String type=e.getClass().getSimpleName();String layer=entityLayer(e);
            if("RectEntity".equals(type)){
                PointF[] p=pointArray(e,"p");if(p==null)return null;List<PointF> out=new ArrayList<>();Collections.addAll(out,p);return new ProfileData(out,layer);
            }
            if("CircleEntity".equals(type)){
                float cx=safeGet(e,"x"),cy=safeGet(e,"y"),r=Math.abs(safeGet(e,"r"));if(r<=0)return null;List<PointF> out=new ArrayList<>();
                for(int i=0;i<CIRCLE_SEGMENTS;i++){double a=2*Math.PI*i/CIRCLE_SEGMENTS;out.add(new PointF(cx+(float)Math.cos(a)*r,cy+(float)Math.sin(a)*r));}
                return new ProfileData(out,layer);
            }
            if("PolygonEntity".equals(type)){
                List<PointF> pts=points(e);return pts.size()>=3?new ProfileData(pts,layer):null;
            }
            if("PolylineEntity".equals(type)&&booleanField(e,"closed")){
                List<PointF> pts=points(e);return pts.size()>=3?new ProfileData(pts,layer):null;
            }
            return null;
        }
        for(Object e:sel)if(!"LineEntity".equals(e.getClass().getSimpleName()))return null;
        String layer=entityLayer(sel.get(0));for(Object e:sel)if(!layer.equals(entityLayer(e)))return null;
        List<PointF> loop=stitchLines(sel);return loop==null?null:new ProfileData(loop,layer);
    }

    private List<PointF> stitchLines(List<Object> lines) {
        if(lines.size()<3)return null;
        boolean[] used=new boolean[lines.size()];
        Object first=lines.get(0);PointF a=endpoint(first,0),b=endpoint(first,1);if(a==null||b==null)return null;
        List<PointF> out=new ArrayList<>();out.add(a);out.add(b);used[0]=true;PointF current=b;
        for(int step=1;step<lines.size();step++){
            int found=-1;PointF next=null;
            for(int i=1;i<lines.size();i++)if(!used[i]){
                PointF p0=endpoint(lines.get(i),0),p1=endpoint(lines.get(i),1);if(p0==null||p1==null)continue;
                if(dist(current,p0)<=LINE_JOIN_TOL_MM){found=i;next=p1;break;}
                if(dist(current,p1)<=LINE_JOIN_TOL_MM){found=i;next=p0;break;}
            }
            if(found<0)return null;used[found]=true;out.add(next);current=next;
        }
        if(dist(current,out.get(0))>LINE_JOIN_TOL_MM)return null;
        out.remove(out.size()-1);return out.size()>=3?out:null;
    }

    // ------------------------------------------------------------------
    // Reflection helpers into the current prototype layers
    // ------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private List<Object> selectionObjects() {
        try{
            if(selectedObjectsField!=null){List<Object> multi=(List<Object>)selectedObjectsField.get(this);if(multi!=null&&!multi.isEmpty())return new ArrayList<>(multi);}
            List<Object> one=new ArrayList<>();Object e=selectedField==null?null:selectedField.get(this);if(e!=null)one.add(e);return one;
        }catch(Exception e){return new ArrayList<>();}
    }

    @SuppressWarnings("unchecked")
    private Geometry3D.Plane3D planeForLayer(String layer) {
        try{
            Map<String,Geometry3D.Plane3D> map=(Map<String,Geometry3D.Plane3D>)planeByLayerField.get(this);
            Geometry3D.Plane3D p=map.get(layer);if(p!=null)return p;
            Object a=activePlaneField.get(this);if(a instanceof Geometry3D.Plane3D)return(Geometry3D.Plane3D)a;
        }catch(Exception ignored){}
        return Geometry3D.xy();
    }

    private void setOverview(boolean value) {try{if(overview3DField!=null)overview3DField.setBoolean(this,value);}catch(Exception ignored){}invalidate();}

    private RectF overviewCard() {
        try{Object o=overviewCardField==null?null:overviewCardField.get(this);return o instanceof RectF?new RectF((RectF)o):new RectF();}
        catch(Exception e){return new RectF();}
    }

    private PointF project(Geometry3D.Vec3 p) {
        try{Object o=projectMethod.invoke(this,p);if(o instanceof PointF)return(PointF)o;}catch(Exception ignored){}
        return new PointF();
    }

    /** Projection hooks for on-canvas feature manipulators in derived tools. */
    protected final PointF projectSolidPoint(Geometry3D.Vec3 p){return project(p);}
    protected final RectF solidViewport(){return overviewCard();}

    private float cameraDepth(Geometry3D.Vec3 p) {
        try{
            float yaw=cameraYawField.getFloat(this),pitch=cameraPitchField.getFloat(this);
            double y1=p.x*Math.sin(Math.toRadians(yaw))+p.y*Math.cos(Math.toRadians(yaw));
            return(float)(y1*Math.sin(Math.toRadians(pitch))+p.z*Math.cos(Math.toRadians(pitch)));
        }catch(Exception e){return p.z;}
    }

    private void orbitBy(float dx,float dy) {
        try{
            float yaw=cameraYawField.getFloat(this)+dx*0.45f;
            float pitch=cameraPitchField.getFloat(this)+dy*0.35f;
            pitch=Math.max(-85f,Math.min(85f,pitch));
            cameraYawField.setFloat(this,yaw);cameraPitchField.setFloat(this,pitch);
        }catch(Exception ignored){}
    }

    private static String entityLayer(Object e){Object v=call(e,"getLayer");return v==null?"":String.valueOf(v);}

    private static Object call(Object target,String name) {
        if(target==null)return null;Class<?> c=target.getClass();while(c!=null){try{Method m=c.getDeclaredMethod(name);m.setAccessible(true);return m.invoke(target);}catch(NoSuchMethodException e){c=c.getSuperclass();}catch(Exception e){return null;}}return null;
    }

    private static Field findField(Class<?> c,String name){Class<?> x=c;while(x!=null){try{Field f=x.getDeclaredField(name);f.setAccessible(true);return f;}catch(Exception e){x=x.getSuperclass();}}return null;}
    private static float safeGet(Object o,String name){try{Field f=findField(o.getClass(),name);return f==null?0:f.getFloat(o);}catch(Exception e){return 0;}}
    private static boolean booleanField(Object o,String name){try{Field f=findField(o.getClass(),name);return f!=null&&f.getBoolean(o);}catch(Exception e){return false;}}
    private static PointF[] pointArray(Object o,String name){try{Field f=findField(o.getClass(),name);Object v=f==null?null:f.get(o);return v instanceof PointF[]?(PointF[])v:null;}catch(Exception e){return null;}}

    @SuppressWarnings("unchecked")
    private static List<PointF> points(Object o){try{Field f=findField(o.getClass(),"points");Object v=f==null?null:f.get(o);if(v instanceof List){List<PointF> out=new ArrayList<>();for(PointF p:(List<PointF>)v)out.add(new PointF(p.x,p.y));return out;}}catch(Exception ignored){}return new ArrayList<>();}

    private static PointF endpoint(Object line,int i){try{return i==0?new PointF(safeGet(line,"x1"),safeGet(line,"y1")):new PointF(safeGet(line,"x2"),safeGet(line,"y2"));}catch(Exception e){return null;}}
    private static float dist(PointF a,PointF b){return(float)Math.hypot(a.x-b.x,a.y-b.y);}

    private void saveSolidUndo(){List<SolidBody> snap=new ArrayList<>();for(SolidBody b:bodies)snap.add(b.copy());solidUndo.addLast(snap);while(solidUndo.size()>30)solidUndo.removeFirst();}

    @Override
    public void clearAll(){super.clearAll();bodies.clear();solidUndo.clear();selectedBody=null;selectedFace=null;bodySerial=1;invalidate();}

    private static String fmt(float v){String s=String.format(Locale.US,"%.2f",v);while(s.contains(".")&&(s.endsWith("0")||s.endsWith(".")))s=s.substring(0,s.length()-1);return s;}
    private static String normalizeDigits(String s){if(s==null)return"";StringBuilder b=new StringBuilder();for(int i=0;i<s.length();i++){char c=s.charAt(i);if(c>='۰'&&c<='۹')b.append((char)('0'+c-'۰'));else if(c>='٠'&&c<='٩')b.append((char)('0'+c-'٠'));else b.append(c);}return b.toString();}
    private void toast(String s){if(s!=null&&!s.trim().isEmpty())Toast.makeText(getContext(),s,Toast.LENGTH_SHORT).show();}
}

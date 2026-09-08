package ir.chobyar.sketch;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PointF;
import android.graphics.RectF;
import android.text.InputType;
import android.view.MotionEvent;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import ir.chobyar.sketch.core.ConstructionPlane;
import ir.chobyar.sketch.core.ConstructionPlaneDocument;

/**
 * Spatial sketch foundation.
 *
 * Every sketch owns a real 3D plane (origin + U/V axes + normal). Existing 2D
 * geometry remains local to that plane, which is exactly the representation a
 * future native B-Rep solid kernel needs. A spatial overview renders sketches
 * and current extrusions in model XYZ so plane placement can already be tested.
 *
 * This is deliberately a bridge: the preview is spatial 3D, while Boolean B-Rep
 * solids will be provided by the native solid kernel in the next architecture
 * layer rather than faked here.
 */
public class SpatialCadCanvasView extends EasyCadCanvasView {
    private static final String REFERENCE_IMAGE_ITEM_ID = "reference-image:1";

    private Field entitiesField;

    private boolean overview3D = false;
    /*
     * Model-space convention: FRONT looks along +Y toward the XZ plane.  The
     * ISO preset must therefore remain on that same side of the model.  The
     * previous 38 degree yaw placed furniture fronts on the far side and made
     * tests (and users) repair the camera through reflection/manual orbiting.
     */
    private float cameraYaw = 218f;
    private float cameraPitch = 24f;
    private float spatialScale = 1.25f;
    private float cameraTargetX = 0f, cameraTargetY = 0f, cameraTargetZ = 0f;
    private float cameraPanX = 0f, cameraPanY = 0f;
    private boolean navigating2D = false;
    private float navLastDistance, navLastMidX, navLastMidY;
    private boolean orbiting = false;
    private float orbitLastX, orbitLastY;
    private final RectF overviewCard = new RectF();

    private final Paint spatialPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint planePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint axisX = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint axisY = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint axisZ = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint spatialText = new Paint(Paint.ANTI_ALIAS_FLAG);

    private static final class ReferenceImage {
        final Bitmap bitmap;final String name;final Geometry3D.Plane3D plane;
        float widthMm=100f,centerU,centerV,rotationDeg,opacity=.55f;boolean visible=true;
        ReferenceImage(Bitmap bitmap,String name,Geometry3D.Plane3D plane){this.bitmap=bitmap;this.name=name;this.plane=plane;}
        float heightMm(){return bitmap.getWidth()==0?widthMm:widthMm*bitmap.getHeight()/bitmap.getWidth();}
    }
    private ReferenceImage referenceImage;
    private final Paint referenceImagePaint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
    private final ProfessionalSketchPlacementController sketchPlacementController;
    private GpuCameraState modelCameraBeforeSketch;

    public SpatialCadCanvasView(Context context) {
        super(context);
        sketchPlacementController = new ProfessionalSketchPlacementController(this);
        initSpatialReflection();
        initSpatialPaints();
    }

    private void initSpatialReflection() {
        try {
            entitiesField = CadCanvasView.class.getDeclaredField("entities");
            entitiesField.setAccessible(true);
        } catch (Exception ignored) {}
    }

    private void initSpatialPaints() {
        spatialPaint.setColor(Color.rgb(32, 67, 112));
        spatialPaint.setStyle(Paint.Style.STROKE);
        spatialPaint.setStrokeWidth(2.4f);
        spatialPaint.setStrokeCap(Paint.Cap.ROUND);
        spatialPaint.setStrokeJoin(Paint.Join.ROUND);

        planePaint.setColor(Color.argb(135, 95, 135, 205));
        planePaint.setStyle(Paint.Style.STROKE);
        planePaint.setStrokeWidth(1.4f);

        axisX.setColor(Color.rgb(205, 65, 65)); axisX.setStrokeWidth(3f);
        axisY.setColor(Color.rgb(50, 155, 85)); axisY.setStrokeWidth(3f);
        axisZ.setColor(Color.rgb(55, 105, 215)); axisZ.setStrokeWidth(3f);

        spatialText.setColor(Color.rgb(45, 58, 78));
        spatialText.setTextSize(22f);
        spatialText.setTextAlign(Paint.Align.CENTER);
    }

    // ------------------------------------------------------------------
    // Sketch plane ownership
    // ------------------------------------------------------------------

    @Override
    public String createSketchSpace(String requestedName) {
        String result = super.createSketchSpace(requestedName);
        invalidate();
        return result + " | " + activePlaneLabel();
    }

    @Override
    public String switchSketchSpace(int index) {
        String result = super.switchSketchSpace(index);
        invalidate();
        return result + " | " + activePlaneLabel();
    }

    public String activePlaneLabel() {
        return planePresentationLabel(constructionPlaneDocument().activePlane());
    }

    /** Non-modal plane assignment used when seeding editable bundled projects. */
    final void applyProjectSketchPlane(Geometry3D.Plane3D plane) {
        applyProjectSketchPlane(getCurrentLayer(), plane);
    }

    final void applyProjectSketchPlane(String layer, Geometry3D.Plane3D plane) {
        if (plane == null) return;
        String key=layer==null||layer.trim().isEmpty()?getCurrentLayer():layer.trim();
        String sketchId=sketchStableIdForLayer(key);if(sketchId==null)sketchId=legacySketchIdForLayer(key);
        bindGeometryToSketch(sketchId,plane);
        invalidate();
    }

    /** Stable subclass-facing plane lookup for associative Sketch references. */
    protected final Geometry3D.Plane3D spatialPlaneForLayer(String layer){
        String sketchId=sketchStableIdForLayer(layer);if(sketchId==null)sketchId=legacySketchIdForLayer(layer);
        String planeId=constructionPlaneDocument().planeIdForSketch(sketchId);
        if(planeId==null)throw new IllegalStateException("Sketch plane relationship is missing");
        return geometry(constructionPlaneDocument().plane(planeId));
    }

    protected final Geometry3D.Plane3D activeSpatialPlane(){return geometry(constructionPlaneDocument().activePlane());}

    public final String activeConstructionPlaneId(){return constructionPlaneDocument().activePlaneId();}
    public final long constructionPlaneModelRevision(){return constructionPlaneDocument().revision();}
    public final boolean hasConstructionPlane(String id){return constructionPlaneDocument().containsPlane(id);}
    public final double constructionPlaneOffsetMm(String id){ConstructionPlane p=constructionPlaneDocument().plane(id);if(p==null)throw new IllegalArgumentException("Plane is missing");return p.offsetDistanceMm;}
    public final boolean setConstructionPlaneVisibility(String id,boolean visible){boolean changed=constructionPlaneDocument().setPlaneVisibility(id,visible);if(changed)invalidate();return changed;}
    public final boolean isConstructionPlaneVisible(String id){ConstructionPlane p=constructionPlaneDocument().plane(id);return p!=null&&p.visible;}
    public final boolean canUndoConstructionPlaneTransaction(){return constructionPlaneDocument().canUndo();}
    public final boolean canRedoConstructionPlaneTransaction(){return constructionPlaneDocument().canRedo();}
    public final boolean undoConstructionPlaneTransaction(){Map<String,String> target=constructionPlaneDocument().undoSketchPlaneAssignments();if(!canAdoptSketchPlaneAssignments(target))return false;
        boolean changed=constructionPlaneDocument().undo();if(changed){restoreSketchSpacesFromPlaneModel();invalidate();dispatchWorkspaceState();}return changed;}
    public final boolean redoConstructionPlaneTransaction(){Map<String,String> target=constructionPlaneDocument().redoSketchPlaneAssignments();if(!canAdoptSketchPlaneAssignments(target))return false;
        boolean changed=constructionPlaneDocument().redo();if(changed){restoreSketchSpacesFromPlaneModel();invalidate();dispatchWorkspaceState();}return changed;}
    public final boolean hasProjectConstructionPlanes(){return !constructionPlaneDocument().isDefaultProjectState("sketch:1");}
    public final String exportConstructionPlaneModel(){return ExactModelProjectAdapter.exportModel((Shapr3DGuideCadCanvasView)this);}
    public final void importConstructionPlaneModel(String raw){resetTransientSketchPlacement();ExactModelProjectAdapter.restoreModel((Shapr3DGuideCadCanvasView)this,raw,exportSketchProjectState());}

    final List<ConstructionPlane> constructionPlaneSnapshot(){return constructionPlaneDocument().planes();}
    final Map<String,String> sketchPlaneAssignmentSnapshot(){return constructionPlaneDocument().sketchPlaneAssignments();}
    final ConstructionPlaneDocument constructionPlaneModel(){return constructionPlaneDocument();}

    /** Subclasses append exact body identities without exposing kernel objects. */
    protected void appendBodyItemRefs(List<CadItemRef> out) {}

    /** Typed projection of model/document Items; labels never become identity. */
    public final CadItemRef[] projectItemRefs(){
        List<CadItemRef> out=new ArrayList<>();appendBodyItemRefs(out);appendSketchItemRefs(out);
        for(ConstructionPlane plane:constructionPlaneDocument().planes())out.add(new CadItemRef(CadItemRef.Kind.PLANE,plane.id,plane.displayName,plane.visible));
        if(referenceImage!=null)out.add(new CadItemRef(CadItemRef.Kind.REFERENCE_IMAGE,REFERENCE_IMAGE_ITEM_ID,referenceImage.name,referenceImage.visible));
        return out.toArray(new CadItemRef[0]);
    }

    public final String activateConstructionPlane(String planeId){
        ConstructionPlane plane=constructionPlaneDocument().plane(planeId);if(plane==null)return "Construction Plane was not found";
        if(constructionPlaneDocument().activatePlane(plane.id)){invalidate();dispatchWorkspaceState();}
        return "Construction Plane • "+plane.displayName;
    }

    public final boolean setConstructionPlaneItemVisibility(String planeId,boolean visible){
        boolean changed=constructionPlaneDocument().setPlaneVisibility(planeId,visible);if(changed){invalidate();dispatchWorkspaceState();}return changed;
    }

    public final boolean renameConstructionPlaneItem(String planeId,String name){
        boolean changed=constructionPlaneDocument().renamePlane(planeId,name);if(changed){invalidate();dispatchWorkspaceState();}return changed;
    }

    public final ConstructionPlaneDocument.DeleteResult deleteConstructionPlaneItem(String planeId){
        ConstructionPlaneDocument.DeleteResult result=constructionPlaneDocument().deletePlane(planeId);
        if(result==ConstructionPlaneDocument.DeleteResult.DELETED){invalidate();dispatchWorkspaceState();}
        return result;
    }

    public final String sketchPlaneId(String sketchId){return constructionPlaneDocument().planeIdForSketch(sketchId);}

    /** Enter the real transient AWAITING_TARGET state without allocating identity. */
    public final String beginSketchPlacement(){
        if(!overview3D)return "Finish the current Sketch before creating another";
        captureModelCameraForSketchSession();
        if(!sketchPlacementController.begin())return "Sketch placement is unavailable";
        invalidate();return "Sketch • Select a construction plane or planar face";
    }

    public final boolean isSketchPlacementAwaitingTarget(){
        return sketchPlacementController.state()==ProfessionalSketchPlacementController.State.AWAITING_TARGET;
    }

    public final void cancelSketchPlacement(){resetTransientSketchPlacement();invalidate();}

    public final String startSketchOnConstructionPlane(String planeId){
        return sketchPlacementController.startNewSketchOnPlane(planeId);
    }

    /** Existing Sketch Items enter the exact Sketch identity; they never clone it. */
    public final String editExistingSketch(String sketchId){
        if(!hasSketchSpace(sketchId))return "Sketch was not found";
        captureModelCameraForSketchSession();overview3D=false;orbiting=false;navigating2D=false;
        String result=switchSketchSpaceByStableId(sketchId);invalidate();post(this::fitAll);return result;
    }

    /** Face placement remains implemented by SolidCadCanvasView's proven path. */
    protected final void prepareModelCameraForSketchSession(){captureModelCameraForSketchSession();}
    protected final void completeExternalSketchPlacementTarget(){sketchPlacementController.completeExternalTarget();}

    public final String finishSketchSessionToModel(){
        setTool(TOOL_SELECT);sketchPlacementController.cancel();orbiting=false;navigating2D=false;
        GpuCameraState saved=modelCameraBeforeSketch;modelCameraBeforeSketch=null;
        if(validModelCamera(saved))restoreModelCamera(saved);else applyDeterministicModelCamera();
        overview3D=true;invalidate();dispatchWorkspaceState();return "3D View";
    }

    private void captureModelCameraForSketchSession(){
        if(overview3D&&modelCameraBeforeSketch==null){GpuCameraState candidate=gpuCameraState();if(validModelCamera(candidate))modelCameraBeforeSketch=candidate;}
    }

    private void resetTransientSketchPlacement(){sketchPlacementController.cancel();modelCameraBeforeSketch=null;}
    private static boolean validModelCamera(GpuCameraState state){
        return state!=null&&state.visible&&Float.isFinite(state.yaw)&&Float.isFinite(state.pitch)&&Float.isFinite(state.scale)&&state.scale>0f
                &&Float.isFinite(state.targetX)&&Float.isFinite(state.targetY)&&Float.isFinite(state.targetZ)
                &&Float.isFinite(state.panX)&&Float.isFinite(state.panY);
    }
    private void restoreModelCamera(GpuCameraState state){
        cameraYaw=state.yaw;cameraPitch=state.pitch;spatialScale=state.scale;cameraTargetX=state.targetX;cameraTargetY=state.targetY;cameraTargetZ=state.targetZ;
        cameraPanX=state.panX;cameraPanY=state.panY;
    }
    private void applyDeterministicModelCamera(){
        cameraYaw=218f;cameraPitch=24f;spatialScale=1.25f;cameraTargetX=0f;cameraTargetY=0f;cameraTargetZ=0f;cameraPanX=0f;cameraPanY=0f;
    }

    private void bindGeometryToSketch(String sketchId,Geometry3D.Plane3D plane){
        ConstructionPlane.Vector origin=vector(plane.origin),u=vector(plane.u),v=vector(plane.v);
        String baseId=matchingBuiltIn(origin,u,v);
        if(baseId!=null){constructionPlaneDocument().createSketchOnPlane(sketchId,baseId);return;}
        String parallel=matchingBuiltIn(new ConstructionPlane.Vector(0,0,0),u,v);
        if(parallel!=null){ConstructionPlane source=constructionPlaneDocument().plane(parallel);double offset=origin.dot(source.normal);ConstructionPlane.Vector tangent=origin.plus(source.normal.times(-offset));
            if(tangent.length()<=1.0e-5){constructionPlaneDocument().createOffsetPlaneWithSketch(parallel,offset,plane.label,sketchId);return;}}
        String id="plane:reference:"+sketchId.replaceAll("[^A-Za-z0-9._:-]","_");
        constructionPlaneDocument().createReferencePlaneWithSketch(id,plane.label,origin,u,v,sketchId);
    }

    protected final String createSketchOnGeometryPlane(String name,Geometry3D.Plane3D plane){
        String sketchId=nextSketchStableId();bindGeometryToSketch(sketchId,plane);
        return createSketchSpaceWithAssignedPlane(name,constructionPlaneDocument().planeIdForSketch(sketchId),true);
    }

    private static String matchingBuiltIn(ConstructionPlane.Vector origin,ConstructionPlane.Vector u,ConstructionPlane.Vector v){
        if(origin.length()>1.0e-6)return null;
        ConstructionPlane[] bases={ConstructionPlane.baseXY(),ConstructionPlane.baseXZ(),ConstructionPlane.baseYZ()};
        for(ConstructionPlane base:bases)if(aligned(u,base.uAxis)&&aligned(v,base.vAxis))return base.id;return null;
    }

    private static boolean aligned(ConstructionPlane.Vector a,ConstructionPlane.Vector b){double al=a.length(),bl=b.length();return al>1.0e-9&&Math.abs(a.dot(b)/(al*bl)-1.0)<1.0e-6;}
    private static ConstructionPlane.Vector vector(Geometry3D.Vec3 v){return new ConstructionPlane.Vector(v.x,v.y,v.z);}
    private static Geometry3D.Vec3 vector(ConstructionPlane.Vector v){return new Geometry3D.Vec3((float)v.x,(float)v.y,(float)v.z);}
    private static Geometry3D.Plane3D geometry(ConstructionPlane p){if(p==null)throw new IllegalStateException("Construction plane is missing");return new Geometry3D.Plane3D(vector(p.origin),vector(p.uAxis),vector(p.vAxis),planePresentationLabel(p));}
    private static String planePresentationLabel(ConstructionPlane p){return p.provenance==ConstructionPlane.Provenance.OFFSET?p.displayName+" • "+fmt((float)p.offsetDistanceMm)+" mm":p.displayName;}
    private static String legacySketchIdForLayer(String layer){String clean=layer==null?"":layer.trim();if("0".equals(clean))return "sketch:1";if(clean.matches("SKETCH_[1-9][0-9]*"))return "sketch:"+clean.substring(7);return "legacy-sketch:"+clean;}

    public boolean is3DOverview() { return overview3D; }

    /**
     * Enter the active sketch as a true orthographic workspace.
     *
     * Previously the Sketch button only changed the drawing tool.  When it was
     * pressed from the model view, the 2D sketch and the projected 3D scene were
     * therefore painted on top of each other.  Keeping this transition explicit
     * also gives the rest of the UI one reliable source of truth for 2D/3D mode.
     */
    public String enterActiveSketchView() {
        captureModelCameraForSketchSession();
        overview3D = false;
        orbiting = false;
        navigating2D = false;
        invalidate();
        post(this::fitAll);
        return "Sketch • " + activePlaneLabel() + " • mm";
    }

    /** Immutable camera contract shared with the Filament renderer. */
    public static final class GpuCameraState {
        public final boolean visible;
        public final float yaw,pitch,scale,targetX,targetY,targetZ,panX,panY;
        public final float left,top,right,bottom;
        GpuCameraState(boolean visible,float yaw,float pitch,float scale,float targetX,float targetY,float targetZ,
                       float panX,float panY,float left,float top,float right,float bottom){
            this.visible=visible;this.yaw=yaw;this.pitch=pitch;this.scale=scale;
            this.targetX=targetX;this.targetY=targetY;this.targetZ=targetZ;this.panX=panX;this.panY=panY;
            this.left=left;this.top=top;this.right=right;this.bottom=bottom;
        }
    }

    public GpuCameraState gpuCameraState(){
        return new GpuCameraState(overview3D,cameraYaw,cameraPitch,spatialScale,cameraTargetX,cameraTargetY,cameraTargetZ,
                cameraPanX,cameraPanY,overviewCard.left,overviewCard.top,overviewCard.right,overviewCard.bottom);
    }

    public String setStandardView(String view) {
        overview3D=true;orbiting=false;navigating2D=false;cameraPanX=0f;cameraPanY=0f;
        String key=view==null?"ISO":view.toUpperCase(java.util.Locale.US);
        if("TOP".equals(key)){cameraYaw=0f;cameraPitch=0f;}
        else if("FRONT".equals(key)){cameraYaw=0f;cameraPitch=90f;}
        else if("RIGHT".equals(key)){cameraYaw=90f;cameraPitch=90f;}
        else{cameraYaw=218f;cameraPitch=24f;key="ISO";}
        invalidate();return key;
    }

    public String toggle3DOverview() {
        overview3D = !overview3D;
        orbiting = false;
        invalidate();
        return overview3D ? "3D View" : "Sketch View";
    }

    public void showPlaneManager() {
        String[] items = constructionPlaneMenuItems();
        new AlertDialog.Builder(getContext())
                .setTitle("Construct • Plane")
                .setMessage("Active plane: " + activePlaneLabel()
                        + "\nActivate an existing base plane or create a parallel offset plane.")
                .setItems(items, (d, which) -> {
                    if (which == 0) toast(activateConstructionPlane(ConstructionPlane.XY_ID));
                    else if (which == 1) toast(activateConstructionPlane(ConstructionPlane.XZ_ID));
                    else if (which == 2) toast(activateConstructionPlane(ConstructionPlane.YZ_ID));
                    else if (which == 3) showOffsetPlaneDialog();
                })
                .setNegativeButton("Close", null)
                .show();
    }

    /** Truthful Construct capabilities: construction geometry, never Sketch creation. */
    final String[] constructionPlaneMenuItems() {
        return new String[] {
                "XY / Top",
                "XZ / Front",
                "YZ / Side",
                "＋ Offset Plane"
        };
    }

    public String createSketchSpaceOnConstructionPlane(String baseName,String planeId) {
        if(!constructionPlaneDocument().containsPlane(planeId))return "Construction Plane was not found";
        captureModelCameraForSketchSession();overview3D=false;orbiting=false;navigating2D=false;
        String result = createSketchSpaceWithAssignedPlane(baseName + " " + (constructionPlaneDocument().planes().size()+1),planeId,false);
        invalidate();post(this::fitAll);return result;
    }

    /** Construct creates construction geometry only; Sketch creation is a separate user intent. */
    public String createOffsetConstructionPlane(float offsetMm,String requestedName) {
        if(!Float.isFinite(offsetMm))throw new IllegalArgumentException("Offset Plane distance must be finite");
        ConstructionPlane plane=constructionPlaneDocument().createOffsetPlane(activeConstructionPlaneId(),offsetMm,requestedName);
        invalidate();dispatchWorkspaceState();return plane.id;
    }

    /** Deterministic non-modal parallel Sketch plane entry for commands/tests. */
    public String createOffsetSketchSpace(float offsetMm, String requestedName) {
        if (!Float.isFinite(offsetMm)) return "Offset Plane • Distance must be a finite value";
        captureModelCameraForSketchSession();
        String name = requestedName == null || requestedName.trim().isEmpty()
                ? "Offset Plane " + (constructionPlaneDocument().planes().size() + 1)
                : requestedName.trim();
        String sketchId=nextSketchStableId();
        ConstructionPlane plane=constructionPlaneDocument().createOffsetPlaneWithSketch(activeConstructionPlaneId(),offsetMm,name,sketchId);
        overview3D = false;orbiting = false;navigating2D = false;
        String result = createSketchSpaceWithAssignedPlane(name,plane.id,true)+" | "+plane.displayName;
        invalidate();
        return result;
    }

    private void showOffsetPlaneDialog() {
        EditText input = new EditText(getContext());
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL | InputType.TYPE_NUMBER_FLAG_SIGNED);
        input.setHint("Example: 12.5");
        input.setText("10");
        input.setSelectAllOnFocus(true);
        new AlertDialog.Builder(getContext())
                .setTitle("Offset Plane • Distance")
                .setMessage("Create a plane parallel to the active plane. Positive and negative distances follow the plane normal.")
                .setView(input)
                .setPositiveButton("Create", (d,w) -> {
                    try {
                        float mm = Float.parseFloat(normalizeDigits(input.getText().toString().trim()));
                        if (!Float.isFinite(mm)) throw new IllegalArgumentException("Distance must be finite");
                        String planeId=createOffsetConstructionPlane(mm,null);
                        toast(constructionPlaneDocument().plane(planeId).displayName+" created");
                    } catch (Exception e) { toast("Distance was entered incorrectly"); }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ------------------------------------------------------------------
    // Spatial overview / orbit
    // ------------------------------------------------------------------

    @Override
    protected void onDraw(Canvas canvas) {
        // A model view must never contain the flat 2D canvas underneath it.
        // Android already draws this View's background before onDraw, so the
        // spatial renderer can safely own the whole frame while in 3D.
        if (overview3D) drawSpatialOverview(canvas);
        else super.onDraw(canvas);
    }

    private void drawSpatialOverview(Canvas canvas) {
        // 3D is the workspace itself, not a card layered over the 2D canvas.
        overviewCard.set(0f,0f,getWidth(),getHeight());
        canvas.save();
        drawReferenceImage(canvas);
        drawSpatialPlanes(canvas);
        drawSpatialEntities(canvas);
        drawSpatialAxes(canvas);
        canvas.restore();
    }

    public String setReferenceImage(Bitmap bitmap,String name){
        if(bitmap==null||bitmap.getWidth()<2||bitmap.getHeight()<2)return "text text text text";
        referenceImage=new ReferenceImage(bitmap,name==null?"Reference Image":name,activeSpatialPlane());
        overview3D=true;invalidate();post(this::fitAll);return "text text Roy Plane text text text • 100 mm";
    }

    public boolean hasReferenceImage(){return referenceImage!=null;}

    /** Clear project-owned auxiliary image state together with Sketch/model state. */
    @Override public void clearAll(){
        resetTransientSketchPlacement();
        referenceImage=null;
        super.clearAll();
    }

    public void showReferenceImageSettings(){
        if(referenceImage==null){toast("Start point from Add > Image, insert an image");return;}ReferenceImage image=referenceImage;
        LinearLayout box=new LinearLayout(getContext());box.setOrientation(LinearLayout.VERTICAL);int pad=(int)(16f*getResources().getDisplayMetrics().density);box.setPadding(pad,0,pad,0);
        EditText width=referenceInput(box,"Width (mm)",fmt(image.widthMm)),x=referenceInput(box,"Position U (mm)",fmt(image.centerU)),y=referenceInput(box,"Position V (mm)",fmt(image.centerV)),angle=referenceInput(box,"Rotate (deg)",fmt(image.rotationDeg));
        TextView opacityLabel=new TextView(getContext());opacityLabel.setText("Opacity • "+Math.round(image.opacity*100f)+"%");box.addView(opacityLabel);
        SeekBar opacity=new SeekBar(getContext());opacity.setMax(100);opacity.setProgress(Math.round(image.opacity*100f));box.addView(opacity);
        opacity.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onProgressChanged(SeekBar s,int value,boolean fromUser){image.opacity=Math.max(.05f,value/100f);opacityLabel.setText("Opacity • "+value+"%");invalidate();}public void onStartTrackingTouch(SeekBar s){}public void onStopTrackingTouch(SeekBar s){}});
        new AlertDialog.Builder(getContext()).setTitle(image.name+" • Reference")
                .setMessage("text text text text text text text Model text text Roy text text text.").setView(box)
                .setPositiveButton("Apply",(d,w)->{try{image.widthMm=Math.max(1f,parseLocal(width));image.centerU=parseLocal(x);image.centerV=parseLocal(y);image.rotationDeg=parseLocal(angle);invalidate();}catch(Exception e){toast("Image values ​​is invalid");}})
                .setNeutralButton(image.visible?"Hide":"Show",(d,w)->{image.visible=!image.visible;invalidate();})
                .setNegativeButton("Close",null).show();
    }

    public String removeReferenceImage(){if(referenceImage==null)return "text text text text";referenceImage=null;invalidate();return "The reference image was deleted";}

    private EditText referenceInput(LinearLayout box,String label,String value){TextView text=new TextView(getContext());text.setText(label);box.addView(text);EditText input=new EditText(getContext());input.setSingleLine(true);input.setText(value);input.setSelectAllOnFocus(true);box.addView(input);return input;}
    private static float parseLocal(EditText input){return Float.parseFloat(normalizeDigits(input.getText().toString()).replace("mm","").replace("°","").trim());}

    private void drawReferenceImage(Canvas canvas){
        ReferenceImage image=referenceImage;if(image==null||!image.visible||image.bitmap.isRecycled())return;
        float halfW=image.widthMm*.5f,halfH=image.heightMm()*.5f;double rotation=Math.toRadians(image.rotationDeg);float c=(float)Math.cos(rotation),s=(float)Math.sin(rotation);
        float[][] local={{-halfW,-halfH},{halfW,-halfH},{halfW,halfH},{-halfW,halfH}};float[] dst=new float[8];
        for(int i=0;i<4;i++){float u=image.centerU+local[i][0]*c-local[i][1]*s,v=image.centerV+local[i][0]*s+local[i][1]*c;PointF q=project(image.plane.point(u,v));dst[i*2]=q.x;dst[i*2+1]=q.y;}
        float[] src={0,0,image.bitmap.getWidth(),0,image.bitmap.getWidth(),image.bitmap.getHeight(),0,image.bitmap.getHeight()};Matrix transform=new Matrix();
        if(!transform.setPolyToPoly(src,0,dst,0,4))return;referenceImagePaint.setAlpha(Math.max(13,Math.min(255,Math.round(image.opacity*255f))));canvas.drawBitmap(image.bitmap,transform,referenceImagePaint);
    }

    private void drawSpatialPlanes(Canvas c) {
        for (ConstructionPlane modelPlane : constructionPlaneDocument().planes()) {
            if(!modelPlane.visible)continue;Geometry3D.Plane3D p=geometry(modelPlane);
            float s=55f;
            Geometry3D.Vec3 a=p.point(-s,-s), b=p.point(s,-s), d=p.point(-s,s), z=p.point(s,s);
            drawWorldLine(c,a,b,planePaint); drawWorldLine(c,b,z,planePaint);
            drawWorldLine(c,z,d,planePaint); drawWorldLine(c,d,a,planePaint);
        }
    }

    private void drawSpatialAxes(Canvas c) {
        Geometry3D.Vec3 o=new Geometry3D.Vec3(0,0,0);
        drawWorldLine(c,o,new Geometry3D.Vec3(70,0,0),axisX);
        drawWorldLine(c,o,new Geometry3D.Vec3(0,70,0),axisY);
        drawWorldLine(c,o,new Geometry3D.Vec3(0,0,70),axisZ);
        PointF x=project(new Geometry3D.Vec3(75,0,0));
        PointF y=project(new Geometry3D.Vec3(0,75,0));
        PointF z=project(new Geometry3D.Vec3(0,0,75));
        c.drawText("X",x.x,x.y,spatialText); c.drawText("Y",y.x,y.y,spatialText); c.drawText("Z",z.x,z.y,spatialText);
    }

    private void drawSpatialEntities(Canvas c) {
        for (Object e : entities()) {
            String layer = entityLayer(e);
            Geometry3D.Plane3D plane = spatialPlaneForLayer(layer);
            String type=e.getClass().getSimpleName();
            if ("GuideEntity".equals(type) || "MeasureEntity".equals(type) || "AngleEntity".equals(type)) continue;
            if ("LineEntity".equals(type)) {
                Geometry3D.Vec3 a=plane.point(safeGet(e,"x1"),safeGet(e,"y1"));
                Geometry3D.Vec3 b=plane.point(safeGet(e,"x2"),safeGet(e,"y2"));
                drawWorldLine(c,a,b,spatialPaint);
            } else if ("RectEntity".equals(type)) {
                PointF[] p = pointArray(e,"p");
                if (p!=null) drawClosedLocal(c,plane,p,e);
            } else if ("PolygonEntity".equals(type) || "PolylineEntity".equals(type)) {
                List<PointF> pts=points(e);
                if (pts.size()>1) drawLocalPolyline(c,plane,pts,"PolygonEntity".equals(type),e);
            } else if ("CircleEntity".equals(type)) {
                drawCircleLocal(c,plane,safeGet(e,"x"),safeGet(e,"y"),Math.abs(safeGet(e,"r")),e);
            } else if ("ArcEntity".equals(type)) {
                drawArcLocal(c,plane,safeGet(e,"x"),safeGet(e,"y"),Math.abs(safeGet(e,"r")),safeGet(e,"start"),safeGet(e,"sweep"));
            } else if ("PointEntity".equals(type)) {
                PointF p=project(plane.point(safeGet(e,"x"),safeGet(e,"y")));
                c.drawCircle(p.x,p.y,4f,spatialPaint);
            }
        }
    }

    private void drawClosedLocal(Canvas c, Geometry3D.Plane3D plane, PointF[] pts, Object entity) {
        List<PointF> list=new ArrayList<>();
        for(PointF p:pts)list.add(p);
        drawLocalPolyline(c,plane,list,true,entity);
    }

    private void drawLocalPolyline(Canvas c, Geometry3D.Plane3D plane, List<PointF> pts, boolean closed, Object entity) {
        if(pts.size()<2)return;
        for(int i=0;i<pts.size()-1;i++) drawWorldLine(c,plane.point(pts.get(i).x,pts.get(i).y),plane.point(pts.get(i+1).x,pts.get(i+1).y),spatialPaint);
        if(closed)drawWorldLine(c,plane.point(pts.get(pts.size()-1).x,pts.get(pts.size()-1).y),plane.point(pts.get(0).x,pts.get(0).y),spatialPaint);
        float h=extrusion(entity);
        if(closed&&h>0.01f){
            Geometry3D.Vec3 n=plane.normal.mul(h);
            for(int i=0;i<pts.size();i++){
                Geometry3D.Vec3 a=plane.point(pts.get(i).x,pts.get(i).y), b=plane.point(pts.get((i+1)%pts.size()).x,pts.get((i+1)%pts.size()).y);
                drawWorldLine(c,a.add(n),b.add(n),spatialPaint);
                drawWorldLine(c,a,a.add(n),spatialPaint);
            }
        }
    }

    private void drawCircleLocal(Canvas c, Geometry3D.Plane3D plane, float cx,float cy,float r,Object entity) {
        if(r<=0)return;
        int n=40;
        Geometry3D.Vec3 first=null,prev=null;
        float h=extrusion(entity);
        Geometry3D.Vec3 off=plane.normal.mul(h);
        Geometry3D.Vec3 topFirst=null,topPrev=null;
        for(int i=0;i<=n;i++){
            double a=2*Math.PI*i/n;
            Geometry3D.Vec3 p=plane.point(cx+(float)Math.cos(a)*r,cy+(float)Math.sin(a)*r);
            if(first==null)first=p;
            if(prev!=null)drawWorldLine(c,prev,p,spatialPaint);
            prev=p;
            if(h>0.01f){
                Geometry3D.Vec3 tp=p.add(off);
                if(topFirst==null)topFirst=tp;
                if(topPrev!=null)drawWorldLine(c,topPrev,tp,spatialPaint);
                if(i%10==0)drawWorldLine(c,p,tp,spatialPaint);
                topPrev=tp;
            }
        }
    }

    private void drawArcLocal(Canvas c, Geometry3D.Plane3D plane,float cx,float cy,float r,float start,float sweep) {
        if(r<=0)return;
        int n=32; Geometry3D.Vec3 prev=null;
        for(int i=0;i<=n;i++){
            double a=Math.toRadians(start+sweep*i/n);
            Geometry3D.Vec3 p=plane.point(cx+(float)Math.cos(a)*r,cy+(float)Math.sin(a)*r);
            if(prev!=null)drawWorldLine(c,prev,p,spatialPaint);
            prev=p;
        }
    }

    private void drawWorldLine(Canvas c, Geometry3D.Vec3 a, Geometry3D.Vec3 b, Paint paint) {
        PointF p=project(a), q=project(b);
        c.drawLine(p.x,p.y,q.x,q.y,paint);
    }

    private PointF project(Geometry3D.Vec3 p) {
        double yaw=Math.toRadians(cameraYaw), pitch=Math.toRadians(cameraPitch);
        double px=p.x-cameraTargetX,py=p.y-cameraTargetY,pz=p.z-cameraTargetZ;
        double x1=px*Math.cos(yaw)-py*Math.sin(yaw);
        double y1=px*Math.sin(yaw)+py*Math.cos(yaw);
        double z1=pz;
        double y2=y1*Math.cos(pitch)-z1*Math.sin(pitch);
        float scale=spatialScale*Math.min(overviewCard.width(),overviewCard.height())/260f;
        return new PointF(overviewCard.centerX()+cameraPanX+(float)x1*scale,
                overviewCard.centerY()+cameraPanY+(float)y2*scale);
    }

    protected void fitSpatialBounds(float minX,float minY,float minZ,float maxX,float maxY,float maxZ) {
        cameraTargetX=(minX+maxX)*.5f;cameraTargetY=(minY+maxY)*.5f;cameraTargetZ=(minZ+maxZ)*.5f;
        float size=Math.max(1f,Math.max(maxX-minX,Math.max(maxY-minY,maxZ-minZ)));
        spatialScale=clamp(150f/size,.035f,8f);
        cameraPanX=0f;cameraPanY=0f;invalidate();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (overview3D && event.getPointerCount()>=2) {
            float ax=event.getX(0),ay=event.getY(0),bx=event.getX(1),by=event.getY(1);
            float distance=(float)Math.hypot(bx-ax,by-ay),midX=(ax+bx)*.5f,midY=(ay+by)*.5f;
            int action=event.getActionMasked();
            if(action==MotionEvent.ACTION_POINTER_DOWN||!navigating2D){
                navigating2D=true;navLastDistance=Math.max(1f,distance);navLastMidX=midX;navLastMidY=midY;return true;
            }
            if(action==MotionEvent.ACTION_MOVE){
                float ratio=distance/Math.max(1f,navLastDistance);
                spatialScale=clamp(spatialScale*ratio,.02f,20f);
                cameraPanX+=midX-navLastMidX;cameraPanY+=midY-navLastMidY;
                navLastDistance=Math.max(1f,distance);navLastMidX=midX;navLastMidY=midY;invalidate();return true;
            }
            return true;
        }
        if(event.getActionMasked()==MotionEvent.ACTION_UP||event.getActionMasked()==MotionEvent.ACTION_CANCEL)navigating2D=false;
        if (overview3D && event.getPointerCount()==1) {
            int a=event.getActionMasked();
            float x=event.getX(),y=event.getY();
            if(a==MotionEvent.ACTION_DOWN && overviewCard.contains(x,y)){
                orbiting=true; orbitLastX=x; orbitLastY=y; return true;
            }
            if(orbiting){
                if(a==MotionEvent.ACTION_MOVE){
                    cameraYaw += (x-orbitLastX)*0.45f;
                    cameraPitch = clamp(cameraPitch+(y-orbitLastY)*0.35f,-85f,85f);
                    orbitLastX=x; orbitLastY=y; invalidate(); return true;
                }
                if(a==MotionEvent.ACTION_UP||a==MotionEvent.ACTION_CANCEL){orbiting=false;return true;}
                return true;
            }
        }
        return super.onTouchEvent(event);
    }

    // ------------------------------------------------------------------
    // Reflection helpers
    // ------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private List<Object> entities() {
        try { return entitiesField == null ? new ArrayList<>() : (List<Object>)entitiesField.get(this); }
        catch (Exception e) { return new ArrayList<>(); }
    }

    private static String entityLayer(Object e) {
        Object v=call(e,"getLayer");
        return v==null?"":String.valueOf(v);
    }

    private static float extrusion(Object e) {
        Object v=call(e,"getExtrusion");
        return v instanceof Number ? ((Number)v).floatValue() : 0f;
    }

    private static Object call(Object target,String name) {
        if(target==null)return null;
        Class<?> c=target.getClass();
        while(c!=null){
            try{Method m=c.getDeclaredMethod(name);m.setAccessible(true);return m.invoke(target);}
            catch(NoSuchMethodException e){c=c.getSuperclass();}
            catch(Exception e){return null;}
        }
        return null;
    }

    private static Field findField(Class<?> c,String name) {
        Class<?> x=c;
        while(x!=null){
            try{Field f=x.getDeclaredField(name);f.setAccessible(true);return f;}
            catch(Exception e){x=x.getSuperclass();}
        }
        return null;
    }

    private static float safeGet(Object o,String name) {
        try{Field f=findField(o.getClass(),name);return f==null?0f:f.getFloat(o);}catch(Exception e){return 0f;}
    }

    private static PointF[] pointArray(Object o,String name) {
        try{Field f=findField(o.getClass(),name);Object v=f==null?null:f.get(o);return v instanceof PointF[]?(PointF[])v:null;}catch(Exception e){return null;}
    }

    @SuppressWarnings("unchecked")
    private static List<PointF> points(Object o) {
        try{
            Field f=findField(o.getClass(),"points"); Object v=f==null?null:f.get(o);
            if(v instanceof List)return new ArrayList<>((List<PointF>)v);
        }catch(Exception ignored){}
        return new ArrayList<>();
    }

    private static String normalizeDigits(String s) {
        if(s==null)return"";StringBuilder b=new StringBuilder();
        for(int i=0;i<s.length();i++){char c=s.charAt(i);b.append(c);}return b.toString();
    }

    private static String fmt(float v){return String.format(Locale.US,"%.1f",v);}
    private static float clamp(float v,float min,float max){return Math.max(min,Math.min(max,v));}
    private void toast(String s){Toast.makeText(getContext(),s,Toast.LENGTH_SHORT).show();}
}

package ir.chobyar.sketch;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;

/** Single production workspace. No activity swapping and no reflection wiring. */
public final class ChobYarActivity extends Activity {
    private static final int REQUEST_EXPORT_CAD=1701;
    private static final int REQUEST_REFERENCE_IMAGE=1702;
    private Shapr3DGuideCadCanvasView cad;
    private FilamentCadSurface gpuSurface;
    private final CadAppearanceController appearance=new CadAppearanceController();
    private final SectionViewController sectionView=new SectionViewController();
    private LinearLayout primaryRail;
    private LinearLayout adaptive;
    private LinearLayout constraintRail;
    private final WorkspaceController workspace=new WorkspaceController();
    private LinearLayout sessionBar;
    private TextView sessionTitle;
    private TextView sessionDone;
    private TextView sessionCopy;
    private TextView instructionChip;
    private TextView workspaceTitle;
    private TextView modeButton;
    private String currentSelectionKind="NONE";
    private TextView snapButton;
    private FrameLayout.LayoutParams adaptiveParams;
    private File pendingCadExport;
    private long feedbackRevision;
    private boolean manualPalette;
    private boolean sketchPalette;

    @Override protected void onCreate(Bundle state){
        super.onCreate(state);immersive();
        FrameLayout root=new FrameLayout(this);root.setBackgroundColor(Color.rgb(248,249,251));
        gpuSurface=new FilamentCadSurface(this);root.addView(gpuSurface,new FrameLayout.LayoutParams(-1,-1));
        cad=new Shapr3DGuideCadCanvasView(this);
        // The GPU SurfaceView sits behind the interaction canvas. Keeping this
        // layer transparent is what lets exact Filament bodies remain visible.
        cad.setBackgroundColor(Color.TRANSPARENT);
        cad.setStatusListener(this::status);
        cad.setDimensionEditListener(this::editDimension);
        cad.setWorkspaceListener(this::workspaceChanged);
        cad.setOnTouchListener((v,event)->{cad.post(this::syncGpuCamera);return false;});
        root.addView(cad,new FrameLayout.LayoutParams(-1,-1));
        root.addView(topControls(),topLayout());
        primaryRail=mainTools();
        root.addView(primaryRail,wrap(Gravity.START|Gravity.CENTER_VERTICAL,8,0,0,0));
        root.addView(viewTools(),wrap(Gravity.END|Gravity.TOP,0,76,8,0));
        instructionChip=label("",10,true);instructionChip.setGravity(Gravity.CENTER);
        instructionChip.setPadding(dp(12),dp(7),dp(12),dp(7));instructionChip.setMaxWidth(dp(430));
        instructionChip.setBackground(round(Color.argb(246,255,255,255),Color.rgb(214,221,231),13));
        instructionChip.setVisibility(View.GONE);
        root.addView(instructionChip,wrap(Gravity.TOP|Gravity.CENTER_HORIZONTAL,0,76,0,0));
        adaptive=adaptiveTools();adaptive.setVisibility(View.GONE);
        adaptiveParams=wrap(Gravity.START|Gravity.CENTER_VERTICAL,8,0,0,0);
        root.addView(adaptive,adaptiveParams);
        constraintRail=sketchConstraints();constraintRail.setVisibility(View.GONE);
        root.addView(constraintRail,wrap(Gravity.END|Gravity.CENTER_VERTICAL,0,0,8,0));
        sessionBar=sessionTools();sessionBar.setVisibility(View.GONE);
        root.addView(sessionBar,wrap(Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL,0,0,0,12));
        setContentView(root);
        cad.post(()->{syncGpuMesh();updateWorkspaceChrome();});
    }

    private View topControls(){
        LinearLayout b=plain(false);
        b.setPadding(dp(6),dp(3),dp(6),dp(3));
        b.setBackground(round(Color.argb(248,255,255,255),Color.rgb(214,220,228),22));
        b.addView(topAction("⌂",this::showItems),new LinearLayout.LayoutParams(dp(42),dp(48)));
        workspaceTitle=label("چوب‌یار 3D",13,true);workspaceTitle.setGravity(Gravity.CENTER);
        b.addView(workspaceTitle,new LinearLayout.LayoutParams(0,dp(48),1f));
        modeButton=topAction("تمام",this::finishSketchView);modeButton.setTextSize(9);modeButton.setVisibility(View.GONE);
        b.addView(modeButton,new LinearLayout.LayoutParams(dp(48),dp(40)));
        b.addView(topAction("↶",this::undoAction),new LinearLayout.LayoutParams(dp(38),dp(48)));
        b.addView(topAction("↷",this::redoAction),new LinearLayout.LayoutParams(dp(38),dp(48)));
        b.addView(topAction("◷",cad::showHistoryManager),new LinearLayout.LayoutParams(dp(38),dp(48)));
        b.addView(topAction("•••",this::more),new LinearLayout.LayoutParams(dp(42),dp(48)));
        return b;
    }

    private LinearLayout mainTools(){
        LinearLayout b=rail(true);
        b.addView(tool("⌕","Search",this::search));
        b.addView(tool("✎","Sketch",this::showSketchPalette));
        b.addView(tool("＋","Add",this::showAddPalette));
        b.addView(tool("↗","Transform",this::showTransformPalette));
        b.addView(tool("⌁","Tools",this::showToolsPalette));return b;
    }

    private View viewTools(){
        LinearLayout b=rail(true);
        Cube cube=new Cube();b.addView(cube,new LinearLayout.LayoutParams(dp(46),dp(46)));
        b.addView(tool("◇","Fit",()->{cad.fitAll();syncGpuCamera();status("Fit");}));
        snapButton=tool("⌁","Snap",()->{cad.toggleSnap();updateSnap();});b.addView(snapButton);
        b.addView(tool("mm","Units",()->status("واحد پروژه: میلی‌متر")));
        updateSnap();
        return b;
    }

    private void undoAction(){
        if(cad.is3DOverview()){
            status(cad.undoLastFeature());
            return;
        }
        if(!cad.canUndoSketch()){
            status("Undo خالی است");
            return;
        }
        cad.undo();
        status("برگشت");
    }

    private void redoAction(){
        if(cad.is3DOverview()){
            status(cad.redoLastFeature());
            return;
        }
        status(cad.redoSketch()?"جلو":"Redo خالی است");
    }

    private View bottomLeftControls(){
        LinearLayout b=plain(false);
        b.addView(miniAction("↶",this::undoAction));
        b.addView(miniAction("↷",this::redoAction));
        b.addView(miniAction("▱",cad::showHistoryManager));
        return b;
    }

    private View bottomRightControls(){
        LinearLayout b=plain(false);
        b.addView(miniAction("▣",this::showItems));
        snapButton=miniAction("⌁",()->{cad.toggleSnap();updateSnap();});b.addView(snapButton);
        b.addView(miniAction("⚙",this::more));
        updateSnap();return b;
    }

    private LinearLayout adaptiveTools(){
        return rail(true);
    }

    private LinearLayout sketchConstraints(){
        LinearLayout b=rail(true);
        b.addView(tool("H/V","Horizontal\nVertical",()->status(cad.applyHorizontalVerticalConstraint())));
        b.addView(tool("⊥","Perpendicular",()->status(cad.applyPerpendicularConstraint())));
        b.addView(tool("∥","Parallel",()->status(cad.applyParallelConstraint())));
        b.addView(tool("⌑","Constraints",cad::showSmartConstraintMenu));
        return b;
    }

    private LinearLayout sessionTools(){
        LinearLayout b=plain(false);b.setPadding(dp(3),dp(2),dp(3),dp(2));
        b.setBackground(round(Color.argb(248,255,255,255),Color.rgb(210,218,228),18));
        b.addView(sessionButton("لغو",this::cancelWorkspaceTool));
        sessionCopy=sessionButton("Copy",()->status(cad.toggleBodyTransformCopy()));sessionCopy.setVisibility(View.GONE);b.addView(sessionCopy);
        sessionTitle=label("",9,true);sessionTitle.setGravity(Gravity.CENTER);
        sessionTitle.setOnClickListener(v->editSessionValue());
        b.addView(sessionTitle,new LinearLayout.LayoutParams(dp(116),dp(38)));
        sessionDone=sessionButton("انجام",this::finishWorkspaceTool);b.addView(sessionDone);
        return b;
    }

    private void workspaceChanged(String info,boolean exact,int tool){
        boolean selected=cad.hasWorkspaceSelection();
        currentSelectionKind=selected?cad.selectionKind():"NONE";
        WorkspaceController.State state=workspace.onCanvasState(cad.is3DOverview(),currentSelectionKind);
        if(state.tool==WorkspaceController.Tool.ALIGN&&cad.isAlignPreviewReady())state=workspace.previewReady();
        if(state.tool==WorkspaceController.Tool.MOVE_ROTATE&&state.canCommit()
                &&!cad.is3DOverview()&&!cad.isTransformSessionActive())cad.showTransformGizmo();
        updateSessionUi(state);
        if(adaptive!=null){
            if(selected&&!manualPalette)renderAdaptive(currentSelectionKind);
            boolean contextual=(manualPalette||selected)&&!state.sessionActive();
            adaptive.setVisibility(contextual?View.VISIBLE:View.GONE);
            if(primaryRail!=null)primaryRail.setVisibility(contextual||state.sessionActive()?View.GONE:View.VISIBLE);
        }
        updateConstraintRail(tool,state.sessionActive());
        updateWorkspaceChrome();
        syncGpuMesh();
    }

    private void beginMoveRotate(){
        if(cad.is3DOverview()&&!"NONE".equals(currentSelectionKind)){
            closeManualPalette();String result=cad.beginBodyTransformSession();
            if(!cad.isBodyTransformSessionActive()){status(result);return;}
            workspace.begin(WorkspaceController.Tool.MOVE_ROTATE);updateSessionUi(workspace.previewReady());status(result);return;
        }
        WorkspaceController.State state=workspace.begin(WorkspaceController.Tool.MOVE_ROTATE);
        if(state.canCommit())cad.showTransformGizmo();
        updateSessionUi(state);
    }

    private void beginAlign(){
        closeManualPalette();String result=cad.beginAlignSession();
        if(!cad.isAlignSessionActive()){status(result);return;}
        workspace.begin(WorkspaceController.Tool.ALIGN);updateSessionUi(workspace.primaryAccepted());status(result);
    }

    private void finishWorkspaceTool(){
        WorkspaceController.State state=workspace.state();
        if(!state.canCommit())return;
        String result=null;
        if(state.tool==WorkspaceController.Tool.MOVE_ROTATE){
            if(cad.isBodyTransformSessionActive())result=cad.commitBodyTransformSession();else cad.finishTransformSession();
        }
        if(state.tool==WorkspaceController.Tool.EXTRUDE)result=cad.commitInteractiveExtrude();
        if(state.tool==WorkspaceController.Tool.REVOLVE)result=cad.commitInteractiveRevolve();
        if(state.tool==WorkspaceController.Tool.ALIGN)result=cad.commitAlignSession();
        updateSessionUi(workspace.finish());
        if(result!=null)status(result);
    }

    private void cancelWorkspaceTool(){
        WorkspaceController.State state=workspace.state();
        if(state.tool==WorkspaceController.Tool.MOVE_ROTATE){if(cad.isBodyTransformSessionActive())cad.cancelBodyTransformSession();else cad.cancelTransformSession();}
        if(state.tool==WorkspaceController.Tool.EXTRUDE)cad.cancelInteractiveExtrude();
        if(state.tool==WorkspaceController.Tool.REVOLVE)cad.cancelInteractiveRevolve();
        if(state.tool==WorkspaceController.Tool.ALIGN)cad.cancelAlignSession();
        updateSessionUi(workspace.cancel());
    }

    private void beginExtrude(){
        closeManualPalette();
        String result=cad.beginInteractiveExtrudeSession();
        if(!cad.isInteractiveExtrudeActive()){status(result);return;}
        workspace.begin(WorkspaceController.Tool.EXTRUDE);updateSessionUi(workspace.previewReady());
        status(result);
    }

    private void beginRevolve(){
        closeManualPalette();
        String result=cad.beginInteractiveRevolveSession();
        if(!cad.isInteractiveRevolveActive()){status(result);return;}
        workspace.begin(WorkspaceController.Tool.REVOLVE);updateSessionUi(workspace.previewReady());
    }

    private void editSessionValue(){
        WorkspaceController.State state=workspace.state();
        if(state.tool==WorkspaceController.Tool.REVOLVE)cad.showInteractiveRevolveAngleEditor();
        else if(state.tool==WorkspaceController.Tool.MOVE_ROTATE&&cad.isBodyTransformSessionActive())cad.showBodyTransformExactEditor();
        else if(state.tool==WorkspaceController.Tool.ALIGN)status(cad.flipAlignSession());
    }

    private void updateSessionUi(WorkspaceController.State state){
        boolean active=state.sessionActive();
        if(sessionBar!=null)sessionBar.setVisibility(active?View.VISIBLE:View.GONE);
        if(instructionChip!=null){instructionChip.setText(state.instruction());instructionChip.setVisibility(active?View.VISIBLE:View.GONE);}
        if(sessionTitle!=null){
            String title=state.title();
            if(state.tool==WorkspaceController.Tool.REVOLVE&&cad!=null&&cad.isInteractiveRevolveActive())title=cad.interactiveRevolveSummary();
            else if(state.tool==WorkspaceController.Tool.MOVE_ROTATE&&cad!=null&&cad.isBodyTransformSessionActive())title=cad.bodyTransformSummary();
            else if(state.tool==WorkspaceController.Tool.ALIGN&&cad!=null&&cad.isAlignSessionActive())title=cad.alignSummary();
            sessionTitle.setText(title);
        }
        if(sessionDone!=null){sessionDone.setEnabled(state.canCommit());sessionDone.setAlpha(state.canCommit()?1f:.38f);}
        if(sessionCopy!=null){boolean copy=active&&state.tool==WorkspaceController.Tool.MOVE_ROTATE&&cad!=null&&cad.isBodyTransformSessionActive();sessionCopy.setVisibility(copy?View.VISIBLE:View.GONE);sessionCopy.setText(copy&&cad.isBodyTransformCopy()?"Copy ✓":"Copy");}
        if(adaptive!=null&&active)adaptive.setVisibility(View.GONE);
        updateConstraintRail(cad==null?CadCanvasView.TOOL_SELECT:cad.getTool(),active);
        if(primaryRail!=null){
            boolean contextual=(manualPalette||(cad!=null&&cad.hasWorkspaceSelection()))&&!active;
            primaryRail.setVisibility(active||contextual?View.GONE:View.VISIBLE);
            if(adaptive!=null)adaptive.setVisibility(contextual?View.VISIBLE:View.GONE);
        }
    }

    private void syncGpuMesh(){
        if(gpuSurface==null||cad==null)return;
        double[] mesh=cad.gpuMesh();gpuSurface.setMesh(sectionView.apply(mesh));cad.setGpuBodyRendering(mesh.length>=9);syncGpuCamera();
    }

    private void syncGpuCamera(){if(gpuSurface!=null&&cad!=null)gpuSurface.setCameraState(cad.gpuCameraState());}

    private void renderAdaptive(String kind){
        setAdaptivePlacement(false);
        adaptive.removeAllViews();String k=kind==null?"SKETCH":kind;
        adaptive.addView(tool("×","Deselect All",cad::clearWorkspaceSelection));
        if("EDGE".equals(k)){
            adaptive.addView(tool("⌒","Fillet",cad::showSelectedFillet));adaptive.addView(tool("／","Chamfer",cad::showSelectedChamfer));
            adaptive.addView(tool("⌨","Measure",this::editDimension));
        }else if("FACE".equals(k)){
            adaptive.addView(tool("✎","Sketch",this::sketchOnSelectedFace));
            adaptive.addView(tool("↗","Move/Rotate",beginMoveRotateRunnable()));
            adaptive.addView(tool("⇧","Extrude",cad::showSelectedPushPull));
        }else if("BODY".equals(k)){
            adaptive.addView(tool("↗","Move/Rotate",beginMoveRotateRunnable()));
            adaptive.addView(tool("◉","Material",this::showMaterialPalette));
            adaptive.addView(tool("◫","Section",this::showSectionViewPanel));
            adaptive.addView(tool("∪","Boolean",cad::showSolidManager));
            adaptive.addView(tool("⌨","Measure",cad::showSketchMeasureInspector));
        }else if("VERTEX".equals(k)){
            adaptive.addView(tool("↗","Move/Rotate",beginMoveRotateRunnable()));adaptive.addView(tool("⌨","Measure",cad::showSketchMeasureInspector));
        }else{
            adaptive.addView(tool("↗","Move/Rotate",beginMoveRotateRunnable()));
            adaptive.addView(tool("⧉","Offset",cad::showOffsetEdgeTool));
            adaptive.addView(tool("✂","Trim",()->status(cad.trimSelectedLines())));
            adaptive.addView(tool("╱","Extend",()->status(cad.extendSelectedLines())));
            adaptive.addView(tool("⌨","اندازه",this::editDimension));
            adaptive.addView(tool("⬆","Extrude",this::beginExtrude));
        }
        adaptive.addView(tool("⌁","More",this::tools));
        adaptive.addView(tool("⌫","Delete",()->{cad.deleteSelected();cad.dispatchWorkspaceState();}));
    }

    private Runnable beginMoveRotateRunnable(){return this::beginMoveRotate;}

    private void sketchOnSelectedFace(){
        String result=cad.sketchOnSelectedFace();status(result);
        if(!cad.is3DOverview())showSketchPalette();
    }

    private void showSketchPalette(){
        if(cad.is3DOverview())status(cad.enterActiveSketchView());
        updateWorkspaceChrome();syncGpuCamera();
        sketchPalette=true;
        openManualPalette();
        adaptive.addView(tool("×","Close",this::closeManualPalette));
        adaptive.addView(tool("╱","Line",()->activateSketchTool(CadCanvasView.TOOL_LINE,"Line")));
        adaptive.addView(tool("⌒","Arc",()->activateSketchTool(CadCanvasView.TOOL_ARC,"Arc")));
        adaptive.addView(tool("▭","Rectangle",()->activateSketchTool(CadCanvasView.TOOL_RECT,"Rectangle")));
        adaptive.addView(tool("○","Circle",()->activateSketchTool(CadCanvasView.TOOL_CIRCLE,"Circle")));
        adaptive.addView(tool("⬡","Polygon",()->activateSketchTool(CadCanvasView.TOOL_POLYGON,"Polygon")));
        adaptive.addView(tool("⌁","Constraints",cad::showSmartConstraintMenu));
        adaptive.addView(tool("…","More",cad::showShaprSketchMenu));
    }

    private void showAddPalette(){
        sketchPalette=false;
        openManualPalette();
        adaptive.addView(tool("×","Close",this::closeManualPalette));
        adaptive.addView(tool("⬆","Extrude",this::beginExtrude));
        adaptive.addView(tool("⟳","Revolve",this::beginRevolve));
        adaptive.addView(tool("➜","Sweep",()->runAndClose(cad::showSweepTool)));
        adaptive.addView(tool("≋","Loft",()->runAndClose(cad::showLoftTool)));
        adaptive.addView(tool("▧","Image",this::importReferenceImage));
        adaptive.addView(tool("∪","Boolean",()->runAndClose(cad::showSolidManager)));
        adaptive.addView(tool("◇","Plane",cad::showPlaneManager));
    }

    private void showTransformPalette(){
        sketchPalette=false;
        openManualPalette();
        adaptive.addView(tool("×","Close",this::closeManualPalette));
        adaptive.addView(tool("↗","Move/Rotate",()->runAndClose(this::beginMoveRotate)));
        adaptive.addView(tool("⇥","Align",()->runAndClose(this::beginAlign)));
        adaptive.addView(tool("⇲","Scale",()->runAndClose(cad::showScaleTool)));
        adaptive.addView(tool("⇋","Mirror",()->runAndClose(cad::showMirrorTool)));
        adaptive.addView(tool("⠿","Pattern",()->runAndClose(cad::showLinearPatternTool)));
    }

    private void showToolsPalette(){
        sketchPalette=false;
        openManualPalette();
        adaptive.addView(tool("×","Close",this::closeManualPalette));
        adaptive.addView(tool("⌒","Fillet",cad::showSelectedFillet));
        adaptive.addView(tool("／","Chamfer",cad::showSelectedChamfer));
        adaptive.addView(tool("▣","Shell",cad::showSelectedShell));
        adaptive.addView(tool("◉","Material",this::showMaterialPalette));
        adaptive.addView(tool("◫","Section",this::showSectionViewPanel));
        adaptive.addView(tool("⌖","Measure",cad::showSketchMeasureInspector));
        adaptive.addView(tool("⌁","Snaps",cad::showShaprSnappingOptions));
        adaptive.addView(tool("▱","History",cad::showHistoryManager));
        adaptive.addView(tool("…","More",this::tools));
    }

    private void openManualPalette(){
        manualPalette=true;setAdaptivePlacement(true);adaptive.removeAllViews();adaptive.setVisibility(View.VISIBLE);
        if(primaryRail!=null)primaryRail.setVisibility(View.GONE);
    }

    private void closeManualPalette(){
        manualPalette=false;sketchPalette=false;adaptive.removeAllViews();
        boolean selected=cad!=null&&cad.hasWorkspaceSelection();
        if(selected){renderAdaptive(cad.selectionKind());adaptive.setVisibility(View.VISIBLE);}
        else adaptive.setVisibility(View.GONE);
        if(primaryRail!=null)primaryRail.setVisibility(selected?View.GONE:View.VISIBLE);
        updateConstraintRail(cad==null?CadCanvasView.TOOL_SELECT:cad.getTool(),false);
    }

    private void activateSketchTool(int tool,String name){
        if(cad.is3DOverview())cad.enterActiveSketchView();
        cad.setTool(tool);status(name+" فعال شد");
        updateWorkspaceChrome();
        updateConstraintRail(tool,false);
    }

    private void finishSketchView(){
        if(cad==null||cad.is3DOverview())return;
        closeManualPalette();cad.setTool(CadCanvasView.TOOL_SELECT);
        cad.setStandardView("ISO");cad.post(cad::fitAll);syncGpuCamera();
        updateWorkspaceChrome();status("نمای 3D");
    }

    private void updateConstraintRail(int activeTool,boolean sessionActive){
        if(constraintRail==null||cad==null)return;
        boolean sketching=!sessionActive&&!cad.is3DOverview()
                &&(sketchPalette||activeTool!=CadCanvasView.TOOL_SELECT);
        constraintRail.setVisibility(sketching?View.VISIBLE:View.GONE);
    }

    private void runAndClose(Runnable action){closeManualPalette();action.run();}

    private void search(){
        String[] x={"Sketch","Extrude","Move / Rotate","Measure","Constraints","Material","History","Plane","Snaps"};
        new AlertDialog.Builder(this).setTitle("جستجوی فرمان").setItems(x,(d,w)->{
            if(w==0)cad.showShaprSketchMenu();else if(w==1)cad.showShaprModelingToolsMenu();
            else if(w==2)beginMoveRotate();else if(w==3)cad.showSketchMeasureInspector();
            else if(w==4)cad.showSmartConstraintMenu();else if(w==5)showMaterialPalette();
            else if(w==6)cad.showHistoryManager();else if(w==7)cad.showPlaneManager();else cad.showShaprSnappingOptions();
        }).show();
    }

    private void tools(){
        String[] x={"Sketch tools","3D modeling","Edit Face / Edge","Constraints","Snaps / Guides","Measure","History"};
        new AlertDialog.Builder(this).setTitle("Tools").setItems(x,(d,w)->{
            if(w==0)cad.showShaprSketchMenu();else if(w==1)cad.showShaprModelingToolsMenu();
            else if(w==2)cad.showDirectManager();else if(w==3)cad.showSmartConstraintMenu();
            else if(w==4)cad.showShaprSnappingOptions();else if(w==5)cad.showSketchMeasureInspector();else cad.showHistoryManager();
        }).show();
    }

    private void transformTools(){
        String[] items={"↗ Move / Rotate","⇲ Scale","⇋ Mirror","⠿ Linear Pattern"};
        new AlertDialog.Builder(this).setTitle("Transform").setItems(items,(d,w)->{
            if(w==0)beginMoveRotate();
            else if(w==1)cad.showScaleTool();
            else if(w==2)cad.showMirrorTool();
            else cad.showLinearPatternTool();
        }).setNegativeButton("بستن",null).show();
    }

    private void more(){
        String[] x={"Items / Layers","Export STEP / STL","Reference Image","Materials / Appearance","نمای بالا","نمای روبرو","نمای راست","نمای ایزومتریک","Snaps / Guides","واحد پروژه: mm"};
        new AlertDialog.Builder(this).setTitle("چوب‌یار 3D").setItems(x,(d,w)->{
            if(w==0)showItems();else if(w==1)showCadExport();else if(w==2){if(cad.hasReferenceImage())cad.showReferenceImageSettings();else importReferenceImage();}
            else if(w==3)showMaterialPalette();else if(w==4)setView("TOP");else if(w==5)setView("FRONT");else if(w==6)setView("RIGHT");
            else if(w==7)setView("ISO");else if(w==8)cad.showShaprSnappingOptions();else status(cad.dualUnitSummary());
        }).show();
    }

    private void importReferenceImage(){
        closeManualPalette();
        Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT);intent.addCategory(Intent.CATEGORY_OPENABLE);intent.setType("image/*");startActivityForResult(intent,REQUEST_REFERENCE_IMAGE);
    }

    private void showWorkspaceHelp(){
        new AlertDialog.Builder(this).setTitle("راهنمای Workspace")
                .setMessage("قلم: طراحی و انتخاب دقیق\nانگشت: چرخش، جابه‌جایی و زوم\n\nیک سطح، لبه یا بدنه را لمس کن تا فقط ابزارهای مربوط به همان انتخاب ظاهر شوند. همه اندازه‌ها میلی‌متر هستند.")
                .setPositiveButton("باشه",null).show();
    }

    private void showSectionViewPanel(){
        String[] choices={"خاموش","XY • محور Z","YZ • محور X","XZ • محور Y","Flip side"};
        LinearLayout box=plain(true);box.setPadding(dp(18),dp(4),dp(18),0);
        TextView offsetLabel=label("Offset • "+String.format(java.util.Locale.US,"%.1f mm",sectionView.offsetMm()),11,true);box.addView(offsetLabel);
        EditText offsetInput=new EditText(this);offsetInput.setSingleLine(true);offsetInput.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL|InputType.TYPE_NUMBER_FLAG_SIGNED);
        offsetInput.setText(String.format(java.util.Locale.US,"%.1f",sectionView.offsetMm()));box.addView(offsetInput,new LinearLayout.LayoutParams(dp(280),dp(48)));
        new AlertDialog.Builder(this).setTitle("Section View")
                .setMessage("این برش فقط نمای رندر را کلیپ می‌کند؛ هندسه OCCT، History، ابعاد و Export تغییر نمی‌کنند.")
                .setView(box).setSingleChoiceItems(choices,sectionView.selectedIndex(),(d,w)->{
                    if(w==0)sectionView.disable();
                    else if(w==1)sectionView.enable(SectionViewController.Axis.Z);
                    else if(w==2)sectionView.enable(SectionViewController.Axis.X);
                    else if(w==3)sectionView.enable(SectionViewController.Axis.Y);
                    else sectionView.flip();
                    syncGpuMesh();status(sectionView.summary());
                }).setPositiveButton("اعمال فاصله",(d,w)->{
                    try{sectionView.setOffsetMm(Double.parseDouble(offsetInput.getText().toString().trim()));}
                    catch(Exception ignored){status("Offset نامعتبر بود");return;}
                    if(!sectionView.isEnabled())sectionView.enable(SectionViewController.Axis.Z);
                    syncGpuMesh();status(sectionView.summary());
                }).setNegativeButton("بستن",null).show();
    }

    private void showMaterialPalette(){
        CadMaterialPreset.Preset[] presets=CadMaterialPreset.Preset.values();
        CadMaterialPreset.State current=appearance.state();
        String[] names=new String[presets.length];
        for(int i=0;i<presets.length;i++){
            CadMaterialPreset.Preset p=presets[i];
            names[i]=(p==current.preset?"✓ ":"")+p.key.toUpperCase(java.util.Locale.US)+" • "+p.label;
        }
        new AlertDialog.Builder(this).setTitle("Materials / Appearance")
                .setMessage("فقط ظاهر رندر تغییر می‌کند؛ هندسه، ابعاد، History و خروجی CAD ثابت می‌مانند.")
                .setItems(names,(d,w)->{
                    CadMaterialPreset.State state=appearance.applyPreset(presets[w],gpuSurface::setAppearance);
                    showAppearanceEditor(state);
                }).setNegativeButton("بستن",null).show();
    }

    private void showAppearanceEditor(CadMaterialPreset.State initial){
        LinearLayout box=plain(true);box.setPadding(dp(18),dp(6),dp(18),0);
        TextView summary=label(initial.summary(),11,true);box.addView(summary);
        TextView colorLabel=label("Base color • #"+String.format(java.util.Locale.US,"%06X",initial.argb&0x00FFFFFF),11,true);box.addView(colorLabel);
        EditText colorInput=new EditText(this);colorInput.setSingleLine(true);colorInput.setHint("#RRGGBB");colorInput.setText(String.format(java.util.Locale.US,"#%06X",initial.argb&0x00FFFFFF));colorInput.setSelectAllOnFocus(true);box.addView(colorInput,new LinearLayout.LayoutParams(dp(280),dp(48)));
        TextView value=label("Roughness • "+Math.round(initial.roughness*100f)+"%",11,true);box.addView(value);
        SeekBar roughness=new SeekBar(this);roughness.setMax(100);roughness.setProgress(Math.round(initial.roughness*100f));box.addView(roughness,new LinearLayout.LayoutParams(dp(280),dp(48)));
        TextView metallic=label("Metallic • "+Math.round(initial.metallic*100f)+"%",11,false);box.addView(metallic);
        roughness.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onProgressChanged(SeekBar s,int progress,boolean fromUser){value.setText("Roughness • "+progress+"%");}
            public void onStartTrackingTouch(SeekBar s){}
            public void onStopTrackingTouch(SeekBar s){}
        });
        new AlertDialog.Builder(this).setTitle(initial.preset.label+" • Appearance").setView(box)
                .setPositiveButton("اعمال",(d,w)->{
                    try{
                        int color=parseAppearanceColor(colorInput.getText().toString());
                        appearance.setColor(color,null);
                        CadMaterialPreset.State state=appearance.setRoughness(Math.max(.04f,roughness.getProgress()/100f),gpuSurface::setAppearance);
                        status("Material • "+state.summary());
                    }catch(Exception e){toast("کد رنگ باید مثل #B98758 باشد");}
                })
                .setNeutralButton("بازنشانی",(d,w)->{CadMaterialPreset.State state=appearance.applyPreset(initial.preset,gpuSurface::setAppearance);status("Material • "+state.summary());})
                .setNegativeButton("لغو",null).show();
    }

    private static int parseAppearanceColor(String raw){
        String s=raw==null?"":raw.trim();if(s.startsWith("#"))s=s.substring(1);
        if(s.length()!=6&&s.length()!=8)throw new IllegalArgumentException("hex color");
        long value=Long.parseLong(s,16);
        return s.length()==6?(int)(0xFF000000L|value):(int)value;
    }

    private void showCadExport(){
        String[] formats={"STEP • مدل دقیق قابل ویرایش","STL • مناسب چاپ سه‌بعدی / CAM"};
        new AlertDialog.Builder(this).setTitle("Export CAD").setItems(formats,(d,w)->exportCad(w)).setNegativeButton("لغو",null).show();
    }

    private void exportCad(int format){
        String ext=format==0?"step":"stl";File file=new File(getCacheDir(),"ChobYar-Model."+ext);
        if(!cad.exportVisibleCad(file.getAbsolutePath(),format)){toast("بدنه دقیق قابل خروجی وجود ندارد");return;}
        pendingCadExport=file;Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);intent.setType(format==0?"model/step":"model/stl");
        intent.putExtra(Intent.EXTRA_TITLE,file.getName());startActivityForResult(intent,REQUEST_EXPORT_CAD);
    }

    @Override protected void onActivityResult(int requestCode,int resultCode,Intent data){
        super.onActivityResult(requestCode,resultCode,data);
        if(requestCode==REQUEST_REFERENCE_IMAGE){
            if(resultCode!=RESULT_OK||data==null||data.getData()==null)return;
            try{Bitmap bitmap=decodeReferenceBitmap(data);status(cad.setReferenceImage(bitmap,"Reference Image"));cad.showReferenceImageSettings();}
            catch(Exception e){toast("خواندن تصویر مرجع انجام نشد");}return;
        }
        if(requestCode!=REQUEST_EXPORT_CAD||resultCode!=RESULT_OK||data==null||data.getData()==null||pendingCadExport==null)return;
        try(FileInputStream in=new FileInputStream(pendingCadExport);OutputStream out=getContentResolver().openOutputStream(data.getData())){
            if(out==null)throw new IllegalStateException();byte[] buffer=new byte[65536];int n;while((n=in.read(buffer))>0)out.write(buffer,0,n);
            out.flush();toast("فایل CAD ذخیره شد");
        }catch(Exception e){toast("ذخیره فایل انجام نشد");}finally{pendingCadExport=null;}
    }

    private Bitmap decodeReferenceBitmap(Intent data)throws Exception{
        BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;
        try(InputStream in=getContentResolver().openInputStream(data.getData())){if(in==null)throw new IllegalStateException();BitmapFactory.decodeStream(in,null,bounds);}
        int max=Math.max(bounds.outWidth,bounds.outHeight),sample=1;while(max/sample>2048)sample*=2;
        BitmapFactory.Options options=new BitmapFactory.Options();options.inSampleSize=sample;options.inPreferredConfig=Bitmap.Config.ARGB_8888;
        try(InputStream in=getContentResolver().openInputStream(data.getData())){if(in==null)throw new IllegalStateException();Bitmap bitmap=BitmapFactory.decodeStream(in,null,options);if(bitmap==null)throw new IllegalStateException();return bitmap;}
    }

    private void setView(String view){
        if(manualPalette)closeManualPalette();
        cad.setStandardView(view);cad.post(cad::fitAll);syncGpuCamera();updateWorkspaceChrome();
    }

    private void showItems(){
        String[] bodies=cad.itemRows();boolean image=cad.hasReferenceImage();
        if(bodies.length==0&&!image){toast("هنوز Body یا تصویر مرجعی وجود ندارد");return;}
        String[] rows=new String[bodies.length+(image?1:0)];System.arraycopy(bodies,0,rows,0,bodies.length);if(image)rows[rows.length-1]="▧ Reference Image";
        new AlertDialog.Builder(this).setTitle("Items")
                .setMessage("Bodyها و تصویر مرجع از همین‌جا انتخاب و مدیریت می‌شوند.")
                .setItems(rows,(d,w)->{if(image&&w==rows.length-1)cad.showReferenceImageSettings();else{status(cad.selectItem(w));showItemActions(w);}})
                .setNegativeButton("بستن",null).show();
    }

    private void showItemActions(int index){
        String[] actions={"نمایش / مخفی","تغییر نام","Fit انتخاب"};
        new AlertDialog.Builder(this).setTitle("Body").setItems(actions,(d,w)->{
            if(w==0){status(cad.toggleItemVisibility(index));showItems();}
            else if(w==1)renameItem(index);
            else {cad.fitAll();status("Fit");}
        }).setNegativeButton("بستن",null).show();
    }

    private void renameItem(int index){
        EditText e=new EditText(this);e.setSingleLine();
        new AlertDialog.Builder(this).setTitle("تغییر نام Body").setView(e)
                .setPositiveButton("ذخیره",(d,w)->status(cad.renameItem(index,e.getText().toString())))
                .setNegativeButton("لغو",null).show();
    }

    private void editDimension(){
        if(!cad.canEditExactDimension()){status(cad.exactDimensionMessage());return;}
        EditText e=new EditText(this);e.setSingleLine();e.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        e.setText(cad.exactDimensionCurrentValue());e.setSelectAllOnFocus(true);
        new AlertDialog.Builder(this).setTitle(cad.exactDimensionTitle()).setMessage(cad.exactDimensionHint()).setView(e)
                .setPositiveButton("اعمال",(d,w)->status(cad.applySelectedDimension(e.getText().toString())))
                .setNegativeButton("لغو",null).show();
    }

    private void updateSnap(){if(snapButton!=null){snapButton.setText("⌁\nSnap");snapButton.setTextColor(cad.isSnapEnabled()?Color.rgb(0,105,210):Color.rgb(80,86,96));}}
    private void updateWorkspaceChrome(){
        if(cad==null)return;boolean model=cad.is3DOverview();
        if(workspaceTitle!=null){
            String title=model?"چوب‌یار 3D":"Sketch • "+cad.activePlaneLabel()+" • mm";
            if(model&&!"NONE".equals(currentSelectionKind))title=currentSelectionKind+" انتخاب شد";
            workspaceTitle.setText(title);
        }
        if(modeButton!=null)modeButton.setVisibility(model?View.GONE:View.VISIBLE);
    }
    private void status(String s){
        if(instructionChip==null||s==null||s.trim().isEmpty()||workspace.state().sessionActive())return;
        final long revision=++feedbackRevision;
        instructionChip.setText(s);instructionChip.setVisibility(View.VISIBLE);
        instructionChip.postDelayed(()->{
            if(revision==feedbackRevision&&!workspace.state().sessionActive())instructionChip.setVisibility(View.GONE);
        },2200L);
    }
    private void toast(String s){Toast.makeText(this,s,Toast.LENGTH_LONG).show();}

    private LinearLayout plain(boolean vertical){LinearLayout x=new LinearLayout(this);x.setOrientation(vertical?LinearLayout.VERTICAL:LinearLayout.HORIZONTAL);x.setGravity(Gravity.CENTER);return x;}
    private LinearLayout rail(boolean vertical){LinearLayout x=plain(vertical);x.setPadding(dp(2),dp(3),dp(2),dp(3));x.setBackground(round(Color.argb(246,255,255,255),Color.rgb(217,223,231),18));return x;}
    private TextView tool(String icon,String text,Runnable r){TextView v=label(icon+"\n"+text,7.5f,false);v.setGravity(Gravity.CENTER);v.setMinWidth(dp(56));v.setMinHeight(dp(48));v.setPadding(dp(2),dp(2),dp(2),dp(2));v.setBackgroundColor(Color.TRANSPARENT);v.setOnClickListener(q->r.run());return v;}
    private TextView miniAction(String text,Runnable r){TextView v=label(text,15,false);v.setGravity(Gravity.CENTER);v.setMinWidth(dp(34));v.setMinHeight(dp(34));v.setBackground(round(Color.argb(205,255,255,255),Color.TRANSPARENT,17));v.setOnClickListener(q->r.run());return v;}
    private TextView topAction(String text,Runnable r){TextView v=label(text,14,false);v.setGravity(Gravity.CENTER);v.setBackgroundColor(Color.TRANSPARENT);v.setOnClickListener(q->r.run());return v;}
    private TextView sessionButton(String text,Runnable r){TextView v=label(text,9,true);v.setGravity(Gravity.CENTER);v.setMinWidth(dp(58));v.setMinHeight(dp(38));v.setPadding(dp(7),0,dp(7),0);v.setBackground(round(Color.argb(232,255,255,255),Color.rgb(213,220,229),12));v.setOnClickListener(q->r.run());return v;}
    private TextView label(String s,float size,boolean bold){TextView v=new TextView(this);v.setText(s);v.setTextSize(size);v.setTextColor(Color.rgb(38,45,56));if(bold)v.setTypeface(null,Typeface.BOLD);return v;}
    private GradientDrawable round(int fill,int stroke,int radius){GradientDrawable d=new GradientDrawable();d.setColor(fill);d.setCornerRadius(dp(radius));d.setStroke(dp(1),stroke);return d;}
    private FrameLayout.LayoutParams wrap(int g,int l,int t,int r,int b){FrameLayout.LayoutParams p=new FrameLayout.LayoutParams(-2,-2,g);p.setMargins(dp(l),dp(t),dp(r),dp(b));return p;}
    private FrameLayout.LayoutParams topLayout(){FrameLayout.LayoutParams p=new FrameLayout.LayoutParams(-1,dp(56),Gravity.TOP);p.setMargins(dp(10),dp(8),dp(10),0);return p;}
    private void setAdaptivePlacement(boolean palette){
        if(adaptive==null)return;adaptive.setOrientation(palette?LinearLayout.VERTICAL:LinearLayout.HORIZONTAL);
        adaptiveParams=wrap(palette?Gravity.START|Gravity.CENTER_VERTICAL:Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL,palette?8:0,0,0,palette?0:12);
        adaptive.setLayoutParams(adaptiveParams);
    }
    private int dp(int v){return Math.round(v*getResources().getDisplayMetrics().density);}
    private void immersive(){getWindow().getDecorView().setSystemUiVisibility(5894|View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);}
    @Override public void onWindowFocusChanged(boolean h){super.onWindowFocusChanged(h);if(h)immersive();}
    @Override protected void onDestroy(){if(gpuSurface!=null)gpuSurface.destroyRenderer();super.onDestroy();}

    private final class Cube extends View{
        Paint p=new Paint(1);Path a=new Path(),b=new Path(),c=new Path();int mode=0;
        Cube(){super(ChobYarActivity.this);setOnClickListener(v->{mode=(mode+1)%4;String[] m={"ISO","TOP","FRONT","RIGHT"};setView(m[mode]);invalidate();});}
        @Override protected void onDraw(Canvas x){super.onDraw(x);float w=getWidth(),h=getHeight();p.setStrokeWidth(dp(1));p.setStyle(Paint.Style.FILL);
            a.reset();a.moveTo(w*.2f,h*.35f);a.lineTo(w*.5f,h*.17f);a.lineTo(w*.8f,h*.35f);a.lineTo(w*.5f,h*.53f);a.close();p.setColor(Color.rgb(231,237,245));x.drawPath(a,p);
            b.reset();b.moveTo(w*.2f,h*.35f);b.lineTo(w*.5f,h*.53f);b.lineTo(w*.5f,h*.84f);b.lineTo(w*.2f,h*.66f);b.close();p.setColor(Color.rgb(218,227,239));x.drawPath(b,p);
            c.reset();c.moveTo(w*.5f,h*.53f);c.lineTo(w*.8f,h*.35f);c.lineTo(w*.8f,h*.66f);c.lineTo(w*.5f,h*.84f);c.close();p.setColor(Color.rgb(201,216,233));x.drawPath(c,p);p.setStyle(Paint.Style.STROKE);p.setColor(Color.rgb(82,99,120));x.drawPath(a,p);x.drawPath(b,p);x.drawPath(c,p);}
    }
}

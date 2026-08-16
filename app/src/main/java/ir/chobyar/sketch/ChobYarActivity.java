package ir.chobyar.sketch;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
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
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.OutputStream;

/** Single production workspace. No activity swapping and no reflection wiring. */
public final class ChobYarActivity extends Activity {
    private static final int REQUEST_EXPORT_CAD=1701;
    private Shapr3DGuideCadCanvasView cad;
    private FilamentCadSurface gpuSurface;
    private LinearLayout adaptive;
    private TextView projectTitle;
    private TextView snapButton;
    private File pendingCadExport;

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
        root.addView(topBar(),matchWrap(Gravity.TOP,8,7,8,0));
        root.addView(mainTools(),wrap(Gravity.START|Gravity.CENTER_VERTICAL,8,0,0,0));
        root.addView(viewTools(),wrap(Gravity.END|Gravity.TOP,0,62,8,0));
        adaptive=adaptiveTools();adaptive.setVisibility(View.GONE);
        root.addView(adaptive,wrap(Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL,0,0,0,12));
        setContentView(root);
        cad.post(this::syncGpuMesh);
    }

    private View topBar(){
        LinearLayout b=card(false);b.setPadding(dp(4),dp(2),dp(4),dp(2));
        b.addView(action("⌂",42,()->status("پروژه‌ها")));
        projectTitle=label("چوب‌یار 3D",13,true);projectTitle.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams title=new LinearLayout.LayoutParams(0,dp(42),1);title.setMargins(dp(5),0,0,0);b.addView(projectTitle,title);
        b.addView(action("↶",42,()->{cad.undo();status("برگشت");}));
        b.addView(action("↷",42,()->status(cad.redoLastFeature())));
        b.addView(action("◷",42,cad::showHistoryManager));
        b.addView(action("⋯",42,this::more));return b;
    }

    private View mainTools(){
        LinearLayout b=card(true);b.setPadding(dp(2),dp(3),dp(2),dp(3));
        b.addView(tool("⌕","جستجو",this::search));
        b.addView(tool("✎","Sketch",cad::showShaprSketchMenu));
        b.addView(tool("＋","Add",cad::showShaprModelingToolsMenu));
        b.addView(tool("◇","Construct",cad::showPlaneManager));
        b.addView(tool("↗","Transform",this::transformTools));
        b.addView(tool("⌁","Tools",this::tools));return b;
    }

    private View viewTools(){
        LinearLayout b=card(true);b.setPadding(dp(2),dp(3),dp(2),dp(3));
        Cube cube=new Cube();b.addView(cube,new LinearLayout.LayoutParams(dp(44),dp(44)));
        b.addView(tool("◇","Fit",()->{cad.fitAll();syncGpuCamera();status("Fit");}));
        snapButton=tool("⌁","Snap",()->{cad.toggleSnap();updateSnap();});b.addView(snapButton);
        b.addView(tool("mm","Units",()->toast(cad.dualUnitSummary())));updateSnap();return b;
    }

    private LinearLayout adaptiveTools(){
        LinearLayout b=card(false);b.setPadding(dp(3),dp(2),dp(3),dp(2));return b;
    }

    private void workspaceChanged(String info,boolean exact,int tool){
        boolean selected=info!=null&&!info.trim().isEmpty()&&!info.startsWith("هیچ")&&!info.startsWith("اول")
                &&!info.contains("انتخاب نبود")&&!info.contains("انتخاب نشده")&&!info.contains("داخل کادر");
        if(adaptive!=null){if(selected)renderAdaptive(cad.selectionKind());adaptive.setVisibility(selected?View.VISIBLE:View.GONE);}
        syncGpuMesh();
    }

    private void syncGpuMesh(){
        if(gpuSurface==null||cad==null)return;
        double[] mesh=cad.gpuMesh();gpuSurface.setMesh(mesh);cad.setGpuBodyRendering(mesh.length>=9);syncGpuCamera();
    }

    private void syncGpuCamera(){if(gpuSurface!=null&&cad!=null)gpuSurface.setCameraState(cad.gpuCameraState());}

    private void renderAdaptive(String kind){
        adaptive.removeAllViews();String k=kind==null?"SKETCH":kind;
        if("EDGE".equals(k)){
            adaptive.addView(tool("⌒","Fillet",cad::showSelectedFillet));adaptive.addView(tool("／","Chamfer",cad::showSelectedChamfer));
            adaptive.addView(tool("⌨","اندازه",this::editDimension));
        }else if("FACE".equals(k)){
            adaptive.addView(tool("⇧","Offset Face",cad::showSelectedPushPull));adaptive.addView(tool("▣","Shell",cad::showSelectedShell));
            adaptive.addView(tool("▱","Sketch",()->status(cad.sketchOnSelectedFace())));
        }else if("BODY".equals(k)){
            adaptive.addView(tool("↗","Move",()->status(cad.showTransformGizmo())));adaptive.addView(tool("∪","Boolean",cad::showSolidManager));
            adaptive.addView(tool("⌨","Measure",cad::showSketchMeasureInspector));
        }else if("VERTEX".equals(k)){
            adaptive.addView(tool("↗","Move",()->status(cad.showTransformGizmo())));adaptive.addView(tool("⌨","Measure",cad::showSketchMeasureInspector));
        }else{
            adaptive.addView(tool("⌨","اندازه",this::editDimension));adaptive.addView(tool("⬆","Extrude",cad::showInteractiveExtrude));
        }
        adaptive.addView(tool("⌁","More",this::tools));
    }

    private void search(){
        String[] x={"Sketch","Extrude","Move / Rotate","Measure","Constraints","History","Plane","Snaps"};
        new AlertDialog.Builder(this).setTitle("جستجوی فرمان").setItems(x,(d,w)->{
            if(w==0)cad.showShaprSketchMenu();else if(w==1)cad.showShaprModelingToolsMenu();
            else if(w==2)status(cad.showTransformGizmo());else if(w==3)cad.showSketchMeasureInspector();
            else if(w==4)cad.showSmartConstraintMenu();else if(w==5)cad.showHistoryManager();
            else if(w==6)cad.showPlaneManager();else cad.showShaprSnappingOptions();
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
            if(w==0)status(cad.showTransformGizmo());
            else if(w==1)cad.showScaleTool();
            else if(w==2)cad.showMirrorTool();
            else cad.showLinearPatternTool();
        }).setNegativeButton("بستن",null).show();
    }

    private void more(){
        String[] x={"Items / Layers","Export STEP / STL","نمای بالا","نمای روبرو","نمای راست","نمای ایزومتریک","Snaps / Guides"};
        new AlertDialog.Builder(this).setTitle("چوب‌یار 3D").setItems(x,(d,w)->{
            if(w==0)showItems();else if(w==1)showCadExport();else if(w==2)setView("TOP");
            else if(w==3)setView("FRONT");else if(w==4)setView("RIGHT");
            else if(w==5)setView("ISO");else cad.showShaprSnappingOptions();
        }).show();
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
        if(requestCode!=REQUEST_EXPORT_CAD||resultCode!=RESULT_OK||data==null||data.getData()==null||pendingCadExport==null)return;
        try(FileInputStream in=new FileInputStream(pendingCadExport);OutputStream out=getContentResolver().openOutputStream(data.getData())){
            if(out==null)throw new IllegalStateException();byte[] buffer=new byte[65536];int n;while((n=in.read(buffer))>0)out.write(buffer,0,n);
            out.flush();toast("فایل CAD ذخیره شد");
        }catch(Exception e){toast("ذخیره فایل انجام نشد");}finally{pendingCadExport=null;}
    }

    private void setView(String view){cad.setStandardView(view);syncGpuCamera();}

    private void showItems(){
        String[] rows=cad.itemRows();
        if(rows.length==0){toast("هنوز Body ساخته نشده");return;}
        new AlertDialog.Builder(this).setTitle("Items • Bodies")
                .setMessage("یک Body را انتخاب کن؛ لمس طولانی با دکمه‌های پایین جایگزین شده تا روی گوشی هم دقیق باشد.")
                .setItems(rows,(d,w)->{status(cad.selectItem(w));showItemActions(w);})
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
    private void status(String s){
        // Project identity must stay stable; command feedback already exists in
        // the workspace status line and dialogs.
        if(projectTitle!=null)projectTitle.setText("چوب‌یار 3D");
    }
    private void toast(String s){Toast.makeText(this,s,Toast.LENGTH_LONG).show();}

    private LinearLayout card(boolean vertical){LinearLayout x=new LinearLayout(this);x.setOrientation(vertical?LinearLayout.VERTICAL:LinearLayout.HORIZONTAL);x.setGravity(Gravity.CENTER);x.setElevation(dp(4));x.setBackground(round(Color.argb(246,255,255,255),Color.rgb(222,226,232),14));return x;}
    private TextView tool(String icon,String text,Runnable r){TextView v=label(icon+"\n"+text,8,false);v.setGravity(Gravity.CENTER);v.setMinWidth(dp(46));v.setMinHeight(dp(43));v.setPadding(dp(2),dp(2),dp(2),dp(2));v.setOnClickListener(q->r.run());return v;}
    private TextView action(String text,int size,Runnable r){TextView v=label(text,17,false);v.setGravity(Gravity.CENTER);v.setMinWidth(dp(size));v.setMinHeight(dp(38));v.setOnClickListener(q->r.run());return v;}
    private TextView label(String s,float size,boolean bold){TextView v=new TextView(this);v.setText(s);v.setTextSize(size);v.setTextColor(Color.rgb(38,45,56));if(bold)v.setTypeface(null,Typeface.BOLD);return v;}
    private GradientDrawable round(int fill,int stroke,int radius){GradientDrawable d=new GradientDrawable();d.setColor(fill);d.setCornerRadius(dp(radius));d.setStroke(dp(1),stroke);return d;}
    private FrameLayout.LayoutParams matchWrap(int g,int l,int t,int r,int b){FrameLayout.LayoutParams p=new FrameLayout.LayoutParams(-1,-2,g);p.setMargins(dp(l),dp(t),dp(r),dp(b));return p;}
    private FrameLayout.LayoutParams wrap(int g,int l,int t,int r,int b){FrameLayout.LayoutParams p=new FrameLayout.LayoutParams(-2,-2,g);p.setMargins(dp(l),dp(t),dp(r),dp(b));return p;}
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

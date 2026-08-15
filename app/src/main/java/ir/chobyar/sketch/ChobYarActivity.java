package ir.chobyar.sketch;

import android.app.Activity;
import android.app.AlertDialog;
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

/** Single production workspace. No activity swapping and no reflection wiring. */
public final class ChobYarActivity extends Activity {
    private Shapr3DGuideCadCanvasView cad;
    private LinearLayout adaptive;
    private TextView projectTitle;
    private TextView snapButton;

    @Override protected void onCreate(Bundle state){
        super.onCreate(state);immersive();
        FrameLayout root=new FrameLayout(this);root.setBackgroundColor(Color.rgb(248,249,251));
        cad=new Shapr3DGuideCadCanvasView(this);
        cad.setStatusListener(this::status);
        cad.setDimensionEditListener(this::editDimension);
        cad.setWorkspaceListener(this::workspaceChanged);
        root.addView(cad,new FrameLayout.LayoutParams(-1,-1));
        root.addView(topBar(),matchWrap(Gravity.TOP,8,7,8,0));
        root.addView(mainTools(),wrap(Gravity.START|Gravity.CENTER_VERTICAL,8,0,0,0));
        root.addView(viewTools(),wrap(Gravity.END|Gravity.TOP,0,62,8,0));
        adaptive=adaptiveTools();adaptive.setVisibility(View.GONE);
        root.addView(adaptive,wrap(Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL,0,0,0,12));
        setContentView(root);
    }

    private View topBar(){
        LinearLayout b=card(false);b.setPadding(dp(4),dp(2),dp(4),dp(2));
        b.addView(action("⌂",42,()->status("پروژه‌ها")));
        projectTitle=label("چوب‌یار 3D",13,true);projectTitle.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams title=new LinearLayout.LayoutParams(0,dp(42),1);title.setMargins(dp(5),0,0,0);b.addView(projectTitle,title);
        b.addView(action("↶",42,()->{cad.undo();status("برگشت");}));
        b.addView(action("◷",42,cad::showHistoryManager));
        b.addView(action("⋯",42,this::more));return b;
    }

    private View mainTools(){
        LinearLayout b=card(true);b.setPadding(dp(2),dp(3),dp(2),dp(3));
        b.addView(tool("⌕","جستجو",this::search));
        b.addView(tool("✎","Sketch",cad::showShaprSketchMenu));
        b.addView(tool("＋","Add",cad::showShaprModelingToolsMenu));
        b.addView(tool("◇","Construct",cad::showPlaneManager));
        b.addView(tool("↗","Transform",()->status(cad.showTransformGizmo())));
        b.addView(tool("⌁","Tools",this::tools));return b;
    }

    private View viewTools(){
        LinearLayout b=card(true);b.setPadding(dp(2),dp(3),dp(2),dp(3));
        Cube cube=new Cube();b.addView(cube,new LinearLayout.LayoutParams(dp(44),dp(44)));
        b.addView(tool("◇","Fit",()->{cad.fitAll();status("Fit");}));
        snapButton=tool("⌁","Snap",()->{cad.toggleSnap();updateSnap();});b.addView(snapButton);
        b.addView(tool("mm","Units",()->toast(cad.dualUnitSummary())));updateSnap();return b;
    }

    private LinearLayout adaptiveTools(){
        LinearLayout b=card(false);b.setPadding(dp(3),dp(2),dp(3),dp(2));
        b.addView(tool("⌨","اندازه",this::editDimension));
        b.addView(tool("↗","Move",()->status(cad.showTransformGizmo())));
        b.addView(tool("⬆","Extrude",cad::showShaprModelingToolsMenu));
        b.addView(tool("⌁","More",this::tools));return b;
    }

    private void workspaceChanged(String info,boolean exact,int tool){
        boolean selected=info!=null&&!info.trim().isEmpty()&&!info.startsWith("هیچ")&&!info.startsWith("اول");
        if(adaptive!=null)adaptive.setVisibility(selected?View.VISIBLE:View.GONE);
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

    private void more(){
        String[] x={"Items / Layers","نمای بالا","نمای روبرو","نمای راست","نمای ایزومتریک","Snaps / Guides"};
        new AlertDialog.Builder(this).setTitle("چوب‌یار 3D").setItems(x,(d,w)->{
            if(w==0)showItems();else if(w==1)cad.setStandardView("TOP");
            else if(w==2)cad.setStandardView("FRONT");else if(w==3)cad.setStandardView("RIGHT");
            else if(w==4)cad.setStandardView("ISO");else cad.showShaprSnappingOptions();
        }).show();
    }

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
    private void status(String s){if(s!=null&&!s.trim().isEmpty()&&projectTitle!=null)projectTitle.setText(s.length()>34?s.substring(0,34)+"…":s);}
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

    private final class Cube extends View{
        Paint p=new Paint(1);Path a=new Path(),b=new Path(),c=new Path();int mode=0;
        Cube(){super(ChobYarActivity.this);setOnClickListener(v->{mode=(mode+1)%4;String[] m={"ISO","TOP","FRONT","RIGHT"};cad.setStandardView(m[mode]);invalidate();});}
        @Override protected void onDraw(Canvas x){super.onDraw(x);float w=getWidth(),h=getHeight();p.setStrokeWidth(dp(1));p.setStyle(Paint.Style.FILL);
            a.reset();a.moveTo(w*.2f,h*.35f);a.lineTo(w*.5f,h*.17f);a.lineTo(w*.8f,h*.35f);a.lineTo(w*.5f,h*.53f);a.close();p.setColor(Color.rgb(231,237,245));x.drawPath(a,p);
            b.reset();b.moveTo(w*.2f,h*.35f);b.lineTo(w*.5f,h*.53f);b.lineTo(w*.5f,h*.84f);b.lineTo(w*.2f,h*.66f);b.close();p.setColor(Color.rgb(218,227,239));x.drawPath(b,p);
            c.reset();c.moveTo(w*.5f,h*.53f);c.lineTo(w*.8f,h*.35f);c.lineTo(w*.8f,h*.66f);c.lineTo(w*.5f,h*.84f);c.close();p.setColor(Color.rgb(201,216,233));x.drawPath(c,p);p.setStyle(Paint.Style.STROKE);p.setColor(Color.rgb(82,99,120));x.drawPath(a,p);x.drawPath(b,p);x.drawPath(c,p);}
    }
}

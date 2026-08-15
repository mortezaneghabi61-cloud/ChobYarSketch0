package ir.chobyar.sketch;

import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Locale;

/**
 * Pen-first modeling shell for ChobYar 3D.
 *
 * The geometry engine stays independent from the UI. The workspace follows a
 * compact CAD pattern: a slim top bar, one left modeling menu, a selection-
 * adaptive tool strip beside it, and a small view/snapping cluster on the right.
 * Icons are original/simple glyphs rather than copied proprietary artwork.
 */
public class EasyMainActivity extends MainActivity {

    protected NativeBRepCadCanvasView easyCad;
    private TextView compactStatus;
    private LinearLayout adaptiveBar;
    private LinearLayout navigationBar;
    private Button snapButton;
    private Button adaptiveModelButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        installMinimalWorkspace();
    }

    private void installMinimalWorkspace() {
        try {
            Field cadField = MainActivity.class.getDeclaredField("cad");
            cadField.setAccessible(true);

            View content = findViewById(android.R.id.content);
            if (!(content instanceof ViewGroup)) return;
            ViewGroup contentGroup = (ViewGroup) content;
            if (contentGroup.getChildCount() == 0) return;
            View rootView = contentGroup.getChildAt(0);
            if (!(rootView instanceof FrameLayout)) return;
            FrameLayout root = (FrameLayout) rootView;

            easyCad = new NativeBRepCadCanvasView(this);
            wireWorkspaceCallbacks();
            cadField.set(this, easyCad);

            root.removeAllViews();
            root.setBackgroundColor(Color.rgb(247, 249, 252));
            root.addView(easyCad, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT));

            // Shapr-style chrome: top navigation, left main menu, adaptive menu,
            // and view/snapping controls kept away from the bottom canvas area.
            root.addView(makeTopBar(), frameMatchWrap(Gravity.TOP, 8, 7, 8, 0));
            root.addView(makeMainRail(), frameWrap(Gravity.START | Gravity.TOP, 8, 72, 0, 0));

            adaptiveBar = makeAdaptiveBar();
            adaptiveBar.setVisibility(View.GONE);
            root.addView(adaptiveBar, frameWrap(Gravity.START | Gravity.TOP, 72, 72, 0, 0));

            navigationBar = makeNavigationBar();
            navigationBar.setVisibility(View.VISIBLE);
            root.addView(navigationBar, frameWrap(Gravity.END | Gravity.TOP, 0, 72, 8, 0));

            compactStatus = new TextView(this);
            compactStatus.setText("آماده • cm + mm");
            compactStatus.setTextSize(10f);
            compactStatus.setTextColor(Color.rgb(78, 88, 102));
            compactStatus.setSingleLine(true);
            compactStatus.setPadding(dp(9), dp(4), dp(9), dp(4));
            compactStatus.setBackground(round(Color.argb(235,255,255,255), Color.rgb(222,227,234), 12));
            root.addView(compactStatus, frameWrap(Gravity.START | Gravity.BOTTOM, 8, 0, 0, 5));

            easyCad.dispatchWorkspaceState();
        } catch (Exception e) {
            Toast.makeText(this, "فضای skachmori فعال نشد", Toast.LENGTH_SHORT).show();
        }
    }

    /** Kept with this exact name because the OCCT activity rewires it after upgrading the canvas. */
    protected void wireWorkspaceCallbacks() {
        easyCad.setStatusListener(this::showStatus);
        easyCad.setDimensionEditListener(() -> invokeMain("showExactDimension", new Class<?>[0]));
        easyCad.setWorkspaceListener(this::onWorkspaceStateChanged);
    }

    private void onWorkspaceStateChanged(String info, boolean exactAvailable, int activeTool) {
        boolean hasSelection = info != null
                && !info.startsWith("هیچ")
                && !info.startsWith("اول")
                && !info.trim().isEmpty();

        // Selection-adaptive tools live beside the main menu, like a CAD context
        // menu. View/Snap controls remain independently available on the right.
        if (adaptiveBar != null) adaptiveBar.setVisibility(hasSelection ? View.VISIBLE : View.GONE);
        if (navigationBar != null) navigationBar.setVisibility(View.VISIBLE);
        if (adaptiveModelButton != null) {
            adaptiveModelButton.setText(easyCad != null && easyCad.is3DOverview() ? "✥\nEdit 3D" : "⬆\nExtrude");
        }
        if (hasSelection) showStatus(info);
    }

    private void showStatus(String text) {
        if (compactStatus != null && text != null && !text.trim().isEmpty()) compactStatus.setText(text);
    }

    private Object invokeMain(String name, Class<?>[] types, Object... args) {
        try {
            Method m = MainActivity.class.getDeclaredMethod(name, types);
            m.setAccessible(true);
            return m.invoke(this, args);
        } catch (Exception ignored) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // Top bar
    // ------------------------------------------------------------------

    private View makeTopBar() {
        LinearLayout bar = horizontalCard();
        bar.setPadding(dp(6), dp(2), dp(6), dp(2));

        ImageView logo = new ImageView(this);
        logo.setImageResource(R.mipmap.ic_launcher);
        logo.setContentDescription("skachmori");
        logo.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        bar.addView(logo, new LinearLayout.LayoutParams(dp(36), dp(36)));

        LinearLayout titleBox = new LinearLayout(this);
        titleBox.setOrientation(LinearLayout.VERTICAL);
        titleBox.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(this);
        title.setText("skachmori");
        title.setTextSize(14.5f);
        title.setTypeface(null, Typeface.BOLD);
        title.setTextColor(Color.rgb(27, 37, 50));
        titleBox.addView(title);

        TextView mode = new TextView(this);
        mode.setText("Modeling");
        mode.setTextSize(8.5f);
        mode.setTextColor(Color.rgb(112, 121, 134));
        titleBox.addView(mode);

        titleBox.setPadding(dp(4), 0, dp(4), 0);
        bar.addView(titleBox, new LinearLayout.LayoutParams(0, dp(42), 1f));

        bar.addView(topButton("↶", "Undo", () -> { easyCad.undo(); showStatus("یک مرحله برگشت"); }));
        bar.addView(topButton("⏱", "History", () -> easyCad.showHistoryManager()));
        bar.addView(topButton("⋯", "بیشتر", this::showMoreMenu));
        return bar;
    }

    // ------------------------------------------------------------------
    // Left main menu + selection-adaptive menu
    // ------------------------------------------------------------------

    private View makeMainRail() {
        LinearLayout rail = verticalCard();
        rail.setPadding(dp(2), dp(4), dp(2), dp(4));
        rail.addView(railButton("⌕", "Search", this::showCommandPalette));
        rail.addView(railButton("✎", "Sketch", () -> invokeMain("showSketchMenu", new Class<?>[0])));
        rail.addView(railButton("＋", "Add", this::showAddMenu));
        rail.addView(railButton("◇", "Construct", this::showConstructMenu));
        rail.addView(railButton("↗", "Transform", () -> invokeMain("showTransformMenu", new Class<?>[0])));
        rail.addView(railButton("⌁", "Tools", this::showUtilityMenu));
        return rail;
    }

    private void showCommandPalette() {
        String[] items = {
                "✎ Sketch",
                "⬆ Extrude",
                "◇ Construction Plane",
                "↗ Move / Rotate",
                "↔ Measure",
                "⌁ Constraints",
                "⏱ History",
                "⇩ Export DXF"
        };
        new AlertDialog.Builder(this).setTitle("Search Commands").setItems(items, (d,w) -> {
            if (w==0) invokeMain("showSketchMenu",new Class<?>[0]);
            else if (w==1) showQuickExtrudeDialog();
            else if (w==2) easyCad.showPlaneManager();
            else if (w==3) invokeMain("showTransformMenu",new Class<?>[0]);
            else if (w==4) { easyCad.setTool(CadCanvasView.TOOL_MEASURE); showStatus("اندازه‌گیری فعال شد"); }
            else if (w==5) easyCad.showSmartConstraintMenu();
            else if (w==6) easyCad.showHistoryManager();
            else invokeMain("exportDxf",new Class<?>[0]);
        }).setNegativeButton("بستن",null).show();
    }

    private void showAddMenu() {
        String[] items = {
                "⬆ Extrude / حجم از Sketch",
                "▣ Solid tools / Revolve • Sweep • Loft • Boolean",
                "✥ Edit 3D / Face • Edge",
                "⏱ History"
        };
        new AlertDialog.Builder(this).setTitle("Add / مدل‌سازی").setItems(items, (d,w) -> {
            if (w==0) showQuickExtrudeDialog();
            else if (w==1) easyCad.showSolidManager();
            else if (w==2) easyCad.showDirectManager();
            else easyCad.showHistoryManager();
        }).setNegativeButton("بستن",null).show();
    }

    private void showConstructMenu() {
        String[] items = {
                "◇ Plane / صفحه ساخت",
                easyCad.isShowGuides() ? "┼ Guides خاموش" : "┼ Guides روشن",
                easyCad.isShowAxes() ? "XYZ محورها مخفی" : "XYZ محورها روشن"
        };
        new AlertDialog.Builder(this).setTitle("Construct").setItems(items, (d,w) -> {
            if (w==0) easyCad.showPlaneManager();
            else if (w==1) { easyCad.toggleGuides(); showStatus(easyCad.isShowGuides()?"Guide روشن":"Guide خاموش"); }
            else { easyCad.toggleAxes(); showStatus(easyCad.isShowAxes()?"محورها روشن":"محورها مخفی"); }
        }).setNegativeButton("بستن",null).show();
    }

    private LinearLayout makeAdaptiveBar() {
        LinearLayout bar = verticalCard();
        bar.setPadding(dp(2),dp(3),dp(2),dp(3));
        bar.addView(compactAction("⌨","اندازه",() -> invokeMain("showExactDimension",new Class<?>[0])));
        adaptiveModelButton = compactAction("⬆","Extrude",this::contextualModelAction);
        bar.addView(adaptiveModelButton);
        bar.addView(compactAction("↗","تغییر",() -> invokeMain("showTransformMenu",new Class<?>[0])));
        bar.addView(compactAction("⌁","More",this::showSelectionMore));
        return bar;
    }

    private void showSelectionMore() {
        String[] items = {
                easyCad.is3DOverview()?"✥ Edit 3D":"⬆ Extrude / حجم",
                "⌁ روابط / Constraints",
                "⌫ حذف انتخاب",
                "⏱ History"
        };
        new AlertDialog.Builder(this).setTitle("ابزار انتخاب").setItems(items,(d,w)->{
            if(w==0) contextualModelAction();
            else if(w==1) easyCad.showSmartConstraintMenu();
            else if(w==2) invokeMain("deleteSelectedQuick",new Class<?>[0]);
            else easyCad.showHistoryManager();
        }).setNegativeButton("بستن",null).show();
    }

    /** Selected 2D closed sketch -> Extrude immediately; 3D selection -> direct edit. */
    private void contextualModelAction() {
        if (easyCad == null) return;
        if (easyCad.is3DOverview()) easyCad.showDirectManager();
        else showQuickExtrudeDialog();
    }

    // ------------------------------------------------------------------
    // Right view/snapping cluster
    // ------------------------------------------------------------------

    private LinearLayout makeNavigationBar() {
        LinearLayout bar = verticalCard();
        bar.setPadding(dp(2),dp(3),dp(2),dp(3));
        bar.addView(navButton("XYZ","View",this::showViewCubeMenu));
        bar.addView(navButton("◇","Fit",() -> { easyCad.fitAll(); showStatus("Fit"); }));
        snapButton = navButton("⌁","Snap",this::toggleSnap);
        bar.addView(snapButton);
        bar.addView(navButton("mm","Units",() -> Toast.makeText(this,easyCad.dualUnitSummary(),Toast.LENGTH_LONG).show()));
        updateSnapButton();
        return bar;
    }

    private void showViewCubeMenu() {
        if (easyCad == null) return;
        String[] items = {
                "◇ ایزومتریک / Isometric",
                "Z  بالا / XY",
                "Y  روبرو / XZ",
                "X  راست / YZ",
                easyCad.is3DOverview() ? "□ برگشت به Sketch 2D" : "▣ ورود به نمای 3D",
                "⌗ Fit / نمایش همه"
        };
        new AlertDialog.Builder(this).setTitle("View Cube / نما").setItems(items, (d,w) -> {
            if (w==0) standardView("ISO");
            else if (w==1) standardView("TOP");
            else if (w==2) standardView("FRONT");
            else if (w==3) standardView("RIGHT");
            else if (w==4) showStatus(easyCad.toggle3DOverview());
            else { easyCad.fitAll(); showStatus("تمام مدل در صفحه"); }
        }).setNegativeButton("بستن",null).show();
    }

    private void standardView(String view) {
        if (easyCad == null) return;
        showStatus(applyStandardView(view));
    }

    private String applyStandardView(String view) {
        try {
            Field overview = SpatialCadCanvasView.class.getDeclaredField("overview3D");
            Field yaw = SpatialCadCanvasView.class.getDeclaredField("cameraYaw");
            Field pitch = SpatialCadCanvasView.class.getDeclaredField("cameraPitch");
            Field orbiting = SpatialCadCanvasView.class.getDeclaredField("orbiting");
            overview.setAccessible(true); yaw.setAccessible(true); pitch.setAccessible(true); orbiting.setAccessible(true);
            overview.setBoolean(easyCad, true); orbiting.setBoolean(easyCad, false);

            String key = view == null ? "ISO" : view.toUpperCase(Locale.US);
            if ("TOP".equals(key)) { yaw.setFloat(easyCad,0f); pitch.setFloat(easyCad,0f); easyCad.invalidate(); return "نمای بالا • XY • محور Z"; }
            if ("FRONT".equals(key)) { yaw.setFloat(easyCad,0f); pitch.setFloat(easyCad,90f); easyCad.invalidate(); return "نمای روبرو • XZ • محور Y"; }
            if ("RIGHT".equals(key)) { yaw.setFloat(easyCad,90f); pitch.setFloat(easyCad,90f); easyCad.invalidate(); return "نمای راست • YZ • محور X"; }
            yaw.setFloat(easyCad,38f); pitch.setFloat(easyCad,24f); easyCad.invalidate(); return "نمای ایزومتریک 3D";
        } catch (Exception e) {
            easyCad.showPlaneManager();
            return "View / Plane";
        }
    }

    private void toggleSnap() {
        easyCad.toggleSnap();
        updateSnapButton();
        showStatus(easyCad.isSnapEnabled()?"Snap روشن":"Snap خاموش");
    }

    private void updateSnapButton() {
        if (snapButton==null || easyCad==null) return;
        snapButton.setText(easyCad.isSnapEnabled()?"⌁\nSnap":"○\nSnap");
        snapButton.setTextColor(easyCad.isSnapEnabled()?Color.rgb(42,100,205):Color.rgb(100,108,120));
    }

    // ------------------------------------------------------------------
    // Modeling dialogs
    // ------------------------------------------------------------------

    private void showQuickExtrudeDialog() {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setText("20mm");
        input.setSelectAllOnFocus(true);
        new AlertDialog.Builder(this)
                .setTitle("Extrude / حجم • cm / mm")
                .setMessage("یک پروفایل بسته را انتخاب کن و ارتفاع را وارد کن؛ مثال 20mm یا 2cm. مقدار منفی جهت Extrude را برعکس می‌کند.")
                .setView(input)
                .setPositiveButton("ساخت حجم", (d,w) -> {
                    try {
                        float mm = parseLengthMm(input.getText().toString());
                        if (Math.abs(mm) < 0.0001f) { showStatus("ارتفاع حجم نباید صفر باشد"); return; }
                        String result = easyCad.extrudeSelectedBody(mm / 10f);
                        showStatus(result);
                        if (result != null && result.contains("ساخته")) standardView("ISO");
                        easyCad.dispatchWorkspaceState();
                    } catch (Exception e) {
                        showStatus("اندازه حجم درست وارد نشده");
                    }
                })
                .setNegativeButton("لغو", null)
                .show();
    }

    private static float parseLengthMm(String raw) {
        String s = normalizeDigits(raw).toLowerCase(Locale.US).trim().replace('،','.').replace(',','.');
        boolean mm = s.endsWith("mm") || s.endsWith("میلیمتر") || s.endsWith("میلی‌متر");
        s = s.replace("میلی‌متر","").replace("میلیمتر","").replace("سانتی‌متر","").replace("سانتیمتر","")
                .replace("mm","").replace("cm","").trim();
        float v = Float.parseFloat(s);
        return mm ? v : v * 10f;
    }

    private static String normalizeDigits(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder();
        for (int i=0;i<s.length();i++) {
            char c=s.charAt(i);
            if(c>='۰'&&c<='۹') b.append((char)('0'+c-'۰'));
            else if(c>='٠'&&c<='٩') b.append((char)('0'+c-'٠'));
            else b.append(c);
        }
        return b.toString().trim();
    }

    private void showUtilityMenu() {
        String[] items = {
                "⌁ ابزارهای عمومی",
                "↔ Measure / اندازه‌گیری",
                "⌁ روابط و Constraint",
                "✥ Edit 3D",
                "⏱ History",
                "⇩ خروجی DXF"
        };
        new AlertDialog.Builder(this).setTitle("Tools").setItems(items, (d,w) -> {
            if (w==0) invokeMain("showToolsMenu",new Class<?>[0]);
            else if (w==1) { easyCad.setTool(CadCanvasView.TOOL_MEASURE); showStatus("اندازه‌گیری فعال شد"); }
            else if (w==2) easyCad.showSmartConstraintMenu();
            else if (w==3) easyCad.showDirectManager();
            else if (w==4) easyCad.showHistoryManager();
            else invokeMain("exportDxf",new Class<?>[0]);
        }).setNegativeButton("بستن",null).show();
    }

    private void showMoreMenu() {
        String[] items = {
                "⇩ خروجی DXF",
                "cm/mm واحدهای اندازه",
                easyCad.isShowGrid()?"# Grid خاموش":"# Grid روشن",
                easyCad.isShowAxes()?"XYZ محورها مخفی":"XYZ محورها روشن",
                easyCad.isShowGuides()?"┼ Guide مخفی":"┼ Guide روشن",
                "◇ Plane / Construction",
                "⋯ تنظیمات پیشرفته"
        };
        new AlertDialog.Builder(this).setTitle("skachmori").setItems(items,(d,w)->{
            if(w==0) invokeMain("exportDxf",new Class<?>[0]);
            else if(w==1) Toast.makeText(this,easyCad.dualUnitSummary(),Toast.LENGTH_LONG).show();
            else if(w==2){easyCad.toggleGrid();showStatus(easyCad.isShowGrid()?"Grid روشن":"Grid خاموش");}
            else if(w==3){easyCad.toggleAxes();showStatus(easyCad.isShowAxes()?"محورها روشن":"محورها مخفی");}
            else if(w==4){easyCad.toggleGuides();showStatus(easyCad.isShowGuides()?"Guide روشن":"Guide مخفی");}
            else if(w==5) easyCad.showPlaneManager();
            else invokeMain("showMoreMenu",new Class<?>[0]);
        }).setNegativeButton("بستن",null).show();
    }

    // ------------------------------------------------------------------
    // UI helpers
    // ------------------------------------------------------------------

    private LinearLayout horizontalCard() {
        LinearLayout box=new LinearLayout(this);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setBackground(round(Color.argb(248,255,255,255),Color.rgb(216,222,230),16));
        box.setElevation(dp(4));
        return box;
    }

    private LinearLayout verticalCard() {
        LinearLayout box=new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(dp(2),dp(3),dp(2),dp(3));
        box.setBackground(round(Color.argb(248,255,255,255),Color.rgb(216,222,230),16));
        box.setElevation(dp(4));
        return box;
    }

    private Button topButton(String text,String description,Runnable action) {
        Button b=new Button(this);
        b.setText(text);
        b.setContentDescription(description);
        b.setTextSize(text.length()>2?10f:17f);
        b.setTextColor(Color.rgb(54,64,78));
        b.setAllCaps(false);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(1),0,dp(1),0);
        b.setMinWidth(dp(38));b.setMinimumWidth(dp(38));
        b.setMinHeight(dp(38));b.setMinimumHeight(dp(38));
        b.setBackground(round(Color.TRANSPARENT,Color.TRANSPARENT,10));
        b.setOnClickListener(v->action.run());
        return b;
    }

    private Button railButton(String icon,String label,Runnable action) {
        Button b=new Button(this);
        b.setText(icon+"\n"+label);
        b.setTextSize(label.length()>8?8.2f:9f);
        b.setTextColor(Color.rgb(48,58,72));
        b.setAllCaps(false);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(1),dp(1),dp(1),dp(1));
        b.setMinWidth(dp(54));b.setMinimumWidth(dp(54));
        b.setMinHeight(dp(47));b.setMinimumHeight(dp(47));
        b.setBackground(round(Color.TRANSPARENT,Color.TRANSPARENT,10));
        b.setOnClickListener(v->action.run());
        return b;
    }

    private Button compactAction(String icon,String label,Runnable action) {
        Button b=railButton(icon,label,action);
        b.setMinWidth(dp(58));b.setMinimumWidth(dp(58));
        b.setMinHeight(dp(45));b.setMinimumHeight(dp(45));
        b.setTextColor(Color.rgb(37,91,174));
        return b;
    }

    private Button navButton(String icon,String label,Runnable action) {
        Button b=new Button(this);
        b.setText(icon+"\n"+label);
        b.setTextSize(icon.length()>2?8.5f:9f);
        b.setTextColor(Color.rgb(58,68,82));
        b.setAllCaps(false);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(1),dp(1),dp(1),dp(1));
        b.setMinWidth(dp(49));b.setMinimumWidth(dp(49));
        b.setMinHeight(dp(44));b.setMinimumHeight(dp(44));
        b.setBackground(round(Color.TRANSPARENT,Color.TRANSPARENT,10));
        b.setOnClickListener(v->action.run());
        return b;
    }

    private GradientDrawable round(int fill,int stroke,int radiusDp) {
        GradientDrawable d=new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(dp(radiusDp));
        if(Color.alpha(stroke)>0)d.setStroke(dp(1),stroke);
        return d;
    }

    private FrameLayout.LayoutParams frameMatchWrap(int gravity,int left,int top,int right,int bottom) {
        FrameLayout.LayoutParams p=new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,FrameLayout.LayoutParams.WRAP_CONTENT,gravity);
        p.setMargins(dp(left),dp(top),dp(right),dp(bottom));
        return p;
    }

    private FrameLayout.LayoutParams frameWrap(int gravity,int left,int top,int right,int bottom) {
        FrameLayout.LayoutParams p=new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT,FrameLayout.LayoutParams.WRAP_CONTENT,gravity);
        p.setMargins(dp(left),dp(top),dp(right),dp(bottom));
        return p;
    }

    private int dp(int value) { return Math.round(value*getResources().getDisplayMetrics().density); }
}

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

            // Full-canvas CAD chrome. Controls float over the model and use the
            // smallest comfortable pen/touch targets instead of consuming the canvas.
            root.addView(makeTopBar(), frameMatchWrap(Gravity.TOP, 7, 6, 7, 0));
            root.addView(makeMainRail(), frameWrap(Gravity.START | Gravity.CENTER_VERTICAL, 7, 0, 0, 0));

            adaptiveBar = makeAdaptiveBar();
            adaptiveBar.setVisibility(View.GONE);
            root.addView(adaptiveBar, frameWrap(Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL, 0, 0, 0, 10));

            navigationBar = makeNavigationBar();
            navigationBar.setVisibility(View.VISIBLE);
            root.addView(navigationBar, frameWrap(Gravity.END | Gravity.TOP, 0, 58, 7, 0));

            compactStatus = new TextView(this);
            compactStatus.setText("Ready • cm + mm");
            compactStatus.setTextSize(9f);
            compactStatus.setTextColor(Color.rgb(78, 88, 102));
            compactStatus.setSingleLine(true);
            compactStatus.setMaxWidth(dp(210));
            compactStatus.setEllipsize(android.text.TextUtils.TruncateAt.END);
            compactStatus.setPadding(dp(8), dp(3), dp(8), dp(3));
            compactStatus.setBackground(round(Color.argb(232,255,255,255), Color.rgb(225,229,235), 10));
            root.addView(compactStatus, frameWrap(Gravity.START | Gravity.BOTTOM, 7, 0, 0, 7));

            easyCad.dispatchWorkspaceState();
        } catch (Exception e) {
            Toast.makeText(this, "text ChobYar 3D text text", Toast.LENGTH_SHORT).show();
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
                && !info.startsWith("None")
                && !info.startsWith("First")
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
        bar.setPadding(dp(3), dp(1), dp(3), dp(1));

        bar.addView(topButton("⌂", "Projecttext", () -> showStatus("Project ChobYar 3D")));

        LinearLayout titleBox = new LinearLayout(this);
        titleBox.setOrientation(LinearLayout.VERTICAL);
        titleBox.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(this);
        title.setText("ChobYar 3D");
        title.setTextSize(12.5f);
        title.setTypeface(null, Typeface.BOLD);
        title.setTextColor(Color.rgb(27, 37, 50));
        titleBox.addView(title);

        TextView mode = new TextView(this);
        mode.setText("Modeltext text");
        mode.setTextSize(7.5f);
        mode.setTextColor(Color.rgb(112, 121, 134));
        titleBox.addView(mode);

        titleBox.setPadding(dp(3), 0, dp(3), 0);
        bar.addView(titleBox, new LinearLayout.LayoutParams(0, dp(38), 1f));

        bar.addView(topButton("↶", "Undo", () -> { easyCad.undo(); showStatus("text text text"); }));
        bar.addView(topButton("⏱", "History", () -> easyCad.showHistoryManager()));
        bar.addView(topButton("⋯", "text", this::showMoreMenu));
        return bar;
    }

    // ------------------------------------------------------------------
    // Left main menu + selection-adaptive menu
    // ------------------------------------------------------------------

    private View makeMainRail() {
        LinearLayout rail = verticalCard();
        rail.setPadding(dp(1), dp(2), dp(1), dp(2));
        rail.addView(railButton("⌕", "Search", this::showCommandPalette));
        rail.addView(railButton("✎", "Sketch", () -> invokeMain("showSketchMenu", new Class<?>[0])));
        rail.addView(railButton("＋", "Add", this::showAddMenu));
        rail.addView(railButton("◇", "Create", this::showConstructMenu));
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
            else if (w==4) { easyCad.setTool(CadCanvasView.TOOL_MEASURE); showStatus("Measure activated"); }
            else if (w==5) easyCad.showSmartConstraintMenu();
            else if (w==6) easyCad.showHistoryManager();
            else invokeMain("exportDxf",new Class<?>[0]);
        }).setNegativeButton("Close",null).show();
    }

    private void showAddMenu() {
        String[] items = {
                "⬆ Extrude / text text Sketch",
                "▣ Solid tools / Revolve • Sweep • Loft • Boolean",
                "✥ Edit 3D / Face • Edge",
                "⏱ History"
        };
        new AlertDialog.Builder(this).setTitle("Add / Modeltext").setItems(items, (d,w) -> {
            if (w==0) showQuickExtrudeDialog();
            else if (w==1) easyCad.showSolidManager();
            else if (w==2) easyCad.showDirectManager();
            else easyCad.showHistoryManager();
        }).setNegativeButton("Close",null).show();
    }

    private void showConstructMenu() {
        String[] items = {
                "◇ Plane / Plane Create",
                easyCad.isShowGuides() ? "┼ Guides Off" : "┼ Guides On",
                easyCad.isShowAxes() ? "XYZ Axes Hide" : "XYZ Axes On"
        };
        new AlertDialog.Builder(this).setTitle("Construct").setItems(items, (d,w) -> {
            if (w==0) easyCad.showPlaneManager();
            else if (w==1) { easyCad.toggleGuides(); showStatus(easyCad.isShowGuides()?"Guide On":"Guide Off"); }
            else { easyCad.toggleAxes(); showStatus(easyCad.isShowAxes()?"Axes On":"Axes Hide"); }
        }).setNegativeButton("Close",null).show();
    }

    private LinearLayout makeAdaptiveBar() {
        LinearLayout bar = horizontalCard();
        bar.setPadding(dp(3),dp(2),dp(3),dp(2));
        bar.addView(compactAction("⌨","Dimension",() -> invokeMain("showExactDimension",new Class<?>[0])));
        adaptiveModelButton = compactAction("⬆","Extrude",this::contextualModelAction);
        bar.addView(adaptiveModelButton);
        bar.addView(compactAction("↗","Transform",() -> showStatus(easyCad.showTransformGizmo())));
        bar.addView(compactAction("⌁","More",this::showSelectionMore));
        return bar;
    }

    private void showSelectionMore() {
        String[] items = {
                easyCad.is3DOverview()?"✥ Edit 3D":"⬆ Extrude / text",
                "⌁ Constraints / Constraints",
                "⌫ Delete Selection",
                "⏱ History"
        };
        new AlertDialog.Builder(this).setTitle("Tools Selection").setItems(items,(d,w)->{
            if(w==0) contextualModelAction();
            else if(w==1) easyCad.showSmartConstraintMenu();
            else if(w==2) invokeMain("deleteSelectedQuick",new Class<?>[0]);
            else easyCad.showHistoryManager();
        }).setNegativeButton("Close",null).show();
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
        bar.setPadding(dp(1),dp(2),dp(1),dp(2));
        bar.addView(navButton("◈","View",this::showViewCubeMenu));
        bar.addView(navButton("◇","Fit",() -> { easyCad.fitAll(); showStatus("Show text Model"); }));
        snapButton = navButton("⌁","Snap",this::toggleSnap);
        bar.addView(snapButton);
        bar.addView(navButton("mm","text",() -> Toast.makeText(this,easyCad.dualUnitSummary(),Toast.LENGTH_LONG).show()));
        updateSnapButton();
        return bar;
    }

    private void showViewCubeMenu() {
        if (easyCad == null) return;
        String[] items = {
                "◇ text / Isometric",
                "Z  Top / XY",
                "Y  Front / XZ",
                "X  Right / YZ",
                easyCad.is3DOverview() ? "□ text text Sketch 2D" : "▣ text text 3D View",
                "⌗ Fit / Show All"
        };
        new AlertDialog.Builder(this).setTitle("View Cube / View").setItems(items, (d,w) -> {
            if (w==0) standardView("ISO");
            else if (w==1) standardView("TOP");
            else if (w==2) standardView("FRONT");
            else if (w==3) standardView("RIGHT");
            else if (w==4) showStatus(easyCad.toggle3DOverview());
            else { easyCad.fitAll(); showStatus("text Model text Plane"); }
        }).setNegativeButton("Close",null).show();
    }

    private void standardView(String view) {
        if (easyCad == null) return;
        showStatus(applyStandardView(view));
    }

    private String applyStandardView(String view) {
        String key = view == null ? "ISO" : view.toUpperCase(Locale.US);
        String result = easyCad.setStandardView(key);
        if ("TOP".equals(key)) return "Top View • XY • Axis Z";
        if ("FRONT".equals(key)) return "Front View • XZ • Axis Y";
        if ("RIGHT".equals(key)) return "Right View • YZ • Axis X";
        return result == null || result.trim().isEmpty() ? "Isometric View 3D" : result;
    }

    private void toggleSnap() {
        easyCad.toggleSnap();
        updateSnapButton();
        showStatus(easyCad.isSnapEnabled()?"Snap On":"Snap Off");
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
                .setTitle("Extrude / text • cm / mm")
                .setMessage("text text text text Selection text text Height text text text; text 20mm text 2cm. text text text Extrude text text text.")
                .setView(input)
                .setPositiveButton("Create text", (d,w) -> {
                    try {
                        float mm = parseLengthMm(input.getText().toString());
                        if (Math.abs(mm) < 0.0001f) { showStatus("Height text text text text"); return; }
                        String result = easyCad.extrudeSelectedBody(mm / 10f);
                        showStatus(result);
                        if (result != null && result.contains("created")) standardView("ISO");
                        easyCad.dispatchWorkspaceState();
                    } catch (Exception e) {
                        showStatus("Dimension text was entered incorrectly");
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private static float parseLengthMm(String raw) {
        String s = normalizeDigits(raw).toLowerCase(Locale.US).trim().replace(',','.');
        boolean mm = s.endsWith("mm") || s.endsWith("mm") || s.endsWith("mm");
        s = s.replace("mm","").replace("mm","").replace("cm","").replace("cm","")
                .replace("mm","").replace("cm","").trim();
        float v = Float.parseFloat(s);
        return mm ? v : v * 10f;
    }

    private static String normalizeDigits(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder();
        for (int i=0;i<s.length();i++) {
            char c=s.charAt(i);
            b.append(c);
        }
        return b.toString().trim();
    }

    private void showUtilityMenu() {
        String[] items = {
                "⌁ Toolstext text",
                "↔ Measure / Measure",
                "⌁ Constraints text Constraint",
                "✥ Edit 3D",
                "⏱ History",
                "⇩ text DXF"
        };
        new AlertDialog.Builder(this).setTitle("Tools").setItems(items, (d,w) -> {
            if (w==0) invokeMain("showToolsMenu",new Class<?>[0]);
            else if (w==1) { easyCad.setTool(CadCanvasView.TOOL_MEASURE); showStatus("Measure activated"); }
            else if (w==2) easyCad.showSmartConstraintMenu();
            else if (w==3) easyCad.showDirectManager();
            else if (w==4) easyCad.showHistoryManager();
            else invokeMain("exportDxf",new Class<?>[0]);
        }).setNegativeButton("Close",null).show();
    }

    private void showMoreMenu() {
        String[] items = {
                "⇩ text DXF",
                "cm/mm text Dimension",
                easyCad.isShowGrid()?"# Grid Off":"# Grid On",
                easyCad.isShowAxes()?"XYZ Axes Hide":"XYZ Axes On",
                easyCad.isShowGuides()?"┼ Guide Hide":"┼ Guide On",
                "◇ Plane / Construction",
                "⋯ text text"
        };
        new AlertDialog.Builder(this).setTitle("ChobYar 3D").setItems(items,(d,w)->{
            if(w==0) invokeMain("exportDxf",new Class<?>[0]);
            else if(w==1) Toast.makeText(this,easyCad.dualUnitSummary(),Toast.LENGTH_LONG).show();
            else if(w==2){easyCad.toggleGrid();showStatus(easyCad.isShowGrid()?"Grid On":"Grid Off");}
            else if(w==3){easyCad.toggleAxes();showStatus(easyCad.isShowAxes()?"Axes On":"Axes Hide");}
            else if(w==4){easyCad.toggleGuides();showStatus(easyCad.isShowGuides()?"Guide On":"Guide Hide");}
            else if(w==5) easyCad.showPlaneManager();
            else invokeMain("showMoreMenu",new Class<?>[0]);
        }).setNegativeButton("Close",null).show();
    }

    // ------------------------------------------------------------------
    // UI helpers
    // ------------------------------------------------------------------

    private LinearLayout horizontalCard() {
        LinearLayout box=new LinearLayout(this);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setBackground(round(Color.argb(242,255,255,255),Color.rgb(224,228,234),12));
        box.setElevation(dp(3));
        return box;
    }

    private LinearLayout verticalCard() {
        LinearLayout box=new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(dp(1),dp(2),dp(1),dp(2));
        box.setBackground(round(Color.argb(242,255,255,255),Color.rgb(224,228,234),12));
        box.setElevation(dp(3));
        return box;
    }

    private Button topButton(String text,String description,Runnable action) {
        Button b=new Button(this);
        b.setText(text);
        b.setContentDescription(description);
        b.setTextSize(text.length()>2?9f:16f);
        b.setTextColor(Color.rgb(54,64,78));
        b.setAllCaps(false);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(1),0,dp(1),0);
        b.setMinWidth(dp(36));b.setMinimumWidth(dp(36));
        b.setMinHeight(dp(36));b.setMinimumHeight(dp(36));
        b.setBackground(round(Color.TRANSPARENT,Color.TRANSPARENT,10));
        b.setOnClickListener(v->action.run());
        return b;
    }

    private Button railButton(String icon,String label,Runnable action) {
        Button b=new Button(this);
        b.setText(icon+"\n"+label);
        b.setTextSize(8f);
        b.setTextColor(Color.rgb(48,58,72));
        b.setAllCaps(false);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(1),dp(1),dp(1),dp(1));
        b.setMinWidth(dp(43));b.setMinimumWidth(dp(43));
        b.setMinHeight(dp(40));b.setMinimumHeight(dp(40));
        b.setBackground(round(Color.TRANSPARENT,Color.TRANSPARENT,10));
        b.setOnClickListener(v->action.run());
        return b;
    }

    private Button compactAction(String icon,String label,Runnable action) {
        Button b=railButton(icon,label,action);
        b.setMinWidth(dp(46));b.setMinimumWidth(dp(46));
        b.setMinHeight(dp(38));b.setMinimumHeight(dp(38));
        b.setTextColor(Color.rgb(37,91,174));
        return b;
    }

    private Button navButton(String icon,String label,Runnable action) {
        Button b=new Button(this);
        b.setText(icon+"\n"+label);
        b.setTextSize(8f);
        b.setTextColor(Color.rgb(58,68,82));
        b.setAllCaps(false);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(1),dp(1),dp(1),dp(1));
        b.setMinWidth(dp(43));b.setMinimumWidth(dp(43));
        b.setMinHeight(dp(39));b.setMinimumHeight(dp(39));
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

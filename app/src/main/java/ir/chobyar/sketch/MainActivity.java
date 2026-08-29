package ir.chobyar.sketch;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * ChobYar modeling workspace.
 *
 * The arrangement follows the efficient modeling-space pattern used by modern
 * direct CAD apps: a slim top bar, a floating main tool rail on the left,
 * selection-adaptive actions beside it, sketch constraints on the right, and
 * view/snapping/unit controls in the lower-right corner. All icons here are
 * ChobYar-owned text/vector-style symbols rather than copied third-party assets.
 */
public class MainActivity extends Activity {

    private static final int REQUEST_EXPORT_DXF = 1001;

    protected ChobYarShaprCanvasView cad;
    private TextView status;
    private LinearLayout adaptiveRail;
    private LinearLayout constraintRail;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        enterImmersiveMode();

        cad = new ShaprLabCanvasView(this);
        cad.setStatusListener(this::say);
        cad.setDimensionEditListener(this::showExactDimension);
        cad.setWorkspaceListener(this::onWorkspaceStateChanged);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.rgb(244, 246, 249));
        root.addView(cad, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        root.addView(makeTopBar(), frameMatchWrap(Gravity.TOP, 10, 8, 10, 0));
        root.addView(makeMainRail(), frameWrap(Gravity.START | Gravity.TOP, 10, 74, 0, 0));

        adaptiveRail = makeAdaptiveRail();
        adaptiveRail.setVisibility(View.GONE);
        root.addView(adaptiveRail, frameWrap(Gravity.START | Gravity.TOP, 78, 74, 0, 0));

        constraintRail = makeConstraintRail();
        root.addView(constraintRail, frameWrap(Gravity.END | Gravity.CENTER_VERTICAL, 0, 0, 10, 0));

        root.addView(makeModeRail(), frameWrap(Gravity.START | Gravity.BOTTOM, 10, 0, 0, 20));
        root.addView(makeViewRail(), frameWrap(Gravity.END | Gravity.BOTTOM, 0, 0, 10, 20));

        status = new TextView(this);
        status.setText("cm | text/touch: text text • text text: Zoom/Pan");
        status.setTextSize(11);
        status.setTextColor(Color.rgb(65, 72, 82));
        status.setGravity(Gravity.CENTER);
        status.setPadding(dp(12), dp(5), dp(12), dp(5));
        status.setBackground(round(Color.argb(235, 255, 255, 255), Color.rgb(220, 224, 230), 16));
        root.addView(status, frameWrap(Gravity.TOP | Gravity.CENTER_HORIZONTAL, 0, 66, 0, 0));

        setContentView(root);
        cad.dispatchWorkspaceState();
    }

    private View makeTopBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(5), dp(3), dp(5), dp(3));
        bar.setBackground(round(Color.argb(247,255,255,255), Color.rgb(218,223,230), 17));
        bar.setElevation(dp(5));

        bar.addView(iconBtn("⌂", "text", () -> Toast.makeText(this, "Project ChobYar", Toast.LENGTH_SHORT).show()));

        TextView title = new TextView(this);
        title.setText("ChobYar  •  Modeltext");
        title.setTextSize(14);
        title.setTextColor(Color.rgb(35, 42, 52));
        title.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, dp(46), 1f);
        bar.addView(title, tp);

        bar.addView(iconBtn("↶", "Undo", () -> { cad.undo(); say("text text text"); }));
        bar.addView(iconBtn("⇩", "text DXF", this::exportDxf));
        bar.addView(iconBtn("⋯", "text", this::showMoreMenu));
        return bar;
    }

    private View makeMainRail() {
        LinearLayout rail = verticalRail();
        rail.addView(railBtn("⌕", "Tools", this::showAllTools));
        rail.addView(railBtn("✎", "Sketch", this::showSketchMenu));
        rail.addView(railBtn("＋", "Add", this::showInsertMenu));
        rail.addView(railBtn("⌖", "Create", this::showConstructMenu));
        rail.addView(railBtn("↗", "Transform", this::showTransformMenu));
        rail.addView(railBtn("⌁", "Tools", this::showToolsMenu));
        return rail;
    }

    private LinearLayout makeAdaptiveRail() {
        LinearLayout rail = verticalRail();
        rail.setBackground(round(Color.argb(248, 235, 244, 255), Color.rgb(145, 181, 235), 17));
        rail.addView(railBtn("⌨", "Dimension", this::showExactDimension));
        rail.addView(railBtn("⌖", "Snap Move", () -> say(cad.beginAnchorMove())));
        rail.addView(railBtn("⟳", "text", () -> promptCommand("text — degrees", "text: 45", "ROTATE ")));
        rail.addView(railBtn("↕", "Offset", () -> promptCommand("Offset — cm", "text: 1.8", "OFFSET ")));
        rail.addView(railBtn("⋯", "text", this::showTransformMenu));
        rail.addView(railBtn("⌫", "Delete", this::deleteSelectedQuick));
        return rail;
    }

    private LinearLayout makeConstraintRail() {
        LinearLayout rail = verticalRail();
        rail.addView(railBtn("⌁", "Snap", () -> {
            cad.toggleSnap();
            say(cad.isSnapEnabled() ? "Snap On" : "Snap Off");
        }));
        rail.addView(railBtn("⊥", "Ortho", () -> {
            cad.toggleOrtho();
            say(cad.isOrthoEnabled() ? "Lock Horizontal/Vertical On" : "Lock Horizontal/Vertical Off");
        }));
        rail.addView(railBtn("#", "Grid", () -> {
            cad.toggleGrid();
            say(cad.isShowGrid() ? "Grid On" : "Grid Off");
        }));
        return rail;
    }

    private View makeModeRail() {
        LinearLayout rail = verticalRail();
        rail.addView(railBtn("↔", "Dimension", () -> setTool(CadCanvasView.TOOL_MEASURE, "Measure")));
        rail.addView(railBtn("☑", "textSelection", () -> say(cad.toggleMultiSelectMode())));
        return rail;
    }

    private View makeViewRail() {
        LinearLayout rail = verticalRail();
        rail.addView(railBtn("◇", "Fit", () -> { cad.fitAll(); say("text text text Plane"); }));
        rail.addView(railBtn("⌁", "Snap", this::showSnapMenu));
        rail.addView(railBtn("cm", "text", () -> Toast.makeText(this, "text text: cm (cm)", Toast.LENGTH_SHORT).show()));
        rail.addView(railBtn("◫", "View", this::showViewMenu));
        return rail;
    }

    private LinearLayout verticalRail() {
        LinearLayout rail = new LinearLayout(this);
        rail.setOrientation(LinearLayout.VERTICAL);
        rail.setGravity(Gravity.CENTER_HORIZONTAL);
        rail.setPadding(dp(4), dp(4), dp(4), dp(4));
        rail.setBackground(round(Color.argb(246,255,255,255), Color.rgb(216,221,228), 17));
        rail.setElevation(dp(5));
        return rail;
    }

    private Button railBtn(String icon, String name, Runnable action) {
        Button b = new Button(this);
        b.setText(icon + "\n" + name);
        b.setTextSize(9.5f);
        b.setTextColor(Color.rgb(45, 53, 64));
        b.setAllCaps(false);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(2), dp(2), dp(2), dp(2));
        b.setMinWidth(dp(58));
        b.setMinimumWidth(dp(58));
        b.setMinHeight(dp(52));
        b.setMinimumHeight(dp(52));
        b.setBackground(round(Color.TRANSPARENT, Color.TRANSPARENT, 12));
        b.setOnClickListener(v -> action.run());
        return b;
    }

    private Button iconBtn(String icon, String description, Runnable action) {
        Button b = new Button(this);
        b.setText(icon);
        b.setContentDescription(description);
        b.setTextSize(19);
        b.setTextColor(Color.rgb(45, 53, 64));
        b.setAllCaps(false);
        b.setPadding(dp(4), 0, dp(4), 0);
        b.setMinWidth(dp(44));
        b.setMinimumWidth(dp(44));
        b.setMinHeight(dp(42));
        b.setMinimumHeight(dp(42));
        b.setBackground(round(Color.TRANSPARENT, Color.TRANSPARENT, 12));
        b.setOnClickListener(v -> action.run());
        return b;
    }

    private GradientDrawable round(int fill, int stroke, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(dp(radiusDp));
        if (Color.alpha(stroke) > 0) d.setStroke(dp(1), stroke);
        return d;
    }

    private void onWorkspaceStateChanged(String info, boolean exactAvailable, int activeTool) {
        boolean hasSelection = info != null && !info.startsWith("None") && !info.startsWith("First");
        if (adaptiveRail != null) adaptiveRail.setVisibility(hasSelection ? View.VISIBLE : View.GONE);
        if (constraintRail != null) constraintRail.setVisibility(activeTool == CadCanvasView.TOOL_SELECT ? View.GONE : View.VISIBLE);
        if (hasSelection) say(info);
    }

    private void setTool(int tool, String name) {
        cad.setTool(tool);
        say("Sketch • " + name + " — Dimension text text text Show text text");
    }

    private void showSketchMenu() {
        String[] items = {
                "／ Line", "□ Rectangle", "○ Circle", "⌒ Arc", "⬡ Polygon",
                "• Point", "✎ text Unlocked", "↔ Measure", "┼ Guide"
        };
        new AlertDialog.Builder(this)
                .setTitle("Sketch / text text")
                .setItems(items, (d, w) -> {
                    switch (w) {
                        case 0: setTool(CadCanvasView.TOOL_LINE, "Line"); break;
                        case 1: setTool(CadCanvasView.TOOL_RECT, "Rectangle"); break;
                        case 2: setTool(CadCanvasView.TOOL_CIRCLE, "Circle"); break;
                        case 3: setTool(CadCanvasView.TOOL_ARC, "Arc"); break;
                        case 4: showPolygonToolDialog(); break;
                        case 5: setTool(CadCanvasView.TOOL_POINT, "Point"); break;
                        case 6: setTool(CadCanvasView.TOOL_FREE, "text Unlocked"); break;
                        case 7: setTool(CadCanvasView.TOOL_MEASURE, "Measure"); break;
                        default: setTool(CadCanvasView.TOOL_GUIDE, "Guide"); break;
                    }
                }).show();
    }

    private void showInsertMenu() {
        String[] items = {"• Point text", "┼ Guide / Guide", "⬡ Polygon"};
        new AlertDialog.Builder(this)
                .setTitle("Add")
                .setItems(items, (d, w) -> {
                    if (w == 0) setTool(CadCanvasView.TOOL_POINT, "Point text");
                    else if (w == 1) setTool(CadCanvasView.TOOL_GUIDE, "Guide");
                    else showPolygonToolDialog();
                }).show();
    }

    private void showConstructMenu() {
        String[] items = {"┼ Create Guide", "Axestext X/Y", "Grid", "Show/Hide Guide"};
        new AlertDialog.Builder(this)
                .setTitle("Construct / Geometry text")
                .setItems(items, (d, w) -> {
                    if (w == 0) setTool(CadCanvasView.TOOL_GUIDE, "Guide");
                    else if (w == 1) { cad.toggleAxes(); say(cad.isShowAxes()?"Axes On":"Axes Hide"); }
                    else if (w == 2) { cad.toggleGrid(); say(cad.isShowGrid()?"Grid On":"Grid Off"); }
                    else { cad.toggleGuides(); say(cad.isShowGuides()?"Guide On":"Guide Hide"); }
                }).show();
    }

    private void showTransformMenu() {
        String[] items = {
                "⌨ Exact Dimension", "⌖ text text Snap", "↔ Move text", "⧉ Copy",
                "⟳ Rotate", "↗ Scale", "⇄ Mirror X", "⇅ Mirror Y",
                "Array", "Offset", "text", "text text"
        };
        new AlertDialog.Builder(this)
                .setTitle("Transform / Transform")
                .setMessage(cad.selectedInfo())
                .setItems(items, (d, w) -> {
                    switch (w) {
                        case 0: showExactDimension(); break;
                        case 1: say(cad.beginAnchorMove()); break;
                        case 2: promptCommand("Move — cm", "dx dy, text: 5 0", "MOVE "); break;
                        case 3: promptCommand("Copy — cm", "dx dy, text: 10 0", "COPY "); break;
                        case 4: promptCommand("Rotate — degrees", "text: 45", "ROTATE "); break;
                        case 5: promptCommand("Scale", "text: 1.5", "SCALE "); break;
                        case 6: promptCommand("Mirror X — cm", "Axis, text: 0", "MIRROR X "); break;
                        case 7: promptCommand("Mirror Y — cm", "Axis, text: 0", "MIRROR Y "); break;
                        case 8: promptCommand("Array", "text dx dy, text: 4 10 0", "ARRAY "); break;
                        case 9: promptCommand("Offset — cm", "text: 1.8", "OFFSET "); break;
                        case 10: say(cad.groupSelected()); break;
                        default: say(cad.ungroupSelected()); break;
                    }
                }).show();
    }

    private void showToolsMenu() {
        String[] items = {
                "Trim", "Extend", "Fillet", "Chamfer", "Join",
                "Material", "text", "DXF text", "3D / Extrude"
        };
        new AlertDialog.Builder(this)
                .setTitle("Tools")
                .setItems(items, (d, w) -> {
                    switch (w) {
                        case 0: say(cad.trimSelectedLines()); break;
                        case 1: say(cad.extendSelectedLines()); break;
                        case 2: promptCommand("Fillet — Radius cm", "text: 1", "FILLET "); break;
                        case 3: promptCommand("Chamfer — cm", "text: 1", "CHAMFER "); break;
                        case 4: say(cad.joinSelectedLines()); break;
                        case 5: showMaterialMenu(); break;
                        case 6: showLayerMenu(); break;
                        case 7: exportDxf(); break;
                        default: show3dMenu(); break;
                    }
                }).show();
    }

    private void showAllTools() {
        String[] items = {"Sketch", "Transform", "Construct", "Tools", "Selection"};
        new AlertDialog.Builder(this)
                .setTitle("Toolstext")
                .setItems(items, (d, w) -> {
                    if (w == 0) showSketchMenu();
                    else if (w == 1) showTransformMenu();
                    else if (w == 2) showConstructMenu();
                    else if (w == 3) showToolsMenu();
                    else setTool(CadCanvasView.TOOL_SELECT, "Selection");
                }).show();
    }

    private void showSnapMenu() {
        String[] items = {"Snap On/Off", "Ortho Horizontal/Vertical", "Grid", "Guide", "text"};
        new AlertDialog.Builder(this)
                .setTitle("Snaps / Guides")
                .setItems(items, (d, w) -> {
                    if (w == 0) cad.toggleSnap();
                    else if (w == 1) cad.toggleOrtho();
                    else if (w == 2) cad.toggleGrid();
                    else if (w == 3) cad.toggleGuides();
                    else cad.toggleDimensions();
                    say("text Snap/View text text");
                }).show();
    }

    private void showViewMenu() {
        String[] items = {"Fit All", "Zoom In", "Zoom Out", "Axis X/Y", "Grid", "text"};
        new AlertDialog.Builder(this)
                .setTitle("View / Show")
                .setItems(items, (d, w) -> {
                    if (w == 0) cad.fitAll();
                    else if (w == 1) cad.zoomBy(1.8f);
                    else if (w == 2) cad.zoomBy(0.55f);
                    else if (w == 3) cad.toggleAxes();
                    else if (w == 4) cad.toggleGrid();
                    else cad.toggleDimensions();
                    say("Show text text");
                }).show();
    }

    private void showMoreMenu() {
        String[] items = {"text DXF", "text", "Material", "text text text text", "text ChobYar"};
        new AlertDialog.Builder(this)
                .setTitle("ChobYar")
                .setItems(items, (d, w) -> {
                    if (w == 0) exportDxf();
                    else if (w == 1) showLayerMenu();
                    else if (w == 2) showMaterialMenu();
                    else if (w == 3) confirmClear();
                    else Toast.makeText(this, "ChobYar CAD — cm", Toast.LENGTH_SHORT).show();
                }).show();
    }

    private void showPolygonToolDialog() {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setText("6");
        input.setSelectAllOnFocus(true);
        new AlertDialog.Builder(this)
                .setTitle("Polygon — text side")
                .setMessage("3 until 64 side")
                .setView(input)
                .setPositiveButton("text", (d, w) -> {
                    try {
                        int sides = Integer.parseInt(normalizeDigits(input.getText().toString().trim()));
                        if (sides < 3 || sides > 64) { say("text side text text 3 text 64 text"); return; }
                        cad.executeCommand("POLYSIDES " + sides);
                        setTool(CadCanvasView.TOOL_POLYGON, sides + " text");
                    } catch (Exception e) { say("text side is invalid"); }
                })
                .setNegativeButton("Cancel", null).show();
    }

    private void showExactDimension() {
        if (!cad.canEditExactDimension()) {
            String message = cad.exactDimensionMessage();
            say(message);
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
            return;
        }
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setHint(cad.exactDimensionHint());
        String current = cad.exactDimensionCurrentValue();
        if (!current.isEmpty()) { input.setText(current); input.setSelectAllOnFocus(true); }
        new AlertDialog.Builder(this)
                .setTitle(cad.exactDimensionTitle())
                .setMessage(cad.exactDimensionHint())
                .setView(input)
                .setPositiveButton("Apply", (d, w) -> {
                    say(cad.applySelectedDimension(input.getText().toString()));
                    cad.dispatchWorkspaceState();
                })
                .setNegativeButton("Cancel", null).show();
    }

    private void promptCommand(String title, String hint, String prefix) {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint(hint);
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(cad.selectedInfo())
                .setView(input)
                .setPositiveButton("Apply", (d, w) -> {
                    say(cad.executeCommand(prefix + normalizeDigits(input.getText().toString().trim())));
                    cad.dispatchWorkspaceState();
                })
                .setNegativeButton("Cancel", null).show();
    }

    private void showLayerMenu() {
        String[] items = {"text text", "text Selection text text", "Hide text text", "Show text"};
        new AlertDialog.Builder(this)
                .setTitle("Items / Layers")
                .setMessage("text text: " + cad.getCurrentLayer())
                .setItems(items, (d, w) -> {
                    if (w == 0) promptCommand("text text text", "text: MDF18", "LAYER ");
                    else if (w == 1) promptCommand("text text text", "text: text", "ASSIGNLAYER ");
                    else if (w == 2) promptCommand("Hide text text", "text text", "LAYERHIDE ");
                    else promptCommand("Show text", "text text", "LAYERSHOW ");
                }).show();
    }

    private void showMaterialMenu() {
        String[] items = {"WOOD — Wood", "MDF", "METAL — Metal", "GLASS — Glass", "DEFAULT"};
        String[] values = {"WOOD", "MDF", "METAL", "GLASS", "DEFAULT"};
        new AlertDialog.Builder(this)
                .setTitle("Material")
                .setItems(items, (d, w) -> say(cad.setMaterial(values[w]))).show();
    }

    private void show3dMenu() {
        String[] items = {"Extrude / Push-Pull", "Revolve", "Loft", "Sweep", "Shell", "Union", "Subtract", "Intersect"};
        new AlertDialog.Builder(this)
                .setTitle("3D")
                .setMessage("Extrude text Preview 2.5D text; Toolstext Solid text text text text text text text.")
                .setItems(items, (d, w) -> {
                    if (w == 0) promptCommand("Extrude — cm", "Height; text: 1.8", "EXTRUDE ");
                    else {
                        String[] c = {"", "REVOLVE", "LOFT", "SWEEP", "SHELL", "UNION", "SUBTRACT", "INTERSECT"};
                        say(cad.executeCommand(c[w]));
                    }
                }).show();
    }

    private void deleteSelectedQuick() {
        String info = cad.selectedInfo();
        if (info == null || info.startsWith("None")) { say("First text text text Selection text"); return; }
        new AlertDialog.Builder(this)
                .setTitle("Delete Selection?")
                .setMessage(info)
                .setPositiveButton("Delete", (d, w) -> { cad.deleteSelected(); say("Selection Delete text"); })
                .setNegativeButton("Cancel", null).show();
    }

    private void confirmClear() {
        new AlertDialog.Builder(this)
                .setTitle("text text text text?")
                .setMessage("Undo text text text text text text text.")
                .setPositiveButton("text text", (d, w) -> { cad.clearAll(); say("Plane text text"); })
                .setNegativeButton("Cancel", null).show();
    }

    private void say(String s) {
        if (status != null && s != null && !s.trim().isEmpty()) status.setText(s);
    }

    private void exportDxf() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/dxf");
        intent.putExtra(Intent.EXTRA_TITLE, "ChobYar-CAD.dxf");
        startActivityForResult(intent, REQUEST_EXPORT_DXF);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_EXPORT_DXF || resultCode != RESULT_OK || data == null) return;
        Uri uri = data.getData();
        if (uri == null) return;
        try (OutputStream out = getContentResolver().openOutputStream(uri)) {
            if (out == null) throw new IllegalStateException("No output stream");
            out.write(cad.buildDxf().getBytes(StandardCharsets.UTF_8));
            Toast.makeText(this, "text DXF Save text", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "Error text Save DXF", Toast.LENGTH_LONG).show();
        }
        enterImmersiveMode();
    }

    private FrameLayout.LayoutParams frameMatchWrap(int gravity, int l, int t, int r, int b) {
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, gravity);
        p.setMargins(dp(l), dp(t), dp(r), dp(b));
        return p;
    }

    private FrameLayout.LayoutParams frameWrap(int gravity, int l, int t, int r, int b) {
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, gravity);
        p.setMargins(dp(l), dp(t), dp(r), dp(b));
        return p;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static String normalizeDigits(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            b.append(c);
        }
        return b.toString();
    }

    private void enterImmersiveMode() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) enterImmersiveMode();
    }
}

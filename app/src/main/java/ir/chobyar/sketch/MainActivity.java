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
        status.setText(s(R.string.workspace_status_default));
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

        bar.addView(iconBtn("⌂", s(R.string.home),
                () -> Toast.makeText(this, s(R.string.project_chobyar), Toast.LENGTH_SHORT).show()));

        TextView title = new TextView(this);
        title.setText(s(R.string.workspace_title));
        title.setTextSize(14);
        title.setTextColor(Color.rgb(35, 42, 52));
        title.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, dp(46), 1f);
        bar.addView(title, tp);

        bar.addView(iconBtn("↶", s(R.string.undo), () -> {
            cad.undo();
            say(s(R.string.undo_complete));
        }));
        bar.addView(iconBtn("⇩", s(R.string.export_dxf), this::exportDxf));
        bar.addView(iconBtn("⋯", s(R.string.more), this::showMoreMenu));
        return bar;
    }

    private View makeMainRail() {
        LinearLayout rail = verticalRail();
        rail.addView(railBtn("⌕", s(R.string.tools), this::showAllTools));
        rail.addView(railBtn("✎", s(R.string.sketch), this::showSketchMenu));
        rail.addView(railBtn("＋", s(R.string.add), this::showInsertMenu));
        rail.addView(railBtn("⌖", s(R.string.construct), this::showConstructMenu));
        rail.addView(railBtn("↗", s(R.string.transform), this::showTransformMenu));
        rail.addView(railBtn("⌁", s(R.string.tools), this::showToolsMenu));
        return rail;
    }

    private LinearLayout makeAdaptiveRail() {
        LinearLayout rail = verticalRail();
        rail.setBackground(round(Color.argb(248, 235, 244, 255), Color.rgb(145, 181, 235), 17));
        rail.addView(railBtn("⌨", s(R.string.dimension), this::showExactDimension));
        rail.addView(railBtn("⌖", s(R.string.snap_move), () -> say(cad.beginAnchorMove())));
        rail.addView(railBtn("⟳", s(R.string.rotate), () -> promptCommand(
                s(R.string.rotate_degrees_title), s(R.string.example_45), "ROTATE ")));
        rail.addView(railBtn("↕", s(R.string.offset), () -> promptCommand(
                s(R.string.offset_cm_title), s(R.string.example_1_8), "OFFSET ")));
        rail.addView(railBtn("⋯", s(R.string.more), this::showTransformMenu));
        rail.addView(railBtn("⌫", s(R.string.delete), this::deleteSelectedQuick));
        return rail;
    }

    private LinearLayout makeConstraintRail() {
        LinearLayout rail = verticalRail();
        rail.addView(railBtn("⌁", s(R.string.snap), () -> {
            cad.toggleSnap();
            say(cad.isSnapEnabled() ? "Snap On" : "Snap Off");
        }));
        rail.addView(railBtn("⊥", s(R.string.ortho), () -> {
            cad.toggleOrtho();
            say(cad.isOrthoEnabled() ? "Lock Horizontal/Vertical On" : "Lock Horizontal/Vertical Off");
        }));
        rail.addView(railBtn("#", s(R.string.grid), () -> {
            cad.toggleGrid();
            say(cad.isShowGrid() ? "Grid On" : "Grid Off");
        }));
        return rail;
    }

    private View makeModeRail() {
        LinearLayout rail = verticalRail();
        rail.addView(railBtn("↔", s(R.string.dimension),
                () -> setTool(CadCanvasView.TOOL_MEASURE, s(R.string.measure))));
        rail.addView(railBtn("☑", s(R.string.multi_select), () -> say(cad.toggleMultiSelectMode())));
        return rail;
    }

    private View makeViewRail() {
        LinearLayout rail = verticalRail();
        rail.addView(railBtn("◇", s(R.string.fit), () -> {
            cad.fitAll();
            say(s(R.string.fit_complete));
        }));
        rail.addView(railBtn("⌁", s(R.string.snap), this::showSnapMenu));
        rail.addView(railBtn("cm", s(R.string.units), () ->
                Toast.makeText(this, s(R.string.units_cm_message), Toast.LENGTH_SHORT).show()));
        rail.addView(railBtn("◫", s(R.string.view), this::showViewMenu));
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
        say(s(R.string.sketch_tool_status, name));
    }

    private void showSketchMenu() {
        String[] items = {
                "／ " + s(R.string.line),
                "□ " + s(R.string.rectangle),
                "○ " + s(R.string.circle),
                "⌒ " + s(R.string.arc),
                "⬡ " + s(R.string.polygon),
                "• " + s(R.string.point),
                "✎ " + s(R.string.freehand),
                "↔ " + s(R.string.measure),
                "┼ " + s(R.string.guide)
        };
        new AlertDialog.Builder(this)
                .setTitle(s(R.string.sketch_tools_title))
                .setItems(items, (d, w) -> {
                    switch (w) {
                        case 0: setTool(CadCanvasView.TOOL_LINE, s(R.string.line)); break;
                        case 1: setTool(CadCanvasView.TOOL_RECT, s(R.string.rectangle)); break;
                        case 2: setTool(CadCanvasView.TOOL_CIRCLE, s(R.string.circle)); break;
                        case 3: setTool(CadCanvasView.TOOL_ARC, s(R.string.arc)); break;
                        case 4: showPolygonToolDialog(); break;
                        case 5: setTool(CadCanvasView.TOOL_POINT, s(R.string.point)); break;
                        case 6: setTool(CadCanvasView.TOOL_FREE, s(R.string.freehand)); break;
                        case 7: setTool(CadCanvasView.TOOL_MEASURE, s(R.string.measure)); break;
                        default: setTool(CadCanvasView.TOOL_GUIDE, s(R.string.guide)); break;
                    }
                }).show();
    }

    private void showInsertMenu() {
        String[] items = {
                "• " + s(R.string.point_entity),
                "┼ " + s(R.string.guide_item),
                "⬡ " + s(R.string.polygon)
        };
        new AlertDialog.Builder(this)
                .setTitle(s(R.string.add))
                .setItems(items, (d, w) -> {
                    if (w == 0) setTool(CadCanvasView.TOOL_POINT, s(R.string.point));
                    else if (w == 1) setTool(CadCanvasView.TOOL_GUIDE, s(R.string.guide));
                    else showPolygonToolDialog();
                }).show();
    }

    private void showConstructMenu() {
        String[] items = {
                "┼ " + s(R.string.create_guide),
                s(R.string.axes_xy),
                s(R.string.grid),
                s(R.string.show_hide_guides)
        };
        new AlertDialog.Builder(this)
                .setTitle(s(R.string.construction_geometry))
                .setItems(items, (d, w) -> {
                    if (w == 0) setTool(CadCanvasView.TOOL_GUIDE, s(R.string.guide));
                    else if (w == 1) { cad.toggleAxes(); say(cad.isShowAxes() ? s(R.string.axes_on) : s(R.string.axes_hidden)); }
                    else if (w == 2) { cad.toggleGrid(); say(cad.isShowGrid() ? "Grid On" : "Grid Off"); }
                    else { cad.toggleGuides(); say(cad.isShowGuides() ? s(R.string.guide_on) : s(R.string.guide_hidden)); }
                }).show();
    }

    private void showTransformMenu() {
        String[] items = {
                "⌨ " + s(R.string.exact_dimension),
                "⌖ " + s(R.string.snap_move),
                "↔ " + s(R.string.move),
                "⧉ " + s(R.string.copy),
                "⟳ " + s(R.string.rotate),
                "↗ " + s(R.string.scale),
                "⇄ " + s(R.string.mirror_x),
                "⇅ " + s(R.string.mirror_y),
                s(R.string.array),
                s(R.string.offset),
                s(R.string.group),
                s(R.string.ungroup)
        };
        new AlertDialog.Builder(this)
                .setTitle(s(R.string.transform_title))
                .setMessage(cad.selectedInfo())
                .setItems(items, (d, w) -> {
                    switch (w) {
                        case 0: showExactDimension(); break;
                        case 1: say(cad.beginAnchorMove()); break;
                        case 2: promptCommand(s(R.string.move_cm_title), s(R.string.example_dxdy_5_0), "MOVE "); break;
                        case 3: promptCommand(s(R.string.copy_cm_title), s(R.string.example_dxdy_10_0), "COPY "); break;
                        case 4: promptCommand(s(R.string.rotate_degrees_title), s(R.string.example_45), "ROTATE "); break;
                        case 5: promptCommand(s(R.string.scale_title), s(R.string.example_1_5), "SCALE "); break;
                        case 6: promptCommand(s(R.string.mirror_x_cm_title), s(R.string.example_axis_0), "MIRROR X "); break;
                        case 7: promptCommand(s(R.string.mirror_y_cm_title), s(R.string.example_axis_0), "MIRROR Y "); break;
                        case 8: promptCommand(s(R.string.array), s(R.string.example_array), "ARRAY "); break;
                        case 9: promptCommand(s(R.string.offset_cm_title), s(R.string.example_1_8), "OFFSET "); break;
                        case 10: say(cad.groupSelected()); break;
                        default: say(cad.ungroupSelected()); break;
                    }
                }).show();
    }

    private void showToolsMenu() {
        String[] items = {
                s(R.string.trim), s(R.string.extend), s(R.string.fillet), s(R.string.chamfer), s(R.string.join),
                s(R.string.material), s(R.string.layers), s(R.string.export_dxf), s(R.string.solid_3d_extrude)
        };
        new AlertDialog.Builder(this)
                .setTitle(s(R.string.tools))
                .setItems(items, (d, w) -> {
                    switch (w) {
                        case 0: say(cad.trimSelectedLines()); break;
                        case 1: say(cad.extendSelectedLines()); break;
                        case 2: promptCommand(s(R.string.fillet_radius_cm_title), s(R.string.example_1), "FILLET "); break;
                        case 3: promptCommand(s(R.string.chamfer_cm_title), s(R.string.example_1), "CHAMFER "); break;
                        case 4: say(cad.joinSelectedLines()); break;
                        case 5: showMaterialMenu(); break;
                        case 6: showLayerMenu(); break;
                        case 7: exportDxf(); break;
                        default: show3dMenu(); break;
                    }
                }).show();
    }

    private void showAllTools() {
        String[] items = {
                s(R.string.sketch), s(R.string.transform), s(R.string.construct), s(R.string.tools), s(R.string.selection)
        };
        new AlertDialog.Builder(this)
                .setTitle(s(R.string.all_tools))
                .setItems(items, (d, w) -> {
                    if (w == 0) showSketchMenu();
                    else if (w == 1) showTransformMenu();
                    else if (w == 2) showConstructMenu();
                    else if (w == 3) showToolsMenu();
                    else setTool(CadCanvasView.TOOL_SELECT, s(R.string.selection));
                }).show();
    }

    private void showSnapMenu() {
        String[] items = {
                "Snap On/Off", "Ortho Horizontal/Vertical", s(R.string.grid), s(R.string.guide), s(R.string.dimensions)
        };
        new AlertDialog.Builder(this)
                .setTitle(s(R.string.snaps_guides))
                .setItems(items, (d, w) -> {
                    if (w == 0) cad.toggleSnap();
                    else if (w == 1) cad.toggleOrtho();
                    else if (w == 2) cad.toggleGrid();
                    else if (w == 3) cad.toggleGuides();
                    else cad.toggleDimensions();
                    say(s(R.string.snap_view_updated));
                }).show();
    }

    private void showViewMenu() {
        String[] items = {
                s(R.string.fit_all), s(R.string.zoom_in), s(R.string.zoom_out), s(R.string.axis_xy),
                s(R.string.grid), s(R.string.dimensions)
        };
        new AlertDialog.Builder(this)
                .setTitle(s(R.string.view))
                .setItems(items, (d, w) -> {
                    if (w == 0) cad.fitAll();
                    else if (w == 1) cad.zoomBy(1.8f);
                    else if (w == 2) cad.zoomBy(0.55f);
                    else if (w == 3) cad.toggleAxes();
                    else if (w == 4) cad.toggleGrid();
                    else cad.toggleDimensions();
                    say(s(R.string.view_settings_updated));
                }).show();
    }

    private void showMoreMenu() {
        String[] items = {
                s(R.string.export_dxf), s(R.string.layers), s(R.string.material),
                s(R.string.clear_workspace), s(R.string.about_chobyar)
        };
        new AlertDialog.Builder(this)
                .setTitle(s(R.string.app_name))
                .setItems(items, (d, w) -> {
                    if (w == 0) exportDxf();
                    else if (w == 1) showLayerMenu();
                    else if (w == 2) showMaterialMenu();
                    else if (w == 3) confirmClear();
                    else Toast.makeText(this, s(R.string.about_chobyar_message), Toast.LENGTH_SHORT).show();
                }).show();
    }

    private void showPolygonToolDialog() {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setText("6");
        input.setSelectAllOnFocus(true);
        new AlertDialog.Builder(this)
                .setTitle(s(R.string.polygon_sides_title))
                .setMessage(s(R.string.polygon_sides_message))
                .setView(input)
                .setPositiveButton(s(R.string.create), (d, w) -> {
                    try {
                        int sides = Integer.parseInt(normalizeDigits(input.getText().toString().trim()));
                        if (sides < 3 || sides > 64) {
                            say(s(R.string.polygon_sides_range_error));
                            return;
                        }
                        cad.executeCommand("POLYSIDES " + sides);
                        setTool(CadCanvasView.TOOL_POLYGON, s(R.string.sides_format, sides));
                    } catch (Exception e) {
                        say(s(R.string.polygon_sides_invalid));
                    }
                })
                .setNegativeButton(s(R.string.cancel), null).show();
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
        if (!current.isEmpty()) {
            input.setText(current);
            input.setSelectAllOnFocus(true);
        }
        new AlertDialog.Builder(this)
                .setTitle(cad.exactDimensionTitle())
                .setMessage(cad.exactDimensionHint())
                .setView(input)
                .setPositiveButton(s(R.string.apply), (d, w) -> {
                    say(cad.applySelectedDimension(input.getText().toString()));
                    cad.dispatchWorkspaceState();
                })
                .setNegativeButton(s(R.string.cancel), null).show();
    }

    private void promptCommand(String title, String hint, String prefix) {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint(hint);
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(cad.selectedInfo())
                .setView(input)
                .setPositiveButton(s(R.string.apply), (d, w) -> {
                    say(cad.executeCommand(prefix + normalizeDigits(input.getText().toString().trim())));
                    cad.dispatchWorkspaceState();
                })
                .setNegativeButton(s(R.string.cancel), null).show();
    }

    private void showLayerMenu() {
        String[] items = {
                s(R.string.set_current_layer), s(R.string.assign_selection_layer),
                s(R.string.hide_layer), s(R.string.show_layer)
        };
        new AlertDialog.Builder(this)
                .setTitle(s(R.string.items_layers))
                .setMessage(s(R.string.current_layer_format, cad.getCurrentLayer()))
                .setItems(items, (d, w) -> {
                    if (w == 0) promptCommand(s(R.string.set_current_layer_title), s(R.string.layer_name_example), "LAYER ");
                    else if (w == 1) promptCommand(s(R.string.assign_selection_layer_title), s(R.string.layer_name_hint), "ASSIGNLAYER ");
                    else if (w == 2) promptCommand(s(R.string.hide_layer_title), s(R.string.layer_name_hint), "LAYERHIDE ");
                    else promptCommand(s(R.string.show_layer_title), s(R.string.layer_name_hint), "LAYERSHOW ");
                }).show();
    }

    private void showMaterialMenu() {
        String[] items = {
                s(R.string.material_wood), s(R.string.material_mdf), s(R.string.material_metal),
                s(R.string.material_glass), s(R.string.material_default)
        };
        String[] values = {"WOOD", "MDF", "METAL", "GLASS", "DEFAULT"};
        new AlertDialog.Builder(this)
                .setTitle(s(R.string.material))
                .setItems(items, (d, w) -> say(cad.setMaterial(values[w]))).show();
    }

    private void show3dMenu() {
        String[] items = {
                s(R.string.extrude_push_pull), s(R.string.revolve), s(R.string.loft), s(R.string.sweep),
                s(R.string.shell), s(R.string.union), s(R.string.subtract), s(R.string.intersect)
        };
        new AlertDialog.Builder(this)
                .setTitle("3D")
                .setMessage(s(R.string.solid_tools_message))
                .setItems(items, (d, w) -> {
                    if (w == 0) promptCommand(s(R.string.extrude_cm_title), s(R.string.height_example_1_8), "EXTRUDE ");
                    else {
                        String[] c = {"", "REVOLVE", "LOFT", "SWEEP", "SHELL", "UNION", "SUBTRACT", "INTERSECT"};
                        say(cad.executeCommand(c[w]));
                    }
                }).show();
    }

    private void deleteSelectedQuick() {
        String info = cad.selectedInfo();
        if (info == null || info.startsWith("None")) {
            say(s(R.string.select_entity_first));
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(s(R.string.delete_selection_title))
                .setMessage(info)
                .setPositiveButton(s(R.string.delete), (d, w) -> {
                    cad.deleteSelected();
                    say(s(R.string.selection_deleted));
                })
                .setNegativeButton(s(R.string.cancel), null).show();
    }

    private void confirmClear() {
        new AlertDialog.Builder(this)
                .setTitle(s(R.string.clear_workspace_title))
                .setMessage(s(R.string.clear_workspace_message))
                .setPositiveButton(s(R.string.clear_all), (d, w) -> {
                    cad.clearAll();
                    say(s(R.string.workspace_cleared));
                })
                .setNegativeButton(s(R.string.cancel), null).show();
    }

    private void say(String message) {
        if (status != null && message != null && !message.trim().isEmpty()) status.setText(message);
    }

    private String s(int resId) {
        return getString(resId);
    }

    private String s(int resId, Object... args) {
        return getString(resId, args);
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
            Toast.makeText(this, s(R.string.dxf_saved), Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, s(R.string.dxf_save_failed), Toast.LENGTH_LONG).show();
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

    private static String normalizeDigits(String input) {
        if (input == null) return "";
        StringBuilder b = new StringBuilder(input.length());
        for (int i = 0; i < input.length(); i++) {
            b.append(input.charAt(i));
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

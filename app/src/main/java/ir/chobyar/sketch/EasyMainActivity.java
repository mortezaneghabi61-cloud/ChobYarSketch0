package ir.chobyar.sketch;

import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Minimal direct-modeling shell for skachmori.
 *
 * The heavy engine remains unchanged; this class only reorganizes access around
 * a clean CAD workspace: one compact tool rail, a selection-adaptive action bar,
 * XYZ/view controls at the top and small navigation controls at the bottom.
 */
public class EasyMainActivity extends MainActivity {

    private NativeBRepCadCanvasView easyCad;
    private TextView compactStatus;
    private LinearLayout adaptiveBar;
    private Button snapButton;

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

            root.addView(makeTopBar(), frameMatchWrap(Gravity.TOP, 10, 8, 10, 0));
            root.addView(makeMainRail(), frameWrap(Gravity.START | Gravity.TOP, 10, 78, 0, 0));

            adaptiveBar = makeAdaptiveBar();
            adaptiveBar.setVisibility(View.GONE);
            root.addView(adaptiveBar, frameWrap(Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL, 0, 0, 0, 18));

            root.addView(makeNavigationBar(), frameWrap(Gravity.END | Gravity.BOTTOM, 0, 0, 10, 18));

            compactStatus = new TextView(this);
            compactStatus.setText("آماده • cm + mm");
            compactStatus.setTextSize(10f);
            compactStatus.setTextColor(Color.rgb(78, 88, 102));
            compactStatus.setSingleLine(true);
            compactStatus.setPadding(dp(10), dp(5), dp(10), dp(5));
            compactStatus.setBackground(round(Color.argb(238,255,255,255), Color.rgb(220,225,232), 14));
            root.addView(compactStatus, frameWrap(Gravity.START | Gravity.BOTTOM, 10, 0, 0, 18));

            easyCad.dispatchWorkspaceState();
        } catch (Exception e) {
            Toast.makeText(this, "فضای skachmori فعال نشد", Toast.LENGTH_SHORT).show();
        }
    }

    private void wireWorkspaceCallbacks() {
        easyCad.setStatusListener(this::showStatus);
        easyCad.setDimensionEditListener(() -> invokeMain("showExactDimension", new Class<?>[0]));
        easyCad.setWorkspaceListener(this::onWorkspaceStateChanged);
    }

    private void onWorkspaceStateChanged(String info, boolean exactAvailable, int activeTool) {
        boolean hasSelection = info != null
                && !info.startsWith("هیچ")
                && !info.startsWith("اول")
                && !info.trim().isEmpty();
        if (adaptiveBar != null) adaptiveBar.setVisibility(hasSelection ? View.VISIBLE : View.GONE);
        if (hasSelection) showStatus(info);
    }

    private void showStatus(String text) {
        if (compactStatus != null && text != null && !text.trim().isEmpty()) {
            compactStatus.setText(text);
        }
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

    private View makeTopBar() {
        LinearLayout bar = horizontalCard();
        bar.setPadding(dp(7), dp(3), dp(7), dp(3));

        ImageView logo = new ImageView(this);
        logo.setImageResource(R.mipmap.ic_launcher);
        logo.setContentDescription("skachmori");
        logo.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        bar.addView(logo, new LinearLayout.LayoutParams(dp(38), dp(38)));

        TextView title = new TextView(this);
        title.setText("skachmori");
        title.setTextSize(15f);
        title.setTypeface(null, Typeface.BOLD);
        title.setTextColor(Color.rgb(27, 37, 50));
        title.setGravity(Gravity.CENTER_VERTICAL);
        title.setPadding(dp(5), 0, dp(4), 0);
        bar.addView(title, new LinearLayout.LayoutParams(0, dp(44), 1f));

        bar.addView(axisButton("X", Color.rgb(205,65,65), () -> standardView("RIGHT")));
        bar.addView(axisButton("Y", Color.rgb(42,150,80), () -> standardView("FRONT")));
        bar.addView(axisButton("Z", Color.rgb(45,100,215), () -> standardView("TOP")));
        bar.addView(topButton("▦", "نماها / View cube", this::showViewCubeMenu));
        bar.addView(topButton("↶", "Undo", () -> {
            easyCad.undo();
            showStatus("یک مرحله برگشت");
        }));
        bar.addView(topButton("⋯", "بیشتر", this::showMoreMenu));
        return bar;
    }

    private Button axisButton(String axis, int color, Runnable action) {
        Button b = topButton(axis, "نمای محور " + axis, action);
        b.setTextColor(color);
        b.setTypeface(null, Typeface.BOLD);
        return b;
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
            overview.setAccessible(true);
            yaw.setAccessible(true);
            pitch.setAccessible(true);
            orbiting.setAccessible(true);
            overview.setBoolean(easyCad, true);
            orbiting.setBoolean(easyCad, false);

            String key = view == null ? "ISO" : view.toUpperCase();
            if ("TOP".equals(key)) {
                yaw.setFloat(easyCad, 0f);
                pitch.setFloat(easyCad, 0f);
                easyCad.invalidate();
                return "نمای بالا • XY • محور Z";
            }
            if ("FRONT".equals(key)) {
                yaw.setFloat(easyCad, 0f);
                pitch.setFloat(easyCad, 90f);
                easyCad.invalidate();
                return "نمای روبرو • XZ • محور Y";
            }
            if ("RIGHT".equals(key)) {
                yaw.setFloat(easyCad, 90f);
                pitch.setFloat(easyCad, 90f);
                easyCad.invalidate();
                return "نمای راست • YZ • محور X";
            }
            yaw.setFloat(easyCad, 38f);
            pitch.setFloat(easyCad, 24f);
            easyCad.invalidate();
            return "نمای ایزومتریک 3D";
        } catch (Exception e) {
            easyCad.showPlaneManager();
            return "View / Plane";
        }
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
        new AlertDialog.Builder(this)
                .setTitle("View / نما")
                .setItems(items, (d,w) -> {
                    if (w == 0) standardView("ISO");
                    else if (w == 1) standardView("TOP");
                    else if (w == 2) standardView("FRONT");
                    else if (w == 3) standardView("RIGHT");
                    else if (w == 4) showStatus(easyCad.toggle3DOverview());
                    else { easyCad.fitAll(); showStatus("تمام مدل در صفحه"); }
                })
                .setNegativeButton("بستن", null)
                .show();
    }

    private View makeMainRail() {
        LinearLayout rail = verticalCard();
        rail.addView(railButton("✎", "Sketch", () -> invokeMain("showSketchMenu", new Class<?>[0])));
        rail.addView(railButton("▣", "Solid", this::showModelMenu));
        rail.addView(railButton("↗", "تغییر", () -> invokeMain("showTransformMenu", new Class<?>[0])));
        rail.addView(railButton("⌁", "Tools", this::showUtilityMenu));
        return rail;
    }

    private void showModelMenu() {
        String[] items = {
                "◇ Plane / Sketch 3D",
                "▣ Solid / Extrude • Revolve • Sweep • Loft • Boolean",
                "✥ Edit 3D / Face • Edge • Fillet • Chamfer",
                "⌁ روابط Sketch",
                "⏱ History"
        };
        new AlertDialog.Builder(this)
                .setTitle("مدل‌سازی 3D")
                .setItems(items, (d,w) -> {
                    if (w == 0) easyCad.showPlaneManager();
                    else if (w == 1) easyCad.showSolidManager();
                    else if (w == 2) easyCad.showDirectManager();
                    else if (w == 3) easyCad.showSmartConstraintMenu();
                    else easyCad.showHistoryManager();
                })
                .setNegativeButton("بستن", null)
                .show();
    }

    private void showUtilityMenu() {
        String[] items = {
                "⌁ ابزارهای عمومی",
                "↔ اندازه‌گیری",
                "⌁ روابط و Constraint",
                "⏱ History",
                "⇩ خروجی DXF"
        };
        new AlertDialog.Builder(this)
                .setTitle("Tools")
                .setItems(items, (d,w) -> {
                    if (w == 0) invokeMain("showToolsMenu", new Class<?>[0]);
                    else if (w == 1) {
                        easyCad.setTool(CadCanvasView.TOOL_MEASURE);
                        showStatus("اندازه‌گیری فعال شد");
                    } else if (w == 2) easyCad.showSmartConstraintMenu();
                    else if (w == 3) easyCad.showHistoryManager();
                    else invokeMain("exportDxf", new Class<?>[0]);
                })
                .setNegativeButton("بستن", null)
                .show();
    }

    private LinearLayout makeAdaptiveBar() {
        LinearLayout bar = horizontalCard();
        bar.setPadding(dp(4), dp(3), dp(4), dp(3));
        bar.addView(compactAction("⌨", "اندازه", () -> invokeMain("showExactDimension", new Class<?>[0])));
        bar.addView(compactAction("↗", "تغییر", () -> invokeMain("showTransformMenu", new Class<?>[0])));
        bar.addView(compactAction("✥", "Edit 3D", () -> easyCad.showDirectManager()));
        bar.addView(compactAction("⌫", "حذف", () -> invokeMain("deleteSelectedQuick", new Class<?>[0])));
        return bar;
    }

    private View makeNavigationBar() {
        LinearLayout bar = horizontalCard();
        bar.setPadding(dp(3), dp(3), dp(3), dp(3));
        bar.addView(navButton("◇", "Fit", () -> { easyCad.fitAll(); showStatus("Fit"); }));
        bar.addView(navButton("＋", "Zoom in", () -> easyCad.zoomBy(1.25f)));
        bar.addView(navButton("−", "Zoom out", () -> easyCad.zoomBy(0.80f)));
        snapButton = navButton("⌁", "Snap", this::toggleSnap);
        bar.addView(snapButton);
        bar.addView(navButton("cm/mm", "واحد", () -> Toast.makeText(
                this, easyCad.dualUnitSummary(), Toast.LENGTH_LONG).show()));
        updateSnapButton();
        return bar;
    }

    private void toggleSnap() {
        easyCad.toggleSnap();
        updateSnapButton();
        showStatus(easyCad.isSnapEnabled() ? "Snap روشن" : "Snap خاموش");
    }

    private void updateSnapButton() {
        if (snapButton == null || easyCad == null) return;
        snapButton.setText(easyCad.isSnapEnabled() ? "⌁\nSnap" : "○\nSnap");
        snapButton.setTextColor(easyCad.isSnapEnabled()
                ? Color.rgb(42, 100, 205)
                : Color.rgb(100, 108, 120));
    }

    private void showMoreMenu() {
        String[] items = {
                "⇩ خروجی DXF",
                "cm/mm واحدهای اندازه",
                easyCad.isShowGrid() ? "# Grid خاموش" : "# Grid روشن",
                easyCad.isShowAxes() ? "XYZ محورها مخفی" : "XYZ محورها روشن",
                easyCad.isShowGuides() ? "┼ Guide مخفی" : "┼ Guide روشن",
                "⋯ تنظیمات پیشرفته"
        };
        new AlertDialog.Builder(this)
                .setTitle("skachmori")
                .setItems(items, (d,w) -> {
                    if (w == 0) invokeMain("exportDxf", new Class<?>[0]);
                    else if (w == 1) Toast.makeText(this, easyCad.dualUnitSummary(), Toast.LENGTH_LONG).show();
                    else if (w == 2) { easyCad.toggleGrid(); showStatus(easyCad.isShowGrid()?"Grid روشن":"Grid خاموش"); }
                    else if (w == 3) { easyCad.toggleAxes(); showStatus(easyCad.isShowAxes()?"محورها روشن":"محورها مخفی"); }
                    else if (w == 4) { easyCad.toggleGuides(); showStatus(easyCad.isShowGuides()?"Guide روشن":"Guide مخفی"); }
                    else invokeMain("showMoreMenu", new Class<?>[0]);
                })
                .setNegativeButton("بستن", null)
                .show();
    }

    private LinearLayout horizontalCard() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setBackground(round(Color.argb(248,255,255,255), Color.rgb(216,222,230), 18));
        box.setElevation(dp(5));
        return box;
    }

    private LinearLayout verticalCard() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(dp(3), dp(4), dp(3), dp(4));
        box.setBackground(round(Color.argb(248,255,255,255), Color.rgb(216,222,230), 18));
        box.setElevation(dp(5));
        return box;
    }

    private Button topButton(String text, String description, Runnable action) {
        Button b = new Button(this);
        b.setText(text);
        b.setContentDescription(description);
        b.setTextSize(text.length() > 2 ? 10f : 17f);
        b.setTextColor(Color.rgb(54, 64, 78));
        b.setAllCaps(false);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(2), 0, dp(2), 0);
        b.setMinWidth(dp(38)); b.setMinimumWidth(dp(38));
        b.setMinHeight(dp(38)); b.setMinimumHeight(dp(38));
        b.setBackground(round(Color.TRANSPARENT, Color.TRANSPARENT, 12));
        b.setOnClickListener(v -> action.run());
        return b;
    }

    private Button railButton(String icon, String label, Runnable action) {
        Button b = new Button(this);
        b.setText(icon + "\n" + label);
        b.setTextSize(9.5f);
        b.setTextColor(Color.rgb(48, 58, 72));
        b.setAllCaps(false);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(2), dp(2), dp(2), dp(2));
        b.setMinWidth(dp(58)); b.setMinimumWidth(dp(58));
        b.setMinHeight(dp(52)); b.setMinimumHeight(dp(52));
        b.setBackground(round(Color.TRANSPARENT, Color.TRANSPARENT, 12));
        b.setOnClickListener(v -> action.run());
        return b;
    }

    private Button compactAction(String icon, String label, Runnable action) {
        Button b = railButton(icon, label, action);
        b.setMinWidth(dp(60)); b.setMinimumWidth(dp(60));
        b.setMinHeight(dp(46)); b.setMinimumHeight(dp(46));
        return b;
    }

    private Button navButton(String icon, String label, Runnable action) {
        Button b = new Button(this);
        b.setText(icon + "\n" + label);
        b.setTextSize(icon.length() > 2 ? 8.5f : 9.5f);
        b.setTextColor(Color.rgb(58, 68, 82));
        b.setAllCaps(false);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(2), dp(1), dp(2), dp(1));
        b.setMinWidth(dp(48)); b.setMinimumWidth(dp(48));
        b.setMinHeight(dp(45)); b.setMinimumHeight(dp(45));
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

    private FrameLayout.LayoutParams frameMatchWrap(int gravity, int left, int top, int right, int bottom) {
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                gravity);
        p.setMargins(dp(left), dp(top), dp(right), dp(bottom));
        return p;
    }

    private FrameLayout.LayoutParams frameWrap(int gravity, int left, int top, int right, int bottom) {
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                gravity);
        p.setMargins(dp(left), dp(top), dp(right), dp(bottom));
        return p;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}

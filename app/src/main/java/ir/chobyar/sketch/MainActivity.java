package ir.chobyar.sketch;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

public class MainActivity extends Activity {

    private static final int REQUEST_EXPORT_DXF = 1001;
    private CadCanvasView cad;
    private TextView status;
    private EditText command;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        enterImmersiveMode();

        cad = new CadCanvasView(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(245, 245, 245));

        root.addView(makeSketchToolbar());
        root.addView(makeCadToolbar());
        root.addView(makeCommandBar());

        status = new TextView(this);
        status.setText("چوب‌یار CAD — mm | دو انگشت: Zoom/Pan | انتخاب کن و «اندازه دقیق» بزن");
        status.setTextSize(12);
        status.setPadding(12, 3, 12, 5);
        status.setTextColor(Color.DKGRAY);
        root.addView(status, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        root.addView(cad, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
    }

    private HorizontalScrollView makeSketchToolbar() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(5, 4, 5, 2);
        row.setGravity(Gravity.CENTER_VERTICAL);

        row.addView(btn("☝ انتخاب", () -> setTool(CadCanvasView.TOOL_SELECT, "انتخاب")));
        row.addView(btn("• نقطه", () -> setTool(CadCanvasView.TOOL_POINT, "نقطه")));
        row.addView(btn("／ خط", () -> setTool(CadCanvasView.TOOL_LINE, "خط")));
        row.addView(btn("□ مستطیل", () -> setTool(CadCanvasView.TOOL_RECT, "مستطیل")));
        row.addView(btn("○ دایره", () -> setTool(CadCanvasView.TOOL_CIRCLE, "دایره")));
        row.addView(btn("⌒ قوس", () -> setTool(CadCanvasView.TOOL_ARC, "قوس")));
        row.addView(btn("⬡ چندضلعی", () -> setTool(CadCanvasView.TOOL_POLYGON, "چندضلعی")));
        row.addView(btn("✎ آزاد", () -> setTool(CadCanvasView.TOOL_FREE, "Freehand")));
        row.addView(btn("↔ اندازه", () -> setTool(CadCanvasView.TOOL_MEASURE, "اندازه‌گیری")));
        row.addView(btn("┼ راهنما", () -> setTool(CadCanvasView.TOOL_GUIDE, "Guide؛ روی محل بزن")));
        row.addView(btn("↶ Undo", () -> { cad.undo(); say("یک مرحله برگشت"); }));
        row.addView(btn("پاک", this::confirmClear));

        return scroll(row);
    }

    private HorizontalScrollView makeCadToolbar() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(5, 1, 5, 3);
        row.setGravity(Gravity.CENTER_VERTICAL);

        row.addView(btn("محور X/Y", () -> { cad.toggleAxes(); say(cad.isShowAxes() ? "محورها روشن" : "محورها مخفی"); }));
        row.addView(btn("Grid", () -> { cad.toggleGrid(); say(cad.isShowGrid() ? "Grid روشن" : "Grid خاموش"); }));
        row.addView(btn("Snap", () -> { cad.toggleSnap(); say(cad.isSnapEnabled() ? "Snap روشن" : "Snap خاموش"); }));
        row.addView(btn("Ortho", () -> { cad.toggleOrtho(); say(cad.isOrthoEnabled() ? "Ortho روشن" : "Ortho خاموش"); }));
        row.addView(btn("Guide", () -> { cad.toggleGuides(); say(cad.isShowGuides() ? "Guide روشن" : "Guide مخفی"); }));
        row.addView(btn("ابعاد", () -> { cad.toggleDimensions(); say(cad.isShowDimensions() ? "ابعاد روشن" : "ابعاد مخفی"); }));
        row.addView(btn("Fit", () -> { cad.fitAll(); say("تمام نقشه در صفحه"); }));
        row.addView(btn("اندازه دقیق", this::showExactDimension));
        row.addView(btn("ویرایش", this::showTransformMenu));
        row.addView(btn("لایه", this::showLayerMenu));
        row.addView(btn("متریال", this::showMaterialMenu));
        row.addView(btn("DXF خروجی", this::exportDxf));
        row.addView(btn("3D", this::show3dMenu));

        return scroll(row);
    }

    private View makeCommandBar() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(7, 3, 7, 3);
        row.setGravity(Gravity.CENTER_VERTICAL);

        command = new EditText(this);
        command.setSingleLine(true);
        command.setHint("فرمان: RECT 0 0 600 400 | ROTATE 45 | ARRAY 4 100 0");
        command.setTextSize(14);
        command.setPadding(10, 4, 10, 4);
        row.addView(command, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        row.addView(btn("اجرا", this::runCommand));

        command.setOnEditorActionListener((v, actionId, event) -> {
            runCommand();
            return true;
        });
        return row;
    }

    private HorizontalScrollView scroll(LinearLayout row) {
        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.addView(row);
        return scroll;
    }

    private Button btn(String text, Runnable action) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(12);
        b.setAllCaps(false);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setPadding(14, 4, 14, 4);
        b.setOnClickListener(v -> action.run());
        return b;
    }

    private void setTool(int tool, String name) {
        cad.setTool(tool);
        say("ابزار: " + name);
    }

    private void runCommand() {
        String raw = command.getText().toString();
        String result = cad.executeCommand(raw);
        if (!result.isEmpty()) say(result);
        command.selectAll();
        InputMethodManager imm =
                (InputMethodManager)getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(command.getWindowToken(), 0);
        enterImmersiveMode();
    }

    private void showExactDimension() {
        say(cad.selectedInfo());
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint("خط: 876 | مستطیل: 600 400 | دایره: قطر 60");
        new AlertDialog.Builder(this)
                .setTitle("اندازه دقیق — mm")
                .setMessage(cad.selectedInfo())
                .setView(input)
                .setPositiveButton("اعمال", (d, w) ->
                        say(cad.applySelectedDimension(input.getText().toString())))
                .setNegativeButton("لغو", null)
                .show();
    }

    private void showTransformMenu() {
        String[] items = {
                "Move — جابه‌جایی دقیق",
                "Copy — کپی دقیق",
                "Rotate — چرخش",
                "Scale — تغییر مقیاس",
                "Mirror X — قرینه نسبت به محور X",
                "Mirror Y — قرینه نسبت به محور Y",
                "Array — تکثیر منظم",
                "Offset — آفست",
                "Delete — حذف"
        };
        new AlertDialog.Builder(this)
                .setTitle("ویرایش شکل انتخاب‌شده")
                .setMessage(cad.selectedInfo())
                .setItems(items, (dialog, which) -> {
                    switch (which) {
                        case 0: promptCommand("Move: dx dy", "مثال: 50 0", "MOVE "); break;
                        case 1: promptCommand("Copy: dx dy", "مثال: 100 0", "COPY "); break;
                        case 2: promptCommand("Rotate: درجه", "مثال: 45", "ROTATE "); break;
                        case 3: promptCommand("Scale", "مثال: 1.5", "SCALE "); break;
                        case 4: promptCommand("Mirror X", "مقدار محور؛ مثال: 0", "MIRROR X "); break;
                        case 5: promptCommand("Mirror Y", "مقدار محور؛ مثال: 0", "MIRROR Y "); break;
                        case 6: promptCommand("Array: count dx dy", "مثال: 4 100 0", "ARRAY "); break;
                        case 7: promptCommand("Offset", "فاصله mm؛ مثال: 18", "OFFSET "); break;
                        default:
                            cad.deleteSelected();
                            say("حذف شد");
                            break;
                    }
                })
                .setNegativeButton("بستن", null)
                .show();
    }

    private void showLayerMenu() {
        String[] items = {
                "لایه جاری",
                "انتقال شکل انتخاب‌شده به لایه",
                "مخفی کردن لایه",
                "نمایش لایه"
        };
        new AlertDialog.Builder(this)
                .setTitle("Layers / لایه‌ها")
                .setMessage("لایه جاری: " + cad.getCurrentLayer())
                .setItems(items, (d, which) -> {
                    if (which == 0) promptCommand("نام لایه جاری", "مثال: MDF18", "LAYER ");
                    else if (which == 1) promptCommand("نام لایه شکل", "مثال: پایه", "ASSIGNLAYER ");
                    else if (which == 2) promptCommand("مخفی کردن لایه", "نام لایه", "LAYERHIDE ");
                    else promptCommand("نمایش لایه", "نام لایه", "LAYERSHOW ");
                })
                .setNegativeButton("بستن", null)
                .show();
    }

    private void showMaterialMenu() {
        String[] items = {"WOOD — چوب", "MDF", "METAL — فلز", "GLASS — شیشه", "DEFAULT"};
        String[] values = {"WOOD", "MDF", "METAL", "GLASS", "DEFAULT"};
        new AlertDialog.Builder(this)
                .setTitle("متریال / رنگ تشخیصی")
                .setMessage("اگر شکلی انتخاب باشد روی همان اعمال می‌شود؛ در غیر این صورت روی ترسیم‌های بعدی.")
                .setItems(items, (d, which) -> say(cad.setMaterial(values[which])))
                .setNegativeButton("بستن", null)
                .show();
    }

    private void show3dMenu() {
        String[] items = {
                "Push/Pull / Extrude — پیش‌نمایش حجم",
                "Revolve — دوران/خراطی",
                "Follow Me — حرکت مقطع روی مسیر",
                "Loft — اتصال مقاطع",
                "Sweep — حرکت مقطع روی مسیر",
                "Shell — پوسته",
                "Union — اتصال حجم‌ها",
                "Subtract — کم‌کردن حجم",
                "Intersect — اشتراک حجم‌ها",
                "Project — پروجکت"
        };
        new AlertDialog.Builder(this)
                .setTitle("3D چوب‌یار")
                .setMessage("Push/Pull فعلاً 2.5D است؛ بقیه ابزارهای Solid بعد از اتصال هسته سه‌بعدی واقعی فعال می‌شوند.")
                .setItems(items, (dialog, which) -> {
                    if (which == 0) {
                        promptCommand("Push/Pull / Extrude", "ارتفاع mm؛ مثال: 18", "EXTRUDE ");
                        return;
                    }
                    String[] cmds = {"", "REVOLVE", "FOLLOWME", "LOFT", "SWEEP",
                            "SHELL", "UNION", "SUBTRACT", "INTERSECT", "PROJECT"};
                    say(cad.executeCommand(cmds[which]));
                })
                .setNegativeButton("بستن", null)
                .show();
    }

    private void promptCommand(String title, String hint, String prefix) {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint(hint);
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(input)
                .setPositiveButton("اجرا", (d, w) ->
                        say(cad.executeCommand(prefix + input.getText().toString().trim())))
                .setNegativeButton("لغو", null)
                .show();
    }

    private void confirmClear() {
        new AlertDialog.Builder(this)
                .setTitle("پاک کردن کل نقشه؟")
                .setMessage("با Undo می‌توانی یک مرحله برگردی.")
                .setPositiveButton("پاک کن", (d, w) -> {
                    cad.clearAll();
                    say("صفحه پاک شد");
                })
                .setNegativeButton("لغو", null)
                .show();
    }

    private void say(String s) {
        if (status != null) status.setText(s);
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
            Toast.makeText(this, "فایل DXF ذخیره شد", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "خطا در ذخیره DXF", Toast.LENGTH_LONG).show();
        }
        enterImmersiveMode();
    }

    private void enterImmersiveMode() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        );
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) enterImmersiveMode();
    }
}

package ir.chobyar.sketch;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
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
    private CentimeterCadCanvasView cad;
    private TextView status;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        enterImmersiveMode();

        cad = new CentimeterCadCanvasView(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(245, 245, 245));

        root.addView(makeSketchToolbar());
        root.addView(makeCadToolbar());

        status = new TextView(this);
        status.setText("واحد اندازه‌گیری: سانتی‌متر (cm) — روی شیء بزن تا انتخاب شود. اندازه دقیق هم متناسب با همان شکل نمایش داده می‌شود.");
        status.setTextSize(12);
        status.setPadding(12, 3, 12, 5);
        status.setTextColor(Color.DKGRAY);
        root.addView(status, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        cad.setStatusListener(this::say);
        cad.setDimensionEditListener(this::showExactDimension);

        root.addView(makeQuickEditBar());
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
        row.addView(btn("⬡ چندضلعی", this::showPolygonToolDialog));
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

        row.addView(btn("− زوم", () -> { cad.zoomBy(0.55f); say("Zoom Out"); }));
        row.addView(btn("+ زوم", () -> { cad.zoomBy(1.8f); say("Zoom In"); }));
        row.addView(btn("محور X/Y", () -> { cad.toggleAxes(); say(cad.isShowAxes() ? "محورها روشن" : "محورها مخفی"); }));
        row.addView(btn("Grid", () -> { cad.toggleGrid(); say(cad.isShowGrid() ? "Grid روشن" : "Grid خاموش"); }));
        row.addView(btn("Snap", () -> { cad.toggleSnap(); say(cad.isSnapEnabled() ? "Snap روشن" : "Snap خاموش"); }));
        row.addView(btn("Ortho", () -> { cad.toggleOrtho(); say(cad.isOrthoEnabled() ? "Ortho روشن" : "Ortho خاموش"); }));
        row.addView(btn("Guide", () -> { cad.toggleGuides(); say(cad.isShowGuides() ? "Guide روشن" : "Guide مخفی"); }));
        row.addView(btn("ابعاد", () -> { cad.toggleDimensions(); say(cad.isShowDimensions() ? "ابعاد cm روشن" : "ابعاد مخفی"); }));
        row.addView(btn("Fit", () -> { cad.fitAll(); say("تمام نقشه در صفحه"); }));
        row.addView(btn("اندازه دقیق", this::showExactDimension));
        row.addView(btn("ویرایش", this::showTransformMenu));
        row.addView(btn("لایه", this::showLayerMenu));
        row.addView(btn("متریال", this::showMaterialMenu));
        row.addView(btn("DXF خروجی", this::exportDxf));
        row.addView(btn("3D", this::show3dMenu));
        return scroll(row);
    }

    private View makeQuickEditBar() {
        LinearLayout outer = new LinearLayout(this);
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.setPadding(6, 2, 6, 3);
        outer.setBackgroundColor(Color.rgb(236, 242, 250));

        TextView title = new TextView(this);
        title.setText("ویرایش انتخاب");
        title.setTextSize(11);
        title.setTextColor(Color.rgb(40, 75, 120));
        title.setPadding(6, 0, 6, 1);
        outer.addView(title);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(quickBtn("☑ چندانتخاب", () -> say(cad.toggleMultiSelectMode())));
        row.addView(quickBtn("📌 جابجایی Snap", () -> say(cad.beginAnchorMove())));
        row.addView(quickBtn("⛓ گروه", () -> say(cad.groupSelected())));
        row.addView(quickBtn("⛓ بازگروه", () -> say(cad.ungroupSelected())));
        row.addView(quickBtn("📐 اندازه", this::showExactDimension));
        row.addView(quickBtn("↔ جابه‌جا", () -> promptCommand("جابه‌جایی دقیق — cm", "dx dy به cm؛ مثال: 5 0", "MOVE ")));
        row.addView(quickBtn("⟳ چرخش", () -> promptCommand("چرخش", "درجه؛ مثال: 45", "ROTATE ")));
        row.addView(quickBtn("⧉ کپی", () -> promptCommand("کپی دقیق — cm", "dx dy به cm؛ مثال: 10 0", "COPY ")));
        row.addView(quickBtn("↕ Offset", () -> promptCommand("Offset — cm", "فاصله به cm؛ مثال: 1.8", "OFFSET ")));
        row.addView(quickBtn("⇄ قرینه", this::showMirrorQuickMenu));
        row.addView(quickBtn("🎨 متریال", this::showMaterialMenu));
        row.addView(quickBtn("⋮ بیشتر", this::showTransformMenu));
        row.addView(quickBtn("⌫ حذف", this::deleteSelectedQuick));
        outer.addView(scroll(row));
        return outer;
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

    private Button quickBtn(String text, Runnable action) {
        Button b = btn(text, action);
        b.setTextSize(11);
        b.setPadding(12, 2, 12, 2);
        return b;
    }

    private void setTool(int tool, String name) {
        cad.setTool(tool);
        say("ابزار: " + name);
    }

    private void showPolygonToolDialog() {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setText("6");
        input.setSelectAllOnFocus(true);
        input.setHint("مثال: 8 یا 9");
        new AlertDialog.Builder(this)
                .setTitle("چندضلعی — تعداد ضلع")
                .setMessage("تعداد ضلع را وارد کن؛ از 3 تا 64 ضلع.")
                .setView(input)
                .setPositiveButton("شروع رسم", (d, w) -> {
                    try {
                        int sides = Integer.parseInt(input.getText().toString().trim());
                        if (sides < 3 || sides > 64) {
                            say("تعداد ضلع باید بین 3 تا 64 باشد");
                            return;
                        }
                        String result = cad.executeCommand("POLYSIDES " + sides);
                        cad.setTool(CadCanvasView.TOOL_POLYGON);
                        say(result + " — مرکز را بزن و شعاع را بکش");
                    } catch (Exception e) {
                        say("تعداد ضلع درست وارد نشده");
                    }
                })
                .setNegativeButton("لغو", null)
                .show();
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
                .setMessage(cad.exactDimensionMessage())
                .setView(input)
                .setPositiveButton("اعمال", (d, w) -> say(cad.applySelectedDimension(input.getText().toString())))
                .setNegativeButton("لغو", null)
                .show();
    }

    private void showTransformMenu() {
        String[] items = {
                "اندازه دقیق", "Move — جابه‌جایی دقیق", "Copy — کپی دقیق",
                "Rotate — چرخش", "Scale — تغییر مقیاس",
                "Mirror X — قرینه نسبت به محور X", "Mirror Y — قرینه نسبت به محور Y",
                "Array — تکثیر منظم", "Offset — آفست", "Material — متریال", "Delete — حذف"
        };
        new AlertDialog.Builder(this)
                .setTitle("ویرایش شکل انتخاب‌شده")
                .setMessage(cad.selectedInfo())
                .setItems(items, (dialog, which) -> {
                    switch (which) {
                        case 0: showExactDimension(); break;
                        case 1: promptCommand("Move — cm", "dx dy به cm؛ مثال: 5 0", "MOVE "); break;
                        case 2: promptCommand("Copy — cm", "dx dy به cm؛ مثال: 10 0", "COPY "); break;
                        case 3: promptCommand("Rotate — درجه", "مثال: 45", "ROTATE "); break;
                        case 4: promptCommand("Scale", "مثال: 1.5", "SCALE "); break;
                        case 5: promptCommand("Mirror X — cm", "مختصات محور به cm؛ مثال: 0", "MIRROR X "); break;
                        case 6: promptCommand("Mirror Y — cm", "مختصات محور به cm؛ مثال: 0", "MIRROR Y "); break;
                        case 7: promptCommand("Array", "تعداد dx dy به cm؛ مثال: 4 10 0", "ARRAY "); break;
                        case 8: promptCommand("Offset — cm", "فاصله به cm؛ مثال: 1.8", "OFFSET "); break;
                        case 9: showMaterialMenu(); break;
                        default: deleteSelectedQuick(); break;
                    }
                })
                .setNegativeButton("بستن", null)
                .show();
    }

    private void showMirrorQuickMenu() {
        String[] items = {"قرینه نسبت به محور X", "قرینه نسبت به محور Y"};
        new AlertDialog.Builder(this)
                .setTitle("قرینه")
                .setMessage(cad.selectedInfo())
                .setItems(items, (d, which) -> {
                    if (which == 0) promptCommand("Mirror X — cm", "مختصات محور؛ مثال: 0", "MIRROR X ");
                    else promptCommand("Mirror Y — cm", "مختصات محور؛ مثال: 0", "MIRROR Y ");
                })
                .setNegativeButton("بستن", null)
                .show();
    }

    private void deleteSelectedQuick() {
        String info = cad.selectedInfo();
        if (info.startsWith("هیچ")) {
            say("اول یک شیء را انتخاب کن");
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("حذف انتخاب؟")
                .setMessage(info)
                .setPositiveButton("حذف", (d, w) -> { cad.deleteSelected(); say("انتخاب حذف شد"); })
                .setNegativeButton("لغو", null)
                .show();
    }

    private void showLayerMenu() {
        String[] items = {"لایه جاری", "انتقال شکل انتخاب‌شده به لایه", "مخفی کردن لایه", "نمایش لایه"};
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
                .setMessage("اگر شکل انتخاب باشد روی انتخاب اعمال می‌شود؛ در غیر این صورت روی ترسیم‌های بعدی.")
                .setItems(items, (d, which) -> say(cad.setMaterial(values[which])))
                .setNegativeButton("بستن", null)
                .show();
    }

    private void show3dMenu() {
        String[] items = {
                "Push/Pull / Extrude — پیش‌نمایش حجم", "Revolve — دوران/خراطی",
                "Follow Me — حرکت مقطع روی مسیر", "Loft — اتصال مقاطع", "Sweep — حرکت مقطع روی مسیر",
                "Shell — پوسته", "Union — اتصال حجم‌ها", "Subtract — کم‌کردن حجم",
                "Intersect — اشتراک حجم‌ها", "Project — پروجکت"
        };
        new AlertDialog.Builder(this)
                .setTitle("3D چوب‌یار")
                .setMessage("Push/Pull فعلاً 2.5D است؛ بقیه ابزارهای Solid بعد از اتصال هسته سه‌بعدی واقعی فعال می‌شوند.")
                .setItems(items, (dialog, which) -> {
                    if (which == 0) {
                        promptCommand("Push/Pull / Extrude — cm", "ارتفاع به cm؛ مثال: 1.8", "EXTRUDE ");
                        return;
                    }
                    String[] cmds = {"", "REVOLVE", "FOLLOWME", "LOFT", "SWEEP", "SHELL", "UNION", "SUBTRACT", "INTERSECT", "PROJECT"};
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
                .setMessage(cad.selectedInfo())
                .setView(input)
                .setPositiveButton("اعمال", (d, w) -> say(cad.executeCommand(prefix + input.getText().toString().trim())))
                .setNegativeButton("لغو", null)
                .show();
    }

    private void confirmClear() {
        new AlertDialog.Builder(this)
                .setTitle("پاک کردن کل نقشه؟")
                .setMessage("با Undo می‌توانی یک مرحله برگردی.")
                .setPositiveButton("پاک کن", (d, w) -> { cad.clearAll(); say("صفحه پاک شد"); })
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

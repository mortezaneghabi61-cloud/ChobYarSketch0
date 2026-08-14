package ir.chobyar.sketch;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.content.Context;
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
        status.setText("چوب‌یار CAD — واحد: mm | فرمان‌ها: LINE, RECT, CIRCLE, MOVE, COPY, OFFSET, DIM, AXIS, GRID, SNAP, ORTHO, FIT");
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

        row.addView(btn("☝ انتخاب", () -> setTool(CadCanvasView.TOOL_SELECT, "انتخاب شکل")));
        row.addView(btn("• نقطه", () -> setTool(CadCanvasView.TOOL_POINT, "نقطه")));
        row.addView(btn("／ خط", () -> setTool(CadCanvasView.TOOL_LINE, "خط")));
        row.addView(btn("□ مستطیل", () -> setTool(CadCanvasView.TOOL_RECT, "مستطیل")));
        row.addView(btn("○ دایره", () -> setTool(CadCanvasView.TOOL_CIRCLE, "دایره")));
        row.addView(btn("⌒ قوس", () -> setTool(CadCanvasView.TOOL_ARC, "قوس")));
        row.addView(btn("↔ اندازه", () -> setTool(CadCanvasView.TOOL_MEASURE, "اندازه‌گیری")));
        row.addView(btn("↶ Undo", () -> { cad.undo(); say("یک مرحله برگشت"); }));
        row.addView(btn("پاک", () -> { cad.clearAll(); say("صفحه پاک شد"); }));

        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.addView(row);
        return scroll;
    }

    private HorizontalScrollView makeCadToolbar() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(5, 1, 5, 3);
        row.setGravity(Gravity.CENTER_VERTICAL);

        row.addView(btn("محور X/Y", () -> { cad.toggleAxes(); say(cad.isShowAxes()?"محورهای X/Y روشن":"محورها مخفی"); }));
        row.addView(btn("Grid", () -> { cad.toggleGrid(); say(cad.isShowGrid()?"شبکه روشن":"شبکه خاموش"); }));
        row.addView(btn("Snap", () -> { cad.toggleSnap(); say(cad.isSnapEnabled()?"Snap روشن":"Snap خاموش"); }));
        row.addView(btn("Ortho", () -> { cad.toggleOrtho(); say(cad.isOrthoEnabled()?"Ortho روشن؛ خط افقی/عمودی":"Ortho خاموش"); }));
        row.addView(btn("Fit", () -> { cad.fitAll(); say("تمام نقشه در صفحه"); }));
        row.addView(btn("حذف", () -> { cad.deleteSelected(); say("شکل انتخاب‌شده حذف شد"); }));
        row.addView(btn("کپی +10", () -> { cad.copySelected(10,10); say("کپی با جابه‌جایی 10mm"); }));
        row.addView(btn("Offset 10", () -> { cad.offsetSelected(10); say("Offset خط = 10mm"); }));
        row.addView(btn("DXF خروجی", this::exportDxf));
        row.addView(btn("3D", this::show3dMenu));

        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.addView(row);
        return scroll;
    }

    private View makeCommandBar() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(7, 3, 7, 3);
        row.setGravity(Gravity.CENTER_VERTICAL);

        command = new EditText(this);
        command.setSingleLine(true);
        command.setHint("فرمان: LINE 0 0 1200 0   یا  RECT 0 0 600 400");
        command.setTextSize(14);
        command.setPadding(10, 4, 10, 4);
        row.addView(command, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        Button run = btn("اجرا", this::runCommand);
        row.addView(run);

        command.setOnEditorActionListener((v, actionId, event) -> {
            runCommand();
            return true;
        });
        return row;
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
        InputMethodManager imm = (InputMethodManager)getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(command.getWindowToken(), 0);
        enterImmersiveMode();
    }

    private void show3dMenu() {
        String[] items = {
                "Extrude — اکسترود",
                "Revolve — دوران/خراطی",
                "Loft — اتصال مقاطع",
                "Sweep — حرکت مقطع روی مسیر",
                "Shell — پوسته",
                "Union — اتصال حجم‌ها",
                "Subtract — کم‌کردن حجم",
                "Intersect — اشتراک حجم‌ها",
                "Project — پروجکت"
        };
        new AlertDialog.Builder(this)
                .setTitle("ابزارهای 3D چوب‌یار")
                .setItems(items, (dialog, which) -> {
                    String cmd;
                    switch (which) {
                        case 0: cmd="EXTRUDE"; break;
                        case 1: cmd="REVOLVE"; break;
                        case 2: cmd="LOFT"; break;
                        case 3: cmd="SWEEP"; break;
                        case 4: cmd="SHELL"; break;
                        case 5: cmd="UNION"; break;
                        case 6: cmd="SUBTRACT"; break;
                        case 7: cmd="INTERSECT"; break;
                        default: cmd="PROJECT"; break;
                    }
                    say(cad.executeCommand(cmd));
                })
                .setNegativeButton("بستن", null)
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

package ir.chobyar.sketch;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

public class AiPanel {
    private static final String PREFS = "chobyar_ai";
    private static final String KEY_ENDPOINT = "endpoint";

    private final Activity activity;
    private final CadCanvasView cad;
    private final TextView status;
    private final AiClient client = new AiClient();
    private final SharedPreferences prefs;

    public AiPanel(Activity activity, CadCanvasView cad, TextView status) {
        this.activity = activity;
        this.cad = cad;
        this.status = status;
        this.prefs = activity.getSharedPreferences(PREFS, Activity.MODE_PRIVATE);
    }

    public View build() {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(7, 3, 7, 3);
        row.setBackgroundColor(Color.rgb(242, 238, 252));

        TextView label = new TextView(activity);
        label.setText("AI چوب‌یار");
        label.setTextSize(12);
        label.setTextColor(Color.rgb(75, 45, 130));
        label.setPadding(7, 0, 10, 0);
        row.addView(label);

        row.addView(button("🤖 بگو چی بسازم", this::showPrompt));
        row.addView(button("⚙ اتصال", this::showSettings));
        return row;
    }

    private Button button(String text, Runnable action) {
        Button b = new Button(activity);
        b.setText(text);
        b.setTextSize(11);
        b.setAllCaps(false);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setPadding(12, 2, 12, 2);
        b.setOnClickListener(v -> action.run());
        return b;
    }

    private void showPrompt() {
        String endpoint = endpoint();
        if (endpoint.isEmpty()) {
            say("اول آدرس سرور AI را تنظیم کن");
            showSettings();
            return;
        }

        EditText input = new EditText(activity);
        input.setMinLines(3);
        input.setMaxLines(6);
        input.setGravity(Gravity.TOP);
        input.setHint("مثلاً: یک مستطیل 600 در 400 بساز، بعد وسطش دایره قطر 60 بکش");
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);

        new AlertDialog.Builder(activity)
                .setTitle("دستور فارسی به AI چوب‌یار")
                .setMessage("AI می‌تواند پاسخ بدهد یا فرمان CAD پیشنهاد کند. قبل از اعمال تغییرات، فرمان‌ها را به تو نشان می‌دهیم.")
                .setView(input)
                .setPositiveButton("ارسال", (d, w) -> ask(input.getText().toString()))
                .setNegativeButton("لغو", null)
                .show();
    }

    private void ask(String prompt) {
        if (prompt == null || prompt.trim().isEmpty()) {
            say("دستور خالی است");
            return;
        }
        say("AI چوب‌یار در حال بررسی...");
        client.ask(endpoint(), prompt.trim(), cad.selectedInfo(), new AiClient.Callback() {
            @Override
            public void onSuccess(AiClient.AiReply reply) {
                activity.runOnUiThread(() -> showReply(reply));
            }

            @Override
            public void onError(String message) {
                activity.runOnUiThread(() -> say(message));
            }
        });
    }

    private void showReply(AiClient.AiReply reply) {
        StringBuilder text = new StringBuilder();
        if (!reply.message.isEmpty()) text.append(reply.message);
        if (!reply.commands.isEmpty()) {
            if (text.length() > 0) text.append("\n\n");
            text.append("فرمان‌های پیشنهادی:\n");
            for (String command : reply.commands) text.append("• ").append(command).append('\n');
        }
        if (text.length() == 0) text.append("پاسخی دریافت نشد");

        AlertDialog.Builder builder = new AlertDialog.Builder(activity)
                .setTitle("AI چوب‌یار")
                .setMessage(text.toString())
                .setNegativeButton("بستن", null);

        if (!reply.commands.isEmpty()) {
            builder.setPositiveButton("اعمال روی نقشه", (d, w) -> applyCommands(reply.commands));
        }
        builder.show();
    }

    private void applyCommands(List<String> commands) {
        String last = "";
        int applied = 0;
        for (String command : commands) {
            if (command == null || command.trim().isEmpty()) continue;
            last = cad.executeCommand(command.trim());
            applied++;
        }
        say(applied + " فرمان AI اعمال شد" + (last.isEmpty() ? "" : " — " + last));
    }

    private void showSettings() {
        EditText input = new EditText(activity);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setHint("https://your-server.example/api/chobyar-ai");
        input.setText(endpoint());
        input.setSelectAllOnFocus(true);

        new AlertDialog.Builder(activity)
                .setTitle("اتصال امن AI")
                .setMessage("آدرس بک‌اند چوب‌یار را وارد کن. کلید OpenAI داخل APK ذخیره نمی‌شود و فقط روی سرور می‌ماند.")
                .setView(input)
                .setPositiveButton("ذخیره", (d, w) -> {
                    String value = input.getText().toString().trim();
                    if (!value.startsWith("https://") && !value.startsWith("http://")) {
                        say("آدرس باید با https:// یا http:// شروع شود");
                        return;
                    }
                    prefs.edit().putString(KEY_ENDPOINT, value).apply();
                    say("آدرس AI ذخیره شد");
                })
                .setNeutralButton("پاک کردن", (d, w) -> {
                    prefs.edit().remove(KEY_ENDPOINT).apply();
                    say("اتصال AI پاک شد");
                })
                .setNegativeButton("لغو", null)
                .show();
    }

    private String endpoint() {
        return prefs.getString(KEY_ENDPOINT, "").trim();
    }

    private void say(String message) {
        if (status != null) status.setText(message);
    }
}

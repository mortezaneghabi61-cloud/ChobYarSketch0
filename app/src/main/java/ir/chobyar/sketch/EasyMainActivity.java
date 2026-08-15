package ir.chobyar.sketch;

import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * ChobYar's adaptive launcher: professional sketch/constraint tools stay under
 * a simple workspace, while Solid 3D, dual cm/mm dimensions and parametric
 * History are directly reachable.
 */
public class EasyMainActivity extends MainActivity {

    private ParametricHistorySolidCadCanvasView easyCad;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        installEasyCanvas();
    }

    private void installEasyCanvas() {
        try {
            Field cadField = MainActivity.class.getDeclaredField("cad");
            cadField.setAccessible(true);
            Object oldObject = cadField.get(this);
            if (!(oldObject instanceof View)) return;
            View oldCad = (View) oldObject;

            View content = findViewById(android.R.id.content);
            if (!(content instanceof ViewGroup)) return;
            ViewGroup contentGroup = (ViewGroup) content;
            if (contentGroup.getChildCount() == 0) return;
            View rootView = contentGroup.getChildAt(0);
            if (!(rootView instanceof FrameLayout)) return;
            FrameLayout root = (FrameLayout) rootView;

            int index = root.indexOfChild(oldCad);
            ViewGroup.LayoutParams oldParams = oldCad.getLayoutParams();

            easyCad = new ParametricHistorySolidCadCanvasView(this);
            wireMainActivityCallbacks(easyCad);

            root.removeView(oldCad);
            root.addView(easyCad, Math.max(0, index), oldParams);
            cadField.set(this, easyCad);

            root.addView(makeRelationsButton(), relationsParams());
            root.addView(makePlaneButton(), planeParams());
            root.addView(makeSolidButton(), solidParams());
            root.addView(makeHistoryButton(), historyParams());
            patchUnitChrome(root);
            easyCad.dispatchWorkspaceState();
        } catch (Exception e) {
            Toast.makeText(this, "فضای CAD تطبیقی فعال نشد", Toast.LENGTH_SHORT).show();
        }
    }

    private void wireMainActivityCallbacks(EasyCadCanvasView cad) {
        cad.setStatusListener(text -> invokeMain("say", new Class<?>[]{String.class}, text));
        cad.setDimensionEditListener(() -> invokeMain("showExactDimension", new Class<?>[0]));
        cad.setWorkspaceListener((info, exact, tool) -> invokeMain(
                "onWorkspaceStateChanged",
                new Class<?>[]{String.class, boolean.class, int.class},
                info, exact, tool));
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

    private Button makeRelationsButton() {
        Button b = floatingButton("⌁\nروابط", "روابط هوشمند Sketch");
        b.setOnClickListener(v -> {
            if (easyCad != null) easyCad.showSmartConstraintMenu();
        });
        return b;
    }

    private Button makePlaneButton() {
        Button b = floatingButton("◇\nPlane/3D", "صفحه Sketch و نمای سه‌بعدی");
        b.setOnClickListener(v -> {
            if (easyCad != null) easyCad.showPlaneManager();
        });
        return b;
    }

    private Button makeSolidButton() {
        Button b = floatingButton("▣\nSolid", "Body، Face، Extrude و Boolean سه‌بعدی");
        b.setOnClickListener(v -> {
            if (easyCad != null) easyCad.showSolidManager();
        });
        return b;
    }

    private Button makeHistoryButton() {
        Button b = floatingButton("⏱\nHistory", "تاریخچه پارامتریک و ویرایش Featureها");
        b.setOnClickListener(v -> {
            if (easyCad != null) easyCad.showHistoryManager();
        });
        return b;
    }

    /** Patch the original Shapr-inspired unit badge/status for dual cm/mm. */
    private void patchUnitChrome(View view) {
        if (view instanceof Button) {
            Button b = (Button) view;
            String t = String.valueOf(b.getText());
            if (t.contains("cm") && t.contains("واحد")) {
                b.setText("cm/mm\nواحد");
                b.setContentDescription("واحدهای سانتی‌متر و میلی‌متر");
                b.setOnClickListener(v -> Toast.makeText(
                        this,
                        easyCad == null ? "cm + mm" : easyCad.dualUnitSummary(),
                        Toast.LENGTH_LONG).show());
            }
        } else if (view instanceof TextView) {
            TextView tv = (TextView) view;
            String t = String.valueOf(tv.getText());
            if (t.startsWith("cm |")) tv.setText(t.replaceFirst("cm \\|", "cm + mm |"));
        }
        if (view instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) view;
            for (int i=0;i<g.getChildCount();i++) patchUnitChrome(g.getChildAt(i));
        }
    }

    private Button floatingButton(String text, String description) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(10f);
        b.setAllCaps(false);
        b.setTextColor(Color.rgb(35, 75, 145));
        b.setGravity(Gravity.CENTER);
        b.setMinWidth(dp(66));
        b.setMinimumWidth(dp(66));
        b.setMinHeight(dp(58));
        b.setMinimumHeight(dp(58));
        b.setPadding(dp(4), dp(3), dp(4), dp(3));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.argb(248, 239, 246, 255));
        bg.setStroke(dp(1), Color.rgb(120, 160, 225));
        bg.setCornerRadius(dp(17));
        b.setBackground(bg);
        b.setElevation(dp(6));
        b.setContentDescription(description);
        return b;
    }

    private FrameLayout.LayoutParams relationsParams() {
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.END | Gravity.CENTER_VERTICAL);
        p.setMargins(0, 0, dp(10), dp(225));
        return p;
    }

    private FrameLayout.LayoutParams planeParams() {
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.END | Gravity.CENTER_VERTICAL);
        p.setMargins(0, 0, dp(10), dp(75));
        return p;
    }

    private FrameLayout.LayoutParams solidParams() {
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.END | Gravity.CENTER_VERTICAL);
        p.setMargins(0, dp(75), dp(10), 0);
        return p;
    }

    private FrameLayout.LayoutParams historyParams() {
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.END | Gravity.CENTER_VERTICAL);
        p.setMargins(0, dp(225), dp(10), 0);
        return p;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}

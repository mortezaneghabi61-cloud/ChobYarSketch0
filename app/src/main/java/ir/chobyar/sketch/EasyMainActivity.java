package ir.chobyar.sketch;

import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.Toast;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Keeps the existing ChobYar workspace, but swaps the CAD canvas for the easy
 * adaptive layer and adds one always-visible "روابط" button. The rest of the
 * original UI continues to use MainActivity's existing buttons and menus.
 */
public class EasyMainActivity extends MainActivity {

    private EasyCadCanvasView easyCad;

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

            easyCad = new EasyCadCanvasView(this);
            wireMainActivityCallbacks(easyCad);

            root.removeView(oldCad);
            root.addView(easyCad, Math.max(0, index), oldParams);
            cadField.set(this, easyCad);

            root.addView(makeRelationsButton(), relationsParams());
            easyCad.dispatchWorkspaceState();
        } catch (Exception e) {
            Toast.makeText(this, "حالت ساده روابط فعال نشد", Toast.LENGTH_SHORT).show();
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
        Button b = new Button(this);
        b.setText("⌁\nروابط");
        b.setTextSize(10f);
        b.setAllCaps(false);
        b.setTextColor(Color.rgb(35, 75, 145));
        b.setGravity(Gravity.CENTER);
        b.setMinWidth(dp(64));
        b.setMinimumWidth(dp(64));
        b.setMinHeight(dp(58));
        b.setMinimumHeight(dp(58));
        b.setPadding(dp(4), dp(3), dp(4), dp(3));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.argb(248, 239, 246, 255));
        bg.setStroke(dp(1), Color.rgb(120, 160, 225));
        bg.setCornerRadius(dp(17));
        b.setBackground(bg);
        b.setElevation(dp(6));
        b.setContentDescription("روابط هوشمند Sketch");
        b.setOnClickListener(v -> {
            if (easyCad != null) easyCad.showSmartConstraintMenu();
        });
        return b;
    }

    private FrameLayout.LayoutParams relationsParams() {
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.END | Gravity.CENTER_VERTICAL);
        p.setMargins(0, 0, dp(10), dp(120));
        return p;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}

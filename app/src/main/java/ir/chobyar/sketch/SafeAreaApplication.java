package ir.chobyar.sketch;

import android.app.Activity;
import android.app.Application;
import android.graphics.Insets;
import android.os.Build;
import android.os.Bundle;
import android.view.DisplayCutout;
import android.view.View;
import android.view.WindowInsets;

/**
 * Applies safe drawing insets to every activity so toolbars and touch targets
 * never sit underneath a camera hole/notch or visible system bars.
 */
public class SafeAreaApplication extends Application implements Application.ActivityLifecycleCallbacks {

    @Override
    public void onCreate() {
        super.onCreate();
        registerActivityLifecycleCallbacks(this);
    }

    private void applySafeArea(Activity activity) {
        View content = activity.findViewById(android.R.id.content);
        if (content == null) return;

        content.setOnApplyWindowInsetsListener((v, insets) -> {
            int left = 0;
            int top = 0;
            int right = 0;
            int bottom = 0;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                Insets cutout = insets.getInsets(WindowInsets.Type.displayCutout());
                left = Math.max(bars.left, cutout.left);
                top = Math.max(bars.top, cutout.top);
                right = Math.max(bars.right, cutout.right);
                bottom = Math.max(bars.bottom, cutout.bottom);
            } else {
                left = insets.getSystemWindowInsetLeft();
                top = insets.getSystemWindowInsetTop();
                right = insets.getSystemWindowInsetRight();
                bottom = insets.getSystemWindowInsetBottom();

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    DisplayCutout cutout = insets.getDisplayCutout();
                    if (cutout != null) {
                        left = Math.max(left, cutout.getSafeInsetLeft());
                        top = Math.max(top, cutout.getSafeInsetTop());
                        right = Math.max(right, cutout.getSafeInsetRight());
                        bottom = Math.max(bottom, cutout.getSafeInsetBottom());
                    }
                }
            }

            v.setPadding(left, top, right, bottom);
            return insets;
        });

        content.requestApplyInsets();
    }

    private void applyWorkspaceChrome(Activity activity) {
        View content = activity.findViewById(android.R.id.content);
        if (content == null) return;
        content.post(() -> FigmaWorkspaceStyler.apply(activity));
    }

    @Override
    public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
        applySafeArea(activity);
        applyWorkspaceChrome(activity);
    }

    @Override
    public void onActivityResumed(Activity activity) {
        // Immersive flags can change insets after focus returns, so refresh them.
        applySafeArea(activity);
        applyWorkspaceChrome(activity);
    }

    @Override public void onActivityStarted(Activity activity) {}
    @Override public void onActivityPaused(Activity activity) {}
    @Override public void onActivityStopped(Activity activity) {}
    @Override public void onActivitySaveInstanceState(Activity activity, Bundle outState) {}
    @Override public void onActivityDestroyed(Activity activity) {}
}

package ir.chobyar.sketch;

import android.app.Instrumentation;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Field;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Pinpoints system-injected S Pen routing at the exact-dimension label.
 * The injected event shape intentionally matches TouchInputContractInstrumentationTest,
 * whose stylus create/select path is already a passing production contract.
 */
@RunWith(AndroidJUnit4.class)
public final class StylusDimensionLabelRoutingInstrumentationTest {

    @Test
    public void systemStylusDownReachesExactLabelInCanvasCoordinates() {
        Instrumentation inst = InstrumentationRegistry.getInstrumentation();
        try (ActivityScenario<ChobYarActivity> scenario = ActivityScenario.launch(ChobYarActivity.class)) {
            inst.waitForIdleSync();
            final Shapr3DGuideCadCanvasView[] holder = new Shapr3DGuideCadCanvasView[1];
            final float[] screenTarget = new float[2];
            final float[] localTarget = new float[2];
            final float[] seen = new float[]{Float.NaN, Float.NaN, Float.NaN, Float.NaN};
            final int[] seenMeta = new int[]{-1, -1};
            final boolean[] seenDown = new boolean[1];

            scenario.onActivity(activity -> {
                Shapr3DGuideCadCanvasView c = find(activity.getWindow().getDecorView());
                assertNotNull(c);
                holder[0] = c;
                c.clearAll();
                c.executeCommand("RECT 0 0 40 20");
                assertNotNull(c.selected);
                c.setTool(CadCanvasView.TOOL_SELECT);
                centerSelectedEntityInSafeCanvas(c);
                c.setOnTouchListener((v, e) -> {
                    if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
                        seenDown[0] = true;
                        seen[0] = e.getX();
                        seen[1] = e.getY();
                        seen[2] = e.getRawX();
                        seen[3] = e.getRawY();
                        seenMeta[0] = e.getToolType(0);
                        seenMeta[1] = e.getSource();
                    }
                    return false;
                });
                c.invalidate();
            });
            inst.waitForIdleSync();
            SystemClock.sleep(120L);

            scenario.onActivity(activity -> {
                Shapr3DGuideCadCanvasView c = holder[0];
                RectF label = exactFieldRect(c);
                assertFalse("Exact dimension label was not drawn", label.isEmpty());
                localTarget[0] = label.centerX();
                localTarget[1] = label.centerY();
                assertTrue("Exact label must stay inside safe horizontal canvas area: " + label,
                        localTarget[0] > c.getWidth() * 0.25f && localTarget[0] < c.getWidth() * 0.75f);
                assertTrue("Exact label must stay inside safe vertical canvas area: " + label,
                        localTarget[1] > c.getHeight() * 0.25f && localTarget[1] < c.getHeight() * 0.75f);
                int[] loc = new int[2];
                c.getLocationOnScreen(loc);
                screenTarget[0] = loc[0] + localTarget[0];
                screenTarget[1] = loc[1] + localTarget[1];
            });

            long down = SystemClock.uptimeMillis();
            send(inst, one(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_DOWN,
                    screenTarget[0], screenTarget[1]));
            inst.waitForIdleSync();

            final boolean[] exactPressed = new boolean[1];
            scenario.onActivity(activity -> exactPressed[0] = privateBoolean(holder[0], "exactFieldPressed"));

            String diagnostic = "targetLocal=" + localTarget[0] + "," + localTarget[1]
                    + " targetScreen=" + screenTarget[0] + "," + screenTarget[1]
                    + " seenLocal=" + seen[0] + "," + seen[1]
                    + " seenRaw=" + seen[2] + "," + seen[3]
                    + " seenTool=" + seenMeta[0]
                    + " seenSource=" + seenMeta[1]
                    + " exactPressed=" + exactPressed[0];

            assertTrue("Stylus ACTION_DOWN never reached the CAD View; " + diagnostic, seenDown[0]);
            assertTrue("Stylus local X differs from label target; " + diagnostic,
                    Math.abs(seen[0] - localTarget[0]) <= 3f);
            assertTrue("Stylus local Y differs from label target; " + diagnostic,
                    Math.abs(seen[1] - localTarget[1]) <= 3f);
            assertTrue("Injected event lost stylus tool type; " + diagnostic,
                    seenMeta[0] == MotionEvent.TOOL_TYPE_STYLUS);
            assertTrue("Injected event lost stylus source; " + diagnostic,
                    (seenMeta[1] & InputDevice.SOURCE_STYLUS) == InputDevice.SOURCE_STYLUS);
            assertTrue("Exact label did not arm despite correct local stylus hit; " + diagnostic,
                    exactPressed[0]);

            SystemClock.sleep(55L);
            send(inst, one(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP,
                    screenTarget[0], screenTarget[1]));
        }
    }

    private static void centerSelectedEntityInSafeCanvas(Shapr3DGuideCadCanvasView c) {
        RectF b = c.selected.bounds();
        float wx = (b.left + b.right) * 0.5f;
        float wy = (b.top + b.bottom) * 0.5f;
        c.offsetX = c.getWidth() * 0.50f - wx * 3f * c.viewScale;
        c.offsetY = c.getHeight() * 0.55f - wy * 3f * c.viewScale;
    }

    private static RectF exactFieldRect(Shapr3DGuideCadCanvasView c) {
        try {
            Field f = ShaprStyleCadCanvasView.class.getDeclaredField("exactFieldRect");
            f.setAccessible(true);
            Object value = f.get(c);
            return value instanceof RectF ? new RectF((RectF) value) : new RectF();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static boolean privateBoolean(Object target, String name) {
        try {
            Field f = ShaprStyleCadCanvasView.class.getDeclaredField(name);
            f.setAccessible(true);
            return f.getBoolean(target);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static Shapr3DGuideCadCanvasView find(View v) {
        if (v instanceof Shapr3DGuideCadCanvasView) return (Shapr3DGuideCadCanvasView) v;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                Shapr3DGuideCadCanvasView found = find(g.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    private static void send(Instrumentation inst, MotionEvent e) {
        try { inst.sendPointerSync(e); }
        finally { e.recycle(); }
    }

    private static MotionEvent one(long down, long time, int action, float x, float y) {
        MotionEvent.PointerProperties p = new MotionEvent.PointerProperties();
        p.id = 0;
        p.toolType = MotionEvent.TOOL_TYPE_STYLUS;
        MotionEvent.PointerCoords c = new MotionEvent.PointerCoords();
        c.x = x;
        c.y = y;
        c.pressure = 1f;
        c.size = 1f;
        c.touchMajor = 12f;
        c.touchMinor = 12f;
        return MotionEvent.obtain(down, time, action, 1,
                new MotionEvent.PointerProperties[]{p}, new MotionEvent.PointerCoords[]{c},
                0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_STYLUS, 0);
    }
}

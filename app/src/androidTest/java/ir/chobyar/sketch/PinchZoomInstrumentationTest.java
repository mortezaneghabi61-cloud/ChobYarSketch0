package ir.chobyar.sketch;

import android.app.Instrumentation;
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

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Regression coverage for the production two-finger ScaleGestureDetector path.
 * Input is injected at window level so the exact activity/view dispatch chain
 * used by a real touchscreen is exercised. This file is test-only and does not
 * add or change user-facing UI.
 */
@RunWith(AndroidJUnit4.class)
public final class PinchZoomInstrumentationTest {

    @Test
    public void realTwoFingerPinchChangesViewportScale() {
        final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();

        try (ActivityScenario<ChobYarActivity> scenario = ActivityScenario.launch(ChobYarActivity.class)) {
            instrumentation.waitForIdleSync();

            final float[] scales = new float[2];
            // left, top, width, height in physical screen coordinates.
            final int[] frame = new int[4];

            scenario.onActivity(activity -> {
                final Shapr3DGuideCadCanvasView canvas = findProductionCanvas(
                        activity.getWindow().getDecorView());
                assertNotNull("Production Shapr3DGuide CAD canvas was not found", canvas);
                assertTrue("CAD canvas was not laid out",
                        canvas.getWidth() > 600 && canvas.getHeight() > 300);

                final int[] location = new int[2];
                canvas.getLocationOnScreen(location);
                frame[0] = location[0];
                frame[1] = location[1];
                frame[2] = canvas.getWidth();
                frame[3] = canvas.getHeight();
                scales[0] = canvas.viewScale;
            });

            // ScaleGestureDetector has a density-dependent minimum span. The old
            // 120-270 px synthetic gesture was too small on the API-35 560-dpi
            // emulator. Use a normal tablet/phone-sized pinch that starts wide
            // enough and expands substantially, while staying clear of tool rails.
            final float cx = frame[0] + frame[2] * 0.52f;
            final float cy = frame[1] + frame[3] * 0.52f;
            final float startHalf = Math.min(frame[2] * 0.20f, 420f);
            final float endHalf = Math.min(frame[2] * 0.40f, 720f);
            assertTrue("Canvas is too narrow for a reliable pinch regression",
                    startHalf >= 120f && endHalf > startHalf * 1.45f);

            final long down = SystemClock.uptimeMillis();
            send(instrumentation, one(down, now(), MotionEvent.ACTION_DOWN,
                    cx - startHalf, cy));
            SystemClock.sleep(45L);

            send(instrumentation, two(down, now(),
                    MotionEvent.ACTION_POINTER_DOWN
                            | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                    cx - startHalf, cy, cx + startHalf, cy));
            SystemClock.sleep(55L);

            final float mid1 = startHalf + (endHalf - startHalf) * 0.35f;
            final float mid2 = startHalf + (endHalf - startHalf) * 0.70f;
            send(instrumentation, two(down, now(), MotionEvent.ACTION_MOVE,
                    cx - mid1, cy, cx + mid1, cy));
            SystemClock.sleep(55L);
            send(instrumentation, two(down, now(), MotionEvent.ACTION_MOVE,
                    cx - mid2, cy, cx + mid2, cy));
            SystemClock.sleep(55L);
            send(instrumentation, two(down, now(), MotionEvent.ACTION_MOVE,
                    cx - endHalf, cy, cx + endHalf, cy));
            SystemClock.sleep(45L);

            send(instrumentation, two(down, now(),
                    MotionEvent.ACTION_POINTER_UP
                            | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                    cx - endHalf, cy, cx + endHalf, cy));
            SystemClock.sleep(30L);
            send(instrumentation, one(down, now(), MotionEvent.ACTION_UP,
                    cx - endHalf, cy));

            instrumentation.waitForIdleSync();
            scenario.onActivity(activity -> {
                final Shapr3DGuideCadCanvasView canvas = findProductionCanvas(
                        activity.getWindow().getDecorView());
                assertNotNull("Production CAD canvas disappeared after pinch", canvas);
                scales[1] = canvas.viewScale;
            });

            System.out.println("PINCH_ZOOM_RESULT before=" + scales[0]
                    + " after=" + scales[1]);
            assertTrue(
                    "Real pinch gesture did not zoom viewport: before=" + scales[0]
                            + " after=" + scales[1],
                    scales[1] > scales[0] * 1.05f
            );
        }
    }

    private static long now() {
        return SystemClock.uptimeMillis();
    }

    private static Shapr3DGuideCadCanvasView findProductionCanvas(View view) {
        if (view instanceof Shapr3DGuideCadCanvasView) {
            return (Shapr3DGuideCadCanvasView) view;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                Shapr3DGuideCadCanvasView found = findProductionCanvas(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    private static void send(Instrumentation instrumentation, MotionEvent event) {
        try {
            instrumentation.sendPointerSync(event);
        } finally {
            event.recycle();
        }
    }

    private static MotionEvent one(long down, long time, int action, float x, float y) {
        return MotionEvent.obtain(
                down, time, action, 1,
                new MotionEvent.PointerProperties[]{pointer(0)},
                new MotionEvent.PointerCoords[]{coords(x, y)},
                0, 0, 1f, 1f, 0, 0,
                InputDevice.SOURCE_TOUCHSCREEN, 0
        );
    }

    private static MotionEvent two(
            long down, long time, int action,
            float x0, float y0, float x1, float y1) {
        return MotionEvent.obtain(
                down, time, action, 2,
                new MotionEvent.PointerProperties[]{pointer(0), pointer(1)},
                new MotionEvent.PointerCoords[]{coords(x0, y0), coords(x1, y1)},
                0, 0, 1f, 1f, 0, 0,
                InputDevice.SOURCE_TOUCHSCREEN, 0
        );
    }

    private static MotionEvent.PointerProperties pointer(int id) {
        MotionEvent.PointerProperties p = new MotionEvent.PointerProperties();
        p.id = id;
        p.toolType = MotionEvent.TOOL_TYPE_FINGER;
        return p;
    }

    private static MotionEvent.PointerCoords coords(float x, float y) {
        MotionEvent.PointerCoords c = new MotionEvent.PointerCoords();
        c.x = x;
        c.y = y;
        c.pressure = 1f;
        c.size = 1f;
        c.touchMajor = 12f;
        c.touchMinor = 12f;
        return c;
    }
}

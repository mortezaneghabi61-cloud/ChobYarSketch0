package ir.chobyar.sketch;

import android.os.SystemClock;
import android.test.ActivityInstrumentationTestCase2;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;

/**
 * Regression coverage for the production two-finger ScaleGestureDetector path.
 * This is test-only code and does not add or change any user-facing UI.
 */
@SuppressWarnings("deprecation")
public final class PinchZoomInstrumentationTest
        extends ActivityInstrumentationTestCase2<ChobYarActivity> {

    public PinchZoomInstrumentationTest() {
        super(ChobYarActivity.class);
    }

    public void testRealTwoFingerPinchChangesViewportScale() throws Throwable {
        final ChobYarActivity activity = getActivity();
        getInstrumentation().waitForIdleSync();

        final CadCanvasView canvas = findCanvas(activity.getWindow().getDecorView());
        assertNotNull("Production CAD canvas was not found", canvas);
        assertTrue("CAD canvas was not laid out", canvas.getWidth() > 200 && canvas.getHeight() > 200);

        final float[] scales = new float[2];
        runTestOnUiThread(() -> {
            scales[0] = canvas.viewScale;

            final float cx = canvas.getWidth() * 0.55f;
            final float cy = canvas.getHeight() * 0.50f;
            final long down = SystemClock.uptimeMillis();

            dispatch(canvas, one(down, down, MotionEvent.ACTION_DOWN, cx - 60f, cy));
            dispatch(canvas, two(down, down + 16,
                    MotionEvent.ACTION_POINTER_DOWN | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                    cx - 60f, cy, cx + 60f, cy));
            dispatch(canvas, two(down, down + 32, MotionEvent.ACTION_MOVE,
                    cx - 75f, cy, cx + 75f, cy));
            dispatch(canvas, two(down, down + 48, MotionEvent.ACTION_MOVE,
                    cx - 105f, cy, cx + 105f, cy));
            dispatch(canvas, two(down, down + 64, MotionEvent.ACTION_MOVE,
                    cx - 135f, cy, cx + 135f, cy));
            dispatch(canvas, two(down, down + 80,
                    MotionEvent.ACTION_POINTER_UP | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                    cx - 135f, cy, cx + 135f, cy));
            dispatch(canvas, one(down, down + 96, MotionEvent.ACTION_UP, cx - 135f, cy));

            scales[1] = canvas.viewScale;
        });
        getInstrumentation().waitForIdleSync();

        assertTrue(
                "Real pinch gesture did not zoom viewport: before=" + scales[0] + " after=" + scales[1],
                scales[1] > scales[0] * 1.05f
        );
    }

    private static CadCanvasView findCanvas(View view) {
        if (view instanceof CadCanvasView) return (CadCanvasView) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                CadCanvasView found = findCanvas(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    private static void dispatch(View view, MotionEvent event) {
        try {
            view.dispatchTouchEvent(event);
        } finally {
            event.recycle();
        }
    }

    private static MotionEvent one(long down, long time, int action, float x, float y) {
        MotionEvent.PointerProperties property = pointer(0);
        MotionEvent.PointerCoords coord = coords(x, y);
        return MotionEvent.obtain(
                down, time, action, 1,
                new MotionEvent.PointerProperties[]{property},
                new MotionEvent.PointerCoords[]{coord},
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
        return c;
    }
}

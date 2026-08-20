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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Regression coverage for the stylus-first Sketch input contract.
 *
 * Production expectations:
 * - stylus can create and select geometry;
 * - two-finger navigation cancels any in-flight drawing and never creates geometry;
 * - two-finger navigation remains available even when the first finger lands on
 *   the floating exact-dimension label.
 */
@RunWith(AndroidJUnit4.class)
public final class TouchInputContractInstrumentationTest {

    @Test
    public void stylusDrawsRectangleAndReselectsIt() {
        final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        try (ActivityScenario<ChobYarActivity> scenario = ActivityScenario.launch(ChobYarActivity.class)) {
            instrumentation.waitForIdleSync();
            final CanvasState state = capture(scenario);

            scenario.onActivity(activity -> {
                Shapr3DGuideCadCanvasView canvas = findProductionCanvas(activity.getWindow().getDecorView());
                assertNotNull(canvas);
                canvas.clearAll();
                canvas.setTool(CadCanvasView.TOOL_RECT);
            });
            instrumentation.waitForIdleSync();

            final float x0 = state.left + state.width * 0.38f;
            final float y0 = state.top + state.height * 0.38f;
            final float x1 = state.left + state.width * 0.62f;
            final float y1 = state.top + state.height * 0.62f;
            stylusDrag(instrumentation, x0, y0, x1, y1);
            instrumentation.waitForIdleSync();

            final float[] edgeScreen = new float[2];
            scenario.onActivity(activity -> {
                Shapr3DGuideCadCanvasView canvas = findProductionCanvas(activity.getWindow().getDecorView());
                assertNotNull(canvas);
                assertEquals("Stylus rectangle must create exactly one entity", 1, canvas.entities.size());
                assertNotNull("New stylus rectangle must be selected", canvas.selected);
                assertEquals(CadCanvasView.TOOL_SELECT, canvas.getTool());

                CadCanvasView.Entity rectangle = canvas.entities.get(0);
                RectF b = rectangle.bounds();
                float wx = (b.left + b.right) * 0.5f;
                float wy = b.top;
                edgeScreen[0] = state.left + wx * 3f * canvas.viewScale + canvas.offsetX;
                edgeScreen[1] = state.top + wy * 3f * canvas.viewScale + canvas.offsetY;
                canvas.clearSmartSelection();
                canvas.setTool(CadCanvasView.TOOL_SELECT);
            });
            instrumentation.waitForIdleSync();

            stylusTap(instrumentation, edgeScreen[0], edgeScreen[1]);
            instrumentation.waitForIdleSync();
            scenario.onActivity(activity -> {
                Shapr3DGuideCadCanvasView canvas = findProductionCanvas(activity.getWindow().getDecorView());
                assertNotNull(canvas);
                assertNotNull("Stylus tap on the rectangle edge must select it", canvas.selected);
                assertEquals(1, canvas.selectedObjects.size());
            });
        }
    }

    @Test
    public void twoFingerPanCancelsPendingLineWithoutCreatingGeometry() {
        final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        try (ActivityScenario<ChobYarActivity> scenario = ActivityScenario.launch(ChobYarActivity.class)) {
            instrumentation.waitForIdleSync();
            final CanvasState state = capture(scenario);
            final float[] before = new float[3];

            scenario.onActivity(activity -> {
                Shapr3DGuideCadCanvasView canvas = findProductionCanvas(activity.getWindow().getDecorView());
                assertNotNull(canvas);
                canvas.clearAll();
                canvas.setTool(CadCanvasView.TOOL_LINE);
                before[0] = canvas.offsetX;
                before[1] = canvas.offsetY;
                before[2] = canvas.viewScale;
            });
            instrumentation.waitForIdleSync();

            final float cx = state.left + state.width * 0.52f;
            final float cy = state.top + state.height * 0.52f;
            twoFingerPan(instrumentation, cx - 190f, cy, cx + 190f, cy, 170f, 95f);
            instrumentation.waitForIdleSync();

            scenario.onActivity(activity -> {
                Shapr3DGuideCadCanvasView canvas = findProductionCanvas(activity.getWindow().getDecorView());
                assertNotNull(canvas);
                assertEquals("Two-finger navigation must not finish the pending line", 0, canvas.entities.size());
                assertFalse("Two-finger navigation must cancel drawing state", canvas.drawing);
                float moved = (float) Math.hypot(canvas.offsetX - before[0], canvas.offsetY - before[1]);
                assertTrue("Two-finger pan did not move viewport enough: moved=" + moved, moved > 70f);
                assertTrue("Pure pan unexpectedly changed zoom too much: before=" + before[2]
                                + " after=" + canvas.viewScale,
                        Math.abs(canvas.viewScale - before[2]) < 0.08f);
            });
        }
    }

    @Test
    public void pinchOverridesExactDimensionLabelGesture() throws Exception {
        final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        try (ActivityScenario<ChobYarActivity> scenario = ActivityScenario.launch(ChobYarActivity.class)) {
            instrumentation.waitForIdleSync();
            final CanvasState state = capture(scenario);

            scenario.onActivity(activity -> {
                Shapr3DGuideCadCanvasView canvas = findProductionCanvas(activity.getWindow().getDecorView());
                assertNotNull(canvas);
                canvas.clearAll();
                canvas.setTool(CadCanvasView.TOOL_RECT);
            });
            instrumentation.waitForIdleSync();

            stylusDrag(instrumentation,
                    state.left + state.width * 0.40f,
                    state.top + state.height * 0.43f,
                    state.left + state.width * 0.63f,
                    state.top + state.height * 0.66f);
            instrumentation.waitForIdleSync();
            SystemClock.sleep(180L);

            final float[] start = new float[3];
            scenario.onActivity(activity -> {
                Shapr3DGuideCadCanvasView canvas = findProductionCanvas(activity.getWindow().getDecorView());
                assertNotNull(canvas);
                canvas.invalidate();
            });
            instrumentation.waitForIdleSync();
            SystemClock.sleep(120L);

            scenario.onActivity(activity -> {
                Shapr3DGuideCadCanvasView canvas = findProductionCanvas(activity.getWindow().getDecorView());
                assertNotNull(canvas);
                RectF label = exactFieldRect(canvas);
                assertFalse("Exact dimension label was not drawn", label.isEmpty());
                start[0] = state.left + label.centerX();
                start[1] = state.top + label.centerY();
                start[2] = canvas.viewScale;
            });

            float secondX = Math.min(state.left + state.width - 90f, start[0] + 360f);
            if (secondX - start[0] < 180f) secondX = Math.max(state.left + 90f, start[0] - 360f);
            pinchFromFirstFinger(instrumentation, start[0], start[1], secondX, start[1]);
            instrumentation.waitForIdleSync();

            scenario.onActivity(activity -> {
                Shapr3DGuideCadCanvasView canvas = findProductionCanvas(activity.getWindow().getDecorView());
                assertNotNull(canvas);
                assertTrue("Two-finger pinch was blocked by exact dimension label: before="
                                + start[2] + " after=" + canvas.viewScale,
                        canvas.viewScale > start[2] * 1.05f);
            });
        }
    }

    private static CanvasState capture(ActivityScenario<ChobYarActivity> scenario) {
        final CanvasState state = new CanvasState();
        scenario.onActivity(activity -> {
            Shapr3DGuideCadCanvasView canvas = findProductionCanvas(activity.getWindow().getDecorView());
            assertNotNull("Production CAD canvas was not found", canvas);
            assertTrue("CAD canvas was not laid out", canvas.getWidth() > 600 && canvas.getHeight() > 300);
            int[] p = new int[2];
            canvas.getLocationOnScreen(p);
            state.left = p[0];
            state.top = p[1];
            state.width = canvas.getWidth();
            state.height = canvas.getHeight();
        });
        return state;
    }

    private static RectF exactFieldRect(Shapr3DGuideCadCanvasView canvas) {
        try {
            Field f = ShaprStyleCadCanvasView.class.getDeclaredField("exactFieldRect");
            f.setAccessible(true);
            Object value = f.get(canvas);
            return value instanceof RectF ? new RectF((RectF) value) : new RectF();
        } catch (Exception e) {
            throw new AssertionError("Could not inspect exact dimension label", e);
        }
    }

    private static Shapr3DGuideCadCanvasView findProductionCanvas(View view) {
        if (view instanceof Shapr3DGuideCadCanvasView) return (Shapr3DGuideCadCanvasView) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                Shapr3DGuideCadCanvasView found = findProductionCanvas(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    private static void stylusDrag(Instrumentation instrumentation,
                                   float x0, float y0, float x1, float y1) {
        long down = SystemClock.uptimeMillis();
        send(instrumentation, one(down, now(), MotionEvent.ACTION_DOWN, x0, y0,
                MotionEvent.TOOL_TYPE_STYLUS, InputDevice.SOURCE_STYLUS));
        SystemClock.sleep(45L);
        send(instrumentation, one(down, now(), MotionEvent.ACTION_MOVE,
                x0 + (x1 - x0) * 0.45f, y0 + (y1 - y0) * 0.45f,
                MotionEvent.TOOL_TYPE_STYLUS, InputDevice.SOURCE_STYLUS));
        SystemClock.sleep(45L);
        send(instrumentation, one(down, now(), MotionEvent.ACTION_MOVE, x1, y1,
                MotionEvent.TOOL_TYPE_STYLUS, InputDevice.SOURCE_STYLUS));
        SystemClock.sleep(35L);
        send(instrumentation, one(down, now(), MotionEvent.ACTION_UP, x1, y1,
                MotionEvent.TOOL_TYPE_STYLUS, InputDevice.SOURCE_STYLUS));
    }

    private static void stylusTap(Instrumentation instrumentation, float x, float y) {
        long down = SystemClock.uptimeMillis();
        send(instrumentation, one(down, now(), MotionEvent.ACTION_DOWN, x, y,
                MotionEvent.TOOL_TYPE_STYLUS, InputDevice.SOURCE_STYLUS));
        SystemClock.sleep(55L);
        send(instrumentation, one(down, now(), MotionEvent.ACTION_UP, x, y,
                MotionEvent.TOOL_TYPE_STYLUS, InputDevice.SOURCE_STYLUS));
    }

    private static void twoFingerPan(Instrumentation instrumentation,
                                     float x0, float y0, float x1, float y1,
                                     float dx, float dy) {
        long down = SystemClock.uptimeMillis();
        send(instrumentation, one(down, now(), MotionEvent.ACTION_DOWN, x0, y0,
                MotionEvent.TOOL_TYPE_FINGER, InputDevice.SOURCE_TOUCHSCREEN));
        SystemClock.sleep(35L);
        send(instrumentation, two(down, now(), pointerDownAction(), x0, y0, x1, y1));
        SystemClock.sleep(45L);
        for (int i = 1; i <= 3; i++) {
            float t = i / 3f;
            send(instrumentation, two(down, now(), MotionEvent.ACTION_MOVE,
                    x0 + dx * t, y0 + dy * t, x1 + dx * t, y1 + dy * t));
            SystemClock.sleep(45L);
        }
        send(instrumentation, two(down, now(), pointerUpAction(),
                x0 + dx, y0 + dy, x1 + dx, y1 + dy));
        SystemClock.sleep(25L);
        send(instrumentation, one(down, now(), MotionEvent.ACTION_UP,
                x0 + dx, y0 + dy, MotionEvent.TOOL_TYPE_FINGER, InputDevice.SOURCE_TOUCHSCREEN));
    }

    private static void pinchFromFirstFinger(Instrumentation instrumentation,
                                             float x0, float y0, float x1, float y1) {
        long down = SystemClock.uptimeMillis();
        send(instrumentation, one(down, now(), MotionEvent.ACTION_DOWN, x0, y0,
                MotionEvent.TOOL_TYPE_FINGER, InputDevice.SOURCE_TOUCHSCREEN));
        SystemClock.sleep(40L);
        send(instrumentation, two(down, now(), pointerDownAction(), x0, y0, x1, y1));
        SystemClock.sleep(50L);
        float direction = x1 >= x0 ? 1f : -1f;
        for (int i = 1; i <= 3; i++) {
            float t = i / 3f;
            send(instrumentation, two(down, now(), MotionEvent.ACTION_MOVE,
                    x0 - direction * 120f * t, y0,
                    x1 + direction * 220f * t, y1));
            SystemClock.sleep(55L);
        }
        send(instrumentation, two(down, now(), pointerUpAction(),
                x0 - direction * 120f, y0, x1 + direction * 220f, y1));
        SystemClock.sleep(30L);
        send(instrumentation, one(down, now(), MotionEvent.ACTION_UP,
                x0 - direction * 120f, y0, MotionEvent.TOOL_TYPE_FINGER,
                InputDevice.SOURCE_TOUCHSCREEN));
    }

    private static int pointerDownAction() {
        return MotionEvent.ACTION_POINTER_DOWN | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT);
    }

    private static int pointerUpAction() {
        return MotionEvent.ACTION_POINTER_UP | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT);
    }

    private static void send(Instrumentation instrumentation, MotionEvent event) {
        try {
            instrumentation.sendPointerSync(event);
        } finally {
            event.recycle();
        }
    }

    private static long now() {
        return SystemClock.uptimeMillis();
    }

    private static MotionEvent one(long down, long time, int action, float x, float y,
                                   int toolType, int source) {
        return MotionEvent.obtain(
                down, time, action, 1,
                new MotionEvent.PointerProperties[]{pointer(0, toolType)},
                new MotionEvent.PointerCoords[]{coords(x, y)},
                0, 0, 1f, 1f, 0, 0, source, 0);
    }

    private static MotionEvent two(long down, long time, int action,
                                   float x0, float y0, float x1, float y1) {
        return MotionEvent.obtain(
                down, time, action, 2,
                new MotionEvent.PointerProperties[]{
                        pointer(0, MotionEvent.TOOL_TYPE_FINGER),
                        pointer(1, MotionEvent.TOOL_TYPE_FINGER)},
                new MotionEvent.PointerCoords[]{coords(x0, y0), coords(x1, y1)},
                0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0);
    }

    private static MotionEvent.PointerProperties pointer(int id, int toolType) {
        MotionEvent.PointerProperties p = new MotionEvent.PointerProperties();
        p.id = id;
        p.toolType = toolType;
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

    private static final class CanvasState {
        int left;
        int top;
        int width;
        int height;
    }
}

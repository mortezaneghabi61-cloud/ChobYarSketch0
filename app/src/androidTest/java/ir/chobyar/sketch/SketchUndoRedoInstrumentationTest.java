package ir.chobyar.sketch;

import android.app.Instrumentation;
import android.os.SystemClock;
import android.util.Log;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Regression coverage for isolated Sketch undo/redo history. */
@RunWith(AndroidJUnit4.class)
public final class SketchUndoRedoInstrumentationTest {

    private static final String TAG = "SketchUndoRedo";

    @Test
    public void rectangleUndoRedoAndPanPreserveGeometry() {
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
                    state.left + state.width * 0.39f,
                    state.top + state.height * 0.40f,
                    state.left + state.width * 0.62f,
                    state.top + state.height * 0.64f);
            instrumentation.waitForIdleSync();

            final int[] counts = new int[4];
            final String[] createdStableId = new String[1];
            scenario.onActivity(activity -> {
                Shapr3DGuideCadCanvasView canvas = findProductionCanvas(activity.getWindow().getDecorView());
                assertNotNull(canvas);
                counts[0] = canvas.entities.size();
                assertEquals("Rectangle creation must produce one entity", 1, counts[0]);
                assertNotNull("Created rectangle must initially be selected", canvas.selected);
                createdStableId[0] = canvas.selected.stableId();
                assertNotNull("Created rectangle must have a stable model id", createdStableId[0]);
                assertTrue("Sketch Undo must be available after creation", canvas.canUndoSketch());
                assertFalse("Fresh edit must invalidate Sketch Redo", canvas.canRedoSketch());

                canvas.undo();
                counts[1] = canvas.entities.size();
                assertEquals("Undo must remove the created rectangle", 0, counts[1]);
                assertNull("Undo must not leave a selection for removed geometry", canvas.selected);
                assertTrue("Sketch Redo must be available after Undo", canvas.canRedoSketch());

                assertTrue("Redo must restore the undone Sketch snapshot", canvas.redoSketch());
                counts[2] = canvas.entities.size();
                assertEquals("Redo must restore exactly one rectangle", 1, counts[2]);
                assertNotNull("Redo must restore the model-owned selection projection", canvas.selected);
                assertEquals("Redo selection must resolve by the original stable entity id",
                        createdStableId[0], canvas.selected.stableId());
                assertTrue("Undo must remain available after Redo", canvas.canUndoSketch());
                assertFalse("Redo stack must be empty after Redo", canvas.canRedoSketch());

                // Start a drawing tool before navigation so two-finger Pan must
                // cancel any in-flight drawing without mutating the restored model.
                canvas.setTool(CadCanvasView.TOOL_LINE);
            });
            instrumentation.waitForIdleSync();

            final float cx = state.left + state.width * 0.52f;
            final float cy = state.top + state.height * 0.52f;
            twoFingerPan(instrumentation, cx - 190f, cy, cx + 190f, cy, 150f, 90f);
            instrumentation.waitForIdleSync();

            scenario.onActivity(activity -> {
                Shapr3DGuideCadCanvasView canvas = findProductionCanvas(activity.getWindow().getDecorView());
                assertNotNull(canvas);
                counts[3] = canvas.entities.size();
                assertEquals("Two-finger navigation after Redo must not create an entity", 1, counts[3]);
                assertFalse("Two-finger navigation must cancel drawing state", canvas.drawing);
                Log.i(TAG, "UNDO_REDO_RESULT afterCreate=" + counts[0]
                        + " afterUndo=" + counts[1]
                        + " afterRedo=" + counts[2]
                        + " afterPan=" + counts[3]);
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
                x0 + (x1 - x0) * 0.5f, y0 + (y1 - y0) * 0.5f,
                MotionEvent.TOOL_TYPE_STYLUS, InputDevice.SOURCE_STYLUS));
        SystemClock.sleep(45L);
        send(instrumentation, one(down, now(), MotionEvent.ACTION_MOVE, x1, y1,
                MotionEvent.TOOL_TYPE_STYLUS, InputDevice.SOURCE_STYLUS));
        SystemClock.sleep(35L);
        send(instrumentation, one(down, now(), MotionEvent.ACTION_UP, x1, y1,
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
        float left;
        float top;
        float width;
        float height;
    }
}

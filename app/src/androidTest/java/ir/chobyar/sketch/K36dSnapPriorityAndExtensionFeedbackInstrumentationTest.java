package ir.chobyar.sketch;

import android.content.Context;
import android.view.InputDevice;
import android.view.MotionEvent;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Field;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;

import ir.chobyar.sketch.core.SketchConstraint;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * API35 regression fence for K3.6d semantic snap arbitration and feedback.
 *
 * Endpoint > Midpoint > generic On-edge/extension is semantic priority, not a
 * nearest-pixel competition. Extension projection is model/source-aware and
 * must expose a distinct presentation label while retaining POINT_ON_ENTITY
 * semantics and a stable host ID when Auto Constraints is enabled.
 */
@RunWith(AndroidJUnit4.class)
public final class K36dSnapPriorityAndExtensionFeedbackInstrumentationTest {

    @Test public void touchAndStylusMidpointBeatCloserLineExtensionOutsideMagneticQuarterRadius() throws Exception {
        onMain(() -> {
            for (boolean stylus : new boolean[]{false, true}) {
                K33MirroredCadCanvasView cad = canvas(false);

                // Host A: midpoint = (50, 0). Pointer will be 3 mm away.
                cad.executeCommand("LINE 0 0 100 0");
                String midpointHostId = cad.selected.stableId();

                // Host B: x=49, y=20..30. Its supporting-line extension passes
                // 1 mm from pointer (50,3), but both endpoints are outside snap radius.
                cad.executeCommand("LINE 49 20 49 30");
                String extensionHostId = cad.selected.stableId();
                assertNotEquals(midpointHostId, extensionHostId);

                cad.setTool(CadCanvasView.TOOL_LINE);
                stroke(cad, screen(cad, 20f, 40f), screen(cad, 50f, 3f), stylus);
                cad.requireSketchMirrorParity();

                assertEquals("semantic priority must beat a closer extension projection",
                        "MIDPOINT", cad.sketchLastModelSnapKind());
                assertEquals("midpoint auto-connection remains active with Auto Constraints off",
                        1, cad.sketchConstraintCount());
                SketchConstraint c = cad.sketchConstraints().get(0);
                assertEquals(SketchConstraint.Kind.MIDPOINT, c.kind);
                assertEquals(1, c.primaryPointIndex);
                assertEquals(midpointHostId, c.secondaryEntityId);
                assertEquals(-1, c.secondaryPointIndex);
                assertEquals(0, cad.legacyMigratedConstraintTruthCount());

                // Create + automatic MIDPOINT remains one Undo step.
                cad.undo();
                cad.requireSketchMirrorParity();
                assertEquals(0, cad.sketchConstraintCount());
            }
            return true;
        });
    }

    @Test public void touchAndStylusExtensionUseDistinctFeedbackAndStableModelHost() throws Exception {
        onMain(() -> {
            for (boolean stylus : new boolean[]{false, true}) {
                K33MirroredCadCanvasView cad = canvas(true);
                cad.executeCommand("LINE 0 0 100 0");
                String hostId = cad.selected.stableId();

                cad.setTool(CadCanvasView.TOOL_LINE);
                stroke(cad, screen(cad, 20f, 24f), screen(cad, 118f, 0.15f), stylus);
                cad.requireSketchMirrorParity();

                assertEquals("extension keeps POINT_ON_ENTITY semantic kind",
                        "ON_EDGE", cad.sketchLastModelSnapKind());
                assertEquals("On extension", routedSnapLabel(cad));
                assertEquals(1, cad.sketchConstraintCount());
                SketchConstraint c = cad.sketchConstraints().get(0);
                assertEquals(SketchConstraint.Kind.POINT_ON_ENTITY, c.kind);
                assertEquals(1, c.primaryPointIndex);
                assertEquals("extension relationship must retain stable source host ID",
                        hostId, c.secondaryEntityId);
                assertEquals(-1, c.secondaryPointIndex);
                assertEquals(0, cad.legacyMigratedConstraintTruthCount());

                cad.undo();
                cad.requireSketchMirrorParity();
                assertEquals(0, cad.sketchConstraintCount());
                assertTrue(cad.redoSketch());
                cad.requireSketchMirrorParity();
                SketchConstraint redone = cad.sketchConstraints().get(0);
                assertEquals(SketchConstraint.Kind.POINT_ON_ENTITY, redone.kind);
                assertEquals(hostId, redone.secondaryEntityId);
                assertEquals(-1, redone.secondaryPointIndex);
            }
            return true;
        });
    }

    private static K33MirroredCadCanvasView canvas(boolean autoConstraints) throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        K33MirroredCadCanvasView cad = new K33MirroredCadCanvasView(context);
        cad.clearAll();
        Field f = ParametricSketchCanvasView.class.getDeclaredField("autoConstraints");
        f.setAccessible(true);
        f.setBoolean(cad, autoConstraints);
        return cad;
    }

    private static String routedSnapLabel(K33MirroredCadCanvasView cad) throws Exception {
        Field f = K33MirroredCadCanvasView.class.getDeclaredField("routedSnapLabel");
        f.setAccessible(true);
        Object value = f.get(cad);
        return value == null ? "" : value.toString();
    }

    private static void stroke(K33MirroredCadCanvasView cad, float[] a, float[] b, boolean stylus) {
        long down = 31_000L;
        send(cad, MotionEvent.ACTION_DOWN, a[0], a[1], down, down, stylus);
        send(cad, MotionEvent.ACTION_MOVE, b[0], b[1], down, down + 16L, stylus);
        send(cad, MotionEvent.ACTION_UP, b[0], b[1], down, down + 32L, stylus);
    }

    private static void send(K33MirroredCadCanvasView cad, int action, float x, float y,
                             long down, long time, boolean stylus) {
        MotionEvent event = MotionEvent.obtain(down, time, action, x, y, 0);
        event.setSource(stylus ? InputDevice.SOURCE_STYLUS : InputDevice.SOURCE_TOUCHSCREEN);
        cad.onTouchEvent(event);
        event.recycle();
    }

    private static float[] screen(K33MirroredCadCanvasView cad, float xMm, float yMm) throws Exception {
        JSONObject view = new JSONObject(cad.exportSketchProjectState()).getJSONObject("view");
        float scale = (float) view.getDouble("scale");
        float ox = (float) view.getDouble("offsetX");
        float oy = (float) view.getDouble("offsetY");
        return new float[]{ox + xMm * 3f * scale, oy + yMm * 3f * scale};
    }

    private static <T> T onMain(Callable<T> callable) throws Exception {
        FutureTask<T> task = new FutureTask<>(callable);
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().runOnMainSync(task);
        return task.get();
    }
}

package ir.chobyar.sketch;

import android.content.Context;
import android.view.InputDevice;
import android.view.MotionEvent;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Field;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;

import ir.chobyar.sketch.core.SketchConstraint;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * API35 touch/stylus regression fence for K3.6d snap arbitration and feedback.
 *
 * Semantic priority is authoritative inside the snap radius:
 * Endpoint > Midpoint > On-edge/extension. Presentation labels remain distinct
 * from durable model semantics; extension is displayed as "On extension" while
 * committing POINT_ON_ENTITY against its stable source host when policy allows.
 */
@RunWith(AndroidJUnit4.class)
public final class K36dSnapFeedbackPriorityInstrumentationTest {

    @Test public void touchAndStylusMidpointBeatCloserExtensionAndStayDurableWithAutoOff() throws Exception {
        onMain(() -> {
            for (boolean stylus : new boolean[]{false, true}) {
                K33MirroredCadCanvasView cad = canvas(false);
                cad.executeCommand("LINE 0 0 100 0");
                String midpointHostId = cad.selected.stableId();
                cad.executeCommand("LINE 0 4 40 4");
                String extensionHostId = cad.selected.stableId();
                assertNotEquals(midpointHostId, extensionHostId);
                Set<String> before = ids(cad.exportSketchProjectState());

                cad.setTool(CadCanvasView.TOOL_LINE);
                // Query (50,3): midpoint (50,0) is 3 mm away, but the second
                // line's extension (50,4) is only 1 mm away. Semantic priority
                // must still select MIDPOINT rather than the closer extension.
                stroke(cad, screen(cad, 20f, 24f), screen(cad, 50f, 3f), stylus);
                cad.requireSketchMirrorParity();

                String createdId = onlyNewId(before, cad.exportSketchProjectState());
                assertEquals("MIDPOINT", cad.sketchLastModelSnapKind());
                assertEquals("Midpoint", routedLabel(cad));
                assertEquals(1, cad.sketchConstraintCount());
                SketchConstraint c = cad.sketchConstraints().get(0);
                assertEquals(SketchConstraint.Kind.MIDPOINT, c.kind);
                assertEquals(createdId, c.primaryEntityId);
                assertEquals(1, c.primaryPointIndex);
                assertEquals(midpointHostId, c.secondaryEntityId);
                assertEquals(-1, c.secondaryPointIndex);
                assertEquals(0, cad.legacyMigratedConstraintTruthCount());

                // Create + automatic MIDPOINT remains one history transaction.
                cad.undo();
                cad.requireSketchMirrorParity();
                assertFalse(ids(cad.exportSketchProjectState()).contains(createdId));
                assertEquals(0, cad.sketchConstraintCount());

                assertTrue(cad.redoSketch());
                cad.requireSketchMirrorParity();
                assertTrue(ids(cad.exportSketchProjectState()).contains(createdId));
                assertEquals(1, cad.sketchConstraintCount());
                SketchConstraint redone = cad.sketchConstraints().get(0);
                assertEquals(SketchConstraint.Kind.MIDPOINT, redone.kind);
                assertEquals(midpointHostId, redone.secondaryEntityId);
                assertEquals(0, cad.legacyMigratedConstraintTruthCount());
            }
            return true;
        });
    }

    @Test public void touchAndStylusEndpointBeatCloserMidpointWithAutoOff() throws Exception {
        onMain(() -> {
            for (boolean stylus : new boolean[]{false, true}) {
                K33MirroredCadCanvasView cad = canvas(false);
                cad.executeCommand("LINE 0 0 100 0");
                String endpointHostId = cad.selected.stableId();
                cad.executeCommand("LINE 89 2 109 2"); // midpoint is (99,2)
                Set<String> before = ids(cad.exportSketchProjectState());

                cad.setTool(CadCanvasView.TOOL_LINE);
                // Query (99,1): midpoint is 1 mm away; endpoint (100,0) is
                // sqrt(2) mm away. ENDPOINT must win despite being farther.
                stroke(cad, screen(cad, 20f, 24f), screen(cad, 99f, 1f), stylus);
                cad.requireSketchMirrorParity();

                String createdId = onlyNewId(before, cad.exportSketchProjectState());
                assertEquals("ENDPOINT", cad.sketchLastModelSnapKind());
                assertEquals("Endpoint", routedLabel(cad));
                assertEquals(1, cad.sketchConstraintCount());
                SketchConstraint c = cad.sketchConstraints().get(0);
                assertEquals(SketchConstraint.Kind.COINCIDENT, c.kind);
                assertEquals(createdId, c.primaryEntityId);
                assertEquals(1, c.primaryPointIndex);
                assertEquals(endpointHostId, c.secondaryEntityId);
                assertEquals(1, c.secondaryPointIndex);
                assertEquals(0, cad.legacyMigratedConstraintTruthCount());

                cad.undo();
                cad.requireSketchMirrorParity();
                assertFalse(ids(cad.exportSketchProjectState()).contains(createdId));
                assertEquals(0, cad.sketchConstraintCount());
            }
            return true;
        });
    }

    @Test public void touchAndStylusExtensionExposeDistinctLabelAndStableHostRelationship() throws Exception {
        onMain(() -> {
            for (boolean stylus : new boolean[]{false, true}) {
                K33MirroredCadCanvasView cad = canvas(true);
                cad.executeCommand("LINE 0 0 100 0");
                String hostId = cad.selected.stableId();
                Set<String> before = ids(cad.exportSketchProjectState());

                cad.setTool(CadCanvasView.TOOL_LINE);
                stroke(cad, screen(cad, 20f, 24f), screen(cad, 118f, 0.15f), stylus);
                cad.requireSketchMirrorParity();

                String createdId = onlyNewId(before, cad.exportSketchProjectState());
                assertEquals("ON_EDGE", cad.sketchLastModelSnapKind());
                assertEquals("On extension", routedLabel(cad));
                assertEquals(1, cad.sketchConstraintCount());
                SketchConstraint c = cad.sketchConstraints().get(0);
                assertEquals(SketchConstraint.Kind.POINT_ON_ENTITY, c.kind);
                assertEquals(createdId, c.primaryEntityId);
                assertEquals(1, c.primaryPointIndex);
                assertEquals(hostId, c.secondaryEntityId);
                assertEquals(-1, c.secondaryPointIndex);
                assertEquals(0, cad.legacyMigratedConstraintTruthCount());

                cad.undo();
                cad.requireSketchMirrorParity();
                assertFalse(ids(cad.exportSketchProjectState()).contains(createdId));
                assertEquals(0, cad.sketchConstraintCount());
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

    private static String routedLabel(K33MirroredCadCanvasView cad) throws Exception {
        Field f = K33MirroredCadCanvasView.class.getDeclaredField("routedSnapLabel");
        f.setAccessible(true);
        Object value = f.get(cad);
        assertNotNull(value);
        return String.valueOf(value);
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

    private static Set<String> ids(String raw) throws Exception {
        JSONArray rows = new JSONObject(raw).getJSONArray("entities");
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (int i = 0; i < rows.length(); i++) out.add(rows.getJSONObject(i).getString("id"));
        return out;
    }

    private static String onlyNewId(Set<String> before, String raw) throws Exception {
        Set<String> after = ids(raw);
        after.removeAll(before);
        assertEquals(1, after.size());
        String id = after.iterator().next();
        assertNotNull(id);
        assertNotEquals("", id);
        return id;
    }

    private static <T> T onMain(Callable<T> callable) throws Exception {
        FutureTask<T> task = new FutureTask<>(callable);
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().runOnMainSync(task);
        return task.get();
    }
}

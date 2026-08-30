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

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;

import ir.chobyar.sketch.core.SketchConstraint;

import static org.junit.Assert.*;

/**
 * K3.6d interaction regression fence.
 *
 * Touch and stylus must route through the same stable-ID/point-index contract,
 * auto-constraints must share the Create undo record, and constrained drag
 * must not silently replace the user's current selection.
 */
@RunWith(AndroidJUnit4.class)
public class K36dTouchStylusSelectionStabilityInstrumentationTest {

    @Test public void touchAndStylusEndpointSnapShareCoincidentContractAndOneUndo() throws Exception {
        onMain(() -> {
            assertCreateSnapContract(false, true);
            assertCreateSnapContract(true, true);
            return true;
        });
    }

    @Test public void touchAndStylusInteriorSnapSharePointOnEntityContractAndOneUndo() throws Exception {
        onMain(() -> {
            assertCreateSnapContract(false, false);
            assertCreateSnapContract(true, false);
            return true;
        });
    }

    @Test public void constrainedHostDragKeepsSelectedStableIdForPointOnEntity() throws Exception {
        onMain(() -> {
            K33MirroredCadCanvasView cad = canvas();
            cad.executeCommand("LINE 0 0 30 0");
            CadCanvasView.Entity host = cad.selected;
            String hostId = host.stableId();
            cad.executeCommand("LINE 8 4 8 12");
            String ownerId = cad.selected.stableId();

            ConstraintInteractionContract.Result result =
                    cad.applyModelPointOnEntityForTest(ownerId, 0, hostId);
            assertEquals(ConstraintInteractionContract.ResultCode.APPLIED, result.code);

            select(cad, host);
            assertEquals(hostId, cad.selected.stableId());
            assertEquals(1, cad.selectedObjects.size());

            cad.moveSelected(0f, 5f);
            cad.requireSketchMirrorParity();

            assertNotNull("drag must preserve a selected entity", cad.selected);
            assertEquals("constrained solve must not jump selection to the driven entity",
                    hostId, cad.selected.stableId());
            assertEquals(1, cad.selectedObjects.size());
            assertEquals(hostId, cad.selectedObjects.iterator().next().stableId());
            assertEquals(1, cad.sketchConstraintCount());
            assertEquals(SketchConstraint.Kind.POINT_ON_ENTITY, cad.sketchConstraints().get(0).kind);
            assertEquals(0, cad.legacyMigratedConstraintTruthCount());
            return true;
        });
    }

    private static void assertCreateSnapContract(boolean stylus, boolean endpoint) throws Exception {
        K33MirroredCadCanvasView cad = canvas();
        cad.executeCommand("LINE 0 0 100 0");
        String hostId = cad.selected.stableId();
        Set<String> before = ids(cad.exportSketchProjectState());

        cad.setTool(CadCanvasView.TOOL_LINE);
        float targetX = endpoint ? 100.4f : 50.3f;
        stroke(cad, screen(cad, 20f, 25f), screen(cad, targetX, 0.2f), stylus);
        cad.requireSketchMirrorParity();

        String state = cad.exportSketchProjectState();
        String createdId = onlyNewId(before, state);
        assertEquals(1, cad.sketchConstraintCount());
        SketchConstraint constraint = cad.sketchConstraints().get(0);
        assertEquals(createdId, constraint.primaryEntityId);
        assertEquals(1, constraint.primaryPointIndex);
        assertEquals(hostId, constraint.secondaryEntityId);
        assertEquals(0, cad.legacyMigratedConstraintTruthCount());

        if (endpoint) {
            assertEquals("ENDPOINT", cad.sketchLastModelSnapKind());
            assertEquals(SketchConstraint.Kind.COINCIDENT, constraint.kind);
            assertEquals(1, constraint.secondaryPointIndex);
        } else {
            assertEquals("ON_EDGE", cad.sketchLastModelSnapKind());
            assertEquals(SketchConstraint.Kind.POINT_ON_ENTITY, constraint.kind);
            assertEquals(-1, constraint.secondaryPointIndex);
        }

        // Create + generated constraint is one model transaction/undo record.
        cad.undo();
        cad.requireSketchMirrorParity();
        assertFalse(ids(cad.exportSketchProjectState()).contains(createdId));
        assertEquals(0, cad.sketchConstraintCount());
    }

    private static K33MirroredCadCanvasView canvas() {
        Context context = ApplicationProvider.getApplicationContext();
        K33MirroredCadCanvasView view = new K33MirroredCadCanvasView(context);
        view.clearAll();
        return view;
    }

    private static void select(K33MirroredCadCanvasView cad, CadCanvasView.Entity value) {
        cad.selectedObjects.clear();
        cad.selectedObjects.add(value);
        cad.selected = value;
    }

    private static void stroke(K33MirroredCadCanvasView cad, float[] a, float[] b, boolean stylus) {
        long down = 19_000L;
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
        return after.iterator().next();
    }

    private static <T> T onMain(Callable<T> callable) throws Exception {
        FutureTask<T> task = new FutureTask<>(callable);
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().runOnMainSync(task);
        return task.get();
    }
}

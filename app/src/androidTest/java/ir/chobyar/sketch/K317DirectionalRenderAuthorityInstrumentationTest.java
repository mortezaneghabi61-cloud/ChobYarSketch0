package ir.chobyar.sketch;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.InputDevice;
import android.view.MotionEvent;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;

import ir.chobyar.sketch.core.SketchConstraint;
import ir.chobyar.sketch.core.SketchEntity;
import ir.chobyar.sketch.core.SketchGeometry;

/**
 * K3.17 RED fence for automatic directional inference and render authority.
 *
 * Historical production behavior keeps H/V and chained perpendicular inference
 * active independently of the generic Point-on-Entity Auto Constraints toggle.
 * K3.17 preserves that interaction policy while moving durable truth to stable-ID
 * SketchDocument constraints and making the inherited draw path presentation-only.
 */
@RunWith(AndroidJUnit4.class)
public final class K317DirectionalRenderAuthorityInstrumentationTest {
    private static final double EPS = 1.0e-4;

    @Test public void automaticHorizontalInferenceMustCommitStableIdModelConstraintWithoutLegacyTruth() throws Exception {
        onMain(() -> {
            K33MirroredCadCanvasView cad = cad(false);
            Set<String> before = ids(cad.exportSketchProjectState());

            cad.setTool(CadCanvasView.TOOL_LINE);
            stroke(cad, screen(cad, 20f, 30f), screen(cad, 80f, 31f), false);
            cad.requireSketchMirrorParity();

            String createdId = onlyNewId(before, cad.exportSketchProjectState());
            SketchConstraint constraint = onlyKind(cad, SketchConstraint.Kind.HORIZONTAL);
            assertEquals(createdId, constraint.primaryEntityId);
            assertHorizontal(modelLine(cad, createdId));
            assertEquals("automatic H must not create AxisLock/Object identity authority",
                    0, cad.legacyMigratedConstraintTruthCount());
            return true;
        });
    }

    @Test public void automaticVerticalInferenceMustCommitStableIdModelConstraintWithoutLegacyTruth() throws Exception {
        onMain(() -> {
            K33MirroredCadCanvasView cad = cad(false);
            Set<String> before = ids(cad.exportSketchProjectState());

            cad.setTool(CadCanvasView.TOOL_LINE);
            stroke(cad, screen(cad, 30f, 20f), screen(cad, 31f, 80f), true);
            cad.requireSketchMirrorParity();

            String createdId = onlyNewId(before, cad.exportSketchProjectState());
            SketchConstraint constraint = onlyKind(cad, SketchConstraint.Kind.VERTICAL);
            assertEquals(createdId, constraint.primaryEntityId);
            assertVertical(modelLine(cad, createdId));
            assertEquals("automatic V must not create AxisLock/Object identity authority",
                    0, cad.legacyMigratedConstraintTruthCount());
            return true;
        });
    }

    @Test public void automaticPerpendicularInferenceMustJoinCreateTransactionAsStableIdModelConstraint() throws Exception {
        onMain(() -> {
            K33MirroredCadCanvasView cad = cad(false);

            cad.setTool(CadCanvasView.TOOL_LINE);
            stroke(cad, screen(cad, 20f, 20f), screen(cad, 80f, 40f), false);
            String anchorId = cad.selected.stableId();
            assertEquals(0, cad.sketchConstraintCount());

            Set<String> beforeSecond = ids(cad.exportSketchProjectState());
            cad.setTool(CadCanvasView.TOOL_LINE);
            stroke(cad, screen(cad, 80f, 40f), screen(cad, 70f, 70f), false);
            cad.requireSketchMirrorParity();

            String drivenId = onlyNewId(beforeSecond, cad.exportSketchProjectState());
            assertTrue("automatic chained P must be model-owned by the two stable line IDs",
                    hasBinaryConstraint(cad, SketchConstraint.Kind.PERPENDICULAR, anchorId, drivenId));
            assertPerpendicular(modelLine(cad, anchorId), modelLine(cad, drivenId));
            assertEquals("automatic P must not create LineRelation/Object identity authority",
                    0, cad.legacyMigratedConstraintTruthCount());

            // Endpoint Coincident + Perpendicular + created line must share the same Create history step.
            cad.undo();
            cad.requireSketchMirrorParity();
            assertFalse(ids(cad.exportSketchProjectState()).contains(drivenId));
            assertEquals(0, countKind(cad, SketchConstraint.Kind.PERPENDICULAR));
            assertTrue(cad.redoSketch());
            cad.requireSketchMirrorParity();
            assertTrue(ids(cad.exportSketchProjectState()).contains(drivenId));
            assertTrue(hasBinaryConstraint(cad, SketchConstraint.Kind.PERPENDICULAR, anchorId, drivenId));
            assertEquals(0, cad.legacyMigratedConstraintTruthCount());
            return true;
        });
    }

    @Test public void drawMustNeverRepairAutomaticDirectionalLegacyDriftOrMutateModelHistoryIdsOrPersistence() throws Exception {
        onMain(() -> {
            K33MirroredCadCanvasView cad = cad(false);
            cad.setTool(CadCanvasView.TOOL_LINE);
            stroke(cad, screen(cad, 20f, 30f), screen(cad, 80f, 31f), false);
            CadCanvasView.LineEntity legacy = (CadCanvasView.LineEntity) cad.selected;
            String id = legacy.stableId();

            long transitionsBefore = cad.sketchAuthorityTransitionCount();
            long mirrorsBefore = cad.sketchMirrorSyncCount();
            int constraintsBefore = cad.sketchConstraintCount();
            boolean canUndoBefore = cad.sketchAuthorityCanUndo();
            boolean canRedoBefore = cad.sketchAuthorityCanRedo();
            List<String> entityIdsBefore = entityIds(cad);
            List<String> constraintIdsBefore = constraintIds(cad);
            double[] modelBefore = modelLineSignature(cad, id);

            // Deliberately perturb only the compatibility projection. Rendering must not repair it.
            legacy.y2 += 17f;
            float[] drift = legacyLineSignature(legacy);
            String persistenceBeforeDraw = cad.exportSketchProjectState();

            Bitmap bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888);
            cad.onDraw(new Canvas(bitmap));

            assertLegacyLineSame(drift, legacy);
            assertLineSame(modelBefore, modelLineSignature(cad, id));
            assertEquals(transitionsBefore, cad.sketchAuthorityTransitionCount());
            assertEquals(mirrorsBefore, cad.sketchMirrorSyncCount());
            assertEquals(constraintsBefore, cad.sketchConstraintCount());
            assertEquals(canUndoBefore, cad.sketchAuthorityCanUndo());
            assertEquals(canRedoBefore, cad.sketchAuthorityCanRedo());
            assertEquals(entityIdsBefore, entityIds(cad));
            assertEquals(constraintIdsBefore, constraintIds(cad));
            assertEquals(persistenceBeforeDraw, cad.exportSketchProjectState());
            return true;
        });
    }

    private static K33MirroredCadCanvasView cad(boolean autoConstraints) throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        K33MirroredCadCanvasView cad = new K33MirroredCadCanvasView(context);
        cad.layout(0, 0, 1400, 1000);
        cad.clearAll();
        Field f = ParametricSketchCanvasView.class.getDeclaredField("autoConstraints");
        f.setAccessible(true);
        f.setBoolean(cad, autoConstraints);
        return cad;
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

    private static SketchConstraint onlyKind(K33MirroredCadCanvasView cad, SketchConstraint.Kind kind) {
        SketchConstraint found = null;
        for (SketchConstraint constraint : cad.sketchConstraints()) {
            if (constraint.kind != kind) continue;
            if (found != null) throw new AssertionError("duplicate " + kind + " constraint");
            found = constraint;
        }
        if (found == null) throw new AssertionError("missing " + kind + " model constraint");
        return found;
    }

    private static int countKind(K33MirroredCadCanvasView cad, SketchConstraint.Kind kind) {
        int count = 0;
        for (SketchConstraint constraint : cad.sketchConstraints()) if (constraint.kind == kind) count++;
        return count;
    }

    private static boolean hasBinaryConstraint(K33MirroredCadCanvasView cad, SketchConstraint.Kind kind,
                                               String a, String b) {
        for (SketchConstraint constraint : cad.sketchConstraints()) {
            if (constraint.kind != kind) continue;
            if ((a.equals(constraint.primaryEntityId) && b.equals(constraint.secondaryEntityId))
                    || (b.equals(constraint.primaryEntityId) && a.equals(constraint.secondaryEntityId))) {
                return true;
            }
        }
        return false;
    }

    private static SketchGeometry.Line modelLine(K33MirroredCadCanvasView cad, String id) {
        for (SketchEntity entity : cad.sketchMirrorEntities()) {
            if (id.equals(entity.id())) return (SketchGeometry.Line) entity;
        }
        throw new AssertionError("missing model line " + id);
    }

    private static void assertHorizontal(SketchGeometry.Line line) {
        assertEquals(line.a.yMm, line.b.yMm, EPS);
    }

    private static void assertVertical(SketchGeometry.Line line) {
        assertEquals(line.a.xMm, line.b.xMm, EPS);
    }

    private static void assertPerpendicular(SketchGeometry.Line a, SketchGeometry.Line b) {
        double ax = a.b.xMm - a.a.xMm;
        double ay = a.b.yMm - a.a.yMm;
        double bx = b.b.xMm - b.a.xMm;
        double by = b.b.yMm - b.a.yMm;
        double denom = Math.hypot(ax, ay) * Math.hypot(bx, by);
        assertTrue("degenerate line in perpendicular contract", denom > 1.0e-9);
        assertEquals(0.0, (ax * bx + ay * by) / denom, EPS);
    }

    private static List<String> entityIds(K33MirroredCadCanvasView cad) {
        ArrayList<String> ids = new ArrayList<>();
        for (SketchEntity entity : cad.sketchMirrorEntities()) ids.add(entity.id());
        return ids;
    }

    private static List<String> constraintIds(K33MirroredCadCanvasView cad) {
        ArrayList<String> ids = new ArrayList<>();
        for (SketchConstraint constraint : cad.sketchConstraints()) ids.add(constraint.id);
        return ids;
    }

    private static double[] modelLineSignature(K33MirroredCadCanvasView cad, String id) {
        SketchGeometry.Line line = modelLine(cad, id);
        return new double[]{line.a.xMm, line.a.yMm, line.b.xMm, line.b.yMm};
    }

    private static float[] legacyLineSignature(CadCanvasView.LineEntity line) {
        return new float[]{line.x1, line.y1, line.x2, line.y2};
    }

    private static void assertLegacyLineSame(float[] expected, CadCanvasView.LineEntity actual) {
        assertEquals(expected[0], actual.x1, 0f);
        assertEquals(expected[1], actual.y1, 0f);
        assertEquals(expected[2], actual.x2, 0f);
        assertEquals(expected[3], actual.y2, 0f);
    }

    private static void assertLineSame(double[] expected, double[] actual) {
        assertEquals(expected[0], actual[0], 0d);
        assertEquals(expected[1], actual[1], 0d);
        assertEquals(expected[2], actual[2], 0d);
        assertEquals(expected[3], actual[3], 0d);
    }

    private static <T> T onMain(Callable<T> callable) throws Exception {
        FutureTask<T> task = new FutureTask<>(callable);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(task);
        return task.get();
    }
}

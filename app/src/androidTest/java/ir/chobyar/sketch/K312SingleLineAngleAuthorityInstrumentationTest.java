package ir.chobyar.sketch;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.InputDevice;
import android.view.MotionEvent;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;

import ir.chobyar.sketch.core.SketchConstraint;
import ir.chobyar.sketch.core.SketchEntity;
import ir.chobyar.sketch.core.SketchGeometry;

/** K3.12 API35 fence: absolute Single-Line Angle is model-owned end to end. */
@RunWith(AndroidJUnit4.class)
public final class K312SingleLineAngleAuthorityInstrumentationTest {
    private static final double EPS = 1.0e-5;

    @Test public void singleLineAngleIsModelOwnedUndoableRedoableAndPersistent() throws Exception {
        onMain(() -> {
            K33MirroredCadCanvasView cad = cad();
            cad.executeCommand("LINE 10 20 45 38");
            String id = cad.selected.stableId();
            SketchGeometry.Line before = line(cad, id);

            String result = cad.setSelectedLineAngle(65f);
            assertTrue(result, result.contains("65"));
            cad.requireSketchMirrorParity();
            SketchConstraint angle = singleLineAngle(cad, id);
            assertEquals(65.0, angle.value, 0.0);
            assertEquals("∠65°", cad.modelLineAngleFeedbackText(id));
            assertEquals(before.lengthMm(), line(cad, id).lengthMm(), EPS);
            assertEquals(65.0, displayAngle(line(cad, id)), EPS);
            assertEquals(0, cad.legacyMigratedConstraintTruthCount());

            String stableConstraintId = angle.id;
            assertTrue(cad.setSelectedLineAngle(25f).contains("25"));
            assertEquals(stableConstraintId, singleLineAngle(cad, id).id);
            assertEquals("∠25°", cad.modelLineAngleFeedbackText(id));

            cad.undo();
            cad.requireSketchMirrorParity();
            assertEquals(65.0, singleLineAngle(cad, id).value, 0.0);
            assertEquals("∠65°", cad.modelLineAngleFeedbackText(id));
            assertTrue(cad.redoSketch());
            cad.requireSketchMirrorParity();
            assertEquals(25.0, displayAngle(line(cad, id)), EPS);
            assertEquals("∠25°", cad.modelLineAngleFeedbackText(id));

            String saved = cad.exportSketchProjectState();
            K33MirroredCadCanvasView restored = cad();
            assertTrue(restored.importSketchProjectState(saved).length() > 0);
            restored.requireSketchMirrorParity();
            assertEquals(25.0, singleLineAngle(restored, id).value, 0.0);
            assertEquals(25.0, displayAngle(line(restored, id)), EPS);
            assertEquals("∠25°", restored.modelLineAngleFeedbackText(id));
            return true;
        });
    }

    @Test public void drawDoesNotRepairSingleLineAngleLegacyProjectionOrMutateModel() throws Exception {
        onMain(() -> {
            K33MirroredCadCanvasView cad = cad();
            cad.executeCommand("LINE 20 30 60 50");
            CadCanvasView.LineEntity legacy = (CadCanvasView.LineEntity) cad.selected;
            String id = legacy.stableId();
            assertTrue(cad.setSelectedLineAngle(40f).contains("40"));
            cad.requireSketchMirrorParity();
            SketchGeometry.Line modelBefore = line(cad, id);
            String constraintIdBefore = singleLineAngle(cad, id).id;
            long transitionsBefore = cad.sketchAuthorityTransitionCount();

            legacy.x1 = 100f;
            legacy.y1 = 120f;
            legacy.x2 = 150f;
            legacy.y2 = 155f;
            cad.onDraw(new Canvas(Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)));

            assertEquals(100f, legacy.x1, 0f);
            assertEquals(120f, legacy.y1, 0f);
            assertEquals(150f, legacy.x2, 0f);
            assertEquals(155f, legacy.y2, 0f);
            assertLine(modelBefore, line(cad, id));
            assertEquals(constraintIdBefore, singleLineAngle(cad, id).id);
            assertEquals(40.0, singleLineAngle(cad, id).value, 0.0);
            assertEquals(transitionsBefore, cad.sketchAuthorityTransitionCount());
            return true;
        });
    }

    @Test public void secondPointFixedIsPivotAndDrawDoesNotRepairLegacyProjection() throws Exception {
        onMain(() -> {
            K33MirroredCadCanvasView cad = cad();
            cad.executeCommand("LINE 10 10 50 25");
            String id = cad.selected.stableId();
            SketchGeometry.Line before = line(cad, id);
            cad.setTool(CadCanvasView.TOOL_SELECT);
            tap(cad, screenX(cad, before.b.xMm), screenY(cad, before.b.yMm));
            assertEquals(1, cad.pointLockTargetPointIndex());
            assertEquals("Point locked", cad.toggleSelectedLock());

            assertTrue(cad.setSelectedLineAngle(115f).contains("115"));
            SketchGeometry.Line solved = line(cad, id);
            assertEquals(before.b.xMm, solved.b.xMm, EPS);
            assertEquals(before.b.yMm, solved.b.yMm, EPS);
            String constraintIdBefore = singleLineAngle(cad, id).id;

            CadCanvasView.LineEntity legacy = (CadCanvasView.LineEntity) cad.selected;
            legacy.x1 += 30f;
            legacy.y1 += 20f;
            float driftX1 = legacy.x1;
            float driftY1 = legacy.y1;
            float driftX2 = legacy.x2;
            float driftY2 = legacy.y2;
            cad.onDraw(new Canvas(Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)));

            assertEquals(driftX1, legacy.x1, 0f);
            assertEquals(driftY1, legacy.y1, 0f);
            assertEquals(driftX2, legacy.x2, 0f);
            assertEquals(driftY2, legacy.y2, 0f);
            assertLine(solved, line(cad, id));
            assertEquals(before.b.xMm, line(cad, id).b.xMm, EPS);
            assertEquals(before.b.yMm, line(cad, id).b.yMm, EPS);
            assertEquals(constraintIdBefore, singleLineAngle(cad, id).id);
            assertEquals(115.0, singleLineAngle(cad, id).value, 0.0);
            return true;
        });
    }

    @Test public void incompatibleAxisAndWholeFixedConstraintsFailWithoutCreatingAngle() throws Exception {
        onMain(() -> {
            K33MirroredCadCanvasView axisCad = cad();
            axisCad.executeCommand("LINE 0 0 40 0");
            String axisId = axisCad.selected.stableId();
            assertTrue(axisCad.applyHorizontalVerticalConstraint().contains("H/V"));
            SketchGeometry.Line axisBefore = line(axisCad, axisId);
            String axisResult = axisCad.setSelectedLineAngle(35f);
            assertTrue(axisResult, axisResult.contains("could not be solved"));
            assertEquals(0, countLineAngles(axisCad, axisId));
            assertLine(axisBefore, line(axisCad, axisId));

            K33MirroredCadCanvasView fixedCad = cad();
            fixedCad.executeCommand("LINE 0 0 40 0");
            String fixedId = fixedCad.selected.stableId();
            assertTrue(fixedCad.toggleSelectedLock().contains("locked"));
            SketchGeometry.Line fixedBefore = line(fixedCad, fixedId);
            String fixedResult = fixedCad.setSelectedLineAngle(35f);
            assertTrue(fixedResult, fixedResult.contains("could not be solved"));
            assertEquals(0, countLineAngles(fixedCad, fixedId));
            assertLine(fixedBefore, line(fixedCad, fixedId));

            K33MirroredCadCanvasView verticalCad = cad();
            verticalCad.executeCommand("LINE 0 0 0 40");
            String verticalId = verticalCad.selected.stableId();
            assertTrue(verticalCad.applyHorizontalVerticalConstraint().contains("H/V"));
            assertTrue(verticalCad.setSelectedLineAngle(35f).contains("could not be solved"));
            assertEquals(0, countLineAngles(verticalCad, verticalId));

            K33MirroredCadCanvasView bothFixedCad = cad();
            bothFixedCad.executeCommand("LINE 10 10 50 20");
            String bothFixedId = bothFixedCad.selected.stableId();
            SketchGeometry.Line bothFixedBefore = line(bothFixedCad, bothFixedId);
            bothFixedCad.setTool(CadCanvasView.TOOL_SELECT);
            tap(bothFixedCad, screenX(bothFixedCad, bothFixedBefore.a.xMm),
                    screenY(bothFixedCad, bothFixedBefore.a.yMm));
            assertEquals("Point locked", bothFixedCad.toggleSelectedLock());
            tap(bothFixedCad, screenX(bothFixedCad, bothFixedBefore.b.xMm),
                    screenY(bothFixedCad, bothFixedBefore.b.yMm));
            assertEquals("Point locked", bothFixedCad.toggleSelectedLock());
            assertTrue(bothFixedCad.setSelectedLineAngle(35f).contains("could not be solved"));
            assertEquals(0, countLineAngles(bothFixedCad, bothFixedId));
            assertLine(bothFixedBefore, line(bothFixedCad, bothFixedId));
            return true;
        });
    }

    @Test public void malformedPersistedAngleFailsOpenAtomically() throws Exception {
        onMain(() -> {
            K33MirroredCadCanvasView current = cad();
            current.executeCommand("LINE 0 0 20 5");
            String before = current.exportSketchProjectState();

            K33MirroredCadCanvasView incoming = cad();
            incoming.executeCommand("LINE 100 100 140 110");
            assertTrue(incoming.setSelectedLineAngle(30f).contains("30"));
            JSONObject malformed = new JSONObject(incoming.exportSketchProjectState());
            JSONArray constraints = malformed.getJSONArray("modelConstraints");
            constraints.getJSONObject(0).put("value", 181.0);

            String result = current.importSketchProjectState(malformed.toString());
            assertTrue(result, result.toLowerCase(java.util.Locale.US).contains("could not be restored"));
            assertEquals(before, current.exportSketchProjectState());
            current.requireSketchMirrorParity();
            return true;
        });
    }

    private static K33MirroredCadCanvasView cad() {
        Context context = ApplicationProvider.getApplicationContext();
        K33MirroredCadCanvasView cad = new K33MirroredCadCanvasView(context);
        cad.layout(0, 0, 1400, 1000);
        return cad;
    }

    private static SketchGeometry.Line line(K33MirroredCadCanvasView cad, String id) {
        for (SketchEntity entity : cad.sketchMirrorEntities()) {
            if (id.equals(entity.id())) return (SketchGeometry.Line) entity;
        }
        throw new AssertionError("Missing model line " + id + "; " + cad.sketchMirrorError());
    }

    private static SketchConstraint singleLineAngle(K33MirroredCadCanvasView cad, String id) {
        List<SketchConstraint> found = new ArrayList<>();
        for (SketchConstraint constraint : cad.sketchConstraints()) {
            if (constraint.kind == SketchConstraint.Kind.LINE_ANGLE
                    && id.equals(constraint.primaryEntityId)) found.add(constraint);
        }
        assertEquals(1, found.size());
        return found.get(0);
    }

    private static int countLineAngles(K33MirroredCadCanvasView cad, String id) {
        int count = 0;
        for (SketchConstraint constraint : cad.sketchConstraints()) {
            if (constraint.kind == SketchConstraint.Kind.LINE_ANGLE
                    && id.equals(constraint.primaryEntityId)) count++;
        }
        return count;
    }

    private static double displayAngle(SketchGeometry.Line line) {
        return displayAngle(line.b.xMm - line.a.xMm, line.b.yMm - line.a.yMm);
    }

    private static double displayAngle(CadCanvasView.LineEntity line) {
        return displayAngle(line.x2 - line.x1, line.y2 - line.y1);
    }

    private static double displayAngle(double dx, double dy) {
        double angle = Math.toDegrees(Math.atan2(dy, dx));
        while (angle < 0.0) angle += 180.0;
        while (angle >= 180.0) angle -= 180.0;
        return angle;
    }

    private static float screenX(K33MirroredCadCanvasView cad, double mm) {
        return (float) (mm * 3.0 * cad.viewScale + cad.offsetX);
    }

    private static float screenY(K33MirroredCadCanvasView cad, double mm) {
        return (float) (mm * 3.0 * cad.viewScale + cad.offsetY);
    }

    private static void tap(K33MirroredCadCanvasView cad, float x, float y) {
        long now = android.os.SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0);
        down.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        cad.onTouchEvent(down);
        down.recycle();
        MotionEvent up = MotionEvent.obtain(now, now + 20, MotionEvent.ACTION_UP, x, y, 0);
        up.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        cad.onTouchEvent(up);
        up.recycle();
    }

    private static void assertLine(SketchGeometry.Line expected, SketchGeometry.Line actual) {
        assertEquals(expected.a.xMm, actual.a.xMm, EPS);
        assertEquals(expected.a.yMm, actual.a.yMm, EPS);
        assertEquals(expected.b.xMm, actual.b.xMm, EPS);
        assertEquals(expected.b.yMm, actual.b.yMm, EPS);
    }

    private static <T> T onMain(Callable<T> callable) throws Exception {
        FutureTask<T> task = new FutureTask<>(callable);
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
                .runOnMainSync(task);
        return task.get();
    }
}

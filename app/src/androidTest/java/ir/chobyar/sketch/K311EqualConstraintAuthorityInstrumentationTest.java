package ir.chobyar.sketch;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;

import ir.chobyar.sketch.core.SketchConstraint;
import ir.chobyar.sketch.core.SketchEntity;
import ir.chobyar.sketch.core.SketchGeometry;

/** K3.11 API35 fence: Equal must be model-owned across interaction/history/persistence. */
@RunWith(AndroidJUnit4.class)
public final class K311EqualConstraintAuthorityInstrumentationTest {

    @Test public void twoSelectedLinesApplyOneModelEqualAndUndoRedoTransactionally() throws Exception {
        onMain(() -> {
            K33MirroredCadCanvasView cad = cad();
            cad.executeCommand("LINE 0 0 50 0");
            CadCanvasView.Entity referenceLegacy = cad.selected;
            String referenceId = referenceLegacy.stableId();
            SketchGeometry.Line referenceBefore = (SketchGeometry.Line) entity(cad, referenceId);

            cad.executeCommand("LINE 10 20 30 40");
            CadCanvasView.Entity drivenLegacy = cad.selected;
            String drivenId = drivenLegacy.stableId();
            double drivenBefore = ((SketchGeometry.Line) entity(cad, drivenId)).lengthMm();
            selectTwo(cad, referenceLegacy, drivenLegacy);

            String result = applyEqual(cad);
            assertEquals("Equal applied", result);
            cad.requireSketchMirrorParity();
            SketchConstraint equal = singleEqual(cad, referenceId, drivenId);
            assertTrue(equal.driving);
            assertEquals(referenceBefore.lengthMm(),
                    ((SketchGeometry.Line) entity(cad, drivenId)).lengthMm(), 1.0e-5);
            assertEquals("Equal must remain model-owned, not populate legacy object truth",
                    0, cad.legacyMigratedConstraintTruthCount());

            cad.undo();
            cad.requireSketchMirrorParity();
            assertEquals(drivenBefore, ((SketchGeometry.Line) entity(cad, drivenId)).lengthMm(), 1.0e-5);
            assertEquals(0, countEqual(cad));
            assertTrue(cad.redoSketch());
            cad.requireSketchMirrorParity();
            assertEquals(referenceBefore.lengthMm(),
                    ((SketchGeometry.Line) entity(cad, drivenId)).lengthMm(), 1.0e-5);
            assertEquals(1, countEqual(cad));
            return true;
        });
    }

    @Test public void circleAndArcEqualizeRadiusWithoutChangingCentersOrArcAngles() throws Exception {
        onMain(() -> {
            K33MirroredCadCanvasView cad = cad();
            cad.executeCommand("CIRCLE 20 25 22");
            CadCanvasView.Entity circleLegacy = cad.selected;
            String circleId = circleLegacy.stableId();
            cad.executeCommand("ARC 70 45 9 30 115");
            CadCanvasView.Entity arcLegacy = cad.selected;
            String arcId = arcLegacy.stableId();
            SketchGeometry.Arc before = (SketchGeometry.Arc) entity(cad, arcId);
            selectTwo(cad, circleLegacy, arcLegacy);

            String result = applyEqual(cad);
            assertEquals("Equal applied", result);
            cad.requireSketchMirrorParity();
            SketchGeometry.Circle circle = (SketchGeometry.Circle) entity(cad, circleId);
            SketchGeometry.Arc arc = (SketchGeometry.Arc) entity(cad, arcId);
            assertEquals(circle.radiusMm, arc.radiusMm, 1.0e-6);
            assertEquals(before.center.xMm, arc.center.xMm, 1.0e-7);
            assertEquals(before.center.yMm, arc.center.yMm, 1.0e-7);
            assertEquals(before.startDeg, arc.startDeg, 1.0e-6);
            assertEquals(before.sweepDeg, arc.sweepDeg, 1.0e-6);
            singleEqual(cad, circleId, arcId);
            return true;
        });
    }

    @Test public void equalConstraintAndSolvedGeometrySurviveProjectRoundTrip() throws Exception {
        onMain(() -> {
            K33MirroredCadCanvasView cad = cad();
            cad.executeCommand("LINE 5 5 65 5");
            CadCanvasView.Entity referenceLegacy = cad.selected;
            String referenceId = referenceLegacy.stableId();
            cad.executeCommand("LINE 15 25 35 25");
            CadCanvasView.Entity drivenLegacy = cad.selected;
            String drivenId = drivenLegacy.stableId();
            selectTwo(cad, referenceLegacy, drivenLegacy);
            String result = applyEqual(cad);
            assertEquals("Equal applied", result);
            cad.requireSketchMirrorParity();
            String saved = cad.exportSketchProjectState();

            K33MirroredCadCanvasView restored = cad();
            restored.importSketchProjectState(saved);
            restored.requireSketchMirrorParity();
            assertEquals(((SketchGeometry.Line) entity(restored, referenceId)).lengthMm(),
                    ((SketchGeometry.Line) entity(restored, drivenId)).lengthMm(), 1.0e-5);
            singleEqual(restored, referenceId, drivenId);
            return true;
        });
    }

    @Test public void drawRepairsOnlyEqualOwnedLegacyGeometryWithoutMutatingModel() throws Exception {
        onMain(() -> {
            K33MirroredCadCanvasView cad = cad();
            cad.executeCommand("LINE 0 0 50 0");
            CadCanvasView.Entity referenceLegacy = cad.selected;
            String referenceId = referenceLegacy.stableId();
            cad.executeCommand("LINE 10 20 30 40");
            CadCanvasView.Entity drivenLegacy = cad.selected;
            String drivenId = drivenLegacy.stableId();
            cad.executeCommand("LINE 80 10 105 35");
            CadCanvasView.LineEntity freeLegacy = (CadCanvasView.LineEntity) cad.selected;
            String freeId = freeLegacy.stableId();
            cad.executeCommand("LINE 120 15 150 35");
            CadCanvasView.LineEntity pointFixedLegacy = (CadCanvasView.LineEntity) cad.selected;
            String pointFixedId = pointFixedLegacy.stableId();
            cad.setTool(CadCanvasView.TOOL_SELECT);
            tap(cad, screenX(cad, 120), screenY(cad, 15));
            assertEquals(pointFixedId, cad.pointLockTargetEntityId());
            assertEquals(0, cad.pointLockTargetPointIndex());
            assertEquals("Point locked", cad.toggleSelectedLock());
            cad.executeCommand("CIRCLE 200 40 18");
            CadCanvasView.Entity circleLegacy = cad.selected;
            cad.executeCommand("ARC 250 50 7 25 120");
            CadCanvasView.Entity arcLegacy = cad.selected;
            String arcId = arcLegacy.stableId();
            selectTwo(cad, circleLegacy, arcLegacy);
            assertEquals("Equal applied", applyEqual(cad));
            selectTwo(cad, referenceLegacy, drivenLegacy);
            assertEquals("Equal applied", applyEqual(cad));
            cad.requireSketchMirrorParity();

            SketchGeometry.Line referenceModel = line(cad, referenceId);
            SketchGeometry.Line drivenModel = line(cad, drivenId);
            SketchGeometry.Line freeModel = line(cad, freeId);
            SketchGeometry.Line pointFixedModel = line(cad, pointFixedId);
            SketchGeometry.Arc arcModel = (SketchGeometry.Arc) entity(cad, arcId);
            double[] referenceTruth = coordinates(referenceModel);
            double[] drivenTruth = coordinates(drivenModel);
            double[] freeTruth = coordinates(freeModel);
            double[] pointFixedTruth = coordinates(pointFixedModel);
            double[] arcTruth = arcCoordinates(arcModel);

            CadCanvasView.LineEntity equalLegacy = (CadCanvasView.LineEntity) drivenLegacy;
            equalLegacy.x1 = 311f;
            equalLegacy.y1 = 312f;
            equalLegacy.x2 = 314f;
            equalLegacy.y2 = 316f;
            freeLegacy.x1 = 411f;
            freeLegacy.y1 = 412f;
            freeLegacy.x2 = 413f;
            freeLegacy.y2 = 414f;
            pointFixedLegacy.x2 = 511f;
            pointFixedLegacy.y2 = 512f;
            arcLegacy.translate(13f, -9f);
            double[] perturbedArc = arcSignature(arcLegacy);
            arcLegacy.moveControlPoint(1,
                    (float) (perturbedArc[0] + perturbedArc[3] * 6.0),
                    (float) (perturbedArc[1] + perturbedArc[4] * 6.0));
            arcLegacy.rotate((float) perturbedArc[0], (float) perturbedArc[1], 17f);
            perturbedArc = arcSignature(arcLegacy);

            Bitmap bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888);
            cad.onDraw(new Canvas(bitmap));

            assertEquals(311f, equalLegacy.x1, 0f);
            assertEquals(312f, equalLegacy.y1, 0f);
            assertEquals(3.0 / 5.0,
                    (equalLegacy.x2 - equalLegacy.x1) / drivenModel.lengthMm(), 1.0e-5);
            assertEquals(4.0 / 5.0,
                    (equalLegacy.y2 - equalLegacy.y1) / drivenModel.lengthMm(), 1.0e-5);
            assertEquals(drivenModel.lengthMm(), Math.hypot(
                    equalLegacy.x2 - equalLegacy.x1, equalLegacy.y2 - equalLegacy.y1), 1.0e-5);
            assertEquals(411f, freeLegacy.x1, 0f);
            assertEquals(412f, freeLegacy.y1, 0f);
            assertEquals(413f, freeLegacy.x2, 0f);
            assertEquals(414f, freeLegacy.y2, 0f);
            assertEquals(511f, pointFixedLegacy.x2, 0f);
            assertEquals(512f, pointFixedLegacy.y2, 0f);
            assertModelLine(cad, referenceId, referenceTruth);
            assertModelLine(cad, drivenId, drivenTruth);
            assertModelLine(cad, freeId, freeTruth);
            assertModelLine(cad, pointFixedId, pointFixedTruth);
            assertTrue(hasPointFixed(cad, pointFixedId, 0));
            assertFalse(hasPointFixed(cad, pointFixedId, 1));
            double[] repairedArc = arcSignature(arcLegacy);
            assertEquals(arcModel.radiusMm, repairedArc[2], 1.0e-5);
            assertEquals(perturbedArc[0], repairedArc[0], 0.0);
            assertEquals(perturbedArc[1], repairedArc[1], 0.0);
            for (int i = 3; i < perturbedArc.length; i++) {
                assertEquals("Equal draw replay must preserve free arc-angle DOFs",
                        perturbedArc[i], repairedArc[i], 1.0e-6);
            }
            assertModelArc(cad, arcId, arcTruth);
            return true;
        });
    }

    private static String applyEqual(K33MirroredCadCanvasView cad) throws Exception {
        Method method = ParametricSketchCanvasView.class.getMethod("applyEqualConstraint");
        Object out = method.invoke(cad);
        return out == null ? "" : String.valueOf(out);
    }

    private static void selectTwo(K33MirroredCadCanvasView cad,
                                  CadCanvasView.Entity first, CadCanvasView.Entity second) {
        cad.selectedObjects.clear();
        cad.selectedObjects.add(first);
        cad.selectedObjects.add(second);
        cad.selected = null;
    }

    private static K33MirroredCadCanvasView cad() {
        Context context = ApplicationProvider.getApplicationContext();
        K33MirroredCadCanvasView cad = new K33MirroredCadCanvasView(context);
        cad.layout(0, 0, 1400, 1000);
        return cad;
    }

    private static SketchEntity entity(K33MirroredCadCanvasView cad, String id) {
        for (SketchEntity e : cad.sketchMirrorEntities()) if (id.equals(e.id())) return e;
        throw new AssertionError("Model entity not found: " + id + "; mirrorError=" + cad.sketchMirrorError());
    }

    private static SketchConstraint singleEqual(K33MirroredCadCanvasView cad,
                                                String first, String second) {
        List<SketchConstraint> found = new ArrayList<>();
        for (SketchConstraint c : cad.sketchConstraints()) {
            if (c.kind != SketchConstraint.Kind.EQUAL || c.secondaryEntityId == null) continue;
            boolean sameOrder = first.equals(c.primaryEntityId) && second.equals(c.secondaryEntityId);
            boolean reverseOrder = second.equals(c.primaryEntityId) && first.equals(c.secondaryEntityId);
            if (sameOrder || reverseOrder) found.add(c);
        }
        assertEquals("Expected one model-owned Equal constraint for the selected stable-ID pair",
                1, found.size());
        return found.get(0);
    }

    private static int countEqual(K33MirroredCadCanvasView cad) {
        int n = 0;
        for (SketchConstraint c : cad.sketchConstraints()) if (c.kind == SketchConstraint.Kind.EQUAL) n++;
        return n;
    }

    private static SketchGeometry.Line line(K33MirroredCadCanvasView cad, String id) {
        return (SketchGeometry.Line) entity(cad, id);
    }

    private static double[] coordinates(SketchGeometry.Line line) {
        return new double[]{line.a.xMm, line.a.yMm, line.b.xMm, line.b.yMm};
    }

    private static double[] arcCoordinates(SketchGeometry.Arc arc) {
        return new double[]{arc.center.xMm, arc.center.yMm, arc.radiusMm, arc.startDeg, arc.sweepDeg};
    }

    private static double[] arcSignature(CadCanvasView.Entity arc) {
        List<CadCanvasView.ControlPoint> points = arc.controlPoints();
        CadCanvasView.ControlPoint center = points.get(0);
        CadCanvasView.ControlPoint start = points.get(1);
        CadCanvasView.ControlPoint end = points.get(2);
        double radius = Math.hypot(start.x - center.x, start.y - center.y);
        return new double[]{center.x, center.y, radius,
                (start.x - center.x) / radius, (start.y - center.y) / radius,
                (end.x - center.x) / radius, (end.y - center.y) / radius};
    }

    private static void assertModelLine(K33MirroredCadCanvasView cad, String id, double[] expected) {
        SketchGeometry.Line actual = line(cad, id);
        assertEquals(expected[0], actual.a.xMm, 0.0);
        assertEquals(expected[1], actual.a.yMm, 0.0);
        assertEquals(expected[2], actual.b.xMm, 0.0);
        assertEquals(expected[3], actual.b.yMm, 0.0);
    }

    private static void assertModelArc(K33MirroredCadCanvasView cad, String id, double[] expected) {
        SketchGeometry.Arc actual = (SketchGeometry.Arc) entity(cad, id);
        assertEquals(expected[0], actual.center.xMm, 0.0);
        assertEquals(expected[1], actual.center.yMm, 0.0);
        assertEquals(expected[2], actual.radiusMm, 0.0);
        assertEquals(expected[3], actual.startDeg, 0.0);
        assertEquals(expected[4], actual.sweepDeg, 0.0);
    }

    private static boolean hasPointFixed(K33MirroredCadCanvasView cad, String id, int pointIndex) {
        for (SketchConstraint constraint : cad.sketchConstraints()) {
            if (constraint.kind == SketchConstraint.Kind.FIXED
                    && constraint.fixesPoint()
                    && id.equals(constraint.primaryEntityId)
                    && constraint.primaryPointIndex == pointIndex) return true;
        }
        return false;
    }

    private static float screenX(K33MirroredCadCanvasView cad, double xMm) {
        return (float) (xMm * 3.0 * cad.viewScale + cad.offsetX);
    }

    private static float screenY(K33MirroredCadCanvasView cad, double yMm) {
        return (float) (yMm * 3.0 * cad.viewScale + cad.offsetY);
    }

    private static void tap(K33MirroredCadCanvasView cad, float x, float y) {
        long down = SystemClock.uptimeMillis();
        send(cad, one(down, down, MotionEvent.ACTION_DOWN, x, y));
        send(cad, one(down, down + 16L, MotionEvent.ACTION_UP, x, y));
    }

    private static void send(K33MirroredCadCanvasView cad, MotionEvent event) {
        try {
            assertTrue(cad.onTouchEvent(event));
        } finally {
            event.recycle();
        }
    }

    private static MotionEvent one(long down, long time, int action, float x, float y) {
        MotionEvent.PointerProperties property = new MotionEvent.PointerProperties();
        property.id = 0;
        property.toolType = MotionEvent.TOOL_TYPE_FINGER;
        MotionEvent.PointerCoords coords = new MotionEvent.PointerCoords();
        coords.x = x;
        coords.y = y;
        coords.pressure = 1f;
        coords.size = 1f;
        return MotionEvent.obtain(down, time, action, 1,
                new MotionEvent.PointerProperties[]{property},
                new MotionEvent.PointerCoords[]{coords},
                0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0);
    }

    private static <T> T onMain(Callable<T> callable) throws Exception {
        FutureTask<T> task = new FutureTask<>(callable);
        new android.os.Handler(android.os.Looper.getMainLooper()).post(task);
        return task.get();
    }
}

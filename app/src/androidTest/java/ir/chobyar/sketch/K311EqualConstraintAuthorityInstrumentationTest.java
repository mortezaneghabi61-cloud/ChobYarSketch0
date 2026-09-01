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

/** K3.11 API35 fence: Equal is model-owned and rendering is presentation-only. */
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

            assertEquals("Equal applied", applyEqual(cad));
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

            assertEquals("Equal applied", applyEqual(cad));
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
            assertEquals("Equal applied", applyEqual(cad));
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

    @Test public void drawNeverRepairsEqualLegacyProjectionOrMutatesModelAuthority() throws Exception {
        onMain(() -> {
            K33MirroredCadCanvasView cad = cad();
            cad.executeCommand("LINE 0 0 50 0");
            CadCanvasView.LineEntity referenceLegacy = (CadCanvasView.LineEntity) cad.selected;
            String referenceId = referenceLegacy.stableId();
            cad.executeCommand("LINE 10 20 30 40");
            CadCanvasView.LineEntity drivenLegacy = (CadCanvasView.LineEntity) cad.selected;
            String drivenId = drivenLegacy.stableId();
            cad.executeCommand("LINE 80 10 105 35");
            CadCanvasView.LineEntity freeLegacy = (CadCanvasView.LineEntity) cad.selected;
            String freeId = freeLegacy.stableId();

            cad.executeCommand("LINE 120 15 150 35");
            CadCanvasView.LineEntity pointFixedLegacy = (CadCanvasView.LineEntity) cad.selected;
            String pointFixedId = pointFixedLegacy.stableId();
            lockPoint(cad, pointFixedLegacy, pointFixedId, 0);

            cad.executeCommand("LINE 160 15 190 35");
            CadCanvasView.LineEntity fixedEqualLegacy = (CadCanvasView.LineEntity) cad.selected;
            String fixedEqualId = fixedEqualLegacy.stableId();
            lockPoint(cad, fixedEqualLegacy, fixedEqualId, 1);
            selectTwo(cad, fixedEqualLegacy, referenceLegacy);
            assertEquals("Equal applied", applyEqual(cad));

            cad.executeCommand("CIRCLE 200 40 18");
            CadCanvasView.Entity circleLegacy = cad.selected;
            String circleId = circleLegacy.stableId();
            cad.executeCommand("ARC 250 50 7 25 120");
            CadCanvasView.Entity arcLegacy = cad.selected;
            String arcId = arcLegacy.stableId();
            selectTwo(cad, circleLegacy, arcLegacy);
            assertEquals("Equal applied", applyEqual(cad));
            selectTwo(cad, referenceLegacy, drivenLegacy);
            assertEquals("Equal applied", applyEqual(cad));
            cad.requireSketchMirrorParity();

            double[] referenceTruth = coordinates(line(cad, referenceId));
            double[] drivenTruth = coordinates(line(cad, drivenId));
            double[] freeTruth = coordinates(line(cad, freeId));
            double[] pointFixedTruth = coordinates(line(cad, pointFixedId));
            double[] fixedEqualTruth = coordinates(line(cad, fixedEqualId));
            double[] circleTruth = circleCoordinates((SketchGeometry.Circle) entity(cad, circleId));
            double[] arcTruth = arcCoordinates((SketchGeometry.Arc) entity(cad, arcId));
            int equalCountBefore = countEqual(cad);
            int constraintCountBefore = cad.sketchConstraintCount();
            int legacyTruthBefore = cad.legacyMigratedConstraintTruthCount();
            boolean undoBefore = cad.sketchAuthorityCanUndo();
            boolean redoBefore = cad.sketchAuthorityCanRedo();
            long transitionsBefore = cad.sketchAuthorityTransitionCount();
            List<String> idsBefore = stableIds(cad);

            drivenLegacy.x1 = 311f;
            drivenLegacy.y1 = 312f;
            drivenLegacy.x2 = 314f;
            drivenLegacy.y2 = 316f;
            float[] drivenDrift = lineSignature(drivenLegacy);

            freeLegacy.x1 = 411f;
            freeLegacy.y1 = 412f;
            freeLegacy.x2 = 413f;
            freeLegacy.y2 = 414f;
            float[] freeDrift = lineSignature(freeLegacy);

            pointFixedLegacy.x2 = 511f;
            pointFixedLegacy.y2 = 512f;
            float[] pointFixedDrift = lineSignature(pointFixedLegacy);

            fixedEqualLegacy.x1 = fixedEqualLegacy.x2 - 6f;
            fixedEqualLegacy.y1 = fixedEqualLegacy.y2 - 8f;
            float[] fixedEqualDrift = lineSignature(fixedEqualLegacy);

            arcLegacy.translate(13f, -9f);
            double[] arcDrift0 = arcSignature(arcLegacy);
            arcLegacy.moveControlPoint(1,
                    (float) (arcDrift0[0] + arcDrift0[3] * 6.0),
                    (float) (arcDrift0[1] + arcDrift0[4] * 6.0));
            arcLegacy.rotate((float) arcDrift0[0], (float) arcDrift0[1], 17f);
            double[] arcDrift = arcSignature(arcLegacy);

            Bitmap bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888);
            cad.onDraw(new Canvas(bitmap));

            assertLineProjectionSame("Equal-driven legacy line", drivenDrift, drivenLegacy);
            assertLineProjectionSame("free legacy line", freeDrift, freeLegacy);
            assertLineProjectionSame("Point-FIXED legacy line", pointFixedDrift, pointFixedLegacy);
            assertLineProjectionSame("Equal + Point-FIXED legacy line", fixedEqualDrift, fixedEqualLegacy);
            assertArraySame("onDraw must not repair Equal arc projection", arcDrift, arcSignature(arcLegacy));

            assertModelLine(cad, referenceId, referenceTruth);
            assertModelLine(cad, drivenId, drivenTruth);
            assertModelLine(cad, freeId, freeTruth);
            assertModelLine(cad, pointFixedId, pointFixedTruth);
            assertModelLine(cad, fixedEqualId, fixedEqualTruth);
            assertModelCircle(cad, circleId, circleTruth);
            assertModelArc(cad, arcId, arcTruth);
            assertTrue(hasPointFixed(cad, pointFixedId, 0));
            assertFalse(hasPointFixed(cad, pointFixedId, 1));
            assertFalse(hasPointFixed(cad, fixedEqualId, 0));
            assertTrue(hasPointFixed(cad, fixedEqualId, 1));
            assertEquals(equalCountBefore, countEqual(cad));
            assertEquals(constraintCountBefore, cad.sketchConstraintCount());
            assertEquals(legacyTruthBefore, cad.legacyMigratedConstraintTruthCount());
            assertEquals("Equal must never create legacy migrated truth", 0, cad.legacyMigratedConstraintTruthCount());
            assertEquals(undoBefore, cad.sketchAuthorityCanUndo());
            assertEquals(redoBefore, cad.sketchAuthorityCanRedo());
            assertEquals(transitionsBefore, cad.sketchAuthorityTransitionCount());
            assertEquals(idsBefore, stableIds(cad));
            assertEquals(referenceId, referenceLegacy.stableId());
            assertEquals(drivenId, drivenLegacy.stableId());
            assertEquals(freeId, freeLegacy.stableId());
            assertEquals(pointFixedId, pointFixedLegacy.stableId());
            assertEquals(fixedEqualId, fixedEqualLegacy.stableId());
            assertEquals(circleId, circleLegacy.stableId());
            assertEquals(arcId, arcLegacy.stableId());
            return true;
        });
    }

    private static void lockPoint(K33MirroredCadCanvasView cad, CadCanvasView.LineEntity legacy,
                                  String stableId, int pointIndex) {
        CadCanvasView.ControlPoint p = legacy.controlPoints().get(pointIndex);
        cad.setTool(CadCanvasView.TOOL_SELECT);
        tap(cad, screenX(cad, p.x), screenY(cad, p.y));
        assertEquals(stableId, cad.pointLockTargetEntityId());
        assertEquals(pointIndex, cad.pointLockTargetPointIndex());
        assertEquals("Point locked", cad.toggleSelectedLock());
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
        assertEquals("Expected one model-owned Equal constraint for the selected stable-ID pair", 1, found.size());
        return found.get(0);
    }

    private static int countEqual(K33MirroredCadCanvasView cad) {
        int n = 0;
        for (SketchConstraint c : cad.sketchConstraints()) if (c.kind == SketchConstraint.Kind.EQUAL) n++;
        return n;
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

    private static SketchGeometry.Line line(K33MirroredCadCanvasView cad, String id) {
        return (SketchGeometry.Line) entity(cad, id);
    }

    private static double[] coordinates(SketchGeometry.Line line) {
        return new double[]{line.a.xMm, line.a.yMm, line.b.xMm, line.b.yMm};
    }

    private static double[] circleCoordinates(SketchGeometry.Circle circle) {
        return new double[]{circle.center.xMm, circle.center.yMm, circle.radiusMm};
    }

    private static double[] arcCoordinates(SketchGeometry.Arc arc) {
        return new double[]{arc.center.xMm, arc.center.yMm, arc.radiusMm, arc.startDeg, arc.sweepDeg};
    }

    private static float[] lineSignature(CadCanvasView.LineEntity line) {
        return new float[]{line.x1, line.y1, line.x2, line.y2};
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

    private static List<String> stableIds(K33MirroredCadCanvasView cad) {
        List<String> out = new ArrayList<>();
        for (SketchEntity e : cad.sketchMirrorEntities()) out.add(e.id());
        return out;
    }

    private static void assertLineProjectionSame(String message, float[] expected, CadCanvasView.LineEntity actual) {
        assertEquals(message + " x1", expected[0], actual.x1, 0f);
        assertEquals(message + " y1", expected[1], actual.y1, 0f);
        assertEquals(message + " x2", expected[2], actual.x2, 0f);
        assertEquals(message + " y2", expected[3], actual.y2, 0f);
    }

    private static void assertArraySame(String message, double[] expected, double[] actual) {
        assertEquals(message + " length", expected.length, actual.length);
        for (int i = 0; i < expected.length; i++) assertEquals(message + "[" + i + "]", expected[i], actual[i], 0.0);
    }

    private static void assertModelLine(K33MirroredCadCanvasView cad, String id, double[] expected) {
        SketchGeometry.Line actual = line(cad, id);
        assertEquals(expected[0], actual.a.xMm, 0.0);
        assertEquals(expected[1], actual.a.yMm, 0.0);
        assertEquals(expected[2], actual.b.xMm, 0.0);
        assertEquals(expected[3], actual.b.yMm, 0.0);
    }

    private static void assertModelCircle(K33MirroredCadCanvasView cad, String id, double[] expected) {
        SketchGeometry.Circle actual = (SketchGeometry.Circle) entity(cad, id);
        assertEquals(expected[0], actual.center.xMm, 0.0);
        assertEquals(expected[1], actual.center.yMm, 0.0);
        assertEquals(expected[2], actual.radiusMm, 0.0);
    }

    private static void assertModelArc(K33MirroredCadCanvasView cad, String id, double[] expected) {
        SketchGeometry.Arc actual = (SketchGeometry.Arc) entity(cad, id);
        assertEquals(expected[0], actual.center.xMm, 0.0);
        assertEquals(expected[1], actual.center.yMm, 0.0);
        assertEquals(expected[2], actual.radiusMm, 0.0);
        assertEquals(expected[3], actual.startDeg, 0.0);
        assertEquals(expected[4], actual.sweepDeg, 0.0);
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

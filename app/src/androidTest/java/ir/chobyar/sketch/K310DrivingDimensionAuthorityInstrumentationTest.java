package ir.chobyar.sketch;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;

import ir.chobyar.sketch.core.SketchConstraint;
import ir.chobyar.sketch.core.SketchEntity;
import ir.chobyar.sketch.core.SketchGeometry;

/** K3.10 API35 fence: exact driving dimensions must be model-owned, editable and persistent. */
@RunWith(AndroidJUnit4.class)
public final class K310DrivingDimensionAuthorityInstrumentationTest {

    @Test public void exactLineLengthCreatesAndEditsOneModelDistanceConstraint() throws Exception {
        onMain(() -> {
            K33MirroredCadCanvasView cad = cad();
            cad.executeCommand("LINE 10 10 40 10");
            String id = cad.selected.stableId();

            String first = cad.applySelectedDimension("80");
            assertTrue(first, first.contains("80"));
            cad.requireSketchMirrorParity();
            assertEquals(80.0, ((SketchGeometry.Line) entity(cad, id)).lengthMm(), 1.0e-5);
            SketchConstraint distance = single(cad, SketchConstraint.Kind.DISTANCE, id, null);
            assertEquals(80.0, distance.value, 1.0e-9);

            String second = cad.applySelectedDimension("65");
            assertTrue(second, second.contains("65"));
            cad.requireSketchMirrorParity();
            assertEquals(65.0, ((SketchGeometry.Line) entity(cad, id)).lengthMm(), 1.0e-5);
            distance = single(cad, SketchConstraint.Kind.DISTANCE, id, null);
            assertEquals(65.0, distance.value, 1.0e-9);
            assertEquals("Dimension edit must replace model truth, not accumulate duplicates",
                    1, count(cad, SketchConstraint.Kind.DISTANCE, id));

            cad.undo();
            cad.requireSketchMirrorParity();
            assertEquals(80.0, ((SketchGeometry.Line) entity(cad, id)).lengthMm(), 1.0e-5);
            assertEquals(80.0, single(cad, SketchConstraint.Kind.DISTANCE, id, null).value, 1.0e-9);
            assertTrue(cad.redoSketch());
            cad.requireSketchMirrorParity();
            assertEquals(65.0, ((SketchGeometry.Line) entity(cad, id)).lengthMm(), 1.0e-5);
            return true;
        });
    }

    @Test public void circleDiameterAndArcRadiusCreateModelRadiusConstraints() throws Exception {
        onMain(() -> {
            K33MirroredCadCanvasView cad = cad();
            cad.executeCommand("CIRCLE 20 25 12");
            String circleId = cad.selected.stableId();
            cad.applySelectedDimension("50"); // Existing UI contract is diameter for circles.
            cad.requireSketchMirrorParity();
            SketchGeometry.Circle circle = (SketchGeometry.Circle) entity(cad, circleId);
            assertEquals(25.0, circle.radiusMm, 1.0e-6);
            assertEquals(25.0, single(cad, SketchConstraint.Kind.RADIUS, circleId, null).value, 1.0e-9);

            cad.executeCommand("ARC 60 40 12 15 110");
            String arcId = cad.selected.stableId();
            cad.applySelectedDimension("30");
            cad.requireSketchMirrorParity();
            SketchGeometry.Arc arc = (SketchGeometry.Arc) entity(cad, arcId);
            assertEquals(30.0, arc.radiusMm, 1.0e-6);
            assertEquals(15.0, arc.startDeg, 1.0e-6);
            assertEquals(110.0, arc.sweepDeg, 1.0e-6);
            assertEquals(30.0, single(cad, SketchConstraint.Kind.RADIUS, arcId, null).value, 1.0e-9);
            return true;
        });
    }

    @Test public void twoLineAngleCreatesModelAngleConstraintAndKeepsReferenceLineStable() throws Exception {
        onMain(() -> {
            K33MirroredCadCanvasView cad = cad();
            cad.executeCommand("LINE 0 0 40 0");
            CadCanvasView.Entity referenceLegacy = cad.selected;
            String referenceId = referenceLegacy.stableId();
            SketchGeometry.Line referenceBefore = (SketchGeometry.Line) entity(cad, referenceId);

            cad.executeCommand("LINE 0 0 20 20");
            CadCanvasView.Entity movingLegacy = cad.selected;
            String movingId = movingLegacy.stableId();
            cad.selectedObjects.clear();
            cad.selectedObjects.add(referenceLegacy);
            cad.selectedObjects.add(movingLegacy);
            cad.selected = null;

            String result = cad.setSelectedLinesAngle(60f);
            assertTrue(result, result.contains("60"));
            cad.requireSketchMirrorParity();
            SketchConstraint angle = single(cad, SketchConstraint.Kind.ANGLE, referenceId, movingId);
            assertEquals(60.0, angle.value, 1.0e-9);
            SketchGeometry.Line referenceAfter = (SketchGeometry.Line) entity(cad, referenceId);
            SketchGeometry.Line movingAfter = (SketchGeometry.Line) entity(cad, movingId);
            assertLine(referenceBefore, referenceAfter);
            assertEquals(60.0, angleDeg(referenceAfter, movingAfter), 1.0e-4);
            return true;
        });
    }

    @Test public void drivingDimensionsRemainModelOwnedAcrossProjectRoundTrip() throws Exception {
        onMain(() -> {
            K33MirroredCadCanvasView cad = cad();
            cad.executeCommand("LINE 5 5 35 5");
            String id = cad.selected.stableId();
            cad.applySelectedDimension("75");
            cad.requireSketchMirrorParity();
            String saved = cad.exportSketchProjectState();

            K33MirroredCadCanvasView restored = cad();
            restored.importSketchProjectState(saved);
            restored.requireSketchMirrorParity();
            assertEquals(75.0, ((SketchGeometry.Line) entity(restored, id)).lengthMm(), 1.0e-5);
            assertEquals(75.0, single(restored, SketchConstraint.Kind.DISTANCE, id, null).value, 1.0e-9);
            return true;
        });
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

    private static SketchConstraint single(K33MirroredCadCanvasView cad, SketchConstraint.Kind kind,
                                           String primary, String secondary) {
        List<SketchConstraint> found = new ArrayList<>();
        for (SketchConstraint c : cad.sketchConstraints()) {
            if (c.kind == kind && primary.equals(c.primaryEntityId)
                    && (secondary == null ? c.secondaryEntityId == null : secondary.equals(c.secondaryEntityId))) {
                found.add(c);
            }
        }
        assertEquals("Expected exactly one model-owned " + kind + " constraint", 1, found.size());
        return found.get(0);
    }

    private static int count(K33MirroredCadCanvasView cad, SketchConstraint.Kind kind, String primary) {
        int n = 0;
        for (SketchConstraint c : cad.sketchConstraints()) {
            if (c.kind == kind && primary.equals(c.primaryEntityId)) n++;
        }
        return n;
    }

    private static void assertLine(SketchGeometry.Line expected, SketchGeometry.Line actual) {
        assertNotNull(actual);
        assertEquals(expected.a.xMm, actual.a.xMm, 1.0e-7);
        assertEquals(expected.a.yMm, actual.a.yMm, 1.0e-7);
        assertEquals(expected.b.xMm, actual.b.xMm, 1.0e-7);
        assertEquals(expected.b.yMm, actual.b.yMm, 1.0e-7);
    }

    private static double angleDeg(SketchGeometry.Line a, SketchGeometry.Line b) {
        double ax = a.b.xMm - a.a.xMm, ay = a.b.yMm - a.a.yMm;
        double bx = b.b.xMm - b.a.xMm, by = b.b.yMm - b.a.yMm;
        double dot = ax * bx + ay * by;
        double cross = ax * by - ay * bx;
        double angle = Math.toDegrees(Math.atan2(Math.abs(cross), dot));
        if (angle > 180.0) angle = 360.0 - angle;
        return angle;
    }

    private static <T> T onMain(Callable<T> callable) throws Exception {
        FutureTask<T> task = new FutureTask<>(callable);
        new android.os.Handler(android.os.Looper.getMainLooper()).post(task);
        return task.get();
    }
}

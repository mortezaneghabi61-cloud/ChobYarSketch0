package ir.chobyar.sketch;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;

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

    private static <T> T onMain(Callable<T> callable) throws Exception {
        FutureTask<T> task = new FutureTask<>(callable);
        new android.os.Handler(android.os.Looper.getMainLooper()).post(task);
        return task.get();
    }
}

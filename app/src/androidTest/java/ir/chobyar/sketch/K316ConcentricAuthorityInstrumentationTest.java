package ir.chobyar.sketch;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.PointF;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Field;
import java.util.Collection;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;

import ir.chobyar.sketch.core.SketchConstraint;
import ir.chobyar.sketch.core.SketchEntity;
import ir.chobyar.sketch.core.SketchGeometry;

/** K3.16 API35 fence for stable-ID, model-owned Concentric authority. */
@RunWith(AndroidJUnit4.class)
public final class K316ConcentricAuthorityInstrumentationTest {
    private static final double EPS = 1.0e-5;

    @Test public void realK33ConcentricIsModelOwnedUndoableAndNeverCreatesIdentityTruth() throws Exception {
        onMain(() -> {
            K33MirroredCadCanvasView cad = cad();
            CadCanvasView.Entity anchor = curve(cad, "CIRCLE 180 180 45");
            CadCanvasView.Entity driven = curve(cad, "ARC 360 260 28 15 155");
            String anchorId = anchor.stableId();
            String drivenId = driven.stableId();
            select(cad, anchor, driven);

            assertEquals("Concentric applied", cad.applyConcentricConstraint());
            cad.requireSketchMirrorParity();
            assertEquals(1, countConcentric(cad, anchorId, drivenId));
            assertEquals(0, legacyConcentricTruthCount(cad));
            assertCenter(modelCenter(cad, anchorId), modelCenter(cad, drivenId));
            assertTrue(cad.sketchAuthorityCanUndo());

            cad.undo();
            cad.requireSketchMirrorParity();
            assertEquals(0, countConcentric(cad, anchorId, drivenId));
            assertEquals(0, legacyConcentricTruthCount(cad));
            assertTrue(cad.redoSketch());
            cad.requireSketchMirrorParity();
            assertEquals(1, countConcentric(cad, anchorId, drivenId));
            assertEquals(0, legacyConcentricTruthCount(cad));
            return true;
        });
    }

    @Test public void circleArcConcentricPreservesRadiiAndDrawNeverRepairsPerturbedProjection() throws Exception {
        onMain(() -> {
            K33MirroredCadCanvasView cad = cad();
            CadCanvasView.Entity anchor = curve(cad, "CIRCLE 180 180 45");
            CadCanvasView.Entity driven = curve(cad, "ARC 360 260 28 15 155");
            String anchorId = anchor.stableId();
            String drivenId = driven.stableId();
            double anchorRadius = modelRadius(cad, anchorId);
            double drivenRadius = modelRadius(cad, drivenId);
            select(cad, anchor, driven);
            assertEquals("Concentric applied", cad.applyConcentricConstraint());
            cad.requireSketchMirrorParity();
            assertEquals(anchorRadius, modelRadius(cad, anchorId), 0.0);
            assertEquals(drivenRadius, modelRadius(cad, drivenId), 0.0);

            double[] modelBefore = modelCurveSignature(cad, drivenId);
            int constraintsBefore = cad.sketchConstraintCount();
            long transitionsBefore = cad.sketchAuthorityTransitionCount();
            driven.translate(73f, -41f);
            PointF legacyDrift = driven.center();
            Bitmap bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888);
            cad.onDraw(new Canvas(bitmap));

            assertEquals(legacyDrift.x, driven.center().x, 0f);
            assertEquals(legacyDrift.y, driven.center().y, 0f);
            assertCurveSame(modelBefore, modelCurveSignature(cad, drivenId));
            assertEquals(constraintsBefore, cad.sketchConstraintCount());
            assertEquals(transitionsBefore, cad.sketchAuthorityTransitionCount());
            assertEquals(0, legacyConcentricTruthCount(cad));
            return true;
        });
    }

    @Test public void concentricPersistsAcrossProjectRoundTripAndDeleteCascades() throws Exception {
        onMain(() -> {
            K33MirroredCadCanvasView cad = cad();
            CadCanvasView.Entity anchor = curve(cad, "ARC 180 180 45 -30 120");
            CadCanvasView.Entity driven = curve(cad, "CIRCLE 360 260 28");
            String anchorId = anchor.stableId();
            String drivenId = driven.stableId();
            select(cad, anchor, driven);
            assertEquals("Concentric applied", cad.applyConcentricConstraint());
            String saved = cad.exportSketchProjectState();

            K33MirroredCadCanvasView restored = cad();
            String imported = restored.importSketchProjectState(saved);
            assertFalse("Concentric state failed to reload: " + imported,
                    imported.contains("could not be restored"));
            restored.requireSketchMirrorParity();
            assertEquals(1, countConcentric(restored, anchorId, drivenId));
            assertEquals(0, legacyConcentricTruthCount(restored));
            assertCenter(modelCenter(restored, anchorId), modelCenter(restored, drivenId));

            CadCanvasView.Entity restoredAnchor = legacyEntity(restored, anchorId);
            select(restored, restoredAnchor);
            restored.deleteSelected();
            restored.requireSketchMirrorParity();
            assertFalse(hasModelEntity(restored, anchorId));
            assertTrue(hasModelEntity(restored, drivenId));
            assertEquals(0, countConcentric(restored, anchorId, drivenId));
            return true;
        });
    }

    @Test public void wholeFixedDrivenConflictIsAtomicAndLeavesRadiusGeometryHistoryUnchanged() throws Exception {
        onMain(() -> {
            K33MirroredCadCanvasView cad = cad();
            CadCanvasView.Entity anchor = curve(cad, "CIRCLE 180 180 45");
            CadCanvasView.Entity driven = curve(cad, "ARC 360 260 28 15 155");
            String anchorId = anchor.stableId();
            String drivenId = driven.stableId();
            select(cad, driven);
            assertEquals("1 selection(s) locked", cad.toggleSelectedLock());
            cad.requireSketchMirrorParity();
            double[] before = modelCurveSignature(cad, drivenId);
            int constraintsBefore = cad.sketchConstraintCount();
            long transitionsBefore = cad.sketchAuthorityTransitionCount();
            boolean undoBefore = cad.sketchAuthorityCanUndo();
            boolean redoBefore = cad.sketchAuthorityCanRedo();

            select(cad, anchor, driven);
            String result = cad.applyConcentricConstraint();
            assertTrue("Impossible whole-FIXED Concentric must fail closed: " + result,
                    result.contains("could not be solved") || result.contains("unchanged"));
            cad.requireSketchMirrorParity();
            assertEquals(0, countConcentric(cad, anchorId, drivenId));
            assertEquals(constraintsBefore, cad.sketchConstraintCount());
            assertEquals(transitionsBefore, cad.sketchAuthorityTransitionCount());
            assertEquals(undoBefore, cad.sketchAuthorityCanUndo());
            assertEquals(redoBefore, cad.sketchAuthorityCanRedo());
            assertCurveSame(before, modelCurveSignature(cad, drivenId));
            assertEquals(0, legacyConcentricTruthCount(cad));
            return true;
        });
    }

    @Test public void unsupportedSelectionFailsClosedWithoutModelOrLegacyMutation() throws Exception {
        onMain(() -> {
            K33MirroredCadCanvasView cad = cad();
            CadCanvasView.Entity line = curve(cad, "LINE 60 80 180 80");
            CadCanvasView.Entity circle = curve(cad, "CIRCLE 300 180 45");
            int constraintsBefore = cad.sketchConstraintCount();
            long transitionsBefore = cad.sketchAuthorityTransitionCount();
            double[] circleBefore = modelCurveSignature(cad, circle.stableId());
            select(cad, line, circle);

            String result = cad.applyConcentricConstraint();
            assertTrue(result.contains("Circle/Arc") || result.contains("exactly two"));
            assertEquals(constraintsBefore, cad.sketchConstraintCount());
            assertEquals(transitionsBefore, cad.sketchAuthorityTransitionCount());
            assertCurveSame(circleBefore, modelCurveSignature(cad, circle.stableId()));
            assertEquals(0, legacyConcentricTruthCount(cad));
            return true;
        });
    }

    private static Context context() { return ApplicationProvider.getApplicationContext(); }

    private static K33MirroredCadCanvasView cad() {
        K33MirroredCadCanvasView cad = new K33MirroredCadCanvasView(context());
        cad.layout(0, 0, 1400, 1000);
        return cad;
    }

    private static CadCanvasView.Entity curve(K33MirroredCadCanvasView cad, String command) {
        cad.executeCommand(command);
        return cad.selected;
    }

    private static void select(K33MirroredCadCanvasView cad, CadCanvasView.Entity... values) {
        cad.selectedObjects.clear();
        for (CadCanvasView.Entity value : values) cad.selectedObjects.add(value);
        cad.selected = values.length == 1 ? values[0] : null;
    }

    private static int countConcentric(K33MirroredCadCanvasView cad, String a, String b) {
        int count = 0;
        for (SketchConstraint c : cad.sketchConstraints()) {
            if (!"CONCENTRIC".equals(c.kind.name())) continue;
            if (a.equals(c.primaryEntityId) && b.equals(c.secondaryEntityId)) count++;
        }
        return count;
    }

    private static int legacyConcentricTruthCount(K33MirroredCadCanvasView cad) {
        try {
            Field field = ShaprConstraintSolverCadCanvasView.class.getDeclaredField("concentricDrives");
            field.setAccessible(true);
            Object value = field.get(cad);
            return value instanceof Collection ? ((Collection<?>) value).size() : -1;
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Cannot inspect legacy Concentric authority", e);
        }
    }

    private static SketchEntity modelEntity(K33MirroredCadCanvasView cad, String id) {
        for (SketchEntity entity : cad.sketchMirrorEntities()) if (id.equals(entity.id())) return entity;
        throw new AssertionError("Missing model entity " + id);
    }

    private static SketchGeometry.Point modelCenter(K33MirroredCadCanvasView cad, String id) {
        SketchEntity entity = modelEntity(cad, id);
        if (entity instanceof SketchGeometry.Circle) return ((SketchGeometry.Circle) entity).center;
        return ((SketchGeometry.Arc) entity).center;
    }

    private static double modelRadius(K33MirroredCadCanvasView cad, String id) {
        SketchEntity entity = modelEntity(cad, id);
        if (entity instanceof SketchGeometry.Circle) return ((SketchGeometry.Circle) entity).radiusMm;
        return ((SketchGeometry.Arc) entity).radiusMm;
    }

    private static double[] modelCurveSignature(K33MirroredCadCanvasView cad, String id) {
        SketchEntity entity = modelEntity(cad, id);
        if (entity instanceof SketchGeometry.Circle) {
            SketchGeometry.Circle c = (SketchGeometry.Circle) entity;
            return new double[]{c.center.xMm, c.center.yMm, c.radiusMm, 0, 0};
        }
        SketchGeometry.Arc a = (SketchGeometry.Arc) entity;
        return new double[]{a.center.xMm, a.center.yMm, a.radiusMm, a.startDeg, a.sweepDeg};
    }

    private static boolean hasModelEntity(K33MirroredCadCanvasView cad, String id) {
        for (SketchEntity entity : cad.sketchMirrorEntities()) if (id.equals(entity.id())) return true;
        return false;
    }

    private static CadCanvasView.Entity legacyEntity(K33MirroredCadCanvasView cad, String id) {
        for (CadCanvasView.Entity entity : cad.entities) if (id.equals(entity.stableId())) return entity;
        throw new AssertionError("Missing legacy projection " + id);
    }

    private static void assertCenter(SketchGeometry.Point expected, SketchGeometry.Point actual) {
        assertEquals(expected.xMm, actual.xMm, EPS);
        assertEquals(expected.yMm, actual.yMm, EPS);
    }

    private static void assertCurveSame(double[] expected, double[] actual) {
        assertEquals(expected.length, actual.length);
        for (int i = 0; i < expected.length; i++) assertEquals(expected[i], actual[i], 0.0);
    }

    private static <T> T onMain(Callable<T> callable) throws Exception {
        FutureTask<T> task = new FutureTask<>(callable);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(task);
        return task.get();
    }
}

package ir.chobyar.sketch;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Field;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;

import ir.chobyar.sketch.core.SketchConstraint;
import ir.chobyar.sketch.core.SketchEntity;
import ir.chobyar.sketch.core.SketchGeometry;

/** K3.13 RED/API35 fence: Tangent semantic authority must leave the legacy View layer. */
@RunWith(AndroidJUnit4.class)
public final class K313TangentAuthorityInstrumentationTest {

    @Test public void lineCircleTangentMustBeModelOwnedWithStableIdsAndTransactionalHistory() throws Exception {
        onMain(() -> {
            K33MirroredCadCanvasView cad = cad();
            cad.executeCommand("CIRCLE 180 180 45");
            CadCanvasView.Entity circleLegacy = cad.selected;
            String circleId = circleLegacy.stableId();

            cad.executeCommand("LINE 225 180 315 95");
            CadCanvasView.Entity lineLegacy = cad.selected;
            String lineId = lineLegacy.stableId();
            selectTwo(cad, lineLegacy, circleLegacy);

            String result = cad.applyTangentConstraint();
            assertTrue("Tangent rejected: " + result, result.contains("Tangent"));

            assertEquals("Tangent must be represented by exactly one model-owned stable-ID constraint",
                    1, countTangent(cad, lineId, circleId));
            assertEquals("Migrated Tangent must not populate ShaprLab object-identity truth",
                    0, legacyTangentTruthCount(cad));
            cad.requireSketchMirrorParity();
            assertTrue("Model-owned Tangent must be undoable", cad.sketchAuthorityCanUndo());

            cad.undo();
            cad.requireSketchMirrorParity();
            assertEquals(0, countTangent(cad, lineId, circleId));
            assertEquals(0, legacyTangentTruthCount(cad));

            assertTrue("Tangent redo must restore the model transaction", cad.redoSketch());
            cad.requireSketchMirrorParity();
            assertEquals(1, countTangent(cad, lineId, circleId));
            assertEquals(0, legacyTangentTruthCount(cad));
            return true;
        });
    }

    @Test public void drawMustNeverRepairLegacyTangentProjectionOrMutateModelAuthority() throws Exception {
        onMain(() -> {
            K33MirroredCadCanvasView cad = cad();
            cad.executeCommand("CIRCLE 180 180 45");
            CadCanvasView.Entity circleLegacy = cad.selected;
            String circleId = circleLegacy.stableId();

            cad.executeCommand("LINE 225 180 315 95");
            CadCanvasView.LineEntity lineLegacy = (CadCanvasView.LineEntity) cad.selected;
            String lineId = lineLegacy.stableId();
            selectTwo(cad, lineLegacy, circleLegacy);
            String result = cad.applyTangentConstraint();
            assertTrue("Tangent rejected: " + result, result.contains("Tangent"));

            double[] modelBefore = modelLineSignature(cad, lineId);
            int modelConstraintCountBefore = cad.sketchConstraintCount();
            long transitionsBefore = cad.sketchAuthorityTransitionCount();
            int legacyTangentCountBefore = legacyTangentTruthCount(cad);

            // Legacy TangentRelation chooses endpoint 0 for this geometry. Drift it far from
            // the circle so a draw-time semantic repair is unambiguous.
            lineLegacy.x1 = 411f;
            lineLegacy.y1 = 337f;
            float[] drift = legacyLineSignature(lineLegacy);

            Bitmap bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888);
            cad.onDraw(new Canvas(bitmap));

            assertLegacyLineSame("onDraw must be presentation-only for Tangent", drift, lineLegacy);
            assertModelLineSame("onDraw must not mutate Tangent model geometry", modelBefore,
                    modelLineSignature(cad, lineId));
            assertEquals("onDraw must not add/remove semantic constraints",
                    modelConstraintCountBefore, cad.sketchConstraintCount());
            assertEquals("onDraw must not create authority history",
                    transitionsBefore, cad.sketchAuthorityTransitionCount());
            assertEquals("onDraw must not rewrite legacy relation ownership",
                    legacyTangentCountBefore, legacyTangentTruthCount(cad));
            assertEquals(lineId, lineLegacy.stableId());
            assertEquals(circleId, circleLegacy.stableId());
            return true;
        });
    }

    private static K33MirroredCadCanvasView cad() {
        Context context = ApplicationProvider.getApplicationContext();
        K33MirroredCadCanvasView cad = new K33MirroredCadCanvasView(context);
        cad.layout(0, 0, 1400, 1000);
        return cad;
    }

    private static void selectTwo(K33MirroredCadCanvasView cad,
                                  CadCanvasView.Entity first, CadCanvasView.Entity second) {
        cad.selectedObjects.clear();
        cad.selectedObjects.add(first);
        cad.selectedObjects.add(second);
        cad.selected = null;
    }

    private static int countTangent(K33MirroredCadCanvasView cad, String first, String second) {
        int count = 0;
        for (SketchConstraint constraint : cad.sketchConstraints()) {
            if (constraint.kind != SketchConstraint.Kind.TANGENT || constraint.secondaryEntityId == null) continue;
            boolean same = first.equals(constraint.primaryEntityId)
                    && second.equals(constraint.secondaryEntityId);
            boolean reverse = second.equals(constraint.primaryEntityId)
                    && first.equals(constraint.secondaryEntityId);
            if (same || reverse) count++;
        }
        return count;
    }

    private static int legacyTangentTruthCount(K33MirroredCadCanvasView cad) {
        try {
            Field field = ShaprLabCanvasView.class.getDeclaredField("tangentRelations");
            field.setAccessible(true);
            Object value = field.get(cad);
            if (value instanceof Collection) return ((Collection<?>) value).size();
            throw new AssertionError("Unexpected tangentRelations store: " + value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Cannot inspect legacy Tangent authority", e);
        }
    }

    private static SketchEntity modelEntity(K33MirroredCadCanvasView cad, String id) {
        List<SketchEntity> entities = cad.sketchMirrorEntities();
        for (SketchEntity entity : entities) if (id.equals(entity.id())) return entity;
        throw new AssertionError("Model entity not found: " + id + "; mirrorError=" + cad.sketchMirrorError());
    }

    private static double[] modelLineSignature(K33MirroredCadCanvasView cad, String id) {
        SketchGeometry.Line line = (SketchGeometry.Line) modelEntity(cad, id);
        return new double[]{line.a.xMm, line.a.yMm, line.b.xMm, line.b.yMm};
    }

    private static float[] legacyLineSignature(CadCanvasView.LineEntity line) {
        return new float[]{line.x1, line.y1, line.x2, line.y2};
    }

    private static void assertLegacyLineSame(String message, float[] expected,
                                             CadCanvasView.LineEntity actual) {
        float[] values = legacyLineSignature(actual);
        assertEquals(message + " x1", expected[0], values[0], 0f);
        assertEquals(message + " y1", expected[1], values[1], 0f);
        assertEquals(message + " x2", expected[2], values[2], 0f);
        assertEquals(message + " y2", expected[3], values[3], 0f);
    }

    private static void assertModelLineSame(String message, double[] expected, double[] actual) {
        assertEquals(message + " ax", expected[0], actual[0], 0.0);
        assertEquals(message + " ay", expected[1], actual[1], 0.0);
        assertEquals(message + " bx", expected[2], actual[2], 0.0);
        assertEquals(message + " by", expected[3], actual[3], 0.0);
    }

    private static <T> T onMain(Callable<T> callable) throws Exception {
        FutureTask<T> task = new FutureTask<>(callable);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(task);
        return task.get();
    }
}
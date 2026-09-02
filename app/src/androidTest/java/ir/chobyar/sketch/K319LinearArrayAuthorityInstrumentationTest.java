package ir.chobyar.sketch;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;

import ir.chobyar.sketch.core.SketchConstraint;
import ir.chobyar.sketch.core.SketchEntity;
import ir.chobyar.sketch.core.SketchGeometry;

/**
 * K3.19 RED fence for model-owned linear Array authority.
 *
 * Array keeps today's interaction semantics: the seed remains, count includes
 * the seed, each generated copy is translated by i * (dx,dy), and constraints
 * are not implicitly duplicated. The migration requirement is authority only:
 * all generated entities must be committed by stable id in one model history
 * step before the legacy layer becomes a projection of that transaction.
 */
@RunWith(AndroidJUnit4.class)
public final class K319LinearArrayAuthorityInstrumentationTest {
    private static final double EPS = 1.0e-4;

    @Test public void linearArrayMustCommitStableIdsIntoModelWithoutMirrorResync() throws Exception {
        onMain(() -> {
            K33MirroredCadCanvasView cad = canvas();
            cad.executeCommand("LINE 0 0 10 0");
            String seedId = cad.selected.stableId();
            long syncBefore = cad.sketchMirrorSyncCount();
            long transitionBefore = cad.sketchAuthorityTransitionCount();

            assertTrue(cad.arraySelected(4, 5f, 3f).contains("Array"));
            cad.requireSketchMirrorParity();

            assertEquals(4, cad.sketchMirrorEntities().size());
            assertEquals("model-owned Array must not rebuild authority from legacy",
                    syncBefore, cad.sketchMirrorSyncCount());
            assertEquals("one Array invocation must be one authority transition",
                    transitionBefore + 1L, cad.sketchAuthorityTransitionCount());
            assertTrue(cad.sketchAuthorityHistoryActive());
            assertEquals(0, cad.legacyMigratedConstraintTruthCount());
            assertStableIdsMatchLegacy(cad);

            List<SketchGeometry.Line> lines = modelLines(cad);
            assertEquals(seedId, lines.get(0).id());
            assertLine(lines.get(0), 0, 0, 10, 0);
            assertLine(lines.get(1), 5, 3, 15, 3);
            assertLine(lines.get(2), 10, 6, 20, 6);
            assertLine(lines.get(3), 15, 9, 25, 9);
            assertEquals(lines.get(3).id(), cad.selected.stableId());
            return true;
        });
    }

    @Test public void arrayUndoRedoMustRoundTripAllGeneratedCopiesAsOneModelHistoryStep() throws Exception {
        onMain(() -> {
            K33MirroredCadCanvasView cad = canvas();
            cad.executeCommand("LINE 2 4 12 4");
            String seedId = cad.selected.stableId();
            cad.arraySelected(5, 4f, -2f);
            cad.requireSketchMirrorParity();
            assertTrue(cad.sketchAuthorityHistoryActive());

            List<String> arrayIds = modelIds(cad);
            assertEquals(5, arrayIds.size());
            String selectedArrayId = cad.selected.stableId();
            assertEquals(arrayIds.get(4), selectedArrayId);

            cad.undo();
            cad.requireSketchMirrorParity();
            assertEquals(1, cad.sketchMirrorEntities().size());
            assertEquals(seedId, cad.sketchMirrorEntities().get(0).id());
            assertStableIdsMatchLegacy(cad);

            assertTrue(cad.redoSketch());
            cad.requireSketchMirrorParity();
            assertEquals(arrayIds, modelIds(cad));
            assertStableIdsMatchLegacy(cad);
            assertEquals(selectedArrayId, cad.selected.stableId());
            assertTrue(cad.sketchAuthorityHistoryActive());
            return true;
        });
    }

    @Test public void arrayMustNotCloneSeedConstraintsOrMutateSeedConstraintIdentity() throws Exception {
        onMain(() -> {
            K33MirroredCadCanvasView cad = canvas();
            cad.executeCommand("LINE 0 0 20 0");
            String seedId = cad.selected.stableId();
            assertTrue(cad.applyHorizontalVerticalConstraint().contains("H/V"));
            cad.requireSketchMirrorParity();
            SketchConstraint seedHorizontal = onlyKind(cad, SketchConstraint.Kind.HORIZONTAL);
            long syncBefore = cad.sketchMirrorSyncCount();
            long transitionBefore = cad.sketchAuthorityTransitionCount();

            cad.arraySelected(3, 0f, 8f);
            cad.requireSketchMirrorParity();

            assertEquals(3, cad.sketchMirrorEntities().size());
            assertEquals(1, cad.sketchConstraintCount());
            SketchConstraint remaining = onlyKind(cad, SketchConstraint.Kind.HORIZONTAL);
            assertEquals(seedHorizontal.id, remaining.id);
            assertEquals(seedId, remaining.primaryEntityId);
            assertEquals(syncBefore, cad.sketchMirrorSyncCount());
            assertEquals(transitionBefore + 1L, cad.sketchAuthorityTransitionCount());
            assertTrue(cad.sketchAuthorityHistoryActive());
            assertStableIdsMatchLegacy(cad);

            for (SketchEntity entity : cad.sketchMirrorEntities()) {
                if (seedId.equals(entity.id())) continue;
                for (SketchConstraint constraint : cad.sketchConstraints()) {
                    assertFalse("Array copies must not inherit seed constraints implicitly",
                            constraint.references(entity.id()));
                }
            }
            return true;
        });
    }

    @Test public void repeatedArraysMustCreateExactlyOneModelTransactionPerInvocation() throws Exception {
        onMain(() -> {
            K33MirroredCadCanvasView cad = canvas();
            cad.executeCommand("LINE 0 0 6 0");
            long syncBefore = cad.sketchMirrorSyncCount();
            long transitionBefore = cad.sketchAuthorityTransitionCount();

            cad.arraySelected(3, 10f, 0f);
            cad.requireSketchMirrorParity();
            List<String> firstIds = modelIds(cad);
            assertEquals(3, firstIds.size());
            assertTrue(cad.sketchAuthorityHistoryActive());

            cad.arraySelected(2, 0f, 10f);
            cad.requireSketchMirrorParity();
            List<String> secondIds = modelIds(cad);
            assertEquals(4, secondIds.size());
            assertEquals(syncBefore, cad.sketchMirrorSyncCount());
            assertEquals(transitionBefore + 2L, cad.sketchAuthorityTransitionCount());
            assertTrue(cad.sketchAuthorityHistoryActive());
            assertStableIdsMatchLegacy(cad);

            cad.undo();
            cad.requireSketchMirrorParity();
            assertEquals(firstIds, modelIds(cad));

            cad.undo();
            cad.requireSketchMirrorParity();
            assertEquals(1, cad.sketchMirrorEntities().size());

            assertTrue(cad.redoSketch());
            cad.requireSketchMirrorParity();
            assertEquals(firstIds, modelIds(cad));
            assertTrue(cad.redoSketch());
            cad.requireSketchMirrorParity();
            assertEquals(secondIds, modelIds(cad));
            return true;
        });
    }

    private static K33MirroredCadCanvasView canvas() {
        Context context = ApplicationProvider.getApplicationContext();
        K33MirroredCadCanvasView cad = new K33MirroredCadCanvasView(context);
        cad.clearAll();
        return cad;
    }

    private static List<SketchGeometry.Line> modelLines(K33MirroredCadCanvasView cad) {
        ArrayList<SketchGeometry.Line> out = new ArrayList<>();
        for (SketchEntity entity : cad.sketchMirrorEntities()) {
            if (!(entity instanceof SketchGeometry.Line)) {
                throw new AssertionError("expected only model lines, got " + entity.kind());
            }
            out.add((SketchGeometry.Line) entity);
        }
        return out;
    }

    private static List<String> modelIds(K33MirroredCadCanvasView cad) {
        ArrayList<String> out = new ArrayList<>();
        for (SketchEntity entity : cad.sketchMirrorEntities()) out.add(entity.id());
        return out;
    }

    private static void assertStableIdsMatchLegacy(K33MirroredCadCanvasView cad) {
        List<String> model = modelIds(cad);
        ArrayList<String> legacy = new ArrayList<>();
        for (CadCanvasView.Entity entity : cad.entities) {
            assertNotNull(entity.stableId());
            legacy.add(entity.stableId());
        }
        assertEquals(model, legacy);
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

    private static void assertLine(SketchGeometry.Line line,
            double x1, double y1, double x2, double y2) {
        assertEquals(x1, line.a.xMm, EPS);
        assertEquals(y1, line.a.yMm, EPS);
        assertEquals(x2, line.b.xMm, EPS);
        assertEquals(y2, line.b.yMm, EPS);
    }

    private static <T> T onMain(Callable<T> callable) throws Exception {
        FutureTask<T> task = new FutureTask<>(callable);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(task);
        return task.get();
    }
}

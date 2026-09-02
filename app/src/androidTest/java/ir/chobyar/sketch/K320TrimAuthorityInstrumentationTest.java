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

/** K3.20 RED fence for model-owned Trim authority. */
@RunWith(AndroidJUnit4.class)
public final class K320TrimAuthorityInstrumentationTest {
    private static final double EPS = 1.0e-4;

    @Test public void trimMustCommitStableIdsIntoModelWithoutMirrorResync() throws Exception {
        onMain(() -> {
            K33MirroredCadCanvasView cad = crossingCanvas();
            List<String> idsBefore = modelIds(cad);
            long syncBefore = cad.sketchMirrorSyncCount();
            long transitionBefore = cad.sketchAuthorityTransitionCount();

            assertTrue(cad.trimSelectedLines().contains("Trim"));
            cad.requireSketchMirrorParity();

            assertEquals(idsBefore, modelIds(cad));
            assertEquals(syncBefore, cad.sketchMirrorSyncCount());
            assertEquals(transitionBefore + 1L, cad.sketchAuthorityTransitionCount());
            assertTrue(cad.sketchAuthorityHistoryActive());
            assertStableIdsMatchLegacy(cad);

            List<SketchGeometry.Line> lines = modelLines(cad);
            assertLine(lines.get(0), 5, 0, 10, 0);
            assertLine(lines.get(1), 5, 0, 5, 5);
            return true;
        });
    }

    @Test public void trimUndoRedoMustRoundTripBothLinesAsOneModelHistoryStep() throws Exception {
        onMain(() -> {
            K33MirroredCadCanvasView cad = crossingCanvas();
            List<String> ids = modelIds(cad);
            List<SketchGeometry.Line> before = modelLines(cad);

            cad.trimSelectedLines();
            cad.requireSketchMirrorParity();
            List<SketchGeometry.Line> trimmed = modelLines(cad);
            assertFalse(sameGeometry(before, trimmed));

            cad.undo();
            cad.requireSketchMirrorParity();
            assertEquals(ids, modelIds(cad));
            assertGeometry(before, modelLines(cad));

            assertTrue(cad.redoSketch());
            cad.requireSketchMirrorParity();
            assertEquals(ids, modelIds(cad));
            assertGeometry(trimmed, modelLines(cad));
            assertTrue(cad.sketchAuthorityHistoryActive());
            return true;
        });
    }

    @Test public void trimMustPreserveExistingConstraintStableIds() throws Exception {
        onMain(() -> {
            K33MirroredCadCanvasView cad = crossingCanvas();
            assertTrue(cad.applyHorizontalVerticalConstraint().contains("H/V"));
            cad.requireSketchMirrorParity();
            List<SketchConstraint> before = new ArrayList<>(cad.sketchConstraints());
            assertEquals(2, before.size());
            long syncBefore = cad.sketchMirrorSyncCount();
            long transitionBefore = cad.sketchAuthorityTransitionCount();

            cad.trimSelectedLines();
            cad.requireSketchMirrorParity();

            List<SketchConstraint> after = new ArrayList<>(cad.sketchConstraints());
            assertEquals(2, after.size());
            assertEquals(before.get(0).id, after.get(0).id);
            assertEquals(before.get(1).id, after.get(1).id);
            assertEquals(before.get(0).kind, after.get(0).kind);
            assertEquals(before.get(1).kind, after.get(1).kind);
            assertEquals(syncBefore, cad.sketchMirrorSyncCount());
            assertEquals(transitionBefore + 1L, cad.sketchAuthorityTransitionCount());
            assertStableIdsMatchLegacy(cad);
            return true;
        });
    }

    @Test public void parallelTrimMustBeTrueNoOpWithoutMirrorResyncOrHistoryStep() throws Exception {
        onMain(() -> {
            K33MirroredCadCanvasView cad = canvas();
            CadCanvasView.Entity a = createLine(cad, 0, 0, 10, 0);
            CadCanvasView.Entity b = createLine(cad, 0, 5, 10, 5);
            selectPair(cad, a, b);
            List<SketchGeometry.Line> before = modelLines(cad);
            long syncBefore = cad.sketchMirrorSyncCount();
            long transitionBefore = cad.sketchAuthorityTransitionCount();
            boolean historyBefore = cad.sketchAuthorityHistoryActive();

            String out = cad.trimSelectedLines();
            cad.requireSketchMirrorParity();

            assertTrue(out.contains("Parallel"));
            assertGeometry(before, modelLines(cad));
            assertEquals(syncBefore, cad.sketchMirrorSyncCount());
            assertEquals(transitionBefore, cad.sketchAuthorityTransitionCount());
            assertEquals(historyBefore, cad.sketchAuthorityHistoryActive());
            assertStableIdsMatchLegacy(cad);
            return true;
        });
    }

    private static K33MirroredCadCanvasView crossingCanvas() {
        K33MirroredCadCanvasView cad = canvas();
        CadCanvasView.Entity a = createLine(cad, 0, 0, 10, 0);
        CadCanvasView.Entity b = createLine(cad, 5, -5, 5, 5);
        selectPair(cad, a, b);
        return cad;
    }

    private static K33MirroredCadCanvasView canvas() {
        Context context = ApplicationProvider.getApplicationContext();
        K33MirroredCadCanvasView cad = new K33MirroredCadCanvasView(context);
        cad.clearAll();
        return cad;
    }

    private static CadCanvasView.Entity createLine(K33MirroredCadCanvasView cad,
            float x1, float y1, float x2, float y2) {
        cad.executeCommand("LINE " + x1 + " " + y1 + " " + x2 + " " + y2);
        assertNotNull(cad.selected);
        return cad.selected;
    }

    private static void selectPair(K33MirroredCadCanvasView cad,
            CadCanvasView.Entity a, CadCanvasView.Entity b) {
        cad.selectedObjects.clear();
        cad.selectedObjects.add(a);
        cad.selectedObjects.add(b);
        cad.selected = null;
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

    private static void assertGeometry(List<SketchGeometry.Line> expected,
            List<SketchGeometry.Line> actual) {
        assertEquals(expected.size(), actual.size());
        for (int i = 0; i < expected.size(); i++) {
            SketchGeometry.Line e = expected.get(i);
            SketchGeometry.Line a = actual.get(i);
            assertEquals(e.id(), a.id());
            assertLine(a, e.a.xMm, e.a.yMm, e.b.xMm, e.b.yMm);
        }
    }

    private static boolean sameGeometry(List<SketchGeometry.Line> a, List<SketchGeometry.Line> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            SketchGeometry.Line x = a.get(i), y = b.get(i);
            if (Math.abs(x.a.xMm - y.a.xMm) > EPS
                    || Math.abs(x.a.yMm - y.a.yMm) > EPS
                    || Math.abs(x.b.xMm - y.b.xMm) > EPS
                    || Math.abs(x.b.yMm - y.b.yMm) > EPS) return false;
        }
        return true;
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

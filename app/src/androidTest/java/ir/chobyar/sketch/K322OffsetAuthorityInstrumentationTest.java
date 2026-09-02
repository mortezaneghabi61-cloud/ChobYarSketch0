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

/** K3.22 RED fence for model-owned Offset authority. */
@RunWith(AndroidJUnit4.class)
public final class K322OffsetAuthorityInstrumentationTest {
    private static final double EPS = 1.0e-4;

    @Test public void lineOffsetMustCommitFreshStableIdWithoutMirrorResync() throws Exception {
        onMain(() -> {
            K33MirroredCadCanvasView cad = canvas();
            cad.executeCommand("LINE 0 0 10 0");
            String seedId = cad.selected.stableId();
            long syncBefore = cad.sketchMirrorSyncCount();
            long transitionBefore = cad.sketchAuthorityTransitionCount();

            assertTrue(cad.offsetSelected(5f).contains("Offset"));
            cad.requireSketchMirrorParity();

            assertEquals(2, cad.sketchMirrorEntities().size());
            assertEquals(syncBefore, cad.sketchMirrorSyncCount());
            assertEquals(transitionBefore + 1L, cad.sketchAuthorityTransitionCount());
            assertTrue(cad.sketchAuthorityHistoryActive());
            assertStableIdsMatchLegacy(cad);

            List<SketchGeometry.Line> lines = modelLines(cad);
            assertEquals(seedId, lines.get(0).id());
            assertLine(lines.get(0), 0, 0, 10, 0);
            assertLine(lines.get(1), 0, 5, 10, 5);
            assertFalse(seedId.equals(lines.get(1).id()));
            assertEquals(lines.get(1).id(), cad.selected.stableId());
            return true;
        });
    }

    @Test public void multiOffsetMustCommitLineAndCircleCopiesAsOneAuthorityTransition() throws Exception {
        onMain(() -> {
            K33MirroredCadCanvasView cad = canvas();
            CadCanvasView.Entity line = createLine(cad, 0, 0, 10, 0);
            CadCanvasView.Entity circle = createCircle(cad, 20, 20, 5);
            String lineId = line.stableId();
            String circleId = circle.stableId();
            selectPair(cad, line, circle);
            long syncBefore = cad.sketchMirrorSyncCount();
            long transitionBefore = cad.sketchAuthorityTransitionCount();

            assertTrue(cad.offsetSelected(3f).contains("Offset"));
            cad.requireSketchMirrorParity();

            assertEquals(4, cad.sketchMirrorEntities().size());
            assertEquals(syncBefore, cad.sketchMirrorSyncCount());
            assertEquals(transitionBefore + 1L, cad.sketchAuthorityTransitionCount());
            assertTrue(cad.sketchAuthorityHistoryActive());
            assertStableIdsMatchLegacy(cad);

            SketchGeometry.Line sourceLine = lineById(cad, lineId);
            SketchGeometry.Circle sourceCircle = circleById(cad, circleId);
            assertLine(sourceLine, 0, 0, 10, 0);
            assertEquals(5.0, sourceCircle.radiusMm, EPS);

            List<SketchGeometry.Line> lines = modelLines(cad);
            List<SketchGeometry.Circle> circles = modelCircles(cad);
            assertEquals(2, lines.size());
            assertEquals(2, circles.size());
            SketchGeometry.Line lineCopy = seedIdDifferentLine(lines, lineId);
            SketchGeometry.Circle circleCopy = seedIdDifferentCircle(circles, circleId);
            assertLine(lineCopy, 0, 3, 10, 3);
            assertEquals(8.0, circleCopy.radiusMm, EPS);
            return true;
        });
    }

    @Test public void offsetUndoRedoMustRoundTripGeneratedIdsAsOneModelHistoryStep() throws Exception {
        onMain(() -> {
            K33MirroredCadCanvasView cad = canvas();
            CadCanvasView.Entity line = createLine(cad, 2, 4, 12, 4);
            CadCanvasView.Entity circle = createCircle(cad, 30, 10, 4);
            selectPair(cad, line, circle);
            List<String> seedIds = modelIds(cad);

            cad.offsetSelected(2f);
            cad.requireSketchMirrorParity();
            List<String> offsetIds = modelIds(cad);
            assertEquals(4, offsetIds.size());
            assertTrue(cad.sketchAuthorityHistoryActive());

            cad.undo();
            cad.requireSketchMirrorParity();
            assertEquals(seedIds, modelIds(cad));
            assertStableIdsMatchLegacy(cad);

            assertTrue(cad.redoSketch());
            cad.requireSketchMirrorParity();
            assertEquals(offsetIds, modelIds(cad));
            assertStableIdsMatchLegacy(cad);
            assertTrue(cad.sketchAuthorityHistoryActive());
            return true;
        });
    }

    @Test public void offsetMustNotCloneSeedConstraintsOrMutateConstraintIdentity() throws Exception {
        onMain(() -> {
            K33MirroredCadCanvasView cad = canvas();
            cad.executeCommand("LINE 0 0 20 0");
            String seedId = cad.selected.stableId();
            assertTrue(cad.applyHorizontalVerticalConstraint().contains("H/V"));
            cad.requireSketchMirrorParity();
            SketchConstraint seedHorizontal = onlyKind(cad, SketchConstraint.Kind.HORIZONTAL);
            long syncBefore = cad.sketchMirrorSyncCount();
            long transitionBefore = cad.sketchAuthorityTransitionCount();

            cad.offsetSelected(6f);
            cad.requireSketchMirrorParity();

            assertEquals(2, cad.sketchMirrorEntities().size());
            assertEquals(1, cad.sketchConstraintCount());
            SketchConstraint remaining = onlyKind(cad, SketchConstraint.Kind.HORIZONTAL);
            assertEquals(seedHorizontal.id, remaining.id);
            assertEquals(seedId, remaining.primaryEntityId);
            assertEquals(syncBefore, cad.sketchMirrorSyncCount());
            assertEquals(transitionBefore + 1L, cad.sketchAuthorityTransitionCount());
            assertStableIdsMatchLegacy(cad);

            for (SketchEntity entity : cad.sketchMirrorEntities()) {
                if (seedId.equals(entity.id())) continue;
                for (SketchConstraint constraint : cad.sketchConstraints()) {
                    assertFalse("Offset copies must not inherit seed constraints implicitly",
                            constraint.references(entity.id()));
                }
            }
            return true;
        });
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

    private static CadCanvasView.Entity createCircle(K33MirroredCadCanvasView cad,
            float x, float y, float radius) {
        cad.executeCommand("CIRCLE " + x + " " + y + " " + radius);
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
            if (entity instanceof SketchGeometry.Line) out.add((SketchGeometry.Line) entity);
        }
        return out;
    }

    private static List<SketchGeometry.Circle> modelCircles(K33MirroredCadCanvasView cad) {
        ArrayList<SketchGeometry.Circle> out = new ArrayList<>();
        for (SketchEntity entity : cad.sketchMirrorEntities()) {
            if (entity instanceof SketchGeometry.Circle) out.add((SketchGeometry.Circle) entity);
        }
        return out;
    }

    private static SketchGeometry.Line lineById(K33MirroredCadCanvasView cad, String id) {
        for (SketchGeometry.Line line : modelLines(cad)) if (id.equals(line.id())) return line;
        throw new AssertionError("missing model line " + id);
    }

    private static SketchGeometry.Circle circleById(K33MirroredCadCanvasView cad, String id) {
        for (SketchGeometry.Circle circle : modelCircles(cad)) if (id.equals(circle.id())) return circle;
        throw new AssertionError("missing model circle " + id);
    }

    private static SketchGeometry.Line seedIdDifferentLine(List<SketchGeometry.Line> lines, String seedId) {
        for (SketchGeometry.Line line : lines) if (!seedId.equals(line.id())) return line;
        throw new AssertionError("missing offset line copy");
    }

    private static SketchGeometry.Circle seedIdDifferentCircle(List<SketchGeometry.Circle> circles, String seedId) {
        for (SketchGeometry.Circle circle : circles) if (!seedId.equals(circle.id())) return circle;
        throw new AssertionError("missing offset circle copy");
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

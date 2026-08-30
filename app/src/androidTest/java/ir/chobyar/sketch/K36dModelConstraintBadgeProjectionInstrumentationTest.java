package ir.chobyar.sketch;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;

import ir.chobyar.sketch.core.DeterministicSketchConstraintSolver;
import ir.chobyar.sketch.core.SketchConstraint;
import ir.chobyar.sketch.core.SketchDocument;
import ir.chobyar.sketch.core.SketchGeometry;

import static org.junit.Assert.*;

/** Regression fence: migrated endpoint badges are pure projections of model truth. */
@RunWith(AndroidJUnit4.class)
public class K36dModelConstraintBadgeProjectionInstrumentationTest {
    private static final double EPS = 1.0e-4;

    @Test public void coincidentBadgeUsesModelConstraintStableIdsPointIndexAndSolvedPoint() throws Exception {
        onMain(() -> {
            K33MirroredCadCanvasView cad = canvas();
            cad.executeCommand("LINE 0 0 10 0");
            CadCanvasView.Entity anchor = cad.selected;
            String anchorId = anchor.stableId();
            cad.executeCommand("LINE 12 2 20 2");
            CadCanvasView.Entity moving = cad.selected;
            String movingId = moving.stableId();
            select(cad, anchor, moving);

            assertTrue(cad.applyManualCoincident().contains("Coincident"));
            List<ModelConstraintBadgeProjection.Badge> badges = ModelConstraintBadgeProjection.project(
                    cad.sketchConstraints(), cad.sketchMirrorEntities());

            assertEquals(1, badges.size());
            ModelConstraintBadgeProjection.Badge badge = badges.get(0);
            assertEquals(ModelConstraintBadgeProjection.BadgeKind.COINCIDENT, badge.kind);
            assertEquals("Coincident", badge.accessibilityLabel());
            assertEquals(movingId, badge.pointEntityId);
            assertEquals(0, badge.pointIndex);
            assertEquals(anchorId, badge.targetEntityId);
            assertEquals(1, badge.targetPointIndex);
            assertEquals(10.0, badge.xMm, EPS);
            assertEquals(0.0, badge.yMm, EPS);
            assertEquals(0, cad.legacyMigratedConstraintTruthCount());
            return true;
        });
    }

    @Test public void pointOnEntityBadgeIdentifiesDrivenEndpointAndHostWithoutLegacyState() throws Exception {
        onMain(() -> {
            K33MirroredCadCanvasView cad = canvas();
            cad.executeCommand("LINE 0 0 20 0");
            String hostId = cad.selected.stableId();
            cad.executeCommand("LINE 7 3 7 10");
            String ownerId = cad.selected.stableId();

            assertEquals(ConstraintInteractionContract.ResultCode.APPLIED,
                    cad.applyModelPointOnEntityForTest(ownerId, 0, hostId).code);
            List<ModelConstraintBadgeProjection.Badge> badges = ModelConstraintBadgeProjection.project(
                    cad.sketchConstraints(), cad.sketchMirrorEntities());

            assertEquals(1, badges.size());
            ModelConstraintBadgeProjection.Badge badge = badges.get(0);
            assertEquals(ModelConstraintBadgeProjection.BadgeKind.POINT_ON_ENTITY, badge.kind);
            assertEquals("Point on entity", badge.accessibilityLabel());
            assertEquals(ownerId, badge.pointEntityId);
            assertEquals(0, badge.pointIndex);
            assertEquals(hostId, badge.targetEntityId);
            assertEquals(-1, badge.targetPointIndex);
            assertEquals(7.0, badge.xMm, EPS);
            assertEquals(0.0, badge.yMm, EPS);
            assertEquals(0, cad.legacyMigratedConstraintTruthCount());
            return true;
        });
    }

    @Test public void projectionIsEphemeralAndTracksUndoRedoModelState() throws Exception {
        onMain(() -> {
            K33MirroredCadCanvasView cad = canvas();
            cad.executeCommand("LINE 0 0 20 0");
            String hostId = cad.selected.stableId();
            cad.executeCommand("LINE 5 2 5 8");
            String ownerId = cad.selected.stableId();
            assertEquals(ConstraintInteractionContract.ResultCode.APPLIED,
                    cad.applyModelPointOnEntityForTest(ownerId, 0, hostId).code);

            List<ModelConstraintBadgeProjection.Badge> applied = ModelConstraintBadgeProjection.project(
                    cad.sketchConstraints(), cad.sketchMirrorEntities());
            assertEquals(1, applied.size());

            cad.undo();
            assertTrue(ModelConstraintBadgeProjection.project(
                    cad.sketchConstraints(), cad.sketchMirrorEntities()).isEmpty());

            assertTrue(cad.redoSketch());
            List<ModelConstraintBadgeProjection.Badge> redone = ModelConstraintBadgeProjection.project(
                    cad.sketchConstraints(), cad.sketchMirrorEntities());
            assertEquals(1, redone.size());
            assertEquals(applied.get(0).constraintId, redone.get(0).constraintId);
            assertEquals(0, cad.legacyMigratedConstraintTruthCount());
            return true;
        });
    }

    @Test public void deletingHostRemovesProjectedBadgeAndUndoRestoresSameStableConstraint() {
        SketchDocument document = new SketchDocument();
        document.add(line("host", 0, 0, 20, 0));
        document.add(line("owner", 6, 4, 6, 10));
        document.addConstraintsAndSolve(
                Collections.singletonList(SketchConstraint.pointOnEntity(
                        "p-delete-badge", "owner", 0, "host")),
                new DeterministicSketchConstraintSolver());

        List<ModelConstraintBadgeProjection.Badge> applied = ModelConstraintBadgeProjection.project(document);
        assertEquals(1, applied.size());
        assertEquals("p-delete-badge", applied.get(0).constraintId);
        assertEquals("host", applied.get(0).targetEntityId);

        assertTrue(document.remove("host"));
        assertTrue(ModelConstraintBadgeProjection.project(document).isEmpty());

        assertTrue(document.undo());
        List<ModelConstraintBadgeProjection.Badge> restored = ModelConstraintBadgeProjection.project(document);
        assertEquals(1, restored.size());
        assertEquals("p-delete-badge", restored.get(0).constraintId);
        assertEquals("owner", restored.get(0).pointEntityId);
        assertEquals(0, restored.get(0).pointIndex);
        assertEquals("host", restored.get(0).targetEntityId);
    }

    private static SketchGeometry.Line line(String id, double x1, double y1, double x2, double y2) {
        return new SketchGeometry.Line(id,
                new SketchGeometry.Point(x1, y1),
                new SketchGeometry.Point(x2, y2));
    }

    private static K33MirroredCadCanvasView canvas() {
        Context context = ApplicationProvider.getApplicationContext();
        K33MirroredCadCanvasView view = new K33MirroredCadCanvasView(context);
        view.clearAll();
        return view;
    }

    private static void select(K33MirroredCadCanvasView cad, CadCanvasView.Entity... values) {
        cad.selectedObjects.clear();
        for (CadCanvasView.Entity value : values) cad.selectedObjects.add(value);
        cad.selected = values.length == 0 ? null : values[values.length - 1];
    }

    private static <T> T onMain(Callable<T> callable) throws Exception {
        FutureTask<T> task = new FutureTask<>(callable);
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().runOnMainSync(task);
        return task.get();
    }
}

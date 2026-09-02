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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;

import ir.chobyar.sketch.core.ConstraintInteractionContract;
import ir.chobyar.sketch.core.SketchConstraint;
import ir.chobyar.sketch.core.SketchEntity;
import ir.chobyar.sketch.core.SketchGeometry;

/**
 * K3.18 RED fence for Disconnect authority.
 *
 * Disconnect is deliberately narrow: it releases COINCIDENT, POINT_ON_ENTITY
 * and MIDPOINT relationships incident to the selected stable entity IDs. It
 * must not delete unrelated relationships or other constraint kinds, must not
 * move geometry merely because freedom was restored, and all removals from one
 * invocation must share one model Undo/Redo transaction.
 */
@RunWith(AndroidJUnit4.class)
public final class K318DisconnectAuthorityInstrumentationTest {
    private static final double EPS = 1.0e-4;

    @Test public void disconnectCoincidentMustRemoveStableIdModelConstraintWithoutLegacyTruthAndRoundTripOneHistoryStep() throws Exception {
        onMain(() -> {
            K33MirroredCadCanvasView cad = canvas();
            cad.executeCommand("LINE 0 0 10 0");
            CadCanvasView.Entity a = cad.selected;
            String aId = a.stableId();
            cad.executeCommand("LINE 12 2 20 2");
            CadCanvasView.Entity b = cad.selected;
            String bId = b.stableId();
            select(cad, a, b);
            assertTrue(cad.applyManualCoincident().contains("Coincident"));
            cad.requireSketchMirrorParity();

            SketchConstraint before = onlyKind(cad, SketchConstraint.Kind.COINCIDENT);
            String constraintId = before.id;
            Map<String, double[]> geometryBefore = lineGeometry(cad);
            long transitionBefore = cad.sketchAuthorityTransitionCount();
            assertEquals(0, cad.legacyMigratedConstraintTruthCount());

            select(cad, b);
            cad.disconnectSelectedConnections();
            cad.requireSketchMirrorParity();

            assertEquals(0, countKind(cad, SketchConstraint.Kind.COINCIDENT));
            assertEquals(0, cad.legacyMigratedConstraintTruthCount());
            assertGeometrySame(geometryBefore, cad);
            assertEquals("one Disconnect invocation must be one authority transition",
                    transitionBefore + 1L, cad.sketchAuthorityTransitionCount());

            cad.undo();
            cad.requireSketchMirrorParity();
            SketchConstraint restored = onlyKind(cad, SketchConstraint.Kind.COINCIDENT);
            assertEquals(constraintId, restored.id);
            assertBinaryReferences(restored, aId, bId);
            assertGeometrySame(geometryBefore, cad);

            assertTrue(cad.redoSketch());
            cad.requireSketchMirrorParity();
            assertEquals(0, countKind(cad, SketchConstraint.Kind.COINCIDENT));
            assertGeometrySame(geometryBefore, cad);
            assertEquals(0, cad.legacyMigratedConstraintTruthCount());
            return true;
        });
    }

    @Test public void disconnectHostMustRemovePointOnEntityAndMidpointAtomicallyButPreserveHorizontalAndGeometry() throws Exception {
        onMain(() -> {
            K33MirroredCadCanvasView cad = canvas();
            cad.executeCommand("LINE 0 0 30 0");
            CadCanvasView.Entity host = cad.selected;
            String hostId = host.stableId();
            select(cad, host);
            assertTrue(cad.applyHorizontalVerticalConstraint().contains("H/V"));

            cad.executeCommand("LINE 7 4 7 10");
            String pointOwnerId = cad.selected.stableId();
            assertEquals(ConstraintInteractionContract.ResultCode.APPLIED,
                    cad.applyModelPointOnEntityForTest(pointOwnerId, 0, hostId).code);

            cad.executeCommand("LINE 15 5 15 12");
            CadCanvasView.Entity midpointOwner = cad.selected;
            String midpointOwnerId = midpointOwner.stableId();
            select(cad, host, midpointOwner);
            assertTrue(cad.applyMidpointConstraint().contains("Midpoint"));
            cad.requireSketchMirrorParity();

            SketchConstraint horizontal = onlyKind(cad, SketchConstraint.Kind.HORIZONTAL);
            SketchConstraint pointOn = onlyKind(cad, SketchConstraint.Kind.POINT_ON_ENTITY);
            SketchConstraint midpoint = onlyKind(cad, SketchConstraint.Kind.MIDPOINT);
            Map<String, double[]> geometryBefore = lineGeometry(cad);
            assertEquals(3, cad.sketchConstraintCount());
            assertEquals(0, cad.legacyMigratedConstraintTruthCount());

            select(cad, host);
            cad.disconnectSelectedConnections();
            cad.requireSketchMirrorParity();

            assertEquals(1, cad.sketchConstraintCount());
            SketchConstraint remaining = cad.sketchConstraints().get(0);
            assertEquals(SketchConstraint.Kind.HORIZONTAL, remaining.kind);
            assertEquals(horizontal.id, remaining.id);
            assertEquals(0, countKind(cad, SketchConstraint.Kind.POINT_ON_ENTITY));
            assertEquals(0, countKind(cad, SketchConstraint.Kind.MIDPOINT));
            assertGeometrySame(geometryBefore, cad);
            assertEquals(0, cad.legacyMigratedConstraintTruthCount());

            cad.undo();
            cad.requireSketchMirrorParity();
            assertEquals(3, cad.sketchConstraintCount());
            assertEquals(pointOn.id, onlyKind(cad, SketchConstraint.Kind.POINT_ON_ENTITY).id);
            assertEquals(midpoint.id, onlyKind(cad, SketchConstraint.Kind.MIDPOINT).id);
            assertEquals(horizontal.id, onlyKind(cad, SketchConstraint.Kind.HORIZONTAL).id);
            assertGeometrySame(geometryBefore, cad);

            assertTrue(cad.redoSketch());
            cad.requireSketchMirrorParity();
            assertEquals(1, cad.sketchConstraintCount());
            assertEquals(SketchConstraint.Kind.HORIZONTAL, cad.sketchConstraints().get(0).kind);
            assertNotNull(modelLine(cad, midpointOwnerId));
            return true;
        });
    }

    @Test public void disconnectMustOnlyRemoveDisconnectableRelationshipsIncidentToSelectedStableIds() throws Exception {
        onMain(() -> {
            K33MirroredCadCanvasView cad = canvas();

            cad.executeCommand("LINE 0 0 10 0");
            CadCanvasView.Entity a = cad.selected;
            String aId = a.stableId();
            cad.executeCommand("LINE 12 2 20 2");
            CadCanvasView.Entity b = cad.selected;
            String bId = b.stableId();
            select(cad, a, b);
            assertTrue(cad.applyManualCoincident().contains("Coincident"));
            String firstConstraintId = onlyKind(cad, SketchConstraint.Kind.COINCIDENT).id;

            cad.executeCommand("LINE 40 0 50 0");
            CadCanvasView.Entity c = cad.selected;
            String cId = c.stableId();
            cad.executeCommand("LINE 52 2 60 2");
            CadCanvasView.Entity d = cad.selected;
            String dId = d.stableId();
            select(cad, c, d);
            assertTrue(cad.applyManualCoincident().contains("Coincident"));
            cad.requireSketchMirrorParity();
            assertEquals(2, countKind(cad, SketchConstraint.Kind.COINCIDENT));

            SketchConstraint second = coincidentForPair(cad, cId, dId);
            assertNotNull(second);
            String secondConstraintId = second.id;

            select(cad, a);
            cad.disconnectSelectedConnections();
            cad.requireSketchMirrorParity();

            assertEquals(1, countKind(cad, SketchConstraint.Kind.COINCIDENT));
            SketchConstraint remaining = onlyKind(cad, SketchConstraint.Kind.COINCIDENT);
            assertEquals(secondConstraintId, remaining.id);
            assertBinaryReferences(remaining, cId, dId);
            assertFalse(firstConstraintId.equals(remaining.id));
            assertEquals(0, cad.legacyMigratedConstraintTruthCount());

            cad.undo();
            cad.requireSketchMirrorParity();
            assertEquals(2, countKind(cad, SketchConstraint.Kind.COINCIDENT));
            assertNotNull(coincidentForPair(cad, aId, bId));
            assertNotNull(coincidentForPair(cad, cId, dId));
            return true;
        });
    }

    @Test public void disconnectWithNoIncidentRelationshipMustBeModelNoOpAndMustNotCreateEmptyUndoStep() throws Exception {
        onMain(() -> {
            K33MirroredCadCanvasView cad = canvas();
            cad.executeCommand("LINE 0 0 10 0");
            CadCanvasView.Entity line = cad.selected;
            String lineId = line.stableId();
            select(cad, line);

            String persistenceBefore = cad.exportSketchProjectState();
            long transitionBefore = cad.sketchAuthorityTransitionCount();
            int constraintsBefore = cad.sketchConstraintCount();

            cad.disconnectSelectedConnections();
            cad.requireSketchMirrorParity();

            assertEquals(persistenceBefore, cad.exportSketchProjectState());
            assertEquals(transitionBefore, cad.sketchAuthorityTransitionCount());
            assertEquals(constraintsBefore, cad.sketchConstraintCount());
            assertEquals(0, cad.legacyMigratedConstraintTruthCount());

            // If Disconnect inserted an empty model history step, this Undo would be a no-op.
            cad.undo();
            cad.requireSketchMirrorParity();
            assertFalse(containsEntity(cad, lineId));
            return true;
        });
    }

    private static K33MirroredCadCanvasView canvas() {
        Context context = ApplicationProvider.getApplicationContext();
        K33MirroredCadCanvasView cad = new K33MirroredCadCanvasView(context);
        cad.clearAll();
        return cad;
    }

    private static void select(K33MirroredCadCanvasView cad, CadCanvasView.Entity... values) {
        cad.selectedObjects.clear();
        for (CadCanvasView.Entity value : values) cad.selectedObjects.add(value);
        cad.selected = values.length == 0 ? null : values[values.length - 1];
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

    private static int countKind(K33MirroredCadCanvasView cad, SketchConstraint.Kind kind) {
        int count = 0;
        for (SketchConstraint constraint : cad.sketchConstraints()) {
            if (constraint.kind == kind) count++;
        }
        return count;
    }

    private static SketchConstraint coincidentForPair(K33MirroredCadCanvasView cad, String a, String b) {
        for (SketchConstraint constraint : cad.sketchConstraints()) {
            if (constraint.kind != SketchConstraint.Kind.COINCIDENT) continue;
            if (binaryReferences(constraint, a, b)) return constraint;
        }
        return null;
    }

    private static void assertBinaryReferences(SketchConstraint constraint, String a, String b) {
        assertTrue("constraint must reference both selected stable entity IDs",
                binaryReferences(constraint, a, b));
    }

    private static boolean binaryReferences(SketchConstraint constraint, String a, String b) {
        return (a.equals(constraint.primaryEntityId) && b.equals(constraint.secondaryEntityId))
                || (b.equals(constraint.primaryEntityId) && a.equals(constraint.secondaryEntityId));
    }

    private static Map<String, double[]> lineGeometry(K33MirroredCadCanvasView cad) {
        LinkedHashMap<String, double[]> out = new LinkedHashMap<>();
        for (SketchEntity entity : cad.sketchMirrorEntities()) {
            if (!(entity instanceof SketchGeometry.Line)) continue;
            SketchGeometry.Line line = (SketchGeometry.Line) entity;
            out.put(entity.id(), new double[]{line.a.xMm, line.a.yMm, line.b.xMm, line.b.yMm});
        }
        return out;
    }

    private static void assertGeometrySame(Map<String, double[]> expected, K33MirroredCadCanvasView cad) {
        Map<String, double[]> actual = lineGeometry(cad);
        assertEquals(expected.keySet(), actual.keySet());
        for (Map.Entry<String, double[]> row : expected.entrySet()) {
            double[] a = row.getValue();
            double[] b = actual.get(row.getKey());
            assertNotNull(b);
            assertEquals(a[0], b[0], EPS);
            assertEquals(a[1], b[1], EPS);
            assertEquals(a[2], b[2], EPS);
            assertEquals(a[3], b[3], EPS);
        }
    }

    private static SketchGeometry.Line modelLine(K33MirroredCadCanvasView cad, String id) {
        for (SketchEntity entity : cad.sketchMirrorEntities()) {
            if (id.equals(entity.id())) return (SketchGeometry.Line) entity;
        }
        throw new AssertionError("missing model line " + id);
    }

    private static boolean containsEntity(K33MirroredCadCanvasView cad, String id) {
        for (SketchEntity entity : cad.sketchMirrorEntities()) if (id.equals(entity.id())) return true;
        return false;
    }

    private static <T> T onMain(Callable<T> callable) throws Exception {
        FutureTask<T> task = new FutureTask<>(callable);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(task);
        return task.get();
    }
}

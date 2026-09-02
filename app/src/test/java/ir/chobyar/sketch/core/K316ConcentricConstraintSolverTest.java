package ir.chobyar.sketch.core;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** JVM contract for K3.16 model-owned Circle/Arc CONCENTRIC authority. */
public final class K316ConcentricConstraintSolverTest {
    private static final double EPS = 1.0e-7;
    private final DeterministicSketchConstraintSolver solver =
            new DeterministicSketchConstraintSolver();

    @Test public void modelExposesStableIdConcentricConstraint() {
        assertNotNull(Enum.valueOf(SketchConstraint.Kind.class, "CONCENTRIC"));
        SketchConstraint c = SketchConstraint.concentric("con-1", "anchor", "driven");
        assertEquals(SketchConstraint.Kind.CONCENTRIC, c.kind);
        assertEquals("anchor", c.primaryEntityId);
        assertEquals("driven", c.secondaryEntityId);
        assertTrue(c.referencedEntityIds().containsAll(Arrays.asList("anchor", "driven")));
    }

    @Test public void circleCircleConvergesDrivenCenterAndPreservesRadiiAndStableIds() {
        SketchGeometry.Circle anchor = circle("anchor", 10, 20, 7);
        SketchGeometry.Circle driven = circle("driven", 80, 90, 13);

        SketchConstraintSolver.Result result = solve(anchor, driven);

        assertTrue(result.solved());
        SketchGeometry.Circle solvedAnchor = (SketchGeometry.Circle) entity(result, "anchor");
        SketchGeometry.Circle solvedDriven = (SketchGeometry.Circle) entity(result, "driven");
        assertCenter(solvedAnchor.center, solvedDriven.center);
        assertEquals(7.0, solvedAnchor.radiusMm, 0.0);
        assertEquals(13.0, solvedDriven.radiusMm, 0.0);
        assertEquals("anchor", solvedAnchor.id());
        assertEquals("driven", solvedDriven.id());
    }

    @Test public void circleArcAndArcArcPreserveEveryNonCenterParameter() {
        SketchGeometry.Circle circle = circle("circle", 2, 3, 5);
        SketchGeometry.Arc arc = arc("arc", 40, 50, 11, 27, 133);
        SketchConstraintSolver.Result circleArc = solve(circle, arc);
        assertTrue(circleArc.solved());
        SketchGeometry.Arc solvedArc = (SketchGeometry.Arc) entity(circleArc, "arc");
        assertCenter(circle.center, solvedArc.center);
        assertEquals(11.0, solvedArc.radiusMm, 0.0);
        assertEquals(27.0, solvedArc.startDeg, 0.0);
        assertEquals(133.0, solvedArc.sweepDeg, 0.0);

        SketchGeometry.Arc anchor = arc("a", -8, 6, 9, -45, 90);
        SketchGeometry.Arc driven = arc("b", 90, -40, 17, 15, -210);
        SketchConstraintSolver.Result arcArc = solve(anchor, driven);
        assertTrue(arcArc.solved());
        SketchGeometry.Arc solvedDriven = (SketchGeometry.Arc) entity(arcArc, "b");
        assertCenter(anchor.center, solvedDriven.center);
        assertEquals(17.0, solvedDriven.radiusMm, 0.0);
        assertEquals(15.0, solvedDriven.startDeg, 0.0);
        assertEquals(-210.0, solvedDriven.sweepDeg, 0.0);
    }

    @Test public void compatibleWholeAndPointFixedCentersRemainAuthoritative() {
        SketchGeometry.Circle anchor = circle("anchor", 4, 6, 5);
        SketchGeometry.Arc alreadyConcentric = arc("driven", 4, 6, 9, 0, 90);
        SketchConstraint relation = SketchConstraint.concentric("con", "anchor", "driven");

        SketchConstraintSolver.Result whole = solver.solve(
                Arrays.asList(anchor, alreadyConcentric),
                Arrays.asList(SketchConstraint.fixed("fix", "driven"), relation));
        assertTrue(whole.solved());

        SketchConstraintSolver.Result point = solver.solve(
                Arrays.asList(anchor, alreadyConcentric),
                Arrays.asList(SketchConstraint.fixedPoint("fix", "driven", 0), relation));
        assertTrue(point.solved());
        assertCenter(alreadyConcentric.center,
                ((SketchGeometry.Arc) entity(point, "driven")).center);
    }

    @Test public void incompatibleWholeOrCenterFixedDrivenFailsClosedAsConflict() {
        SketchGeometry.Circle anchor = circle("anchor", 0, 0, 5);
        SketchGeometry.Arc driven = arc("driven", 20, 30, 9, 0, 90);
        SketchConstraint relation = SketchConstraint.concentric("con", "anchor", "driven");

        SketchConstraintSolver.Result whole = solver.solve(
                Arrays.asList(anchor, driven),
                Arrays.asList(SketchConstraint.fixed("fix", "driven"), relation));
        assertFalse(whole.solved());
        assertEquals(SketchConstraintSolver.Status.CONFLICT, whole.status);

        SketchConstraintSolver.Result point = solver.solve(
                Arrays.asList(anchor, driven),
                Arrays.asList(SketchConstraint.fixedPoint("fix", "driven", 0), relation));
        assertFalse(point.solved());
        assertEquals(SketchConstraintSolver.Status.CONFLICT, point.status);
    }

    @Test public void documentConflictIsAtomicWithoutGeometryMetadataRevisionOrHistoryMutation() {
        SketchDocument document = new SketchDocument();
        document.add(circle("anchor", 0, 0, 5));
        document.add(arc("driven", 20, 30, 9, 0, 90));
        document.addConstraintsAndSolve(Collections.singletonList(
                SketchConstraint.fixedPoint("fix", "driven", 0)), solver);
        long revision = document.revision();
        int count = document.constraintCount();
        boolean undo = document.canUndo();
        boolean redo = document.canRedo();
        SketchGeometry.Arc before = (SketchGeometry.Arc) document.entity("driven");

        try {
            document.addConstraintsAndSolve(Collections.singletonList(
                    SketchConstraint.concentric("con", "anchor", "driven")), solver);
            fail("Incompatible fixed-center Concentric must fail atomically");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage() != null && !expected.getMessage().isEmpty());
        }

        assertEquals(revision, document.revision());
        assertEquals(count, document.constraintCount());
        assertEquals(undo, document.canUndo());
        assertEquals(redo, document.canRedo());
        assertArcEquals(before, (SketchGeometry.Arc) document.entity("driven"));
    }

    @Test public void unsupportedLineCurvePairFailsClosedWithoutGeometryMutation() {
        SketchGeometry.Line line = new SketchGeometry.Line("line",
                new SketchGeometry.Point(0, 0), new SketchGeometry.Point(10, 0));
        SketchGeometry.Circle circle = circle("circle", 20, 30, 5);

        SketchConstraintSolver.Result result = solver.solve(
                Arrays.asList(line, circle), Collections.singletonList(
                        SketchConstraint.concentric("con", "line", "circle")));

        assertFalse(result.solved());
        assertEquals(SketchConstraintSolver.Status.UNSUPPORTED, result.status);
        assertEquals(0.0, ((SketchGeometry.Line) entity(result, "line")).a.xMm, 0.0);
        assertEquals(20.0, ((SketchGeometry.Circle) entity(result, "circle")).center.xMm, 0.0);
    }

    @Test public void missingReferenceAndSelfReferenceFailClosed() {
        SketchGeometry.Circle circle = circle("circle", 0, 0, 5);
        SketchConstraintSolver.Result missing = solver.solve(
                Collections.singletonList(circle), Collections.singletonList(
                        SketchConstraint.concentric("missing", "circle", "absent")));
        assertFalse(missing.solved());
        assertEquals(SketchConstraintSolver.Status.UNSUPPORTED, missing.status);

        try {
            SketchConstraint.concentric("self", "circle", "circle");
            fail("Self-referential Concentric must be rejected");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage() != null && !expected.getMessage().isEmpty());
        }
    }

    @Test public void deleteCascadeAndUndoRedoKeepStableModelAuthority() {
        SketchDocument document = new SketchDocument();
        document.add(circle("anchor", 0, 0, 5));
        document.add(arc("driven", 20, 30, 9, 10, 100));
        document.addConstraintsAndSolve(Collections.singletonList(
                SketchConstraint.concentric("con", "anchor", "driven")), solver);
        assertEquals(1, concentricCount(document));
        assertCenter(((SketchGeometry.Circle) document.entity("anchor")).center,
                ((SketchGeometry.Arc) document.entity("driven")).center);

        assertTrue(document.undo());
        assertEquals(0, concentricCount(document));
        assertTrue(document.redo());
        assertEquals(1, concentricCount(document));

        document.selectOnly("anchor");
        assertEquals(1, document.removeSelected());
        assertEquals(0, concentricCount(document));
        assertNotNull(document.entity("driven"));
    }

    private SketchConstraintSolver.Result solve(SketchEntity anchor, SketchEntity driven) {
        return solver.solve(Arrays.asList(anchor, driven), Collections.singletonList(
                SketchConstraint.concentric("con", anchor.id(), driven.id())));
    }

    private static int concentricCount(SketchDocument document) {
        int count = 0;
        for (SketchConstraint c : document.constraints()) {
            if (c.kind == SketchConstraint.Kind.CONCENTRIC) count++;
        }
        return count;
    }

    private static SketchGeometry.Circle circle(String id, double x, double y, double radius) {
        return new SketchGeometry.Circle(id, new SketchGeometry.Point(x, y), radius);
    }

    private static SketchGeometry.Arc arc(String id, double x, double y, double radius,
                                          double start, double sweep) {
        return new SketchGeometry.Arc(id, new SketchGeometry.Point(x, y), radius, start, sweep);
    }

    private static SketchEntity entity(SketchConstraintSolver.Result result, String id) {
        for (SketchEntity entity : result.entities()) if (id.equals(entity.id())) return entity;
        throw new AssertionError("Missing solved entity " + id);
    }

    private static void assertCenter(SketchGeometry.Point expected, SketchGeometry.Point actual) {
        assertEquals(expected.xMm, actual.xMm, EPS);
        assertEquals(expected.yMm, actual.yMm, EPS);
    }

    private static void assertArcEquals(SketchGeometry.Arc expected, SketchGeometry.Arc actual) {
        assertCenter(expected.center, actual.center);
        assertEquals(expected.radiusMm, actual.radiusMm, 0.0);
        assertEquals(expected.startDeg, actual.startDeg, 0.0);
        assertEquals(expected.sweepDeg, actual.sweepDeg, 0.0);
    }
}

package ir.chobyar.sketch.core;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** K3.10 red/green fence for model-owned driving dimensions and remaining DOF. */
public final class K310DrivingDimensionSolverTest {
    private static final double EPS = 1.0e-6;
    private final SketchConstraintSolver solver = new DeterministicSketchConstraintSolver();

    @Test public void distanceDrivesLineLengthWithoutConsumingPositionOrOrientationDof() {
        SketchGeometry.Line horizontal = new SketchGeometry.Line("line-a",
                new SketchGeometry.Point(10, 20), new SketchGeometry.Point(30, 20));
        SketchConstraint length = SketchConstraint.distance("len-a", "line-a", 50.0);
        SketchConstraintSolver.Result a = solver.solve(
                Collections.singletonList(horizontal), Collections.singletonList(length));
        assertTrue(a.message, a.solved());
        SketchGeometry.Line solvedA = (SketchGeometry.Line) a.entities().get(0);
        assertEquals(50.0, solvedA.lengthMm(), EPS);
        assertEquals(20.0, (solvedA.a.xMm + solvedA.b.xMm) * 0.5, EPS);
        assertEquals(20.0, solvedA.a.yMm, EPS);
        assertEquals(20.0, solvedA.b.yMm, EPS);

        SketchGeometry.Line diagonalElsewhere = new SketchGeometry.Line("line-b",
                new SketchGeometry.Point(-30, 40), new SketchGeometry.Point(-20, 50));
        SketchConstraintSolver.Result b = solver.solve(
                Collections.singletonList(diagonalElsewhere),
                Collections.singletonList(SketchConstraint.distance("len-b", "line-b", 50.0)));
        assertTrue(b.message, b.solved());
        SketchGeometry.Line solvedB = (SketchGeometry.Line) b.entities().get(0);
        assertEquals(50.0, solvedB.lengthMm(), EPS);
        assertEquals(-25.0, (solvedB.a.xMm + solvedB.b.xMm) * 0.5, EPS);
        assertEquals(45.0, (solvedB.a.yMm + solvedB.b.yMm) * 0.5, EPS);
        assertTrue("Length must not force a global axis orientation",
                Math.abs(solvedB.a.yMm - solvedB.b.yMm) > 1.0);
    }

    @Test public void distanceUsesPointFixedEndpointAsAnchorAndKeepsFreeEndpointEditable() {
        SketchDocument doc = new SketchDocument();
        doc.add(new SketchGeometry.Line("line",
                new SketchGeometry.Point(10, 10), new SketchGeometry.Point(30, 10)));
        doc.selectOnly("line");
        doc.addConstraintsAndSolve(Arrays.asList(
                SketchConstraint.fixedPoint("pin", "line", 0),
                SketchConstraint.distance("len", "line", 50.0)), solver);
        SketchGeometry.Line solved = (SketchGeometry.Line) doc.entity("line");
        assertPoint(10.0, 10.0, solved.a);
        assertEquals(50.0, solved.lengthMm(), EPS);

        SketchGeometry.Point freeBefore = solved.b;
        doc.translateSelectionAndSolve(0.0, 25.0, solver);
        SketchGeometry.Line edited = (SketchGeometry.Line) doc.entity("line");
        assertPoint(10.0, 10.0, edited.a);
        assertEquals(50.0, edited.lengthMm(), EPS);
        assertTrue("The non-fixed endpoint must retain angular DOF",
                Math.abs(edited.b.yMm - freeBefore.yMm) > 1.0e-3);
    }

    @Test public void radiusDrivesCircleAndArcWithoutMovingFixedCenter() {
        SketchDocument circleDoc = new SketchDocument();
        circleDoc.add(new SketchGeometry.Circle("circle", new SketchGeometry.Point(15, 25), 8));
        circleDoc.addConstraintsAndSolve(Arrays.asList(
                SketchConstraint.fixedPoint("circle-pin", "circle", 0),
                SketchConstraint.radius("circle-r", "circle", 22.5)), solver);
        SketchGeometry.Circle circle = (SketchGeometry.Circle) circleDoc.entity("circle");
        assertPoint(15, 25, circle.center);
        assertEquals(22.5, circle.radiusMm, EPS);

        SketchDocument arcDoc = new SketchDocument();
        arcDoc.add(new SketchGeometry.Arc("arc", new SketchGeometry.Point(35, 45), 10, 20, 115));
        arcDoc.addConstraintsAndSolve(Arrays.asList(
                SketchConstraint.fixedPoint("arc-pin", "arc", 0),
                SketchConstraint.radius("arc-r", "arc", 30.0)), solver);
        SketchGeometry.Arc arc = (SketchGeometry.Arc) arcDoc.entity("arc");
        assertPoint(35, 45, arc.center);
        assertEquals(30.0, arc.radiusMm, EPS);
        assertEquals(20.0, arc.startDeg, EPS);
        assertEquals(115.0, arc.sweepDeg, EPS);
    }

    @Test public void angleDrivesSecondLineAroundItsFixedEndpointAndPreservesLengths() {
        SketchGeometry.Line reference = new SketchGeometry.Line("ref",
                new SketchGeometry.Point(0, 0), new SketchGeometry.Point(40, 0));
        SketchGeometry.Line moving = new SketchGeometry.Line("moving",
                new SketchGeometry.Point(0, 0), new SketchGeometry.Point(20, 20));
        SketchConstraintSolver.Result result = solver.solve(Arrays.asList(reference, moving), Arrays.asList(
                SketchConstraint.fixed("ref-fixed", "ref"),
                SketchConstraint.fixedPoint("moving-pin", "moving", 0),
                SketchConstraint.angle("angle", "ref", "moving", 60.0)));
        assertTrue(result.message, result.solved());
        SketchGeometry.Line solvedRef = line(result, "ref");
        SketchGeometry.Line solvedMoving = line(result, "moving");
        assertPoint(0, 0, solvedRef.a);
        assertPoint(40, 0, solvedRef.b);
        assertPoint(0, 0, solvedMoving.a);
        assertEquals(Math.hypot(20, 20), solvedMoving.lengthMm(), EPS);
        assertEquals(60.0, undirectedAngleDeg(solvedRef, solvedMoving), 1.0e-5);
    }

    @Test public void incompatibleDrivingDimensionsFailClosedAsConflict() {
        SketchDocument doc = new SketchDocument();
        doc.add(new SketchGeometry.Line("line",
                new SketchGeometry.Point(0, 0), new SketchGeometry.Point(20, 0)));
        long revision = doc.revision();
        boolean canUndoBefore = doc.canUndo();
        boolean canRedoBefore = doc.canRedo();
        SketchGeometry.Line before = (SketchGeometry.Line) doc.entity("line");
        try {
            doc.addConstraintsAndSolve(Arrays.asList(
                    SketchConstraint.fixedPoint("pin-a", "line", 0),
                    SketchConstraint.fixedPoint("pin-b", "line", 1),
                    SketchConstraint.distance("len", "line", 35.0)), solver);
            throw new AssertionError("Expected over-constrained length to fail closed");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("CONFLICT"));
        }
        SketchGeometry.Line after = (SketchGeometry.Line) doc.entity("line");
        assertPoint(before.a.xMm, before.a.yMm, after.a);
        assertPoint(before.b.xMm, before.b.yMm, after.b);
        assertEquals(revision, doc.revision());
        assertEquals(0, doc.constraintCount());
        assertEquals(canUndoBefore, doc.canUndo());
        assertEquals(canRedoBefore, doc.canRedo());
    }

    @Test public void dimensionKindsRejectUnsupportedGeometryRatherThanMutatingIt() {
        SketchConstraintSolver.Result distanceCircle = solver.solve(
                Collections.singletonList(new SketchGeometry.Circle("c", new SketchGeometry.Point(0, 0), 10)),
                Collections.singletonList(SketchConstraint.distance("bad-distance", "c", 20)));
        assertEquals(SketchConstraintSolver.Status.UNSUPPORTED, distanceCircle.status);

        SketchConstraintSolver.Result radiusLine = solver.solve(
                Collections.singletonList(new SketchGeometry.Line("l",
                        new SketchGeometry.Point(0, 0), new SketchGeometry.Point(10, 0))),
                Collections.singletonList(SketchConstraint.radius("bad-radius", "l", 20)));
        assertEquals(SketchConstraintSolver.Status.UNSUPPORTED, radiusLine.status);

        SketchConstraintSolver.Result angleCircleLine = solver.solve(Arrays.asList(
                        new SketchGeometry.Circle("c2", new SketchGeometry.Point(0, 0), 10),
                        new SketchGeometry.Line("l2", new SketchGeometry.Point(0, 0), new SketchGeometry.Point(10, 0))),
                Collections.singletonList(SketchConstraint.angle("bad-angle", "c2", "l2", 45)));
        assertEquals(SketchConstraintSolver.Status.UNSUPPORTED, angleCircleLine.status);
    }

    private static SketchGeometry.Line line(SketchConstraintSolver.Result result, String id) {
        for (SketchEntity entity : result.entities()) {
            if (id.equals(entity.id())) return (SketchGeometry.Line) entity;
        }
        throw new AssertionError("Line not found: " + id);
    }

    private static double undirectedAngleDeg(SketchGeometry.Line a, SketchGeometry.Line b) {
        double ax = a.b.xMm - a.a.xMm, ay = a.b.yMm - a.a.yMm;
        double bx = b.b.xMm - b.a.xMm, by = b.b.yMm - b.a.yMm;
        double dot = ax * bx + ay * by;
        double cross = ax * by - ay * bx;
        double angle = Math.toDegrees(Math.atan2(Math.abs(cross), dot));
        if (angle < 0) angle += 180.0;
        if (angle > 180.0) angle = 360.0 - angle;
        return angle;
    }

    private static void assertPoint(double x, double y, SketchGeometry.Point actual) {
        assertEquals(x, actual.xMm, EPS);
        assertEquals(y, actual.yMm, EPS);
    }
}
package ir.chobyar.sketch.core;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** K3.11 RED/GREEN fence for model-owned Equal length/radius semantics. */
public final class K311EqualConstraintSolverTest {
    private static final double EPS = 1.0e-6;
    private final SketchConstraintSolver solver = new DeterministicSketchConstraintSolver();

    @Test public void equalLinesDriveSecondLengthWithoutChangingReferenceOrDrivenOrientation() {
        SketchGeometry.Line reference = new SketchGeometry.Line("ref",
                new SketchGeometry.Point(0, 0), new SketchGeometry.Point(40, 0));
        SketchGeometry.Line driven = new SketchGeometry.Line("driven",
                new SketchGeometry.Point(10, 20), new SketchGeometry.Point(20, 30));
        double drivenCx = (driven.a.xMm + driven.b.xMm) * 0.5;
        double drivenCy = (driven.a.yMm + driven.b.yMm) * 0.5;
        double drivenAngle = angleDeg(driven);

        SketchConstraintSolver.Result result = solver.solve(Arrays.asList(reference, driven),
                Collections.singletonList(SketchConstraint.equal("eq", "ref", "driven")));
        assertTrue(result.message, result.solved());
        SketchGeometry.Line solvedRef = line(result, "ref");
        SketchGeometry.Line solvedDriven = line(result, "driven");
        assertLine(reference, solvedRef);
        assertEquals(reference.lengthMm(), solvedDriven.lengthMm(), EPS);
        assertEquals(drivenCx, (solvedDriven.a.xMm + solvedDriven.b.xMm) * 0.5, EPS);
        assertEquals(drivenCy, (solvedDriven.a.yMm + solvedDriven.b.yMm) * 0.5, EPS);
        assertEquals(drivenAngle, angleDeg(solvedDriven), 1.0e-6);
    }

    @Test public void equalLineHonorsDrivenPointFixedAnchorAndLeavesAngularDof() {
        SketchDocument doc = new SketchDocument();
        doc.add(new SketchGeometry.Line("ref",
                new SketchGeometry.Point(0, 0), new SketchGeometry.Point(50, 0)));
        doc.add(new SketchGeometry.Line("driven",
                new SketchGeometry.Point(10, 10), new SketchGeometry.Point(30, 10)));
        doc.addConstraintsAndSolve(Arrays.asList(
                SketchConstraint.fixedPoint("pin", "driven", 0),
                SketchConstraint.equal("eq", "ref", "driven")), solver);
        SketchGeometry.Line solved = (SketchGeometry.Line) doc.entity("driven");
        assertPoint(10, 10, solved.a);
        assertEquals(50.0, solved.lengthMm(), EPS);

        doc.selectOnly("driven");
        doc.translateSelectionAndSolve(0, 20, solver);
        SketchGeometry.Line edited = (SketchGeometry.Line) doc.entity("driven");
        assertPoint(10, 10, edited.a);
        assertEquals(50.0, edited.lengthMm(), EPS);
        assertTrue("Equal must not consume the driven line's remaining angular DOF",
                Math.abs(edited.b.yMm - solved.b.yMm) > 1.0e-3);
    }

    @Test public void equalCurvesDriveRadiusAcrossCircleAndArcWithoutMovingCentersOrArcAngles() {
        SketchGeometry.Circle reference = new SketchGeometry.Circle("circle",
                new SketchGeometry.Point(15, 25), 24);
        SketchGeometry.Arc driven = new SketchGeometry.Arc("arc",
                new SketchGeometry.Point(60, 45), 10, 25, 120);
        SketchConstraintSolver.Result result = solver.solve(Arrays.asList(reference, driven),
                Collections.singletonList(SketchConstraint.equal("eq", "circle", "arc")));
        assertTrue(result.message, result.solved());
        SketchGeometry.Circle solvedCircle = (SketchGeometry.Circle) entity(result, "circle");
        SketchGeometry.Arc solvedArc = (SketchGeometry.Arc) entity(result, "arc");
        assertPoint(15, 25, solvedCircle.center);
        assertEquals(24.0, solvedCircle.radiusMm, EPS);
        assertPoint(60, 45, solvedArc.center);
        assertEquals(24.0, solvedArc.radiusMm, EPS);
        assertEquals(25.0, solvedArc.startDeg, EPS);
        assertEquals(120.0, solvedArc.sweepDeg, EPS);
    }

    @Test public void incompatibleDrivingLengthAndEqualFailClosedAsConflict() {
        SketchDocument doc = new SketchDocument();
        doc.add(new SketchGeometry.Line("ref",
                new SketchGeometry.Point(0, 0), new SketchGeometry.Point(40, 0)));
        doc.add(new SketchGeometry.Line("driven",
                new SketchGeometry.Point(0, 20), new SketchGeometry.Point(20, 20)));
        long revision = doc.revision();
        int constraintsBefore = doc.constraintCount();
        try {
            doc.addConstraintsAndSolve(Arrays.asList(
                    SketchConstraint.equal("eq", "ref", "driven"),
                    SketchConstraint.distance("len", "driven", 30)), solver);
            throw new AssertionError("Expected Equal + incompatible driving length to conflict");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("CONFLICT"));
        }
        assertEquals(revision, doc.revision());
        assertEquals(constraintsBefore, doc.constraintCount());
        assertEquals(40.0, ((SketchGeometry.Line) doc.entity("ref")).lengthMm(), EPS);
        assertEquals(20.0, ((SketchGeometry.Line) doc.entity("driven")).lengthMm(), EPS);
    }

    @Test public void equalRejectsMixedFamiliesAndSameEntityRatherThanMutating() {
        SketchGeometry.Line line = new SketchGeometry.Line("line",
                new SketchGeometry.Point(0, 0), new SketchGeometry.Point(10, 0));
        SketchGeometry.Circle circle = new SketchGeometry.Circle("circle",
                new SketchGeometry.Point(30, 30), 8);
        SketchConstraintSolver.Result mixed = solver.solve(Arrays.asList(line, circle),
                Collections.singletonList(SketchConstraint.equal("bad-mixed", "line", "circle")));
        assertEquals(SketchConstraintSolver.Status.UNSUPPORTED, mixed.status);

        SketchConstraintSolver.Result same = solver.solve(Collections.singletonList(line),
                Collections.singletonList(SketchConstraint.equal("bad-same", "line", "line")));
        assertEquals(SketchConstraintSolver.Status.UNSUPPORTED, same.status);
    }

    private static SketchEntity entity(SketchConstraintSolver.Result result, String id) {
        for (SketchEntity e : result.entities()) if (id.equals(e.id())) return e;
        throw new AssertionError("Entity not found: " + id);
    }

    private static SketchGeometry.Line line(SketchConstraintSolver.Result result, String id) {
        return (SketchGeometry.Line) entity(result, id);
    }

    private static void assertPoint(double x, double y, SketchGeometry.Point actual) {
        assertEquals(x, actual.xMm, EPS);
        assertEquals(y, actual.yMm, EPS);
    }

    private static void assertLine(SketchGeometry.Line expected, SketchGeometry.Line actual) {
        assertPoint(expected.a.xMm, expected.a.yMm, actual.a);
        assertPoint(expected.b.xMm, expected.b.yMm, actual.b);
    }

    private static double angleDeg(SketchGeometry.Line line) {
        return Math.toDegrees(Math.atan2(line.b.yMm - line.a.yMm,
                line.b.xMm - line.a.xMm));
    }
}

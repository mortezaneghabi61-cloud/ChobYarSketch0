package ir.chobyar.sketch.core;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** JVM contract for K3.13 model-owned line-to-curve Tangent authority. */
public final class K313TangentConstraintSolverTest {
    private static final double EPS = 1.0e-6;
    private final DeterministicSketchConstraintSolver solver = new DeterministicSketchConstraintSolver();

    @Test public void lineCircleTangentUsesNearbyBranchAndPreservesCurveGeometry() {
        SketchGeometry.Line line = line("line", 10, 0, 5, 2);
        SketchGeometry.Circle circle = circle("circle", 0, 0, 5);

        SketchConstraintSolver.Result result = solver.solve(
                Arrays.asList(line, circle),
                Collections.singletonList(SketchConstraint.tangent("t", "line", "circle")));

        assertTrue(result.solved());
        SketchGeometry.Line solvedLine = (SketchGeometry.Line) entity(result, "line");
        SketchGeometry.Circle solvedCircle = (SketchGeometry.Circle) entity(result, "circle");
        assertEquals(10.0, solvedLine.a.xMm, EPS);
        assertEquals(0.0, solvedLine.a.yMm, EPS);
        assertEquals(circle.center.xMm, solvedCircle.center.xMm, 0.0);
        assertEquals(circle.center.yMm, solvedCircle.center.yMm, 0.0);
        assertEquals(circle.radiusMm, solvedCircle.radiusMm, 0.0);
        assertTangent(solvedLine, solvedCircle.center, solvedCircle.radiusMm);
    }

    @Test public void lineArcTangentSupportsReversedConstraintOrderAndPreservesArcParameters() {
        SketchGeometry.Line line = line("line", 10, 0, 5, 2);
        SketchGeometry.Arc arc = arc("arc", 0, 0, 5, 30, 120);

        SketchConstraintSolver.Result result = solver.solve(
                Arrays.asList(line, arc),
                Collections.singletonList(SketchConstraint.tangent("t", "arc", "line")));

        assertTrue(result.solved());
        SketchGeometry.Line solvedLine = (SketchGeometry.Line) entity(result, "line");
        SketchGeometry.Arc solvedArc = (SketchGeometry.Arc) entity(result, "arc");
        assertEquals(arc.center.xMm, solvedArc.center.xMm, 0.0);
        assertEquals(arc.center.yMm, solvedArc.center.yMm, 0.0);
        assertEquals(arc.radiusMm, solvedArc.radiusMm, 0.0);
        assertEquals(arc.startDeg, solvedArc.startDeg, 0.0);
        assertEquals(arc.sweepDeg, solvedArc.sweepDeg, 0.0);
        assertTangent(solvedLine, solvedArc.center, solvedArc.radiusMm);
    }

    @Test public void pointFixedExternalEndpointIsPreservedWhileOtherEndpointMovesToTangent() {
        SketchGeometry.Line line = line("line", 10, 0, 6, 3);
        SketchGeometry.Circle circle = circle("circle", 0, 0, 5);
        SketchConstraint fixed = SketchConstraint.fixedPoint("fix", "line", 0);
        SketchConstraint tangent = SketchConstraint.tangent("t", "line", "circle");

        SketchConstraintSolver.Result result = solver.solve(
                Arrays.asList(line, circle), Arrays.asList(fixed, tangent));

        assertTrue(result.solved());
        SketchGeometry.Line solved = (SketchGeometry.Line) entity(result, "line");
        assertEquals(10.0, solved.a.xMm, 0.0);
        assertEquals(0.0, solved.a.yMm, 0.0);
        assertTangent(solved, circle.center, circle.radiusMm);
    }

    @Test public void pointFixedContactEndpointRotatesFreeEndpointAndPreservesLength() {
        SketchGeometry.Line line = line("line", 5, 0, 10, 4);
        SketchGeometry.Circle circle = circle("circle", 0, 0, 5);
        double beforeLength = line.lengthMm();

        SketchConstraintSolver.Result result = solver.solve(
                Arrays.asList(line, circle),
                Arrays.asList(
                        SketchConstraint.fixedPoint("fix", "line", 0),
                        SketchConstraint.tangent("t", "line", "circle")));

        assertTrue(result.solved());
        SketchGeometry.Line solved = (SketchGeometry.Line) entity(result, "line");
        assertEquals(5.0, solved.a.xMm, 0.0);
        assertEquals(0.0, solved.a.yMm, 0.0);
        assertEquals(beforeLength, solved.lengthMm(), EPS);
        assertTangent(solved, circle.center, circle.radiusMm);
    }

    @Test public void wholeFixedAndBothPointFixedTangencyFailClosedAsConflict() {
        SketchGeometry.Line line = line("line", 10, 10, 20, 10);
        SketchGeometry.Circle circle = circle("circle", 0, 0, 5);

        SketchConstraintSolver.Result wholeFixed = solver.solve(
                Arrays.asList(line, circle),
                Arrays.asList(
                        SketchConstraint.fixed("fix", "line"),
                        SketchConstraint.tangent("t", "line", "circle")));
        assertFalse(wholeFixed.solved());
        assertEquals(SketchConstraintSolver.Status.CONFLICT, wholeFixed.status);

        SketchConstraintSolver.Result bothPoints = solver.solve(
                Arrays.asList(line, circle),
                Arrays.asList(
                        SketchConstraint.fixedPoint("a", "line", 0),
                        SketchConstraint.fixedPoint("b", "line", 1),
                        SketchConstraint.tangent("t", "line", "circle")));
        assertFalse(bothPoints.solved());
        assertEquals(SketchConstraintSolver.Status.CONFLICT, bothPoints.status);
    }

    @Test public void documentConflictIsAtomicAndDoesNotCreateHistoryOrConstraintMetadata() {
        SketchDocument document = new SketchDocument();
        SketchGeometry.Line line = line("line", 10, 10, 20, 10);
        SketchGeometry.Circle circle = circle("circle", 0, 0, 5);
        document.add(line);
        document.add(circle);
        document.addConstraintsAndSolve(
                Collections.singletonList(SketchConstraint.fixed("fix", "line")), solver);

        long revisionBefore = document.revision();
        int constraintCountBefore = document.constraintCount();
        SketchGeometry.Line geometryBefore = (SketchGeometry.Line) document.entity("line");
        boolean undoBefore = document.canUndo();
        boolean redoBefore = document.canRedo();

        try {
            document.addConstraintsAndSolve(
                    Collections.singletonList(SketchConstraint.tangent("t", "line", "circle")), solver);
            fail("Impossible fixed Tangent must fail atomically");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage() != null && !expected.getMessage().isEmpty());
        }

        assertEquals(revisionBefore, document.revision());
        assertEquals(constraintCountBefore, document.constraintCount());
        assertEquals(undoBefore, document.canUndo());
        assertEquals(redoBefore, document.canRedo());
        SketchGeometry.Line geometryAfter = (SketchGeometry.Line) document.entity("line");
        assertLineEquals(geometryBefore, geometryAfter);
    }

    @Test public void curveCurveTangentRemainsExplicitlyUnsupportedRatherThanDecorative() {
        SketchGeometry.Circle circle = circle("circle", 0, 0, 5);
        SketchGeometry.Arc arc = arc("arc", 12, 0, 3, 0, 90);

        SketchConstraintSolver.Result result = solver.solve(
                Arrays.asList(circle, arc),
                Collections.singletonList(SketchConstraint.tangent("t", "circle", "arc")));

        assertFalse(result.solved());
        assertEquals(SketchConstraintSolver.Status.UNSUPPORTED, result.status);
        assertTrue(result.message.contains("one line and one circle/arc"));
    }

    private static SketchGeometry.Line line(String id, double x1, double y1, double x2, double y2) {
        return new SketchGeometry.Line(id,
                new SketchGeometry.Point(x1, y1),
                new SketchGeometry.Point(x2, y2));
    }

    private static SketchGeometry.Circle circle(String id, double cx, double cy, double r) {
        return new SketchGeometry.Circle(id, new SketchGeometry.Point(cx, cy), r);
    }

    private static SketchGeometry.Arc arc(String id, double cx, double cy, double r,
                                          double startDeg, double sweepDeg) {
        return new SketchGeometry.Arc(id, new SketchGeometry.Point(cx, cy), r, startDeg, sweepDeg);
    }

    private static SketchEntity entity(SketchConstraintSolver.Result result, String id) {
        for (SketchEntity entity : result.entities()) if (id.equals(entity.id())) return entity;
        throw new AssertionError("Missing solved entity " + id);
    }

    private static void assertTangent(SketchGeometry.Line line,
                                      SketchGeometry.Point center, double radius) {
        double dx = line.b.xMm - line.a.xMm;
        double dy = line.b.yMm - line.a.yMm;
        double length = Math.hypot(dx, dy);
        double cross = dx * (center.yMm - line.a.yMm)
                - dy * (center.xMm - line.a.xMm);
        double distance = Math.abs(cross) / length;
        assertEquals(radius, distance, EPS);
    }

    private static void assertLineEquals(SketchGeometry.Line expected, SketchGeometry.Line actual) {
        assertEquals(expected.a.xMm, actual.a.xMm, 0.0);
        assertEquals(expected.a.yMm, actual.a.yMm, 0.0);
        assertEquals(expected.b.xMm, actual.b.xMm, 0.0);
        assertEquals(expected.b.yMm, actual.b.yMm, 0.0);
    }
}
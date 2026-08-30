package ir.chobyar.sketch.core;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DeterministicSketchConstraintSolverTest {
    private static final double EPS = 1.0e-6;
    private final DeterministicSketchConstraintSolver solver = new DeterministicSketchConstraintSolver();

    private static SketchGeometry.Line line(String id, double x1, double y1, double x2, double y2) {
        return new SketchGeometry.Line(id,
                new SketchGeometry.Point(x1, y1),
                new SketchGeometry.Point(x2, y2));
    }

    @Test public void horizontalAndVerticalPreserveLineLengthAroundCenter() {
        SketchGeometry.Line a = line("a", 0, 0, 10, 2);
        SketchGeometry.Line b = line("b", 20, 0, 22, 10);
        double aLength = a.lengthMm();
        double bLength = b.lengthMm();

        SketchConstraintSolver.Result result = solver.solve(
                Arrays.asList(a, b),
                Arrays.asList(SketchConstraint.horizontal("h", "a"),
                        SketchConstraint.vertical("v", "b")));

        assertTrue(result.solved());
        SketchGeometry.Line sa = solvedLine(result, "a");
        SketchGeometry.Line sb = solvedLine(result, "b");
        assertEquals(sa.a.yMm, sa.b.yMm, EPS);
        assertEquals(sb.a.xMm, sb.b.xMm, EPS);
        assertEquals(aLength, sa.lengthMm(), EPS);
        assertEquals(bLength, sb.lengthMm(), EPS);
        assertEquals(5.0, (sa.a.xMm + sa.b.xMm) * 0.5, EPS);
        assertEquals(1.0, (sa.a.yMm + sa.b.yMm) * 0.5, EPS);
    }

    @Test public void parallelAndPerpendicularRotateSecondaryAroundItsCenter() {
        SketchGeometry.Line reference = line("a", 0, 0, 10, 0);
        SketchGeometry.Line parallelCandidate = line("b", 20, 0, 27, 4);
        SketchGeometry.Line perpendicularCandidate = line("c", 40, 0, 47, 4);

        SketchConstraintSolver.Result parallel = solver.solve(
                Arrays.asList(reference, parallelCandidate),
                Collections.singletonList(SketchConstraint.parallel("p", "a", "b")));
        assertTrue(parallel.solved());
        SketchGeometry.Line b = solvedLine(parallel, "b");
        assertEquals(b.a.yMm, b.b.yMm, EPS);
        assertEquals(23.5, (b.a.xMm + b.b.xMm) * 0.5, EPS);
        assertEquals(2.0, (b.a.yMm + b.b.yMm) * 0.5, EPS);

        SketchConstraintSolver.Result perpendicular = solver.solve(
                Arrays.asList(reference, perpendicularCandidate),
                Collections.singletonList(SketchConstraint.perpendicular("q", "a", "c")));
        assertTrue(perpendicular.solved());
        SketchGeometry.Line c = solvedLine(perpendicular, "c");
        assertEquals(c.a.xMm, c.b.xMm, EPS);
        assertEquals(43.5, (c.a.xMm + c.b.xMm) * 0.5, EPS);
        assertEquals(2.0, (c.a.yMm + c.b.yMm) * 0.5, EPS);
    }

    @Test public void coincidenceAndPointOnLinePropagateGeometryByStableId() {
        SketchGeometry.Line host = line("host", 0, 0, 20, 0);
        SketchGeometry.Line child = line("child", 18, 3, 18, 12);
        SketchConstraintSolver.Result result = solver.solve(
                Arrays.asList(host, child),
                Arrays.asList(
                        SketchConstraint.pointOnEntity("on", "child", 0, "host"),
                        SketchConstraint.vertical("v", "child")));
        assertTrue(result.solved());
        SketchGeometry.Line solved = solvedLine(result, "child");
        assertEquals(18.0, solved.a.xMm, EPS);
        assertEquals(0.0, solved.a.yMm, EPS);
        assertEquals(solved.a.xMm, solved.b.xMm, EPS);

        SketchGeometry.Line other = line("other", 30, 4, 40, 4);
        SketchConstraintSolver.Result coincident = solver.solve(
                Arrays.asList(host, other),
                Collections.singletonList(SketchConstraint.coincident("c", "other", 0, "host", 1)));
        assertTrue(coincident.solved());
        SketchGeometry.Line moved = solvedLine(coincident, "other");
        assertEquals(20.0, moved.a.xMm, EPS);
        assertEquals(0.0, moved.a.yMm, EPS);
    }

    @Test public void contradictoryAxisLocksFailClosedAsConflict() {
        SketchGeometry.Line a = line("a", 0, 0, 10, 3);
        SketchConstraintSolver.Result result = solver.solve(
                Collections.singletonList(a),
                Arrays.asList(SketchConstraint.horizontal("h", "a"),
                        SketchConstraint.vertical("v", "a")));
        assertFalse(result.solved());
        assertEquals(SketchConstraintSolver.Status.CONFLICT, result.status);
        assertTrue(result.maxResidual > 0.0);
    }

    @Test public void unsupportedConstraintNeverPretendsToBeSolved() {
        SketchGeometry.Line a = line("a", 0, 0, 10, 0);
        SketchConstraintSolver.Result result = solver.solve(
                Collections.singletonList(a),
                Collections.singletonList(SketchConstraint.distance("d", "a", 10.0)));
        assertEquals(SketchConstraintSolver.Status.UNSUPPORTED, result.status);
        assertFalse(result.solved());
        assertTrue(result.message.contains("not yet supported"));
    }

    private static SketchGeometry.Line solvedLine(SketchConstraintSolver.Result result, String id) {
        List<SketchEntity> entities = result.entities();
        for (SketchEntity entity : entities) {
            if (id.equals(entity.id())) return (SketchGeometry.Line) entity;
        }
        throw new AssertionError("missing solved entity " + id);
    }
}

package ir.chobyar.sketch.core;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** K3.12 red/green fence for model-owned absolute Single-Line Angle. */
public final class K312SingleLineAngleSolverTest {
    private static final double EPS = 1.0e-6;
    private final SketchConstraintSolver solver = new DeterministicSketchConstraintSolver();

    @Test public void lineAnglePreservesLengthAndCenterWhenNoPointIsFixed() {
        SketchGeometry.Line source = line("line", 10, 20, 40, 60);
        SketchConstraintSolver.Result result = solver.solve(
                Collections.singletonList(source),
                Collections.singletonList(SketchConstraint.lineAngle("angle", "line", 30.0)));

        assertTrue(result.message, result.solved());
        SketchGeometry.Line solved = solvedLine(result, "line");
        assertEquals(source.lengthMm(), solved.lengthMm(), EPS);
        assertEquals((source.a.xMm + source.b.xMm) * 0.5,
                (solved.a.xMm + solved.b.xMm) * 0.5, EPS);
        assertEquals((source.a.yMm + source.b.yMm) * 0.5,
                (solved.a.yMm + solved.b.yMm) * 0.5, EPS);
        assertEquals(30.0, displayAngle(solved), EPS);
    }

    @Test public void lineAngleRotatesAroundPointFixedEndpointAndLeavesTranslationDofFree() {
        SketchDocument doc = new SketchDocument();
        doc.add(line("line", 10, 10, 40, 25));
        doc.selectOnly("line");
        doc.addConstraintsAndSolve(Arrays.asList(
                SketchConstraint.fixedPoint("pin", "line", 0),
                SketchConstraint.lineAngle("angle", "line", 75.0)), solver);

        SketchGeometry.Line solved = (SketchGeometry.Line) doc.entity("line");
        assertPoint(10, 10, solved.a);
        assertEquals(75.0, displayAngle(solved), EPS);
        double length = solved.lengthMm();

        doc.translateSelectionAndSolve(20.0, 30.0, solver);
        SketchGeometry.Line translated = (SketchGeometry.Line) doc.entity("line");
        assertPoint(10, 10, translated.a);
        assertEquals(length, translated.lengthMm(), EPS);
        assertEquals(75.0, displayAngle(translated), EPS);
    }

    @Test public void lineAngleRotatesAroundSecondPointFixedEndpoint() {
        SketchDocument doc = new SketchDocument();
        doc.add(line("line", 10, 10, 40, 25));
        doc.addConstraintsAndSolve(Arrays.asList(
                SketchConstraint.fixedPoint("pin", "line", 1),
                SketchConstraint.lineAngle("angle", "line", 110.0)), solver);

        SketchGeometry.Line solved = (SketchGeometry.Line) doc.entity("line");
        assertPoint(40, 25, solved.b);
        assertEquals(110.0, displayAngle(solved), EPS);
    }

    @Test public void incompatibleHorizontalAndWholeFixedConstraintsFailClosed() {
        SketchGeometry.Line horizontal = line("line", 0, 0, 30, 0);
        SketchConstraintSolver.Result axisConflict = solver.solve(
                Collections.singletonList(horizontal), Arrays.asList(
                        SketchConstraint.horizontal("h", "line"),
                        SketchConstraint.lineAngle("angle", "line", 35.0)));
        assertFalse(axisConflict.solved());
        assertEquals(SketchConstraintSolver.Status.CONFLICT, axisConflict.status);

        SketchConstraintSolver.Result fixedConflict = solver.solve(
                Collections.singletonList(horizontal), Arrays.asList(
                        SketchConstraint.fixed("fixed", "line"),
                        SketchConstraint.lineAngle("angle", "line", 35.0)));
        assertFalse(fixedConflict.solved());
        assertEquals(SketchConstraintSolver.Status.CONFLICT, fixedConflict.status);

        SketchConstraintSolver.Result bothPointsConflict = solver.solve(
                Collections.singletonList(horizontal), Arrays.asList(
                        SketchConstraint.fixedPoint("a", "line", 0),
                        SketchConstraint.fixedPoint("b", "line", 1),
                        SketchConstraint.lineAngle("angle", "line", 35.0)));
        assertFalse(bothPointsConflict.solved());
        assertEquals(SketchConstraintSolver.Status.CONFLICT, bothPointsConflict.status);
    }

    @Test public void editingLineAngleReplacesOneDrivingSlotAndUndoRedoIsAtomic() {
        SketchDocument doc = new SketchDocument();
        doc.add(line("line", 0, 0, 25, 10));
        doc.setDrivingDimensionAndSolve(
                SketchConstraint.lineAngle("first", "line", 25.0), solver);
        long afterFirst = doc.revision();

        doc.setDrivingDimensionAndSolve(
                SketchConstraint.lineAngle("replacement", "line", 80.0), solver);
        assertEquals(1, doc.constraintCount());
        assertEquals("first", doc.constraints().get(0).id);
        assertEquals(80.0, displayAngle((SketchGeometry.Line) doc.entity("line")), EPS);

        assertTrue(doc.undo());
        assertEquals(afterFirst, doc.revision());
        assertEquals(25.0, displayAngle((SketchGeometry.Line) doc.entity("line")), EPS);
        assertTrue(doc.redo());
        assertEquals(80.0, displayAngle((SketchGeometry.Line) doc.entity("line")), EPS);
    }


    @Test public void straightAngleCanonicalizesToZero() {
        SketchConstraint angle = SketchConstraint.lineAngle("angle", "line", 180.0);
        assertEquals(0.0, angle.value, 0.0);
    }

    @Test public void invalidAbsoluteAngleIsRejectedWithoutMutation() {
        SketchDocument doc = new SketchDocument();
        doc.add(line("line", 0, 0, 20, 5));
        long revision = doc.revision();
        try {
            doc.setDrivingDimensionAndSolve(
                    SketchConstraint.lineAngle("bad", "line", 181.0), solver);
            throw new AssertionError("Expected invalid absolute angle to be rejected");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("between 0 and 180"));
        }
        assertEquals(revision, doc.revision());
        assertEquals(0, doc.constraintCount());
    }

    private static SketchGeometry.Line line(String id, double x1, double y1, double x2, double y2) {
        return new SketchGeometry.Line(id,
                new SketchGeometry.Point(x1, y1), new SketchGeometry.Point(x2, y2));
    }

    private static SketchGeometry.Line solvedLine(SketchConstraintSolver.Result result, String id) {
        for (SketchEntity entity : result.entities()) {
            if (id.equals(entity.id())) return (SketchGeometry.Line) entity;
        }
        throw new AssertionError("Missing line " + id);
    }

    private static double displayAngle(SketchGeometry.Line line) {
        double angle = Math.toDegrees(Math.atan2(
                line.b.yMm - line.a.yMm, line.b.xMm - line.a.xMm));
        while (angle < 0.0) angle += 180.0;
        while (angle >= 180.0) angle -= 180.0;
        return angle;
    }

    private static void assertPoint(double x, double y, SketchGeometry.Point actual) {
        assertEquals(x, actual.xMm, EPS);
        assertEquals(y, actual.yMm, EPS);
    }
}

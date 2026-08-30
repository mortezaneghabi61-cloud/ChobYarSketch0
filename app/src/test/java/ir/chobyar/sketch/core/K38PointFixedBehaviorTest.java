package ir.chobyar.sketch.core;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** K3.8 behavior contracts for point-level Lock/Unlock model authority. */
public final class K38PointFixedBehaviorTest {
    private static final double EPS = 1.0e-6;

    @Test public void translatingLineWithFixedStartMovesOnlyFreeEndpoint() {
        SketchDocument doc = new SketchDocument();
        doc.add(line("line", 0, 0, 10, 4));
        doc.addConstraint(SketchConstraint.fixedPoint("lock-start", "line", 0));
        doc.selectOnly("line");

        assertTrue(doc.translateSelection(5, 7));
        SketchGeometry.Line moved = (SketchGeometry.Line) doc.entity("line");
        assertPoint(moved.a, 0, 0);
        assertPoint(moved.b, 15, 11);
    }

    @Test public void translatingLineWithBothEndpointsFixedDoesNothing() {
        SketchDocument doc = new SketchDocument();
        doc.add(line("line", 0, 0, 10, 4));
        doc.addConstraints(Arrays.asList(
                SketchConstraint.fixedPoint("lock-a", "line", 0),
                SketchConstraint.fixedPoint("lock-b", "line", 1)));
        doc.selectOnly("line");

        assertFalse(doc.translateSelection(5, 7));
        SketchGeometry.Line line = (SketchGeometry.Line) doc.entity("line");
        assertPoint(line.a, 0, 0);
        assertPoint(line.b, 10, 4);
    }

    @Test public void horizontalConstraintConvergesAroundFixedEndpoint() {
        DeterministicSketchConstraintSolver solver = new DeterministicSketchConstraintSolver();
        SketchConstraintSolver.Result result = solver.solve(
                Arrays.asList(line("line", 0, 0, 10, 5)),
                Arrays.asList(
                        SketchConstraint.fixedPoint("lock", "line", 0),
                        SketchConstraint.horizontal("horizontal", "line")));

        assertTrue(result.message, result.solved());
        SketchGeometry.Line solved = findLine(result, "line");
        assertPoint(solved.a, 0, 0);
        assertEquals(0.0, solved.b.yMm, EPS);
        assertTrue(solved.b.xMm > 0.0);
    }

    @Test public void conflictingCoincidentCannotMoveFixedEndpoint() {
        DeterministicSketchConstraintSolver solver = new DeterministicSketchConstraintSolver();
        SketchConstraintSolver.Result result = solver.solve(
                Arrays.asList(
                        line("owner", 0, 0, 10, 0),
                        line("target", 5, 5, 15, 5)),
                Arrays.asList(
                        SketchConstraint.fixedPoint("lock", "owner", 0),
                        SketchConstraint.coincident("coincident", "owner", 0, "target", 0)));

        assertEquals(SketchConstraintSolver.Status.CONFLICT, result.status);
        SketchGeometry.Line owner = findLine(result, "owner");
        assertPoint(owner.a, 0, 0);
    }

    @Test public void circleAndArcCenterLocksBlockTranslationWithoutFreezingShapeParameters() {
        SketchDocument doc = new SketchDocument();
        doc.add(new SketchGeometry.Circle("circle", p(4, 5), 12));
        doc.add(new SketchGeometry.Arc("arc", p(-3, 7), 8, 10, 120));
        doc.addConstraints(Arrays.asList(
                SketchConstraint.fixedPoint("circle-center", "circle", 0),
                SketchConstraint.fixedPoint("arc-center", "arc", 0)));

        doc.selectOnly("circle");
        assertFalse(doc.translateSelection(9, 11));
        SketchGeometry.Circle circle = (SketchGeometry.Circle) doc.entity("circle");
        assertPoint(circle.center, 4, 5);
        assertEquals(12.0, circle.radiusMm, EPS);

        doc.selectOnly("arc");
        assertFalse(doc.translateSelection(-2, 6));
        SketchGeometry.Arc arc = (SketchGeometry.Arc) doc.entity("arc");
        assertPoint(arc.center, -3, 7);
        assertEquals(8.0, arc.radiusMm, EPS);
        assertEquals(10.0, arc.startDeg, EPS);
        assertEquals(120.0, arc.sweepDeg, EPS);
    }

    @Test public void pointFixedConstraintParticipatesInUndoRedo() {
        SketchDocument doc = new SketchDocument();
        doc.add(line("line", 0, 0, 10, 0));
        doc.addConstraint(SketchConstraint.fixedPoint("lock", "line", 1));
        assertTrue(doc.containsConstraint("lock"));

        assertTrue(doc.undo());
        assertFalse(doc.containsConstraint("lock"));
        assertTrue(doc.redo());
        SketchConstraint restored = doc.constraint("lock");
        assertTrue(restored.fixesPoint());
        assertEquals(1, restored.primaryPointIndex);
    }

    @Test public void batchUnlockRemovalIsExactlyOneUndoStep() {
        SketchDocument doc = new SketchDocument();
        doc.add(line("a", 0, 0, 10, 0));
        doc.add(line("b", 0, 5, 10, 5));
        doc.addConstraints(Arrays.asList(
                SketchConstraint.fixed("lock-a", "a"),
                SketchConstraint.fixed("lock-b", "b")));

        assertEquals(2, doc.removeConstraints(Arrays.asList("lock-a", "lock-b")));
        assertFalse(doc.containsConstraint("lock-a"));
        assertFalse(doc.containsConstraint("lock-b"));

        assertTrue(doc.undo());
        assertTrue("One Undo must restore the complete multi-selection Lock state",
                doc.containsConstraint("lock-a"));
        assertTrue(doc.containsConstraint("lock-b"));

        assertTrue(doc.redo());
        assertFalse(doc.containsConstraint("lock-a"));
        assertFalse(doc.containsConstraint("lock-b"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void documentRejectsUnsupportedPointLockTarget() {
        SketchDocument doc = new SketchDocument();
        doc.add(new SketchGeometry.Rect("rect", p(0, 0),
                new SketchGeometry.Vector(10, 0), new SketchGeometry.Vector(0, 5)));
        doc.addConstraint(SketchConstraint.fixedPoint("bad", "rect", 0));
    }

    private static SketchGeometry.Line findLine(SketchConstraintSolver.Result result, String id) {
        for (SketchEntity entity : result.entities()) {
            if (id.equals(entity.id())) return (SketchGeometry.Line) entity;
        }
        throw new AssertionError("Missing line " + id);
    }

    private static SketchGeometry.Line line(String id, double ax, double ay, double bx, double by) {
        return new SketchGeometry.Line(id, p(ax, ay), p(bx, by));
    }

    private static SketchGeometry.Point p(double x, double y) {
        return new SketchGeometry.Point(x, y);
    }

    private static void assertPoint(SketchGeometry.Point point, double x, double y) {
        assertEquals(x, point.xMm, EPS);
        assertEquals(y, point.yMm, EPS);
    }
}

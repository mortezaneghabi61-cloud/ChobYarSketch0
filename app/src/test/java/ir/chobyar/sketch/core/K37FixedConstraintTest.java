package ir.chobyar.sketch.core;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Regression contracts for model-owned whole-entity FIXED semantics. */
public class K37FixedConstraintTest {
    private static final double EPS = 1.0e-6;

    private static SketchGeometry.Line line(String id, double x1, double y1, double x2, double y2) {
        return new SketchGeometry.Line(id,
                new SketchGeometry.Point(x1, y1),
                new SketchGeometry.Point(x2, y2));
    }

    @Test public void plainTranslateDoesNotMoveFixedSelection() {
        SketchDocument doc = new SketchDocument();
        doc.add(line("locked", 1, 2, 11, 2));
        doc.addConstraint(SketchConstraint.fixed("fix-locked", "locked"));
        doc.selectOnly("locked");

        long beforeRevision = doc.revision();
        assertFalse(doc.translateSelection(30, 40));
        SketchGeometry.Line locked = (SketchGeometry.Line) doc.entity("locked");
        assertEquals(1.0, locked.a.xMm, EPS);
        assertEquals(2.0, locked.a.yMm, EPS);
        assertEquals(11.0, locked.b.xMm, EPS);
        assertEquals(2.0, locked.b.yMm, EPS);
        assertEquals(beforeRevision, doc.revision());
    }

    @Test public void translateAndSolveKeepsFixedSelectionStationary() {
        SketchDocument doc = new SketchDocument();
        doc.add(line("locked", 0, 0, 10, 0));
        doc.addConstraint(SketchConstraint.fixed("fix-locked", "locked"));
        doc.selectOnly("locked");

        SketchConstraintSolver.Result result = doc.translateSelectionAndSolve(
                25, -8, new DeterministicSketchConstraintSolver());

        assertTrue(result.solved());
        SketchGeometry.Line locked = (SketchGeometry.Line) doc.entity("locked");
        assertEquals(0.0, locked.a.xMm, EPS);
        assertEquals(0.0, locked.a.yMm, EPS);
        assertEquals(10.0, locked.b.xMm, EPS);
        assertEquals(0.0, locked.b.yMm, EPS);
    }

    @Test public void solverPinsFixedGeometryAndMovesDependentEntity() {
        SketchGeometry.Line host = line("host", 0, 0, 20, 0);
        SketchGeometry.Line child = line("child", 25, 5, 25, 15);
        DeterministicSketchConstraintSolver solver = new DeterministicSketchConstraintSolver();

        SketchConstraintSolver.Result result = solver.solve(
                Arrays.asList(host, child),
                Arrays.asList(
                        SketchConstraint.fixed("fix-host", "host"),
                        SketchConstraint.coincident("join", "child", 0, "host", 1)));

        assertTrue(result.solved());
        SketchGeometry.Line solvedHost = solvedLine(result, "host");
        SketchGeometry.Line solvedChild = solvedLine(result, "child");
        assertEquals(0.0, solvedHost.a.xMm, EPS);
        assertEquals(20.0, solvedHost.b.xMm, EPS);
        assertEquals(20.0, solvedChild.a.xMm, EPS);
        assertEquals(0.0, solvedChild.a.yMm, EPS);
    }

    @Test public void incompatibleConstraintCannotMoveFixedGeometry() {
        SketchGeometry.Line locked = line("locked", 0, 0, 10, 3);
        DeterministicSketchConstraintSolver solver = new DeterministicSketchConstraintSolver();

        SketchConstraintSolver.Result result = solver.solve(
                Collections.singletonList(locked),
                Arrays.asList(
                        SketchConstraint.fixed("fix", "locked"),
                        SketchConstraint.horizontal("horizontal", "locked")));

        assertFalse(result.solved());
        assertEquals(SketchConstraintSolver.Status.CONFLICT, result.status);
        SketchGeometry.Line out = solvedLine(result, "locked");
        assertEquals(0.0, out.a.xMm, EPS);
        assertEquals(0.0, out.a.yMm, EPS);
        assertEquals(10.0, out.b.xMm, EPS);
        assertEquals(3.0, out.b.yMm, EPS);
    }

    @Test public void fixedSupportsNonLineGeometryWithoutTypeCast() {
        SketchGeometry.Circle circle = new SketchGeometry.Circle(
                "circle", new SketchGeometry.Point(7, 9), 12);
        DeterministicSketchConstraintSolver solver = new DeterministicSketchConstraintSolver();

        SketchConstraintSolver.Result result = solver.solve(
                Collections.singletonList(circle),
                Collections.singletonList(SketchConstraint.fixed("fix-circle", "circle")));

        assertTrue(result.solved());
        SketchGeometry.Circle out = (SketchGeometry.Circle) result.entities().get(0);
        assertEquals(7.0, out.center.xMm, EPS);
        assertEquals(9.0, out.center.yMm, EPS);
        assertEquals(12.0, out.radiusMm, EPS);
    }

    @Test public void fixedConstraintSurvivesUndoRedoAndExternalRestore() {
        SketchDocument doc = new SketchDocument();
        SketchGeometry.Line locked = line("locked", 0, 0, 10, 0);
        doc.add(locked);
        doc.addConstraint(SketchConstraint.fixed("fix", "locked"));
        assertNotNull(doc.constraint("fix"));

        assertTrue(doc.undo());
        assertNull(doc.constraint("fix"));
        assertTrue(doc.redo());
        assertNotNull(doc.constraint("fix"));

        SketchDocument restored = new SketchDocument();
        restored.restoreExternal(Collections.singletonList(locked), Collections.emptySet(),
                Collections.singletonList(SketchConstraint.fixed("fix", "locked")));
        assertNotNull(restored.constraint("fix"));
        restored.selectOnly("locked");
        assertFalse(restored.translateSelection(5, 5));
    }

    private static SketchGeometry.Line solvedLine(SketchConstraintSolver.Result result, String id) {
        for (SketchEntity entity : result.entities()) {
            if (id.equals(entity.id())) return (SketchGeometry.Line) entity;
        }
        throw new AssertionError("missing solved entity " + id);
    }
}

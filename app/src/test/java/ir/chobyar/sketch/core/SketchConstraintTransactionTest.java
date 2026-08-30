package ir.chobyar.sketch.core;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SketchConstraintTransactionTest {
    private static final double EPS = 1.0e-6;
    private final SketchConstraintSolver solver = new DeterministicSketchConstraintSolver();

    private static SketchGeometry.Line line(String id, double x1, double y1, double x2, double y2) {
        return new SketchGeometry.Line(id,
                new SketchGeometry.Point(x1, y1),
                new SketchGeometry.Point(x2, y2));
    }

    @Test public void addConstraintAndSolvedGeometryShareOneUndoRedoStep() {
        SketchDocument doc = new SketchDocument();
        doc.add(line("a", 0, 0, 10, 2));
        while (doc.canUndo()) doc.undo();
        while (doc.canRedo()) doc.redo();
        // RestoreExternal gives a clean history boundary, matching project-open semantics.
        doc.restoreExternal(Collections.singletonList(line("a", 0, 0, 10, 2)), Collections.singleton("a"));

        doc.addConstraintsAndSolve(
                Collections.singletonList(SketchConstraint.horizontal("h", "a")), solver);
        SketchGeometry.Line solved = (SketchGeometry.Line) doc.entity("a");
        assertEquals(solved.a.yMm, solved.b.yMm, EPS);
        assertEquals(1, doc.constraintCount());
        assertTrue(doc.canUndo());

        assertTrue(doc.undo());
        SketchGeometry.Line original = (SketchGeometry.Line) doc.entity("a");
        assertEquals(0.0, original.a.yMm, EPS);
        assertEquals(2.0, original.b.yMm, EPS);
        assertEquals(0, doc.constraintCount());
        assertFalse(doc.canUndo());

        assertTrue(doc.redo());
        assertEquals(1, doc.constraintCount());
        SketchGeometry.Line redone = (SketchGeometry.Line) doc.entity("a");
        assertEquals(redone.a.yMm, redone.b.yMm, EPS);
    }

    @Test public void conflictRejectsBeforeGeometryHistoryOrConstraintMutation() {
        SketchDocument doc = new SketchDocument();
        doc.restoreExternal(Collections.singletonList(line("a", 0, 0, 10, 3)), Collections.singleton("a"));
        long revision = doc.revision();

        try {
            doc.addConstraintsAndSolve(Arrays.asList(
                    SketchConstraint.horizontal("h", "a"),
                    SketchConstraint.vertical("v", "a")), solver);
            throw new AssertionError("expected conflict");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("CONFLICT"));
        }

        assertEquals(revision, doc.revision());
        assertEquals(0, doc.constraintCount());
        assertFalse(doc.canUndo());
        SketchGeometry.Line original = (SketchGeometry.Line) doc.entity("a");
        assertEquals(0.0, original.a.yMm, EPS);
        assertEquals(3.0, original.b.yMm, EPS);
    }

    @Test public void constrainedMovePropagatesAndIsOneUndoStep() {
        SketchDocument doc = new SketchDocument();
        SketchGeometry.Line host = line("host", 0, 0, 20, 0);
        SketchGeometry.Line child = line("child", 20, 0, 20, 10);
        doc.restoreExternal(Arrays.asList(host, child), Collections.singleton("child"),
                Arrays.asList(
                        SketchConstraint.coincident("c", "child", 0, "host", 1),
                        SketchConstraint.vertical("v", "child")));

        doc.translateSelectionAndSolve(5, 4, solver);
        SketchGeometry.Line moved = (SketchGeometry.Line) doc.entity("child");
        // Constraint graph wins over the raw translation at the connected endpoint.
        assertEquals(20.0, moved.a.xMm, EPS);
        assertEquals(0.0, moved.a.yMm, EPS);
        assertEquals(moved.a.xMm, moved.b.xMm, EPS);
        assertTrue(doc.canUndo());

        assertTrue(doc.undo());
        SketchGeometry.Line original = (SketchGeometry.Line) doc.entity("child");
        assertEquals(20.0, original.a.xMm, EPS);
        assertEquals(0.0, original.a.yMm, EPS);
        assertEquals(20.0, original.b.xMm, EPS);
        assertEquals(10.0, original.b.yMm, EPS);
        assertFalse(doc.canUndo());
    }
}

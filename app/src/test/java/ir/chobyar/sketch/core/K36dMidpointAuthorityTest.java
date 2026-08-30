package ir.chobyar.sketch.core;

import org.junit.Test;

import java.util.Collections;

import static org.junit.Assert.*;

/** Production contract for K3.6d model-owned midpoint relationships. */
public class K36dMidpointAuthorityTest {
    private static final DeterministicSketchConstraintSolver SOLVER = new DeterministicSketchConstraintSolver();

    private static SketchGeometry.Line line(String id, double x1, double y1, double x2, double y2) {
        return new SketchGeometry.Line(id,
                new SketchGeometry.Point(x1, y1),
                new SketchGeometry.Point(x2, y2));
    }

    private static void assertDrivenAtHostMidpoint(SketchDocument d, String drivenId,
                                                   int pointIndex, String hostId) {
        SketchGeometry.Line driven = (SketchGeometry.Line) d.entity(drivenId);
        SketchGeometry.Line host = (SketchGeometry.Line) d.entity(hostId);
        SketchGeometry.Point p = pointIndex == 0 ? driven.a : driven.b;
        assertEquals((host.a.xMm + host.b.xMm) * 0.5, p.xMm, 1e-9);
        assertEquals((host.a.yMm + host.b.yMm) * 0.5, p.yMm, 1e-9);
    }

    @Test public void midpointIsStableIdModelTruthAndTracksHostEdit() {
        SketchDocument d = new SketchDocument();
        d.add(line("host", 0, 0, 20, 0));
        d.addWithConstraintsAndSolve(
                line("driven", 9, 4, 9, 10),
                Collections.singletonList(SketchConstraint.midpoint("mid-1", "driven", 0, "host")),
                SOLVER);

        SketchConstraint c = d.constraint("mid-1");
        assertNotNull(c);
        assertEquals(SketchConstraint.Kind.MIDPOINT, c.kind);
        assertEquals("driven", c.primaryEntityId);
        assertEquals(0, c.primaryPointIndex);
        assertEquals("host", c.secondaryEntityId);
        assertEquals(-1, c.secondaryPointIndex);
        assertDrivenAtHostMidpoint(d, "driven", 0, "host");

        d.selectOnly("host");
        d.translateSelectionAndSolve(6, 8, SOLVER);
        assertDrivenAtHostMidpoint(d, "driven", 0, "host");
    }

    @Test public void createMidpointRelationshipIsOneUndoAndRedoRestoresExactReference() {
        SketchDocument d = new SketchDocument();
        d.add(line("host", -10, 2, 10, 6));
        d.addWithConstraintsAndSolve(
                line("new", 1, 7, 8, 12),
                Collections.singletonList(SketchConstraint.midpoint("mid-create", "new", 0, "host")),
                SOLVER);
        assertDrivenAtHostMidpoint(d, "new", 0, "host");

        assertTrue(d.undo());
        assertNull(d.entity("new"));
        assertNull(d.constraint("mid-create"));
        assertEquals(1, d.size());

        assertTrue(d.redo());
        assertDrivenAtHostMidpoint(d, "new", 0, "host");
        SketchConstraint restored = d.constraint("mid-create");
        assertNotNull(restored);
        assertEquals("new", restored.primaryEntityId);
        assertEquals(0, restored.primaryPointIndex);
        assertEquals("host", restored.secondaryEntityId);
    }

    @Test public void persistenceRestoreRetainsMidpointAndSubsequentSolvePropagation() {
        SketchDocument original = new SketchDocument();
        original.add(line("host", 0, 0, 30, 10));
        original.addWithConstraintsAndSolve(
                line("driven", 2, 9, 2, 14),
                Collections.singletonList(SketchConstraint.midpoint("mid-persist", "driven", 0, "host")),
                SOLVER);

        SketchDocument reopened = new SketchDocument();
        reopened.restoreExternal(original.entities(), Collections.<String>emptyList(), original.constraints());
        assertEquals(1, reopened.constraintCount());
        assertEquals(SketchConstraint.Kind.MIDPOINT, reopened.constraint("mid-persist").kind);
        assertDrivenAtHostMidpoint(reopened, "driven", 0, "host");

        reopened.selectOnly("host");
        reopened.translateSelectionAndSolve(-4, 7, SOLVER);
        assertDrivenAtHostMidpoint(reopened, "driven", 0, "host");
    }

    @Test public void deletingMidpointHostCascadesAndUndoRedoRestoresStableReference() {
        SketchDocument d = new SketchDocument();
        d.add(line("host", 0, 0, 12, 4));
        d.addWithConstraintsAndSolve(
                line("driven", 3, 3, 3, 9),
                Collections.singletonList(SketchConstraint.midpoint("mid-delete", "driven", 0, "host")),
                SOLVER);

        assertTrue(d.remove("host"));
        assertNull(d.entity("host"));
        assertNull(d.constraint("mid-delete"));
        assertNotNull(d.entity("driven"));

        assertTrue(d.undo());
        assertNotNull(d.entity("host"));
        SketchConstraint restored = d.constraint("mid-delete");
        assertNotNull(restored);
        assertEquals("driven", restored.primaryEntityId);
        assertEquals(0, restored.primaryPointIndex);
        assertEquals("host", restored.secondaryEntityId);
        assertDrivenAtHostMidpoint(d, "driven", 0, "host");

        assertTrue(d.redo());
        assertNull(d.entity("host"));
        assertNull(d.constraint("mid-delete"));
    }

    @Test public void malformedMidpointReferencesFailClosed() {
        try {
            SketchConstraint.midpoint("bad-index", "driven", 7, "host");
            fail("midpoint must reject non-endpoint primary references");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("endpoint 0 or 1"));
        }

        SketchDocument d = new SketchDocument();
        d.add(line("driven", 0, 0, 0, 5));
        long revision = d.revision();
        try {
            d.addConstraintsAndSolve(
                    Collections.singletonList(SketchConstraint.midpoint("dangling", "driven", 0, "missing")),
                    SOLVER);
            fail("dangling host must fail before mutation");
        } catch (IllegalArgumentException expected) {
            // Model reference validation owns this failure before solver mutation.
        }
        assertEquals(revision, d.revision());
        assertEquals(0, d.constraintCount());
    }
}

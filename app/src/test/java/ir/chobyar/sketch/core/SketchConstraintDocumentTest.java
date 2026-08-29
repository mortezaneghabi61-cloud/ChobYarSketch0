package ir.chobyar.sketch.core;

import org.junit.Test;

import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class SketchConstraintDocumentTest {
    private static SketchGeometry.Line line(String id, double x1, double y1, double x2, double y2) {
        return new SketchGeometry.Line(id,
                new SketchGeometry.Point(x1, y1),
                new SketchGeometry.Point(x2, y2));
    }

    @Test public void constraintsReferenceStableEntityIdsNotObjectIdentity() {
        SketchDocument doc = new SketchDocument();
        doc.add(line("a", 0, 0, 10, 0));
        doc.add(line("b", 10, 0, 10, 10));
        doc.addConstraint(SketchConstraint.coincident("c1", "a", 1, "b", 0));

        SketchConstraint c = doc.constraint("c1");
        assertNotNull(c);
        assertEquals("a", c.primaryEntityId);
        assertEquals("b", c.secondaryEntityId);
        assertTrue(c.references("a"));
        assertTrue(c.references("b"));
    }

    @Test public void constraintAdditionParticipatesInUndoRedo() {
        SketchDocument doc = new SketchDocument();
        doc.add(line("a", 0, 0, 10, 0));
        doc.addConstraint(SketchConstraint.horizontal("h1", "a"));
        assertEquals(1, doc.constraintCount());

        assertTrue(doc.undo());
        assertEquals(1, doc.size());
        assertEquals(0, doc.constraintCount());

        assertTrue(doc.redo());
        assertEquals(1, doc.constraintCount());
        assertEquals(SketchConstraint.Kind.HORIZONTAL, doc.constraint("h1").kind);
    }

    @Test public void createWithAutoConstraintIsOneUndoStep() {
        SketchDocument doc = new SketchDocument();
        doc.add(line("host", 0, 0, 20, 0));
        doc.addWithConstraints(
                line("new", 20, 0, 20, 10),
                Collections.singletonList(SketchConstraint.coincident("c1", "host", 1, "new", 0)));

        assertEquals(2, doc.size());
        assertEquals(1, doc.constraintCount());
        assertTrue(doc.undo());
        assertEquals(1, doc.size());
        assertFalse(doc.contains("new"));
        assertEquals(0, doc.constraintCount());
        assertTrue(doc.redo());
        assertTrue(doc.contains("new"));
        assertTrue(doc.containsConstraint("c1"));
    }

    @Test public void deletingReferencedEntityCascadesConstraintAndUndoRestoresBoth() {
        SketchDocument doc = new SketchDocument();
        doc.add(line("a", 0, 0, 10, 0));
        doc.add(line("b", 10, 0, 20, 0));
        doc.addConstraint(SketchConstraint.coincident("c1", "a", 1, "b", 0));

        assertTrue(doc.remove("b"));
        assertFalse(doc.contains("b"));
        assertEquals(0, doc.constraintCount());

        assertTrue(doc.undo());
        assertTrue(doc.contains("b"));
        assertTrue(doc.containsConstraint("c1"));
    }

    @Test public void danglingConstraintIsRejectedWithoutMutatingHistory() {
        SketchDocument doc = new SketchDocument();
        doc.add(line("a", 0, 0, 10, 0));
        long revision = doc.revision();
        boolean undoBefore = doc.canUndo();
        try {
            doc.addConstraint(SketchConstraint.coincident("bad", "a", 1, "missing", 0));
            throw new AssertionError("expected dangling constraint rejection");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("missing entity"));
        }
        assertEquals(revision, doc.revision());
        assertEquals(undoBefore, doc.canUndo());
        assertEquals(0, doc.constraintCount());
    }

    @Test public void externalRestoreValidatesAndRestoresConstraintGraphWithoutUndoHistory() {
        SketchDocument doc = new SketchDocument();
        SketchGeometry.Line a = line("a", 0, 0, 10, 0);
        SketchGeometry.Line b = line("b", 10, 0, 10, 10);
        SketchConstraint c = SketchConstraint.coincident("c1", "a", 1, "b", 0);
        doc.restoreExternal(java.util.Arrays.asList(a, b), Collections.singleton("a"), Collections.singleton(c));

        assertEquals(2, doc.size());
        assertEquals(1, doc.constraintCount());
        assertEquals(Collections.singleton("a"), doc.selectionIds());
        assertFalse(doc.canUndo());
        assertFalse(doc.canRedo());
    }
}

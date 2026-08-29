package ir.chobyar.sketch.core;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class SketchDocumentTest {

    @Test
    public void stableIdsDriveSelectionAndUndoRedo() {
        SketchDocument doc = new SketchDocument(8);
        SketchGeometry.Line line = new SketchGeometry.Line(
                "line-1",
                new SketchGeometry.Point(0.0, 0.0),
                new SketchGeometry.Point(100.0, 0.0));

        doc.add(line);
        doc.selectOnly("line-1");
        assertEquals(Collections.singleton("line-1"), doc.selectionIds());
        assertTrue(doc.translateSelection(25.0, 10.0));

        SketchGeometry.Line moved = (SketchGeometry.Line) doc.entity("line-1");
        assertNotNull(moved);
        assertEquals(25.0, moved.a.xMm, 1.0e-9);
        assertEquals(10.0, moved.a.yMm, 1.0e-9);
        assertEquals(125.0, moved.b.xMm, 1.0e-9);

        assertTrue(doc.undo());
        SketchGeometry.Line restored = (SketchGeometry.Line) doc.entity("line-1");
        assertEquals(0.0, restored.a.xMm, 1.0e-9);
        assertEquals(Collections.singleton("line-1"), doc.selectionIds());

        assertTrue(doc.redo());
        moved = (SketchGeometry.Line) doc.entity("line-1");
        assertEquals(25.0, moved.a.xMm, 1.0e-9);
    }

    @Test
    public void removingEntityCannotLeaveStaleSelection() {
        SketchDocument doc = new SketchDocument();
        doc.add(new SketchGeometry.Circle("c1", new SketchGeometry.Point(4.0, 5.0), 12.0));
        doc.selectOnly("c1");
        assertTrue(doc.remove("c1"));
        assertTrue(doc.selectionIds().isEmpty());
        assertFalse(doc.contains("c1"));

        assertTrue(doc.undo());
        assertTrue(doc.contains("c1"));
        assertEquals(Collections.singleton("c1"), doc.selectionIds());
    }

    @Test
    public void invalidAndDuplicateGeometryIsRejectedBeforeStateMutation() {
        SketchDocument doc = new SketchDocument();
        doc.add(new SketchGeometry.Line(
                "e1", new SketchGeometry.Point(0.0, 0.0), new SketchGeometry.Point(1.0, 0.0)));
        long revision = doc.revision();

        try {
            doc.add(new SketchGeometry.Circle("e1", new SketchGeometry.Point(0.0, 0.0), 1.0));
            throw new AssertionError("Expected duplicate id rejection");
        } catch (IllegalArgumentException expected) {
            // expected
        }
        assertEquals(revision, doc.revision());
        assertEquals(1, doc.size());

        try {
            doc.add(new SketchGeometry.Circle("bad", new SketchGeometry.Point(0.0, 0.0), 0.0));
            throw new AssertionError("Expected invalid radius rejection");
        } catch (IllegalArgumentException expected) {
            // expected
        }
        assertEquals(revision, doc.revision());
    }

    @Test
    public void externalRestoreResetsHistoryAndFiltersSelection() {
        SketchDocument doc = new SketchDocument();
        doc.add(new SketchGeometry.Circle("old", new SketchGeometry.Point(0.0, 0.0), 5.0));
        assertTrue(doc.canUndo());

        doc.restoreExternal(Arrays.asList(
                        new SketchGeometry.Line("line", new SketchGeometry.Point(0.0, 0.0), new SketchGeometry.Point(20.0, 0.0)),
                        new SketchGeometry.Arc("arc", new SketchGeometry.Point(10.0, 10.0), 4.0, 0.0, 90.0)),
                Arrays.asList("line", "missing"));

        assertEquals(2, doc.size());
        assertEquals(Collections.singleton("line"), doc.selectionIds());
        assertFalse(doc.canUndo());
        assertFalse(doc.canRedo());
    }

    @Test
    public void polylineDefensivelyCopiesInputPoints() {
        SketchGeometry.Point a = new SketchGeometry.Point(0.0, 0.0);
        SketchGeometry.Point b = new SketchGeometry.Point(10.0, 0.0);
        SketchGeometry.Polyline p = new SketchGeometry.Polyline("p", Arrays.asList(a, b), false);
        assertTrue(p.isValid());
        assertEquals(2, p.points().size());
        try {
            p.points().add(new SketchGeometry.Point(20.0, 0.0));
            throw new AssertionError("Expected immutable point list");
        } catch (UnsupportedOperationException expected) {
            // expected
        }
    }
}

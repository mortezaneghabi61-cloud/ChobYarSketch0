package ir.chobyar.sketch.core;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;

public class SketchEntitiesTest {

    @Test
    public void snapshotCopyPreservesIdentityButUserDuplicateGetsNewIdentity() {
        SketchGeometry.Line source = new SketchGeometry.Line(
                "line-original",
                new SketchGeometry.Point(5.0, 7.0),
                new SketchGeometry.Point(105.0, 7.0));

        SketchEntity snapshot = source.copy();
        SketchEntity duplicate = SketchEntities.duplicateAs(source, "line-copy");

        assertNotSame(source, snapshot);
        assertEquals("line-original", snapshot.id());
        assertEquals("line-copy", duplicate.id());
        assertEquals(source.kind(), duplicate.kind());
        assertTrue(duplicate.isValid());

        SketchDocument doc = new SketchDocument();
        doc.add(source);
        doc.add(duplicate);
        assertEquals(2, doc.size());
    }

    @Test
    public void duplicateAsDeepCopiesCollectionGeometry() {
        SketchPolygon polygon = SketchPolygon.regular("poly-original", 6, 0.0, 0.0, 20.0, -90.0);
        SketchPolygon duplicate = (SketchPolygon) SketchEntities.duplicateAs(polygon, "poly-copy");

        assertEquals("poly-copy", duplicate.id());
        assertEquals(polygon.vertices().size(), duplicate.vertices().size());
        assertNotSame(polygon.vertices(), duplicate.vertices());
        assertNotSame(polygon.vertices().get(0), duplicate.vertices().get(0));
    }
}

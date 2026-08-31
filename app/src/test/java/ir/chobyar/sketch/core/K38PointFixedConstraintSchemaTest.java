package ir.chobyar.sketch.core;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** K3.8 contract: FIXED may anchor one stable sketch point without implying whole-entity lock. */
public final class K38PointFixedConstraintSchemaTest {
    @Test public void wholeEntityFixedRemainsBackwardCompatible() {
        SketchConstraint fixed = SketchConstraint.fixed("fixed-entity", "line-1");
        assertEquals(SketchConstraint.Kind.FIXED, fixed.kind);
        assertEquals("line-1", fixed.primaryEntityId);
        assertEquals(-1, fixed.primaryPointIndex);
        assertTrue(fixed.fixesWholeEntity());
        assertFalse(fixed.fixesPoint());
    }

    @Test public void pointFixedStoresStablePointIndex() {
        SketchConstraint fixed = SketchConstraint.fixedPoint("fixed-point", "line-1", 1);
        assertEquals(SketchConstraint.Kind.FIXED, fixed.kind);
        assertEquals("line-1", fixed.primaryEntityId);
        assertEquals(1, fixed.primaryPointIndex);
        assertTrue(fixed.fixesPoint());
        assertFalse(fixed.fixesWholeEntity());
        assertTrue(fixed.references("line-1"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void fixedRejectsSecondaryPointIndex() {
        new SketchConstraint("bad-fixed", SketchConstraint.Kind.FIXED,
                "line-1", 0, null, 0, Double.NaN, true);
    }
}

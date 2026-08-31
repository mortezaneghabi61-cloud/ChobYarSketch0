package ir.chobyar.sketch.core;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Contract for the K3.8 Interaction slice: only model-supported point handles may expose Lock. */
public final class K38PointLockInteractionMappingTest {

    @Test public void lineEndpointsMapDirectlyToModelPointIndices() {
        assertTrue(PointLockInteractionMapping.isSupported(SketchEntity.Kind.LINE, 0));
        assertTrue(PointLockInteractionMapping.isSupported(SketchEntity.Kind.LINE, 1));
        assertEquals(0, PointLockInteractionMapping.modelPointIndex(SketchEntity.Kind.LINE, 0));
        assertEquals(1, PointLockInteractionMapping.modelPointIndex(SketchEntity.Kind.LINE, 1));
    }

    @Test public void circleOnlyCenterHandleIsLockable() {
        assertTrue(PointLockInteractionMapping.isSupported(SketchEntity.Kind.CIRCLE, 0));
        assertEquals(0, PointLockInteractionMapping.modelPointIndex(SketchEntity.Kind.CIRCLE, 0));
        assertFalse(PointLockInteractionMapping.isSupported(SketchEntity.Kind.CIRCLE, 1));
    }

    @Test public void arcOnlyCenterHandleIsLockableInThisSlice() {
        assertTrue(PointLockInteractionMapping.isSupported(SketchEntity.Kind.ARC, 0));
        assertEquals(0, PointLockInteractionMapping.modelPointIndex(SketchEntity.Kind.ARC, 0));
        assertFalse(PointLockInteractionMapping.isSupported(SketchEntity.Kind.ARC, 1));
        assertFalse(PointLockInteractionMapping.isSupported(SketchEntity.Kind.ARC, 2));
    }

    @Test public void unsupportedPrimitivesAndHandlesFailClosed() {
        assertFalse(PointLockInteractionMapping.isSupported(SketchEntity.Kind.RECT, 0));
        assertFalse(PointLockInteractionMapping.isSupported(SketchEntity.Kind.POLYLINE, 0));
        assertEquals(-1, PointLockInteractionMapping.modelPointIndex(SketchEntity.Kind.CIRCLE, 1));
        assertEquals(-1, PointLockInteractionMapping.modelPointIndex(SketchEntity.Kind.ARC, 2));
    }
}

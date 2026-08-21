package ir.chobyar.sketch;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class SectionViewControllerInstrumentationTest {
    private static final double EPS = 1e-6;

    @Test public void disabledSectionPassesTriangleSnapshotThrough() {
        SectionViewController controller = new SectionViewController();
        double[] mesh = {-2,0,0, 2,0,0, 0,2,0};
        assertArrayEquals(mesh, controller.apply(mesh), 0.0);
        assertFalse(controller.isEnabled());
    }

    @Test public void zSectionClipsCrossingTriangleAtExactPlane() {
        SectionViewController controller = new SectionViewController();
        controller.enable(SectionViewController.Axis.Z);
        controller.setOffsetMm(0.0);
        double[] mesh = {-1,0,-2, 1,0,2, 0,2,2};
        double[] clipped = controller.apply(mesh);
        assertTrue(clipped.length >= 9);
        for (int i = 2; i < clipped.length; i += 3) assertTrue(clipped[i] >= -EPS);
        boolean hasPlaneVertex = false;
        for (int i = 2; i < clipped.length; i += 3) if (Math.abs(clipped[i]) <= EPS) hasPlaneVertex = true;
        assertTrue(hasPlaneVertex);
    }

    @Test public void flipKeepsOppositeHalfSpaceWithoutChangingOffset() {
        SectionViewController controller = new SectionViewController();
        controller.enable(SectionViewController.Axis.X);
        controller.setOffsetMm(3.5);
        controller.flip();
        double[] mesh = {2,0,0, 4,0,0, 2,2,0};
        double[] clipped = controller.apply(mesh);
        assertTrue(clipped.length >= 9);
        for (int i = 0; i < clipped.length; i += 3) assertTrue(clipped[i] <= 3.5 + EPS);
        assertEquals(3.5, controller.offsetMm(), EPS);
        assertTrue(controller.isFlipped());
    }

    @Test public void axisOffsetAndDisableRemainRendererOnlyState() {
        SectionViewController controller = new SectionViewController();
        controller.enable(SectionViewController.Axis.Y);
        controller.setOffsetMm(-12.25);
        assertEquals(SectionViewController.Axis.Y, controller.axis());
        assertEquals(-12.25, controller.offsetMm(), EPS);
        assertEquals(3, controller.selectedIndex());
        assertTrue(controller.summary().contains("Section Y"));
        controller.disable();
        assertEquals(0, controller.selectedIndex());
        assertFalse(controller.isEnabled());
    }
}

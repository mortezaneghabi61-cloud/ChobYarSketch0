package ir.chobyar.sketch;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Locks the single topology-rematch confidence policy shared by Preview and History. */
public class OcctTopologyConfidencePolicyTest {
    @Test public void confidenceBoundaryIsStrictlyBelowNinetyFive() {
        Geometry3D.Vec3 anchor = new Geometry3D.Vec3(1, 2, 3);
        assertTrue(new OcctTopologyRef.Resolution(anchor, 94.999).confident());
        assertFalse(new OcctTopologyRef.Resolution(anchor, 95.0).confident());
        assertFalse(new OcctTopologyRef.Resolution(anchor, 180.0).confident());
    }
}

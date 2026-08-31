package ir.chobyar.sketch.core;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Ensures the existing schema-v2 bridge preserves point-level FIXED semantics. */
public final class K38PointFixedPersistenceTest {
    @Test public void bridgeRestoresPointIndexAndParity() {
        String raw = "{"
                + "\"schemaVersion\":2,"
                + "\"entities\":[{\"id\":\"line-1\",\"type\":\"LINE\","
                + "\"x1\":0,\"y1\":0,\"x2\":10,\"y2\":5}],"
                + "\"modelConstraintSchemaVersion\":1,"
                + "\"modelConstraints\":[{"
                + "\"id\":\"lock-end\",\"kind\":\"FIXED\","
                + "\"primaryEntityId\":\"line-1\",\"primaryPointIndex\":1,"
                + "\"secondaryPointIndex\":-1,\"driving\":true"
                + "}]}";

        SketchDocument doc = new SketchDocument();
        LegacySketchStateBridge.restoreDocument(doc, raw);

        SketchConstraint fixed = doc.constraint("lock-end");
        assertTrue(fixed.fixesPoint());
        assertEquals(1, fixed.primaryPointIndex);
        assertTrue(LegacySketchStateBridge.hasParity(doc, raw));
    }
}

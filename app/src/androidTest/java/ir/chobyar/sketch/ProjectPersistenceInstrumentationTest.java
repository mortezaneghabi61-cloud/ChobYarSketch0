package ir.chobyar.sketch;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class ProjectPersistenceInstrumentationTest {

    private static String sketchFixture() throws Exception {
        JSONObject sketch = new JSONObject();
        sketch.put("schemaVersion", 1);
        sketch.put("currentLayer", "Furniture");
        sketch.put("entities", new JSONArray()
                .put(new JSONObject().put("type", "LINE").put("x1", 0).put("y1", 0).put("x2", 120).put("y2", 0))
                .put(new JSONObject().put("type", "CIRCLE").put("x", 60).put("y", 40).put("r", 12.5)));
        return sketch.toString();
    }

    @Test public void envelopeRoundTripPreservesSketchAndMillimeters() throws Exception {
        String encoded = CadProjectDocument.encodeSketch(sketchFixture());
        CadProjectDocument.Decoded decoded = CadProjectDocument.decode(encoded);
        assertEquals(1, decoded.schemaVersion);
        assertEquals(CadProjectDocument.SCOPE_SKETCH_V1, decoded.scope);
        assertEquals("mm", decoded.unit);
        JSONObject restored = new JSONObject(decoded.sketchState);
        assertEquals("Furniture", restored.getString("currentLayer"));
        assertEquals(2, restored.getJSONArray("entities").length());
    }

    @Test public void futureSchemaFailsClosed() throws Exception {
        JSONObject root = new JSONObject(CadProjectDocument.encodeSketch(sketchFixture()));
        root.put("schemaVersion", CadProjectDocument.SCHEMA_VERSION + 1);
        try {
            CadProjectDocument.decode(root.toString());
            fail("future schema must be rejected");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("newer"));
        }
    }

    @Test public void malformedOrWrongFormatFailsClosed() throws Exception {
        try {
            CadProjectDocument.decode("{broken");
            fail("malformed json must fail");
        } catch (RuntimeException expected) {
            assertTrue(true);
        }
        try {
            CadProjectDocument.decode(new JSONObject().put("format", "other").put("schemaVersion", 1).toString());
            fail("wrong format must fail");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("format"));
        }
    }

    @Test public void envelopeDeclaresNonLossySketchScope() throws Exception {
        JSONObject root = new JSONObject(CadProjectDocument.encodeSketch(sketchFixture()));
        assertEquals("chobyar-project", root.getString("format"));
        assertEquals("sketch-v1", root.getString("scope"));
        assertEquals("mm", root.getString("unit"));
        assertTrue(root.has("sketch"));
    }
}

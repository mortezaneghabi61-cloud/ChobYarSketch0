package ir.chobyar.sketch;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class ProjectPersistenceInstrumentationTest {

    private static JSONObject meta(String type) throws Exception {
        return new JSONObject().put("type", type).put("layer", "Furniture")
                .put("color", 0xff223344).put("extrusion", 0).put("construction", false)
                .put("referenceBodyId", -1).put("referenceEdgeIndex", -1).put("referenceEdgeKind", 0);
    }

    private static String sketchFixture() throws Exception {
        JSONObject view = new JSONObject().put("scale", 1.25).put("offsetX", 140).put("offsetY", 180)
                .put("grid", true).put("axes", true).put("guides", true).put("dimensions", true)
                .put("snap", true).put("ortho", false);
        JSONArray layers = new JSONArray().put(new JSONObject().put("name", "0").put("visible", true))
                .put(new JSONObject().put("name", "Furniture").put("visible", true));
        JSONObject line = meta("LINE").put("x1", 0).put("y1", 0).put("x2", 120).put("y2", 0);
        JSONObject circle = meta("CIRCLE").put("x", 60).put("y", 40).put("r", 12.5);
        return new JSONObject().put("schemaVersion", 1).put("unit", "mm")
                .put("currentLayer", "Furniture").put("currentColor", 0xff223344).put("polygonSides", 6)
                .put("view", view).put("layers", layers).put("entities", new JSONArray().put(line).put(circle)).toString();
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

    @Test public void canvasSnapshotRoundTripPreservesEntitiesMetadataAndView() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        CadCanvasView canvas = new CadCanvasView(context);
        assertTrue(canvas.canImportSketchProjectState(sketchFixture()));
        assertTrue(canvas.importSketchProjectState(sketchFixture()).contains("2"));
        JSONObject out = new JSONObject(canvas.exportSketchProjectState());
        assertEquals("mm", out.getString("unit"));
        assertEquals("Furniture", out.getString("currentLayer"));
        assertEquals(2, out.getJSONArray("entities").length());
        assertEquals("LINE", out.getJSONArray("entities").getJSONObject(0).getString("type"));
        assertEquals(120.0, out.getJSONArray("entities").getJSONObject(0).getDouble("x2"), 0.0001);
        assertEquals(12.5, out.getJSONArray("entities").getJSONObject(1).getDouble("r"), 0.0001);
        assertEquals(1.25, out.getJSONObject("view").getDouble("scale"), 0.0001);
    }

    @Test public void invalidSketchRestoreDoesNotMutateCurrentCanvas() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        CadCanvasView canvas = new CadCanvasView(context);
        canvas.importSketchProjectState(sketchFixture());
        JSONObject before = new JSONObject(canvas.exportSketchProjectState());
        JSONObject bad = new JSONObject(sketchFixture());
        bad.getJSONArray("entities").getJSONObject(0).put("type", "UNKNOWN");
        assertFalse(canvas.canImportSketchProjectState(bad.toString()));
        assertTrue(canvas.importSketchProjectState(bad.toString()).contains("نامعتبر"));
        JSONObject after = new JSONObject(canvas.exportSketchProjectState());
        assertEquals(before.getJSONArray("entities").length(), after.getJSONArray("entities").length());
        assertEquals(before.getJSONArray("entities").getJSONObject(0).getString("type"), after.getJSONArray("entities").getJSONObject(0).getString("type"));
        assertEquals(before.getJSONArray("entities").getJSONObject(0).getDouble("x2"), after.getJSONArray("entities").getJSONObject(0).getDouble("x2"), 0.0001);
    }
}

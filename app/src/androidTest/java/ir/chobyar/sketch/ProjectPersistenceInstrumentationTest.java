package ir.chobyar.sketch;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;

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

    private static <T> T onMain(Callable<T> callable) throws Exception {
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            try {
                result.set(callable.call());
            } catch (Throwable t) {
                error.set(t);
            }
        });
        Throwable failure = error.get();
        if (failure instanceof Exception) throw (Exception) failure;
        if (failure instanceof Error) throw (Error) failure;
        if (failure != null) throw new RuntimeException(failure);
        return result.get();
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
        JSONObject out = onMain(() -> {
            Context context = ApplicationProvider.getApplicationContext();
            CadCanvasView canvas = new CadCanvasView(context);
            assertTrue(canvas.canImportSketchProjectState(sketchFixture()));
            assertTrue(canvas.importSketchProjectState(sketchFixture()).contains("2"));
            return new JSONObject(canvas.exportSketchProjectState());
        });
        assertEquals("mm", out.getString("unit"));
        assertEquals("Furniture", out.getString("currentLayer"));
        assertEquals(2, out.getJSONArray("entities").length());
        assertEquals("LINE", out.getJSONArray("entities").getJSONObject(0).getString("type"));
        assertEquals(120.0, out.getJSONArray("entities").getJSONObject(0).getDouble("x2"), 0.0001);
        assertEquals(12.5, out.getJSONArray("entities").getJSONObject(1).getDouble("r"), 0.0001);
        assertEquals(1.25, out.getJSONObject("view").getDouble("scale"), 0.0001);
    }

    @Test public void legacySketchMigratesToStableIdsAndRoundTripPreservesThem() throws Exception {
        JSONObject[] snapshots = onMain(() -> {
            Context context = ApplicationProvider.getApplicationContext();
            CadCanvasView canvas = new CadCanvasView(context);
            assertTrue(canvas.importSketchProjectState(sketchFixture()).contains("2"));
            JSONObject migrated = new JSONObject(canvas.exportSketchProjectState());
            assertEquals(2, migrated.getInt("schemaVersion"));
            JSONArray entities = migrated.getJSONArray("entities");
            String firstId = entities.getJSONObject(0).getString("id");
            String secondId = entities.getJSONObject(1).getString("id");
            assertFalse(firstId.isEmpty());
            assertFalse(secondId.isEmpty());
            assertFalse(firstId.equals(secondId));

            CadCanvasView reopened = new CadCanvasView(context);
            assertTrue(reopened.canImportSketchProjectState(migrated.toString()));
            assertTrue(reopened.importSketchProjectState(migrated.toString()).contains("2"));
            return new JSONObject[]{migrated, new JSONObject(reopened.exportSketchProjectState())};
        });
        JSONArray before = snapshots[0].getJSONArray("entities");
        JSONArray after = snapshots[1].getJSONArray("entities");
        assertEquals(before.getJSONObject(0).getString("id"), after.getJSONObject(0).getString("id"));
        assertEquals(before.getJSONObject(1).getString("id"), after.getJSONObject(1).getString("id"));
    }

    @Test public void schemaV2RejectsDuplicateOrMissingStableIdsBeforeMutation() throws Exception {
        onMain(() -> {
            Context context = ApplicationProvider.getApplicationContext();
            CadCanvasView canvas = new CadCanvasView(context);
            canvas.importSketchProjectState(sketchFixture());
            JSONObject validV2 = new JSONObject(canvas.exportSketchProjectState());
            JSONArray entities = validV2.getJSONArray("entities");
            String firstId = entities.getJSONObject(0).getString("id");

            JSONObject duplicate = new JSONObject(validV2.toString());
            duplicate.getJSONArray("entities").getJSONObject(1).put("id", firstId);
            assertFalse(canvas.canImportSketchProjectState(duplicate.toString()));

            JSONObject missing = new JSONObject(validV2.toString());
            missing.getJSONArray("entities").getJSONObject(0).remove("id");
            assertFalse(canvas.canImportSketchProjectState(missing.toString()));
            return true;
        });
    }

    @Test public void invalidSketchRestoreDoesNotMutateCurrentCanvas() throws Exception {
        JSONObject[] snapshots = onMain(() -> {
            Context context = ApplicationProvider.getApplicationContext();
            CadCanvasView canvas = new CadCanvasView(context);
            canvas.importSketchProjectState(sketchFixture());
            JSONObject before = new JSONObject(canvas.exportSketchProjectState());
            JSONObject bad = new JSONObject(sketchFixture());
            bad.getJSONArray("entities").getJSONObject(0).put("type", "UNKNOWN");
            assertFalse(canvas.canImportSketchProjectState(bad.toString()));
            assertTrue(canvas.importSketchProjectState(bad.toString()).contains("نامعتبر"));
            JSONObject after = new JSONObject(canvas.exportSketchProjectState());
            return new JSONObject[]{before, after};
        });
        JSONObject before = snapshots[0];
        JSONObject after = snapshots[1];
        assertEquals(before.getJSONArray("entities").length(), after.getJSONArray("entities").length());
        assertEquals(before.getJSONArray("entities").getJSONObject(0).getString("type"), after.getJSONArray("entities").getJSONObject(0).getString("type"));
        assertEquals(before.getJSONArray("entities").getJSONObject(0).getDouble("x2"), after.getJSONArray("entities").getJSONObject(0).getDouble("x2"), 0.0001);
    }
}
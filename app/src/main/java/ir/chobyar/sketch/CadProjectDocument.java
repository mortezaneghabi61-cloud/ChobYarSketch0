package ir.chobyar.sketch;

import org.json.JSONObject;

/**
 * Versioned on-disk project envelope.
 *
 * The first schema intentionally stores only the sketch/document shell. 3D
 * History is not silently flattened: callers must check scope before saving a
 * model that contains exact bodies. Later schemas can add featureGraph without
 * changing the SAF file contract.
 */
final class CadProjectDocument {
    static final String FORMAT = "chobyar-project";
    static final int SCHEMA_VERSION = 1;
    static final String SCOPE_SKETCH_V1 = "sketch-v1";

    static final class Decoded {
        final int schemaVersion;
        final String scope;
        final String unit;
        final String sketchState;

        Decoded(int schemaVersion, String scope, String unit, String sketchState) {
            this.schemaVersion = schemaVersion;
            this.scope = scope;
            this.unit = unit;
            this.sketchState = sketchState;
        }
    }

    private CadProjectDocument() {}

    static String encodeSketch(String sketchState) {
        if (sketchState == null || sketchState.trim().isEmpty()) {
            throw new IllegalArgumentException("Sketch state is empty");
        }
        JSONObject sketch = new JSONObject(sketchState);
        JSONObject root = new JSONObject();
        root.put("format", FORMAT);
        root.put("schemaVersion", SCHEMA_VERSION);
        root.put("scope", SCOPE_SKETCH_V1);
        root.put("unit", "mm");
        root.put("sketch", sketch);
        return root.toString();
    }

    static Decoded decode(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            throw new IllegalArgumentException("Project file is empty");
        }
        JSONObject root = new JSONObject(raw);
        if (!FORMAT.equals(root.optString("format", ""))) {
            throw new IllegalArgumentException("Unsupported project format");
        }
        int version = root.optInt("schemaVersion", -1);
        if (version < 1) throw new IllegalArgumentException("Missing project schema");
        if (version > SCHEMA_VERSION) {
            throw new IllegalArgumentException("Project was created by a newer ChobYar version");
        }
        String scope = root.optString("scope", "");
        if (!SCOPE_SKETCH_V1.equals(scope)) {
            throw new IllegalArgumentException("Unsupported project scope");
        }
        String unit = root.optString("unit", "");
        if (!"mm".equals(unit)) throw new IllegalArgumentException("Project unit must be mm");
        JSONObject sketch = root.optJSONObject("sketch");
        if (sketch == null) throw new IllegalArgumentException("Project sketch section is missing");
        return new Decoded(version, scope, unit, sketch.toString());
    }
}

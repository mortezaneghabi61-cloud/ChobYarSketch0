package ir.chobyar.sketch;

import org.json.JSONObject;

/**
 * Versioned on-disk project envelope.
 *
 * Schema v1 stores only the sketch/document shell. Schema v2 adds an exact-model
 * section containing logical plane/feature/topology references. OCCT handles and
 * display triangles are never serialized as model truth.
 */
final class CadProjectDocument {
    static final String FORMAT = "chobyar-project";
    static final int SCHEMA_VERSION = 2;
    static final int SKETCH_SCHEMA_VERSION = 1;
    static final String SCOPE_SKETCH_V1 = "sketch-v1";
    static final String SCOPE_MODEL_V2 = "model-v2";

    static final class Decoded {
        final int schemaVersion;
        final String scope;
        final String unit;
        final String sketchState;
        final String modelState;

        Decoded(int schemaVersion, String scope, String unit, String sketchState, String modelState) {
            this.schemaVersion = schemaVersion;
            this.scope = scope;
            this.unit = unit;
            this.sketchState = sketchState;
            this.modelState = modelState;
        }

        boolean hasExactModel(){return SCOPE_MODEL_V2.equals(scope)&&modelState!=null;}
    }

    private CadProjectDocument() {}

    static String encodeSketch(String sketchState) {
        JSONObject sketch = parseObject(sketchState, "Sketch state is empty", "Invalid sketch project state");
        try {
            JSONObject root = new JSONObject();
            root.put("format", FORMAT);
            root.put("schemaVersion", SKETCH_SCHEMA_VERSION);
            root.put("scope", SCOPE_SKETCH_V1);
            root.put("unit", "mm");
            root.put("sketch", sketch);
            return root.toString();
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid sketch project state", e);
        }
    }

    static String encodeModel(String sketchState,String modelState) {
        JSONObject sketch=parseObject(sketchState,"Sketch state is empty","Invalid sketch project state");
        JSONObject model=parseObject(modelState,"Model state is empty","Invalid model project state");
        try{
            JSONObject root=new JSONObject();
            root.put("format",FORMAT);
            root.put("schemaVersion",SCHEMA_VERSION);
            root.put("scope",SCOPE_MODEL_V2);
            root.put("unit","mm");
            root.put("sketch",sketch);
            root.put("model",model);
            return root.toString();
        }catch(Exception e){throw new IllegalArgumentException("Invalid model project state",e);}
    }

    static Decoded decode(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            throw new IllegalArgumentException("Project file is empty");
        }
        try {
            JSONObject root = new JSONObject(raw);
            if (!FORMAT.equals(root.optString("format", ""))) {
                throw new IllegalArgumentException("Unsupported project format");
            }
            int version = root.optInt("schemaVersion", -1);
            if (version < 1) throw new IllegalArgumentException("Missing project schema");
            if (version > SCHEMA_VERSION) {
                throw new IllegalArgumentException("Project was created by a newer ChobYar version");
            }
            String unit = root.optString("unit", "");
            if (!"mm".equals(unit)) throw new IllegalArgumentException("Project unit must be mm");
            JSONObject sketch = root.optJSONObject("sketch");
            if (sketch == null) throw new IllegalArgumentException("Project sketch section is missing");
            String scope = root.optString("scope", "");
            if(version==SKETCH_SCHEMA_VERSION&&SCOPE_SKETCH_V1.equals(scope)){
                return new Decoded(version,scope,unit,sketch.toString(),null);
            }
            if(version==SCHEMA_VERSION&&SCOPE_MODEL_V2.equals(scope)){
                JSONObject model=root.optJSONObject("model");
                if(model==null)throw new IllegalArgumentException("Project model section is missing");
                return new Decoded(version,scope,unit,sketch.toString(),model.toString());
            }
            throw new IllegalArgumentException("Unsupported project scope");
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Malformed ChobYar project file", e);
        }
    }

    private static JSONObject parseObject(String raw,String emptyMessage,String invalidMessage){
        if(raw==null||raw.trim().isEmpty())throw new IllegalArgumentException(emptyMessage);
        try{return new JSONObject(raw);}catch(Exception e){throw new IllegalArgumentException(invalidMessage,e);}
    }
}
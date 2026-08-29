from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CANVAS = ROOT / "app/src/main/java/ir/chobyar/sketch/CadCanvasView.java"
TEST = ROOT / "app/src/androidTest/java/ir/chobyar/sketch/ProjectPersistenceInstrumentationTest.java"


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: expected exactly one match, found {count}")
    return text.replace(old, new, 1)


text = CANVAS.read_text(encoding="utf-8")

text = replace_once(
    text,
    "import java.util.Map;\n",
    "import java.util.Map;\nimport java.util.HashSet;\nimport java.util.Set;\nimport java.util.UUID;\n",
    "imports",
)

text = replace_once(
    text,
    "        Entity c = selected.copy();\n        c.translate(dx, dy);",
    "        Entity c = selected.copy();\n        c.regenerateStableId();\n        c.translate(dx, dy);",
    "copySelected fresh id",
)

text = replace_once(
    text,
    "        saveUndo();\n        copyMeta(selected, o);\n        entities.add(o);",
    "        saveUndo();\n        copyMeta(selected, o);\n        o.regenerateStableId();\n        entities.add(o);",
    "offset fresh id",
)

text = replace_once(
    text,
    "            Entity c = seed.copy();\n            c.translate(dx * i, dy * i);",
    "            Entity c = seed.copy();\n            c.regenerateStableId();\n            c.translate(dx * i, dy * i);",
    "array fresh id",
)

text = replace_once(
    text,
    "            root.put(\"schemaVersion\",1);",
    "            root.put(\"schemaVersion\",2);",
    "sketch schema v2",
)

text = replace_once(
    text,
    "        if(root.optInt(\"schemaVersion\",-1)!=1)throw new IllegalArgumentException(\"schema\");",
    "        int schemaVersion=root.optInt(\"schemaVersion\",-1);\n        if(schemaVersion!=1&&schemaVersion!=2)throw new IllegalArgumentException(\"schema\");",
    "accept v1/v2 schema",
)

text = replace_once(
    text,
    "        org.json.JSONArray rows=root.getJSONArray(\"entities\");\n        List<Entity> restored=new ArrayList<>();\n        for(int i=0;i<rows.length();i++)restored.add(projectEntityFromJson(rows.getJSONObject(i)));",
    "        org.json.JSONArray rows=root.getJSONArray(\"entities\");\n        List<Entity> restored=new ArrayList<>();\n        Set<String> restoredIds=new HashSet<>();\n        for(int i=0;i<rows.length();i++){\n            org.json.JSONObject row=rows.getJSONObject(i);\n            if(schemaVersion>=2&&!row.has(\"id\"))throw new IllegalArgumentException(\"entity id\");\n            Entity restoredEntity=projectEntityFromJson(row);\n            String restoredId=restoredEntity.stableId();\n            if(!restoredIds.add(restoredId))throw new IllegalArgumentException(\"duplicate entity id\");\n            restored.add(restoredEntity);\n        }",
    "validate persisted ids",
)

text = replace_once(
    text,
    "        BaseEntity base=(BaseEntity)entity;org.json.JSONObject o=new org.json.JSONObject();\n        if(entity instanceof PointEntity)",
    "        BaseEntity base=(BaseEntity)entity;org.json.JSONObject o=new org.json.JSONObject();\n        o.put(\"id\",base.stableId());\n        if(entity instanceof PointEntity)",
    "serialize stable id",
)

text = replace_once(
    text,
    "        BaseEntity base=(BaseEntity)entity;base.layer=o.optString(\"layer\",\"0\");base.color=o.optInt(\"color\",Color.rgb(25,25,25));\n        base.extrusion=finiteFloat(o.optDouble(\"extrusion\",0));",
    "        BaseEntity base=(BaseEntity)entity;\n        String persistedId=o.optString(\"id\",\"\").trim();if(!persistedId.isEmpty())base.restoreStableId(persistedId);\n        base.layer=o.optString(\"layer\",\"0\");base.color=o.optInt(\"color\",Color.rgb(25,25,25));\n        base.extrusion=finiteFloat(o.optDouble(\"extrusion\",0));",
    "restore stable id",
)

text = replace_once(
    text,
    "        void setReferenceSource(int bodyId,int edgeIndex,int edgeKind);\n    }",
    "        void setReferenceSource(int bodyId,int edgeIndex,int edgeKind);\n        String stableId();\n        void regenerateStableId();\n    }",
    "entity identity contract",
)

text = replace_once(
    text,
    "    private abstract static class BaseEntity implements Entity{\n        String layer=\"0\";",
    "    private abstract static class BaseEntity implements Entity{\n        private String stableId=UUID.randomUUID().toString();\n        String layer=\"0\";",
    "base identity field",
)

text = replace_once(
    text,
    "        public void setReferenceSource(int bodyId,int edgeIndex,int edgeKind){referenceBodyId=bodyId;referenceEdgeIndex=edgeIndex;referenceEdgeKind=edgeKind;}\n        void meta(BaseEntity e){e.layer=layer;e.color=color;e.extrusion=extrusion;e.construction=construction;e.referenceBodyId=referenceBodyId;e.referenceEdgeIndex=referenceEdgeIndex;e.referenceEdgeKind=referenceEdgeKind;}",
    "        public void setReferenceSource(int bodyId,int edgeIndex,int edgeKind){referenceBodyId=bodyId;referenceEdgeIndex=edgeIndex;referenceEdgeKind=edgeKind;}\n        public String stableId(){return stableId;}\n        public void regenerateStableId(){stableId=UUID.randomUUID().toString();}\n        void restoreStableId(String value){String normalized=value==null?\"\":value.trim();if(normalized.isEmpty()||normalized.length()>128)throw new IllegalArgumentException(\"entity id\");stableId=normalized;}\n        void meta(BaseEntity e){e.stableId=stableId;e.layer=layer;e.color=color;e.extrusion=extrusion;e.construction=construction;e.referenceBodyId=referenceBodyId;e.referenceEdgeIndex=referenceEdgeIndex;e.referenceEdgeKind=referenceEdgeKind;}",
    "base identity behavior",
)

CANVAS.write_text(text, encoding="utf-8")


test = TEST.read_text(encoding="utf-8")
marker = "    @Test public void invalidSketchRestoreDoesNotMutateCurrentCanvas() throws Exception {\n"
insert = r'''    @Test public void legacySketchMigratesToStableIdsAndRoundTripPreservesThem() throws Exception {
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

'''
if test.count(marker) != 1:
    raise RuntimeError("test insertion marker not unique")
test = test.replace(marker, insert + marker, 1)
TEST.write_text(test, encoding="utf-8")

print("K3.2 stable-id bridge patch applied")

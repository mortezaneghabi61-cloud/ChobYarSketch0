package ir.chobyar.sketch;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class ExactModelProjectStateInstrumentationTest {

    private static String sketchFixture() throws Exception {
        return new JSONObject().put("schemaVersion",1).put("unit","mm")
                .put("currentLayer","0").put("currentColor",0xff202020).put("polygonSides",6)
                .put("view",new JSONObject().put("scale",1).put("offsetX",120).put("offsetY",160)
                        .put("grid",true).put("axes",true).put("guides",true).put("dimensions",true).put("snap",true).put("ortho",false))
                .put("layers",new JSONArray().put(new JSONObject().put("name","0").put("visible",true)))
                .put("entities",new JSONArray()).toString();
    }

    private static String modelFixture() throws Exception {
        JSONArray planes=new JSONArray().put(ExactModelProjectState.plane("0",Geometry3D.xy()));
        JSONArray sources=new JSONArray().put(0);
        JSONObject params=new JSONObject().put("heightMm",18.0);
        JSONArray features=new JSONArray().put(ExactModelProjectState.feature(1,"EXTRUDE","F1",sources,params));
        OcctTopologyRef.Ref ref=new OcctTopologyRef.Ref(OcctTopologyRef.EDGE,"B1:E4",52.5,
                new Geometry3D.Vec3(12,4,0),new Geometry3D.Vec3(1,0,0),.25,.5,.75,1,0);
        JSONArray direct=new JSONArray().put(ExactModelProjectState.directEdit(1,"FILLET","F1",2.5,null,ref));
        JSONObject camera=ExactModelProjectState.camera(true,38,24,1.25f,0,0,0,4,-2);
        return ExactModelProjectState.encode(planes,features,direct,camera);
    }

    @Test public void modelV2EnvelopeRoundTripKeepsSketchAndLogicalModel() throws Exception {
        String model=modelFixture();
        String encoded=CadProjectDocument.encodeModel(sketchFixture(),model);
        CadProjectDocument.Decoded decoded=CadProjectDocument.decode(encoded);
        assertEquals(2,decoded.schemaVersion);
        assertEquals(CadProjectDocument.SCOPE_MODEL_V2,decoded.scope);
        assertEquals("mm",decoded.unit);
        assertTrue(decoded.hasExactModel());
        assertEquals(1,new JSONObject(decoded.modelState).getJSONArray("features").length());
    }

    @Test public void schemaV1RemainsReadableAfterV2Upgrade() throws Exception {
        CadProjectDocument.Decoded decoded=CadProjectDocument.decode(CadProjectDocument.encodeSketch(sketchFixture()));
        assertEquals(1,decoded.schemaVersion);
        assertEquals(CadProjectDocument.SCOPE_SKETCH_V1,decoded.scope);
        assertFalse(decoded.hasExactModel());
        assertEquals(null,decoded.modelState);
    }

    @Test public void stableTopologyRefRoundTripsWithoutTraversalIndex() throws Exception {
        OcctTopologyRef.Ref source=new OcctTopologyRef.Ref(OcctTopologyRef.FACE,"B7:F2",144.75,
                new Geometry3D.Vec3(20,30,40),new Geometry3D.Vec3(0,0,1),.2,.3,.4,2,16.0);
        JSONObject json=ExactModelProjectState.topologyRef(source);
        String raw=json.toString();
        assertFalse(raw.contains("subshapeIndex"));
        assertFalse(raw.contains("handle"));
        OcctTopologyRef.Ref restored=ExactModelProjectState.topologyRefFromJson(json);
        assertEquals(source.kind,restored.kind);
        assertEquals(source.id,restored.id);
        assertEquals(source.measure,restored.measure,0.000001);
        assertEquals(source.signatureKind,restored.signatureKind);
        assertEquals(source.secondaryMeasure,restored.secondaryMeasure,0.000001);
    }

    @Test public void sketchPlaneRoundTripPreservesExactBasisAndOrigin() throws Exception {
        Geometry3D.Plane3D plane=new Geometry3D.Plane3D(new Geometry3D.Vec3(0,0,35),
                new Geometry3D.Vec3(1,0,0),new Geometry3D.Vec3(0,1,0),"Shelf +35");
        JSONObject row=ExactModelProjectState.plane("Shelf",plane);
        Geometry3D.Plane3D restored=ExactModelProjectState.planeFromJson(row);
        assertEquals(35f,restored.origin.z,0.0001f);
        assertEquals(1f,restored.u.x,0.0001f);
        assertEquals(1f,restored.v.y,0.0001f);
        assertEquals("Shelf +35",restored.label);
    }

    @Test public void modelStateRejectsNativeHandlesTraversalIndexesAndMeshes() throws Exception {
        JSONObject root=new JSONObject(modelFixture());
        root.getJSONArray("features").getJSONObject(0).getJSONObject("params").put("handle",123456L);
        try{ExactModelProjectState.decode(root.toString());fail("native handle must be rejected");}
        catch(IllegalArgumentException expected){assertTrue(expected.getMessage().contains("Transient"));}

        root=new JSONObject(modelFixture());
        root.getJSONArray("directEdits").getJSONObject(0).getJSONObject("target").put("subshapeIndex",17);
        try{ExactModelProjectState.decode(root.toString());fail("subshape traversal index must be rejected");}
        catch(IllegalArgumentException expected){assertTrue(expected.getMessage().contains("Transient"));}

        root=new JSONObject(modelFixture());root.put("mesh",new JSONArray().put(1).put(2).put(3));
        try{ExactModelProjectState.decode(root.toString());fail("display mesh must be rejected");}
        catch(IllegalArgumentException expected){assertTrue(expected.getMessage().contains("Transient"));}
    }

    @Test public void topologyDirectEditsFailClosedWithoutLogicalTarget() throws Exception {
        JSONObject root=new JSONObject(modelFixture());
        root.getJSONArray("directEdits").getJSONObject(0).remove("target");
        try{ExactModelProjectState.decode(root.toString());fail("topology edit without target must fail");}
        catch(IllegalArgumentException expected){assertTrue(expected.getMessage().contains("target"));}
    }

    @Test public void futureModelPayloadVersionFailsClosed() throws Exception {
        JSONObject root=new JSONObject(modelFixture()).put("modelVersion",ExactModelProjectState.MODEL_VERSION+1);
        try{ExactModelProjectState.decode(root.toString());fail("future model payload must fail");}
        catch(IllegalArgumentException expected){assertTrue(expected.getMessage().contains("version"));}
    }
}
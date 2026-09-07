package ir.chobyar.sketch;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

@RunWith(AndroidJUnit4.class)
public final class ModelOwnedConstructionPlaneInstrumentationTest {
    private static K33MirroredCadCanvasView canvas(){
        Context context=ApplicationProvider.getApplicationContext();
        return new K33MirroredCadCanvasView(context);
    }

    private static void onMain(Runnable task){
        final Throwable[] failure={null};InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{try{task.run();}catch(Throwable t){failure[0]=t;}});
        if(failure[0] instanceof AssertionError)throw (AssertionError)failure[0];if(failure[0] instanceof RuntimeException)throw (RuntimeException)failure[0];if(failure[0]!=null)throw new RuntimeException(failure[0]);
    }

    @Test public void createSketchOnBasePlanePersistsAcrossSaveOpen(){onMain(()->{
        K33MirroredCadCanvasView source=canvas();
        source.createSketchSpaceOnConstructionPlane("Front sketch","plane:xz");
        String id=source.activeConstructionPlaneId();
        String project=CadProjectPersistenceController.encode(source);
        K33MirroredCadCanvasView restored=canvas();
        CadProjectPersistenceController.restore(restored,project);
        assertEquals(id,restored.activeConstructionPlaneId());
    });}

    @Test public void createOffsetPlanePersistsAcrossSaveOpen(){onMain(()->{
        K33MirroredCadCanvasView source=canvas();
        source.createOffsetSketchSpace(18.25f,"Shelf datum");
        String id=source.activeConstructionPlaneId();
        String project=CadProjectPersistenceController.encode(source);
        K33MirroredCadCanvasView restored=canvas();
        CadProjectPersistenceController.restore(restored,project);
        assertEquals(id,restored.activeConstructionPlaneId());
        assertEquals(18.25,restored.constructionPlaneOffsetMm(id),0.0);
    });}

    @Test public void offsetPlaneUndoRedoKeepsStableIdentity(){onMain(()->{
        K33MirroredCadCanvasView cad=canvas();
        cad.createOffsetSketchSpace(9f,"Datum");
        String id=cad.activeConstructionPlaneId();
        assertTrue(cad.undoConstructionPlaneTransaction());
        assertFalse(cad.hasConstructionPlane(id));
        assertTrue(cad.redoConstructionPlaneTransaction());
        assertTrue(cad.hasConstructionPlane(id));
    });}

    @Test public void sketchPlaneRelationshipSurvivesActivityRecovery(){onMain(()->{
        K33MirroredCadCanvasView cad=canvas();
        cad.createOffsetSketchSpace(-3f,"Recovery datum");
        String project=CadProjectPersistenceController.encode(cad);
        WorkspaceRecoveryStore store=new WorkspaceRecoveryStore(ApplicationProvider.getApplicationContext());store.clear();store.save(project,"Recovery project");
        WorkspaceRecoveryStore.Snapshot snapshot=store.load();assertNotNull(snapshot);
        K33MirroredCadCanvasView restored=canvas();
        CadProjectPersistenceController.restore(restored,snapshot.payload);store.clear();
        assertEquals(cad.activeSketchStableId(),restored.activeSketchStableId());
        assertEquals(cad.activeConstructionPlaneId(),restored.activeConstructionPlaneId());
    });}

    @Test public void constructUiUsesModelOwnedPlanes(){onMain(()->{
        K33MirroredCadCanvasView cad=canvas();
        cad.createSketchSpaceOnConstructionPlane("Side sketch","plane:yz");
        assertEquals("plane:yz",cad.activeConstructionPlaneId());
        assertTrue(cad.activePlaneLabel().contains("YZ"));
    });}

    @Test public void drawDoesNotRepairOrMutatePlaneRelationships(){onMain(()->{
        K33MirroredCadCanvasView cad=canvas();
        long before=cad.constructionPlaneModelRevision();
        cad.drawForPlaneAuthorityTest();
        assertEquals(before,cad.constructionPlaneModelRevision());
    });}

    @Test public void planeVisibilityPersists(){onMain(()->{
        K33MirroredCadCanvasView source=canvas();
        source.createOffsetSketchSpace(6f,"Hidden datum");
        String id=source.activeConstructionPlaneId();
        source.setConstructionPlaneVisibility(id,false);
        String project=CadProjectPersistenceController.encode(source);
        K33MirroredCadCanvasView restored=canvas();
        CadProjectPersistenceController.restore(restored,project);
        assertFalse(restored.isConstructionPlaneVisible(id));
    });}

    @Test public void malformedPlaneDataFailsClosed(){onMain(()->{
        K33MirroredCadCanvasView cad=canvas();
        cad.createOffsetSketchSpace(2f,"Bad input target");String model=cad.exportConstructionPlaneModel();
        try { org.json.JSONObject root=new org.json.JSONObject(model);root.getJSONArray("planes").getJSONObject(0).put("origin",new org.json.JSONArray().put("NaN").put(0).put(0));model=root.toString(); }
        catch(org.json.JSONException e){throw new AssertionError(e);}
        try { canvas().importConstructionPlaneModel(model); fail("malformed plane must fail"); }
        catch (IllegalArgumentException expected) { assertNotNull(expected.getMessage()); }
    });}

    @Test public void modelSurvivesViewRecreationWithoutViewLocalAuthority(){onMain(()->{
        K33MirroredCadCanvasView source=canvas();
        source.createOffsetSketchSpace(11f,"Model datum");
        String model=source.exportConstructionPlaneModel();
        K33MirroredCadCanvasView recreated=canvas();
        recreated.importConstructionPlaneModel(model);
        assertEquals(source.activeConstructionPlaneId(),recreated.activeConstructionPlaneId());
        assertFalse(recreated.hasViewLocalPlaneAuthority());
    });}
}

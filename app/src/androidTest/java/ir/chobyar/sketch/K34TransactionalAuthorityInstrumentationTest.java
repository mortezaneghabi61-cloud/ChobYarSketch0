package ir.chobyar.sketch;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;

@RunWith(AndroidJUnit4.class)
public class K34TransactionalAuthorityInstrumentationTest {

    @Test public void moveUndoRedoUseSketchDocumentHistoryWithoutRehydrate() throws Exception {
        onMain(() -> {
            Context context=ApplicationProvider.getApplicationContext();
            K33MirroredCadCanvasView cad=new K33MirroredCadCanvasView(context);
            cad.importSketchProjectState(fixture());
            long syncBefore=cad.sketchMirrorSyncCount();

            cad.selected=cad.entities.get(0);
            cad.moveSelected(10f,5f);
            cad.requireSketchMirrorParity();
            assertTrue(cad.sketchAuthorityHistoryActive());
            assertTrue(cad.sketchAuthorityCanUndo());
            assertEquals(syncBefore+1,cad.sketchMirrorSyncCount());
            assertEquals(10.0,lineX1(cad.exportSketchProjectState()),1.0e-6);

            cad.undo();
            cad.requireSketchMirrorParity();
            assertEquals(0.0,lineX1(cad.exportSketchProjectState()),1.0e-6);
            assertTrue(cad.sketchAuthorityCanRedo());

            assertTrue(cad.redoSketch());
            cad.requireSketchMirrorParity();
            assertEquals(10.0,lineX1(cad.exportSketchProjectState()),1.0e-6);
            assertTrue(cad.sketchAuthorityTransitionCount()>=3);
            return true;
        });
    }

    @Test public void deleteUndoUsesSketchDocumentAsTransactionalAuthority() throws Exception {
        onMain(() -> {
            Context context=ApplicationProvider.getApplicationContext();
            K33MirroredCadCanvasView cad=new K33MirroredCadCanvasView(context);
            cad.importSketchProjectState(fixture());
            cad.selected=cad.entities.get(1);

            cad.deleteSelected();
            cad.requireSketchMirrorParity();
            assertTrue(cad.sketchAuthorityHistoryActive());
            assertTrue(cad.sketchAuthorityCanUndo());
            assertEquals(1,entityCount(cad.exportSketchProjectState()));

            cad.undo();
            cad.requireSketchMirrorParity();
            assertEquals(2,entityCount(cad.exportSketchProjectState()));
            return true;
        });
    }

    @Test public void copyUndoRedoUsesAuthorityChosenFreshStableId() throws Exception {
        onMain(() -> {
            Context context=ApplicationProvider.getApplicationContext();
            K33MirroredCadCanvasView cad=new K33MirroredCadCanvasView(context);
            cad.importSketchProjectState(fixture());
            Set<String> before=ids(cad.exportSketchProjectState());
            long syncBefore=cad.sketchMirrorSyncCount();

            cad.selected=cad.entities.get(0);
            cad.copySelected(20f,5f);
            cad.requireSketchMirrorParity();
            String copied=cad.exportSketchProjectState();
            Set<String> after=ids(copied);
            assertEquals(3,after.size());
            assertTrue(after.containsAll(before));
            LinkedHashSet<String> fresh=new LinkedHashSet<>(after);
            fresh.removeAll(before);
            assertEquals(1,fresh.size());
            String newId=fresh.iterator().next();
            assertFalse(newId.trim().isEmpty());
            assertTrue(cad.sketchAuthorityHistoryActive());
            assertTrue(cad.sketchAuthorityCanUndo());
            assertEquals(syncBefore+1,cad.sketchMirrorSyncCount());
            assertEquals(20.0,lineX1ById(copied,newId),1.0e-6);

            cad.undo();
            cad.requireSketchMirrorParity();
            assertEquals(before,ids(cad.exportSketchProjectState()));
            assertTrue(cad.sketchAuthorityCanRedo());

            assertTrue(cad.redoSketch());
            cad.requireSketchMirrorParity();
            assertEquals(after,ids(cad.exportSketchProjectState()));
            assertEquals(20.0,lineX1ById(cad.exportSketchProjectState(),newId),1.0e-6);
            return true;
        });
    }

    @Test public void copySaveReopenPreservesAuthorityChosenStableId() throws Exception {
        final String[] copiedId=new String[1];
        String saved=onMain(() -> {
            Context context=ApplicationProvider.getApplicationContext();
            K33MirroredCadCanvasView cad=new K33MirroredCadCanvasView(context);
            cad.importSketchProjectState(fixture());
            Set<String> before=ids(cad.exportSketchProjectState());
            cad.selected=cad.entities.get(0);
            cad.copySelected(30f,0f);
            cad.requireSketchMirrorParity();
            String out=cad.exportSketchProjectState();
            LinkedHashSet<String> fresh=new LinkedHashSet<>(ids(out));
            fresh.removeAll(before);
            assertEquals(1,fresh.size());
            copiedId[0]=fresh.iterator().next();
            assertEquals(30.0,lineX1ById(out,copiedId[0]),1.0e-6);
            return out;
        });

        onMain(() -> {
            Context context=ApplicationProvider.getApplicationContext();
            K33MirroredCadCanvasView reopened=new K33MirroredCadCanvasView(context);
            reopened.importSketchProjectState(saved);
            reopened.requireSketchMirrorParity();
            assertTrue(ids(reopened.exportSketchProjectState()).contains(copiedId[0]));
            assertEquals(30.0,lineX1ById(reopened.exportSketchProjectState(),copiedId[0]),1.0e-6);
            assertFalse(reopened.sketchAuthorityHistoryActive());
            return true;
        });
    }

    @Test public void transactionalMoveSaveReopenPreservesGeometryAndResetsHistory() throws Exception {
        String saved=onMain(() -> {
            Context context=ApplicationProvider.getApplicationContext();
            K33MirroredCadCanvasView cad=new K33MirroredCadCanvasView(context);
            cad.importSketchProjectState(fixture());
            cad.selected=cad.entities.get(0);
            cad.moveSelected(7f,3f);
            cad.requireSketchMirrorParity();
            assertTrue(cad.sketchAuthorityHistoryActive());
            assertTrue(cad.sketchAuthorityCanUndo());
            assertEquals(7.0,lineX1(cad.exportSketchProjectState()),1.0e-6);
            return cad.exportSketchProjectState();
        });

        onMain(() -> {
            Context context=ApplicationProvider.getApplicationContext();
            K33MirroredCadCanvasView reopened=new K33MirroredCadCanvasView(context);
            reopened.importSketchProjectState(saved);
            reopened.requireSketchMirrorParity();
            assertEquals(7.0,lineX1(reopened.exportSketchProjectState()),1.0e-6);
            assertFalse(reopened.sketchAuthorityHistoryActive());
            assertFalse(reopened.sketchAuthorityCanUndo());

            reopened.selected=reopened.entities.get(0);
            reopened.moveSelected(2f,0f);
            reopened.requireSketchMirrorParity();
            assertEquals(9.0,lineX1(reopened.exportSketchProjectState()),1.0e-6);
            assertTrue(reopened.sketchAuthorityHistoryActive());
            assertTrue(reopened.sketchAuthorityCanUndo());
            return true;
        });
    }

    @Test public void schemaV1MigratesToStableIdsBeforeAuthorityTransaction() throws Exception {
        String saved=onMain(() -> {
            Context context=ApplicationProvider.getApplicationContext();
            K33MirroredCadCanvasView cad=new K33MirroredCadCanvasView(context);
            assertTrue(cad.importSketchProjectState(fixtureV1()).contains("2"));
            String migrated=cad.exportSketchProjectState();
            assertEquals(2,new JSONObject(migrated).getInt("schemaVersion"));
            Set<String> migratedIds=ids(migrated);
            assertEquals(2,migratedIds.size());

            cad.selected=cad.entities.get(0);
            cad.moveSelected(3f,0f);
            cad.requireSketchMirrorParity();
            assertTrue(cad.sketchAuthorityHistoryActive());
            String out=cad.exportSketchProjectState();
            assertEquals(migratedIds,ids(out));
            assertEquals(3.0,firstLineX1(out),1.0e-6);
            return out;
        });

        onMain(() -> {
            Context context=ApplicationProvider.getApplicationContext();
            K33MirroredCadCanvasView reopened=new K33MirroredCadCanvasView(context);
            reopened.importSketchProjectState(saved);
            reopened.requireSketchMirrorParity();
            assertEquals(ids(saved),ids(reopened.exportSketchProjectState()));
            assertEquals(3.0,firstLineX1(reopened.exportSketchProjectState()),1.0e-6);
            return true;
        });
    }

    @Test public void lockedSelectionNeverPreMutatesSketchDocumentAuthority() throws Exception {
        onMain(() -> {
            Context context=ApplicationProvider.getApplicationContext();
            K33MirroredCadCanvasView cad=new K33MirroredCadCanvasView(context);
            cad.importSketchProjectState(fixture());
            cad.selected=cad.entities.get(0);
            assertTrue(cad.toggleSelectedLock().contains("Lock"));

            cad.moveSelected(12f,0f);
            cad.requireSketchMirrorParity();
            assertEquals(0.0,lineX1(cad.exportSketchProjectState()),1.0e-6);
            assertFalse(cad.sketchAuthorityHistoryActive());
            assertFalse(cad.sketchAuthorityCanUndo());
            return true;
        });
    }

    private static String fixture() throws Exception {
        JSONObject root=new JSONObject();
        root.put("schemaVersion",2);root.put("unit","mm");root.put("currentLayer","0");
        JSONArray entities=new JSONArray();
        entities.put(new JSONObject().put("id","line-authority").put("type","LINE")
                .put("x1",0).put("y1",0).put("x2",40).put("y2",0).put("layer","0"));
        entities.put(new JSONObject().put("id","circle-authority").put("type","CIRCLE")
                .put("x",80).put("y",40).put("r",15).put("layer","0"));
        root.put("entities",entities);
        return root.toString();
    }

    private static String fixtureV1() throws Exception {
        JSONObject root=new JSONObject();
        root.put("schemaVersion",1);root.put("unit","mm");root.put("currentLayer","0");
        JSONArray entities=new JSONArray();
        entities.put(new JSONObject().put("type","LINE")
                .put("x1",0).put("y1",0).put("x2",40).put("y2",0).put("layer","0"));
        entities.put(new JSONObject().put("type","CIRCLE")
                .put("x",80).put("y",40).put("r",15).put("layer","0"));
        root.put("entities",entities);
        return root.toString();
    }

    private static int entityCount(String raw) throws Exception {
        return new JSONObject(raw).getJSONArray("entities").length();
    }

    private static Set<String> ids(String raw) throws Exception {
        JSONArray rows=new JSONObject(raw).getJSONArray("entities");
        LinkedHashSet<String> out=new LinkedHashSet<>();
        for(int i=0;i<rows.length();i++){
            String id=rows.getJSONObject(i).getString("id").trim();
            assertFalse(id.isEmpty());
            assertTrue(out.add(id));
        }
        return out;
    }

    private static double lineX1(String raw) throws Exception {
        return lineX1ById(raw,"line-authority");
    }

    private static double lineX1ById(String raw,String id) throws Exception {
        JSONArray rows=new JSONObject(raw).getJSONArray("entities");
        for(int i=0;i<rows.length();i++){
            JSONObject row=rows.getJSONObject(i);
            if(id.equals(row.optString("id")) && "LINE".equals(row.optString("type"))) return row.getDouble("x1");
        }
        throw new AssertionError(id+" line missing");
    }

    private static double firstLineX1(String raw) throws Exception {
        JSONArray rows=new JSONObject(raw).getJSONArray("entities");
        for(int i=0;i<rows.length();i++){
            JSONObject row=rows.getJSONObject(i);
            if("LINE".equals(row.optString("type"))) return row.getDouble("x1");
        }
        throw new AssertionError("LINE missing");
    }

    private static <T> T onMain(Callable<T> callable) throws Exception {
        FutureTask<T> task=new FutureTask<>(callable);
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().runOnMainSync(task);
        return task.get();
    }
}

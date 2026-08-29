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
            assertEquals(syncBefore+1,cad.sketchMirrorSyncCount()); // one authority-chain hydration only
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

    @Test public void unsupportedCopyFallsBackAndInvalidatesTransactionalHistory() throws Exception {
        onMain(() -> {
            Context context=ApplicationProvider.getApplicationContext();
            K33MirroredCadCanvasView cad=new K33MirroredCadCanvasView(context);
            cad.importSketchProjectState(fixture());
            cad.selected=cad.entities.get(0);
            cad.moveSelected(4f,0f);
            assertTrue(cad.sketchAuthorityHistoryActive());
            assertTrue(cad.sketchAuthorityCanUndo());

            cad.copySelected(20f,0f);
            cad.requireSketchMirrorParity();
            assertFalse(cad.sketchAuthorityHistoryActive());
            assertFalse(cad.sketchAuthorityCanUndo());
            assertEquals(3,entityCount(cad.exportSketchProjectState()));
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

    private static int entityCount(String raw) throws Exception {
        return new JSONObject(raw).getJSONArray("entities").length();
    }

    private static double lineX1(String raw) throws Exception {
        JSONArray rows=new JSONObject(raw).getJSONArray("entities");
        for(int i=0;i<rows.length();i++){
            JSONObject row=rows.getJSONObject(i);
            if("line-authority".equals(row.optString("id"))) return row.getDouble("x1");
        }
        throw new AssertionError("line-authority missing");
    }

    private static <T> T onMain(Callable<T> callable) throws Exception {
        FutureTask<T> task=new FutureTask<>(callable);
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().runOnMainSync(task);
        return task.get();
    }
}

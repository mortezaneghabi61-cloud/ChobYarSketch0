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
public class K34CreateAuthorityInstrumentationTest {

    @Test public void exactLineCreateUsesDocumentHistoryAndStableIdAcrossUndoRedo() throws Exception {
        onMain(() -> {
            Context context=ApplicationProvider.getApplicationContext();
            K33MirroredCadCanvasView cad=new K33MirroredCadCanvasView(context);
            long syncBefore=cad.sketchMirrorSyncCount();

            assertTrue(cad.executeCommand("LINE 10 20 50 20").contains("خط"));
            cad.requireSketchMirrorParity();
            String created=cad.exportSketchProjectState();
            assertEquals(1,entityCount(created));
            String id=onlyId(created);
            assertEquals(10.0,lineX1ById(created,id),1.0e-6);
            assertTrue(cad.sketchAuthorityHistoryActive());
            assertTrue(cad.sketchAuthorityCanUndo());
            assertEquals(syncBefore+1,cad.sketchMirrorSyncCount());

            cad.undo();
            cad.requireSketchMirrorParity();
            assertEquals(0,entityCount(cad.exportSketchProjectState()));
            assertTrue(cad.sketchAuthorityCanRedo());

            assertTrue(cad.redoSketch());
            cad.requireSketchMirrorParity();
            String redone=cad.exportSketchProjectState();
            assertEquals(1,entityCount(redone));
            assertEquals(id,onlyId(redone));
            assertEquals(10.0,lineX1ById(redone,id),1.0e-6);
            return true;
        });
    }

    @Test public void exactPrimitiveSequenceStaysOnOneTransactionalAuthorityChain() throws Exception {
        onMain(() -> {
            Context context=ApplicationProvider.getApplicationContext();
            K33MirroredCadCanvasView cad=new K33MirroredCadCanvasView(context);
            long syncBefore=cad.sketchMirrorSyncCount();

            cad.executeCommand("POINT 5 6");
            cad.requireSketchMirrorParity();
            cad.executeCommand("LINE 0 0 20 0");
            cad.requireSketchMirrorParity();
            cad.executeCommand("RECT 30 10 40 25");
            cad.requireSketchMirrorParity();
            cad.executeCommand("CIRCLE 100 50 12");
            cad.requireSketchMirrorParity();
            cad.executeCommand("ARC 140 60 20 180 90");
            cad.requireSketchMirrorParity();

            String out=cad.exportSketchProjectState();
            assertEquals(5,entityCount(out));
            assertEquals(5,ids(out).size());
            assertTrue(cad.sketchAuthorityHistoryActive());
            assertTrue(cad.sketchAuthorityCanUndo());
            // One bridge hydration at the first model-authority boundary only.
            assertEquals(syncBefore+1,cad.sketchMirrorSyncCount());
            assertTrue(cad.sketchAuthorityTransitionCount()>=5);

            cad.undo();
            cad.requireSketchMirrorParity();
            assertEquals(4,entityCount(cad.exportSketchProjectState()));
            cad.undo();
            cad.requireSketchMirrorParity();
            assertEquals(3,entityCount(cad.exportSketchProjectState()));
            return true;
        });
    }

    @Test public void exactCircleCreateSaveReopenPreservesAuthorityStableId() throws Exception {
        final String[] stableId=new String[1];
        String saved=onMain(() -> {
            Context context=ApplicationProvider.getApplicationContext();
            K33MirroredCadCanvasView cad=new K33MirroredCadCanvasView(context);
            cad.executeCommand("CIRCLE 25 35 9");
            cad.requireSketchMirrorParity();
            String out=cad.exportSketchProjectState();
            stableId[0]=onlyId(out);
            assertEquals(9.0,circleRadiusById(out,stableId[0]),1.0e-6);
            assertTrue(cad.sketchAuthorityHistoryActive());
            return out;
        });

        onMain(() -> {
            Context context=ApplicationProvider.getApplicationContext();
            K33MirroredCadCanvasView reopened=new K33MirroredCadCanvasView(context);
            reopened.importSketchProjectState(saved);
            reopened.requireSketchMirrorParity();
            String out=reopened.exportSketchProjectState();
            assertTrue(ids(out).contains(stableId[0]));
            assertEquals(9.0,circleRadiusById(out,stableId[0]),1.0e-6);
            assertFalse(reopened.sketchAuthorityHistoryActive());
            return true;
        });
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

    private static String onlyId(String raw) throws Exception {
        Set<String> values=ids(raw);
        assertEquals(1,values.size());
        return values.iterator().next();
    }

    private static double lineX1ById(String raw,String id) throws Exception {
        JSONArray rows=new JSONObject(raw).getJSONArray("entities");
        for(int i=0;i<rows.length();i++){
            JSONObject row=rows.getJSONObject(i);
            if(id.equals(row.optString("id"))&&"LINE".equals(row.optString("type"))) return row.getDouble("x1");
        }
        throw new AssertionError(id+" line missing");
    }

    private static double circleRadiusById(String raw,String id) throws Exception {
        JSONArray rows=new JSONObject(raw).getJSONArray("entities");
        for(int i=0;i<rows.length();i++){
            JSONObject row=rows.getJSONObject(i);
            if(id.equals(row.optString("id"))&&"CIRCLE".equals(row.optString("type"))) return row.getDouble("r");
        }
        throw new AssertionError(id+" circle missing");
    }

    private static <T> T onMain(Callable<T> callable) throws Exception {
        FutureTask<T> task=new FutureTask<>(callable);
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().runOnMainSync(task);
        return task.get();
    }
}

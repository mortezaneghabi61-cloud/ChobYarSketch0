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
public class K33SketchMirrorInstrumentationTest {

    @Test public void copyArrayOffsetCreateFreshIdsAndMirrorParity() throws Exception {
        onMain(() -> {
            Context context=ApplicationProvider.getApplicationContext();
            K33MirroredCadCanvasView cad=new K33MirroredCadCanvasView(context);
            assertTrue(cad.importSketchProjectState(fixture()).contains("2"));
            cad.requireSketchMirrorParity();

            cad.selected=cad.entities.get(0);
            String original=cad.selected.stableId();
            cad.copySelected(10f,5f);
            cad.requireSketchMirrorParity();
            Set<String> afterCopy=ids(cad.exportSketchProjectState());
            assertEquals(3,afterCopy.size());
            assertTrue(afterCopy.contains(original));

            cad.selected=cad.entities.get(0);
            cad.arraySelected(3,20f,0f);
            cad.requireSketchMirrorParity();
            Set<String> afterArray=ids(cad.exportSketchProjectState());
            assertEquals(5,afterArray.size());

            cad.selected=cad.entities.get(1); // circle supports geometric offset
            String beforeOffset=cad.selected.stableId();
            assertTrue(cad.offsetSelected(5f).startsWith("Offset"));
            cad.requireSketchMirrorParity();
            Set<String> afterOffset=ids(cad.exportSketchProjectState());
            assertEquals(6,afterOffset.size());
            assertTrue(afterOffset.contains(beforeOffset));
            assertEquals(afterOffset.size(),new LinkedHashSet<>(afterOffset).size());
            return true;
        });
    }

    @Test public void undoRedoPreserveStableIdentityAndMirrorParity() throws Exception {
        onMain(() -> {
            Context context=ApplicationProvider.getApplicationContext();
            K33MirroredCadCanvasView cad=new K33MirroredCadCanvasView(context);
            cad.importSketchProjectState(fixture());
            cad.selected=cad.entities.get(0);
            cad.copySelected(25f,0f);
            Set<String> committed=ids(cad.exportSketchProjectState());
            assertEquals(3,committed.size());
            cad.requireSketchMirrorParity();

            cad.undo();
            assertEquals(2,ids(cad.exportSketchProjectState()).size());
            cad.requireSketchMirrorParity();

            assertTrue(cad.redoSketch());
            assertEquals(committed,ids(cad.exportSketchProjectState()));
            cad.requireSketchMirrorParity();
            return true;
        });
    }

    @Test public void saveReopenPreservesIdsAndHydratesSketchDocument() throws Exception {
        String saved=onMain(() -> {
            Context context=ApplicationProvider.getApplicationContext();
            K33MirroredCadCanvasView cad=new K33MirroredCadCanvasView(context);
            cad.importSketchProjectState(fixture());
            cad.selected=cad.entities.get(0);
            assertTrue(cad.applySelectedDimension("55").contains("55"));
            cad.requireSketchMirrorParity();
            return cad.exportSketchProjectState();
        });
        Set<String> before=ids(saved);
        onMain(() -> {
            Context context=ApplicationProvider.getApplicationContext();
            K33MirroredCadCanvasView reopened=new K33MirroredCadCanvasView(context);
            assertTrue(reopened.importSketchProjectState(saved).contains("2"));
            reopened.requireSketchMirrorParity();
            assertEquals(before,ids(reopened.exportSketchProjectState()));
            assertFalse(reopened.sketchMirrorEntities().isEmpty());
            assertTrue(reopened.sketchMirrorSyncCount()>0);
            return true;
        });
    }

    private static String fixture() throws Exception {
        JSONObject root=new JSONObject();
        root.put("schemaVersion",2);root.put("unit","mm");root.put("currentLayer","0");
        JSONArray entities=new JSONArray();
        entities.put(new JSONObject().put("id","line-original").put("type","LINE")
                .put("x1",0).put("y1",0).put("x2",40).put("y2",0).put("layer","0"));
        entities.put(new JSONObject().put("id","circle-original").put("type","CIRCLE")
                .put("x",80).put("y",40).put("r",15).put("layer","0"));
        root.put("entities",entities);
        return root.toString();
    }

    private static Set<String> ids(String raw) throws Exception {
        JSONArray rows=new JSONObject(raw).getJSONArray("entities");
        LinkedHashSet<String> out=new LinkedHashSet<>();
        for(int i=0;i<rows.length();i++){
            String id=rows.getJSONObject(i).getString("id");
            assertFalse(id.trim().isEmpty());
            assertTrue(out.add(id));
        }
        return out;
    }

    private static <T> T onMain(Callable<T> callable) throws Exception {
        FutureTask<T> task=new FutureTask<>(callable);
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().runOnMainSync(task);
        return task.get();
    }
}

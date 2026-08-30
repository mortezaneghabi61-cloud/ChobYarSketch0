package ir.chobyar.sketch;

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

import static org.junit.Assert.*;

/**
 * K3.6d persistence/history fence for stable endpoint references.
 *
 * Save/Open must preserve stable entity IDs plus point-index relationship
 * provenance exactly, while both model and legacy session Undo/Redo histories
 * are reset at the project-open boundary. This is required before these refs
 * can safely feed Feature History or projected-reference ownership.
 */
@RunWith(AndroidJUnit4.class)
public class K36dSaveOpenRelationshipHistoryInstrumentationTest {

    @Test public void saveOpenPreservesEndpointRelationshipAndResetsSessionHistory() throws Exception {
        onMain(() -> {
            K33MirroredCadCanvasView source=canvas();
            source.executeCommand("LINE 1.25 2.5 11.25 2.5");
            String anchorId=source.selected.stableId();
            source.executeCommand("LINE 11.25 2.5 21.75 8.5");
            String drivenId=source.selected.stableId();

            ConstraintInteractionContract.Result applied=
                    source.applyModelCoincidentForTest(drivenId,0,anchorId,1);
            assertEquals(ConstraintInteractionContract.ResultCode.APPLIED,applied.code);
            source.requireSketchMirrorParity();

            String saved=source.exportSketchProjectState();
            JSONObject savedRoot=new JSONObject(saved);
            assertEquals(2,savedRoot.optInt("schemaVersion",-1));
            assertEquals(1,savedRoot.optInt("modelConstraintSchemaVersion",-1));
            assertEquals(1,savedRoot.getJSONArray("modelConstraints").length());
            Set<String> savedIds=ids(savedRoot);
            assertTrue(savedIds.contains(anchorId));
            assertTrue(savedIds.contains(drivenId));

            K33MirroredCadCanvasView reopened=canvas();
            reopened.executeCommand("LINE 100 100 110 100");
            reopened.moveSelected(5f,0f);
            assertTrue("pre-open legacy Undo should exist",reopened.canUndoSketch());

            String result=reopened.importSketchProjectState(saved);
            assertFalse(result.toLowerCase(java.util.Locale.US).contains("could not be restored"));
            reopened.requireSketchMirrorParity();

            JSONObject afterRoot=new JSONObject(reopened.exportSketchProjectState());
            assertEquals(savedIds,ids(afterRoot));
            assertEquals(1,reopened.sketchConstraintCount());

            JSONObject relation=afterRoot.getJSONArray("modelConstraints").getJSONObject(0);
            assertEquals(drivenId,relation.getString("primaryEntityId"));
            assertEquals(0,relation.getInt("primaryPointIndex"));
            assertEquals(anchorId,relation.getString("secondaryEntityId"));
            assertEquals(1,relation.getInt("secondaryPointIndex"));

            // Open is a persistence boundary, never a restoration of editing-session history.
            assertFalse("model Undo must reset on Open",reopened.sketchAuthorityCanUndo());
            assertFalse("model Redo must reset on Open",reopened.sketchAuthorityCanRedo());
            assertFalse("legacy Undo must reset on Open",reopened.canUndoSketch());
            assertFalse("legacy Redo must reset on Open",reopened.canRedoSketch());

            // No accidental unit rescale or ID churn across the round trip.
            assertEntityCoordinates(savedRoot,afterRoot,anchorId);
            assertEntityCoordinates(savedRoot,afterRoot,drivenId);
            return true;
        });
    }

    private static void assertEntityCoordinates(JSONObject before,JSONObject after,String id) throws Exception {
        JSONObject a=entity(before,id),b=entity(after,id);
        assertEquals(a.getString("type"),b.getString("type"));
        for(String key:new String[]{"x1","y1","x2","y2","x","y","r","start","sweep"}) {
            if(a.has(key)||b.has(key)) {
                assertTrue("coordinate field disappeared: "+key,a.has(key)&&b.has(key));
                assertEquals("coordinate changed: "+key,a.getDouble(key),b.getDouble(key),1.0e-6);
            }
        }
    }

    private static JSONObject entity(JSONObject root,String id) throws Exception {
        JSONArray rows=root.getJSONArray("entities");
        for(int i=0;i<rows.length();i++) {
            JSONObject row=rows.getJSONObject(i);
            if(id.equals(row.getString("id"))) return row;
        }
        fail("Missing entity id after Save/Open: "+id);
        return null;
    }

    private static Set<String> ids(JSONObject root) throws Exception {
        JSONArray rows=root.getJSONArray("entities");
        LinkedHashSet<String> out=new LinkedHashSet<>();
        for(int i=0;i<rows.length();i++) out.add(rows.getJSONObject(i).getString("id"));
        return out;
    }

    private static K33MirroredCadCanvasView canvas() {
        Context context=ApplicationProvider.getApplicationContext();
        K33MirroredCadCanvasView view=new K33MirroredCadCanvasView(context);
        view.clearAll();
        return view;
    }

    private static <T>T onMain(Callable<T> callable) throws Exception {
        FutureTask<T> task=new FutureTask<>(callable);
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().runOnMainSync(task);
        return task.get();
    }
}

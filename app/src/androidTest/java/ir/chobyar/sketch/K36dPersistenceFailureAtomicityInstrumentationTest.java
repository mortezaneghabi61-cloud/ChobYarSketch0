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
 * Persistence/reference regression fence for K3.6d.
 *
 * Opening a project is a transaction boundary. A model-owned relationship that
 * fails validation must not leave the legacy View on the incoming geometry while
 * SketchDocument remains on the old geometry. That split authority would make
 * stable IDs unsafe for future Feature History/projected references.
 */
@RunWith(AndroidJUnit4.class)
public class K36dPersistenceFailureAtomicityInstrumentationTest {

    @Test public void danglingModelReferenceRejectsOpenWithoutMutatingCurrentDocument() throws Exception {
        onMain(() -> {
            K33MirroredCadCanvasView current=canvas();
            current.executeCommand("LINE 0 0 10 0");
            String before=current.exportSketchProjectState();
            Set<String> beforeIds=ids(before);
            assertEquals(1,beforeIds.size());

            K33MirroredCadCanvasView incoming=canvas();
            incoming.executeCommand("LINE 100 100 120 100");
            String incomingId=incoming.selected.stableId();
            JSONObject malformed=new JSONObject(incoming.exportSketchProjectState());
            JSONArray constraints=new JSONArray();
            JSONObject dangling=new JSONObject();
            dangling.put("id","dangling-point-on-entity");
            dangling.put("kind","POINT_ON_ENTITY");
            dangling.put("primaryEntityId",incomingId);
            dangling.put("primaryPointIndex",0);
            dangling.put("secondaryEntityId","missing-host-id");
            dangling.put("secondaryPointIndex",-1);
            dangling.put("driving",true);
            constraints.put(dangling);
            malformed.put("modelConstraintSchemaVersion",1);
            malformed.put("modelConstraints",constraints);

            String result=current.importSketchProjectState(malformed.toString());
            assertTrue(result.toLowerCase(java.util.Locale.US).contains("could not be restored"));

            // Failed Open must be atomic across both authorities.
            current.requireSketchMirrorParity();
            Set<String> afterIds=ids(current.exportSketchProjectState());
            assertEquals(beforeIds,afterIds);
            assertFalse(afterIds.contains(incomingId));
            assertEquals(0,current.sketchConstraintCount());
            return true;
        });
    }

    private static K33MirroredCadCanvasView canvas() {
        Context context=ApplicationProvider.getApplicationContext();
        K33MirroredCadCanvasView view=new K33MirroredCadCanvasView(context);
        view.clearAll();
        return view;
    }

    private static Set<String> ids(String raw) throws Exception {
        JSONArray rows=new JSONObject(raw).getJSONArray("entities");
        LinkedHashSet<String> out=new LinkedHashSet<>();
        for(int i=0;i<rows.length();i++) out.add(rows.getJSONObject(i).getString("id"));
        return out;
    }

    private static <T>T onMain(Callable<T> callable) throws Exception {
        FutureTask<T> task=new FutureTask<>(callable);
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().runOnMainSync(task);
        return task.get();
    }
}

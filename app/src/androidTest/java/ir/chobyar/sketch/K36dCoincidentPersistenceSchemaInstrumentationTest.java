package ir.chobyar.sketch;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Persistence compatibility fence for K3.6d COINCIDENT endpoint authority.
 *
 * K3.6d gives persisted primary/secondary endpoint ordering directional solver
 * meaning (primary is driven, secondary is target). That is a schema semantic
 * change and must not ship under the same modelConstraintSchemaVersion used by
 * older builds where that role meaning was not part of the persisted contract.
 *
 * This test intentionally requires a schema bump before the directed endpoint
 * semantics can be considered persistence-safe for Save/Open, projected refs,
 * or future Feature History dependencies.
 */
@RunWith(AndroidJUnit4.class)
public class K36dCoincidentPersistenceSchemaInstrumentationTest {

    @Test public void directedCoincidentSemanticsRequireConstraintSchemaV2() throws Exception {
        onMain(() -> {
            K33MirroredCadCanvasView view = canvas();
            view.executeCommand("LINE 0 0 10 0");

            JSONObject root = new JSONObject(view.exportSketchProjectState());

            assertEquals(2, root.getInt("schemaVersion"));
            assertTrue("K3.6d directed COINCIDENT roles require model-constraint schema v2+",
                    root.getInt("modelConstraintSchemaVersion") >= 2);
            return true;
        });
    }

    private static K33MirroredCadCanvasView canvas() {
        Context context = ApplicationProvider.getApplicationContext();
        K33MirroredCadCanvasView view = new K33MirroredCadCanvasView(context);
        view.clearAll();
        return view;
    }

    private static <T> T onMain(Callable<T> callable) throws Exception {
        FutureTask<T> task = new FutureTask<>(callable);
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().runOnMainSync(task);
        return task.get();
    }
}

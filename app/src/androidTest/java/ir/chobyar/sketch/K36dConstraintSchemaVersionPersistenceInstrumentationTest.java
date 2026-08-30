package ir.chobyar.sketch;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;

import static org.junit.Assert.*;

/**
 * Persistence fence for K3.6d model-owned relationships.
 *
 * Unknown model-constraint schema versions must fail closed before project-open
 * mutates legacy geometry or SketchDocument state. Silently accepting a newer
 * relationship schema would make stable entity-id + point-index references
 * unsafe for future Feature History/projected-reference migrations.
 */
@RunWith(AndroidJUnit4.class)
public class K36dConstraintSchemaVersionPersistenceInstrumentationTest {

    @Test public void unknownModelConstraintSchemaRejectsOpenAtomically() throws Exception {
        onMain(() -> {
            K33MirroredCadCanvasView current = canvas();
            current.executeCommand("LINE 0 0 10 0");
            String before = current.exportSketchProjectState();
            String beforeId = current.selected.stableId();

            K33MirroredCadCanvasView incoming = canvas();
            incoming.executeCommand("LINE 100 100 120 100");
            JSONObject unsupported = new JSONObject(incoming.exportSketchProjectState());
            unsupported.put("modelConstraintSchemaVersion", 999);

            String result = current.importSketchProjectState(unsupported.toString());

            assertTrue(result.toLowerCase(java.util.Locale.US).contains("could not be restored"));
            current.requireSketchMirrorParity();
            assertEquals(before, current.exportSketchProjectState());
            assertNotNull(current.sketchMirrorEntities().stream()
                    .filter(e -> beforeId.equals(e.id())).findFirst().orElse(null));
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

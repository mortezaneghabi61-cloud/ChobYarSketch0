package ir.chobyar.sketch;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;

import ir.chobyar.sketch.core.SketchConstraint;

/**
 * K3.8 production integration fence.
 *
 * Lock/Unlock must be model-owned by stable entity IDs. The inherited legacy
 * IdentityHashMap<Object,...> lock store is not persistence/history-safe and
 * must never be semantic truth for the installable K33 production canvas.
 */
@RunWith(AndroidJUnit4.class)
public class K38ProductionLockAuthorityInstrumentationTest {

    @Test public void selectionLockCreatesStableFixedConstraintAndSurvivesSaveOpen() throws Exception {
        onMain(() -> {
            K33MirroredCadCanvasView cad = cad();
            cad.executeCommand("LINE 0 0 100 25");
            String entityId = cad.selected.stableId();

            cad.toggleSelectedLock();
            cad.requireSketchMirrorParity();

            SketchConstraint fixed = fixedFor(cad, entityId);
            assertTrue(fixed.fixesWholeEntity());
            assertEquals(entityId, fixed.primaryEntityId);
            assertTrue(cad.sketchAuthorityCanUndo());

            String raw = cad.exportSketchProjectState();
            K33MirroredCadCanvasView reopened = cad();
            reopened.importSketchProjectState(raw);
            reopened.requireSketchMirrorParity();

            SketchConstraint restored = fixedFor(reopened, entityId);
            assertTrue(restored.fixesWholeEntity());
            assertEquals(entityId, restored.primaryEntityId);
            assertFalse("Open must reset session Undo while preserving FIXED relationship",
                    reopened.sketchAuthorityCanUndo());
            assertFalse(reopened.sketchAuthorityCanRedo());
            return true;
        });
    }

    private static SketchConstraint fixedFor(K33MirroredCadCanvasView cad, String entityId) {
        for (SketchConstraint c : cad.sketchConstraints()) {
            if (c.kind == SketchConstraint.Kind.FIXED && entityId.equals(c.primaryEntityId)) return c;
        }
        throw new AssertionError("Lock UI did not create model-owned FIXED for " + entityId);
    }

    private static K33MirroredCadCanvasView cad() {
        Context context = ApplicationProvider.getApplicationContext();
        return new K33MirroredCadCanvasView(context);
    }

    private static <T> T onMain(Callable<T> callable) throws Exception {
        FutureTask<T> task = new FutureTask<>(callable);
        android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
        handler.post(task);
        return task.get();
    }
}

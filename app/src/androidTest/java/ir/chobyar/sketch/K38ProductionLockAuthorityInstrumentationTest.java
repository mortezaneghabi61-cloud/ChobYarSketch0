package ir.chobyar.sketch;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.view.MotionEvent;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;

import ir.chobyar.sketch.core.SketchConstraint;
import ir.chobyar.sketch.core.SketchEntity;
import ir.chobyar.sketch.core.SketchGeometry;

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
            assertEquals("Legacy object-identity Lock truth must start empty", 0,
                    cad.legacySelectionLockTruthCount());

            cad.toggleSelectedLock();
            cad.requireSketchMirrorParity();

            SketchConstraint fixed = fixedFor(cad, entityId);
            assertTrue(fixed.fixesWholeEntity());
            assertEquals(entityId, fixed.primaryEntityId);
            assertTrue(cad.sketchAuthorityCanUndo());
            assertEquals("Production Lock must not populate legacy object-identity truth", 0,
                    cad.legacySelectionLockTruthCount());

            String raw = cad.exportSketchProjectState();
            K33MirroredCadCanvasView reopened = cad();
            reopened.importSketchProjectState(raw);
            reopened.requireSketchMirrorParity();

            SketchConstraint restored = fixedFor(reopened, entityId);
            assertTrue(restored.fixesWholeEntity());
            assertEquals(entityId, restored.primaryEntityId);
            assertEquals("Reopen must not reconstruct legacy object-identity Lock truth", 0,
                    reopened.legacySelectionLockTruthCount());
            assertFalse("Open must reset session Undo while preserving FIXED relationship",
                    reopened.sketchAuthorityCanUndo());
            assertFalse(reopened.sketchAuthorityCanRedo());
            return true;
        });
    }

    @Test public void reopenedFixedGeometryRejectsLegacyTouchMutationAsSemanticTruth() throws Exception {
        onMain(() -> {
            K33MirroredCadCanvasView cad = cad();
            cad.executeCommand("LINE 0 0 100 25");
            String entityId = cad.selected.stableId();
            cad.toggleSelectedLock();
            String raw = cad.exportSketchProjectState();

            K33MirroredCadCanvasView reopened = cad();
            reopened.importSketchProjectState(raw);
            reopened.requireSketchMirrorParity();
            SketchGeometry.Line before = lineFor(reopened, entityId);

            // Selection is session/UI state and intentionally is not persisted across Open.
            // Locate the reopened legacy object only by the model's stable entity ID so this
            // regression never makes Java object identity part of the persistence contract.
            CadCanvasView.Entity legacy = legacyEntityFor(reopened, entityId);
            assertNotNull("Open must recreate the stable-ID legacy projection", legacy);

            // Simulate a stale legacy handle/touch path mutating View geometry directly.
            // The model-owned FIXED relation must win at the next interaction boundary.
            legacy.moveControlPoint(0, 77f, 88f);
            long now = android.os.SystemClock.uptimeMillis();
            MotionEvent cancel = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0f, 0f, 0);
            try {
                reopened.onTouchEvent(cancel);
            } finally {
                cancel.recycle();
            }

            reopened.requireSketchMirrorParity();
            SketchGeometry.Line after = lineFor(reopened, entityId);
            assertEquals(before.a.xMm, after.a.xMm, 1.0e-9);
            assertEquals(before.a.yMm, after.a.yMm, 1.0e-9);
            assertEquals(before.b.xMm, after.b.xMm, 1.0e-9);
            assertEquals(before.b.yMm, after.b.yMm, 1.0e-9);
            assertTrue(fixedFor(reopened, entityId).fixesWholeEntity());
            assertFalse("Rejected stale View mutation must not manufacture session Undo",
                    reopened.sketchAuthorityCanUndo());
            return true;
        });
    }

    @Test public void modelOwnedLockBlocksLegacyTransformCommandsAndPreservesSingleUndo() throws Exception {
        onMain(() -> {
            K33MirroredCadCanvasView cad = cad();
            cad.executeCommand("LINE 0 0 100 25");
            String entityId = cad.selected.stableId();
            cad.toggleSelectedLock();
            cad.requireSketchMirrorParity();
            SketchGeometry.Line locked = lineFor(cad, entityId);
            assertTrue("Lock must be the current model-owned Undo step", cad.sketchAuthorityCanUndo());
            assertEquals(0, cad.legacySelectionLockTruthCount());

            cad.rotateSelected(30f);
            cad.scaleSelected(1.5f);
            cad.requireSketchMirrorParity();

            SketchGeometry.Line afterBlockedTransforms = lineFor(cad, entityId);
            assertEquals(locked.a.xMm, afterBlockedTransforms.a.xMm, 1.0e-9);
            assertEquals(locked.a.yMm, afterBlockedTransforms.a.yMm, 1.0e-9);
            assertEquals(locked.b.xMm, afterBlockedTransforms.b.xMm, 1.0e-9);
            assertEquals(locked.b.yMm, afterBlockedTransforms.b.yMm, 1.0e-9);
            assertTrue("Rejected transforms must not consume or fabricate Undo", cad.sketchAuthorityCanUndo());
            assertTrue(fixedFor(cad, entityId).fixesWholeEntity());
            assertEquals("Transform guard must not repopulate legacy object-identity truth", 0,
                    cad.legacySelectionLockTruthCount());

            cad.undo();
            cad.requireSketchMirrorParity();
            assertFalse("One Undo after rejected transforms must remove Lock", hasFixedFor(cad, entityId));

            SketchGeometry.Line beforeUnlockedRotate = lineFor(cad, entityId);
            cad.rotateSelected(30f);
            cad.requireSketchMirrorParity();
            SketchGeometry.Line afterUnlockedRotate = lineFor(cad, entityId);
            boolean changed = Math.abs(beforeUnlockedRotate.a.xMm - afterUnlockedRotate.a.xMm) > 1.0e-9
                    || Math.abs(beforeUnlockedRotate.a.yMm - afterUnlockedRotate.a.yMm) > 1.0e-9
                    || Math.abs(beforeUnlockedRotate.b.xMm - afterUnlockedRotate.b.xMm) > 1.0e-9
                    || Math.abs(beforeUnlockedRotate.b.yMm - afterUnlockedRotate.b.yMm) > 1.0e-9;
            assertTrue("Rotate must succeed after model-owned Unlock", changed);
            return true;
        });
    }

    private static SketchConstraint fixedFor(K33MirroredCadCanvasView cad, String entityId) {
        for (SketchConstraint c : cad.sketchConstraints()) {
            if (c.kind == SketchConstraint.Kind.FIXED && entityId.equals(c.primaryEntityId)) return c;
        }
        throw new AssertionError("Lock UI did not create model-owned FIXED for " + entityId);
    }

    private static boolean hasFixedFor(K33MirroredCadCanvasView cad, String entityId) {
        for (SketchConstraint c : cad.sketchConstraints()) {
            if (c.kind == SketchConstraint.Kind.FIXED && entityId.equals(c.primaryEntityId)) return true;
        }
        return false;
    }

    private static SketchGeometry.Line lineFor(K33MirroredCadCanvasView cad, String entityId) {
        for (SketchEntity entity : cad.sketchMirrorEntities()) {
            if (entityId.equals(entity.id()) && entity instanceof SketchGeometry.Line) {
                return (SketchGeometry.Line) entity;
            }
        }
        throw new AssertionError("Model line was not found for " + entityId);
    }

    private static CadCanvasView.Entity legacyEntityFor(K33MirroredCadCanvasView cad, String entityId) {
        for (CadCanvasView.Entity entity : cad.entities) {
            if (entity != null && entityId.equals(entity.stableId())) return entity;
        }
        return null;
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

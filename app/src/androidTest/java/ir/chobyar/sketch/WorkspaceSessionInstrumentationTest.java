package ir.chobyar.sketch;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Durable interaction-state contracts for the production single-workspace tools.
 *
 * These tests deliberately exercise WorkspaceController rather than dialog UI so
 * Move/Rotate and Align cannot silently regress from select -> preview ->
 * commit/cancel semantics while the exact OCCT geometry remains independently
 * covered by the native and CAD regression gates.
 */
@RunWith(AndroidJUnit4.class)
public final class WorkspaceSessionInstrumentationTest {

    @Test public void moveRotateWithSelectedBodyStartsPreviewAndCanCommit() {
        WorkspaceController controller=new WorkspaceController();
        controller.onCanvasState(true,"BODY");

        WorkspaceController.State state=controller.begin(WorkspaceController.Tool.MOVE_ROTATE);

        assertEquals(WorkspaceController.Mode.MODELING,state.mode);
        assertEquals(WorkspaceController.Tool.MOVE_ROTATE,state.tool);
        assertEquals(WorkspaceController.Phase.PREVIEW,state.phase);
        assertEquals(WorkspaceController.Selection.BODY,state.selection);
        assertTrue(state.canCommit());
        assertTrue(state.instruction().contains("text"));
        assertTrue(state.instruction().contains("text"));
    }

    @Test public void moveRotateWithoutSelectionWaitsThenBecomesPreviewAfterBodyPick() {
        WorkspaceController controller=new WorkspaceController();
        controller.onCanvasState(true,"NONE");

        WorkspaceController.State waiting=controller.begin(WorkspaceController.Tool.MOVE_ROTATE);
        assertEquals(WorkspaceController.Phase.SELECT_PRIMARY,waiting.phase);
        assertFalse(waiting.canCommit());

        WorkspaceController.State preview=controller.onCanvasState(true,"BODY");
        assertEquals(WorkspaceController.Tool.MOVE_ROTATE,preview.tool);
        assertEquals(WorkspaceController.Phase.PREVIEW,preview.phase);
        assertTrue(preview.canCommit());
    }

    @Test public void alignUsesPrimaryTargetPreviewSequence() {
        WorkspaceController controller=new WorkspaceController();
        controller.onCanvasState(true,"FACE");

        WorkspaceController.State primary=controller.begin(WorkspaceController.Tool.ALIGN);
        assertEquals(WorkspaceController.Phase.SELECT_PRIMARY,primary.phase);
        assertFalse(primary.canCommit());

        WorkspaceController.State target=controller.primaryAccepted();
        assertEquals(WorkspaceController.Phase.SELECT_SECONDARY,target.phase);
        assertFalse(target.canCommit());
        assertTrue(target.instruction().contains("text"));

        WorkspaceController.State preview=controller.previewReady();
        assertEquals(WorkspaceController.Phase.PREVIEW,preview.phase);
        assertTrue(preview.canCommit());
        assertTrue(preview.instruction().contains("Same/Opposed"));
    }

    @Test public void finishReturnsToolToIdleWithoutDroppingModelingMode() {
        WorkspaceController controller=new WorkspaceController();
        controller.onCanvasState(true,"BODY");
        controller.begin(WorkspaceController.Tool.MOVE_ROTATE);

        WorkspaceController.State done=controller.finish();

        assertEquals(WorkspaceController.Mode.MODELING,done.mode);
        assertEquals(WorkspaceController.Tool.NONE,done.tool);
        assertEquals(WorkspaceController.Phase.IDLE,done.phase);
        assertFalse(done.sessionActive());
        assertFalse(done.canCommit());
    }

    @Test public void cancelAlignReturnsToIdleAndPreservesSelectionContext() {
        WorkspaceController controller=new WorkspaceController();
        controller.onCanvasState(true,"FACE");
        controller.begin(WorkspaceController.Tool.ALIGN);
        controller.primaryAccepted();

        WorkspaceController.State canceled=controller.cancel();

        assertEquals(WorkspaceController.Mode.MODELING,canceled.mode);
        assertEquals(WorkspaceController.Selection.FACE,canceled.selection);
        assertEquals(WorkspaceController.Tool.NONE,canceled.tool);
        assertEquals(WorkspaceController.Phase.IDLE,canceled.phase);
        assertFalse(canceled.sessionActive());
    }
}

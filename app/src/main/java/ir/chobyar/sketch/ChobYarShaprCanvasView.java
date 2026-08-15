package ir.chobyar.sketch;

import android.content.Context;
import android.view.MotionEvent;

/** Small UI bridge: keeps the CAD engine independent while allowing the
 * workspace chrome to react immediately to geometry selection changes. */
public class ChobYarShaprCanvasView extends ShaprStyleCadCanvasView {

    public interface WorkspaceListener {
        void onWorkspaceStateChanged(String selectionInfo, boolean exactDimensionAvailable, int activeTool);
    }

    private WorkspaceListener workspaceListener;

    public ChobYarShaprCanvasView(Context context) {
        super(context);
    }

    public void setWorkspaceListener(WorkspaceListener listener) {
        workspaceListener = listener;
        dispatchWorkspaceState();
    }

    @Override
    public void setTool(int newTool) {
        super.setTool(newTool);
        dispatchWorkspaceState();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        boolean handled = super.onTouchEvent(event);
        int a = event.getActionMasked();
        if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
            dispatchWorkspaceState();
        }
        return handled;
    }

    @Override
    public void deleteSelected() {
        super.deleteSelected();
        dispatchWorkspaceState();
    }

    @Override
    public void clearAll() {
        super.clearAll();
        dispatchWorkspaceState();
    }

    @Override
    public void undo() {
        super.undo();
        dispatchWorkspaceState();
    }

    public void dispatchWorkspaceState() {
        if (workspaceListener != null) {
            workspaceListener.onWorkspaceStateChanged(selectedInfo(), canEditExactDimension(), getTool());
        }
    }
}

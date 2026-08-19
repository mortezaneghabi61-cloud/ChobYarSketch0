package ir.chobyar.sketch;

import android.view.MotionEvent;

/**
 * Central interaction policy for the touch + pen workspace.
 *
 * This class deliberately contains no rendering or geometry code. It defines
 * which input device owns sketch creation and which legacy tools need to be
 * re-armed after a committed pen stroke. Dedicated Arc / Automatic / Spline
 * modes already own their own continuation lifecycle and must not be forced
 * through the legacy TOOL_SELECT reset path.
 */
final class ShaprInteractionPolicy {
    private ShaprInteractionPolicy() {}

    static boolean isPen(MotionEvent event) {
        if (event == null || event.getPointerCount() == 0) return false;
        int type = event.getToolType(0);
        return type == MotionEvent.TOOL_TYPE_STYLUS || type == MotionEvent.TOOL_TYPE_ERASER;
    }

    static boolean isFinger(MotionEvent event) {
        return event != null && event.getPointerCount() > 0
                && event.getToolType(0) == MotionEvent.TOOL_TYPE_FINGER;
    }

    /**
     * The legacy Line implementation resets to Select after each segment, but
     * the tablet workflow needs a connected line sequence to continue from the
     * previous endpoint. Profile primitives such as Rectangle/Circle/Polygon
     * intentionally return to the edit context so their dimension controls are
     * immediately available. Arc/Spline/Automatic have dedicated mode state.
     */
    static boolean isPersistentSketchTool(int tool) {
        return tool == CadCanvasView.TOOL_LINE;
    }

    static boolean shouldRearmAfterCommit(MotionEvent event, int tool) {
        return isPen(event) && isPersistentSketchTool(tool)
                && event.getActionMasked() == MotionEvent.ACTION_UP;
    }
}

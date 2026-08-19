package ir.chobyar.sketch;

import android.view.MotionEvent;

/**
 * Central interaction policy for the touch + pen workspace.
 *
 * This class deliberately contains no rendering or geometry code. It defines
 * which input device owns sketch creation and which sketch tools remain armed
 * after a completed pen stroke, so the same behavior can be reused by the
 * snapping, constraint and modeling layers without duplicating conditionals.
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

    static boolean isPersistentSketchTool(int tool) {
        return tool == CadCanvasView.TOOL_LINE
                || tool == CadCanvasView.TOOL_ARC
                || tool == CadCanvasView.TOOL_RECT
                || tool == CadCanvasView.TOOL_CIRCLE
                || tool == CadCanvasView.TOOL_POLYGON;
    }

    /**
     * Shapr-style pen sketching keeps the chosen primitive armed after lifting
     * the pen. Selection, measure and one-shot utility tools are intentionally
     * excluded.
     */
    static boolean shouldRearmAfterCommit(MotionEvent event, int tool) {
        return isPen(event) && isPersistentSketchTool(tool)
                && event.getActionMasked() == MotionEvent.ACTION_UP;
    }
}

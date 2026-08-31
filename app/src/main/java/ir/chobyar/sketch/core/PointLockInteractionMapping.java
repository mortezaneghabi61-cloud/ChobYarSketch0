package ir.chobyar.sketch.core;

/**
 * K3.8 boundary between legacy canvas control-handle indices and model-owned FIXED point indices.
 * Unsupported handles fail closed so UI cannot manufacture a lock target the solver does not own.
 */
public final class PointLockInteractionMapping {
    private PointLockInteractionMapping() {}

    public static boolean isSupported(SketchEntity.Kind kind, int controlHandleIndex) {
        return modelPointIndex(kind, controlHandleIndex) >= 0;
    }

    public static int modelPointIndex(SketchEntity.Kind kind, int controlHandleIndex) {
        if (kind == null || controlHandleIndex < 0) return -1;
        switch (kind) {
            case LINE:
                return controlHandleIndex == 0 || controlHandleIndex == 1 ? controlHandleIndex : -1;
            case CIRCLE:
            case ARC:
                return controlHandleIndex == 0 ? 0 : -1;
            default:
                return -1;
        }
    }
}

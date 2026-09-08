package ir.chobyar.sketch;

/**
 * Transient interaction authority for creating a new Sketch from model view.
 *
 * Construction geometry and Sketch-to-plane relationships remain owned by the
 * model. This controller owns only the non-persisted placement state, so a new
 * Sketch cannot be created until the user supplies an explicit model target.
 */
public final class ProfessionalSketchPlacementController {
    public enum State { INACTIVE, AWAITING_TARGET }

    private final SpatialCadCanvasView cad;
    private State state = State.INACTIVE;

    public ProfessionalSketchPlacementController(SpatialCadCanvasView cad) {
        if (cad == null) throw new IllegalArgumentException("CAD view is required");
        this.cad = cad;
    }

    public State state() { return state; }

    public boolean begin() {
        if (!cad.is3DOverview()) return false;
        state = State.AWAITING_TARGET;
        return true;
    }

    public void cancel() { state = State.INACTIVE; }

    public String startNewSketchOnPlane(String planeId) {
        if (state != State.AWAITING_TARGET) {
            throw new IllegalStateException("Sketch placement target was not requested");
        }
        if (!cad.constructionPlaneModel().containsPlane(planeId)) {
            throw new IllegalArgumentException("Construction Plane was not found");
        }

        // Clear the transient state before the model dispatches its workspace
        // update. If creation fails, restore AWAITING_TARGET so the intent is
        // still explicit and no second placement authority is introduced.
        state = State.INACTIVE;
        try {
            return cad.createSketchSpaceOnConstructionPlane("Sketch", planeId);
        } catch (RuntimeException failure) {
            state = State.AWAITING_TARGET;
            throw failure;
        }
    }

    /** Complete an explicit placement through the existing face workflow. */
    public void completeExternalTarget() {
        if (state != State.AWAITING_TARGET) {
            throw new IllegalStateException("Sketch placement target was not requested");
        }
        state = State.INACTIVE;
    }
}

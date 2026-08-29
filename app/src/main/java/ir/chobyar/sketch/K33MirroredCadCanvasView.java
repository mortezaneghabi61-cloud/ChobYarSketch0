package ir.chobyar.sketch;

import android.content.Context;
import android.view.MotionEvent;

import java.util.List;

import ir.chobyar.sketch.core.LegacySketchStateBridge;
import ir.chobyar.sketch.core.SketchDocument;
import ir.chobyar.sketch.core.SketchEntity;

/**
 * K3.3 migration canvas.
 *
 * The legacy view remains authoritative for user interaction, snapping,
 * constraints and annotations. Supported committed geometry mutations are
 * mirrored into the pure {@link SketchDocument} so the next authority move has
 * a measurable parity seam without prematurely moving solver/snapping state.
 */
public class K33MirroredCadCanvasView extends Shapr3DGuideCadCanvasView {
    private final SketchDocument sketchDocument = new SketchDocument();
    private long mirrorSyncCount;
    private String lastMirrorError = "";

    public K33MirroredCadCanvasView(Context context) {
        super(context);
        syncMirror("constructor");
    }

    public long sketchMirrorSyncCount() { return mirrorSyncCount; }
    public String sketchMirrorError() { return lastMirrorError; }
    public List<SketchEntity> sketchMirrorEntities() { return sketchDocument.entities(); }

    /** Non-throwing status used by production telemetry/UI. */
    public boolean assertSketchMirrorParity() {
        try {
            boolean ok = LegacySketchStateBridge.hasParity(sketchDocument, exportSketchProjectState());
            if (!ok) lastMirrorError = "SketchDocument parity mismatch";
            return ok;
        } catch (RuntimeException e) {
            lastMirrorError = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return false;
        }
    }

    /** Strict QA hook. Never use this as a user-facing crash path. */
    public void requireSketchMirrorParity() {
        if (!assertSketchMirrorParity()) throw new IllegalStateException(lastMirrorError);
    }

    private void syncMirror(String source) {
        try {
            LegacySketchStateBridge.restoreDocument(sketchDocument, exportSketchProjectState());
            mirrorSyncCount++;
            lastMirrorError = "";
            if (!LegacySketchStateBridge.hasParity(sketchDocument, exportSketchProjectState())) {
                lastMirrorError = "Parity failed after " + source;
            }
        } catch (RuntimeException e) {
            lastMirrorError = source + ": " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        boolean handled = super.onTouchEvent(event);
        int action = event == null ? MotionEvent.ACTION_CANCEL : event.getActionMasked();
        // Drag/draw previews stay legacy-owned; commit the mirror at gesture end.
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) syncMirror("touch-commit");
        return handled;
    }

    @Override public void clearAll() { super.clearAll(); syncMirror("clear"); }
    @Override public void undo() { super.undo(); syncMirror("undo"); }
    @Override public boolean redoSketch() { boolean out=super.redoSketch(); if(out)syncMirror("redo"); return out; }
    @Override public void deleteSelected() { super.deleteSelected(); syncMirror("delete"); }
    @Override public void copySelected(float dx,float dy) { super.copySelected(dx,dy); syncMirror("copy"); }
    @Override public void moveSelected(float dx,float dy) { super.moveSelected(dx,dy); syncMirror("move"); }
    @Override public String offsetSelected(float distance) { String out=super.offsetSelected(distance); syncMirror("offset"); return out; }
    @Override public String rotateSelected(float deg) { String out=super.rotateSelected(deg); syncMirror("rotate"); return out; }
    @Override public String scaleSelected(float factor) { String out=super.scaleSelected(factor); syncMirror("scale"); return out; }
    @Override public String mirrorSelected(boolean acrossXAxis,float axisValue) { String out=super.mirrorSelected(acrossXAxis,axisValue); syncMirror("mirror"); return out; }
    @Override public String arraySelected(int count,float dx,float dy) { String out=super.arraySelected(count,dx,dy); syncMirror("array"); return out; }

    // Advanced 2D editing can mutate entities without passing through the base mutators.
    @Override public String trimSelectedLines() { String out=super.trimSelectedLines(); syncMirror("trim"); return out; }
    @Override public String extendSelectedLines() { String out=super.extendSelectedLines(); syncMirror("extend"); return out; }
    @Override public String chamferSelectedLines(float setback) { String out=super.chamferSelectedLines(setback); syncMirror("sketch-chamfer"); return out; }
    @Override public String filletSelectedLines(float radius) { String out=super.filletSelectedLines(radius); syncMirror("sketch-fillet"); return out; }
    @Override public String joinSelectedLines() { String out=super.joinSelectedLines(); syncMirror("join"); return out; }

    // Driving dimensions and constraint application can reposition geometry immediately.
    @Override public String applySelectedDimension(String raw) { String out=super.applySelectedDimension(raw); syncMirror("dimension"); return out; }
    @Override public String setSelectedLineAngle(float degrees) { String out=super.setSelectedLineAngle(degrees); syncMirror("line-angle"); return out; }
    @Override public String setSelectedLinesAngle(float degrees) { String out=super.setSelectedLinesAngle(degrees); syncMirror("lines-angle"); return out; }
    @Override public String applyHorizontalVerticalConstraint() { String out=super.applyHorizontalVerticalConstraint(); syncMirror("constraint-hv"); return out; }
    @Override public String applyPerpendicularConstraint() { String out=super.applyPerpendicularConstraint(); syncMirror("constraint-perpendicular"); return out; }
    @Override public String applyParallelConstraint() { String out=super.applyParallelConstraint(); syncMirror("constraint-parallel"); return out; }
    @Override public String applyEqualConstraint() { String out=super.applyEqualConstraint(); syncMirror("constraint-equal"); return out; }
    @Override public String applySymmetryConstraint() { String out=super.applySymmetryConstraint(); syncMirror("constraint-symmetry"); return out; }
    @Override public String applyMidpointConstraint() { String out=super.applyMidpointConstraint(); syncMirror("constraint-midpoint"); return out; }
    @Override public String applyTangentConstraint() { String out=super.applyTangentConstraint(); syncMirror("constraint-tangent"); return out; }
    @Override public String applyConcentricConstraint() { String out=super.applyConcentricConstraint(); syncMirror("constraint-concentric"); return out; }
    @Override public String disconnectSelectedConnections() { String out=super.disconnectSelectedConnections(); syncMirror("constraint-disconnect"); return out; }

    @Override public String importSketchProjectState(String raw) { String out=super.importSketchProjectState(raw); syncMirror("project-open"); return out; }

    @Override public String executeCommand(String raw) {
        String out = super.executeCommand(raw);
        syncMirror("command");
        return out;
    }
}

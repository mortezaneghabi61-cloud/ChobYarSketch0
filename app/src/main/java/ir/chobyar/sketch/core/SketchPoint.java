package ir.chobyar.sketch.core;

import java.util.Objects;

/** A real editable sketch point, distinct from the Point coordinate value type. */
public final class SketchPoint extends SketchGeometry.Base {
    public final SketchGeometry.Point position;

    public SketchPoint(String id, SketchGeometry.Point position) {
        super(id);
        this.position = Objects.requireNonNull(position, "position");
    }

    @Override public Kind kind() { return Kind.POINT; }

    @Override public SketchPoint copy() {
        return new SketchPoint(id(), new SketchGeometry.Point(position.xMm, position.yMm));
    }

    @Override public SketchPoint translated(double dxMm, double dyMm) {
        if (!validDelta(dxMm, dyMm)) throw new IllegalArgumentException("Translation must be finite");
        return new SketchPoint(id(), position.translated(dxMm, dyMm));
    }

    @Override public boolean isValid() {
        return position.isFinite();
    }
}

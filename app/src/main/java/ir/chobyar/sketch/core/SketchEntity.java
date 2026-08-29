package ir.chobyar.sketch.core;

/** Pure model-space sketch entity. No Android UI types are allowed here. */
public interface SketchEntity {
    enum Kind {
        LINE,
        CIRCLE,
        ARC,
        RECT,
        POLYLINE
    }

    String id();
    Kind kind();

    /** Returns an independent value copy preserving the stable entity id. */
    SketchEntity copy();

    /** Returns a translated value copy preserving the stable entity id. */
    SketchEntity translated(double dxMm, double dyMm);

    /** Rejects NaN/infinite/degenerate model values before they enter a document. */
    boolean isValid();
}

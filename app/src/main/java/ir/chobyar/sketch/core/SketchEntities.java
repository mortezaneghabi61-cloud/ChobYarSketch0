package ir.chobyar.sketch.core;

/** Value-level entity operations whose semantics differ from history snapshots. */
public final class SketchEntities {
    private SketchEntities() {}

    /**
     * Creates the same geometry with a different stable identity.
     *
     * {@link SketchEntity#copy()} deliberately preserves identity for snapshots,
     * undo/redo and read isolation. User-facing Copy/Array operations must call
     * this method with a fresh id instead, so two live document entities never
     * accidentally share one identity.
     */
    public static SketchEntity duplicateAs(SketchEntity source, String newId) {
        if (source == null) throw new NullPointerException("source");
        if (!source.isValid()) throw new IllegalArgumentException("Cannot duplicate invalid geometry: " + source.id());

        if (source instanceof SketchPoint) {
            SketchPoint p = (SketchPoint) source;
            return new SketchPoint(newId,
                    new SketchGeometry.Point(p.position.xMm, p.position.yMm));
        }
        if (source instanceof SketchGeometry.Line) {
            SketchGeometry.Line line = (SketchGeometry.Line) source;
            return new SketchGeometry.Line(newId,
                    new SketchGeometry.Point(line.a.xMm, line.a.yMm),
                    new SketchGeometry.Point(line.b.xMm, line.b.yMm));
        }
        if (source instanceof SketchGeometry.Circle) {
            SketchGeometry.Circle circle = (SketchGeometry.Circle) source;
            return new SketchGeometry.Circle(newId,
                    new SketchGeometry.Point(circle.center.xMm, circle.center.yMm),
                    circle.radiusMm);
        }
        if (source instanceof SketchGeometry.Arc) {
            SketchGeometry.Arc arc = (SketchGeometry.Arc) source;
            return new SketchGeometry.Arc(newId,
                    new SketchGeometry.Point(arc.center.xMm, arc.center.yMm),
                    arc.radiusMm, arc.startDeg, arc.sweepDeg);
        }
        if (source instanceof SketchGeometry.Rect) {
            SketchGeometry.Rect rect = (SketchGeometry.Rect) source;
            return new SketchGeometry.Rect(newId,
                    new SketchGeometry.Point(rect.origin.xMm, rect.origin.yMm),
                    new SketchGeometry.Vector(rect.u.xMm, rect.u.yMm),
                    new SketchGeometry.Vector(rect.v.xMm, rect.v.yMm));
        }
        if (source instanceof SketchPolygon) {
            return new SketchPolygon(newId, ((SketchPolygon) source).vertices());
        }
        if (source instanceof SketchGeometry.Polyline) {
            SketchGeometry.Polyline polyline = (SketchGeometry.Polyline) source;
            return new SketchGeometry.Polyline(newId, polyline.points(), polyline.closed);
        }
        throw new IllegalArgumentException("Unsupported Sketch entity implementation: " + source.getClass().getName());
    }
}

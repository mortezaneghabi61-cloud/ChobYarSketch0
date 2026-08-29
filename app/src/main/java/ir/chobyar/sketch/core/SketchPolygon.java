package ir.chobyar.sketch.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Closed polygon primitive with an explicit vertex list in model millimeters. */
public final class SketchPolygon extends SketchGeometry.Base {
    private static final double EPS = 1.0e-9;
    private final List<SketchGeometry.Point> vertices;

    public SketchPolygon(String id, List<SketchGeometry.Point> vertices) {
        super(id);
        if (vertices == null) throw new NullPointerException("vertices");
        ArrayList<SketchGeometry.Point> copy = new ArrayList<>(vertices.size());
        for (SketchGeometry.Point p : vertices) {
            if (p == null) throw new IllegalArgumentException("Polygon contains null vertex");
            copy.add(new SketchGeometry.Point(p.xMm, p.yMm));
        }
        this.vertices = Collections.unmodifiableList(copy);
    }

    public static SketchPolygon regular(String id, int sides, double cxMm, double cyMm,
                                        double radiusMm, double rotationDeg) {
        if (sides < 3 || sides > 512) throw new IllegalArgumentException("Polygon sides must be 3..512");
        if (!SketchGeometry.finite(cxMm) || !SketchGeometry.finite(cyMm)
                || !SketchGeometry.finite(radiusMm) || radiusMm <= EPS
                || !SketchGeometry.finite(rotationDeg)) {
            throw new IllegalArgumentException("Invalid regular polygon parameters");
        }
        ArrayList<SketchGeometry.Point> points = new ArrayList<>(sides);
        double base = Math.toRadians(rotationDeg);
        for (int i = 0; i < sides; i++) {
            double a = base + (Math.PI * 2.0 * i / sides);
            points.add(new SketchGeometry.Point(
                    cxMm + Math.cos(a) * radiusMm,
                    cyMm + Math.sin(a) * radiusMm));
        }
        return new SketchPolygon(id, points);
    }

    public List<SketchGeometry.Point> vertices() { return vertices; }

    @Override public Kind kind() { return Kind.POLYGON; }

    @Override public SketchPolygon copy() {
        return new SketchPolygon(id(), vertices);
    }

    @Override public SketchPolygon translated(double dxMm, double dyMm) {
        if (!validDelta(dxMm, dyMm)) throw new IllegalArgumentException("Translation must be finite");
        ArrayList<SketchGeometry.Point> moved = new ArrayList<>(vertices.size());
        for (SketchGeometry.Point p : vertices) moved.add(p.translated(dxMm, dyMm));
        return new SketchPolygon(id(), moved);
    }

    @Override public boolean isValid() {
        if (vertices.size() < 3) return false;
        for (SketchGeometry.Point p : vertices) if (!p.isFinite()) return false;
        double twiceArea = 0.0;
        for (int i = 0; i < vertices.size(); i++) {
            SketchGeometry.Point a = vertices.get(i);
            SketchGeometry.Point b = vertices.get((i + 1) % vertices.size());
            twiceArea += a.xMm * b.yMm - b.xMm * a.yMm;
        }
        return Math.abs(twiceArea) > EPS;
    }
}

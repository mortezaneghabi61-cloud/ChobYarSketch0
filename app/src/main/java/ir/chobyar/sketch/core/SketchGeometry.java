package ir.chobyar.sketch.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Immutable, millimeter-based sketch primitives owned by {@link SketchDocument}. */
public final class SketchGeometry {
    private static final double EPS = 1.0e-9;
    private static final double ORTHOGONAL_REL_TOL = 1.0e-8;

    private SketchGeometry() {}

    public static final class Point {
        public final double xMm;
        public final double yMm;

        public Point(double xMm, double yMm) {
            this.xMm = xMm;
            this.yMm = yMm;
        }

        public Point translated(double dxMm, double dyMm) {
            return new Point(xMm + dxMm, yMm + dyMm);
        }

        public boolean isFinite() {
            return finite(xMm) && finite(yMm);
        }
    }

    /** Model-space direction/extent value; translation never applies to vectors. */
    public static final class Vector {
        public final double xMm;
        public final double yMm;

        public Vector(double xMm, double yMm) {
            this.xMm = xMm;
            this.yMm = yMm;
        }

        public boolean isFinite() {
            return finite(xMm) && finite(yMm);
        }

        public double lengthSquared() {
            return xMm * xMm + yMm * yMm;
        }
    }

    public abstract static class Base implements SketchEntity {
        private final String id;

        Base(String id) {
            String normalized = id == null ? "" : id.trim();
            if (normalized.isEmpty()) throw new IllegalArgumentException("Sketch entity id is empty");
            this.id = normalized;
        }

        @Override public final String id() { return id; }

        final boolean validDelta(double dxMm, double dyMm) {
            return finite(dxMm) && finite(dyMm);
        }
    }

    public static final class Line extends Base {
        public final Point a;
        public final Point b;

        public Line(String id, Point a, Point b) {
            super(id);
            this.a = Objects.requireNonNull(a, "a");
            this.b = Objects.requireNonNull(b, "b");
        }

        @Override public Kind kind() { return Kind.LINE; }
        @Override public Line copy() { return new Line(id(), new Point(a.xMm, a.yMm), new Point(b.xMm, b.yMm)); }
        @Override public Line translated(double dxMm, double dyMm) {
            if (!validDelta(dxMm, dyMm)) throw new IllegalArgumentException("Translation must be finite");
            return new Line(id(), a.translated(dxMm, dyMm), b.translated(dxMm, dyMm));
        }
        @Override public boolean isValid() {
            return a.isFinite() && b.isFinite() && distanceSquared(a, b) > EPS * EPS;
        }
        public double lengthMm() { return Math.sqrt(distanceSquared(a, b)); }
    }

    public static final class Circle extends Base {
        public final Point center;
        public final double radiusMm;

        public Circle(String id, Point center, double radiusMm) {
            super(id);
            this.center = Objects.requireNonNull(center, "center");
            this.radiusMm = radiusMm;
        }

        @Override public Kind kind() { return Kind.CIRCLE; }
        @Override public Circle copy() { return new Circle(id(), new Point(center.xMm, center.yMm), radiusMm); }
        @Override public Circle translated(double dxMm, double dyMm) {
            if (!validDelta(dxMm, dyMm)) throw new IllegalArgumentException("Translation must be finite");
            return new Circle(id(), center.translated(dxMm, dyMm), radiusMm);
        }
        @Override public boolean isValid() { return center.isFinite() && finite(radiusMm) && radiusMm > EPS; }
    }

    public static final class Arc extends Base {
        public final Point center;
        public final double radiusMm;
        public final double startDeg;
        public final double sweepDeg;

        public Arc(String id, Point center, double radiusMm, double startDeg, double sweepDeg) {
            super(id);
            this.center = Objects.requireNonNull(center, "center");
            this.radiusMm = radiusMm;
            this.startDeg = startDeg;
            this.sweepDeg = sweepDeg;
        }

        @Override public Kind kind() { return Kind.ARC; }
        @Override public Arc copy() { return new Arc(id(), new Point(center.xMm, center.yMm), radiusMm, startDeg, sweepDeg); }
        @Override public Arc translated(double dxMm, double dyMm) {
            if (!validDelta(dxMm, dyMm)) throw new IllegalArgumentException("Translation must be finite");
            return new Arc(id(), center.translated(dxMm, dyMm), radiusMm, startDeg, sweepDeg);
        }
        @Override public boolean isValid() {
            return center.isFinite() && finite(radiusMm) && radiusMm > EPS
                    && finite(startDeg) && finite(sweepDeg) && Math.abs(sweepDeg) > EPS;
        }
    }

    /** Rectangle represented by an origin and two perpendicular model-space basis vectors. */
    public static final class Rect extends Base {
        public final Point origin;
        public final Vector u;
        public final Vector v;

        public Rect(String id, Point origin, Vector u, Vector v) {
            super(id);
            this.origin = Objects.requireNonNull(origin, "origin");
            this.u = Objects.requireNonNull(u, "u");
            this.v = Objects.requireNonNull(v, "v");
        }

        @Override public Kind kind() { return Kind.RECT; }
        @Override public Rect copy() {
            return new Rect(id(), new Point(origin.xMm, origin.yMm),
                    new Vector(u.xMm, u.yMm), new Vector(v.xMm, v.yMm));
        }
        @Override public Rect translated(double dxMm, double dyMm) {
            if (!validDelta(dxMm, dyMm)) throw new IllegalArgumentException("Translation must be finite");
            return new Rect(id(), origin.translated(dxMm, dyMm), u, v);
        }
        @Override public boolean isValid() {
            if (!origin.isFinite() || !u.isFinite() || !v.isFinite()) return false;
            double u2 = u.lengthSquared();
            double v2 = v.lengthSquared();
            if (u2 <= EPS * EPS || v2 <= EPS * EPS) return false;
            double dot = u.xMm * v.xMm + u.yMm * v.yMm;
            double scale = Math.sqrt(u2 * v2);
            return Math.abs(dot) <= ORTHOGONAL_REL_TOL * scale;
        }
    }

    public static final class Polyline extends Base {
        private final List<Point> points;
        public final boolean closed;

        public Polyline(String id, List<Point> points, boolean closed) {
            super(id);
            if (points == null) throw new NullPointerException("points");
            ArrayList<Point> copy = new ArrayList<>(points.size());
            for (Point p : points) {
                if (p == null) throw new IllegalArgumentException("Polyline contains null point");
                copy.add(new Point(p.xMm, p.yMm));
            }
            this.points = Collections.unmodifiableList(copy);
            this.closed = closed;
        }

        public List<Point> points() { return points; }
        @Override public Kind kind() { return Kind.POLYLINE; }
        @Override public Polyline copy() { return new Polyline(id(), points, closed); }
        @Override public Polyline translated(double dxMm, double dyMm) {
            if (!validDelta(dxMm, dyMm)) throw new IllegalArgumentException("Translation must be finite");
            ArrayList<Point> moved = new ArrayList<>(points.size());
            for (Point p : points) moved.add(p.translated(dxMm, dyMm));
            return new Polyline(id(), moved, closed);
        }
        @Override public boolean isValid() {
            if (points.size() < (closed ? 3 : 2)) return false;
            double length2Sum = 0.0;
            for (int i = 0; i < points.size(); i++) {
                Point p = points.get(i);
                if (!p.isFinite()) return false;
                if (i > 0) length2Sum += distanceSquared(points.get(i - 1), p);
            }
            if (closed) length2Sum += distanceSquared(points.get(points.size() - 1), points.get(0));
            return length2Sum > EPS * EPS;
        }
    }

    public static boolean finite(double v) { return !Double.isNaN(v) && !Double.isInfinite(v); }

    static double distanceSquared(Point a, Point b) {
        double dx = b.xMm - a.xMm;
        double dy = b.yMm - a.yMm;
        return dx * dx + dy * dy;
    }
}

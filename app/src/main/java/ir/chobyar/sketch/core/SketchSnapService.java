package ir.chobyar.sketch.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * Pure model-space geometric snapping for Sketch Core V2.
 *
 * The service has no Android/View dependency and works only in millimeters.
 * Screen-space tolerance, grid/guides and visual feedback remain interaction
 * concerns. This separation lets touch, stylus, numeric tools and future solver
 * code ask the same SketchDocument geometry for deterministic snap results.
 */
public final class SketchSnapService {
    private static final double EPS = 1.0e-9;

    public enum Kind {
        POINT,
        ENDPOINT,
        INTERSECTION,
        MIDPOINT,
        CENTER,
        QUADRANT,
        ON_EDGE
    }

    public static final class Result {
        public final SketchGeometry.Point point;
        public final Kind kind;
        public final String entityId;
        public final String secondaryEntityId;
        public final double distanceMm;

        Result(SketchGeometry.Point point, Kind kind, String entityId,
               String secondaryEntityId, double distanceMm) {
            this.point = point;
            this.kind = kind;
            this.entityId = entityId;
            this.secondaryEntityId = secondaryEntityId;
            this.distanceMm = distanceMm;
        }
    }

    /** Snap against an immutable snapshot of the current SketchDocument. */
    public Result snap(SketchDocument document, SketchGeometry.Point query,
                       double radiusMm, String excludedId) {
        if (document == null) throw new NullPointerException("document");
        return snap(document.entities(), query, radiusMm, excludedId);
    }

    /**
     * Finds the best geometric snap candidate inside radiusMm.
     *
     * Distance is the primary ordering. Equal-distance ties are resolved by a
     * stable semantic priority (point/endpoint, intersection, midpoint/center,
     * then on-edge) and finally by entity id, so results do not depend on object
     * identity or hash iteration order.
     */
    public Result snap(Collection<? extends SketchEntity> values,
                       SketchGeometry.Point query, double radiusMm,
                       String excludedId) {
        if (query == null || !query.isFinite()) throw new IllegalArgumentException("query must be finite");
        if (!SketchGeometry.finite(radiusMm) || radiusMm < 0.0) {
            throw new IllegalArgumentException("radiusMm must be finite and non-negative");
        }
        if (values == null || values.isEmpty()) return null;

        String excluded = normalizeOptionalId(excludedId);
        ArrayList<SketchEntity> entities = new ArrayList<>();
        for (SketchEntity entity : values) {
            if (entity == null || !entity.isValid()) continue;
            if (excluded != null && excluded.equals(entity.id())) continue;
            entities.add(entity.copy());
        }
        Collections.sort(entities, (a, b) -> a.id().compareTo(b.id()));

        Best best = new Best(query, radiusMm);
        for (SketchEntity entity : entities) offerEntity(best, entity);
        offerLineIntersections(best, entities);
        return best.result;
    }

    private static void offerEntity(Best best, SketchEntity entity) {
        if (entity instanceof SketchPoint) {
            SketchPoint p = (SketchPoint) entity;
            best.offer(p.position, Kind.POINT, p.id(), null);
            return;
        }
        if (entity instanceof SketchGeometry.Line) {
            SketchGeometry.Line line = (SketchGeometry.Line) entity;
            offerSegment(best, line.id(), line.a, line.b, true);
            return;
        }
        if (entity instanceof SketchGeometry.Circle) {
            SketchGeometry.Circle circle = (SketchGeometry.Circle) entity;
            offerCircle(best, circle);
            return;
        }
        if (entity instanceof SketchGeometry.Arc) {
            SketchGeometry.Arc arc = (SketchGeometry.Arc) entity;
            offerArc(best, arc);
            return;
        }
        if (entity instanceof SketchGeometry.Rect) {
            SketchGeometry.Rect rect = (SketchGeometry.Rect) entity;
            SketchGeometry.Point p0 = rect.origin;
            SketchGeometry.Point p1 = plus(rect.origin, rect.u);
            SketchGeometry.Point p3 = plus(rect.origin, rect.v);
            SketchGeometry.Point p2 = new SketchGeometry.Point(
                    rect.origin.xMm + rect.u.xMm + rect.v.xMm,
                    rect.origin.yMm + rect.u.yMm + rect.v.yMm);
            offerClosedEdges(best, rect.id(), list(p0, p1, p2, p3));
            return;
        }
        if (entity instanceof SketchPolygon) {
            SketchPolygon polygon = (SketchPolygon) entity;
            offerClosedEdges(best, polygon.id(), polygon.vertices());
            return;
        }
        if (entity instanceof SketchGeometry.Polyline) {
            SketchGeometry.Polyline polyline = (SketchGeometry.Polyline) entity;
            offerPolyline(best, polyline);
        }
    }

    private static void offerCircle(Best best, SketchGeometry.Circle circle) {
        SketchGeometry.Point c = circle.center;
        double r = circle.radiusMm;
        best.offer(c, Kind.CENTER, circle.id(), null);
        best.offer(new SketchGeometry.Point(c.xMm + r, c.yMm), Kind.QUADRANT, circle.id(), null);
        best.offer(new SketchGeometry.Point(c.xMm - r, c.yMm), Kind.QUADRANT, circle.id(), null);
        best.offer(new SketchGeometry.Point(c.xMm, c.yMm + r), Kind.QUADRANT, circle.id(), null);
        best.offer(new SketchGeometry.Point(c.xMm, c.yMm - r), Kind.QUADRANT, circle.id(), null);
        SketchGeometry.Point near = nearestOnCircle(best.query, c, r);
        if (near != null) best.offer(near, Kind.ON_EDGE, circle.id(), null);
    }

    private static void offerArc(Best best, SketchGeometry.Arc arc) {
        SketchGeometry.Point c = arc.center;
        best.offer(c, Kind.CENTER, arc.id(), null);
        SketchGeometry.Point start = pointOnCircle(c, arc.radiusMm, arc.startDeg);
        SketchGeometry.Point end = pointOnCircle(c, arc.radiusMm, arc.startDeg + arc.sweepDeg);
        best.offer(start, Kind.ENDPOINT, arc.id(), null);
        best.offer(end, Kind.ENDPOINT, arc.id(), null);

        double dx = best.query.xMm - c.xMm;
        double dy = best.query.yMm - c.yMm;
        if (dx * dx + dy * dy <= EPS * EPS) return;
        double angle = Math.toDegrees(Math.atan2(dy, dx));
        if (angleOnSweep(angle, arc.startDeg, arc.sweepDeg)) {
            best.offer(pointOnCircle(c, arc.radiusMm, angle), Kind.ON_EDGE, arc.id(), null);
        }
    }

    private static void offerPolyline(Best best, SketchGeometry.Polyline polyline) {
        List<SketchGeometry.Point> points = polyline.points();
        if (points.isEmpty()) return;
        for (SketchGeometry.Point p : points) best.offer(p, Kind.ENDPOINT, polyline.id(), null);
        int segmentCount = polyline.closed ? points.size() : points.size() - 1;
        for (int i = 0; i < segmentCount; i++) {
            SketchGeometry.Point a = points.get(i);
            SketchGeometry.Point b = points.get((i + 1) % points.size());
            offerSegmentInterior(best, polyline.id(), a, b);
        }
    }

    private static void offerClosedEdges(Best best, String id, List<SketchGeometry.Point> points) {
        if (points == null || points.size() < 2) return;
        for (SketchGeometry.Point p : points) best.offer(p, Kind.ENDPOINT, id, null);
        for (int i = 0; i < points.size(); i++) {
            offerSegmentInterior(best, id, points.get(i), points.get((i + 1) % points.size()));
        }
    }

    private static void offerSegment(Best best, String id, SketchGeometry.Point a,
                                     SketchGeometry.Point b, boolean offerEndpoints) {
        if (offerEndpoints) {
            best.offer(a, Kind.ENDPOINT, id, null);
            best.offer(b, Kind.ENDPOINT, id, null);
        }
        offerSegmentInterior(best, id, a, b);
    }

    private static void offerSegmentInterior(Best best, String id, SketchGeometry.Point a,
                                             SketchGeometry.Point b) {
        best.offer(midpoint(a, b), Kind.MIDPOINT, id, null);
        SketchGeometry.Point near = nearestOnSegment(best.query, a, b);
        if (near != null) best.offer(near, Kind.ON_EDGE, id, null);
    }

    private static void offerLineIntersections(Best best, List<SketchEntity> entities) {
        for (int i = 0; i < entities.size(); i++) {
            if (!(entities.get(i) instanceof SketchGeometry.Line)) continue;
            SketchGeometry.Line a = (SketchGeometry.Line) entities.get(i);
            for (int j = i + 1; j < entities.size(); j++) {
                if (!(entities.get(j) instanceof SketchGeometry.Line)) continue;
                SketchGeometry.Line b = (SketchGeometry.Line) entities.get(j);
                SketchGeometry.Point p = lineSegmentIntersection(a.a, a.b, b.a, b.b);
                if (p != null) best.offer(p, Kind.INTERSECTION, a.id(), b.id());
            }
        }
    }

    private static SketchGeometry.Point nearestOnSegment(SketchGeometry.Point q,
                                                          SketchGeometry.Point a,
                                                          SketchGeometry.Point b) {
        double dx = b.xMm - a.xMm;
        double dy = b.yMm - a.yMm;
        double l2 = dx * dx + dy * dy;
        if (l2 <= EPS * EPS) return null;
        double t = ((q.xMm - a.xMm) * dx + (q.yMm - a.yMm) * dy) / l2;
        t = Math.max(0.0, Math.min(1.0, t));
        return new SketchGeometry.Point(a.xMm + t * dx, a.yMm + t * dy);
    }

    private static SketchGeometry.Point nearestOnCircle(SketchGeometry.Point q,
                                                         SketchGeometry.Point c,
                                                         double r) {
        double dx = q.xMm - c.xMm;
        double dy = q.yMm - c.yMm;
        double len = Math.hypot(dx, dy);
        if (len <= EPS) return null;
        return new SketchGeometry.Point(c.xMm + dx / len * r, c.yMm + dy / len * r);
    }

    private static SketchGeometry.Point lineSegmentIntersection(SketchGeometry.Point a,
                                                                 SketchGeometry.Point b,
                                                                 SketchGeometry.Point c,
                                                                 SketchGeometry.Point d) {
        double rx = b.xMm - a.xMm, ry = b.yMm - a.yMm;
        double sx = d.xMm - c.xMm, sy = d.yMm - c.yMm;
        double den = cross(rx, ry, sx, sy);
        if (Math.abs(den) <= EPS) return null;
        double qpx = c.xMm - a.xMm, qpy = c.yMm - a.yMm;
        double t = cross(qpx, qpy, sx, sy) / den;
        double u = cross(qpx, qpy, rx, ry) / den;
        if (t < -EPS || t > 1.0 + EPS || u < -EPS || u > 1.0 + EPS) return null;
        return new SketchGeometry.Point(a.xMm + t * rx, a.yMm + t * ry);
    }

    private static boolean angleOnSweep(double angle, double start, double sweep) {
        if (Math.abs(sweep) >= 360.0 - 1.0e-8) return true;
        if (sweep >= 0.0) return ccwDistance(start, angle) <= sweep + 1.0e-8;
        return ccwDistance(angle, start) <= -sweep + 1.0e-8;
    }

    private static double ccwDistance(double from, double to) {
        double value = normalizeDeg(to) - normalizeDeg(from);
        if (value < 0.0) value += 360.0;
        return value;
    }

    private static double normalizeDeg(double angle) {
        double value = angle % 360.0;
        return value < 0.0 ? value + 360.0 : value;
    }

    private static SketchGeometry.Point pointOnCircle(SketchGeometry.Point c, double r, double degrees) {
        double a = Math.toRadians(degrees);
        return new SketchGeometry.Point(c.xMm + Math.cos(a) * r, c.yMm + Math.sin(a) * r);
    }

    private static SketchGeometry.Point midpoint(SketchGeometry.Point a, SketchGeometry.Point b) {
        return new SketchGeometry.Point((a.xMm + b.xMm) * 0.5, (a.yMm + b.yMm) * 0.5);
    }

    private static SketchGeometry.Point plus(SketchGeometry.Point p, SketchGeometry.Vector v) {
        return new SketchGeometry.Point(p.xMm + v.xMm, p.yMm + v.yMm);
    }

    private static List<SketchGeometry.Point> list(SketchGeometry.Point a, SketchGeometry.Point b,
                                                    SketchGeometry.Point c, SketchGeometry.Point d) {
        ArrayList<SketchGeometry.Point> out = new ArrayList<>(4);
        out.add(a); out.add(b); out.add(c); out.add(d);
        return out;
    }

    private static double cross(double ax, double ay, double bx, double by) {
        return ax * by - ay * bx;
    }

    private static double distance(SketchGeometry.Point a, SketchGeometry.Point b) {
        return Math.hypot(a.xMm - b.xMm, a.yMm - b.yMm);
    }

    private static String normalizeOptionalId(String id) {
        if (id == null) return null;
        String value = id.trim();
        return value.isEmpty() ? null : value;
    }

    private static int priority(Kind kind) {
        switch (kind) {
            case POINT:
            case ENDPOINT: return 0;
            case INTERSECTION: return 1;
            case MIDPOINT:
            case CENTER:
            case QUADRANT: return 2;
            case ON_EDGE:
            default: return 3;
        }
    }

    private static final class Best {
        final SketchGeometry.Point query;
        final double radius;
        Result result;

        Best(SketchGeometry.Point query, double radius) {
            this.query = query;
            this.radius = radius;
        }

        void offer(SketchGeometry.Point point, Kind kind, String entityId, String secondaryEntityId) {
            if (point == null || !point.isFinite()) return;
            double d = distance(query, point);
            if (d > radius + EPS) return;
            Result candidate = new Result(point, kind, entityId, secondaryEntityId, d);
            if (better(candidate, result)) result = candidate;
        }

        private static boolean better(Result a, Result b) {
            if (b == null) return true;
            if (a.distanceMm < b.distanceMm - EPS) return true;
            if (a.distanceMm > b.distanceMm + EPS) return false;
            int pa = priority(a.kind), pb = priority(b.kind);
            if (pa != pb) return pa < pb;
            int primary = safe(a.entityId).compareTo(safe(b.entityId));
            if (primary != 0) return primary < 0;
            int secondary = safe(a.secondaryEntityId).compareTo(safe(b.secondaryEntityId));
            if (secondary != 0) return secondary < 0;
            return a.kind.ordinal() < b.kind.ordinal();
        }

        private static String safe(String value) { return value == null ? "" : value; }
    }
}

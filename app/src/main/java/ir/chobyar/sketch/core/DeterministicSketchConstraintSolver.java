package ir.chobyar.sketch.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Small deterministic solver used while the mature-solver benchmark is running.
 *
 * It intentionally supports only the constraint slice being migrated in K3.6:
 * horizontal, vertical, parallel, perpendicular, endpoint coincidence,
 * midpoint and point-on-entity for line/circle/arc hosts. Unsupported records
 * fail closed rather than silently becoming decorative metadata. The
 * implementation remains behind SketchConstraintSolver so a mature solver can
 * replace it without changing model ownership or persistence records.
 */
public final class DeterministicSketchConstraintSolver implements SketchConstraintSolver {
    private static final int MAX_ITERATIONS = 32;
    private static final double DIST_TOL_MM = 1.0e-7;
    private static final double ANGLE_TOL = 1.0e-9;
    private static final double EPS = 1.0e-12;

    @Override
    public Result solve(Collection<? extends SketchEntity> sourceEntities,
                        Collection<SketchConstraint> sourceConstraints) {
        LinkedHashMap<String, SketchEntity> entities = copyEntities(sourceEntities);
        List<SketchConstraint> constraints = copyConstraints(sourceConstraints);

        for (SketchConstraint constraint : constraints) {
            String unsupported = unsupportedReason(constraint, entities);
            if (unsupported != null) {
                return result(Status.UNSUPPORTED, 0, Double.POSITIVE_INFINITY,
                        unsupported, entities);
            }
        }

        FixedState fixed = captureFixedState(entities, constraints);
        if (constraints.isEmpty()) return result(Status.SOLVED, 0, 0.0, "", entities);

        double residual = Double.POSITIVE_INFINITY;
        for (int iteration = 1; iteration <= MAX_ITERATIONS; iteration++) {
            for (SketchConstraint constraint : constraints) apply(constraint, entities);
            restoreFixedState(entities, fixed);
            residual = maxResidual(constraints, entities);
            if (residual <= DIST_TOL_MM) {
                return result(Status.SOLVED, iteration, residual, "", entities);
            }
        }
        return result(Status.CONFLICT, MAX_ITERATIONS, residual,
                "Constraint set did not converge within tolerance", entities);
    }

    private static LinkedHashMap<String, SketchEntity> copyEntities(
            Collection<? extends SketchEntity> values) {
        LinkedHashMap<String, SketchEntity> out = new LinkedHashMap<>();
        if (values == null) return out;
        for (SketchEntity entity : values) {
            if (entity == null) throw new NullPointerException("entity");
            if (out.put(entity.id(), entity.copy()) != null) {
                throw new IllegalArgumentException("Duplicate sketch entity id: " + entity.id());
            }
        }
        return out;
    }

    private static List<SketchConstraint> copyConstraints(Collection<SketchConstraint> values) {
        ArrayList<SketchConstraint> out = new ArrayList<>();
        if (values == null) return out;
        for (SketchConstraint constraint : values) {
            if (constraint == null) throw new NullPointerException("constraint");
            out.add(constraint.copy());
        }
        return out;
    }

    private static FixedState captureFixedState(Map<String, SketchEntity> entities,
                                                List<SketchConstraint> constraints) {
        FixedState fixed = new FixedState();
        for (SketchConstraint constraint : constraints) {
            if (constraint.kind != SketchConstraint.Kind.FIXED) continue;
            SketchEntity entity = entities.get(constraint.primaryEntityId);
            if (entity == null) continue;
            if (constraint.fixesWholeEntity()) {
                fixed.wholeEntities.put(constraint.primaryEntityId, entity.copy());
                continue;
            }
            if (constraint.fixesPoint()) {
                LinkedHashMap<Integer, SketchGeometry.Point> anchors = fixed.pointAnchors.get(constraint.primaryEntityId);
                if (anchors == null) {
                    anchors = new LinkedHashMap<>();
                    fixed.pointAnchors.put(constraint.primaryEntityId, anchors);
                }
                SketchGeometry.Point point = fixedPoint(entity, constraint.primaryPointIndex);
                anchors.put(constraint.primaryPointIndex,
                        new SketchGeometry.Point(point.xMm, point.yMm));
            }
        }
        return fixed;
    }

    private static void restoreFixedState(Map<String, SketchEntity> entities, FixedState fixed) {
        for (Map.Entry<String, SketchEntity> entry : fixed.wholeEntities.entrySet()) {
            entities.put(entry.getKey(), entry.getValue().copy());
        }
        for (Map.Entry<String, LinkedHashMap<Integer, SketchGeometry.Point>> entry
                : fixed.pointAnchors.entrySet()) {
            if (fixed.wholeEntities.containsKey(entry.getKey())) continue;
            SketchEntity current = entities.get(entry.getKey());
            if (current == null) continue;
            for (Map.Entry<Integer, SketchGeometry.Point> anchor : entry.getValue().entrySet()) {
                current = withFixedPoint(current, anchor.getKey(), anchor.getValue());
            }
            entities.put(entry.getKey(), current);
        }
    }

    private static SketchGeometry.Point fixedPoint(SketchEntity entity, int pointIndex) {
        if (entity instanceof SketchGeometry.Line) {
            return endpoint((SketchGeometry.Line) entity, pointIndex);
        }
        if (entity instanceof SketchGeometry.Circle && pointIndex == 0) {
            return ((SketchGeometry.Circle) entity).center;
        }
        if (entity instanceof SketchGeometry.Arc && pointIndex == 0) {
            return ((SketchGeometry.Arc) entity).center;
        }
        throw new IllegalArgumentException("Unsupported FIXED point target: " + entity.id()
                + "[" + pointIndex + "]");
    }

    private static SketchEntity withFixedPoint(SketchEntity entity, int pointIndex,
                                               SketchGeometry.Point value) {
        SketchGeometry.Point p = new SketchGeometry.Point(value.xMm, value.yMm);
        if (entity instanceof SketchGeometry.Line) {
            return withEndpoint((SketchGeometry.Line) entity, pointIndex, p);
        }
        if (entity instanceof SketchGeometry.Circle && pointIndex == 0) {
            SketchGeometry.Circle circle = (SketchGeometry.Circle) entity;
            return new SketchGeometry.Circle(circle.id(), p, circle.radiusMm);
        }
        if (entity instanceof SketchGeometry.Arc && pointIndex == 0) {
            SketchGeometry.Arc arc = (SketchGeometry.Arc) entity;
            return new SketchGeometry.Arc(arc.id(), p, arc.radiusMm, arc.startDeg, arc.sweepDeg);
        }
        throw new IllegalArgumentException("Unsupported FIXED point target: " + entity.id()
                + "[" + pointIndex + "]");
    }

    private static String unsupportedReason(SketchConstraint c, Map<String, SketchEntity> entities) {
        SketchEntity a = entities.get(c.primaryEntityId);
        SketchEntity b = c.secondaryEntityId == null ? null : entities.get(c.secondaryEntityId);
        if (a == null || (c.secondaryEntityId != null && b == null)) {
            return "Constraint references missing geometry: " + c.id;
        }
        switch (c.kind) {
            case HORIZONTAL:
            case VERTICAL:
                return a instanceof SketchGeometry.Line ? null : c.kind + " requires a line";
            case PARALLEL:
            case PERPENDICULAR:
                return a instanceof SketchGeometry.Line && b instanceof SketchGeometry.Line
                        ? null : c.kind + " requires two lines";
            case COINCIDENT:
                if (!(a instanceof SketchGeometry.Line) || !(b instanceof SketchGeometry.Line)) {
                    return "COINCIDENT currently requires two line endpoints";
                }
                return validEndpoint(c.primaryPointIndex) && validEndpoint(c.secondaryPointIndex)
                        ? null : "COINCIDENT requires endpoint indices 0 or 1";
            case MIDPOINT:
                if (!(a instanceof SketchGeometry.Line) || !(b instanceof SketchGeometry.Line)) {
                    return "MIDPOINT requires a line endpoint and line host";
                }
                if (isDegenerateLine((SketchGeometry.Line) b)) {
                    return "MIDPOINT requires a non-degenerate line host";
                }
                return validEndpoint(c.primaryPointIndex)
                        ? null : "MIDPOINT requires endpoint index 0 or 1";
            case POINT_ON_ENTITY:
                if (!(a instanceof SketchGeometry.Line) || !isPointHost(b)) {
                    return "POINT_ON_ENTITY requires a line endpoint and line/circle/arc host";
                }
                if (b instanceof SketchGeometry.Line && isDegenerateLine((SketchGeometry.Line) b)) {
                    return "POINT_ON_ENTITY requires a non-degenerate line host";
                }
                return validEndpoint(c.primaryPointIndex)
                        ? null : "POINT_ON_ENTITY requires endpoint index 0 or 1";
            case FIXED:
                if (c.fixesWholeEntity()) return null;
                if (!c.fixesPoint()) return "FIXED point target is invalid";
                if (a instanceof SketchGeometry.Line) {
                    return validEndpoint(c.primaryPointIndex)
                            ? null : "FIXED line point must be endpoint 0 or 1";
                }
                if (a instanceof SketchGeometry.Circle || a instanceof SketchGeometry.Arc) {
                    return c.primaryPointIndex == 0
                            ? null : "FIXED circle/arc point must be center index 0";
                }
                return "FIXED point currently supports line endpoints and circle/arc centers";
            default:
                return "Constraint kind not yet supported by K3.6 solver: " + c.kind;
        }
    }

    private static boolean validEndpoint(int index) { return index == 0 || index == 1; }

    private static boolean isPointHost(SketchEntity entity) {
        return entity instanceof SketchGeometry.Line
                || entity instanceof SketchGeometry.Circle
                || entity instanceof SketchGeometry.Arc;
    }

    private static boolean isDegenerateLine(SketchGeometry.Line line) {
        double dx = line.b.xMm - line.a.xMm;
        double dy = line.b.yMm - line.a.yMm;
        return dx * dx + dy * dy <= EPS;
    }

    private static void apply(SketchConstraint c, LinkedHashMap<String, SketchEntity> entities) {
        switch (c.kind) {
            case HORIZONTAL:
                entities.put(c.primaryEntityId,
                        alignAxis(line(entities, c.primaryEntityId), true));
                break;
            case VERTICAL:
                entities.put(c.primaryEntityId,
                        alignAxis(line(entities, c.primaryEntityId), false));
                break;
            case PARALLEL:
                entities.put(c.secondaryEntityId,
                        rotateRelative(line(entities, c.primaryEntityId),
                                line(entities, c.secondaryEntityId), true));
                break;
            case PERPENDICULAR:
                entities.put(c.secondaryEntityId,
                        rotateRelative(line(entities, c.primaryEntityId),
                                line(entities, c.secondaryEntityId), false));
                break;
            case COINCIDENT: {
                SketchGeometry.Line driven = line(entities, c.primaryEntityId);
                SketchGeometry.Line targetLine = line(entities, c.secondaryEntityId);
                SketchGeometry.Point target = endpoint(targetLine, c.secondaryPointIndex);
                entities.put(c.primaryEntityId,
                        withEndpoint(driven, c.primaryPointIndex, target));
                break;
            }
            case MIDPOINT: {
                SketchGeometry.Line owner = line(entities, c.primaryEntityId);
                SketchGeometry.Line host = line(entities, c.secondaryEntityId);
                entities.put(c.primaryEntityId,
                        withEndpoint(owner, c.primaryPointIndex, midpoint(host)));
                break;
            }
            case POINT_ON_ENTITY: {
                SketchGeometry.Line owner = line(entities, c.primaryEntityId);
                SketchEntity host = entities.get(c.secondaryEntityId);
                SketchGeometry.Point p = endpoint(owner, c.primaryPointIndex);
                entities.put(c.primaryEntityId,
                        withEndpoint(owner, c.primaryPointIndex, projectToEntity(host, p)));
                break;
            }
            case FIXED:
                break;
            default:
                throw new IllegalStateException("Unsupported constraint reached apply: " + c.kind);
        }
    }

    private static SketchGeometry.Line line(Map<String, SketchEntity> entities, String id) {
        return (SketchGeometry.Line) entities.get(id);
    }

    private static SketchGeometry.Point midpoint(SketchGeometry.Line line) {
        return new SketchGeometry.Point((line.a.xMm + line.b.xMm) * 0.5,
                (line.a.yMm + line.b.yMm) * 0.5);
    }

    private static SketchGeometry.Line alignAxis(SketchGeometry.Line line, boolean horizontal) {
        double cx = (line.a.xMm + line.b.xMm) * 0.5;
        double cy = (line.a.yMm + line.b.yMm) * 0.5;
        double len = line.lengthMm();
        if (horizontal) {
            return new SketchGeometry.Line(line.id(),
                    new SketchGeometry.Point(cx - len * 0.5, cy),
                    new SketchGeometry.Point(cx + len * 0.5, cy));
        }
        return new SketchGeometry.Line(line.id(),
                new SketchGeometry.Point(cx, cy - len * 0.5),
                new SketchGeometry.Point(cx, cy + len * 0.5));
    }

    private static SketchGeometry.Line rotateRelative(SketchGeometry.Line reference,
                                                       SketchGeometry.Line moving,
                                                       boolean parallel) {
        double referenceAngle = angleDeg(reference);
        double current = angleDeg(moving);
        double targetBase = parallel ? referenceAngle : referenceAngle + 90.0;
        double target = nearestDirectedAngle(current, targetBase);
        return setAngleAroundCenter(moving, target);
    }

    private static SketchGeometry.Line setAngleAroundCenter(SketchGeometry.Line line, double angleDeg) {
        double cx = (line.a.xMm + line.b.xMm) * 0.5;
        double cy = (line.a.yMm + line.b.yMm) * 0.5;
        double half = line.lengthMm() * 0.5;
        double r = Math.toRadians(angleDeg);
        double vx = half * Math.cos(r);
        double vy = half * Math.sin(r);
        return new SketchGeometry.Line(line.id(),
                new SketchGeometry.Point(cx - vx, cy - vy),
                new SketchGeometry.Point(cx + vx, cy + vy));
    }

    private static SketchGeometry.Line withEndpoint(SketchGeometry.Line line, int index,
                                                     SketchGeometry.Point value) {
        SketchGeometry.Point p = new SketchGeometry.Point(value.xMm, value.yMm);
        return index == 0
                ? new SketchGeometry.Line(line.id(), p, line.b)
                : new SketchGeometry.Line(line.id(), line.a, p);
    }

    private static SketchGeometry.Point endpoint(SketchGeometry.Line line, int index) {
        return index == 0 ? line.a : line.b;
    }

    private static SketchGeometry.Point projectToEntity(SketchEntity host, SketchGeometry.Point p) {
        if (host instanceof SketchGeometry.Line) {
            return projectToSupportingLine((SketchGeometry.Line) host, p);
        }
        if (host instanceof SketchGeometry.Circle) {
            SketchGeometry.Circle circle = (SketchGeometry.Circle) host;
            return projectToCircle(circle.center, circle.radiusMm, p);
        }
        if (host instanceof SketchGeometry.Arc) {
            SketchGeometry.Arc arc = (SketchGeometry.Arc) host;
            return projectToCircle(arc.center, arc.radiusMm, p);
        }
        throw new IllegalArgumentException("Unsupported Point-on-Entity host");
    }

    private static SketchGeometry.Point projectToSupportingLine(SketchGeometry.Line line, SketchGeometry.Point p) {
        double dx = line.b.xMm - line.a.xMm;
        double dy = line.b.yMm - line.a.yMm;
        double l2 = dx * dx + dy * dy;
        if (l2 <= EPS) throw new IllegalStateException("Degenerate line host reached projection");
        double t = ((p.xMm - line.a.xMm) * dx + (p.yMm - line.a.yMm) * dy) / l2;
        return new SketchGeometry.Point(line.a.xMm + t * dx, line.a.yMm + t * dy);
    }

    private static SketchGeometry.Point projectToCircle(SketchGeometry.Point center,
                                                         double radiusMm,
                                                         SketchGeometry.Point p) {
        double dx = p.xMm - center.xMm;
        double dy = p.yMm - center.yMm;
        double len = Math.hypot(dx, dy);
        if (len <= EPS) {
            return new SketchGeometry.Point(center.xMm + radiusMm, center.yMm);
        }
        double scale = radiusMm / len;
        return new SketchGeometry.Point(center.xMm + dx * scale, center.yMm + dy * scale);
    }

    private static double maxResidual(List<SketchConstraint> constraints,
                                      Map<String, SketchEntity> entities) {
        double max = 0.0;
        for (SketchConstraint c : constraints) max = Math.max(max, residual(c, entities));
        return max;
    }

    private static double residual(SketchConstraint c, Map<String, SketchEntity> entities) {
        if (c.kind == SketchConstraint.Kind.FIXED) return 0.0;
        SketchGeometry.Line a = line(entities, c.primaryEntityId);
        switch (c.kind) {
            case HORIZONTAL:
                return Math.abs(a.b.yMm - a.a.yMm);
            case VERTICAL:
                return Math.abs(a.b.xMm - a.a.xMm);
            case PARALLEL: {
                SketchGeometry.Line b = line(entities, c.secondaryEntityId);
                return normalizedCrossResidual(a, b);
            }
            case PERPENDICULAR: {
                SketchGeometry.Line b = line(entities, c.secondaryEntityId);
                return normalizedDotResidual(a, b);
            }
            case COINCIDENT: {
                SketchGeometry.Line b = line(entities, c.secondaryEntityId);
                return distance(endpoint(a, c.primaryPointIndex), endpoint(b, c.secondaryPointIndex));
            }
            case MIDPOINT: {
                SketchGeometry.Line host = line(entities, c.secondaryEntityId);
                return distance(endpoint(a, c.primaryPointIndex), midpoint(host));
            }
            case POINT_ON_ENTITY: {
                SketchEntity host = entities.get(c.secondaryEntityId);
                SketchGeometry.Point p = endpoint(a, c.primaryPointIndex);
                return distance(p, projectToEntity(host, p));
            }
            default:
                return Double.POSITIVE_INFINITY;
        }
    }

    private static double normalizedCrossResidual(SketchGeometry.Line a, SketchGeometry.Line b) {
        double ax = a.b.xMm - a.a.xMm, ay = a.b.yMm - a.a.yMm;
        double bx = b.b.xMm - b.a.xMm, by = b.b.yMm - b.a.yMm;
        double denom = Math.max(EPS, Math.hypot(ax, ay) * Math.hypot(bx, by));
        double value = Math.abs(ax * by - ay * bx) / denom;
        return value <= ANGLE_TOL ? 0.0 : value;
    }

    private static double normalizedDotResidual(SketchGeometry.Line a, SketchGeometry.Line b) {
        double ax = a.b.xMm - a.a.xMm, ay = a.b.yMm - a.a.yMm;
        double bx = b.b.xMm - b.a.xMm, by = b.b.yMm - b.a.yMm;
        double denom = Math.max(EPS, Math.hypot(ax, ay) * Math.hypot(bx, by));
        double value = Math.abs(ax * bx + ay * by) / denom;
        return value <= ANGLE_TOL ? 0.0 : value;
    }

    private static double distance(SketchGeometry.Point a, SketchGeometry.Point b) {
        return Math.hypot(b.xMm - a.xMm, b.yMm - a.yMm);
    }

    private static double angleDeg(SketchGeometry.Line line) {
        return normalize360(Math.toDegrees(Math.atan2(line.b.yMm - line.a.yMm,
                line.b.xMm - line.a.xMm)));
    }

    private static double nearestDirectedAngle(double current, double target) {
        double a = normalize360(target);
        double b = normalize360(target + 180.0);
        return angleDistance(current, a) <= angleDistance(current, b) ? a : b;
    }

    private static double angleDistance(double a, double b) {
        double d = Math.abs(normalize360(a) - normalize360(b));
        return Math.min(d, 360.0 - d);
    }

    private static double normalize360(double angle) {
        double v = angle % 360.0;
        return v < 0.0 ? v + 360.0 : v;
    }

    private static Result result(Status status, int iterations, double residual,
                                 String message, LinkedHashMap<String, SketchEntity> entities) {
        return new Result(status, iterations, residual, message, entities.values());
    }

    private static final class FixedState {
        final LinkedHashMap<String, SketchEntity> wholeEntities = new LinkedHashMap<>();
        final LinkedHashMap<String, LinkedHashMap<Integer, SketchGeometry.Point>> pointAnchors =
                new LinkedHashMap<>();
    }
}

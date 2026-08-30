package ir.chobyar.sketch;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import ir.chobyar.sketch.core.SketchConstraint;
import ir.chobyar.sketch.core.SketchDocument;
import ir.chobyar.sketch.core.SketchEntity;
import ir.chobyar.sketch.core.SketchGeometry;

/** Stateless presentation projection for model-owned endpoint constraints. */
public final class ModelConstraintBadgeProjection {
    private ModelConstraintBadgeProjection() {}

    public enum BadgeKind {
        COINCIDENT("Coincident"),
        POINT_ON_ENTITY("Point on entity"),
        MIDPOINT("Midpoint");

        public final String accessibilityLabel;

        BadgeKind(String accessibilityLabel) {
            this.accessibilityLabel = accessibilityLabel;
        }
    }

    public static final class Badge {
        public final String constraintId;
        public final BadgeKind kind;
        public final String pointEntityId;
        public final int pointIndex;
        public final String targetEntityId;
        public final int targetPointIndex;
        public final double xMm;
        public final double yMm;

        private Badge(String constraintId, BadgeKind kind, String pointEntityId,
                      int pointIndex, String targetEntityId, int targetPointIndex,
                      double xMm, double yMm) {
            this.constraintId = Objects.requireNonNull(constraintId, "constraintId");
            this.kind = Objects.requireNonNull(kind, "kind");
            this.pointEntityId = Objects.requireNonNull(pointEntityId, "pointEntityId");
            this.pointIndex = pointIndex;
            this.targetEntityId = targetEntityId;
            this.targetPointIndex = targetPointIndex;
            this.xMm = xMm;
            this.yMm = yMm;
        }

        public String accessibilityLabel() { return kind.accessibilityLabel; }
    }

    public static List<Badge> project(SketchDocument document) {
        Objects.requireNonNull(document, "document");
        return project(document.constraints(), document.entities());
    }

    public static List<Badge> project(List<SketchConstraint> constraints,
                                      List<SketchEntity> entities) {
        Objects.requireNonNull(constraints, "constraints");
        Objects.requireNonNull(entities, "entities");
        HashMap<String, SketchEntity> byId = new HashMap<>();
        for (SketchEntity entity : entities) {
            if (entity != null && entity.id() != null) byId.put(entity.id(), entity);
        }
        ArrayList<Badge> out = new ArrayList<>();
        for (SketchConstraint constraint : constraints) {
            Badge badge = projectOne(byId, constraint);
            if (badge != null) out.add(badge);
        }
        return Collections.unmodifiableList(out);
    }

    private static Badge projectOne(Map<String, SketchEntity> entities,
                                    SketchConstraint constraint) {
        if (constraint == null) return null;
        BadgeKind badgeKind;
        if (constraint.kind == SketchConstraint.Kind.COINCIDENT) {
            badgeKind = BadgeKind.COINCIDENT;
        } else if (constraint.kind == SketchConstraint.Kind.POINT_ON_ENTITY) {
            badgeKind = BadgeKind.POINT_ON_ENTITY;
        } else if (constraint.kind == SketchConstraint.Kind.MIDPOINT) {
            badgeKind = BadgeKind.MIDPOINT;
        } else {
            return null;
        }

        SketchGeometry.Point point = endpoint(entities.get(constraint.primaryEntityId),
                constraint.primaryPointIndex);
        if (point == null) return null;
        if (constraint.secondaryEntityId == null
                || entities.get(constraint.secondaryEntityId) == null) return null;
        if (badgeKind == BadgeKind.COINCIDENT
                && endpoint(entities.get(constraint.secondaryEntityId),
                constraint.secondaryPointIndex) == null) return null;
        if (badgeKind == BadgeKind.MIDPOINT
                && !(entities.get(constraint.secondaryEntityId) instanceof SketchGeometry.Line)) return null;

        return new Badge(constraint.id, badgeKind,
                constraint.primaryEntityId, constraint.primaryPointIndex,
                constraint.secondaryEntityId, constraint.secondaryPointIndex,
                point.xMm, point.yMm);
    }

    private static SketchGeometry.Point endpoint(SketchEntity entity, int pointIndex) {
        if (!(entity instanceof SketchGeometry.Line)) return null;
        SketchGeometry.Line line = (SketchGeometry.Line) entity;
        if (pointIndex == 0) return line.a;
        if (pointIndex == 1) return line.b;
        return null;
    }
}

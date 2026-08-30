package ir.chobyar.sketch.core;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Immutable, model-owned sketch constraint identified only by stable ids.
 *
 * This is deliberately solver-agnostic. A future native or Java solver adapter
 * may translate these records into solver handles, but Android Views and object
 * identity must never become the persistence/ownership boundary again.
 */
public final class SketchConstraint {
    public enum Kind {
        COINCIDENT,
        POINT_ON_ENTITY,
        MIDPOINT,
        HORIZONTAL,
        VERTICAL,
        PARALLEL,
        PERPENDICULAR,
        TANGENT,
        EQUAL,
        DISTANCE,
        RADIUS,
        ANGLE,
        FIXED
    }

    public final String id;
    public final Kind kind;
    public final String primaryEntityId;
    public final int primaryPointIndex;
    public final String secondaryEntityId;
    public final int secondaryPointIndex;
    public final double value;
    public final boolean driving;

    public SketchConstraint(String id, Kind kind,
                            String primaryEntityId, int primaryPointIndex,
                            String secondaryEntityId, int secondaryPointIndex,
                            double value, boolean driving) {
        this.id = normalizeRequired(id, "constraint id");
        this.kind = kind == null ? failKind() : kind;
        this.primaryEntityId = normalizeRequired(primaryEntityId, "primary entity id");
        this.primaryPointIndex = requirePointIndex(primaryPointIndex, "primary point index");
        this.secondaryEntityId = normalizeOptional(secondaryEntityId);
        this.secondaryPointIndex = requirePointIndex(secondaryPointIndex, "secondary point index");
        this.value = value;
        this.driving = driving;
        validateShape();
    }

    public static SketchConstraint coincident(String id, String a, int aPoint, String b, int bPoint) {
        return new SketchConstraint(id, Kind.COINCIDENT, a, aPoint, b, bPoint, Double.NaN, true);
    }

    public static SketchConstraint pointOnEntity(String id, String pointOwner, int pointIndex, String hostEntity) {
        return new SketchConstraint(id, Kind.POINT_ON_ENTITY, pointOwner, pointIndex, hostEntity, -1, Double.NaN, true);
    }

    public static SketchConstraint midpoint(String id, String pointOwner, int pointIndex, String hostEntity) {
        return new SketchConstraint(id, Kind.MIDPOINT, pointOwner, pointIndex, hostEntity, -1, Double.NaN, true);
    }

    public static SketchConstraint horizontal(String id, String entityId) {
        return unary(id, Kind.HORIZONTAL, entityId);
    }

    public static SketchConstraint vertical(String id, String entityId) {
        return unary(id, Kind.VERTICAL, entityId);
    }

    public static SketchConstraint parallel(String id, String a, String b) {
        return binary(id, Kind.PARALLEL, a, b);
    }

    public static SketchConstraint perpendicular(String id, String a, String b) {
        return binary(id, Kind.PERPENDICULAR, a, b);
    }

    public static SketchConstraint tangent(String id, String a, String b) {
        return binary(id, Kind.TANGENT, a, b);
    }

    public static SketchConstraint equal(String id, String a, String b) {
        return binary(id, Kind.EQUAL, a, b);
    }

    public static SketchConstraint distance(String id, String entityId, double mm) {
        return dimension(id, Kind.DISTANCE, entityId, mm);
    }

    public static SketchConstraint radius(String id, String entityId, double mm) {
        return dimension(id, Kind.RADIUS, entityId, mm);
    }

    public static SketchConstraint angle(String id, String a, String b, double degrees) {
        return new SketchConstraint(id, Kind.ANGLE, a, -1, b, -1, degrees, true);
    }

    public static SketchConstraint fixed(String id, String entityId) {
        return unary(id, Kind.FIXED, entityId);
    }

    private static SketchConstraint unary(String id, Kind kind, String entityId) {
        return new SketchConstraint(id, kind, entityId, -1, null, -1, Double.NaN, true);
    }

    private static SketchConstraint binary(String id, Kind kind, String a, String b) {
        return new SketchConstraint(id, kind, a, -1, b, -1, Double.NaN, true);
    }

    private static SketchConstraint dimension(String id, Kind kind, String entityId, double value) {
        return new SketchConstraint(id, kind, entityId, -1, null, -1, value, true);
    }

    public SketchConstraint copy() {
        return new SketchConstraint(id, kind, primaryEntityId, primaryPointIndex,
                secondaryEntityId, secondaryPointIndex, value, driving);
    }

    public Set<String> referencedEntityIds() {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        out.add(primaryEntityId);
        if (secondaryEntityId != null) out.add(secondaryEntityId);
        return Collections.unmodifiableSet(out);
    }

    public boolean references(String entityId) {
        if (entityId == null) return false;
        String normalized = entityId.trim();
        return primaryEntityId.equals(normalized)
                || (secondaryEntityId != null && secondaryEntityId.equals(normalized));
    }

    private void validateShape() {
        boolean needsSecondary = kind == Kind.COINCIDENT
                || kind == Kind.POINT_ON_ENTITY
                || kind == Kind.MIDPOINT
                || kind == Kind.PARALLEL
                || kind == Kind.PERPENDICULAR
                || kind == Kind.TANGENT
                || kind == Kind.EQUAL
                || kind == Kind.ANGLE;
        if (needsSecondary && secondaryEntityId == null) {
            throw new IllegalArgumentException(kind + " requires a secondary entity");
        }
        if (!needsSecondary && secondaryEntityId != null) {
            throw new IllegalArgumentException(kind + " does not accept a secondary entity");
        }

        boolean dimension = kind == Kind.DISTANCE || kind == Kind.RADIUS || kind == Kind.ANGLE;
        if (dimension) {
            if (!SketchGeometry.finite(value)) throw new IllegalArgumentException(kind + " value must be finite");
            if ((kind == Kind.DISTANCE || kind == Kind.RADIUS) && value <= 0.0) {
                throw new IllegalArgumentException(kind + " value must be positive");
            }
        } else if (!Double.isNaN(value)) {
            throw new IllegalArgumentException(kind + " does not accept a numeric value");
        }

        boolean pointConstraint = kind == Kind.COINCIDENT || kind == Kind.POINT_ON_ENTITY
                || kind == Kind.MIDPOINT;
        if (pointConstraint && primaryPointIndex < 0) {
            throw new IllegalArgumentException(kind + " requires primary point index");
        }
        if (pointConstraint && !isEndpointIndex(primaryPointIndex)) {
            throw new IllegalArgumentException(kind + " primary point index must be endpoint 0 or 1");
        }
        if (kind == Kind.COINCIDENT && secondaryPointIndex < 0) {
            throw new IllegalArgumentException("COINCIDENT requires secondary point index");
        }
        if (kind == Kind.COINCIDENT && !isEndpointIndex(secondaryPointIndex)) {
            throw new IllegalArgumentException("COINCIDENT secondary point index must be endpoint 0 or 1");
        }
        if (!pointConstraint && (primaryPointIndex >= 0 || secondaryPointIndex >= 0)) {
            throw new IllegalArgumentException(kind + " does not accept point indexes");
        }
        if ((kind == Kind.POINT_ON_ENTITY || kind == Kind.MIDPOINT) && secondaryPointIndex >= 0) {
            throw new IllegalArgumentException(kind + " host uses whole entity");
        }
    }

    private static boolean isEndpointIndex(int index) {
        return index == 0 || index == 1;
    }

    private static Kind failKind() {
        throw new NullPointerException("constraint kind");
    }

    private static int requirePointIndex(int index, String label) {
        if (index < -1 || index > 7) throw new IllegalArgumentException(label + " out of range: " + index);
        return index;
    }

    private static String normalizeRequired(String value, String label) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(label + " is empty");
        return normalized;
    }

    private static String normalizeOptional(String value) {
        if (value == null) return null;
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }
}

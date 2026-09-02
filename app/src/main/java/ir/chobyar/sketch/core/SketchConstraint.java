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
        SYMMETRY,
        EQUAL,
        DISTANCE,
        RADIUS,
        LINE_ANGLE,
        ANGLE,
        FIXED
    }

    public final String id;
    public final Kind kind;
    public final String primaryEntityId;
    public final int primaryPointIndex;
    public final String secondaryEntityId;
    public final int secondaryPointIndex;
    /** Optional third stable-id reference. K3.14 uses it for the Symmetry axis. */
    public final String tertiaryEntityId;
    public final double value;
    public final boolean driving;

    /** Backward-compatible constructor for all one- and two-entity constraints. */
    public SketchConstraint(String id, Kind kind,
                            String primaryEntityId, int primaryPointIndex,
                            String secondaryEntityId, int secondaryPointIndex,
                            double value, boolean driving) {
        this(id, kind, primaryEntityId, primaryPointIndex,
                secondaryEntityId, secondaryPointIndex, null, value, driving);
    }

    /** Stable-id constructor for constraints that can reference three entities. */
    public SketchConstraint(String id, Kind kind,
                            String primaryEntityId, int primaryPointIndex,
                            String secondaryEntityId, int secondaryPointIndex,
                            String tertiaryEntityId,
                            double value, boolean driving) {
        this.id = normalizeRequired(id, "constraint id");
        this.kind = kind == null ? failKind() : kind;
        this.primaryEntityId = normalizeRequired(primaryEntityId, "primary entity id");
        this.primaryPointIndex = requirePointIndex(primaryPointIndex, "primary point index");
        this.secondaryEntityId = normalizeOptional(secondaryEntityId);
        this.secondaryPointIndex = requirePointIndex(secondaryPointIndex, "secondary point index");
        this.tertiaryEntityId = normalizeOptional(tertiaryEntityId);
        this.value = this.kind == Kind.LINE_ANGLE ? normalizeLineAngle(value) : value;
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

    /** source, driven mirror, then symmetry axis. */
    public static SketchConstraint symmetry(String id, String source, String mirror, String axis) {
        return new SketchConstraint(id, Kind.SYMMETRY,
                source, -1, mirror, -1, axis, Double.NaN, true);
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

    /** Absolute undirected orientation of one line, canonicalized to [0, 180) degrees. */
    public static SketchConstraint lineAngle(String id, String entityId, double degrees) {
        return dimension(id, Kind.LINE_ANGLE, entityId, degrees);
    }

    public static SketchConstraint angle(String id, String a, String b, double degrees) {
        return new SketchConstraint(id, Kind.ANGLE, a, -1, b, -1, degrees, true);
    }

    /** Backward-compatible whole-entity FIXED constraint. */
    public static SketchConstraint fixed(String id, String entityId) {
        return unary(id, Kind.FIXED, entityId);
    }

    /**
     * Point-level FIXED anchor used by Shapr-style Lock/Unlock.
     * Point meaning is entity-specific and validated by the solver/model layer:
     * line 0/1 = endpoints; circle/arc 0 = center for the first K3.8 slice.
     */
    public static SketchConstraint fixedPoint(String id, String entityId, int pointIndex) {
        return new SketchConstraint(id, Kind.FIXED, entityId, pointIndex,
                null, -1, Double.NaN, true);
    }

    public boolean fixesWholeEntity() {
        return kind == Kind.FIXED && primaryPointIndex < 0;
    }

    public boolean fixesPoint() {
        return kind == Kind.FIXED && primaryPointIndex >= 0;
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
                secondaryEntityId, secondaryPointIndex, tertiaryEntityId, value, driving);
    }

    public Set<String> referencedEntityIds() {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        out.add(primaryEntityId);
        if (secondaryEntityId != null) out.add(secondaryEntityId);
        if (tertiaryEntityId != null) out.add(tertiaryEntityId);
        return Collections.unmodifiableSet(out);
    }

    public boolean references(String entityId) {
        if (entityId == null) return false;
        String normalized = entityId.trim();
        return primaryEntityId.equals(normalized)
                || (secondaryEntityId != null && secondaryEntityId.equals(normalized))
                || (tertiaryEntityId != null && tertiaryEntityId.equals(normalized));
    }

    private void validateShape() {
        boolean needsSecondary = kind == Kind.COINCIDENT
                || kind == Kind.POINT_ON_ENTITY
                || kind == Kind.MIDPOINT
                || kind == Kind.PARALLEL
                || kind == Kind.PERPENDICULAR
                || kind == Kind.TANGENT
                || kind == Kind.SYMMETRY
                || kind == Kind.EQUAL
                || kind == Kind.ANGLE;
        if (needsSecondary && secondaryEntityId == null) {
            throw new IllegalArgumentException(kind + " requires a secondary entity");
        }
        if (!needsSecondary && secondaryEntityId != null) {
            throw new IllegalArgumentException(kind + " does not accept a secondary entity");
        }

        if (kind == Kind.SYMMETRY) {
            if (tertiaryEntityId == null) {
                throw new IllegalArgumentException("SYMMETRY requires a tertiary axis entity");
            }
            if (primaryEntityId.equals(secondaryEntityId)
                    || primaryEntityId.equals(tertiaryEntityId)
                    || secondaryEntityId.equals(tertiaryEntityId)) {
                throw new IllegalArgumentException("SYMMETRY requires three distinct entities");
            }
        } else if (tertiaryEntityId != null) {
            throw new IllegalArgumentException(kind + " does not accept a tertiary entity");
        }

        boolean dimension = kind == Kind.DISTANCE || kind == Kind.RADIUS
                || kind == Kind.LINE_ANGLE || kind == Kind.ANGLE;
        if (dimension) {
            if (!SketchGeometry.finite(value)) throw new IllegalArgumentException(kind + " value must be finite");
            if ((kind == Kind.DISTANCE || kind == Kind.RADIUS) && value <= 0.0) {
                throw new IllegalArgumentException(kind + " value must be positive");
            }
        } else if (!Double.isNaN(value)) {
            throw new IllegalArgumentException(kind + " does not accept a numeric value");
        }

        boolean relationshipPointConstraint = kind == Kind.COINCIDENT
                || kind == Kind.POINT_ON_ENTITY || kind == Kind.MIDPOINT;
        if (relationshipPointConstraint && primaryPointIndex < 0) {
            throw new IllegalArgumentException(kind + " requires primary point index");
        }
        if (relationshipPointConstraint && !isEndpointIndex(primaryPointIndex)) {
            throw new IllegalArgumentException(kind + " primary point index must be endpoint 0 or 1");
        }
        if (kind == Kind.COINCIDENT && secondaryPointIndex < 0) {
            throw new IllegalArgumentException("COINCIDENT requires secondary point index");
        }
        if (kind == Kind.COINCIDENT && !isEndpointIndex(secondaryPointIndex)) {
            throw new IllegalArgumentException("COINCIDENT secondary point index must be endpoint 0 or 1");
        }
        if ((kind == Kind.POINT_ON_ENTITY || kind == Kind.MIDPOINT) && secondaryPointIndex >= 0) {
            throw new IllegalArgumentException(kind + " host uses whole entity");
        }
        if (kind == Kind.FIXED) {
            if (secondaryPointIndex >= 0) {
                throw new IllegalArgumentException("FIXED does not accept a secondary point index");
            }
        } else if (!relationshipPointConstraint
                && (primaryPointIndex >= 0 || secondaryPointIndex >= 0)) {
            throw new IllegalArgumentException(kind + " does not accept point indexes");
        }
    }

    private static boolean isEndpointIndex(int index) {
        return index == 0 || index == 1;
    }

    private static double normalizeLineAngle(double degrees) {
        if (!SketchGeometry.finite(degrees) || degrees < 0.0 || degrees > 180.0) {
            throw new IllegalArgumentException("LINE_ANGLE must be between 0 and 180 degrees");
        }
        return degrees == 180.0 ? 0.0 : degrees;
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

package ir.chobyar.sketch;

import java.util.Objects;

/**
 * Stateless UX contract for model-authoritative endpoint constraints.
 *
 * This class intentionally owns no geometry and stores no constraint state.
 * It exists only to carry deterministic interaction intent from View input to
 * the SketchDocument/Solver layer. Touch and stylus must produce the same
 * stable entity-id + point-index contract.
 */
public final class ConstraintInteractionContract {
    private ConstraintInteractionContract() {}

    public enum Kind {
        COINCIDENT,
        POINT_ON_ENTITY
    }

    public enum SnapFeedback {
        ENDPOINT("Endpoint"),
        ON_EDGE("On edge"),
        NONE("");

        public final String label;

        SnapFeedback(String label) {
            this.label = label;
        }
    }

    public enum ResultCode {
        APPLIED,
        INVALID_SELECTION,
        CONFLICT,
        UNSOLVABLE
    }

    public static final class PointRef {
        public final String entityId;
        public final int pointIndex;

        public PointRef(String entityId, int pointIndex) {
            this.entityId = requireId(entityId, "entityId");
            if (pointIndex < 0) throw new IllegalArgumentException("pointIndex must be >= 0");
            this.pointIndex = pointIndex;
        }

        @Override public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof PointRef)) return false;
            PointRef that = (PointRef) other;
            return pointIndex == that.pointIndex && entityId.equals(that.entityId);
        }

        @Override public int hashCode() {
            return Objects.hash(entityId, pointIndex);
        }
    }

    public static final class Intent {
        public final Kind kind;
        public final PointRef drivenPoint;
        public final PointRef targetPoint;
        public final String hostEntityId;

        private Intent(Kind kind, PointRef drivenPoint, PointRef targetPoint, String hostEntityId) {
            this.kind = Objects.requireNonNull(kind, "kind");
            this.drivenPoint = Objects.requireNonNull(drivenPoint, "drivenPoint");
            this.targetPoint = targetPoint;
            this.hostEntityId = hostEntityId;
        }

        public static Intent coincident(PointRef drivenPoint, PointRef targetPoint) {
            if (drivenPoint.equals(targetPoint)) {
                throw new IllegalArgumentException("Coincident endpoints must be distinct references");
            }
            return new Intent(Kind.COINCIDENT, drivenPoint,
                    Objects.requireNonNull(targetPoint, "targetPoint"), null);
        }

        public static Intent pointOnEntity(PointRef drivenPoint, String hostEntityId) {
            String host = requireId(hostEntityId, "hostEntityId");
            if (drivenPoint.entityId.equals(host)) {
                throw new IllegalArgumentException("Point-on-entity host must differ from driven entity");
            }
            return new Intent(Kind.POINT_ON_ENTITY, drivenPoint, null, host);
        }
    }

    public static final class Result {
        public final ResultCode code;
        public final String message;

        private Result(ResultCode code, String message) {
            this.code = Objects.requireNonNull(code, "code");
            this.message = message == null ? "" : message;
        }

        public static Result applied() {
            return new Result(ResultCode.APPLIED, "");
        }

        public static Result invalidSelection(String message) {
            return new Result(ResultCode.INVALID_SELECTION, safeMessage(message,
                    "Select an endpoint and a valid constraint target"));
        }

        public static Result conflict(String message) {
            return new Result(ResultCode.CONFLICT, safeMessage(message,
                    "Constraint conflicts with existing sketch constraints; geometry was left unchanged"));
        }

        public static Result unsolvable(String message) {
            return new Result(ResultCode.UNSOLVABLE, safeMessage(message,
                    "Constraint could not be solved; geometry was left unchanged"));
        }

        public boolean mutatesGeometry() {
            return code == ResultCode.APPLIED;
        }
    }

    /**
     * Required ParametricSketchCanvasView seam for K3.6d integration:
     *
     * protected ConstraintInteractionContract.Result
     *     onModelCoincidentRequested(ConstraintInteractionContract.Intent intent)
     *
     * protected ConstraintInteractionContract.Result
     *     onModelPointOnEntityRequested(ConstraintInteractionContract.Intent intent)
     *
     * protected void
     *     onConstraintTargetFeedback(ConstraintInteractionContract.Intent intent,
     *                                ConstraintInteractionContract.SnapFeedback feedback)
     *
     * protected boolean isModelEndpointConstraintAuthorityEnabled()
     *
     * The base implementation should return false / INVALID_SELECTION and must
     * never create duplicate geometry or constraint truth. K33MirroredCadCanvasView
     * should override these hooks and perform one SketchDocument transaction.
     */
    public static String requiredProtectedHookContract() {
        return "model-endpoint-constraint-hooks-v1";
    }

    private static String requireId(String value, String name) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(name + " must be a stable non-empty id");
        }
        return value;
    }

    private static String safeMessage(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value;
    }
}

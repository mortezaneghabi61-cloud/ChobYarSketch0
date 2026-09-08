package ir.chobyar.sketch;

import java.util.Objects;

/**
 * Stable typed identity for one row in the project Items hierarchy.
 *
 * Presentation labels are intentionally not identity. Callers must route
 * actions by {@link #kind} and {@link #stableId}; visibility is a projection of
 * the owning model state and must never become a second source of truth here.
 */
public final class CadItemRef {
    public enum Kind { BODY, SKETCH, PLANE, REFERENCE_IMAGE }

    public final Kind kind;
    public final String stableId;
    public final String label;
    public final boolean visible;

    public CadItemRef(Kind kind, String stableId, String label, boolean visible) {
        if (kind == null) throw new NullPointerException("kind");
        String id = clean(stableId);
        String text = clean(label);
        if (id.isEmpty()) throw new IllegalArgumentException("Item stable id is empty");
        if (text.isEmpty()) throw new IllegalArgumentException("Item label is empty");
        this.kind = kind;
        this.stableId = id;
        this.label = text;
        this.visible = visible;
    }

    /** Equality follows stable project identity, not mutable presentation state. */
    @Override public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof CadItemRef)) return false;
        CadItemRef that = (CadItemRef) other;
        return kind == that.kind && stableId.equals(that.stableId);
    }

    @Override public int hashCode() {
        return Objects.hash(kind, stableId);
    }

    @Override public String toString() {
        return kind.name() + ":" + stableId + " • " + label + (visible ? "" : " • Hidden");
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}

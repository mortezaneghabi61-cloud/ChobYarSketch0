package ir.chobyar.sketch;

import java.util.Arrays;

/** Stable sketch/profile payload passed to solid-creation operations. */
public final class CadProfile {
    public enum Type { POLYGON, CIRCLE }

    private final Type type;
    private final double[] data;

    private CadProfile(Type type, double[] data) {
        this.type = type;
        this.data = data;
    }

    public static CadProfile polygon(double[] xyzMm) {
        if (xyzMm == null || xyzMm.length < 9 || xyzMm.length % 3 != 0) {
            throw new IllegalArgumentException("Polygon profile requires at least three XYZ points");
        }
        for (double value : xyzMm) {
            if (!Double.isFinite(value)) {
                throw new IllegalArgumentException("Polygon profile coordinates must be finite");
            }
        }
        return new CadProfile(Type.POLYGON, xyzMm.clone());
    }

    public static CadProfile circle(CadVector3 centerMm,
                                    CadVector3 normal,
                                    CadVector3 uAxis,
                                    double radiusMm) {
        if (centerMm == null || normal == null || uAxis == null
                || !centerMm.isFinite() || !normal.isNonZero() || !uAxis.isNonZero()
                || !Double.isFinite(radiusMm) || radiusMm <= 0.0) {
            throw new IllegalArgumentException("Invalid circle profile");
        }
        return new CadProfile(Type.CIRCLE, new double[]{
                centerMm.x, centerMm.y, centerMm.z,
                normal.x, normal.y, normal.z,
                uAxis.x, uAxis.y, uAxis.z,
                radiusMm
        });
    }

    public Type type() {
        return type;
    }

    int nativeProfileType() {
        return type == Type.CIRCLE
                ? NativeBRepKernel.OCCT_PROFILE_CIRCLE
                : NativeBRepKernel.OCCT_PROFILE_POLYGON;
    }

    double[] nativeDataCopy() {
        return data.clone();
    }

    @Override
    public String toString() {
        return "CadProfile{" + type + ", values=" + data.length + "}";
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof CadProfile)) return false;
        CadProfile o = (CadProfile) other;
        return type == o.type && Arrays.equals(data, o.data);
    }

    @Override
    public int hashCode() {
        return 31 * type.hashCode() + Arrays.hashCode(data);
    }
}

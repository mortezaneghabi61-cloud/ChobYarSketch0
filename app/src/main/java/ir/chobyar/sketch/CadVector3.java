package ir.chobyar.sketch;

/** Immutable model-space vector. Units are millimeters unless a method says otherwise. */
public final class CadVector3 {
    public static final CadVector3 ZERO = new CadVector3(0.0, 0.0, 0.0);

    public final double x;
    public final double y;
    public final double z;

    public CadVector3(double x, double y, double z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public boolean isFinite() {
        return Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z);
    }

    public double lengthSquared() {
        return x * x + y * y + z * z;
    }

    public boolean isNonZero() {
        return isFinite() && lengthSquared() > 1e-20;
    }

    Geometry3D.Vec3 toLegacyVec3() {
        return new Geometry3D.Vec3((float) x, (float) y, (float) z);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof CadVector3)) return false;
        CadVector3 o = (CadVector3) other;
        return Double.compare(x, o.x) == 0
                && Double.compare(y, o.y) == 0
                && Double.compare(z, o.z) == 0;
    }

    @Override
    public int hashCode() {
        long a = Double.doubleToLongBits(x);
        long b = Double.doubleToLongBits(y);
        long c = Double.doubleToLongBits(z);
        int result = (int) (a ^ (a >>> 32));
        result = 31 * result + (int) (b ^ (b >>> 32));
        result = 31 * result + (int) (c ^ (c >>> 32));
        return result;
    }

    @Override
    public String toString() {
        return "CadVector3{" + x + ", " + y + ", " + z + "}";
    }
}

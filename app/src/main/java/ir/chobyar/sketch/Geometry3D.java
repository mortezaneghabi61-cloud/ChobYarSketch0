package ir.chobyar.sketch;

/**
 * Minimal dependency-free 3D math foundation for ChobYar.
 *
 * The current sketch engine stores coordinates locally in millimeters. A Plane3D
 * maps those local (u,v) sketch coordinates into real XYZ model space. The same
 * class can later be passed to the native solid kernel without changing the UI
 * contract.
 */
public final class Geometry3D {
    private Geometry3D() {}

    public static final class Vec3 {
        public final float x, y, z;
        public Vec3(float x, float y, float z) { this.x=x; this.y=y; this.z=z; }
        public Vec3 add(Vec3 o) { return new Vec3(x+o.x, y+o.y, z+o.z); }
        public Vec3 sub(Vec3 o) { return new Vec3(x-o.x, y-o.y, z-o.z); }
        public Vec3 mul(float s) { return new Vec3(x*s, y*s, z*s); }
        public float dot(Vec3 o) { return x*o.x + y*o.y + z*o.z; }
        public Vec3 cross(Vec3 o) {
            return new Vec3(y*o.z-z*o.y, z*o.x-x*o.z, x*o.y-y*o.x);
        }
        public float length() { return (float)Math.sqrt(x*x+y*y+z*z); }
        public Vec3 normalized() {
            float l=length();
            return l < 1e-7f ? new Vec3(0,0,0) : mul(1f/l);
        }
    }

    public static final class Plane3D {
        public final Vec3 origin;
        public final Vec3 u;
        public final Vec3 v;
        public final Vec3 normal;
        public final String label;

        public Plane3D(Vec3 origin, Vec3 u, Vec3 v, String label) {
            this.origin = origin;
            this.u = u.normalized();
            Vec3 n = this.u.cross(v).normalized();
            this.normal = n;
            this.v = n.cross(this.u).normalized();
            this.label = label == null ? "Plane" : label;
        }

        public Vec3 point(float localUmm, float localVmm) {
            return origin.add(u.mul(localUmm)).add(v.mul(localVmm));
        }

        public Plane3D offset(float mm, String newLabel) {
            return new Plane3D(origin.add(normal.mul(mm)), u, v, newLabel);
        }
    }

    public static Plane3D xy() {
        return new Plane3D(new Vec3(0,0,0), new Vec3(1,0,0), new Vec3(0,1,0), "XY • بالا");
    }

    public static Plane3D xz() {
        return new Plane3D(new Vec3(0,0,0), new Vec3(1,0,0), new Vec3(0,0,1), "XZ • روبرو");
    }

    public static Plane3D yz() {
        return new Plane3D(new Vec3(0,0,0), new Vec3(0,1,0), new Vec3(0,0,1), "YZ • بغل");
    }
}

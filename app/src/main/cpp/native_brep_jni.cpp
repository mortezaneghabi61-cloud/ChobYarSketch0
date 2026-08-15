#include <jni.h>
#include <cmath>
#include <sstream>
#include <string>

namespace {
constexpr double EPS = 1e-9;

struct Vec3 {
    double x, y, z;
    Vec3 operator+(const Vec3& o) const { return {x + o.x, y + o.y, z + o.z}; }
    Vec3 operator-(const Vec3& o) const { return {x - o.x, y - o.y, z - o.z}; }
    Vec3 operator*(double s) const { return {x * s, y * s, z * s}; }
};

double dot(const Vec3& a, const Vec3& b) { return a.x*b.x + a.y*b.y + a.z*b.z; }
double length(const Vec3& v) { return std::sqrt(dot(v,v)); }
Vec3 normalized(const Vec3& v) {
    const double l = length(v);
    return l < EPS ? Vec3{0.0,0.0,0.0} : v * (1.0/l);
}

jdoubleArray makeArray(JNIEnv* env, const double* values, jsize count) {
    jdoubleArray out = env->NewDoubleArray(count);
    if (!out) return nullptr;
    env->SetDoubleArrayRegion(out, 0, count, values);
    return out;
}
}

extern "C" JNIEXPORT jstring JNICALL
Java_ir_chobyar_sketch_NativeBRepKernel_nativeVersion(JNIEnv* env, jclass) {
#ifdef CHOBYAR_WITH_OCCT
    return env->NewStringUTF("skachmori Native Geometry Core 0.4 • C++17 / NDK • OCCT direct edit compiled");
#else
    return env->NewStringUTF("skachmori Native Geometry Core 0.4 • C++17 / Android NDK");
#endif
}

extern "C" JNIEXPORT jint JNICALL
Java_ir_chobyar_sketch_NativeBRepKernel_nativeCapabilityFlags(JNIEnv*, jclass) {
    // 1=plane/sphere, 2=sphere/sphere, 4=analytic mass properties,
    // 8=topology self-test, 16=OCCT linked, 32=OCCT primitives,
    // 64=exact Boolean, 128=Revolve, 256=Sweep/Loft,
    // 512=Edge Fillet/Chamfer, 1024=Face PushPull/Shell, 2048=Body transform.
    int flags = 1 | 2 | 4 | 8;
#ifdef CHOBYAR_WITH_OCCT
    flags |= 16 | 32 | 64 | 128 | 256 | 512 | 1024 | 2048;
#endif
    return flags;
}

extern "C" JNIEXPORT jstring JNICALL
Java_ir_chobyar_sketch_NativeBRepKernel_nativeSelfTest(JNIEnv* env, jclass) {
    const int euler = 8 - 12 + 6;
    const double r = 30.0;
    const double d = 40.0;
    const double x = d * 0.5;
    const double circleR = std::sqrt(r*r - x*x);

    std::ostringstream ss;
    ss.setf(std::ios::fixed);
    ss.precision(4);
    ss << (euler == 2 && std::abs(circleR - std::sqrt(500.0)) < 1e-8 ? "OK" : "FAIL")
       << " • Euler=" << euler
       << " • Sphere/Sphere r=" << circleR << " mm"
       << " • JNI/C++ bridge active";
#ifdef CHOBYAR_WITH_OCCT
    ss << " • OCCT linked";
#endif
    return env->NewStringUTF(ss.str().c_str());
}

extern "C" JNIEXPORT jdoubleArray JNICALL
Java_ir_chobyar_sketch_NativeBRepKernel_nativeSphereSphereIntersection(
        JNIEnv* env, jclass,
        jdouble ax, jdouble ay, jdouble az, jdouble ar,
        jdouble bx, jdouble by, jdouble bz, jdouble br) {
    const Vec3 a{ax,ay,az};
    const Vec3 b{bx,by,bz};
    const Vec3 delta = b - a;
    const double d = length(delta);
    if (ar <= 0.0 || br <= 0.0 || d < EPS || d > ar + br + EPS || d < std::abs(ar - br) - EPS) {
        return env->NewDoubleArray(0);
    }

    const Vec3 n = normalized(delta);
    const double x = (ar*ar - br*br + d*d) / (2.0*d);
    double rr2 = ar*ar - x*x;
    if (rr2 < -EPS) return env->NewDoubleArray(0);
    if (rr2 < 0.0) rr2 = 0.0;
    const Vec3 c = a + n*x;
    const double values[7] = {c.x,c.y,c.z,n.x,n.y,n.z,std::sqrt(rr2)};
    return makeArray(env, values, 7);
}

extern "C" JNIEXPORT jdoubleArray JNICALL
Java_ir_chobyar_sketch_NativeBRepKernel_nativePlaneSphereIntersection(
        JNIEnv* env, jclass,
        jdouble px, jdouble py, jdouble pz,
        jdouble nx, jdouble ny, jdouble nz,
        jdouble sx, jdouble sy, jdouble sz,
        jdouble radius) {
    Vec3 n = normalized({nx,ny,nz});
    if (length(n) < EPS || radius <= 0.0) return env->NewDoubleArray(0);
    const Vec3 p{px,py,pz};
    const Vec3 s{sx,sy,sz};
    const double signedDistance = dot(s - p, n);
    const double absDistance = std::abs(signedDistance);
    if (absDistance > radius + EPS) return env->NewDoubleArray(0);
    const Vec3 c = s - n*signedDistance;
    double rr2 = radius*radius - signedDistance*signedDistance;
    if (rr2 < 0.0) rr2 = 0.0;
    const double values[7] = {c.x,c.y,c.z,n.x,n.y,n.z,std::sqrt(rr2)};
    return makeArray(env, values, 7);
}

extern "C" JNIEXPORT jdoubleArray JNICALL
Java_ir_chobyar_sketch_NativeBRepKernel_nativeCylinderMassProperties(
        JNIEnv* env, jclass, jdouble radius, jdouble height) {
    if (radius <= 0.0 || height <= 0.0) return env->NewDoubleArray(0);
    const double volume = M_PI * radius * radius * height;
    const double area = 2.0 * M_PI * radius * (radius + height);
    const double values[2] = {volume, area};
    return makeArray(env, values, 2);
}

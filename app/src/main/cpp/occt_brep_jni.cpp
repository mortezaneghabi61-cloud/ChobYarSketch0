#include <jni.h>
#include <atomic>
#include <cmath>
#include <mutex>
#include <sstream>
#include <string>
#include <unordered_map>
#include <vector>

#ifdef CHOBYAR_WITH_OCCT
#include <Standard_Failure.hxx>
#include <Standard_Version.hxx>
#include <TopoDS.hxx>
#include <TopoDS_Face.hxx>
#include <TopoDS_Shape.hxx>
#include <TopoDS_Wire.hxx>
#include <TopExp_Explorer.hxx>
#include <TopAbs_Orientation.hxx>
#include <TopAbs_ShapeEnum.hxx>
#include <TopLoc_Location.hxx>
#include <BRepPrimAPI_MakeBox.hxx>
#include <BRepPrimAPI_MakeCylinder.hxx>
#include <BRepPrimAPI_MakePrism.hxx>
#include <BRepBuilderAPI_MakeFace.hxx>
#include <BRepBuilderAPI_MakePolygon.hxx>
#include <BRepAlgoAPI_Fuse.hxx>
#include <BRepAlgoAPI_Cut.hxx>
#include <BRepAlgoAPI_Common.hxx>
#include <BRepGProp.hxx>
#include <BRepMesh_IncrementalMesh.hxx>
#include <BRep_Tool.hxx>
#include <GProp_GProps.hxx>
#include <Poly_Triangulation.hxx>
#include <Poly_Triangle.hxx>
#include <gp_Ax2.hxx>
#include <gp_Dir.hxx>
#include <gp_Pnt.hxx>
#include <gp_Trsf.hxx>
#include <gp_Vec.hxx>
#endif

namespace {

jdoubleArray emptyArray(JNIEnv* env) {
    return env->NewDoubleArray(0);
}

#ifdef CHOBYAR_WITH_OCCT
std::mutex gShapeMutex;
std::unordered_map<jlong, TopoDS_Shape> gShapes;
std::atomic<jlong> gNextHandle{1};

jlong storeShape(const TopoDS_Shape& shape) {
    if (shape.IsNull()) return 0;
    const jlong h = gNextHandle.fetch_add(1);
    std::lock_guard<std::mutex> lock(gShapeMutex);
    gShapes[h] = shape;
    return h;
}

bool loadShape(jlong handle, TopoDS_Shape& out) {
    std::lock_guard<std::mutex> lock(gShapeMutex);
    auto it = gShapes.find(handle);
    if (it == gShapes.end()) return false;
    out = it->second;
    return !out.IsNull();
}

int countSubShapes(const TopoDS_Shape& shape, TopAbs_ShapeEnum kind) {
    int count = 0;
    for (TopExp_Explorer ex(shape, kind); ex.More(); ex.Next()) ++count;
    return count;
}

double volumeOf(const TopoDS_Shape& shape) {
    GProp_GProps props;
    BRepGProp::VolumeProperties(shape, props, true, false, false);
    return props.Mass();
}

std::string statsText(const TopoDS_Shape& shape) {
    std::ostringstream ss;
    ss.setf(std::ios::fixed);
    ss.precision(4);
    ss << "Volume=" << volumeOf(shape) << " mm3"
       << " • Face=" << countSubShapes(shape, TopAbs_FACE)
       << " • Edge=" << countSubShapes(shape, TopAbs_EDGE)
       << " • Solid=" << countSubShapes(shape, TopAbs_SOLID);
    return ss.str();
}

bool validVector(double x, double y, double z) {
    return std::sqrt(x*x + y*y + z*z) > 1e-10;
}
#endif

}

extern "C" JNIEXPORT jboolean JNICALL
Java_ir_chobyar_sketch_NativeBRepKernel_nativeOcctAvailable(JNIEnv*, jclass) {
#ifdef CHOBYAR_WITH_OCCT
    return JNI_TRUE;
#else
    return JNI_FALSE;
#endif
}

extern "C" JNIEXPORT jstring JNICALL
Java_ir_chobyar_sketch_NativeBRepKernel_nativeOcctVersion(JNIEnv* env, jclass) {
#ifdef CHOBYAR_WITH_OCCT
    std::string s = std::string("Open CASCADE Technology ") + OCC_VERSION_COMPLETE + " • exact B-Rep";
    return env->NewStringUTF(s.c_str());
#else
    return env->NewStringUTF("OCCT not linked for this ABI");
#endif
}

extern "C" JNIEXPORT jstring JNICALL
Java_ir_chobyar_sketch_NativeBRepKernel_nativeOcctSelfTest(JNIEnv* env, jclass) {
#ifdef CHOBYAR_WITH_OCCT
    try {
        const TopoDS_Shape box = BRepPrimAPI_MakeBox(100.0, 80.0, 20.0).Shape();
        const gp_Ax2 axis(gp_Pnt(50.0, 40.0, 0.0), gp_Dir(0.0, 0.0, 1.0));
        const TopoDS_Shape cylinder = BRepPrimAPI_MakeCylinder(axis, 10.0, 20.0).Shape();
        BRepAlgoAPI_Cut cut(box, cylinder);
        cut.Build();
        if (!cut.IsDone() || cut.Shape().IsNull()) {
            return env->NewStringUTF("FAIL • OCCT BRepAlgoAPI_Cut did not complete");
        }
        const TopoDS_Shape result = cut.Shape();
        const double expected = 100.0 * 80.0 * 20.0 - 3.14159265358979323846 * 10.0 * 10.0 * 20.0;
        const double actual = volumeOf(result);
        const double error = std::abs(actual - expected);
        std::ostringstream ss;
        ss.setf(std::ios::fixed);
        ss.precision(4);
        ss << (error < 0.05 ? "OK" : "FAIL")
           << " • OCCT " << OCC_VERSION_COMPLETE
           << " • exact Box-Cylinder Cut"
           << " • V=" << actual << " mm3"
           << " • error=" << error << " mm3"
           << " • Face=" << countSubShapes(result, TopAbs_FACE)
           << " • Edge=" << countSubShapes(result, TopAbs_EDGE);
        return env->NewStringUTF(ss.str().c_str());
    } catch (const Standard_Failure& e) {
        std::string s = std::string("FAIL • OCCT Standard_Failure: ") + (e.GetMessageString() ? e.GetMessageString() : "unknown");
        return env->NewStringUTF(s.c_str());
    } catch (...) {
        return env->NewStringUTF("FAIL • OCCT unknown native exception");
    }
#else
    return env->NewStringUTF("SKIP • OCCT is not linked for this ABI");
#endif
}

extern "C" JNIEXPORT jlong JNICALL
Java_ir_chobyar_sketch_NativeBRepKernel_nativeOcctCreateBox(
        JNIEnv*, jclass, jdouble dx, jdouble dy, jdouble dz) {
#ifdef CHOBYAR_WITH_OCCT
    if (dx <= 0.0 || dy <= 0.0 || dz <= 0.0) return 0;
    try {
        return storeShape(BRepPrimAPI_MakeBox(dx, dy, dz).Shape());
    } catch (...) { return 0; }
#else
    (void)dx; (void)dy; (void)dz;
    return 0;
#endif
}

extern "C" JNIEXPORT jlong JNICALL
Java_ir_chobyar_sketch_NativeBRepKernel_nativeOcctCreateCylinder(
        JNIEnv*, jclass, jdouble cx, jdouble cy, jdouble cz, jdouble radius, jdouble height) {
#ifdef CHOBYAR_WITH_OCCT
    if (radius <= 0.0 || height == 0.0) return 0;
    try {
        gp_Dir direction(0.0, 0.0, height > 0.0 ? 1.0 : -1.0);
        gp_Ax2 axis(gp_Pnt(cx, cy, cz), direction);
        return storeShape(BRepPrimAPI_MakeCylinder(axis, radius, std::abs(height)).Shape());
    } catch (...) { return 0; }
#else
    (void)cx; (void)cy; (void)cz; (void)radius; (void)height;
    return 0;
#endif
}

extern "C" JNIEXPORT jlong JNICALL
Java_ir_chobyar_sketch_NativeBRepKernel_nativeOcctCreateCylinderAxis(
        JNIEnv*, jclass,
        jdouble cx, jdouble cy, jdouble cz,
        jdouble ax, jdouble ay, jdouble az,
        jdouble radius, jdouble height) {
#ifdef CHOBYAR_WITH_OCCT
    if (radius <= 0.0 || height == 0.0 || !validVector(ax,ay,az)) return 0;
    try {
        const double sign = height >= 0.0 ? 1.0 : -1.0;
        gp_Dir direction(ax*sign, ay*sign, az*sign);
        gp_Ax2 axis(gp_Pnt(cx, cy, cz), direction);
        return storeShape(BRepPrimAPI_MakeCylinder(axis, radius, std::abs(height)).Shape());
    } catch (...) { return 0; }
#else
    (void)cx; (void)cy; (void)cz; (void)ax; (void)ay; (void)az; (void)radius; (void)height;
    return 0;
#endif
}

extern "C" JNIEXPORT jlong JNICALL
Java_ir_chobyar_sketch_NativeBRepKernel_nativeOcctCreatePrism(
        JNIEnv* env, jclass, jdoubleArray xyzArray, jdouble vx, jdouble vy, jdouble vz) {
#ifdef CHOBYAR_WITH_OCCT
    if (xyzArray == nullptr || !validVector(vx,vy,vz)) return 0;
    const jsize count = env->GetArrayLength(xyzArray);
    if (count < 9 || count % 3 != 0) return 0;
    std::vector<jdouble> xyz(static_cast<size_t>(count));
    env->GetDoubleArrayRegion(xyzArray, 0, count, xyz.data());
    if (env->ExceptionCheck()) return 0;
    try {
        BRepBuilderAPI_MakePolygon polygon;
        const int pointCount = count / 3;
        for (int i=0;i<pointCount;i++) {
            const int k=i*3;
            polygon.Add(gp_Pnt(xyz[k], xyz[k+1], xyz[k+2]));
        }
        polygon.Close();
        if (!polygon.IsDone()) return 0;
        const TopoDS_Wire wire = polygon.Wire();
        BRepBuilderAPI_MakeFace faceMaker(wire, true);
        if (!faceMaker.IsDone()) return 0;
        const gp_Vec extrusion(vx,vy,vz);
        BRepPrimAPI_MakePrism prism(faceMaker.Face(), extrusion, true, true);
        prism.Build();
        if (!prism.IsDone() || prism.Shape().IsNull()) return 0;
        return storeShape(prism.Shape());
    } catch (...) { return 0; }
#else
    (void)env; (void)xyzArray; (void)vx; (void)vy; (void)vz;
    return 0;
#endif
}

extern "C" JNIEXPORT jlong JNICALL
Java_ir_chobyar_sketch_NativeBRepKernel_nativeOcctBoolean(
        JNIEnv*, jclass, jint operation, jlong leftHandle, jlong rightHandle) {
#ifdef CHOBYAR_WITH_OCCT
    TopoDS_Shape left, right;
    if (!loadShape(leftHandle, left) || !loadShape(rightHandle, right)) return 0;
    try {
        TopoDS_Shape result;
        if (operation == 0) {
            BRepAlgoAPI_Fuse op(left, right); op.Build(); if (!op.IsDone()) return 0; result = op.Shape();
        } else if (operation == 1) {
            BRepAlgoAPI_Cut op(left, right); op.Build(); if (!op.IsDone()) return 0; result = op.Shape();
        } else if (operation == 2) {
            BRepAlgoAPI_Common op(left, right); op.Build(); if (!op.IsDone()) return 0; result = op.Shape();
        } else return 0;
        if (result.IsNull()) return 0;
        return storeShape(result);
    } catch (...) { return 0; }
#else
    (void)operation; (void)leftHandle; (void)rightHandle;
    return 0;
#endif
}

extern "C" JNIEXPORT jdoubleArray JNICALL
Java_ir_chobyar_sketch_NativeBRepKernel_nativeOcctShapeStats(JNIEnv* env, jclass, jlong handle) {
#ifdef CHOBYAR_WITH_OCCT
    TopoDS_Shape shape;
    if (!loadShape(handle, shape)) return emptyArray(env);
    const double values[4] = {
            volumeOf(shape),
            static_cast<double>(countSubShapes(shape, TopAbs_FACE)),
            static_cast<double>(countSubShapes(shape, TopAbs_EDGE)),
            static_cast<double>(countSubShapes(shape, TopAbs_SOLID))
    };
    jdoubleArray out = env->NewDoubleArray(4);
    if (out) env->SetDoubleArrayRegion(out, 0, 4, values);
    return out;
#else
    (void)handle;
    return emptyArray(env);
#endif
}

extern "C" JNIEXPORT jdoubleArray JNICALL
Java_ir_chobyar_sketch_NativeBRepKernel_nativeOcctTriangulate(
        JNIEnv* env, jclass, jlong handle, jdouble deflection) {
#ifdef CHOBYAR_WITH_OCCT
    TopoDS_Shape shape;
    if (!loadShape(handle, shape)) return emptyArray(env);
    if (!(deflection > 0.0)) deflection = 0.35;
    try {
        BRepMesh_IncrementalMesh mesher(shape, deflection, false, 0.35, true);
        mesher.Perform();
        if (!mesher.IsDone()) return emptyArray(env);

        std::vector<double> data;
        data.reserve(4096);
        for (TopExp_Explorer ex(shape, TopAbs_FACE); ex.More(); ex.Next()) {
            const TopoDS_Face face = TopoDS::Face(ex.Current());
            TopLoc_Location location;
            Handle(Poly_Triangulation) triangulation = BRep_Tool::Triangulation(face, location);
            if (triangulation.IsNull()) continue;
            const gp_Trsf transform = location.Transformation();
            for (Standard_Integer i=1; i<=triangulation->NbTriangles(); ++i) {
                Standard_Integer n1=0,n2=0,n3=0;
                triangulation->Triangle(i).Get(n1,n2,n3);
                if (face.Orientation() == TopAbs_REVERSED) std::swap(n2,n3);
                const Standard_Integer ids[3] = {n1,n2,n3};
                for (int j=0;j<3;j++) {
                    gp_Pnt p = triangulation->Node(ids[j]);
                    p.Transform(transform);
                    data.push_back(p.X());
                    data.push_back(p.Y());
                    data.push_back(p.Z());
                }
            }
        }
        if (data.empty()) return emptyArray(env);
        jdoubleArray out = env->NewDoubleArray(static_cast<jsize>(data.size()));
        if (out) env->SetDoubleArrayRegion(out, 0, static_cast<jsize>(data.size()), data.data());
        return out ? out : emptyArray(env);
    } catch (...) { return emptyArray(env); }
#else
    (void)handle; (void)deflection;
    return emptyArray(env);
#endif
}

extern "C" JNIEXPORT jstring JNICALL
Java_ir_chobyar_sketch_NativeBRepKernel_nativeOcctShapeSummary(JNIEnv* env, jclass, jlong handle) {
#ifdef CHOBYAR_WITH_OCCT
    TopoDS_Shape shape;
    if (!loadShape(handle, shape)) return env->NewStringUTF("Shape handle not found");
    const std::string s = statsText(shape);
    return env->NewStringUTF(s.c_str());
#else
    (void)handle;
    return env->NewStringUTF("OCCT not linked");
#endif
}

extern "C" JNIEXPORT void JNICALL
Java_ir_chobyar_sketch_NativeBRepKernel_nativeOcctRelease(JNIEnv*, jclass, jlong handle) {
#ifdef CHOBYAR_WITH_OCCT
    std::lock_guard<std::mutex> lock(gShapeMutex);
    gShapes.erase(handle);
#else
    (void)handle;
#endif
}

extern "C" JNIEXPORT void JNICALL
Java_ir_chobyar_sketch_NativeBRepKernel_nativeOcctClear(JNIEnv*, jclass) {
#ifdef CHOBYAR_WITH_OCCT
    std::lock_guard<std::mutex> lock(gShapeMutex);
    gShapes.clear();
#endif
}

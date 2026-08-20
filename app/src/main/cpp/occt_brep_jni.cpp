#include <jni.h>
#include <algorithm>
#include <atomic>
#include <cstdint>
#include <cmath>
#include <fstream>
#include <limits>
#include <mutex>
#include <sstream>
#include <string>
#include <unordered_map>
#include <vector>

#ifdef CHOBYAR_WITH_OCCT
#include <Standard_Failure.hxx>
#include <Standard_Version.hxx>
#include <TopoDS.hxx>
#include <TopoDS_Edge.hxx>
#include <TopoDS_Face.hxx>
#include <TopoDS_Shape.hxx>
#include <TopoDS_Wire.hxx>
#include <TopoDS_Compound.hxx>
#include <TopExp_Explorer.hxx>
#include <TopAbs_Orientation.hxx>
#include <TopAbs_ShapeEnum.hxx>
#include <TopLoc_Location.hxx>
#include <BRepPrimAPI_MakeBox.hxx>
#include <BRepPrimAPI_MakeCylinder.hxx>
#include <BRepPrimAPI_MakePrism.hxx>
#include <BRepPrimAPI_MakeRevol.hxx>
#include <BRepBuilderAPI_MakeEdge.hxx>
#include <BRepBuilderAPI_MakeFace.hxx>
#include <BRepBuilderAPI_MakePolygon.hxx>
#include <BRepBuilderAPI_MakeWire.hxx>
#include <BRepBuilderAPI_Transform.hxx>
#include <BRep_Builder.hxx>
#include <BRepOffsetAPI_MakePipe.hxx>
#include <BRepOffsetAPI_ThruSections.hxx>
#include <BRepOffsetAPI_MakeThickSolid.hxx>
#include <BRepFilletAPI_MakeFillet.hxx>
#include <BRepFilletAPI_MakeChamfer.hxx>
#include <BRepAlgoAPI_Fuse.hxx>
#include <BRepAlgoAPI_Cut.hxx>
#include <BRepAlgoAPI_Common.hxx>
#include <BRepAdaptor_Surface.hxx>
#include <BRepAdaptor_Curve.hxx>
#include <BRepGProp.hxx>
#include <BRepMesh_IncrementalMesh.hxx>
#include <BRep_Tool.hxx>
#include <GProp_GProps.hxx>
#include <Geom_Circle.hxx>
#include <GeomAbs_SurfaceType.hxx>
#include <GeomAbs_CurveType.hxx>
#include <NCollection_List.hxx>
#include <Poly_Triangulation.hxx>
#include <Poly_Triangle.hxx>
#include <gp_Ax1.hxx>
#include <gp_Ax2.hxx>
#include <gp_Circ.hxx>
#include <gp_Cylinder.hxx>
#include <gp_Dir.hxx>
#include <gp_Pnt.hxx>
#include <gp_Trsf.hxx>
#include <gp_Vec.hxx>
#include <STEPControl_Writer.hxx>
#include <STEPControl_StepModelType.hxx>
#include <IFSelect_ReturnStatus.hxx>
#endif

namespace {

jdoubleArray emptyArray(JNIEnv* env) {
    return env->NewDoubleArray(0);
}

#ifdef CHOBYAR_WITH_OCCT
constexpr jint PROFILE_POLYGON = 0;
constexpr jint PROFILE_CIRCLE = 1;
constexpr double PI = 3.14159265358979323846;

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

bool finitePoint(double x,double y,double z) {
    return std::isfinite(x) && std::isfinite(y) && std::isfinite(z);
}

bool readArray(JNIEnv* env, jdoubleArray array, std::vector<jdouble>& out) {
    if (array == nullptr) return false;
    const jsize count = env->GetArrayLength(array);
    if (count <= 0) return false;
    out.resize(static_cast<size_t>(count));
    env->GetDoubleArrayRegion(array, 0, count, out.data());
    return !env->ExceptionCheck();
}

bool buildProfileWire(JNIEnv* env, jint profileType, jdoubleArray profileData, TopoDS_Wire& out) {
    std::vector<jdouble> data;
    if (!readArray(env, profileData, data)) return false;

    if (profileType == PROFILE_POLYGON) {
        if (data.size() < 9 || data.size() % 3 != 0) return false;
        BRepBuilderAPI_MakePolygon polygon;
        for (size_t i=0; i<data.size(); i+=3) polygon.Add(gp_Pnt(data[i], data[i+1], data[i+2]));
        polygon.Close();
        if (!polygon.IsDone()) return false;
        out = polygon.Wire();
        return !out.IsNull();
    }

    if (profileType == PROFILE_CIRCLE) {
        // [cx,cy,cz,nx,ny,nz,ux,uy,uz,radius]
        if (data.size() < 10 || data[9] <= 0.0) return false;
        if (!validVector(data[3],data[4],data[5]) || !validVector(data[6],data[7],data[8])) return false;
        const gp_Ax2 axis(gp_Pnt(data[0],data[1],data[2]),
                          gp_Dir(data[3],data[4],data[5]),
                          gp_Dir(data[6],data[7],data[8]));
        Handle(Geom_Circle) circle = new Geom_Circle(axis, data[9]);
        BRepBuilderAPI_MakeEdge edgeMaker(circle);
        if (!edgeMaker.IsDone()) return false;
        BRepBuilderAPI_MakeWire wireMaker;
        wireMaker.Add(edgeMaker.Edge());
        if (!wireMaker.IsDone()) return false;
        out = wireMaker.Wire();
        return !out.IsNull();
    }
    return false;
}

bool buildProfileFace(JNIEnv* env, jint profileType, jdoubleArray profileData, TopoDS_Face& out) {
    TopoDS_Wire wire;
    if (!buildProfileWire(env, profileType, profileData, wire)) return false;
    BRepBuilderAPI_MakeFace faceMaker(wire, true);
    if (!faceMaker.IsDone()) return false;
    out = faceMaker.Face();
    return !out.IsNull();
}

bool buildOpenWire(JNIEnv* env, jdoubleArray xyzArray, TopoDS_Wire& out) {
    std::vector<jdouble> xyz;
    if (!readArray(env, xyzArray, xyz) || xyz.size() < 6 || xyz.size() % 3 != 0) return false;
    BRepBuilderAPI_MakePolygon polygon;
    for (size_t i=0; i<xyz.size(); i+=3) polygon.Add(gp_Pnt(xyz[i],xyz[i+1],xyz[i+2]));
    if (!polygon.IsDone()) return false;
    out = polygon.Wire();
    return !out.IsNull();
}

bool isSolidResult(const TopoDS_Shape& shape) {
    return !shape.IsNull() && countSubShapes(shape, TopAbs_SOLID) > 0;
}

gp_Pnt linearCenter(const TopoDS_Edge& edge) {
    GProp_GProps props;
    BRepGProp::LinearProperties(edge, props, true, false);
    return props.CentreOfMass();
}

gp_Pnt surfaceCenter(const TopoDS_Face& face) {
    GProp_GProps props;
    BRepGProp::SurfaceProperties(face, props, true, false);
    return props.CentreOfMass();
}

double surfaceArea(const TopoDS_Face& face) {
    GProp_GProps props;
    BRepGProp::SurfaceProperties(face, props, true, false);
    return props.Mass();
}

double squaredDistance(const gp_Pnt& p,double x,double y,double z) {
    const double dx=p.X()-x,dy=p.Y()-y,dz=p.Z()-z;
    return dx*dx+dy*dy+dz*dz;
}

bool nearestEdge(const TopoDS_Shape& shape,double x,double y,double z,TopoDS_Edge& out) {
    if (!finitePoint(x,y,z)) return false;
    double best=std::numeric_limits<double>::max();
    bool found=false;
    for (TopExp_Explorer ex(shape,TopAbs_EDGE);ex.More();ex.Next()) {
        TopoDS_Edge edge=TopoDS::Edge(ex.Current());
        double d=squaredDistance(linearCenter(edge),x,y,z);
        if (d<best){best=d;out=edge;found=true;}
    }
    return found;
}

bool nearestFace(const TopoDS_Shape& shape,double x,double y,double z,TopoDS_Face& out) {
    if (!finitePoint(x,y,z)) return false;
    double best=std::numeric_limits<double>::max();
    bool found=false;
    for (TopExp_Explorer ex(shape,TopAbs_FACE);ex.More();ex.Next()) {
        TopoDS_Face face=TopoDS::Face(ex.Current());
        double d=squaredDistance(surfaceCenter(face),x,y,z);
        if (d<best){best=d;out=face;found=true;}
    }
    return found;
}

bool largestFace(const TopoDS_Shape& shape,TopoDS_Face& out) {
    double best=-1.0;bool found=false;
    for (TopExp_Explorer ex(shape,TopAbs_FACE);ex.More();ex.Next()) {
        TopoDS_Face face=TopoDS::Face(ex.Current());double a=surfaceArea(face);
        if(a>best){best=a;out=face;found=true;}
    }
    return found;
}

bool planarOutwardNormal(const TopoDS_Face& face,gp_Dir& out) {
    BRepAdaptor_Surface surface(face,true);
    if(surface.GetType()!=GeomAbs_Plane)return false;
    out=surface.Plane().Axis().Direction();
    if(face.Orientation()==TopAbs_REVERSED)out.Reverse();
    return true;
}

gp_Pnt shapeCenter(const TopoDS_Shape& shape) {
    GProp_GProps props;
    BRepGProp::VolumeProperties(shape,props,true,false,false);
    if(std::abs(props.Mass())>1e-12)return props.CentreOfMass();
    BRepGProp::SurfaceProperties(shape,props,true,false);
    return props.CentreOfMass();
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
        if (!cut.IsDone() || cut.Shape().IsNull()) return env->NewStringUTF("FAIL • OCCT BRepAlgoAPI_Cut did not complete");
        const TopoDS_Shape result = cut.Shape();
        const double expected = 100.0 * 80.0 * 20.0 - PI * 10.0 * 10.0 * 20.0;
        const double actual = volumeOf(result);
        const double error = std::abs(actual - expected);
        std::ostringstream ss;
        ss.setf(std::ios::fixed);ss.precision(4);
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
    } catch (...) { return env->NewStringUTF("FAIL • OCCT unknown native exception"); }
#else
    return env->NewStringUTF("SKIP • OCCT is not linked for this ABI");
#endif
}

extern "C" JNIEXPORT jlong JNICALL
Java_ir_chobyar_sketch_NativeBRepKernel_nativeOcctCreateBox(JNIEnv*, jclass, jdouble dx, jdouble dy, jdouble dz) {
#ifdef CHOBYAR_WITH_OCCT
    if (dx <= 0.0 || dy <= 0.0 || dz <= 0.0) return 0;
    try { return storeShape(BRepPrimAPI_MakeBox(dx, dy, dz).Shape()); } catch (...) { return 0; }
#else
    (void)dx; (void)dy; (void)dz; return 0;
#endif
}

extern "C" JNIEXPORT jlong JNICALL
Java_ir_chobyar_sketch_NativeBRepKernel_nativeOcctCreateCylinder(JNIEnv*, jclass, jdouble cx, jdouble cy, jdouble cz, jdouble radius, jdouble height) {
#ifdef CHOBYAR_WITH_OCCT
    if (radius <= 0.0 || height == 0.0) return 0;
    try {
        gp_Dir direction(0.0, 0.0, height > 0.0 ? 1.0 : -1.0);
        gp_Ax2 axis(gp_Pnt(cx, cy, cz), direction);
        return storeShape(BRepPrimAPI_MakeCylinder(axis, radius, std::abs(height)).Shape());
    } catch (...) { return 0; }
#else
    (void)cx; (void)cy; (void)cz; (void)radius; (void)height; return 0;
#endif
}

extern "C" JNIEXPORT jlong JNICALL
Java_ir_chobyar_sketch_NativeBRepKernel_nativeOcctCreateCylinderAxis(
        JNIEnv*, jclass,jdouble cx,jdouble cy,jdouble cz,jdouble ax,jdouble ay,jdouble az,jdouble radius,jdouble height) {
#ifdef CHOBYAR_WITH_OCCT
    if (radius <= 0.0 || height == 0.0 || !validVector(ax,ay,az)) return 0;
    try {
        const double sign = height >= 0.0 ? 1.0 : -1.0;
        gp_Dir direction(ax*sign, ay*sign, az*sign);
        gp_Ax2 axis(gp_Pnt(cx, cy, cz), direction);
        return storeShape(BRepPrimAPI_MakeCylinder(axis, radius, std::abs(height)).Shape());
    } catch (...) { return 0; }
#else
    (void)cx;(void)cy;(void)cz;(void)ax;(void)ay;(void)az;(void)radius;(void)height;return 0;
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
        for (int i=0;i<pointCount;i++) { const int k=i*3; polygon.Add(gp_Pnt(xyz[k], xyz[k+1], xyz[k+2])); }
        polygon.Close();if (!polygon.IsDone()) return 0;
        BRepBuilderAPI_MakeFace faceMaker(polygon.Wire(), true);if (!faceMaker.IsDone()) return 0;
        BRepPrimAPI_MakePrism prism(faceMaker.Face(), gp_Vec(vx,vy,vz), true, true);prism.Build();
        if (!prism.IsDone() || !isSolidResult(prism.Shape())) return 0;
        return storeShape(prism.Shape());
    } catch (...) { return 0; }
#else
    (void)env;(void)xyzArray;(void)vx;(void)vy;(void)vz;return 0;
#endif
}

extern "C" JNIEXPORT jlong JNICALL
Java_ir_chobyar_sketch_NativeBRepKernel_nativeOcctCreateRevolve(
        JNIEnv* env, jclass, jint profileType, jdoubleArray profileData,
        jdouble ox, jdouble oy, jdouble oz,jdouble ax, jdouble ay, jdouble az, jdouble angleDeg) {
#ifdef CHOBYAR_WITH_OCCT
    if (!validVector(ax,ay,az) || std::abs(angleDeg) < 1e-8 || std::abs(angleDeg) > 360.000001) return 0;
    try {
        TopoDS_Face face;if (!buildProfileFace(env, profileType, profileData, face)) return 0;
        BRepPrimAPI_MakeRevol revolve(face, gp_Ax1(gp_Pnt(ox,oy,oz), gp_Dir(ax,ay,az)), angleDeg * PI / 180.0, true);
        revolve.Build();if (!revolve.IsDone() || !isSolidResult(revolve.Shape())) return 0;
        return storeShape(revolve.Shape());
    } catch (...) { return 0; }
#else
    (void)env;(void)profileType;(void)profileData;(void)ox;(void)oy;(void)oz;(void)ax;(void)ay;(void)az;(void)angleDeg;return 0;
#endif
}

extern "C" JNIEXPORT jlong JNICALL
Java_ir_chobyar_sketch_NativeBRepKernel_nativeOcctCreateHelicalRevolve(
        JNIEnv* env, jclass, jint profileType, jdoubleArray profileData,
        jdouble ox, jdouble oy, jdouble oz,jdouble ax, jdouble ay, jdouble az,
        jdouble angleDeg,jdouble heightMm) {
#ifdef CHOBYAR_WITH_OCCT
    if (!validVector(ax,ay,az) || std::abs(angleDeg) < 1e-8 ||
        std::abs(angleDeg) > 36000.000001 || std::abs(heightMm) < 1e-8) return 0;
    try {
        TopoDS_Wire source;
        if (!buildProfileWire(env, profileType, profileData, source)) return 0;
        const gp_Ax1 screwAxis(gp_Pnt(ox,oy,oz),gp_Dir(ax,ay,az));
        const gp_Vec lead(gp_Dir(ax,ay,az));
        // 15 degrees per section gives a smooth exact B-Rep surface while
        // keeping ten-turn woodworking threads practical on mobile hardware.
        const int sectionCount=std::max(8,std::min(720,
                static_cast<int>(std::ceil(std::abs(angleDeg)/15.0))));
        BRepOffsetAPI_ThruSections screw(true,false,1.0e-5);
        screw.CheckCompatibility(true);
        for(int i=0;i<=sectionCount;i++){
            const double t=static_cast<double>(i)/sectionCount;
            gp_Trsf rotation;rotation.SetRotation(screwAxis,angleDeg*PI/180.0*t);
            BRepBuilderAPI_Transform rotate(source,rotation,true);rotate.Build();
            if(!rotate.IsDone())return 0;
            gp_Trsf translation;translation.SetTranslation(lead.Multiplied(heightMm*t));
            BRepBuilderAPI_Transform move(rotate.Shape(),translation,true);move.Build();
            if(!move.IsDone()||move.Shape().ShapeType()!=TopAbs_WIRE)return 0;
            screw.AddWire(TopoDS::Wire(move.Shape()));
        }
        screw.Build();
        if(!screw.IsDone()||!isSolidResult(screw.Shape()))return 0;
        return storeShape(screw.Shape());
    } catch (...) { return 0; }
#else
    (void)env;(void)profileType;(void)profileData;(void)ox;(void)oy;(void)oz;
    (void)ax;(void)ay;(void)az;(void)angleDeg;(void)heightMm;return 0;
#endif
}

extern "C" JNIEXPORT jlong JNICALL
Java_ir_chobyar_sketch_NativeBRepKernel_nativeOcctCreateSweep(
        JNIEnv* env, jclass, jint profileType, jdoubleArray profileData, jdoubleArray pathXYZ) {
#ifdef CHOBYAR_WITH_OCCT
    try {
        TopoDS_Face profileFace;TopoDS_Wire pathWire;
        if (!buildProfileFace(env, profileType, profileData, profileFace)) return 0;
        if (!buildOpenWire(env, pathXYZ, pathWire)) return 0;
        BRepOffsetAPI_MakePipe pipe(pathWire, profileFace);pipe.Build();
        if (!pipe.IsDone() || !isSolidResult(pipe.Shape())) return 0;
        return storeShape(pipe.Shape());
    } catch (...) { return 0; }
#else
    (void)env;(void)profileType;(void)profileData;(void)pathXYZ;return 0;
#endif
}

extern "C" JNIEXPORT jlong JNICALL
Java_ir_chobyar_sketch_NativeBRepKernel_nativeOcctCreateLoft(
        JNIEnv* env, jclass,jint firstType, jdoubleArray firstProfileData,jint secondType, jdoubleArray secondProfileData) {
#ifdef CHOBYAR_WITH_OCCT
    try {
        TopoDS_Wire first, second;
        if (!buildProfileWire(env, firstType, firstProfileData, first)) return 0;
        if (!buildProfileWire(env, secondType, secondProfileData, second)) return 0;
        BRepOffsetAPI_ThruSections loft(true, false, 1.0e-6);loft.CheckCompatibility(true);loft.AddWire(first);loft.AddWire(second);loft.Build();
        if (!loft.IsDone() || !isSolidResult(loft.Shape())) return 0;
        return storeShape(loft.Shape());
    } catch (...) { return 0; }
#else
    (void)env;(void)firstType;(void)firstProfileData;(void)secondType;(void)secondProfileData;return 0;
#endif
}

extern "C" JNIEXPORT jlong JNICALL
Java_ir_chobyar_sketch_NativeBRepKernel_nativeOcctBoolean(JNIEnv*, jclass, jint operation, jlong leftHandle, jlong rightHandle) {
#ifdef CHOBYAR_WITH_OCCT
    TopoDS_Shape left, right;if (!loadShape(leftHandle, left) || !loadShape(rightHandle, right)) return 0;
    try {
        TopoDS_Shape result;
        if (operation == 0) { BRepAlgoAPI_Fuse op(left, right); op.Build(); if (!op.IsDone()) return 0; result = op.Shape(); }
        else if (operation == 1) { BRepAlgoAPI_Cut op(left, right); op.Build(); if (!op.IsDone()) return 0; result = op.Shape(); }
        else if (operation == 2) { BRepAlgoAPI_Common op(left, right); op.Build(); if (!op.IsDone()) return 0; result = op.Shape(); }
        else return 0;
        if (result.IsNull()) return 0;return storeShape(result);
    } catch (...) { return 0; }
#else
    (void)operation;(void)leftHandle;(void)rightHandle;return 0;
#endif
}

// -----------------------------------------------------------------------------
// Exact direct edits
// -----------------------------------------------------------------------------

extern "C" JNIEXPORT jlong JNICALL
Java_ir_chobyar_sketch_NativeBRepKernel_nativeOcctFillet(
        JNIEnv*, jclass, jlong handle, jdouble ax, jdouble ay, jdouble az, jdouble radius, jboolean allEdges) {
#ifdef CHOBYAR_WITH_OCCT
    TopoDS_Shape shape;if(!loadShape(handle,shape)||radius<=0.0)return 0;
    try {
        BRepFilletAPI_MakeFillet fillet(shape);int added=0;
        if(allEdges==JNI_TRUE){
            for(TopExp_Explorer ex(shape,TopAbs_EDGE);ex.More();ex.Next()){fillet.Add(radius,TopoDS::Edge(ex.Current()));added++;}
        }else{
            TopoDS_Edge edge;if(!nearestEdge(shape,ax,ay,az,edge))return 0;fillet.Add(radius,edge);added=1;
        }
        if(added==0)return 0;fillet.Build();
        if(!fillet.IsDone()||fillet.Shape().IsNull()||!isSolidResult(fillet.Shape()))return 0;
        return storeShape(fillet.Shape());
    }catch(...){return 0;}
#else
    (void)handle;(void)ax;(void)ay;(void)az;(void)radius;(void)allEdges;return 0;
#endif
}

extern "C" JNIEXPORT jlong JNICALL
Java_ir_chobyar_sketch_NativeBRepKernel_nativeOcctChamfer(
        JNIEnv*, jclass, jlong handle, jdouble ax, jdouble ay, jdouble az, jdouble distance, jboolean allEdges) {
#ifdef CHOBYAR_WITH_OCCT
    TopoDS_Shape shape;if(!loadShape(handle,shape)||distance<=0.0)return 0;
    try {
        BRepFilletAPI_MakeChamfer chamfer(shape);int added=0;
        if(allEdges==JNI_TRUE){
            for(TopExp_Explorer ex(shape,TopAbs_EDGE);ex.More();ex.Next()){chamfer.Add(distance,TopoDS::Edge(ex.Current()));added++;}
        }else{
            TopoDS_Edge edge;if(!nearestEdge(shape,ax,ay,az,edge))return 0;chamfer.Add(distance,edge);added=1;
        }
        if(added==0)return 0;chamfer.Build();
        if(!chamfer.IsDone()||chamfer.Shape().IsNull()||!isSolidResult(chamfer.Shape()))return 0;
        return storeShape(chamfer.Shape());
    }catch(...){return 0;}
#else
    (void)handle;(void)ax;(void)ay;(void)az;(void)distance;(void)allEdges;return 0;
#endif
}

extern "C" JNIEXPORT jlong JNICALL
Java_ir_chobyar_sketch_NativeBRepKernel_nativeOcctPushPullFace(
        JNIEnv*, jclass, jlong handle, jdouble ax, jdouble ay, jdouble az, jdouble distance) {
#ifdef CHOBYAR_WITH_OCCT
    TopoDS_Shape shape;if(!loadShape(handle,shape)||std::abs(distance)<1e-9)return 0;
    try {
        TopoDS_Face face;if(!nearestFace(shape,ax,ay,az,face))return 0;
        gp_Dir normal;if(!planarOutwardNormal(face,normal))return 0;
        gp_Vec vector(normal);vector*=distance;
        BRepPrimAPI_MakePrism prism(face,vector,true,true);prism.Build();
        if(!prism.IsDone()||prism.Shape().IsNull())return 0;
        TopoDS_Shape result;
        if(distance>0.0){BRepAlgoAPI_Fuse op(shape,prism.Shape());op.Build();if(!op.IsDone())return 0;result=op.Shape();}
        else{BRepAlgoAPI_Cut op(shape,prism.Shape());op.Build();if(!op.IsDone())return 0;result=op.Shape();}
        if(result.IsNull()||!isSolidResult(result))return 0;return storeShape(result);
    }catch(...){return 0;}
#else
    (void)handle;(void)ax;(void)ay;(void)az;(void)distance;return 0;
#endif
}

extern "C" JNIEXPORT jlong JNICALL
Java_ir_chobyar_sketch_NativeBRepKernel_nativeOcctShell(
        JNIEnv*, jclass, jlong handle, jdouble ax, jdouble ay, jdouble az, jdouble thickness) {
#ifdef CHOBYAR_WITH_OCCT
    TopoDS_Shape shape;if(!loadShape(handle,shape)||thickness<=0.0)return 0;
    try {
        TopoDS_Face opening;
        if(finitePoint(ax,ay,az)){if(!nearestFace(shape,ax,ay,az,opening))return 0;}
        else if(!largestFace(shape,opening))return 0;
        NCollection_List<TopoDS_Shape> closing;closing.Append(opening);
        BRepOffsetAPI_MakeThickSolid thick;
        thick.MakeThickSolidByJoin(shape,closing,-std::abs(thickness),1.0e-3,
                                   BRepOffset_Skin,false,false,GeomAbs_Intersection,true);
        if(!thick.IsDone()||thick.Shape().IsNull()||!isSolidResult(thick.Shape()))return 0;
        return storeShape(thick.Shape());
    }catch(...){return 0;}
#else
    (void)handle;(void)ax;(void)ay;(void)az;(void)thickness;return 0;
#endif
}

extern "C" JNIEXPORT jlong JNICALL
Java_ir_chobyar_sketch_NativeBRepKernel_nativeOcctTranslate(
        JNIEnv*, jclass, jlong handle, jdouble dx, jdouble dy, jdouble dz) {
#ifdef CHOBYAR_WITH_OCCT
    TopoDS_Shape shape;if(!loadShape(handle,shape)||!validVector(dx,dy,dz))return 0;
    try {
        gp_Trsf tr;tr.SetTranslation(gp_Vec(dx,dy,dz));
        BRepBuilderAPI_Transform op(shape,tr,true);op.Build();
        if(!op.IsDone()||op.Shape().IsNull())return 0;return storeShape(op.Shape());
    }catch(...){return 0;}
#else
    (void)handle;(void)dx;(void)dy;(void)dz;return 0;
#endif
}

extern "C" JNIEXPORT jlong JNICALL
Java_ir_chobyar_sketch_NativeBRepKernel_nativeOcctRotate(
        JNIEnv*, jclass, jlong handle, jdouble ax, jdouble ay, jdouble az, jdouble angleDeg) {
#ifdef CHOBYAR_WITH_OCCT
    TopoDS_Shape shape;if(!loadShape(handle,shape)||!validVector(ax,ay,az)||std::abs(angleDeg)<1e-9)return 0;
    try {
        gp_Pnt center=shapeCenter(shape);gp_Trsf tr;
        tr.SetRotation(gp_Ax1(center,gp_Dir(ax,ay,az)),angleDeg*PI/180.0);
        BRepBuilderAPI_Transform op(shape,tr,true);op.Build();
        if(!op.IsDone()||op.Shape().IsNull())return 0;return storeShape(op.Shape());
    }catch(...){return 0;}
#else
    (void)handle;(void)ax;(void)ay;(void)az;(void)angleDeg;return 0;
#endif
}

extern "C" JNIEXPORT jlong JNICALL
Java_ir_chobyar_sketch_NativeBRepKernel_nativeOcctScale(
        JNIEnv*, jclass, jlong handle, jdouble factor) {
#ifdef CHOBYAR_WITH_OCCT
    TopoDS_Shape shape;if(!loadShape(handle,shape)||!std::isfinite(factor)||factor<=1.0e-6)return 0;
    try {gp_Trsf tr;tr.SetScale(shapeCenter(shape),factor);BRepBuilderAPI_Transform op(shape,tr,true);op.Build();
        if(!op.IsDone()||op.Shape().IsNull())return 0;return storeShape(op.Shape());}catch(...){return 0;}
#else
    (void)handle;(void)factor;return 0;
#endif
}

extern "C" JNIEXPORT jlong JNICALL
Java_ir_chobyar_sketch_NativeBRepKernel_nativeOcctMirror(
        JNIEnv*, jclass, jlong handle, jdouble nx, jdouble ny, jdouble nz) {
#ifdef CHOBYAR_WITH_OCCT
    TopoDS_Shape shape;if(!loadShape(handle,shape)||!validVector(nx,ny,nz))return 0;
    try {gp_Trsf tr;tr.SetMirror(gp_Ax2(shapeCenter(shape),gp_Dir(nx,ny,nz)));BRepBuilderAPI_Transform op(shape,tr,true);op.Build();
        if(!op.IsDone()||op.Shape().IsNull())return 0;return storeShape(op.Shape());}catch(...){return 0;}
#else
    (void)handle;(void)nx;(void)ny;(void)nz;return 0;
#endif
}

extern "C" JNIEXPORT jlong JNICALL
Java_ir_chobyar_sketch_NativeBRepKernel_nativeOcctLinearPattern(
        JNIEnv*, jclass, jlong handle, jdouble dx, jdouble dy, jdouble dz, jint count) {
#ifdef CHOBYAR_WITH_OCCT
    TopoDS_Shape shape;if(!loadShape(handle,shape)||!validVector(dx,dy,dz)||count<2||count>256)return 0;
    try {BRep_Builder builder;TopoDS_Compound compound;builder.MakeCompound(compound);builder.Add(compound,shape);
        for(jint i=1;i<count;i++){gp_Trsf tr;tr.SetTranslation(gp_Vec(dx*i,dy*i,dz*i));BRepBuilderAPI_Transform op(shape,tr,true);op.Build();
            if(!op.IsDone()||op.Shape().IsNull())return 0;builder.Add(compound,op.Shape());}
        return storeShape(compound);}catch(...){return 0;}
#else
    (void)handle;(void)dx;(void)dy;(void)dz;(void)count;return 0;
#endif
}

extern "C" JNIEXPORT jdoubleArray JNICALL
Java_ir_chobyar_sketch_NativeBRepKernel_nativeOcctEdgeDescriptors(JNIEnv* env, jclass, jlong handle) {
#ifdef CHOBYAR_WITH_OCCT
    TopoDS_Shape shape;if(!loadShape(handle,shape))return emptyArray(env);
    try {
        constexpr int RECORD=18;
        std::vector<double> data;
        const int edgeCount=countSubShapes(shape,TopAbs_EDGE);
        data.reserve(static_cast<size_t>(std::max(0,edgeCount))*RECORD);
        int edgeIndex=0;
        for(TopExp_Explorer ex(shape,TopAbs_EDGE);ex.More();ex.Next(),++edgeIndex){
            const TopoDS_Edge edge=TopoDS::Edge(ex.Current());
            BRepAdaptor_Curve curve(edge);
            const double first=curve.FirstParameter(),last=curve.LastParameter();
            if(!std::isfinite(first)||!std::isfinite(last))continue;
            const gp_Pnt p1=curve.Value(first),p2=curve.Value(last);
            int kind=0;
            gp_Pnt center(0.0,0.0,0.0);gp_Dir normal(0.0,0.0,1.0);double radius=0.0;
            const GeomAbs_CurveType type=curve.GetType();
            if(type==GeomAbs_Line){kind=1;}
            else if(type==GeomAbs_Circle){
                const gp_Circ circle=curve.Circle();center=circle.Location();normal=circle.Axis().Direction();radius=circle.Radius();
                const double span=std::abs(last-first);kind=std::abs(span-2.0*PI)<1.0e-6?2:3;
            }
            const double orientation=edge.Orientation()==TopAbs_REVERSED?-1.0:1.0;
            const double rec[RECORD]={static_cast<double>(kind),static_cast<double>(edgeIndex),p1.X(),p1.Y(),p1.Z(),p2.X(),p2.Y(),p2.Z(),center.X(),center.Y(),center.Z(),normal.X(),normal.Y(),normal.Z(),radius,first,last,orientation};
            data.insert(data.end(),rec,rec+RECORD);
        }
        if(data.empty())return emptyArray(env);
        jdoubleArray out=env->NewDoubleArray(static_cast<jsize>(data.size()));
        if(out)env->SetDoubleArrayRegion(out,0,static_cast<jsize>(data.size()),data.data());
        return out?out:emptyArray(env);
    }catch(...){return emptyArray(env);}
#else
    (void)handle;return emptyArray(env);
#endif
}

extern "C" JNIEXPORT jdoubleArray JNICALL
Java_ir_chobyar_sketch_NativeBRepKernel_nativeOcctShapeStats(JNIEnv* env, jclass, jlong handle) {
#ifdef CHOBYAR_WITH_OCCT
    TopoDS_Shape shape;if (!loadShape(handle, shape)) return emptyArray(env);
    const double values[4] = {volumeOf(shape),static_cast<double>(countSubShapes(shape, TopAbs_FACE)),
            static_cast<double>(countSubShapes(shape, TopAbs_EDGE)),static_cast<double>(countSubShapes(shape, TopAbs_SOLID))};
    jdoubleArray out = env->NewDoubleArray(4);if (out) env->SetDoubleArrayRegion(out, 0, 4, values);return out;
#else
    (void)handle;return emptyArray(env);
#endif
}

extern "C" JNIEXPORT jdoubleArray JNICALL
Java_ir_chobyar_sketch_NativeBRepKernel_nativeOcctTriangulate(
        JNIEnv* env, jclass, jlong handle, jdouble deflection) {
#ifdef CHOBYAR_WITH_OCCT
    TopoDS_Shape shape;if (!loadShape(handle, shape)) return emptyArray(env);
    if (!(deflection > 0.0)) deflection = 0.35;
    try {
        BRepMesh_IncrementalMesh mesher(shape, deflection, false, 0.35, true);mesher.Perform();
        if (!mesher.IsDone()) return emptyArray(env);
        std::vector<double> data;data.reserve(4096);
        for (TopExp_Explorer ex(shape, TopAbs_FACE); ex.More(); ex.Next()) {
            const TopoDS_Face face = TopoDS::Face(ex.Current());TopLoc_Location location;
            Handle(Poly_Triangulation) triangulation = BRep_Tool::Triangulation(face, location);
            if (triangulation.IsNull()) continue;
            const gp_Trsf transform = location.Transformation();
            for (Standard_Integer i=1; i<=triangulation->NbTriangles(); ++i) {
                Standard_Integer n1=0,n2=0,n3=0;triangulation->Triangle(i).Get(n1,n2,n3);
                if (face.Orientation() == TopAbs_REVERSED) std::swap(n2,n3);
                const Standard_Integer ids[3] = {n1,n2,n3};
                for (int j=0;j<3;j++) {gp_Pnt p = triangulation->Node(ids[j]);p.Transform(transform);data.push_back(p.X());data.push_back(p.Y());data.push_back(p.Z());}
            }
        }
        if (data.empty()) return emptyArray(env);
        jdoubleArray out = env->NewDoubleArray(static_cast<jsize>(data.size()));
        if (out) env->SetDoubleArrayRegion(out, 0, static_cast<jsize>(data.size()), data.data());
        return out ? out : emptyArray(env);
    } catch (...) { return emptyArray(env); }
#else
    (void)handle;(void)deflection;return emptyArray(env);
#endif
}

extern "C" JNIEXPORT jboolean JNICALL
Java_ir_chobyar_sketch_NativeBRepKernel_nativeOcctExport(
        JNIEnv* env, jclass, jlongArray handles, jstring pathValue, jint format) {
#ifdef CHOBYAR_WITH_OCCT
    if(!handles||!pathValue)return JNI_FALSE;
    const jsize count=env->GetArrayLength(handles);if(count<=0)return JNI_FALSE;
    std::vector<jlong> ids(static_cast<size_t>(count));env->GetLongArrayRegion(handles,0,count,ids.data());
    const char* raw=env->GetStringUTFChars(pathValue,nullptr);if(!raw)return JNI_FALSE;std::string path(raw);env->ReleaseStringUTFChars(pathValue,raw);
    try {
        std::vector<TopoDS_Shape> shapes;shapes.reserve(static_cast<size_t>(count));
        for(jlong id:ids){TopoDS_Shape shape;if(loadShape(id,shape))shapes.push_back(shape);}
        if(shapes.empty())return JNI_FALSE;
        if(format==0){STEPControl_Writer writer;for(const TopoDS_Shape& shape:shapes)if(writer.Transfer(shape,STEPControl_AsIs)!=IFSelect_RetDone)return JNI_FALSE;
            return writer.Write(path.c_str())==IFSelect_RetDone?JNI_TRUE:JNI_FALSE;}
        // OCCT 8 removed the legacy StlAPI_Writer shape facade.  Emit a
        // standards-compliant binary STL directly from OCCT face meshes so
        // multi-body export stays deterministic and independent of UI meshes.
        std::ofstream out(path,std::ios::binary|std::ios::trunc);if(!out)return JNI_FALSE;
        char header[80]{};const char marker[]="ChobYar exact OCCT B-Rep STL";
        std::copy(marker,marker+sizeof(marker)-1,header);out.write(header,sizeof(header));
        const std::streampos countPos=out.tellp();std::uint32_t triangleCount=0;out.write(reinterpret_cast<const char*>(&triangleCount),sizeof(triangleCount));
        for(const TopoDS_Shape& shape:shapes){
            BRepMesh_IncrementalMesh mesher(shape,0.12,false,0.25,true);mesher.Perform();if(!mesher.IsDone())return JNI_FALSE;
            for(TopExp_Explorer ex(shape,TopAbs_FACE);ex.More();ex.Next()){
                const TopoDS_Face face=TopoDS::Face(ex.Current());TopLoc_Location location;
                Handle(Poly_Triangulation) mesh=BRep_Tool::Triangulation(face,location);if(mesh.IsNull())continue;
                const gp_Trsf transform=location.Transformation();
                for(Standard_Integer i=1;i<=mesh->NbTriangles();++i){
                    Standard_Integer n1=0,n2=0,n3=0;mesh->Triangle(i).Get(n1,n2,n3);if(face.Orientation()==TopAbs_REVERSED)std::swap(n2,n3);
                    gp_Pnt p1=mesh->Node(n1),p2=mesh->Node(n2),p3=mesh->Node(n3);p1.Transform(transform);p2.Transform(transform);p3.Transform(transform);
                    gp_Vec normal(p1,p2);normal.Cross(gp_Vec(p1,p3));if(normal.SquareMagnitude()>1e-24)normal.Normalize();
                    const float record[12]={static_cast<float>(normal.X()),static_cast<float>(normal.Y()),static_cast<float>(normal.Z()),
                        static_cast<float>(p1.X()),static_cast<float>(p1.Y()),static_cast<float>(p1.Z()),
                        static_cast<float>(p2.X()),static_cast<float>(p2.Y()),static_cast<float>(p2.Z()),
                        static_cast<float>(p3.X()),static_cast<float>(p3.Y()),static_cast<float>(p3.Z())};
                    const std::uint16_t attribute=0;out.write(reinterpret_cast<const char*>(record),sizeof(record));out.write(reinterpret_cast<const char*>(&attribute),sizeof(attribute));++triangleCount;
                }
            }
        }
        if(!out||triangleCount==0)return JNI_FALSE;out.seekp(countPos);out.write(reinterpret_cast<const char*>(&triangleCount),sizeof(triangleCount));out.close();return out?JNI_TRUE:JNI_FALSE;
    }catch(...){return JNI_FALSE;}
#else
    (void)env;(void)handles;(void)pathValue;(void)format;return JNI_FALSE;
#endif
}

extern "C" JNIEXPORT jstring JNICALL
Java_ir_chobyar_sketch_NativeBRepKernel_nativeOcctShapeSummary(JNIEnv* env, jclass, jlong handle) {
#ifdef CHOBYAR_WITH_OCCT
    TopoDS_Shape shape;if (!loadShape(handle, shape)) return env->NewStringUTF("Shape handle not found");
    const std::string s = statsText(shape);return env->NewStringUTF(s.c_str());
#else
    (void)handle;return env->NewStringUTF("OCCT not linked");
#endif
}

extern "C" JNIEXPORT void JNICALL
Java_ir_chobyar_sketch_NativeBRepKernel_nativeOcctRelease(JNIEnv*, jclass, jlong handle) {
#ifdef CHOBYAR_WITH_OCCT
    std::lock_guard<std::mutex> lock(gShapeMutex);gShapes.erase(handle);
#else
    (void)handle;
#endif
}

extern "C" JNIEXPORT void JNICALL
Java_ir_chobyar_sketch_NativeBRepKernel_nativeOcctClear(JNIEnv*, jclass) {
#ifdef CHOBYAR_WITH_OCCT
    std::lock_guard<std::mutex> lock(gShapeMutex);gShapes.clear();
#endif
}


extern "C" JNIEXPORT jdoubleArray JNICALL
Java_ir_chobyar_sketch_NativeBRepKernel_nativeOcctFaceDescriptors(JNIEnv* env, jclass, jlong handle) {
#ifdef CHOBYAR_WITH_OCCT
    TopoDS_Shape shape;
    if (!loadShape(handle, shape)) return emptyArray(env);
    try {
        constexpr int FACE_PLANE = 1;
        constexpr int FACE_CYLINDER = 2;
        constexpr int RECORD = 14;
        std::vector<double> data;
        const int faceCount = countSubShapes(shape, TopAbs_FACE);
        data.reserve(static_cast<size_t>(std::max(0, faceCount)) * RECORD);
        int faceIndex = 0;
        for (TopExp_Explorer ex(shape, TopAbs_FACE); ex.More(); ex.Next(), ++faceIndex) {
            const TopoDS_Face face = TopoDS::Face(ex.Current());
            BRepAdaptor_Surface surface(face, true);
            int kind = 0;
            gp_Pnt center = surfaceCenter(face);
            gp_Pnt origin = center;
            gp_Dir axis(0.0, 0.0, 1.0);
            double radius = 0.0;

            if (surface.GetType() == GeomAbs_Plane) {
                kind = FACE_PLANE;
                const gp_Pln plane = surface.Plane();
                origin = plane.Location();
                axis = plane.Axis().Direction();
                if (face.Orientation() == TopAbs_REVERSED) axis.Reverse();
            } else if (surface.GetType() == GeomAbs_Cylinder) {
                kind = FACE_CYLINDER;
                const gp_Cylinder cylinder = surface.Cylinder();
                origin = cylinder.Location();
                axis = cylinder.Axis().Direction();
                radius = cylinder.Radius();
            } else {
                continue;
            }

            const double orientation = face.Orientation() == TopAbs_REVERSED ? -1.0 : 1.0;
            const double record[RECORD] = {
                static_cast<double>(kind), static_cast<double>(faceIndex),
                center.X(), center.Y(), center.Z(),
                origin.X(), origin.Y(), origin.Z(),
                axis.X(), axis.Y(), axis.Z(),
                surfaceArea(face), radius, orientation
            };
            data.insert(data.end(), record, record + RECORD);
        }
        if (data.empty()) return emptyArray(env);
        jdoubleArray out = env->NewDoubleArray(static_cast<jsize>(data.size()));
        if (out) env->SetDoubleArrayRegion(out, 0, static_cast<jsize>(data.size()), data.data());
        return out ? out : emptyArray(env);
    } catch (...) {
        return emptyArray(env);
    }
#else
    (void)handle;
    return emptyArray(env);
#endif
}

// Included after occt_brep_jni.cpp so this extension shares the same translation
// unit and can reuse the exact OCCT shape registry and geometry helpers.
#ifdef CHOBYAR_WITH_OCCT
#include <gp_Cylinder.hxx>
#include <gp_Pln.hxx>
#endif

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
            const gp_Pnt center = surfaceCenter(face);
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

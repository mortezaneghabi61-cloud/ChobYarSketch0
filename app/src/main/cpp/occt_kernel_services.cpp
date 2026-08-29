#include "occt_kernel_services.h"

#ifdef CHOBYAR_WITH_OCCT

#include "shape_store.h"

#include <BRepAdaptor_Surface.hxx>
#include <GeomAbs_SurfaceType.hxx>
#include <TopAbs_Orientation.hxx>
#include <TopExp_Explorer.hxx>

namespace chobyar::cad {
namespace {

ShapeStore gShapeStore;

} // namespace

NativeHandle storeShape(const TopoDS_Shape& shape) {
    return static_cast<NativeHandle>(gShapeStore.store(shape));
}

bool loadShape(NativeHandle handle, TopoDS_Shape& out) {
    return gShapeStore.load(static_cast<ShapeStore::Handle>(handle), out);
}

bool releaseShape(NativeHandle handle) {
    return gShapeStore.erase(static_cast<ShapeStore::Handle>(handle));
}

void clearShapes() {
    gShapeStore.clear();
}

int countSubShapes(const TopoDS_Shape& shape, TopAbs_ShapeEnum kind) {
    int count = 0;
    for (TopExp_Explorer ex(shape, kind); ex.More(); ex.Next()) ++count;
    return count;
}

bool isSolidResult(const TopoDS_Shape& shape) {
    return !shape.IsNull() && countSubShapes(shape, TopAbs_SOLID) > 0;
}

bool planarOutwardNormal(const TopoDS_Face& face, gp_Dir& out) {
    BRepAdaptor_Surface surface(face, true);
    if (surface.GetType() != GeomAbs_Plane) return false;
    out = surface.Plane().Axis().Direction();
    if (face.Orientation() == TopAbs_REVERSED) out.Reverse();
    return true;
}

} // namespace chobyar::cad

#endif // CHOBYAR_WITH_OCCT

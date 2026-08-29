from pathlib import Path

ROOT = Path("app/src/main/cpp")
JNI = ROOT / "occt_brep_jni.cpp"
CMAKE = ROOT / "CMakeLists.txt"
OLD_WRAPPER = ROOT / "occt_brep_with_indexed_direct.cpp"
OLD_DIRECT = ROOT / "occt_indexed_direct_extension.inc"
SERVICES_H = ROOT / "occt_kernel_services.h"
SERVICES_CPP = ROOT / "occt_kernel_services.cpp"
DIRECT_CPP = ROOT / "occt_indexed_direct.cpp"


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one match, found {count}")
    return text.replace(old, new, 1)


SERVICES_H_CONTENT = r'''#pragma once

#include <cstdint>

#ifdef CHOBYAR_WITH_OCCT

#include <TopAbs_ShapeEnum.hxx>
#include <TopoDS_Face.hxx>
#include <TopoDS_Shape.hxx>
#include <gp_Dir.hxx>

namespace chobyar::cad {

using NativeHandle = std::int64_t;

NativeHandle storeShape(const TopoDS_Shape& shape);
bool loadShape(NativeHandle handle, TopoDS_Shape& out);
bool releaseShape(NativeHandle handle);
void clearShapes();

int countSubShapes(const TopoDS_Shape& shape, TopAbs_ShapeEnum kind);
bool isSolidResult(const TopoDS_Shape& shape);
bool planarOutwardNormal(const TopoDS_Face& face, gp_Dir& out);

} // namespace chobyar::cad

#endif // CHOBYAR_WITH_OCCT
'''

SERVICES_CPP_CONTENT = r'''#include "occt_kernel_services.h"

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
'''

DIRECT_PREFIX = r'''#include <jni.h>
#include <cmath>

#ifdef CHOBYAR_WITH_OCCT
#include "occt_kernel_services.h"

#include <BRepAlgoAPI_Cut.hxx>
#include <BRepAlgoAPI_Fuse.hxx>
#include <BRepFilletAPI_MakeChamfer.hxx>
#include <BRepFilletAPI_MakeFillet.hxx>
#include <BRepOffsetAPI_MakeThickSolid.hxx>
#include <BRepOffset_Mode.hxx>
#include <BRepPrimAPI_MakePrism.hxx>
#include <GeomAbs_JoinType.hxx>
#include <NCollection_List.hxx>
#include <TopAbs_ShapeEnum.hxx>
#include <TopExp_Explorer.hxx>
#include <TopoDS.hxx>
#include <TopoDS_Edge.hxx>
#include <TopoDS_Face.hxx>
#include <TopoDS_Shape.hxx>
#include <gp_Dir.hxx>
#include <gp_Vec.hxx>

using chobyar::cad::isSolidResult;
using chobyar::cad::loadShape;
using chobyar::cad::planarOutwardNormal;
using chobyar::cad::storeShape;
#endif
'''

if not JNI.exists() or not CMAKE.exists() or not OLD_WRAPPER.exists() or not OLD_DIRECT.exists():
    raise SystemExit("K2.2 expected source layout is missing")
if SERVICES_H.exists() or SERVICES_CPP.exists() or DIRECT_CPP.exists():
    raise SystemExit("K2.2 target files already exist; refusing to overwrite")

jni = JNI.read_text()
jni = replace_once(jni, '#include "shape_store.h"', '#include "occt_kernel_services.h"', "JNI include")

old_registry = r'''chobyar::cad::ShapeStore gShapeStore;

jlong storeShape(const TopoDS_Shape& shape) {
    return static_cast<jlong>(gShapeStore.store(shape));
}

bool loadShape(jlong handle, TopoDS_Shape& out) {
    return gShapeStore.load(static_cast<chobyar::cad::ShapeStore::Handle>(handle), out);
}

int countSubShapes(const TopoDS_Shape& shape, TopAbs_ShapeEnum kind) {
    int count = 0;
    for (TopExp_Explorer ex(shape, kind); ex.More(); ex.Next()) ++count;
    return count;
}
'''
new_registry = r'''using chobyar::cad::NativeHandle;
using chobyar::cad::clearShapes;
using chobyar::cad::countSubShapes;
using chobyar::cad::isSolidResult;
using chobyar::cad::loadShape;
using chobyar::cad::planarOutwardNormal;
using chobyar::cad::releaseShape;
using chobyar::cad::storeShape;
'''
jni = replace_once(jni, old_registry, new_registry, "shape registry extraction")

old_is_solid = r'''bool isSolidResult(const TopoDS_Shape& shape) {
    return !shape.IsNull() && countSubShapes(shape, TopAbs_SOLID) > 0;
}

'''
jni = replace_once(jni, old_is_solid, "", "isSolidResult extraction")

old_planar = r'''bool planarOutwardNormal(const TopoDS_Face& face,gp_Dir& out) {
    BRepAdaptor_Surface surface(face,true);
    if(surface.GetType()!=GeomAbs_Plane)return false;
    out=surface.Plane().Axis().Direction();
    if(face.Orientation()==TopAbs_REVERSED)out.Reverse();
    return true;
}

'''
jni = replace_once(jni, old_planar, "", "planarOutwardNormal extraction")
jni = replace_once(
    jni,
    'gShapeStore.erase(static_cast<chobyar::cad::ShapeStore::Handle>(handle));',
    'releaseShape(static_cast<NativeHandle>(handle));',
    "release delegation",
)
jni = replace_once(jni, 'gShapeStore.clear();', 'clearShapes();', "clear delegation")
JNI.write_text(jni)

cmake = CMAKE.read_text()
cmake = replace_once(
    cmake,
    '        shape_store.cpp\n        occt_brep_with_indexed_direct.cpp\n',
    '        shape_store.cpp\n        occt_kernel_services.cpp\n        occt_brep_jni.cpp\n        occt_indexed_direct.cpp\n',
    "CMake native source split",
)
CMAKE.write_text(cmake)

SERVICES_H.write_text(SERVICES_H_CONTENT)
SERVICES_CPP.write_text(SERVICES_CPP_CONTENT)
DIRECT_CPP.write_text(DIRECT_PREFIX + OLD_DIRECT.read_text())
OLD_DIRECT.unlink()
OLD_WRAPPER.unlink()

print("K2.2 split prepared:")
for path in [CMAKE, JNI, SERVICES_H, SERVICES_CPP, DIRECT_CPP, OLD_DIRECT, OLD_WRAPPER]:
    print(" -", path)

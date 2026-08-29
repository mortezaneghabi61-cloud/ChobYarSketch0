#pragma once

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

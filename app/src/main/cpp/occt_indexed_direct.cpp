#include <jni.h>
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

#ifdef CHOBYAR_WITH_OCCT
namespace {

bool edgeByTraversalIndex(const TopoDS_Shape& shape,int wanted,TopoDS_Edge& out){
    if(wanted<0)return false;int index=0;
    for(TopExp_Explorer ex(shape,TopAbs_EDGE);ex.More();ex.Next(),++index){
        if(index==wanted){out=TopoDS::Edge(ex.Current());return !out.IsNull();}
    }
    return false;
}

bool faceByTraversalIndex(const TopoDS_Shape& shape,int wanted,TopoDS_Face& out){
    if(wanted<0)return false;int index=0;
    for(TopExp_Explorer ex(shape,TopAbs_FACE);ex.More();ex.Next(),++index){
        if(index==wanted){out=TopoDS::Face(ex.Current());return !out.IsNull();}
    }
    return false;
}

}
#endif

extern "C" JNIEXPORT jlong JNICALL
Java_ir_chobyar_sketch_NativeIndexedDirectKernel_nativeOcctFilletByIndex(
        JNIEnv*,jclass,jlong handle,jint edgeIndex,jdouble radius){
#ifdef CHOBYAR_WITH_OCCT
    TopoDS_Shape shape;if(!loadShape(handle,shape)||radius<=0.0)return 0;
    try{
        TopoDS_Edge edge;if(!edgeByTraversalIndex(shape,edgeIndex,edge))return 0;
        BRepFilletAPI_MakeFillet fillet(shape);fillet.Add(radius,edge);fillet.Build();
        if(!fillet.IsDone()||fillet.Shape().IsNull()||!isSolidResult(fillet.Shape()))return 0;
        return storeShape(fillet.Shape());
    }catch(...){return 0;}
#else
    (void)handle;(void)edgeIndex;(void)radius;return 0;
#endif
}

extern "C" JNIEXPORT jlong JNICALL
Java_ir_chobyar_sketch_NativeIndexedDirectKernel_nativeOcctChamferByIndex(
        JNIEnv*,jclass,jlong handle,jint edgeIndex,jdouble distance){
#ifdef CHOBYAR_WITH_OCCT
    TopoDS_Shape shape;if(!loadShape(handle,shape)||distance<=0.0)return 0;
    try{
        TopoDS_Edge edge;if(!edgeByTraversalIndex(shape,edgeIndex,edge))return 0;
        BRepFilletAPI_MakeChamfer chamfer(shape);chamfer.Add(distance,edge);chamfer.Build();
        if(!chamfer.IsDone()||chamfer.Shape().IsNull()||!isSolidResult(chamfer.Shape()))return 0;
        return storeShape(chamfer.Shape());
    }catch(...){return 0;}
#else
    (void)handle;(void)edgeIndex;(void)distance;return 0;
#endif
}

extern "C" JNIEXPORT jlong JNICALL
Java_ir_chobyar_sketch_NativeIndexedDirectKernel_nativeOcctPushPullFaceByIndex(
        JNIEnv*,jclass,jlong handle,jint faceIndex,jdouble distance){
#ifdef CHOBYAR_WITH_OCCT
    TopoDS_Shape shape;if(!loadShape(handle,shape)||std::abs(distance)<1e-9)return 0;
    try{
        TopoDS_Face face;if(!faceByTraversalIndex(shape,faceIndex,face))return 0;
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
    (void)handle;(void)faceIndex;(void)distance;return 0;
#endif
}

extern "C" JNIEXPORT jlong JNICALL
Java_ir_chobyar_sketch_NativeIndexedDirectKernel_nativeOcctShellByIndex(
        JNIEnv*,jclass,jlong handle,jint faceIndex,jdouble thickness){
#ifdef CHOBYAR_WITH_OCCT
    TopoDS_Shape shape;if(!loadShape(handle,shape)||thickness<=0.0)return 0;
    try{
        TopoDS_Face opening;if(!faceByTraversalIndex(shape,faceIndex,opening))return 0;
        NCollection_List<TopoDS_Shape> closing;closing.Append(opening);
        BRepOffsetAPI_MakeThickSolid thick;
        thick.MakeThickSolidByJoin(shape,closing,-std::abs(thickness),1.0e-3,
                                   BRepOffset_Skin,false,false,GeomAbs_Intersection,true);
        if(!thick.IsDone()||thick.Shape().IsNull()||!isSolidResult(thick.Shape()))return 0;
        return storeShape(thick.Shape());
    }catch(...){return 0;}
#else
    (void)handle;(void)faceIndex;(void)thickness;return 0;
#endif
}

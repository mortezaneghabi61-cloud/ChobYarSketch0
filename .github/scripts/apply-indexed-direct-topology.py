from pathlib import Path

BRANCH = "feature/stable-direct-topology-20260821"


def replace_once(path: str, old: str, new: str) -> None:
    p = Path(path)
    text = p.read_text()
    if new in text:
        return
    if old not in text:
        raise RuntimeError(f"pattern not found in {path}: {old[:120]!r}")
    p.write_text(text.replace(old, new, 1))


# 1) Preserve current exact OCCT traversal index in stable topology resolution.
path = "app/src/main/java/ir/chobyar/sketch/OcctTopologyRef.java"
replace_once(path,
'''    static final class Resolution {
        final Geometry3D.Vec3 anchor;
        final double score;
        Resolution(Geometry3D.Vec3 anchor,double score){this.anchor=anchor;this.score=score;}
        boolean confident(){return score<95.0;}
    }
''',
'''    static final class Resolution {
        final Geometry3D.Vec3 anchor;
        final double score;
        final int subshapeIndex;
        Resolution(Geometry3D.Vec3 anchor,double score){this(anchor,score,-1);}
        Resolution(Geometry3D.Vec3 anchor,double score,int subshapeIndex){
            this.anchor=anchor;this.score=score;this.subshapeIndex=subshapeIndex;
        }
        boolean confident(){return score<95.0;}
        boolean hasExactIndex(){return subshapeIndex>=0;}
    }
''')
replace_once(path,
'''    private static final class ExactEdgeCandidate {
        final int type;
        final Geometry3D.Vec3 p1,p2,center,anchor,vector;
        final double radius,span,measure;
        ExactEdgeCandidate(int type,Geometry3D.Vec3 p1,Geometry3D.Vec3 p2,
                           Geometry3D.Vec3 center,Geometry3D.Vec3 anchor,
                           Geometry3D.Vec3 vector,double radius,double span,double measure){
            this.type=type;this.p1=p1;this.p2=p2;this.center=center;this.anchor=anchor;
            this.vector=vector;this.radius=radius;this.span=span;this.measure=measure;
        }
    }
''',
'''    private static final class ExactEdgeCandidate {
        final int type,index;
        final Geometry3D.Vec3 p1,p2,center,anchor,vector;
        final double radius,span,measure;
        ExactEdgeCandidate(int type,int index,Geometry3D.Vec3 p1,Geometry3D.Vec3 p2,
                           Geometry3D.Vec3 center,Geometry3D.Vec3 anchor,
                           Geometry3D.Vec3 vector,double radius,double span,double measure){
            this.type=type;this.index=index;this.p1=p1;this.p2=p2;this.center=center;this.anchor=anchor;
            this.vector=vector;this.radius=radius;this.span=span;this.measure=measure;
        }
    }
''')
replace_once(path,
'''        return best==null?null:new Resolution(best.center,score);
    }

    private static List<ExactFaceCandidate> exactFaceCandidates''',
'''        return best==null?null:new Resolution(best.center,score,best.index);
    }

    private static List<ExactFaceCandidate> exactFaceCandidates''')
replace_once(path,
'''        return best==null?null:new Resolution(best.anchor,score);
    }

    private static List<ExactEdgeCandidate> exactEdgeCandidates(double[] d){
        List<ExactEdgeCandidate> out=new ArrayList<>();
        if(d==null)return out;final int n=NativeBRepKernel.OCCT_EDGE_RECORD_SIZE;
        for(int i=0;i+n-1<d.length;i+=n){
            int kind=(int)Math.round(d[i]);
''',
'''        return best==null?null:new Resolution(best.anchor,score,best.index);
    }

    private static List<ExactEdgeCandidate> exactEdgeCandidates(double[] d){
        List<ExactEdgeCandidate> out=new ArrayList<>();
        if(d==null)return out;final int n=NativeBRepKernel.OCCT_EDGE_RECORD_SIZE;
        for(int i=0;i+n-1<d.length;i+=n){
            int kind=(int)Math.round(d[i]),index=(int)Math.round(d[i+1]);
''')
replace_once(path,
'''                out.add(new ExactEdgeCandidate(kind,p1,p2,null,anchor,dir,0.0,0.0,len));continue;
''',
'''                out.add(new ExactEdgeCandidate(kind,index,p1,p2,null,anchor,dir,0.0,0.0,len));continue;
''')
replace_once(path,
'''            out.add(new ExactEdgeCandidate(kind,p1,p2,center,anchor,normal,radius,span,radius*span));
''',
'''            out.add(new ExactEdgeCandidate(kind,index,p1,p2,center,anchor,normal,radius,span,radius*span));
''')

# 2) Add an isolated JNI bridge so mature NativeBRepKernel ABI stays compatible.
Path("app/src/main/java/ir/chobyar/sketch/NativeIndexedDirectKernel.java").write_text(r'''package ir.chobyar.sketch;

/**
 * Exact-index direct modeling bridge.
 *
 * Stable topology matching resolves a logical Edge/Face to the current OCCT
 * traversal index. These operations consume that index directly so the native
 * operation never performs another nearest-anchor selection step.
 */
final class NativeIndexedDirectKernel {
    private NativeIndexedDirectKernel() {}

    static long filletByIndex(long handle,int edgeIndex,double radiusMm){
        if(!NativeBRepKernel.occtAvailable()||handle==0L||edgeIndex<0||radiusMm<=0.0)return 0L;
        try{return nativeOcctFilletByIndex(handle,edgeIndex,radiusMm);}catch(Throwable t){return 0L;}
    }

    static long chamferByIndex(long handle,int edgeIndex,double distanceMm){
        if(!NativeBRepKernel.occtAvailable()||handle==0L||edgeIndex<0||distanceMm<=0.0)return 0L;
        try{return nativeOcctChamferByIndex(handle,edgeIndex,distanceMm);}catch(Throwable t){return 0L;}
    }

    static long pushPullFaceByIndex(long handle,int faceIndex,double distanceMm){
        if(!NativeBRepKernel.occtAvailable()||handle==0L||faceIndex<0||Math.abs(distanceMm)<1e-9)return 0L;
        try{return nativeOcctPushPullFaceByIndex(handle,faceIndex,distanceMm);}catch(Throwable t){return 0L;}
    }

    static long shellByIndex(long handle,int faceIndex,double thicknessMm){
        if(!NativeBRepKernel.occtAvailable()||handle==0L||faceIndex<0||thicknessMm<=0.0)return 0L;
        try{return nativeOcctShellByIndex(handle,faceIndex,thicknessMm);}catch(Throwable t){return 0L;}
    }

    private static native long nativeOcctFilletByIndex(long handle,int edgeIndex,double radiusMm);
    private static native long nativeOcctChamferByIndex(long handle,int edgeIndex,double distanceMm);
    private static native long nativeOcctPushPullFaceByIndex(long handle,int faceIndex,double distanceMm);
    private static native long nativeOcctShellByIndex(long handle,int faceIndex,double thicknessMm);
}
''')

# 3) Native exact index operations share the private shape registry by compiling
#    the mature kernel and this extension in one translation unit.
Path("app/src/main/cpp/occt_indexed_direct_extension.inc").write_text(r'''
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
''')
Path("app/src/main/cpp/occt_brep_with_indexed_direct.cpp").write_text(r'''// Compile the mature OCCT kernel and exact-index direct-edit extension as one
// translation unit so the extension can reuse the private shape registry safely.
#include "occt_brep_jni.cpp"
#include "occt_indexed_direct_extension.inc"
''')
replace_once("app/src/main/cpp/CMakeLists.txt","        occt_brep_jni.cpp\n","        occt_brep_with_indexed_direct.cpp\n")

# 4) Stable direct edits use exact current subshape index whenever descriptors
#    provide one; anchor selection remains fallback-only for unsupported topology.
path = "app/src/main/java/ir/chobyar/sketch/OcctStableCadCanvasView.java"
replace_once(path,
'''        Geometry3D.Vec3 anchor=null;
        if(edit.target!=null){
            OcctTopologyRef.Resolution r=OcctTopologyRef.resolve(handle,edit.target);
            if(r==null||r.score>180.0){edit.broken=true;edit.warning="Topology دوباره پیدا نشد";return 0L;}
            anchor=r.anchor;
        }
        switch(edit.kind){
            case FILLET:return NativeBRepKernel.occtFillet(handle,anchor,edit.value,false);
            case CHAMFER:return NativeBRepKernel.occtChamfer(handle,anchor,edit.value,false);
            case PUSH_PULL:return NativeBRepKernel.occtPushPullFace(handle,anchor,edit.value);
            case SHELL:return NativeBRepKernel.occtShell(handle,anchor,edit.value);
''',
'''        Geometry3D.Vec3 anchor=null;int subshapeIndex=-1;
        if(edit.target!=null){
            OcctTopologyRef.Resolution r=OcctTopologyRef.resolve(handle,edit.target);
            if(r==null||r.score>180.0){edit.broken=true;edit.warning="Topology دوباره پیدا نشد";return 0L;}
            anchor=r.anchor;subshapeIndex=r.subshapeIndex;
        }
        switch(edit.kind){
            case FILLET:return subshapeIndex>=0
                    ?NativeIndexedDirectKernel.filletByIndex(handle,subshapeIndex,edit.value)
                    :NativeBRepKernel.occtFillet(handle,anchor,edit.value,false);
            case CHAMFER:return subshapeIndex>=0
                    ?NativeIndexedDirectKernel.chamferByIndex(handle,subshapeIndex,edit.value)
                    :NativeBRepKernel.occtChamfer(handle,anchor,edit.value,false);
            case PUSH_PULL:return subshapeIndex>=0
                    ?NativeIndexedDirectKernel.pushPullFaceByIndex(handle,subshapeIndex,edit.value)
                    :NativeBRepKernel.occtPushPullFace(handle,anchor,edit.value);
            case SHELL:return subshapeIndex>=0
                    ?NativeIndexedDirectKernel.shellByIndex(handle,subshapeIndex,edit.value)
                    :NativeBRepKernel.occtShell(handle,anchor,edit.value);
''')

# 5) Emulator regression proves rematching returns the *current* native index,
#    rather than the stale index from before an upstream History rebuild.
Path("app/src/androidTest/java/ir/chobyar/sketch/ExactTopologyIndexInstrumentationTest.java").write_text(r'''package ir.chobyar.sketch;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public final class ExactTopologyIndexInstrumentationTest {
    @Test public void edgeHistoryRematchReturnsCurrentTraversalIndex(){
        double[] before=line(4,0,0,0,0,0,40);
        OcctTopologyRef.Ref ref=OcctTopologyRef.captureEdgeDescriptorsForTest(
                before,new Geometry3D.Vec3(0,0,20),null,null,"E-stable");
        assertNotNull(ref);
        double[] rebuilt=concat(
                line(3,20,0,0,20,0,12),
                line(17,5,2,0,5,2,50));
        OcctTopologyRef.Resolution r=OcctTopologyRef.resolveEdgeDescriptorsForTest(rebuilt,ref);
        assertNotNull(r);assertTrue(r.confident());assertTrue(r.hasExactIndex());
        assertEquals(17,r.subshapeIndex);
        android.util.Log.i("ExactTopologyIndex","EXACT_EDGE_INDEX_REMATCH old=4 current=17");
    }

    @Test public void faceHistoryRematchReturnsCurrentTraversalIndex(){
        double[] before=plane(6,40,30,20,0,0,20,0,0,1,4800);
        OcctTopologyRef.Ref ref=OcctTopologyRef.captureFaceDescriptorsForTest(
                before,new Geometry3D.Vec3(40,30,20),"F-stable");
        assertNotNull(ref);
        double[] rebuilt=concat(
                plane(2,0,0,0,0,0,0,0,0,-1,900),
                plane(23,50,35,28,0,0,28,0,0,1,7000));
        OcctTopologyRef.Resolution r=OcctTopologyRef.resolveFaceDescriptorsForTest(rebuilt,ref);
        assertNotNull(r);assertTrue(r.confident());assertTrue(r.hasExactIndex());
        assertEquals(23,r.subshapeIndex);
        android.util.Log.i("ExactTopologyIndex","EXACT_FACE_INDEX_REMATCH old=6 current=23");
    }

    private static double[] line(int index,double x1,double y1,double z1,double x2,double y2,double z2){
        double[] r=new double[NativeBRepKernel.OCCT_EDGE_RECORD_SIZE];
        r[0]=NativeBRepKernel.OCCT_EDGE_LINE;r[1]=index;
        r[2]=x1;r[3]=y1;r[4]=z1;r[5]=x2;r[6]=y2;r[7]=z2;r[17]=1;return r;
    }

    private static double[] plane(int index,double cx,double cy,double cz,
                                  double ox,double oy,double oz,double ax,double ay,double az,double area){
        double[] r=new double[NativeBRepKernel.OCCT_FACE_RECORD_SIZE];
        r[0]=NativeBRepKernel.OCCT_FACE_PLANE;r[1]=index;
        r[2]=cx;r[3]=cy;r[4]=cz;r[5]=ox;r[6]=oy;r[7]=oz;
        r[8]=ax;r[9]=ay;r[10]=az;r[11]=area;r[13]=1;return r;
    }

    private static double[] concat(double[]... records){
        int total=0;for(double[] r:records)total+=r.length;
        double[] out=new double[total];int at=0;
        for(double[] r:records){System.arraycopy(r,0,out,at,r.length);at+=r.length;}return out;
    }
}
''')

# 6) Raise durable regression gate from 19/51 to 20/53.
path = ".github/scripts/run-production-cad-regression.sh"
replace_once(path,
'''# Construction / exact Project / associative references / exact topology: 19 tests
''',
'''# Construction / exact Project / associative references / exact topology: 21 tests
''')
replace_once(path,
'''run_contract ExactFaceTopologyInstrumentationTest exact-face-topology 'OK (6 tests)'

passed_classes=$(grep -c ' | OK (' test-artifacts/production-cad-summary.txt || true)
if [[ "$passed_classes" -ne 19 ]]; then
  echo "CONSOLIDATED_COUNT_FAIL passed_classes=${passed_classes} expected=19" | tee -a test-artifacts/production-cad-summary.txt
''',
'''run_contract ExactFaceTopologyInstrumentationTest exact-face-topology 'OK (6 tests)'
run_contract ExactTopologyIndexInstrumentationTest exact-topology-index 'OK (2 tests)'

passed_classes=$(grep -c ' | OK (' test-artifacts/production-cad-summary.txt || true)
if [[ "$passed_classes" -ne 20 ]]; then
  echo "CONSOLIDATED_COUNT_FAIL passed_classes=${passed_classes} expected=20" | tee -a test-artifacts/production-cad-summary.txt
''')
replace_once(path,
'''echo 'PRODUCTION_CAD_REGRESSION OK classes=19 tests=51' | tee -a test-artifacts/production-cad-summary.txt
''',
'''echo 'PRODUCTION_CAD_REGRESSION OK classes=20 tests=53' | tee -a test-artifacts/production-cad-summary.txt
''')

path = ".github/workflows/manual26100-consolidated-regression.yml"
replace_once(path,"Run 51 core Sketch + 3D + Project + Topology contracts on API 35","Run 53 core Sketch + 3D + Project + Topology contracts on API 35")
replace_once(path,"CONSOLIDATED_RESULT classes=19 tests=51 status=PASS","CONSOLIDATED_RESULT classes=20 tests=53 status=PASS")

path = ".github/workflows/solid-command-smoke.yml"
replace_once(path,"name: 51 production CAD contracts on API 35","name: 53 production CAD contracts on API 35")
replace_once(path,
'''          grep -q 'OCCT_FACE_SPHERE' "$NATIVE"
          grep -q 'occtEdgeDescriptors' "$TOPOLOGY"
          grep -q 'occtFaceDescriptors' "$TOPOLOGY"
''',
'''          grep -q 'OCCT_FACE_SPHERE' "$NATIVE"
          grep -q 'occtEdgeDescriptors' "$TOPOLOGY"
          grep -q 'occtFaceDescriptors' "$TOPOLOGY"
          grep -q 'subshapeIndex' "$TOPOLOGY"
          test -f app/src/main/java/ir/chobyar/sketch/NativeIndexedDirectKernel.java
          grep -q 'filletByIndex' app/src/main/java/ir/chobyar/sketch/NativeIndexedDirectKernel.java
''')
replace_once(path,
'''            ExactFaceTopologyInstrumentationTest; do
''',
'''            ExactFaceTopologyInstrumentationTest \\
            ExactTopologyIndexInstrumentationTest; do
''')

# Documentation: record that exact direct tools now terminate in current native
# subshape identity rather than a second nearest-anchor selection.
progress = Path("docs/manual26100-progress.md")
progress.write_text('''# Manual 26.100 parity progress\n\nThis branch is audited against the Shapr3D 26.100 workflow contract while keeping ChobYar-owned UI and implementation.\n\nVerified durable areas now include Sketch input/selection, Undo/Redo, dimensions and constraints, Construction geometry, Extrude, Revolve, Boolean Keep Originals / Keep Target / Keep Tool, Fillet, Chamfer, Shell, Push/Pull, Sweep, Loft, exact OCCT edge projection, associative Project references, and stable exact topology rematching.\n\n## Current exact-topology gate\n\n- Exact Edge descriptors are the primary source for Line/Circle/Arc identity.\n- Exact Face descriptors cover Plane, Cylinder, Sphere, Cone and Torus.\n- Stable History rematching returns the current OCCT subshape traversal index.\n- Fillet / Chamfer / Push-Pull / Shell consume that exact current index when available.\n- Nearest-anchor native selection remains fallback-only when no exact descriptor index exists.\n- Display triangulation remains a rendering/fallback representation and is not the authoritative exact geometry.\n\n## Regression gate\n\nThe consolidated Android API 35 production suite is 20 instrumentation classes / 53 tests. The arm64 native gate separately compiles and packages the real OCCT-linked `libchobyar_brep.so`.\n\n## Next gates\n\n- Extend exact edge descriptors beyond Line/Circle/Arc to additional analytic/parametric curve families where OCCT exposes durable signatures.\n- Continue reducing compatibility reflection in production UI wiring.\n- Validate installable APK interaction on a physical Android pen device before calling the workflow feature-complete.\n''')

print("indexed direct topology patch applied")

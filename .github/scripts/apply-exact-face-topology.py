from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    p = Path(path)
    text = p.read_text()
    if new in text:
        return
    if old not in text:
        raise SystemExit(f"anchor not found in {path}: {old[:100]!r}")
    p.write_text(text.replace(old, new, 1))


# ---------------------------------------------------------------------------
# Native OCCT exact face descriptor contract.
# Record: kind,index,center.xyz,origin.xyz,axis.xyz,area,radius,orientation
# ---------------------------------------------------------------------------
cpp_path = Path("app/src/main/cpp/occt_brep_jni.cpp")
cpp = cpp_path.read_text()
if "nativeOcctFaceDescriptors" not in cpp:
    if "#include <gp_Circ.hxx>" not in cpp:
        raise SystemExit("gp_Circ include anchor missing")
    cpp = cpp.replace("#include <gp_Circ.hxx>", "#include <gp_Circ.hxx>\n#include <gp_Cylinder.hxx>", 1)
    cpp += r'''

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
'''
    cpp_path.write_text(cpp)

native = Path("app/src/main/java/ir/chobyar/sketch/NativeBRepKernel.java")
text = native.read_text()
if "OCCT_FACE_RECORD_SIZE" not in text:
    text = text.replace(
        "    static final int OCCT_EDGE_RECORD_SIZE = 18;\n",
        "    static final int OCCT_EDGE_RECORD_SIZE = 18;\n\n"
        "    // Exact OCCT face descriptor contract. Every record has 14 doubles:\n"
        "    // kind,index,center.xyz,origin.xyz,axis.xyz,area,radius,orientation.\n"
        "    static final int OCCT_FACE_UNSUPPORTED = 0;\n"
        "    static final int OCCT_FACE_PLANE = 1;\n"
        "    static final int OCCT_FACE_CYLINDER = 2;\n"
        "    static final int OCCT_FACE_RECORD_SIZE = 14;\n",
        1,
    )
    edge_wrapper = '''    /** Exact analytic B-Rep edges; never reconstructed from the display mesh. */
    static double[] occtEdgeDescriptors(long handle){
        if(!occtAvailable()||handle==0L)return new double[0];
        try{return nativeOcctEdgeDescriptors(handle);}catch(Throwable t){return new double[0];}
    }
'''
    face_wrapper = edge_wrapper + '''
    /** Exact analytic planar/cylindrical B-Rep faces from OCCT topology. */
    static double[] occtFaceDescriptors(long handle){
        if(!occtAvailable()||handle==0L)return new double[0];
        try{return nativeOcctFaceDescriptors(handle);}catch(Throwable t){return new double[0];}
    }
'''
    if edge_wrapper not in text:
        raise SystemExit("NativeBRepKernel edge wrapper anchor missing")
    text = text.replace(edge_wrapper, face_wrapper, 1)
    native_decl = "    private static native double[] nativeOcctEdgeDescriptors(long handle);\n"
    if native_decl not in text:
        raise SystemExit("NativeBRepKernel native edge declaration anchor missing")
    text = text.replace(native_decl, native_decl + "    private static native double[] nativeOcctFaceDescriptors(long handle);\n", 1)
    native.write_text(text)

# ---------------------------------------------------------------------------
# Stable logical face references: exact plane/cylinder first, mesh fallback.
# ---------------------------------------------------------------------------
topo = Path("app/src/main/java/ir/chobyar/sketch/OcctTopologyRef.java")
t = topo.read_text()
if "ExactFaceCandidate" not in t:
    old_ref = '''        final Geometry3D.Vec3 vector;
        final double nx,ny,nz;

        Ref(int kind,String id,double measure,Geometry3D.Vec3 anchor,Geometry3D.Vec3 vector,
            double nx,double ny,double nz){
            this.kind=kind;
            this.id=id;
            this.measure=measure;
            this.anchor=anchor;
            this.vector=vector;
            this.nx=nx;this.ny=ny;this.nz=nz;
        }
'''
    new_ref = '''        final Geometry3D.Vec3 vector;
        final double nx,ny,nz;
        final int signatureKind;
        final double secondaryMeasure;

        Ref(int kind,String id,double measure,Geometry3D.Vec3 anchor,Geometry3D.Vec3 vector,
            double nx,double ny,double nz){
            this(kind,id,measure,anchor,vector,nx,ny,nz,0,0.0);
        }

        Ref(int kind,String id,double measure,Geometry3D.Vec3 anchor,Geometry3D.Vec3 vector,
            double nx,double ny,double nz,int signatureKind,double secondaryMeasure){
            this.kind=kind;
            this.id=id;
            this.measure=measure;
            this.anchor=anchor;
            this.vector=vector;
            this.nx=nx;this.ny=ny;this.nz=nz;
            this.signatureKind=signatureKind;
            this.secondaryMeasure=secondaryMeasure;
        }
'''
    if old_ref not in t:
        raise SystemExit("OcctTopologyRef Ref anchor missing")
    t = t.replace(old_ref, new_ref, 1)

    edge_class = '''    private static final class ExactEdgeCandidate {
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
'''
    face_class = edge_class + '''
    private static final class ExactFaceCandidate {
        final int type,index;
        final Geometry3D.Vec3 center,origin,axis;
        final double area,radius;
        ExactFaceCandidate(int type,int index,Geometry3D.Vec3 center,Geometry3D.Vec3 origin,
                           Geometry3D.Vec3 axis,double area,double radius){
            this.type=type;this.index=index;this.center=center;this.origin=origin;
            this.axis=axis;this.area=area;this.radius=radius;
        }
    }
'''
    if edge_class not in t:
        raise SystemExit("ExactEdgeCandidate anchor missing")
    t = t.replace(edge_class, face_class, 1)

    old_capture_face = '''    static Ref captureFace(long handle,Geometry3D.Vec3 touchAnchor,String stableId){
        Mesh mesh=mesh(handle);if(mesh==null||mesh.triangles.isEmpty()||touchAnchor==null)return null;
        List<FaceGroup> groups=faceGroups(mesh);
        Triangle picked=null;double best=Double.POSITIVE_INFINITY;
        for(Triangle t:mesh.triangles){double d=dist2(t.center,touchAnchor);if(d<best){best=d;picked=t;}}
        if(picked==null||picked.group==null)return null;
        FaceGroup g=picked.group;Geometry3D.Vec3 c=g.center();
        return new Ref(FACE,stableId,g.area,c,g.normal,
                mesh.bounds.fx(c.x),mesh.bounds.fy(c.y),mesh.bounds.fz(c.z));
    }
'''
    new_capture_face = '''    static Ref captureFace(long handle,Geometry3D.Vec3 touchAnchor,String stableId){
        if(touchAnchor==null)return null;
        List<ExactFaceCandidate> exact=exactFaceCandidates(NativeBRepKernel.occtFaceDescriptors(handle));
        if(!exact.isEmpty()){
            Ref ref=captureExactFace(exact,touchAnchor,stableId);
            if(ref!=null)return ref;
        }
        return captureMeshFace(handle,touchAnchor,stableId);
    }
'''
    if old_capture_face not in t:
        raise SystemExit("captureFace anchor missing")
    t = t.replace(old_capture_face, new_capture_face, 1)

    old_resolve_start = '''    static Resolution resolve(long handle,Ref ref){
        if(ref==null)return null;
        if(ref.kind==EDGE){
            List<ExactEdgeCandidate> exact=exactEdgeCandidates(NativeBRepKernel.occtEdgeDescriptors(handle));
            if(!exact.isEmpty()){
                Resolution resolution=resolveExactEdge(exact,ref);
                if(resolution!=null)return resolution;
            }
        }
        Mesh mesh=mesh(handle);if(mesh==null)return null;
'''
    new_resolve_start = '''    static Resolution resolve(long handle,Ref ref){
        if(ref==null)return null;
        if(ref.kind==EDGE){
            List<ExactEdgeCandidate> exact=exactEdgeCandidates(NativeBRepKernel.occtEdgeDescriptors(handle));
            if(!exact.isEmpty()){
                Resolution resolution=resolveExactEdge(exact,ref);
                if(resolution!=null)return resolution;
            }
        }else if(ref.kind==FACE){
            List<ExactFaceCandidate> exact=exactFaceCandidates(NativeBRepKernel.occtFaceDescriptors(handle));
            if(!exact.isEmpty()){
                Resolution resolution=resolveExactFace(exact,ref);
                if(resolution!=null)return resolution;
            }
        }
        Mesh mesh=mesh(handle);if(mesh==null)return null;
'''
    if old_resolve_start not in t:
        raise SystemExit("resolve anchor missing")
    t = t.replace(old_resolve_start, new_resolve_start, 1)

    seam_anchor = '''    /** Deterministic exact-descriptor seam for x86 emulator regression tests. */
    static Ref captureEdgeDescriptorsForTest'''
    face_seams = '''    /** Deterministic exact-face seam for x86 emulator regression tests. */
    static Ref captureFaceDescriptorsForTest(double[] descriptors,Geometry3D.Vec3 touchAnchor,String stableId){
        return captureExactFace(exactFaceCandidates(descriptors),touchAnchor,stableId);
    }

    static Resolution resolveFaceDescriptorsForTest(double[] descriptors,Ref ref){
        return resolveExactFace(exactFaceCandidates(descriptors),ref);
    }

'''
    if seam_anchor not in t:
        raise SystemExit("test seam anchor missing")
    t = t.replace(seam_anchor, face_seams + seam_anchor, 1)

    helper_anchor = '''    private static Ref captureExactEdge(List<ExactEdgeCandidate> edges,Geometry3D.Vec3 touchAnchor,
'''
    face_helpers = '''    private static Ref captureExactFace(List<ExactFaceCandidate> faces,Geometry3D.Vec3 touchAnchor,String stableId){
        if(faces==null||faces.isEmpty()||touchAnchor==null)return null;
        Bounds bounds=exactFaceBounds(faces);if(!bounds.valid())return null;
        ExactFaceCandidate best=null;double score=Double.POSITIVE_INFINITY;double diag=bounds.diag();
        for(ExactFaceCandidate f:faces){
            double s=distanceToExactFace(f,touchAnchor)/diag*90.0+
                    Math.sqrt(dist2(f.center,touchAnchor))/diag*10.0;
            if(s<score){score=s;best=f;}
        }
        if(best==null)return null;Geometry3D.Vec3 c=best.center;
        return new Ref(FACE,stableId,best.area,c,best.axis,
                bounds.fx(c.x),bounds.fy(c.y),bounds.fz(c.z),best.type,best.radius);
    }

    private static Resolution resolveExactFace(List<ExactFaceCandidate> faces,Ref ref){
        if(faces==null||faces.isEmpty()||ref==null||ref.kind!=FACE)return null;
        Bounds bounds=exactFaceBounds(faces);if(!bounds.valid())return null;
        Geometry3D.Vec3 expected=bounds.fromFraction(ref.nx,ref.ny,ref.nz);double diag=bounds.diag();
        ExactFaceCandidate best=null;double score=Double.POSITIVE_INFINITY;
        for(ExactFaceCandidate f:faces){
            if(ref.signatureKind!=0&&f.type!=ref.signatureKind)continue;
            double pos=Math.sqrt(dist2(f.center,expected))/diag*55.0;
            double area=relative(ref.measure,f.area)*22.0;
            double axis=ref.vector==null?0.0:(1.0-Math.abs(ref.vector.dot(f.axis)))*18.0;
            double radius=(ref.signatureKind==NativeBRepKernel.OCCT_FACE_CYLINDER||f.type==NativeBRepKernel.OCCT_FACE_CYLINDER)
                    ?relative(ref.secondaryMeasure,f.radius)*20.0:0.0;
            double s=pos+area+axis+radius;
            if(s<score){score=s;best=f;}
        }
        return best==null?null:new Resolution(best.center,score);
    }

    private static List<ExactFaceCandidate> exactFaceCandidates(double[] d){
        List<ExactFaceCandidate> out=new ArrayList<>();
        if(d==null)return out;final int n=NativeBRepKernel.OCCT_FACE_RECORD_SIZE;
        for(int i=0;i+n-1<d.length;i+=n){
            int kind=(int)Math.round(d[i]),index=(int)Math.round(d[i+1]);
            if(kind!=NativeBRepKernel.OCCT_FACE_PLANE&&kind!=NativeBRepKernel.OCCT_FACE_CYLINDER)continue;
            Geometry3D.Vec3 center=v(d,i+2),origin=v(d,i+5),axis=v(d,i+8);double len=axis.length();
            double area=Math.abs(d[i+11]),radius=Math.abs(d[i+12]);
            if(len<1e-7||area<1e-9)continue;axis=axis.mul((float)(1.0/len));
            if(kind==NativeBRepKernel.OCCT_FACE_CYLINDER&&radius<1e-7)continue;
            out.add(new ExactFaceCandidate(kind,index,center,origin,axis,area,radius));
        }
        return out;
    }

    private static Bounds exactFaceBounds(List<ExactFaceCandidate> faces){
        Bounds b=new Bounds();
        for(ExactFaceCandidate f:faces){b.add(f.center);b.add(f.origin);}
        return b;
    }

    private static double distanceToExactFace(ExactFaceCandidate f,Geometry3D.Vec3 p){
        Geometry3D.Vec3 q=p.sub(f.origin);
        if(f.type==NativeBRepKernel.OCCT_FACE_PLANE)return Math.abs(q.dot(f.axis));
        double axial=q.dot(f.axis);Geometry3D.Vec3 radial=q.sub(f.axis.mul((float)axial));
        return Math.abs(radial.length()-f.radius);
    }

    private static Ref captureMeshFace(long handle,Geometry3D.Vec3 touchAnchor,String stableId){
        Mesh mesh=mesh(handle);if(mesh==null||mesh.triangles.isEmpty()||touchAnchor==null)return null;
        faceGroups(mesh);
        Triangle picked=null;double best=Double.POSITIVE_INFINITY;
        for(Triangle triangle:mesh.triangles){double d=dist2(triangle.center,touchAnchor);if(d<best){best=d;picked=triangle;}}
        if(picked==null||picked.group==null)return null;
        FaceGroup g=picked.group;Geometry3D.Vec3 c=g.center();
        return new Ref(FACE,stableId,g.area,c,g.normal,
                mesh.bounds.fx(c.x),mesh.bounds.fy(c.y),mesh.bounds.fz(c.z));
    }

'''
    if helper_anchor not in t:
        raise SystemExit("exact edge helper anchor missing")
    t = t.replace(helper_anchor, face_helpers + helper_anchor, 1)
    topo.write_text(t)

# ---------------------------------------------------------------------------
# Instrumentation regression for exact plane/cylinder face references.
# ---------------------------------------------------------------------------
face_test = Path("app/src/androidTest/java/ir/chobyar/sketch/ExactFaceTopologyInstrumentationTest.java")
if not face_test.exists():
    face_test.write_text(r'''package ir.chobyar.sketch;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public final class ExactFaceTopologyInstrumentationTest {
    private static final int N=NativeBRepKernel.OCCT_FACE_RECORD_SIZE;

    @Test public void planarFaceCaptureUsesExactAreaCenterAndNormal(){
        double[] d=concat(
                plane(0,50,40,0,0,0,0,0,0,-1,8000),
                plane(1,50,40,20,0,0,20,0,0,1,8000));
        OcctTopologyRef.Ref ref=OcctTopologyRef.captureFaceDescriptorsForTest(
                d,new Geometry3D.Vec3(45,35,19.9f),"F-top");
        assertNotNull(ref);assertEquals(OcctTopologyRef.FACE,ref.kind);
        assertEquals(NativeBRepKernel.OCCT_FACE_PLANE,ref.signatureKind);
        assertEquals(8000.0,ref.measure,1e-4);
        near(50,ref.anchor.x,.001);near(40,ref.anchor.y,.001);near(20,ref.anchor.z,.001);
        near(1,Math.abs(ref.vector.z),.001);
        android.util.Log.i("ExactFaceTopology","EXACT_FACE_PLANE_CAPTURE area=8000 center=50,40,20 exact=true");
    }

    @Test public void planarFaceReferenceRematchesAfterHistoryDimensionChange(){
        double[] before=concat(
                plane(0,40,30,0,0,0,0,0,0,-1,4800),
                plane(1,40,30,20,0,0,20,0,0,1,4800));
        OcctTopologyRef.Ref ref=OcctTopologyRef.captureFaceDescriptorsForTest(
                before,new Geometry3D.Vec3(40,30,20),"F-history");
        assertNotNull(ref);
        double[] rebuilt=concat(
                plane(4,50,35,0,0,0,0,0,0,-1,7000),
                plane(9,50,35,28,0,0,28,0,0,1,7000));
        OcctTopologyRef.Resolution r=OcctTopologyRef.resolveFaceDescriptorsForTest(rebuilt,ref);
        assertNotNull(r);assertTrue(r.confident());
        near(50,r.anchor.x,.001);near(35,r.anchor.y,.001);near(28,r.anchor.z,.001);
        android.util.Log.i("ExactFaceTopology","EXACT_FACE_PLANE_REMATCH rebuilt=true center=50,35,28");
    }

    @Test public void cylindricalFaceRematchesByAxisRadiusAndArea(){
        double[] before=concat(
                cylinder(2,0,0,20,0,0,0,0,0,1,10,1256.637061),
                plane(3,0,0,0,0,0,0,0,0,-1,314.159265));
        OcctTopologyRef.Ref ref=OcctTopologyRef.captureFaceDescriptorsForTest(
                before,new Geometry3D.Vec3(10,0,20),"F-cylinder");
        assertNotNull(ref);assertEquals(NativeBRepKernel.OCCT_FACE_CYLINDER,ref.signatureKind);
        assertEquals(10.0,ref.secondaryMeasure,1e-4);
        double[] rebuilt=concat(
                cylinder(8,5,4,25,5,4,0,0,0,1,12,1884.955592),
                plane(9,5,4,0,5,4,0,0,0,-1,452.389342));
        OcctTopologyRef.Resolution r=OcctTopologyRef.resolveFaceDescriptorsForTest(rebuilt,ref);
        assertNotNull(r);assertTrue(r.confident());
        near(5,r.anchor.x,.001);near(4,r.anchor.y,.001);near(25,r.anchor.z,.001);
        android.util.Log.i("ExactFaceTopology","EXACT_FACE_CYLINDER_REMATCH radius=12 axisZ=true exact=true");
    }

    private static double[] plane(int index,double cx,double cy,double cz,
                                  double ox,double oy,double oz,
                                  double ax,double ay,double az,double area){
        double[] r=new double[N];r[0]=NativeBRepKernel.OCCT_FACE_PLANE;r[1]=index;
        r[2]=cx;r[3]=cy;r[4]=cz;r[5]=ox;r[6]=oy;r[7]=oz;
        r[8]=ax;r[9]=ay;r[10]=az;r[11]=area;r[12]=0;r[13]=1;return r;
    }

    private static double[] cylinder(int index,double cx,double cy,double cz,
                                     double ox,double oy,double oz,
                                     double ax,double ay,double az,
                                     double radius,double area){
        double[] r=new double[N];r[0]=NativeBRepKernel.OCCT_FACE_CYLINDER;r[1]=index;
        r[2]=cx;r[3]=cy;r[4]=cz;r[5]=ox;r[6]=oy;r[7]=oz;
        r[8]=ax;r[9]=ay;r[10]=az;r[11]=area;r[12]=radius;r[13]=1;return r;
    }

    private static double[] concat(double[]... records){
        double[] out=new double[records.length*N];int at=0;
        for(double[] r:records){System.arraycopy(r,0,out,at,N);at+=N;}return out;
    }

    private static void near(double expected,double actual,double eps){
        assertTrue("expected="+expected+" actual="+actual,Math.abs(expected-actual)<=eps);
    }
}
''')

# Raise the regression gate from 45 to 48 tests / 19 classes.
reg = Path(".github/scripts/run-production-cad-regression.sh")
r = reg.read_text()
if "ExactFaceTopologyInstrumentationTest" not in r:
    r = r.replace(
        "run_contract ExactEdgeTopologyInstrumentationTest exact-edge-topology 'OK (3 tests)'\n",
        "run_contract ExactEdgeTopologyInstrumentationTest exact-edge-topology 'OK (3 tests)'\n"
        "run_contract ExactFaceTopologyInstrumentationTest exact-face-topology 'OK (3 tests)'\n",
        1,
    )
    r = r.replace("Construction / exact Project / associative references / exact topology: 15 tests",
                  "Construction / exact Project / associative references / exact topology: 18 tests")
    r = r.replace('passed_classes" -ne 18', 'passed_classes" -ne 19')
    r = r.replace('expected=18', 'expected=19')
    r = r.replace('classes=18 tests=45', 'classes=19 tests=48')
    reg.write_text(r)

manual = Path(".github/workflows/manual26100-consolidated-regression.yml")
m = manual.read_text()
m = m.replace("Run 45 core Sketch + 3D + Project + Topology contracts on API 35",
              "Run 48 core Sketch + 3D + Project + Topology contracts on API 35")
m = m.replace("classes=18 tests=45", "classes=19 tests=48")
manual.write_text(m)

prod = Path(".github/workflows/solid-command-smoke.yml")
p = prod.read_text()
p = p.replace("45 production CAD contracts on API 35", "48 production CAD contracts on API 35")
if "ExactFaceTopologyInstrumentationTest" not in p:
    p = p.replace("            ExactEdgeTopologyInstrumentationTest; do",
                  "            ExactEdgeTopologyInstrumentationTest \\\n            ExactFaceTopologyInstrumentationTest; do", 1)
prod.write_text(p)

print("Exact face topology patch applied")

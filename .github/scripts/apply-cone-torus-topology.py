from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    p = Path(path)
    text = p.read_text()
    if old not in text:
        raise SystemExit(f"missing expected block in {path}: {old[:80]!r}")
    p.write_text(text.replace(old, new, 1))


replace_once(
    "app/src/main/java/ir/chobyar/sketch/NativeBRepKernel.java",
    """    // Exact OCCT face descriptor contract. Every record has 14 doubles:\n    // kind,index,center.xyz,origin.xyz,axis.xyz,area,radius,orientation.\n    static final int OCCT_FACE_UNSUPPORTED = 0;\n    static final int OCCT_FACE_PLANE = 1;\n    static final int OCCT_FACE_CYLINDER = 2;\n    static final int OCCT_FACE_SPHERE = 3;\n    static final int OCCT_FACE_RECORD_SIZE = 14;\n""",
    """    // Exact OCCT face descriptor contract. Every record has 14 doubles:\n    // kind,index,center.xyz,origin.xyz,axis.xyz,area,shapeParam,orientation.\n    // shapeParam = radius (cylinder/sphere), semi-angle rad (cone), major radius (torus).\n    static final int OCCT_FACE_UNSUPPORTED = 0;\n    static final int OCCT_FACE_PLANE = 1;\n    static final int OCCT_FACE_CYLINDER = 2;\n    static final int OCCT_FACE_SPHERE = 3;\n    static final int OCCT_FACE_CONE = 4;\n    static final int OCCT_FACE_TORUS = 5;\n    static final int OCCT_FACE_RECORD_SIZE = 14;\n""",
)
replace_once(
    "app/src/main/java/ir/chobyar/sketch/NativeBRepKernel.java",
    "/** Exact analytic planar/cylindrical B-Rep faces from OCCT topology. */",
    "/** Exact analytic plane/cylinder/sphere/cone/torus B-Rep faces from OCCT topology. */",
)

replace_once(
    "app/src/main/cpp/occt_brep_jni.cpp",
    """#include <gp_Cylinder.hxx>\n#include <gp_Sphere.hxx>\n#include <gp_Dir.hxx>\n""",
    """#include <gp_Cylinder.hxx>\n#include <gp_Sphere.hxx>\n#include <gp_Cone.hxx>\n#include <gp_Torus.hxx>\n#include <gp_Dir.hxx>\n""",
)
replace_once(
    "app/src/main/cpp/occt_brep_jni.cpp",
    """        constexpr int FACE_PLANE = 1;\n        constexpr int FACE_CYLINDER = 2;\n        constexpr int FACE_SPHERE = 3;\n        constexpr int RECORD = 14;\n""",
    """        constexpr int FACE_PLANE = 1;\n        constexpr int FACE_CYLINDER = 2;\n        constexpr int FACE_SPHERE = 3;\n        constexpr int FACE_CONE = 4;\n        constexpr int FACE_TORUS = 5;\n        constexpr int RECORD = 14;\n""",
)
replace_once(
    "app/src/main/cpp/occt_brep_jni.cpp",
    """            } else if (surface.GetType() == GeomAbs_Sphere) {\n                kind = FACE_SPHERE;\n                const gp_Sphere sphere = surface.Sphere();\n                origin = sphere.Location();\n                axis = sphere.Position().Direction();\n                radius = sphere.Radius();\n            } else {\n                continue;\n            }\n""",
    """            } else if (surface.GetType() == GeomAbs_Sphere) {\n                kind = FACE_SPHERE;\n                const gp_Sphere sphere = surface.Sphere();\n                origin = sphere.Location();\n                axis = sphere.Position().Direction();\n                radius = sphere.Radius();\n            } else if (surface.GetType() == GeomAbs_Cone) {\n                kind = FACE_CONE;\n                const gp_Cone cone = surface.Cone();\n                origin = cone.Location();\n                axis = cone.Axis().Direction();\n                radius = std::abs(cone.SemiAngle());\n            } else if (surface.GetType() == GeomAbs_Torus) {\n                kind = FACE_TORUS;\n                const gp_Torus torus = surface.Torus();\n                origin = torus.Location();\n                axis = torus.Axis().Direction();\n                radius = torus.MajorRadius();\n            } else {\n                continue;\n            }\n""",
)

replace_once(
    "app/src/main/java/ir/chobyar/sketch/OcctTopologyRef.java",
    """            if(kind!=NativeBRepKernel.OCCT_FACE_PLANE&&kind!=NativeBRepKernel.OCCT_FACE_CYLINDER&&kind!=NativeBRepKernel.OCCT_FACE_SPHERE)continue;\n""",
    """            if(kind!=NativeBRepKernel.OCCT_FACE_PLANE&&kind!=NativeBRepKernel.OCCT_FACE_CYLINDER&&\n                    kind!=NativeBRepKernel.OCCT_FACE_SPHERE&&kind!=NativeBRepKernel.OCCT_FACE_CONE&&\n                    kind!=NativeBRepKernel.OCCT_FACE_TORUS)continue;\n""",
)
replace_once(
    "app/src/main/java/ir/chobyar/sketch/OcctTopologyRef.java",
    """            if((kind==NativeBRepKernel.OCCT_FACE_CYLINDER||kind==NativeBRepKernel.OCCT_FACE_SPHERE)&&radius<1e-7)continue;\n""",
    """            if(kind!=NativeBRepKernel.OCCT_FACE_PLANE&&radius<1e-7)continue;\n""",
)
replace_once(
    "app/src/main/java/ir/chobyar/sketch/OcctTopologyRef.java",
    """        if(f.type==NativeBRepKernel.OCCT_FACE_PLANE)return Math.abs(q.dot(f.axis));\n        if(f.type==NativeBRepKernel.OCCT_FACE_SPHERE)return Math.abs(q.length()-f.radius);\n        double axial=q.dot(f.axis);Geometry3D.Vec3 radial=q.sub(f.axis.mul((float)axial));\n        return Math.abs(radial.length()-f.radius);\n""",
    """        if(f.type==NativeBRepKernel.OCCT_FACE_PLANE)return Math.abs(q.dot(f.axis));\n        if(f.type==NativeBRepKernel.OCCT_FACE_SPHERE)return Math.abs(q.length()-f.radius);\n        if(f.type==NativeBRepKernel.OCCT_FACE_CONE||f.type==NativeBRepKernel.OCCT_FACE_TORUS)\n            return Math.sqrt(dist2(f.center,p));\n        double axial=q.dot(f.axis);Geometry3D.Vec3 radial=q.sub(f.axis.mul((float)axial));\n        return Math.abs(radial.length()-f.radius);\n""",
)

replace_once(
    "app/src/androidTest/java/ir/chobyar/sketch/ExactFaceTopologyInstrumentationTest.java",
    """    private static double[] plane(int index,double cx,double cy,double cz,\n""",
    """    @Test public void conicalFaceRematchesByAxisSemiAngleAndArea(){\n        double[] before=concat(\n                cone(6,0,0,20,0,0,0,0,0,1,0.35,1800),\n                plane(7,0,0,0,0,0,0,0,0,-1,500));\n        OcctTopologyRef.Ref ref=OcctTopologyRef.captureFaceDescriptorsForTest(\n                before,new Geometry3D.Vec3(0,0,20),\"F-cone\");\n        assertNotNull(ref);assertEquals(NativeBRepKernel.OCCT_FACE_CONE,ref.signatureKind);\n        assertEquals(0.35,ref.secondaryMeasure,1e-5);\n        double[] rebuilt=concat(\n                cone(14,3,2,26,3,2,0,0,0,1,0.40,2450),\n                plane(2,3,2,0,3,2,0,0,0,-1,650));\n        OcctTopologyRef.Resolution r=OcctTopologyRef.resolveFaceDescriptorsForTest(rebuilt,ref);\n        assertNotNull(r);assertTrue(r.confident());\n        near(3,r.anchor.x,.001);near(2,r.anchor.y,.001);near(26,r.anchor.z,.001);\n        android.util.Log.i(\"ExactFaceTopology\",\"EXACT_FACE_CONE_REMATCH semiAngle=0.40 exact=true\");\n    }\n\n    @Test public void toroidalFaceRematchesByAxisMajorRadiusAndArea(){\n        double[] before=concat(\n                torus(8,10,10,10,10,10,10,0,0,1,30,6200),\n                plane(9,10,10,0,10,10,0,0,0,-1,900));\n        OcctTopologyRef.Ref ref=OcctTopologyRef.captureFaceDescriptorsForTest(\n                before,new Geometry3D.Vec3(10,10,10),\"F-torus\");\n        assertNotNull(ref);assertEquals(NativeBRepKernel.OCCT_FACE_TORUS,ref.signatureKind);\n        assertEquals(30.0,ref.secondaryMeasure,1e-4);\n        double[] rebuilt=concat(\n                torus(3,14,8,12,14,8,12,0,1,0,34,7600),\n                plane(4,14,8,0,14,8,0,0,0,-1,1050));\n        OcctTopologyRef.Resolution r=OcctTopologyRef.resolveFaceDescriptorsForTest(rebuilt,ref);\n        assertNotNull(r);assertTrue(r.confident());\n        near(14,r.anchor.x,.001);near(8,r.anchor.y,.001);near(12,r.anchor.z,.001);\n        android.util.Log.i(\"ExactFaceTopology\",\"EXACT_FACE_TORUS_REMATCH majorRadius=34 exact=true\");\n    }\n\n    private static double[] plane(int index,double cx,double cy,double cz,\n""",
)
replace_once(
    "app/src/androidTest/java/ir/chobyar/sketch/ExactFaceTopologyInstrumentationTest.java",
    """    private static double[] concat(double[]... records){\n""",
    """    private static double[] cone(int index,double cx,double cy,double cz,\n                                 double ox,double oy,double oz,\n                                 double ax,double ay,double az,\n                                 double semiAngle,double area){\n        double[] r=new double[N];r[0]=NativeBRepKernel.OCCT_FACE_CONE;r[1]=index;\n        r[2]=cx;r[3]=cy;r[4]=cz;r[5]=ox;r[6]=oy;r[7]=oz;\n        r[8]=ax;r[9]=ay;r[10]=az;r[11]=area;r[12]=semiAngle;r[13]=1;return r;\n    }\n\n    private static double[] torus(int index,double cx,double cy,double cz,\n                                  double ox,double oy,double oz,\n                                  double ax,double ay,double az,\n                                  double majorRadius,double area){\n        double[] r=new double[N];r[0]=NativeBRepKernel.OCCT_FACE_TORUS;r[1]=index;\n        r[2]=cx;r[3]=cy;r[4]=cz;r[5]=ox;r[6]=oy;r[7]=oz;\n        r[8]=ax;r[9]=ay;r[10]=az;r[11]=area;r[12]=majorRadius;r[13]=1;return r;\n    }\n\n    private static double[] concat(double[]... records){\n""",
)

replace_once(
    ".github/scripts/run-production-cad-regression.sh",
    "# Construction / exact Project / associative references / exact topology: 19 tests",
    "# Construction / exact Project / associative references / exact topology: 21 tests",
)
replace_once(
    ".github/scripts/run-production-cad-regression.sh",
    "run_contract ExactFaceTopologyInstrumentationTest exact-face-topology 'OK (4 tests)'",
    "run_contract ExactFaceTopologyInstrumentationTest exact-face-topology 'OK (6 tests)'",
)
replace_once(
    ".github/scripts/run-production-cad-regression.sh",
    "echo 'PRODUCTION_CAD_REGRESSION OK classes=19 tests=49'",
    "echo 'PRODUCTION_CAD_REGRESSION OK classes=19 tests=51'",
)

print("CONE_TORUS_TOPOLOGY_PATCH_OK")

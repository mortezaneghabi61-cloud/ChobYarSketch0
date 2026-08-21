from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    p = Path(path)
    text = p.read_text()
    if new in text:
        return
    if old not in text:
        raise RuntimeError(f"expected pattern missing in {path}: {old[:120]!r}")
    p.write_text(text.replace(old, new, 1))

# Native Java contract: keep the 14-double record layout and add Sphere as a
# supported analytic surface. Sphere reuses origin=center and radius.
replace_once(
    "app/src/main/java/ir/chobyar/sketch/NativeBRepKernel.java",
    "    static final int OCCT_FACE_PLANE = 1;\n    static final int OCCT_FACE_CYLINDER = 2;\n    static final int OCCT_FACE_RECORD_SIZE = 14;",
    "    static final int OCCT_FACE_PLANE = 1;\n    static final int OCCT_FACE_CYLINDER = 2;\n    static final int OCCT_FACE_SPHERE = 3;\n    static final int OCCT_FACE_RECORD_SIZE = 14;"
)

# OCCT JNI: expose exact spherical faces without touching display triangulation.
replace_once(
    "app/src/main/cpp/occt_brep_jni.cpp",
    "#include <gp_Cylinder.hxx>\n#include <gp_Dir.hxx>",
    "#include <gp_Cylinder.hxx>\n#include <gp_Sphere.hxx>\n#include <gp_Dir.hxx>"
)
replace_once(
    "app/src/main/cpp/occt_brep_jni.cpp",
    "        constexpr int FACE_PLANE = 1;\n        constexpr int FACE_CYLINDER = 2;\n        constexpr int RECORD = 14;",
    "        constexpr int FACE_PLANE = 1;\n        constexpr int FACE_CYLINDER = 2;\n        constexpr int FACE_SPHERE = 3;\n        constexpr int RECORD = 14;"
)
replace_once(
    "app/src/main/cpp/occt_brep_jni.cpp",
    "            } else if (surface.GetType() == GeomAbs_Cylinder) {\n                kind = FACE_CYLINDER;\n                const gp_Cylinder cylinder = surface.Cylinder();\n                origin = cylinder.Location();\n                axis = cylinder.Axis().Direction();\n                radius = cylinder.Radius();\n            } else {",
    "            } else if (surface.GetType() == GeomAbs_Cylinder) {\n                kind = FACE_CYLINDER;\n                const gp_Cylinder cylinder = surface.Cylinder();\n                origin = cylinder.Location();\n                axis = cylinder.Axis().Direction();\n                radius = cylinder.Radius();\n            } else if (surface.GetType() == GeomAbs_Sphere) {\n                kind = FACE_SPHERE;\n                const gp_Sphere sphere = surface.Sphere();\n                origin = sphere.Location();\n                axis = sphere.Position().Direction();\n                radius = sphere.Radius();\n            } else {"
)

# Stable topology matcher: Sphere participates in exact capture and History
# rematching by center, area and radius. Mesh remains fallback for unsupported
# surfaces such as cone/torus until their richer signatures are added.
replace_once(
    "app/src/main/java/ir/chobyar/sketch/OcctTopologyRef.java",
    "            if(kind!=NativeBRepKernel.OCCT_FACE_PLANE&&kind!=NativeBRepKernel.OCCT_FACE_CYLINDER)continue;",
    "            if(kind!=NativeBRepKernel.OCCT_FACE_PLANE&&kind!=NativeBRepKernel.OCCT_FACE_CYLINDER&&kind!=NativeBRepKernel.OCCT_FACE_SPHERE)continue;"
)
replace_once(
    "app/src/main/java/ir/chobyar/sketch/OcctTopologyRef.java",
    "            if(kind==NativeBRepKernel.OCCT_FACE_CYLINDER&&radius<1e-7)continue;",
    "            if((kind==NativeBRepKernel.OCCT_FACE_CYLINDER||kind==NativeBRepKernel.OCCT_FACE_SPHERE)&&radius<1e-7)continue;"
)
replace_once(
    "app/src/main/java/ir/chobyar/sketch/OcctTopologyRef.java",
    "            double radius=(ref.signatureKind==NativeBRepKernel.OCCT_FACE_CYLINDER||f.type==NativeBRepKernel.OCCT_FACE_CYLINDER)\n                    ?relative(ref.secondaryMeasure,f.radius)*20.0:0.0;",
    "            double radius=(ref.secondaryMeasure>1e-7||f.radius>1e-7)\n                    ?relative(ref.secondaryMeasure,f.radius)*20.0:0.0;"
)
replace_once(
    "app/src/main/java/ir/chobyar/sketch/OcctTopologyRef.java",
    "        if(f.type==NativeBRepKernel.OCCT_FACE_PLANE)return Math.abs(q.dot(f.axis));\n        double axial=q.dot(f.axis);Geometry3D.Vec3 radial=q.sub(f.axis.mul((float)axial));\n        return Math.abs(radial.length()-f.radius);",
    "        if(f.type==NativeBRepKernel.OCCT_FACE_PLANE)return Math.abs(q.dot(f.axis));\n        if(f.type==NativeBRepKernel.OCCT_FACE_SPHERE)return Math.abs(q.length()-f.radius);\n        double axial=q.dot(f.axis);Geometry3D.Vec3 radial=q.sub(f.axis.mul((float)axial));\n        return Math.abs(radial.length()-f.radius);"
)

# Add deterministic emulator coverage for spherical capture/rematch.
test_path = Path("app/src/androidTest/java/ir/chobyar/sketch/ExactFaceTopologyInstrumentationTest.java")
test = test_path.read_text()
needle = "    private static double[] plane(int index,double cx,double cy,double cz,"
if "sphericalFaceRematchesByCenterRadiusAndArea" not in test:
    method = '''    @Test public void sphericalFaceRematchesByCenterRadiusAndArea(){\n        double[] before=concat(\n                sphere(4,10,20,30,10,20,30,0,0,1,15,2827.433388),\n                plane(5,10,20,45,10,20,45,0,0,1,706.858347));\n        OcctTopologyRef.Ref ref=OcctTopologyRef.captureFaceDescriptorsForTest(\n                before,new Geometry3D.Vec3(25,20,30),\"F-sphere\");\n        assertNotNull(ref);assertEquals(NativeBRepKernel.OCCT_FACE_SPHERE,ref.signatureKind);\n        assertEquals(15.0,ref.secondaryMeasure,1e-4);\n        double[] rebuilt=concat(\n                sphere(12,12,18,34,12,18,34,0,1,0,18,4071.504079),\n                plane(2,12,18,52,12,18,52,0,0,1,1017.876020));\n        OcctTopologyRef.Resolution r=OcctTopologyRef.resolveFaceDescriptorsForTest(rebuilt,ref);\n        assertNotNull(r);assertTrue(r.confident());\n        near(12,r.anchor.x,.001);near(18,r.anchor.y,.001);near(34,r.anchor.z,.001);\n        android.util.Log.i(\"ExactFaceTopology\",\"EXACT_FACE_SPHERE_REMATCH radius=18 exact=true\");\n    }\n\n'''
    if needle not in test:
        raise RuntimeError("test insertion point missing")
    test = test.replace(needle, method + needle, 1)

helper_needle = "    private static double[] concat(double[]... records){"
if "private static double[] sphere(" not in test:
    helper = '''    private static double[] sphere(int index,double cx,double cy,double cz,\n                                   double ox,double oy,double oz,\n                                   double ax,double ay,double az,\n                                   double radius,double area){\n        double[] r=new double[N];r[0]=NativeBRepKernel.OCCT_FACE_SPHERE;r[1]=index;\n        r[2]=cx;r[3]=cy;r[4]=cz;r[5]=ox;r[6]=oy;r[7]=oz;\n        r[8]=ax;r[9]=ay;r[10]=az;r[11]=area;r[12]=radius;r[13]=1;return r;\n    }\n\n'''
    if helper_needle not in test:
        raise RuntimeError("helper insertion point missing")
    test = test.replace(helper_needle, helper + helper_needle, 1)
test_path.write_text(test)

# Raise durable regression gate from 48 to 49 while keeping 19 classes.
replace_once(
    ".github/scripts/run-production-cad-regression.sh",
    "# Construction / exact Project / associative references / exact topology: 18 tests",
    "# Construction / exact Project / associative references / exact topology: 19 tests"
)
replace_once(
    ".github/scripts/run-production-cad-regression.sh",
    "run_contract ExactFaceTopologyInstrumentationTest exact-face-topology 'OK (3 tests)'",
    "run_contract ExactFaceTopologyInstrumentationTest exact-face-topology 'OK (4 tests)'"
)
replace_once(
    ".github/scripts/run-production-cad-regression.sh",
    "PRODUCTION_CAD_REGRESSION OK classes=19 tests=48",
    "PRODUCTION_CAD_REGRESSION OK classes=19 tests=49"
)
replace_once(
    ".github/workflows/manual26100-consolidated-regression.yml",
    "Run 48 core Sketch + 3D + Project + Topology contracts on API 35",
    "Run 49 core Sketch + 3D + Project + Topology contracts on API 35"
)
replace_once(
    ".github/workflows/manual26100-consolidated-regression.yml",
    "CONSOLIDATED_RESULT classes=19 tests=48 status=PASS",
    "CONSOLIDATED_RESULT classes=19 tests=49 status=PASS"
)
replace_once(
    ".github/workflows/solid-command-smoke.yml",
    "name: 48 production CAD contracts on API 35",
    "name: 49 production CAD contracts on API 35"
)

print("SPHERE_FACE_TOPOLOGY_PATCH_OK")
# trigger after workflow registration

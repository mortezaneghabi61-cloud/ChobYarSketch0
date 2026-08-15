package ir.chobyar.sketch;

/**
 * Stable Java/JNI boundary for the future exact native B-Rep backend.
 *
 * The first native module deliberately has no dependency on OCCT yet: it proves
 * Android NDK/CMake/JNI integration, moves exact analytic math into C++ and gives
 * the UI a stable contract. The next backend can link Open CASCADE behind this
 * class without forcing another UI rewrite.
 */
final class NativeBRepKernel {
    private static final boolean AVAILABLE;
    private static final String LOAD_ERROR;

    static {
        boolean ok=false;
        String error="";
        try {
            System.loadLibrary("chobyar_brep");
            ok=true;
        } catch (Throwable t) {
            error=t.getClass().getSimpleName()+": "+String.valueOf(t.getMessage());
        }
        AVAILABLE=ok;
        LOAD_ERROR=error;
    }

    private NativeBRepKernel() {}

    static boolean isAvailable(){ return AVAILABLE; }
    static String loadError(){ return LOAD_ERROR; }

    static String version(){
        if(!AVAILABLE)return "Native kernel unavailable";
        try{return nativeVersion();}catch(Throwable t){return "Native version failed";}
    }

    static String selfTest(){
        if(!AVAILABLE)return "FAIL • "+LOAD_ERROR;
        try{return nativeSelfTest();}catch(Throwable t){return "FAIL • "+t.getClass().getSimpleName();}
    }

    static int capabilityFlags(){
        if(!AVAILABLE)return 0;
        try{return nativeCapabilityFlags();}catch(Throwable t){return 0;}
    }

    static double[] sphereSphere(Geometry3D.Vec3 a,float ar,Geometry3D.Vec3 b,float br){
        if(!AVAILABLE||a==null||b==null)return new double[0];
        try{return nativeSphereSphereIntersection(a.x,a.y,a.z,ar,b.x,b.y,b.z,br);}
        catch(Throwable t){return new double[0];}
    }

    static double[] planeSphere(Geometry3D.Vec3 point,Geometry3D.Vec3 normal,Geometry3D.Vec3 center,float radius){
        if(!AVAILABLE||point==null||normal==null||center==null)return new double[0];
        try{return nativePlaneSphereIntersection(point.x,point.y,point.z,normal.x,normal.y,normal.z,center.x,center.y,center.z,radius);}
        catch(Throwable t){return new double[0];}
    }

    static double[] cylinderMass(float radiusMm,float heightMm){
        if(!AVAILABLE)return new double[0];
        try{return nativeCylinderMassProperties(radiusMm,heightMm);}
        catch(Throwable t){return new double[0];}
    }

    private static native String nativeVersion();
    private static native int nativeCapabilityFlags();
    private static native String nativeSelfTest();
    private static native double[] nativeSphereSphereIntersection(
            double ax,double ay,double az,double ar,
            double bx,double by,double bz,double br);
    private static native double[] nativePlaneSphereIntersection(
            double px,double py,double pz,
            double nx,double ny,double nz,
            double sx,double sy,double sz,double radius);
    private static native double[] nativeCylinderMassProperties(double radius,double height);
}

package ir.chobyar.sketch;

/**
 * Stable Java/JNI boundary for ChobYar's native geometry backend.
 *
 * The bridge keeps the UI independent from the kernel implementation. Basic
 * analytic math is always available in C++; on arm64-v8a CI additionally links
 * the pinned Open CASCADE Technology backend for exact B-Rep primitives and
 * Boolean operations.
 */
final class NativeBRepKernel {
    static final int OCCT_UNION = 0;
    static final int OCCT_SUBTRACT = 1;
    static final int OCCT_INTERSECT = 2;

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

    static boolean occtAvailable(){
        if(!AVAILABLE)return false;
        try{return nativeOcctAvailable();}catch(Throwable t){return false;}
    }

    static String occtVersion(){
        if(!AVAILABLE)return "OCCT unavailable";
        try{return nativeOcctVersion();}catch(Throwable t){return "OCCT version unavailable";}
    }

    static String occtSelfTest(){
        if(!AVAILABLE)return "FAIL • Native library unavailable";
        try{return nativeOcctSelfTest();}catch(Throwable t){return "FAIL • "+t.getClass().getSimpleName();}
    }

    static long occtCreateBox(double dxMm,double dyMm,double dzMm){
        if(!occtAvailable())return 0L;
        try{return nativeOcctCreateBox(dxMm,dyMm,dzMm);}catch(Throwable t){return 0L;}
    }

    static long occtCreateCylinder(double cxMm,double cyMm,double czMm,double radiusMm,double heightMm){
        if(!occtAvailable())return 0L;
        try{return nativeOcctCreateCylinder(cxMm,cyMm,czMm,radiusMm,heightMm);}catch(Throwable t){return 0L;}
    }

    static long occtBoolean(int operation,long left,long right){
        if(!occtAvailable()||left==0L||right==0L)return 0L;
        try{return nativeOcctBoolean(operation,left,right);}catch(Throwable t){return 0L;}
    }

    static double[] occtShapeStats(long handle){
        if(!occtAvailable()||handle==0L)return new double[0];
        try{return nativeOcctShapeStats(handle);}catch(Throwable t){return new double[0];}
    }

    static String occtShapeSummary(long handle){
        if(!occtAvailable()||handle==0L)return "OCCT Shape unavailable";
        try{return nativeOcctShapeSummary(handle);}catch(Throwable t){return "OCCT Shape summary failed";}
    }

    static void occtRelease(long handle){
        if(!AVAILABLE||handle==0L)return;
        try{nativeOcctRelease(handle);}catch(Throwable ignored){}
    }

    static void occtClear(){
        if(!AVAILABLE)return;
        try{nativeOcctClear();}catch(Throwable ignored){}
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

    private static native boolean nativeOcctAvailable();
    private static native String nativeOcctVersion();
    private static native String nativeOcctSelfTest();
    private static native long nativeOcctCreateBox(double dx,double dy,double dz);
    private static native long nativeOcctCreateCylinder(double cx,double cy,double cz,double radius,double height);
    private static native long nativeOcctBoolean(int operation,long left,long right);
    private static native double[] nativeOcctShapeStats(long handle);
    private static native String nativeOcctShapeSummary(long handle);
    private static native void nativeOcctRelease(long handle);
    private static native void nativeOcctClear();
}

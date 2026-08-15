package ir.chobyar.sketch;

/**
 * Stable Java/JNI boundary for skachmori's native geometry backend.
 *
 * The UI talks only to this contract. The arm64 backend links Open CASCADE and
 * builds exact B-Rep solids, performs Boolean and direct-edit operations, and
 * returns a display triangulation generated from the resulting TopoDS_Shape.
 */
final class NativeBRepKernel {
    static final int OCCT_UNION = 0;
    static final int OCCT_SUBTRACT = 1;
    static final int OCCT_INTERSECT = 2;

    static final int OCCT_PROFILE_POLYGON = 0;
    static final int OCCT_PROFILE_CIRCLE = 1;

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

    static long occtCreateCylinderAxis(Geometry3D.Vec3 center,Geometry3D.Vec3 axis,double radiusMm,double heightMm){
        if(!occtAvailable()||center==null||axis==null)return 0L;
        try{return nativeOcctCreateCylinderAxis(center.x,center.y,center.z,axis.x,axis.y,axis.z,radiusMm,heightMm);}
        catch(Throwable t){return 0L;}
    }

    /** xyz is [x0,y0,z0,x1,y1,z1,...] in model millimeters. */
    static long occtCreatePrism(double[] xyz,Geometry3D.Vec3 extrusionVectorMm){
        if(!occtAvailable()||xyz==null||xyz.length<9||extrusionVectorMm==null)return 0L;
        try{return nativeOcctCreatePrism(xyz,extrusionVectorMm.x,extrusionVectorMm.y,extrusionVectorMm.z);}
        catch(Throwable t){return 0L;}
    }

    /**
     * profileData:
     * POLYGON -> xyz triples in mm.
     * CIRCLE  -> [cx,cy,cz,nx,ny,nz,ux,uy,uz,radiusMm].
     */
    static long occtCreateRevolve(int profileType,double[] profileData,
                                  Geometry3D.Vec3 axisOrigin,Geometry3D.Vec3 axisDirection,
                                  double angleDeg){
        if(!occtAvailable()||profileData==null||axisOrigin==null||axisDirection==null)return 0L;
        try{return nativeOcctCreateRevolve(profileType,profileData,
                axisOrigin.x,axisOrigin.y,axisOrigin.z,
                axisDirection.x,axisDirection.y,axisDirection.z,angleDeg);}
        catch(Throwable t){return 0L;}
    }

    static long occtCreateSweep(int profileType,double[] profileData,double[] pathXYZ){
        if(!occtAvailable()||profileData==null||pathXYZ==null||pathXYZ.length<6)return 0L;
        try{return nativeOcctCreateSweep(profileType,profileData,pathXYZ);}
        catch(Throwable t){return 0L;}
    }

    static long occtCreateLoft(int firstType,double[] firstProfileData,
                               int secondType,double[] secondProfileData){
        if(!occtAvailable()||firstProfileData==null||secondProfileData==null)return 0L;
        try{return nativeOcctCreateLoft(firstType,firstProfileData,secondType,secondProfileData);}
        catch(Throwable t){return 0L;}
    }

    static long occtBoolean(int operation,long left,long right){
        if(!occtAvailable()||left==0L||right==0L)return 0L;
        try{return nativeOcctBoolean(operation,left,right);}catch(Throwable t){return 0L;}
    }

    // ------------------------------------------------------------------
    // Exact direct modeling on an existing TopoDS_Shape
    // ------------------------------------------------------------------

    static long occtFillet(long handle,Geometry3D.Vec3 edgeAnchor,double radiusMm,boolean allEdges){
        if(!occtAvailable()||handle==0L||radiusMm<=0.0)return 0L;
        double x=edgeAnchor==null?Double.NaN:edgeAnchor.x;
        double y=edgeAnchor==null?Double.NaN:edgeAnchor.y;
        double z=edgeAnchor==null?Double.NaN:edgeAnchor.z;
        try{return nativeOcctFillet(handle,x,y,z,radiusMm,allEdges);}catch(Throwable t){return 0L;}
    }

    static long occtChamfer(long handle,Geometry3D.Vec3 edgeAnchor,double distanceMm,boolean allEdges){
        if(!occtAvailable()||handle==0L||distanceMm<=0.0)return 0L;
        double x=edgeAnchor==null?Double.NaN:edgeAnchor.x;
        double y=edgeAnchor==null?Double.NaN:edgeAnchor.y;
        double z=edgeAnchor==null?Double.NaN:edgeAnchor.z;
        try{return nativeOcctChamfer(handle,x,y,z,distanceMm,allEdges);}catch(Throwable t){return 0L;}
    }

    static long occtPushPullFace(long handle,Geometry3D.Vec3 faceAnchor,double distanceMm){
        if(!occtAvailable()||handle==0L||faceAnchor==null||Math.abs(distanceMm)<1e-9)return 0L;
        try{return nativeOcctPushPullFace(handle,faceAnchor.x,faceAnchor.y,faceAnchor.z,distanceMm);}
        catch(Throwable t){return 0L;}
    }

    static long occtShell(long handle,Geometry3D.Vec3 openingFaceAnchor,double thicknessMm){
        if(!occtAvailable()||handle==0L||openingFaceAnchor==null||thicknessMm<=0.0)return 0L;
        try{return nativeOcctShell(handle,openingFaceAnchor.x,openingFaceAnchor.y,openingFaceAnchor.z,thicknessMm);}
        catch(Throwable t){return 0L;}
    }

    static long occtTranslate(long handle,Geometry3D.Vec3 deltaMm){
        if(!occtAvailable()||handle==0L||deltaMm==null)return 0L;
        try{return nativeOcctTranslate(handle,deltaMm.x,deltaMm.y,deltaMm.z);}catch(Throwable t){return 0L;}
    }

    static long occtRotate(long handle,Geometry3D.Vec3 axis,double angleDeg){
        if(!occtAvailable()||handle==0L||axis==null||Math.abs(angleDeg)<1e-9)return 0L;
        try{return nativeOcctRotate(handle,axis.x,axis.y,axis.z,angleDeg);}catch(Throwable t){return 0L;}
    }

    static double[] occtShapeStats(long handle){
        if(!occtAvailable()||handle==0L)return new double[0];
        try{return nativeOcctShapeStats(handle);}catch(Throwable t){return new double[0];}
    }

    /** Returns triangle coordinates [x,y,z] × 3 per triangle, in mm. */
    static double[] occtTriangulate(long handle,double deflectionMm){
        if(!occtAvailable()||handle==0L)return new double[0];
        try{return nativeOcctTriangulate(handle,deflectionMm);}catch(Throwable t){return new double[0];}
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
    private static native long nativeOcctCreateCylinderAxis(
            double cx,double cy,double cz,
            double ax,double ay,double az,
            double radius,double height);
    private static native long nativeOcctCreatePrism(double[] xyz,double vx,double vy,double vz);
    private static native long nativeOcctCreateRevolve(
            int profileType,double[] profileData,
            double ox,double oy,double oz,
            double ax,double ay,double az,double angleDeg);
    private static native long nativeOcctCreateSweep(int profileType,double[] profileData,double[] pathXYZ);
    private static native long nativeOcctCreateLoft(
            int firstType,double[] firstProfileData,
            int secondType,double[] secondProfileData);
    private static native long nativeOcctBoolean(int operation,long left,long right);

    private static native long nativeOcctFillet(long handle,double ax,double ay,double az,double radius,boolean allEdges);
    private static native long nativeOcctChamfer(long handle,double ax,double ay,double az,double distance,boolean allEdges);
    private static native long nativeOcctPushPullFace(long handle,double ax,double ay,double az,double distance);
    private static native long nativeOcctShell(long handle,double ax,double ay,double az,double thickness);
    private static native long nativeOcctTranslate(long handle,double dx,double dy,double dz);
    private static native long nativeOcctRotate(long handle,double ax,double ay,double az,double angleDeg);

    private static native double[] nativeOcctShapeStats(long handle);
    private static native double[] nativeOcctTriangulate(long handle,double deflection);
    private static native String nativeOcctShapeSummary(long handle);
    private static native void nativeOcctRelease(long handle);
    private static native void nativeOcctClear();
}

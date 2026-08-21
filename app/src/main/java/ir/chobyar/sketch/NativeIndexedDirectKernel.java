package ir.chobyar.sketch;

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

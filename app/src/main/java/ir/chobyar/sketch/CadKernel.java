package ir.chobyar.sketch;

/**
 * Stable V2 modeling contract. Presentation code depends on this interface,
 * never on JNI or Open CASCADE directly.
 */
public interface CadKernel {
    enum BooleanOperation { UNION, SUBTRACT, INTERSECT }

    boolean isAvailable();

    String backendVersion();

    CadKernelResult<String> selfTest();

    CadKernelResult<CadBodyRef> createBox(double xMm, double yMm, double zMm);

    CadKernelResult<CadBodyRef> createCylinder(CadVector3 centerMm,
                                               CadVector3 axis,
                                               double radiusMm,
                                               double heightMm);

    CadKernelResult<CadBodyRef> extrude(CadProfile profile, CadVector3 extrusionMm);

    CadKernelResult<CadBodyRef> revolve(CadProfile profile,
                                        CadVector3 axisOriginMm,
                                        CadVector3 axisDirection,
                                        double angleDeg);

    CadKernelResult<CadBodyRef> helicalRevolve(CadProfile profile,
                                               CadVector3 axisOriginMm,
                                               CadVector3 axisDirection,
                                               double angleDeg,
                                               double heightMm);

    CadKernelResult<CadBodyRef> sweep(CadProfile profile, double[] pathXYZMm);

    CadKernelResult<CadBodyRef> loft(CadProfile first, CadProfile second);

    CadKernelResult<CadBodyRef> booleanOperation(BooleanOperation operation,
                                                 CadBodyRef left,
                                                 CadBodyRef right);

    CadKernelResult<CadBodyRef> fillet(CadBodyRef body,
                                       CadVector3 edgeAnchorMm,
                                       double radiusMm,
                                       boolean allEdges);

    CadKernelResult<CadBodyRef> chamfer(CadBodyRef body,
                                        CadVector3 edgeAnchorMm,
                                        double distanceMm,
                                        boolean allEdges);

    CadKernelResult<CadBodyRef> pushPullFace(CadBodyRef body,
                                             CadVector3 faceAnchorMm,
                                             double distanceMm);

    CadKernelResult<CadBodyRef> shell(CadBodyRef body,
                                      CadVector3 openingFaceAnchorMm,
                                      double thicknessMm);

    CadKernelResult<CadBodyRef> translate(CadBodyRef body, CadVector3 deltaMm);

    CadKernelResult<CadBodyRef> rotate(CadBodyRef body, CadVector3 axis, double angleDeg);

    CadKernelResult<CadBodyRef> scale(CadBodyRef body, double factor);

    CadKernelResult<CadBodyRef> mirror(CadBodyRef body, CadVector3 normal);

    CadKernelResult<CadBodyRef> linearPattern(CadBodyRef body, CadVector3 stepMm, int count);

    CadKernelResult<double[]> shapeStats(CadBodyRef body);

    CadKernelResult<double[]> triangulate(CadBodyRef body, double deflectionMm);

    CadKernelResult<double[]> edgeDescriptors(CadBodyRef body);

    CadKernelResult<double[]> faceDescriptors(CadBodyRef body);

    CadKernelResult<String> shapeSummary(CadBodyRef body);

    CadKernelResult<Boolean> export(CadBodyRef[] bodies, String path, int format);

    void release(CadBodyRef body);

    void clear();
}

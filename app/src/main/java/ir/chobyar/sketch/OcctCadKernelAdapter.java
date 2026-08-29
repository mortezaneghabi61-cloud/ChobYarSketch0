package ir.chobyar.sketch;

import java.util.ArrayList;
import java.util.List;

/**
 * Migration adapter from the V2 CAD-kernel contract to the existing OCCT/JNI
 * implementation. This class is intentionally the only new V2 code that knows
 * about NativeBRepKernel raw handles.
 */
public final class OcctCadKernelAdapter implements CadKernel {
    private static final double EPS = 1e-9;

    @Override
    public boolean isAvailable() {
        return NativeBRepKernel.occtAvailable();
    }

    @Override
    public String backendVersion() {
        return NativeBRepKernel.occtVersion();
    }

    @Override
    public CadKernelResult<String> selfTest() {
        if (!isAvailable()) return unavailable("OCCT backend is unavailable");
        String text = NativeBRepKernel.occtSelfTest();
        if (text == null || text.startsWith("FAIL")) {
            return failure(CadKernelResult.Code.NATIVE_FAILURE,
                    text == null ? "OCCT self-test returned no result" : text);
        }
        return CadKernelResult.success(text);
    }

    @Override
    public CadKernelResult<CadBodyRef> createBox(double xMm, double yMm, double zMm) {
        if (!positive(xMm) || !positive(yMm) || !positive(zMm)) {
            return invalid("Box dimensions must be finite and greater than zero");
        }
        return bodyResult(NativeBRepKernel.occtCreateBox(xMm, yMm, zMm), "create box");
    }

    @Override
    public CadKernelResult<CadBodyRef> createCylinder(CadVector3 centerMm,
                                                      CadVector3 axis,
                                                      double radiusMm,
                                                      double heightMm) {
        if (!finite(centerMm) || axis == null || !axis.isNonZero()
                || !positive(radiusMm) || !positive(heightMm)) {
            return invalid("Invalid cylinder parameters");
        }
        return bodyResult(NativeBRepKernel.occtCreateCylinderAxis(
                centerMm.toLegacyVec3(), axis.toLegacyVec3(), radiusMm, heightMm), "create cylinder");
    }

    @Override
    public CadKernelResult<CadBodyRef> extrude(CadProfile profile, CadVector3 extrusionMm) {
        if (profile == null || extrusionMm == null || !extrusionMm.isNonZero()) {
            return invalid("Extrude requires a profile and non-zero vector");
        }
        if (profile.type() != CadProfile.Type.POLYGON) {
            return failure(CadKernelResult.Code.UNSUPPORTED,
                    "The current OCCT migration adapter extrudes polygon profiles only");
        }
        return bodyResult(NativeBRepKernel.occtCreatePrism(
                profile.nativeDataCopy(), extrusionMm.toLegacyVec3()), "extrude profile");
    }

    @Override
    public CadKernelResult<CadBodyRef> revolve(CadProfile profile,
                                               CadVector3 axisOriginMm,
                                               CadVector3 axisDirection,
                                               double angleDeg) {
        if (!validProfileAxis(profile, axisOriginMm, axisDirection) || !finiteNonZero(angleDeg)) {
            return invalid("Invalid revolve parameters");
        }
        return bodyResult(NativeBRepKernel.occtCreateRevolve(
                profile.nativeProfileType(), profile.nativeDataCopy(),
                axisOriginMm.toLegacyVec3(), axisDirection.toLegacyVec3(), angleDeg), "revolve profile");
    }

    @Override
    public CadKernelResult<CadBodyRef> helicalRevolve(CadProfile profile,
                                                      CadVector3 axisOriginMm,
                                                      CadVector3 axisDirection,
                                                      double angleDeg,
                                                      double heightMm) {
        if (!validProfileAxis(profile, axisOriginMm, axisDirection)
                || !finiteNonZero(angleDeg) || !Double.isFinite(heightMm)) {
            return invalid("Invalid helical-revolve parameters");
        }
        return bodyResult(NativeBRepKernel.occtCreateHelicalRevolve(
                profile.nativeProfileType(), profile.nativeDataCopy(),
                axisOriginMm.toLegacyVec3(), axisDirection.toLegacyVec3(), angleDeg, heightMm),
                "helical revolve profile");
    }

    @Override
    public CadKernelResult<CadBodyRef> sweep(CadProfile profile, double[] pathXYZMm) {
        if (profile == null || !validXYZ(pathXYZMm, 2)) {
            return invalid("Sweep requires a profile and at least two finite XYZ path points");
        }
        return bodyResult(NativeBRepKernel.occtCreateSweep(
                profile.nativeProfileType(), profile.nativeDataCopy(), pathXYZMm.clone()), "sweep profile");
    }

    @Override
    public CadKernelResult<CadBodyRef> loft(CadProfile first, CadProfile second) {
        if (first == null || second == null) return invalid("Loft requires two profiles");
        return bodyResult(NativeBRepKernel.occtCreateLoft(
                first.nativeProfileType(), first.nativeDataCopy(),
                second.nativeProfileType(), second.nativeDataCopy()), "loft profiles");
    }

    @Override
    public CadKernelResult<CadBodyRef> booleanOperation(BooleanOperation operation,
                                                        CadBodyRef left,
                                                        CadBodyRef right) {
        if (operation == null || !valid(left) || !valid(right)) {
            return invalid("Boolean operation requires two valid bodies");
        }
        int nativeOp;
        switch (operation) {
            case UNION: nativeOp = NativeBRepKernel.OCCT_UNION; break;
            case SUBTRACT: nativeOp = NativeBRepKernel.OCCT_SUBTRACT; break;
            case INTERSECT: nativeOp = NativeBRepKernel.OCCT_INTERSECT; break;
            default: return failure(CadKernelResult.Code.UNSUPPORTED, "Unsupported Boolean operation");
        }
        return bodyResult(NativeBRepKernel.occtBoolean(nativeOp,
                left.nativeHandle(), right.nativeHandle()), "boolean operation");
    }

    @Override
    public CadKernelResult<CadBodyRef> fillet(CadBodyRef body, CadVector3 edgeAnchorMm,
                                              double radiusMm, boolean allEdges) {
        if (!valid(body) || !positive(radiusMm) || (!allEdges && !finite(edgeAnchorMm))) {
            return invalid("Invalid fillet parameters");
        }
        Geometry3D.Vec3 anchor = edgeAnchorMm == null ? null : edgeAnchorMm.toLegacyVec3();
        return bodyResult(NativeBRepKernel.occtFillet(body.nativeHandle(), anchor, radiusMm, allEdges), "fillet");
    }

    @Override
    public CadKernelResult<CadBodyRef> chamfer(CadBodyRef body, CadVector3 edgeAnchorMm,
                                               double distanceMm, boolean allEdges) {
        if (!valid(body) || !positive(distanceMm) || (!allEdges && !finite(edgeAnchorMm))) {
            return invalid("Invalid chamfer parameters");
        }
        Geometry3D.Vec3 anchor = edgeAnchorMm == null ? null : edgeAnchorMm.toLegacyVec3();
        return bodyResult(NativeBRepKernel.occtChamfer(body.nativeHandle(), anchor, distanceMm, allEdges), "chamfer");
    }

    @Override
    public CadKernelResult<CadBodyRef> pushPullFace(CadBodyRef body,
                                                    CadVector3 faceAnchorMm,
                                                    double distanceMm) {
        if (!valid(body) || !finite(faceAnchorMm) || !finiteNonZero(distanceMm)) {
            return invalid("Invalid push/pull parameters");
        }
        return bodyResult(NativeBRepKernel.occtPushPullFace(
                body.nativeHandle(), faceAnchorMm.toLegacyVec3(), distanceMm), "push/pull face");
    }

    @Override
    public CadKernelResult<CadBodyRef> shell(CadBodyRef body,
                                             CadVector3 openingFaceAnchorMm,
                                             double thicknessMm) {
        if (!valid(body) || !finite(openingFaceAnchorMm) || !positive(thicknessMm)) {
            return invalid("Invalid shell parameters");
        }
        return bodyResult(NativeBRepKernel.occtShell(
                body.nativeHandle(), openingFaceAnchorMm.toLegacyVec3(), thicknessMm), "shell");
    }

    @Override
    public CadKernelResult<CadBodyRef> translate(CadBodyRef body, CadVector3 deltaMm) {
        if (!valid(body) || !finite(deltaMm)) return invalid("Invalid translation parameters");
        return bodyResult(NativeBRepKernel.occtTranslate(
                body.nativeHandle(), deltaMm.toLegacyVec3()), "translate");
    }

    @Override
    public CadKernelResult<CadBodyRef> rotate(CadBodyRef body, CadVector3 axis, double angleDeg) {
        if (!valid(body) || axis == null || !axis.isNonZero() || !finiteNonZero(angleDeg)) {
            return invalid("Invalid rotation parameters");
        }
        return bodyResult(NativeBRepKernel.occtRotate(
                body.nativeHandle(), axis.toLegacyVec3(), angleDeg), "rotate");
    }

    @Override
    public CadKernelResult<CadBodyRef> scale(CadBodyRef body, double factor) {
        if (!valid(body) || !positive(factor)) return invalid("Scale factor must be finite and positive");
        return bodyResult(NativeBRepKernel.occtScale(body.nativeHandle(), factor), "scale");
    }

    @Override
    public CadKernelResult<CadBodyRef> mirror(CadBodyRef body, CadVector3 normal) {
        if (!valid(body) || normal == null || !normal.isNonZero()) return invalid("Invalid mirror parameters");
        return bodyResult(NativeBRepKernel.occtMirror(
                body.nativeHandle(), normal.toLegacyVec3()), "mirror");
    }

    @Override
    public CadKernelResult<CadBodyRef> linearPattern(CadBodyRef body, CadVector3 stepMm, int count) {
        if (!valid(body) || stepMm == null || !stepMm.isNonZero() || count < 2) {
            return invalid("Linear pattern requires a non-zero step and count >= 2");
        }
        return bodyResult(NativeBRepKernel.occtLinearPattern(
                body.nativeHandle(), stepMm.toLegacyVec3(), count), "linear pattern");
    }

    @Override
    public CadKernelResult<double[]> shapeStats(CadBodyRef body) {
        return arrayResult(body, NativeBRepKernel.occtShapeStats(valid(body) ? body.nativeHandle() : 0L), "shape stats");
    }

    @Override
    public CadKernelResult<double[]> triangulate(CadBodyRef body, double deflectionMm) {
        if (!valid(body) || !positive(deflectionMm)) return invalid("Invalid tessellation parameters");
        return arrayResult(body, NativeBRepKernel.occtTriangulate(body.nativeHandle(), deflectionMm), "triangulate");
    }

    @Override
    public CadKernelResult<double[]> edgeDescriptors(CadBodyRef body) {
        return arrayResult(body, NativeBRepKernel.occtEdgeDescriptors(valid(body) ? body.nativeHandle() : 0L), "edge descriptors");
    }

    @Override
    public CadKernelResult<double[]> faceDescriptors(CadBodyRef body) {
        return arrayResult(body, NativeBRepKernel.occtFaceDescriptors(valid(body) ? body.nativeHandle() : 0L), "face descriptors");
    }

    @Override
    public CadKernelResult<String> shapeSummary(CadBodyRef body) {
        if (!valid(body)) return invalid("Invalid body");
        if (!isAvailable()) return unavailable("OCCT backend is unavailable");
        String text = NativeBRepKernel.occtShapeSummary(body.nativeHandle());
        if (text == null || text.contains("unavailable") || text.contains("failed")) {
            return failure(CadKernelResult.Code.NATIVE_FAILURE,
                    text == null ? "Shape summary failed" : text);
        }
        return CadKernelResult.success(text);
    }

    @Override
    public CadKernelResult<Boolean> export(CadBodyRef[] bodies, String path, int format) {
        if (bodies == null || bodies.length == 0 || path == null || path.trim().isEmpty()) {
            return invalid("Export requires bodies and a destination path");
        }
        List<Long> handles = new ArrayList<>();
        for (CadBodyRef body : bodies) {
            if (!valid(body)) return invalid("Export contains an invalid body");
            handles.add(body.nativeHandle());
        }
        long[] raw = new long[handles.size()];
        for (int i = 0; i < handles.size(); i++) raw[i] = handles.get(i);
        if (!isAvailable()) return unavailable("OCCT backend is unavailable");
        boolean ok = NativeBRepKernel.occtExport(raw, path, format);
        return ok ? CadKernelResult.success(Boolean.TRUE)
                : failure(CadKernelResult.Code.NATIVE_FAILURE, "Native export failed");
    }

    @Override
    public void release(CadBodyRef body) {
        if (valid(body)) NativeBRepKernel.occtRelease(body.nativeHandle());
    }

    @Override
    public void clear() {
        NativeBRepKernel.occtClear();
    }

    private CadKernelResult<CadBodyRef> bodyResult(long handle, String operation) {
        if (!isAvailable()) return unavailable("OCCT backend is unavailable");
        if (handle <= 0L) return failure(CadKernelResult.Code.NATIVE_FAILURE, "Native " + operation + " failed");
        return CadKernelResult.success(CadBodyRef.fromNative(handle));
    }

    private CadKernelResult<double[]> arrayResult(CadBodyRef body, double[] values, String operation) {
        if (!valid(body)) return invalid("Invalid body");
        if (!isAvailable()) return unavailable("OCCT backend is unavailable");
        if (values == null || values.length == 0) {
            return failure(CadKernelResult.Code.NATIVE_FAILURE, "Native " + operation + " returned no data");
        }
        return CadKernelResult.success(values);
    }

    private static boolean valid(CadBodyRef body) {
        return body != null && body.isValid();
    }

    private static boolean finite(CadVector3 value) {
        return value != null && value.isFinite();
    }

    private static boolean positive(double value) {
        return Double.isFinite(value) && value > 0.0;
    }

    private static boolean finiteNonZero(double value) {
        return Double.isFinite(value) && Math.abs(value) > EPS;
    }

    private static boolean validProfileAxis(CadProfile profile, CadVector3 origin, CadVector3 axis) {
        return profile != null && finite(origin) && axis != null && axis.isNonZero();
    }

    private static boolean validXYZ(double[] values, int minPoints) {
        if (values == null || values.length < minPoints * 3 || values.length % 3 != 0) return false;
        for (double value : values) if (!Double.isFinite(value)) return false;
        return true;
    }

    private static <T> CadKernelResult<T> invalid(String message) {
        return failure(CadKernelResult.Code.INVALID_ARGUMENT, message);
    }

    private static <T> CadKernelResult<T> unavailable(String message) {
        return failure(CadKernelResult.Code.UNAVAILABLE, message);
    }

    private static <T> CadKernelResult<T> failure(CadKernelResult.Code code, String message) {
        return CadKernelResult.failure(code, message);
    }
}

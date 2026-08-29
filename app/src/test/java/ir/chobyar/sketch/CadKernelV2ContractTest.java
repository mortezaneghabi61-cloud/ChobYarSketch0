package ir.chobyar.sketch;

import org.junit.Test;

import static org.junit.Assert.*;

public class CadKernelV2ContractTest {

    @Test
    public void resultKeepsFailureMeaning() {
        CadKernelResult<String> result = CadKernelResult.failure(
                CadKernelResult.Code.INVALID_ARGUMENT, "bad input");

        assertFalse(result.isSuccess());
        assertEquals(CadKernelResult.Code.INVALID_ARGUMENT, result.code());
        assertEquals("bad input", result.message());
        assertNull(result.value());
    }

    @Test(expected = IllegalStateException.class)
    public void failedResultCannotBeRequiredAsValue() {
        CadKernelResult.failure(CadKernelResult.Code.NATIVE_FAILURE, "failed").requireValue();
    }

    @Test
    public void polygonProfileDefensivelyCopiesInput() {
        double[] source = new double[]{0,0,0, 100,0,0, 100,50,0, 0,50,0};
        CadProfile profile = CadProfile.polygon(source);
        source[0] = 999;

        assertEquals(0.0, profile.nativeDataCopy()[0], 0.0);
        assertEquals(CadProfile.Type.POLYGON, profile.type());
    }

    @Test(expected = IllegalArgumentException.class)
    public void polygonRejectsIncompleteGeometry() {
        CadProfile.polygon(new double[]{0,0,0, 1,0,0});
    }

    @Test
    public void vectorRejectsNanAsUsableDirection() {
        CadVector3 vector = new CadVector3(Double.NaN, 0.0, 1.0);
        assertFalse(vector.isFinite());
        assertFalse(vector.isNonZero());
    }

    @Test
    public void adapterRejectsBadArgumentsBeforeNativeBackend() {
        CadKernel kernel = new OcctCadKernelAdapter();

        CadKernelResult<CadBodyRef> badBox = kernel.createBox(-1.0, 100.0, 100.0);
        assertFalse(badBox.isSuccess());
        assertEquals(CadKernelResult.Code.INVALID_ARGUMENT, badBox.code());

        CadKernelResult<CadBodyRef> badPattern = kernel.linearPattern(
                null, new CadVector3(10.0, 0.0, 0.0), 3);
        assertFalse(badPattern.isSuccess());
        assertEquals(CadKernelResult.Code.INVALID_ARGUMENT, badPattern.code());
    }

    @Test
    public void opaqueBodyDoesNotExposeNumericHandleInText() {
        CadBodyRef body = CadBodyRef.fromNative(12345L);
        assertTrue(body.isValid());
        assertFalse(body.toString().contains("12345"));
    }
}

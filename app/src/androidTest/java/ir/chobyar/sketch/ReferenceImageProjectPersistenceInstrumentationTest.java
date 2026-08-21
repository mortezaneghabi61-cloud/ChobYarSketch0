package ir.chobyar.sketch;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Field;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;

@RunWith(AndroidJUnit4.class)
public class ReferenceImageProjectPersistenceInstrumentationTest {

    private static <T> T onMain(Callable<T> callable)throws Exception{
        AtomicReference<T> result=new AtomicReference<>();AtomicReference<Throwable> error=new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{try{result.set(callable.call());}catch(Throwable t){error.set(t);}});
        Throwable t=error.get();if(t instanceof Exception)throw (Exception)t;if(t instanceof Error)throw (Error)t;if(t!=null)throw new RuntimeException(t);return result.get();
    }

    @Test public void imageOnlyProjectRoundTripsPixelsCalibrationPlaneAndCamera() throws Exception {
        Object[] result=onMain(()->{
            Context context=ApplicationProvider.getApplicationContext();
            Shapr3DGuideCadCanvasView source=new Shapr3DGuideCadCanvasView(context);
            source.createOffsetSketchSpace(12.5f,"Reference Plane");
            Bitmap bitmap=Bitmap.createBitmap(4,2,Bitmap.Config.ARGB_8888);
            bitmap.setPixel(0,0,Color.RED);bitmap.setPixel(1,0,Color.GREEN);bitmap.setPixel(2,0,Color.BLUE);bitmap.setPixel(3,0,Color.WHITE);
            bitmap.setPixel(0,1,Color.BLACK);bitmap.setPixel(1,1,Color.YELLOW);bitmap.setPixel(2,1,Color.CYAN);bitmap.setPixel(3,1,Color.MAGENTA);
            source.setReferenceImage(bitmap,"Calibrated board");
            Object sourceImage=referenceImage(source);
            setFloat(sourceImage,"widthMm",246.8f);setFloat(sourceImage,"centerU",17.25f);setFloat(sourceImage,"centerV",-9.5f);
            setFloat(sourceImage,"rotationDeg",23.75f);setFloat(sourceImage,"opacity",0.42f);setBoolean(sourceImage,"visible",false);
            source.setStandardView("RIGHT");

            String saved=CadProjectPersistenceController.encode(source);
            CadProjectDocument.Decoded envelope=CadProjectDocument.decode(saved);
            assertEquals(3,envelope.schemaVersion);assertEquals(CadProjectDocument.SCOPE_WORKSPACE_V3,envelope.scope);
            assertTrue(envelope.hasExactModel());assertTrue(envelope.hasReferenceImage());

            Shapr3DGuideCadCanvasView restored=new Shapr3DGuideCadCanvasView(context);
            String status=CadProjectPersistenceController.restore(restored,saved);
            assertTrue(status.contains("Reference Image restored"));assertTrue(restored.hasReferenceImage());
            return new Object[]{restored,referenceImage(restored)};
        });

        Shapr3DGuideCadCanvasView restored=(Shapr3DGuideCadCanvasView)result[0];Object image=result[1];
        assertEquals("Calibrated board",objectField(image,"name").toString());
        assertEquals(246.8f,floatField(image,"widthMm"),0.0001f);assertEquals(17.25f,floatField(image,"centerU"),0.0001f);
        assertEquals(-9.5f,floatField(image,"centerV"),0.0001f);assertEquals(23.75f,floatField(image,"rotationDeg"),0.0001f);
        assertEquals(0.42f,floatField(image,"opacity"),0.0001f);assertFalse(booleanField(image,"visible"));
        Geometry3D.Plane3D plane=(Geometry3D.Plane3D)objectField(image,"plane");assertTrue(plane.label.contains("12.5"));
        Bitmap bitmap=(Bitmap)objectField(image,"bitmap");assertEquals(4,bitmap.getWidth());assertEquals(2,bitmap.getHeight());
        assertEquals(Color.RED,bitmap.getPixel(0,0));assertEquals(Color.CYAN,bitmap.getPixel(1,1));assertEquals(Color.MAGENTA,bitmap.getPixel(3,1));
        SpatialCadCanvasView.GpuCameraState camera=restored.gpuCameraState();assertEquals(90f,camera.yaw,0.0001f);assertEquals(90f,camera.pitch,0.0001f);
    }

    @Test public void exactModelExportAcceptsReferenceImageBecauseWorkspaceOwnsItsPayload() throws Exception {
        String[] encoded=onMain(()->{
            Context context=ApplicationProvider.getApplicationContext();Shapr3DGuideCadCanvasView cad=new Shapr3DGuideCadCanvasView(context);
            cad.setReferenceImage(Bitmap.createBitmap(8,4,Bitmap.Config.ARGB_8888),"Reference");
            return new String[]{ExactModelProjectAdapter.exportModel(cad),CadProjectPersistenceController.encode(cad)};
        });
        ExactModelProjectState.Decoded model=ExactModelProjectState.decode(encoded[0]);assertEquals(0,model.features.length());
        CadProjectDocument.Decoded project=CadProjectDocument.decode(encoded[1]);assertTrue(project.hasExactModel());assertTrue(project.hasReferenceImage());
    }

    @Test public void malformedReferencePayloadFailsBeforeLiveWorkspaceMutation() throws Exception {
        onMain(()->{
            Context context=ApplicationProvider.getApplicationContext();Shapr3DGuideCadCanvasView cad=new Shapr3DGuideCadCanvasView(context);
            cad.setReferenceImage(Bitmap.createBitmap(6,3,Bitmap.Config.ARGB_8888),"Keep me");Object before=referenceImage(cad);
            JSONObject project=new JSONObject(CadProjectPersistenceController.encode(cad));JSONObject reference=project.getJSONObject("referenceImage");
            reference.put("widthPx",reference.getInt("widthPx")+1);
            try{CadProjectPersistenceController.restore(cad,project.toString());fail("dimension mismatch must be rejected");}
            catch(IllegalArgumentException expected){assertTrue(expected.getMessage().contains("dimensions"));}
            assertTrue(cad.hasReferenceImage());assertSame(before,referenceImage(cad));assertEquals("Keep me",objectField(referenceImage(cad),"name").toString());
            return null;
        });
    }

    @Test public void clearAllAndLegacyOpenCannotLeakStaleReferenceImage() throws Exception {
        onMain(()->{
            Context context=ApplicationProvider.getApplicationContext();Shapr3DGuideCadCanvasView plain=new Shapr3DGuideCadCanvasView(context);
            String legacy=CadProjectDocument.encodeSketch(plain.exportSketchProjectState());
            Shapr3DGuideCadCanvasView cad=new Shapr3DGuideCadCanvasView(context);cad.setReferenceImage(Bitmap.createBitmap(5,5,Bitmap.Config.ARGB_8888),"Old");
            assertTrue(cad.hasReferenceImage());CadProjectPersistenceController.restore(cad,legacy);assertFalse(cad.hasReferenceImage());
            cad.setReferenceImage(Bitmap.createBitmap(5,5,Bitmap.Config.ARGB_8888),"Again");cad.clearAll();assertFalse(cad.hasReferenceImage());
            return null;
        });
    }

    @Test public void legacyModelV2EnvelopeRemainsReadableAfterWorkspaceV3Upgrade() throws Exception {
        String raw=onMain(()->{
            Context context=ApplicationProvider.getApplicationContext();Shapr3DGuideCadCanvasView cad=new Shapr3DGuideCadCanvasView(context);
            String sketch=cad.exportSketchProjectState();String model=ExactModelProjectAdapter.exportModel(cad);return CadProjectDocument.encodeModel(sketch,model);
        });
        CadProjectDocument.Decoded decoded=CadProjectDocument.decode(raw);assertEquals(2,decoded.schemaVersion);
        assertEquals(CadProjectDocument.SCOPE_MODEL_V2,decoded.scope);assertTrue(decoded.hasExactModel());assertFalse(decoded.hasReferenceImage());
    }

    private static Object referenceImage(SpatialCadCanvasView canvas)throws Exception{Field f=SpatialCadCanvasView.class.getDeclaredField("referenceImage");f.setAccessible(true);return f.get(canvas);}
    private static Object objectField(Object target,String name)throws Exception{Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(target);}
    private static float floatField(Object target,String name)throws Exception{Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);return f.getFloat(target);}
    private static boolean booleanField(Object target,String name)throws Exception{Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);return f.getBoolean(target);}
    private static void setFloat(Object target,String name,float value)throws Exception{Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);f.setFloat(target,value);}
    private static void setBoolean(Object target,String name,boolean value)throws Exception{Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);f.setBoolean(target,value);}
}

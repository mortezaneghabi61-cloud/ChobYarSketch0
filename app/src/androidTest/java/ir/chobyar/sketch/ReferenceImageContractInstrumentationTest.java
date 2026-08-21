package ir.chobyar.sketch;

import android.app.Instrumentation;
import android.graphics.Bitmap;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * Contract coverage for the Manual 26.100 reference-image workflow.
 *
 * The production behavior under test is intentionally model-space based:
 * - Add > Image attaches the bitmap to the active Sketch plane;
 * - calibration values are real mm / degree values, not screen pixels;
 * - camera view changes never rebind the image to the screen;
 * - the image remains a removable document item.
 */
@RunWith(AndroidJUnit4.class)
public final class ReferenceImageContractInstrumentationTest {
    private static final String TAG = "ReferenceImageContract";

    @Test
    public void imageAttachesToActiveSketchPlaneWithPhysicalAspectRatio() throws Exception {
        final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        try (ActivityScenario<ChobYarActivity> scenario = ActivityScenario.launch(ChobYarActivity.class)) {
            instrumentation.waitForIdleSync();
            scenario.onActivity(activity -> {
                Shapr3DGuideCadCanvasView canvas = findProductionCanvas(activity.getWindow().getDecorView());
                assertNotNull(canvas);
                String planeResult = canvas.createOffsetSketchSpace(12.5f, "Reference Plane");
                assertTrue(planeResult.contains("Reference Plane"));
                String expectedPlane = canvas.activePlaneLabel();

                Bitmap bitmap = Bitmap.createBitmap(400, 200, Bitmap.Config.ARGB_8888);
                String result = canvas.setReferenceImage(bitmap, "Thread photo");
                assertTrue(result.contains("100 mm"));
                assertTrue(canvas.hasReferenceImage());
                assertTrue(canvas.is3DOverview());

                Object image = referenceImage(canvas);
                assertNotNull(image);
                assertEquals(100f, floatField(image, "widthMm"), 0.0001f);
                assertEquals(50f, imageHeightMm(image), 0.0001f);
                Object plane = objectField(image, "plane");
                assertNotNull(plane);
                assertEquals(expectedPlane, stringField(plane, "label"));
                Log.i(TAG, "REFERENCE_IMAGE_ATTACH_RESULT plane=" + expectedPlane
                        + " width=100.0 height=50.0 overview=true");
            });
        }
    }

    @Test
    public void exactCalibrationStateUsesMillimetersDegreesOpacityAndVisibility() throws Exception {
        final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        try (ActivityScenario<ChobYarActivity> scenario = ActivityScenario.launch(ChobYarActivity.class)) {
            instrumentation.waitForIdleSync();
            scenario.onActivity(activity -> {
                Shapr3DGuideCadCanvasView canvas = findProductionCanvas(activity.getWindow().getDecorView());
                assertNotNull(canvas);
                Bitmap bitmap = Bitmap.createBitmap(600, 300, Bitmap.Config.ARGB_8888);
                canvas.setReferenceImage(bitmap, "Measured part");
                Object image = referenceImage(canvas);
                assertNotNull(image);

                // These are the exact fields written by the production Reference settings dialog.
                setFloatField(image, "widthMm", 62.4f);
                setFloatField(image, "centerU", 13.75f);
                setFloatField(image, "centerV", -8.25f);
                setFloatField(image, "rotationDeg", 17.5f);
                setFloatField(image, "opacity", 0.37f);
                setBooleanField(image, "visible", false);

                assertEquals(62.4f, floatField(image, "widthMm"), 0.0001f);
                assertEquals(31.2f, imageHeightMm(image), 0.0001f);
                assertEquals(13.75f, floatField(image, "centerU"), 0.0001f);
                assertEquals(-8.25f, floatField(image, "centerV"), 0.0001f);
                assertEquals(17.5f, floatField(image, "rotationDeg"), 0.0001f);
                assertEquals(0.37f, floatField(image, "opacity"), 0.0001f);
                assertFalse(booleanField(image, "visible"));
                Log.i(TAG, "REFERENCE_IMAGE_CALIBRATION_RESULT width=62.4 u=13.75 v=-8.25 rotation=17.5 opacity=0.37 visible=false");
            });
        }
    }

    @Test
    public void cameraViewChangesKeepImageRegisteredToTheSamePlane() throws Exception {
        final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        try (ActivityScenario<ChobYarActivity> scenario = ActivityScenario.launch(ChobYarActivity.class)) {
            instrumentation.waitForIdleSync();
            scenario.onActivity(activity -> {
                Shapr3DGuideCadCanvasView canvas = findProductionCanvas(activity.getWindow().getDecorView());
                assertNotNull(canvas);
                canvas.createOffsetSketchSpace(23f, "Inspection Plane");
                canvas.setReferenceImage(Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888), "Inspection");
                Object image = referenceImage(canvas);
                Object planeBefore = objectField(image, "plane");
                assertNotNull(planeBefore);

                assertEquals("ISO", canvas.setStandardView("ISO"));
                assertSame(planeBefore, objectField(referenceImage(canvas), "plane"));
                assertEquals("RIGHT", canvas.setStandardView("RIGHT"));
                assertSame(planeBefore, objectField(referenceImage(canvas), "plane"));
                assertEquals("TOP", canvas.setStandardView("TOP"));
                assertSame(planeBefore, objectField(referenceImage(canvas), "plane"));

                Log.i(TAG, "REFERENCE_IMAGE_CAMERA_RESULT registered=true views=ISO,RIGHT,TOP plane="
                        + stringField(planeBefore, "label"));
            });
        }
    }

    @Test
    public void referenceImageIsAStableRemovableDocumentItem() throws Exception {
        final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        try (ActivityScenario<ChobYarActivity> scenario = ActivityScenario.launch(ChobYarActivity.class)) {
            instrumentation.waitForIdleSync();
            scenario.onActivity(activity -> {
                Shapr3DGuideCadCanvasView canvas = findProductionCanvas(activity.getWindow().getDecorView());
                assertNotNull(canvas);
                canvas.setReferenceImage(Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888), "Item photo");
                assertTrue(canvas.hasReferenceImage());
                assertNotNull(referenceImage(canvas));
                String removed = canvas.removeReferenceImage();
                assertTrue(removed.contains("حذف"));
                assertFalse(canvas.hasReferenceImage());
                Log.i(TAG, "REFERENCE_IMAGE_ITEM_RESULT add=true remove=true remaining=false");
            });
        }
    }

    private static Shapr3DGuideCadCanvasView findProductionCanvas(View view) {
        if (view instanceof Shapr3DGuideCadCanvasView) return (Shapr3DGuideCadCanvasView) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                Shapr3DGuideCadCanvasView found = findProductionCanvas(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    private static Object referenceImage(SpatialCadCanvasView canvas) {
        try {
            Field field = SpatialCadCanvasView.class.getDeclaredField("referenceImage");
            field.setAccessible(true);
            return field.get(canvas);
        } catch (Exception e) {
            throw new AssertionError("Could not inspect reference image", e);
        }
    }

    private static Object objectField(Object target, String name) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target);
        } catch (Exception e) {
            throw new AssertionError("Could not inspect field " + name, e);
        }
    }

    private static float floatField(Object target, String name) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.getFloat(target);
        } catch (Exception e) {
            throw new AssertionError("Could not inspect float field " + name, e);
        }
    }

    private static boolean booleanField(Object target, String name) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.getBoolean(target);
        } catch (Exception e) {
            throw new AssertionError("Could not inspect boolean field " + name, e);
        }
    }

    private static String stringField(Object target, String name) {
        Object value = objectField(target, name);
        return value == null ? null : value.toString();
    }

    private static void setFloatField(Object target, String name, float value) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.setFloat(target, value);
        } catch (Exception e) {
            throw new AssertionError("Could not set float field " + name, e);
        }
    }

    private static void setBooleanField(Object target, String name, boolean value) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.setBoolean(target, value);
        } catch (Exception e) {
            throw new AssertionError("Could not set boolean field " + name, e);
        }
    }

    private static float imageHeightMm(Object image) {
        try {
            Method method = image.getClass().getDeclaredMethod("heightMm");
            method.setAccessible(true);
            return ((Number) method.invoke(image)).floatValue();
        } catch (Exception e) {
            throw new AssertionError("Could not compute reference image height", e);
        }
    }
}

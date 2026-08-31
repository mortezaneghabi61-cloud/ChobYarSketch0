package ir.chobyar.sketch;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;

import ir.chobyar.sketch.core.SketchConstraint;
import ir.chobyar.sketch.core.SketchEntity;
import ir.chobyar.sketch.core.SketchGeometry;

/** K3.9 regression fence for solver-aware transforms with model-owned point FIXED. */
@RunWith(AndroidJUnit4.class)
public final class K39PointFixedTransformInstrumentationTest {

    @Test public void lineEndpointStaysFixedAcrossRotateScaleMirrorAndHistory() throws Exception {
        onMain(() -> {
            K33MirroredCadCanvasView cad = cad();
            cad.executeCommand("LINE 10 10 40 30");
            String id = cad.selected.stableId();
            cad.setTool(CadCanvasView.TOOL_SELECT);
            lockPoint(cad, id, 0, MotionEvent.TOOL_TYPE_FINGER, InputDevice.SOURCE_TOUCHSCREEN);

            SketchGeometry.Line before = lineFor(cad, id);
            String rotate = cad.rotateSelected(90f);
            assertFalse("Rotate must be solver-aware, not fail-closed: " + rotate, rotate.contains("prevents"));
            cad.requireSketchMirrorParity();
            SketchGeometry.Line rotated = lineFor(cad, id);
            assertPointEquals(before.a, rotated.a);
            assertTrue("Free endpoint must rotate around the locked endpoint", pointChanged(before.b, rotated.b));

            double rotatedLength = rotated.lengthMm();
            String scale = cad.scaleSelected(1.5f);
            assertFalse("Scale must be solver-aware, not fail-closed: " + scale, scale.contains("prevents"));
            cad.requireSketchMirrorParity();
            SketchGeometry.Line scaled = lineFor(cad, id);
            assertPointEquals(before.a, scaled.a);
            assertEquals(rotatedLength * 1.5, scaled.lengthMm(), 1.0e-5);

            SketchGeometry.Point beforeMirrorFree = scaled.b;
            String mirror = cad.mirrorSelected(true, 0f);
            assertFalse("Mirror must be solver-aware, not fail-closed: " + mirror, mirror.contains("prevents"));
            cad.requireSketchMirrorParity();
            SketchGeometry.Line mirrored = lineFor(cad, id);
            assertPointEquals(before.a, mirrored.a);
            assertTrue("Mirror must preserve the lock while changing the free endpoint",
                    pointChanged(beforeMirrorFree, mirrored.b));
            assertPointFixed(cad, id, 0);
            assertEquals(0, cad.legacySelectionLockTruthCount());

            SketchGeometry.Line beforeUndo = mirrored;
            cad.undo();
            SketchGeometry.Line afterUndo = lineFor(cad, id);
            assertPointEquals(before.a, afterUndo.a);
            assertTrue(pointChanged(beforeUndo.b, afterUndo.b));
            assertPointFixed(cad, id, 0);
            assertTrue(cad.redoSketch());
            SketchGeometry.Line afterRedo = lineFor(cad, id);
            assertPointEquals(before.a, afterRedo.a);
            assertPointEquals(beforeUndo.b, afterRedo.b);
            assertPointFixed(cad, id, 0);
            return true;
        });
    }

    @Test public void circleCenterStaysFixedWhileScaleChangesRadius() throws Exception {
        onMain(() -> {
            K33MirroredCadCanvasView cad = cad();
            cad.executeCommand("CIRCLE 20 25 12");
            String id = cad.selected.stableId();
            cad.setTool(CadCanvasView.TOOL_SELECT);
            lockPoint(cad, id, 0, MotionEvent.TOOL_TYPE_STYLUS, InputDevice.SOURCE_STYLUS);
            SketchGeometry.Circle before = circleFor(cad, id);

            String scale = cad.scaleSelected(1.75f);
            assertFalse(scale.contains("prevents"));
            cad.requireSketchMirrorParity();
            SketchGeometry.Circle after = circleFor(cad, id);
            assertPointEquals(before.center, after.center);
            assertEquals(before.radiusMm * 1.75, after.radiusMm, 1.0e-6);
            assertPointFixed(cad, id, 0);
            assertEquals(0, cad.legacySelectionLockTruthCount());
            return true;
        });
    }

    @Test public void arcCenterStaysFixedWhileRotateScaleMirrorUseFreeDof() throws Exception {
        onMain(() -> {
            K33MirroredCadCanvasView cad = cad();
            cad.executeCommand("ARC 30 35 10 20 110");
            String id = cad.selected.stableId();
            cad.setTool(CadCanvasView.TOOL_SELECT);
            lockPoint(cad, id, 0, MotionEvent.TOOL_TYPE_STYLUS, InputDevice.SOURCE_STYLUS);
            SketchGeometry.Arc before = arcFor(cad, id);

            String rotate = cad.rotateSelected(35f);
            assertFalse(rotate.contains("prevents"));
            SketchGeometry.Arc rotated = arcFor(cad, id);
            assertPointEquals(before.center, rotated.center);
            assertAngleEquals(before.startDeg + 35.0, rotated.startDeg);

            String scale = cad.scaleSelected(2f);
            assertFalse(scale.contains("prevents"));
            SketchGeometry.Arc scaled = arcFor(cad, id);
            assertPointEquals(before.center, scaled.center);
            assertEquals(before.radiusMm * 2.0, scaled.radiusMm, 1.0e-6);

            double sweepBeforeMirror = scaled.sweepDeg;
            String mirror = cad.mirrorSelected(true, 0f);
            assertFalse(mirror.contains("prevents"));
            cad.requireSketchMirrorParity();
            SketchGeometry.Arc mirrored = arcFor(cad, id);
            assertPointEquals(before.center, mirrored.center);
            assertEquals(-sweepBeforeMirror, mirrored.sweepDeg, 1.0e-5);
            assertPointFixed(cad, id, 0);
            return true;
        });
    }

    @Test public void pointFixedDoesNotFakeFullyDefinedAndArcEndpointsStayFailClosed() throws Exception {
        onMain(() -> {
            K33MirroredCadCanvasView cad = cad();
            cad.executeCommand("LINE 0 0 50 15");
            String lineId = cad.selected.stableId();
            cad.setTool(CadCanvasView.TOOL_SELECT);
            lockPoint(cad, lineId, 0, MotionEvent.TOOL_TYPE_FINGER, InputDevice.SOURCE_TOUCHSCREEN);
            assertFalse("One locked endpoint must not make the whole line Fully-defined",
                    cad.selectedInfo().contains("Fully-defined"));

            cad.executeCommand("ARC 60 40 12 10 100");
            String arcId = cad.selected.stableId();
            cad.setTool(CadCanvasView.TOOL_SELECT);
            SketchGeometry.Arc arc = arcFor(cad, arcId);
            double start = Math.toRadians(arc.startDeg);
            tap(cad,
                    screenX(cad, arc.center.xMm + Math.cos(start) * arc.radiusMm),
                    screenY(cad, arc.center.yMm + Math.sin(start) * arc.radiusMm),
                    MotionEvent.TOOL_TYPE_FINGER, InputDevice.SOURCE_TOUCHSCREEN);
            assertEquals("Arc start/end Point-FIXED remains fail-closed until joint-anchor solving exists",
                    -1, cad.pointLockTargetPointIndex());
            assertFalse(hasPointFixed(cad, arcId, 1));
            assertFalse(hasPointFixed(cad, arcId, 2));
            return true;
        });
    }

    private static void lockPoint(K33MirroredCadCanvasView cad, String id, int pointIndex,
                                  int toolType, int source) {
        SketchEntity entity = entityFor(cad, id);
        SketchGeometry.Point point;
        if (entity instanceof SketchGeometry.Line) {
            SketchGeometry.Line line = (SketchGeometry.Line) entity;
            point = pointIndex == 0 ? line.a : line.b;
        } else if (entity instanceof SketchGeometry.Circle) {
            point = ((SketchGeometry.Circle) entity).center;
        } else if (entity instanceof SketchGeometry.Arc) {
            point = ((SketchGeometry.Arc) entity).center;
        } else throw new AssertionError("Unsupported lock test geometry");
        tap(cad, screenX(cad, point.xMm), screenY(cad, point.yMm), toolType, source);
        assertEquals(id, cad.pointLockTargetEntityId());
        assertEquals(pointIndex, cad.pointLockTargetPointIndex());
        assertEquals("Point locked", cad.toggleSelectedLock());
        assertPointFixed(cad, id, pointIndex);
    }

    private static K33MirroredCadCanvasView cad() {
        Context context = ApplicationProvider.getApplicationContext();
        K33MirroredCadCanvasView cad = new K33MirroredCadCanvasView(context);
        cad.layout(0, 0, 1400, 1000);
        return cad;
    }

    private static float screenX(K33MirroredCadCanvasView cad, double xMm) {
        return (float) (xMm * 3.0 * cad.viewScale + cad.offsetX);
    }

    private static float screenY(K33MirroredCadCanvasView cad, double yMm) {
        return (float) (yMm * 3.0 * cad.viewScale + cad.offsetY);
    }

    private static void tap(K33MirroredCadCanvasView cad, float x, float y, int toolType, int source) {
        long down = SystemClock.uptimeMillis();
        send(cad, one(down, down, MotionEvent.ACTION_DOWN, x, y, toolType, source));
        send(cad, one(down, down + 16L, MotionEvent.ACTION_UP, x, y, toolType, source));
    }

    private static void send(K33MirroredCadCanvasView cad, MotionEvent event) {
        try { assertTrue(cad.onTouchEvent(event)); }
        finally { event.recycle(); }
    }

    private static MotionEvent one(long down, long time, int action, float x, float y,
                                   int toolType, int source) {
        MotionEvent.PointerProperties property = new MotionEvent.PointerProperties();
        property.id = 0;
        property.toolType = toolType;
        MotionEvent.PointerCoords coords = new MotionEvent.PointerCoords();
        coords.x = x; coords.y = y; coords.pressure = 1f; coords.size = 1f;
        coords.touchMajor = 10f; coords.touchMinor = 10f;
        return MotionEvent.obtain(down, time, action, 1,
                new MotionEvent.PointerProperties[]{property},
                new MotionEvent.PointerCoords[]{coords}, 0, 0, 1f, 1f, 0, 0, source, 0);
    }

    private static void assertPointFixed(K33MirroredCadCanvasView cad, String id, int pointIndex) {
        assertTrue(hasPointFixed(cad, id, pointIndex));
        assertEquals(0, cad.legacySelectionLockTruthCount());
    }

    private static boolean hasPointFixed(K33MirroredCadCanvasView cad, String id, int pointIndex) {
        for (SketchConstraint constraint : cad.sketchConstraints()) {
            if (constraint.kind == SketchConstraint.Kind.FIXED
                    && id.equals(constraint.primaryEntityId)
                    && constraint.fixesPoint()
                    && constraint.primaryPointIndex == pointIndex) return true;
        }
        return false;
    }

    private static SketchGeometry.Line lineFor(K33MirroredCadCanvasView cad, String id) {
        return (SketchGeometry.Line) entityFor(cad, id);
    }

    private static SketchGeometry.Circle circleFor(K33MirroredCadCanvasView cad, String id) {
        return (SketchGeometry.Circle) entityFor(cad, id);
    }

    private static SketchGeometry.Arc arcFor(K33MirroredCadCanvasView cad, String id) {
        return (SketchGeometry.Arc) entityFor(cad, id);
    }

    private static SketchEntity entityFor(K33MirroredCadCanvasView cad, String id) {
        for (SketchEntity entity : cad.sketchMirrorEntities()) if (id.equals(entity.id())) return entity;
        throw new AssertionError("Model entity not found: " + id);
    }

    private static void assertPointEquals(SketchGeometry.Point expected, SketchGeometry.Point actual) {
        assertEquals(expected.xMm, actual.xMm, 1.0e-7);
        assertEquals(expected.yMm, actual.yMm, 1.0e-7);
    }

    private static boolean pointChanged(SketchGeometry.Point before, SketchGeometry.Point after) {
        return Math.abs(before.xMm - after.xMm) > 1.0e-6
                || Math.abs(before.yMm - after.yMm) > 1.0e-6;
    }

    private static void assertAngleEquals(double expected, double actual) {
        double e = normalize(expected), a = normalize(actual);
        assertEquals(e, a, 1.0e-5);
    }

    private static double normalize(double angle) {
        double v = angle % 360.0;
        return v < 0.0 ? v + 360.0 : v;
    }

    private static <T> T onMain(Callable<T> callable) throws Exception {
        FutureTask<T> task = new FutureTask<>(callable);
        new android.os.Handler(android.os.Looper.getMainLooper()).post(task);
        return task.get();
    }
}

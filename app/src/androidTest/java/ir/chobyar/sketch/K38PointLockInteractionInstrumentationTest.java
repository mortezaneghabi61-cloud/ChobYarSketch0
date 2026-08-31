package ir.chobyar.sketch;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
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

/**
 * K3.8 Interaction fence for Shapr-style point Lock/Unlock.
 *
 * Endpoint/center taps must select stable-ID point targets for finger and pen,
 * point FIXED must block mutation before legacy geometry moves, and unsupported
 * radius/arc handles must not manufacture point-lock targets.
 */
@RunWith(AndroidJUnit4.class)
public final class K38PointLockInteractionInstrumentationTest {

    @Test public void fingerLineEndpointLocksOnlyThatEndpointAndUnlocks() throws Exception {
        onMain(() -> {
            K33MirroredCadCanvasView cad = cad();
            cad.executeCommand("LINE 0 0 40 0");
            String id = cad.selected.stableId();
            cad.setTool(CadCanvasView.TOOL_SELECT);

            tap(cad, screenX(cad, 0), screenY(cad, 0),
                    MotionEvent.TOOL_TYPE_FINGER, InputDevice.SOURCE_TOUCHSCREEN);
            assertEquals(id, cad.pointLockTargetEntityId());
            assertEquals(0, cad.pointLockTargetPointIndex());
            assertFalse(cad.pointLockTargetLocked());

            assertEquals("Point locked", cad.toggleSelectedLock());
            assertTrue(cad.pointLockTargetLocked());
            assertPointFixed(cad, id, 0);
            assertEquals(0, cad.legacySelectionLockTruthCount());
            SketchGeometry.Line locked = lineFor(cad, id);

            drag(cad, screenX(cad, locked.a.xMm), screenY(cad, locked.a.yMm),
                    screenX(cad, locked.a.xMm) + 90f, screenY(cad, locked.a.yMm) + 55f,
                    MotionEvent.TOOL_TYPE_FINGER, InputDevice.SOURCE_TOUCHSCREEN);
            SketchGeometry.Line afterLockedDrag = lineFor(cad, id);
            assertPointEquals(locked.a, afterLockedDrag.a);
            assertPointEquals(locked.b, afterLockedDrag.b);
            assertPointFixed(cad, id, 0);

            drag(cad, screenX(cad, afterLockedDrag.b.xMm), screenY(cad, afterLockedDrag.b.yMm),
                    screenX(cad, afterLockedDrag.b.xMm) + 75f, screenY(cad, afterLockedDrag.b.yMm) + 45f,
                    MotionEvent.TOOL_TYPE_FINGER, InputDevice.SOURCE_TOUCHSCREEN);
            cad.requireSketchMirrorParity();
            SketchGeometry.Line afterFreeEndpointDrag = lineFor(cad, id);
            assertPointEquals(locked.a, afterFreeEndpointDrag.a);
            assertTrue("The other line endpoint must remain editable",
                    pointChanged(locked.b, afterFreeEndpointDrag.b));
            assertPointFixed(cad, id, 0);
            assertEquals(0, cad.legacySelectionLockTruthCount());

            tap(cad, screenX(cad, afterFreeEndpointDrag.a.xMm), screenY(cad, afterFreeEndpointDrag.a.yMm),
                    MotionEvent.TOOL_TYPE_FINGER, InputDevice.SOURCE_TOUCHSCREEN);
            assertTrue(cad.pointLockTargetLocked());
            assertEquals("Point unlocked", cad.toggleSelectedLock());
            assertFalse(hasPointFixed(cad, id, 0));

            SketchGeometry.Line beforeUnlockedDrag = lineFor(cad, id);
            drag(cad, screenX(cad, beforeUnlockedDrag.a.xMm), screenY(cad, beforeUnlockedDrag.a.yMm),
                    screenX(cad, beforeUnlockedDrag.a.xMm) + 65f, screenY(cad, beforeUnlockedDrag.a.yMm) + 30f,
                    MotionEvent.TOOL_TYPE_FINGER, InputDevice.SOURCE_TOUCHSCREEN);
            cad.requireSketchMirrorParity();
            assertTrue("Unlocked endpoint must move again",
                    pointChanged(beforeUnlockedDrag.a, lineFor(cad, id).a));
            return true;
        });
    }

    @Test public void stylusEndpointUsesSamePointAuthorityAndBlocksDrag() throws Exception {
        onMain(() -> {
            K33MirroredCadCanvasView cad = cad();
            cad.executeCommand("LINE 10 10 60 20");
            String id = cad.selected.stableId();
            cad.setTool(CadCanvasView.TOOL_SELECT);
            SketchGeometry.Line before = lineFor(cad, id);

            tap(cad, screenX(cad, before.b.xMm), screenY(cad, before.b.yMm),
                    MotionEvent.TOOL_TYPE_STYLUS, InputDevice.SOURCE_STYLUS);
            assertEquals(id, cad.pointLockTargetEntityId());
            assertEquals(1, cad.pointLockTargetPointIndex());
            assertEquals("Point locked", cad.toggleSelectedLock());
            assertPointFixed(cad, id, 1);

            drag(cad, screenX(cad, before.b.xMm), screenY(cad, before.b.yMm),
                    screenX(cad, before.b.xMm) + 80f, screenY(cad, before.b.yMm) - 60f,
                    MotionEvent.TOOL_TYPE_STYLUS, InputDevice.SOURCE_STYLUS);
            cad.requireSketchMirrorParity();
            SketchGeometry.Line after = lineFor(cad, id);
            assertPointEquals(before.a, after.a);
            assertPointEquals(before.b, after.b);
            assertTrue(cad.pointLockTargetLocked());
            assertEquals(0, cad.legacySelectionLockTruthCount());
            return true;
        });
    }

    @Test public void circleCenterLocksWhileRadiusHandleRemainsEditableAndUnsupported() throws Exception {
        onMain(() -> {
            K33MirroredCadCanvasView cad = cad();
            cad.executeCommand("CIRCLE 20 20 10");
            String id = cad.selected.stableId();
            cad.setTool(CadCanvasView.TOOL_SELECT);
            SketchGeometry.Circle before = circleFor(cad, id);

            tap(cad, screenX(cad, before.center.xMm), screenY(cad, before.center.yMm),
                    MotionEvent.TOOL_TYPE_FINGER, InputDevice.SOURCE_TOUCHSCREEN);
            assertEquals(0, cad.pointLockTargetPointIndex());
            assertEquals("Point locked", cad.toggleSelectedLock());
            assertPointFixed(cad, id, 0);

            drag(cad, screenX(cad, before.center.xMm), screenY(cad, before.center.yMm),
                    screenX(cad, before.center.xMm) + 70f, screenY(cad, before.center.yMm) + 50f,
                    MotionEvent.TOOL_TYPE_FINGER, InputDevice.SOURCE_TOUCHSCREEN);
            SketchGeometry.Circle afterCenterDrag = circleFor(cad, id);
            assertPointEquals(before.center, afterCenterDrag.center);
            assertEquals(before.radiusMm, afterCenterDrag.radiusMm, 1.0e-9);

            float radiusX = screenX(cad, afterCenterDrag.center.xMm + afterCenterDrag.radiusMm);
            float radiusY = screenY(cad, afterCenterDrag.center.yMm);
            drag(cad, radiusX, radiusY, radiusX + 75f, radiusY,
                    MotionEvent.TOOL_TYPE_FINGER, InputDevice.SOURCE_TOUCHSCREEN);
            cad.requireSketchMirrorParity();
            SketchGeometry.Circle afterRadiusDrag = circleFor(cad, id);
            assertPointEquals(before.center, afterRadiusDrag.center);
            assertTrue("Circle radius handle must remain editable with center FIXED",
                    Math.abs(afterRadiusDrag.radiusMm - before.radiusMm) > 1.0e-6);
            assertEquals("Radius handle must not be exposed as a point Lock target",
                    -1, cad.pointLockTargetPointIndex());
            assertPointFixed(cad, id, 0);
            assertEquals(0, cad.legacySelectionLockTruthCount());
            return true;
        });
    }

    @Test public void arcCenterIsPointLockableButStartAndEndHandlesAreNot() throws Exception {
        onMain(() -> {
            K33MirroredCadCanvasView cad = cad();
            cad.executeCommand("ARC 30 30 10 0 90");
            String id = cad.selected.stableId();
            cad.setTool(CadCanvasView.TOOL_SELECT);
            SketchGeometry.Arc before = arcFor(cad, id);

            tap(cad, screenX(cad, before.center.xMm), screenY(cad, before.center.yMm),
                    MotionEvent.TOOL_TYPE_STYLUS, InputDevice.SOURCE_STYLUS);
            assertEquals(id, cad.pointLockTargetEntityId());
            assertEquals(0, cad.pointLockTargetPointIndex());
            assertEquals("Point locked", cad.toggleSelectedLock());
            assertPointFixed(cad, id, 0);

            drag(cad, screenX(cad, before.center.xMm), screenY(cad, before.center.yMm),
                    screenX(cad, before.center.xMm) - 60f, screenY(cad, before.center.yMm) + 45f,
                    MotionEvent.TOOL_TYPE_STYLUS, InputDevice.SOURCE_STYLUS);
            cad.requireSketchMirrorParity();
            SketchGeometry.Arc afterCenterDrag = arcFor(cad, id);
            assertPointEquals(before.center, afterCenterDrag.center);
            assertEquals(before.radiusMm, afterCenterDrag.radiusMm, 1.0e-9);

            double startRad = Math.toRadians(afterCenterDrag.startDeg);
            float startX = screenX(cad, afterCenterDrag.center.xMm
                    + Math.cos(startRad) * afterCenterDrag.radiusMm);
            float startY = screenY(cad, afterCenterDrag.center.yMm
                    + Math.sin(startRad) * afterCenterDrag.radiusMm);
            tap(cad, startX, startY, MotionEvent.TOOL_TYPE_FINGER, InputDevice.SOURCE_TOUCHSCREEN);
            assertEquals("Arc start handle must fail closed for point Lock in K3.8",
                    -1, cad.pointLockTargetPointIndex());
            assertPointFixed(cad, id, 0);
            assertEquals(0, cad.legacySelectionLockTruthCount());
            return true;
        });
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

    private static void tap(K33MirroredCadCanvasView cad, float x, float y,
                            int toolType, int source) {
        long down = SystemClock.uptimeMillis();
        send(cad, one(down, down, MotionEvent.ACTION_DOWN, x, y, toolType, source));
        send(cad, one(down, down + 16L, MotionEvent.ACTION_UP, x, y, toolType, source));
    }

    private static void drag(K33MirroredCadCanvasView cad, float x0, float y0, float x1, float y1,
                             int toolType, int source) {
        long down = SystemClock.uptimeMillis();
        send(cad, one(down, down, MotionEvent.ACTION_DOWN, x0, y0, toolType, source));
        send(cad, one(down, down + 16L, MotionEvent.ACTION_MOVE,
                x0 + (x1 - x0) * 0.5f, y0 + (y1 - y0) * 0.5f, toolType, source));
        send(cad, one(down, down + 32L, MotionEvent.ACTION_MOVE, x1, y1, toolType, source));
        send(cad, one(down, down + 48L, MotionEvent.ACTION_UP, x1, y1, toolType, source));
    }

    private static void send(K33MirroredCadCanvasView cad, MotionEvent event) {
        try {
            assertTrue("Production canvas must handle point interaction event", cad.onTouchEvent(event));
        } finally {
            event.recycle();
        }
    }

    private static MotionEvent one(long down, long time, int action, float x, float y,
                                   int toolType, int source) {
        MotionEvent.PointerProperties property = new MotionEvent.PointerProperties();
        property.id = 0;
        property.toolType = toolType;
        MotionEvent.PointerCoords coords = new MotionEvent.PointerCoords();
        coords.x = x;
        coords.y = y;
        coords.pressure = 1f;
        coords.size = 1f;
        coords.touchMajor = 10f;
        coords.touchMinor = 10f;
        return MotionEvent.obtain(down, time, action, 1,
                new MotionEvent.PointerProperties[]{property},
                new MotionEvent.PointerCoords[]{coords},
                0, 0, 1f, 1f, 0, 0, source, 0);
    }

    private static void assertPointFixed(K33MirroredCadCanvasView cad, String id, int pointIndex) {
        assertTrue("Expected point FIXED for " + id + ":" + pointIndex,
                hasPointFixed(cad, id, pointIndex));
        for (SketchConstraint constraint : cad.sketchConstraints()) {
            if (constraint.kind == SketchConstraint.Kind.FIXED
                    && id.equals(constraint.primaryEntityId)
                    && constraint.primaryPointIndex == pointIndex) {
                assertTrue(constraint.fixesPoint());
                assertFalse(constraint.fixesWholeEntity());
                return;
            }
        }
        throw new AssertionError("Point FIXED was not found");
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
        SketchEntity entity = entityFor(cad, id);
        assertTrue(entity instanceof SketchGeometry.Line);
        return (SketchGeometry.Line) entity;
    }

    private static SketchGeometry.Circle circleFor(K33MirroredCadCanvasView cad, String id) {
        SketchEntity entity = entityFor(cad, id);
        assertTrue(entity instanceof SketchGeometry.Circle);
        return (SketchGeometry.Circle) entity;
    }

    private static SketchGeometry.Arc arcFor(K33MirroredCadCanvasView cad, String id) {
        SketchEntity entity = entityFor(cad, id);
        assertTrue(entity instanceof SketchGeometry.Arc);
        return (SketchGeometry.Arc) entity;
    }

    private static SketchEntity entityFor(K33MirroredCadCanvasView cad, String id) {
        for (SketchEntity entity : cad.sketchMirrorEntities()) {
            if (id.equals(entity.id())) return entity;
        }
        throw new AssertionError("Model entity was not found for " + id);
    }

    private static void assertPointEquals(SketchGeometry.Point expected, SketchGeometry.Point actual) {
        assertNotNull(actual);
        assertEquals(expected.xMm, actual.xMm, 1.0e-9);
        assertEquals(expected.yMm, actual.yMm, 1.0e-9);
    }

    private static boolean pointChanged(SketchGeometry.Point before, SketchGeometry.Point after) {
        return Math.abs(before.xMm - after.xMm) > 1.0e-6
                || Math.abs(before.yMm - after.yMm) > 1.0e-6;
    }

    private static <T> T onMain(Callable<T> callable) throws Exception {
        FutureTask<T> task = new FutureTask<>(callable);
        new android.os.Handler(android.os.Looper.getMainLooper()).post(task);
        return task.get();
    }
}

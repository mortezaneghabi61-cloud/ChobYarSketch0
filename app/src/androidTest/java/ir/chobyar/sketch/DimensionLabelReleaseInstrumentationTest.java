package ir.chobyar.sketch;

import android.app.Instrumentation;
import android.graphics.PointF;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.IdentityHashMap;
import java.util.Map;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** Regression for exact-dimension label drag finalization at ACTION_UP. */
@RunWith(AndroidJUnit4.class)
public final class DimensionLabelReleaseInstrumentationTest {

    @Test
    public void fingerDragCommitsFinalReleasePointNotLastMovePoint() throws Exception {
        runReleaseContract(MotionEvent.TOOL_TYPE_FINGER, InputDevice.SOURCE_TOUCHSCREEN);
    }

    @Test
    public void stylusDragCommitsFinalReleasePointNotLastMovePoint() throws Exception {
        runReleaseContract(MotionEvent.TOOL_TYPE_STYLUS, InputDevice.SOURCE_STYLUS);
    }

    private static void runReleaseContract(int toolType, int source) throws Exception {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        try (ActivityScenario<ChobYarActivity> scenario = ActivityScenario.launch(ChobYarActivity.class)) {
            instrumentation.waitForIdleSync();

            final float[] start = new float[2];
            final Shapr3DGuideCadCanvasView[] holder = new Shapr3DGuideCadCanvasView[1];
            final boolean[] pressedAfterDown = new boolean[1];
            final boolean[] draggingAfterMove = new boolean[1];
            final String[] selectedIdAfterDown = new String[1];
            final String[] gestureIdAfterDown = new String[1];

            scenario.onActivity(activity -> {
                Shapr3DGuideCadCanvasView canvas = findProductionCanvas(activity.getWindow().getDecorView());
                assertNotNull("Production CAD canvas was not found", canvas);
                holder[0] = canvas;
                canvas.clearAll();
                canvas.executeCommand("RECT 0 0 40 20");
                assertNotNull("Rectangle must remain selected for exact dimension editing", canvas.selected);
                canvas.setTool(CadCanvasView.TOOL_SELECT);
                canvas.invalidate();
            });
            instrumentation.waitForIdleSync();
            SystemClock.sleep(120L);

            scenario.onActivity(activity -> {
                Shapr3DGuideCadCanvasView canvas = holder[0];
                RectF label = exactFieldRect(canvas);
                assertFalse("Exact dimension label was not drawn", label.isEmpty());
                int[] location = new int[2];
                canvas.getLocationOnScreen(location);
                start[0] = location[0] + label.centerX();
                start[1] = location[1] + label.centerY();
            });

            final float finalDx = 100f;
            final float finalDy = 70f;
            long down = SystemClock.uptimeMillis();
            send(instrumentation, one(down, now(), MotionEvent.ACTION_DOWN,
                    start[0], start[1], toolType, source));
            instrumentation.waitForIdleSync();
            scenario.onActivity(activity -> {
                Shapr3DGuideCadCanvasView canvas = holder[0];
                pressedAfterDown[0] = privateBoolean(canvas, "exactFieldPressed");
                selectedIdAfterDown[0] = stableId(canvas.selected);
                gestureIdAfterDown[0] = stableId(privateObject(canvas, "fieldGestureEntity"));
            });

            SystemClock.sleep(35L);
            send(instrumentation, one(down, now(), MotionEvent.ACTION_MOVE,
                    start[0] + 40f, start[1] + 25f, toolType, source));
            instrumentation.waitForIdleSync();
            scenario.onActivity(activity ->
                    draggingAfterMove[0] = privateBoolean(holder[0], "exactFieldDragging"));

            SystemClock.sleep(35L);
            send(instrumentation, one(down, now(), MotionEvent.ACTION_UP,
                    start[0] + finalDx, start[1] + finalDy, toolType, source));
            instrumentation.waitForIdleSync();
            SystemClock.sleep(80L);

            scenario.onActivity(activity -> {
                Shapr3DGuideCadCanvasView canvas = holder[0];
                OffsetProbe probe = dimensionOffsetProbe(canvas, canvas.selected);
                String diagnostic = "toolType=" + toolType
                        + " pressedAfterDown=" + pressedAfterDown[0]
                        + " draggingAfterMove=" + draggingAfterMove[0]
                        + " selectedIdAfterDown=" + selectedIdAfterDown[0]
                        + " gestureIdAfterDown=" + gestureIdAfterDown[0]
                        + " selectedIdAfterUp=" + stableId(canvas.selected)
                        + " mapSize=" + probe.mapSize
                        + " stableIdMatch=" + (probe.byStableId != null)
                        + " identityMatch=" + (probe.byIdentity != null);
                assertNotNull("Dimension label offset was not stored for current entity; " + diagnostic,
                        probe.byIdentity);
                PointF offset = probe.byIdentity;
                assertTrue("ACTION_UP X must be committed; actual=" + offset.x + "; " + diagnostic,
                        Math.abs(offset.x - finalDx) <= 3f);
                assertTrue("ACTION_UP Y must be committed; actual=" + offset.y + "; " + diagnostic,
                        Math.abs(offset.y - finalDy) <= 3f);
            });
        }
    }

    private static RectF exactFieldRect(Shapr3DGuideCadCanvasView canvas) {
        try {
            Field field = ShaprStyleCadCanvasView.class.getDeclaredField("exactFieldRect");
            field.setAccessible(true);
            Object value = field.get(canvas);
            return value instanceof RectF ? new RectF((RectF) value) : new RectF();
        } catch (Exception e) {
            throw new AssertionError("Could not inspect exact dimension label", e);
        }
    }

    @SuppressWarnings("unchecked")
    private static OffsetProbe dimensionOffsetProbe(Shapr3DGuideCadCanvasView canvas, Object entity) {
        try {
            Field field = ShaprStyleCadCanvasView.class.getDeclaredField("dimensionLabelOffsets");
            field.setAccessible(true);
            IdentityHashMap<Object, PointF> offsets =
                    (IdentityHashMap<Object, PointF>) field.get(canvas);
            PointF direct = offsets.get(entity);
            PointF stable = null;
            String wantedId = stableId(entity);
            for (Map.Entry<Object, PointF> entry : offsets.entrySet()) {
                if (wantedId != null && wantedId.equals(stableId(entry.getKey()))) {
                    stable = entry.getValue();
                    break;
                }
            }
            return new OffsetProbe(copy(direct), copy(stable), offsets.size());
        } catch (Exception e) {
            throw new AssertionError("Could not inspect dimension label offset", e);
        }
    }

    private static PointF copy(PointF value) {
        return value == null ? null : new PointF(value);
    }

    private static boolean privateBoolean(Object target, String name) {
        Object value = privateObject(target, name);
        return value instanceof Boolean && (Boolean) value;
    }

    private static Object privateObject(Object target, String name) {
        try {
            Field field = ShaprStyleCadCanvasView.class.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target);
        } catch (Exception e) {
            throw new AssertionError("Could not inspect " + name, e);
        }
    }

    private static String stableId(Object entity) {
        if (entity == null) return null;
        Class<?> type = entity.getClass();
        while (type != null) {
            try {
                Method method = type.getDeclaredMethod("stableId");
                method.setAccessible(true);
                Object value = method.invoke(entity);
                return value == null ? null : String.valueOf(value);
            } catch (NoSuchMethodException e) {
                type = type.getSuperclass();
            } catch (Exception e) {
                return "<stable-id-error:" + e.getClass().getSimpleName() + ">";
            }
        }
        return "<no-stable-id>";
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

    private static void send(Instrumentation instrumentation, MotionEvent event) {
        try {
            instrumentation.sendPointerSync(event);
        } finally {
            event.recycle();
        }
    }

    private static long now() {
        return SystemClock.uptimeMillis();
    }

    private static MotionEvent one(long down, long time, int action, float x, float y,
                                   int toolType, int source) {
        MotionEvent.PointerProperties properties = new MotionEvent.PointerProperties();
        properties.id = 0;
        properties.toolType = toolType;
        MotionEvent.PointerCoords coords = new MotionEvent.PointerCoords();
        coords.x = x;
        coords.y = y;
        coords.pressure = 1f;
        coords.size = 1f;
        coords.touchMajor = 12f;
        coords.touchMinor = 12f;
        return MotionEvent.obtain(down, time, action, 1,
                new MotionEvent.PointerProperties[]{properties},
                new MotionEvent.PointerCoords[]{coords},
                0, 0, 1f, 1f, 0, 0, source, 0);
    }

    private static final class OffsetProbe {
        final PointF byIdentity;
        final PointF byStableId;
        final int mapSize;

        OffsetProbe(PointF byIdentity, PointF byStableId, int mapSize) {
            this.byIdentity = byIdentity;
            this.byStableId = byStableId;
            this.mapSize = mapSize;
        }
    }
}

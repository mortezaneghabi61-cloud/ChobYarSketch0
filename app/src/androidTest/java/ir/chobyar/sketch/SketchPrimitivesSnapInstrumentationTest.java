package ir.chobyar.sketch;

import android.app.Instrumentation;
import android.graphics.PointF;
import android.graphics.RectF;
import android.os.SystemClock;
import android.util.Log;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Production-canvas regression tests for core Sketch primitives, guidepoints,
 * intersection snapping, and exact numeric dimensions.
 */
@RunWith(AndroidJUnit4.class)
public final class SketchPrimitivesSnapInstrumentationTest {

    private static final String TAG = "SketchPrimitivesSnap";
    private static final float EPS = 0.08f;
    private static final float PX_PER_MM = 3f;

    @Test
    public void lineSnapsToEndpointMidpointAndIntersection() {
        Instrumentation inst = InstrumentationRegistry.getInstrumentation();
        try (ActivityScenario<ChobYarActivity> scenario = ActivityScenario.launch(ChobYarActivity.class)) {
            inst.waitForIdleSync();
            scenario.onActivity(activity -> {
                Shapr3DGuideCadCanvasView c = canvas(activity);
                c.clearAll();
                // Horizontal reference: endpoint (100,100), midpoint (160,100).
                c.executeCommand("LINE 100 100 220 100");
                // Crossing reference: intersection with first line at (200,100).
                c.executeCommand("LINE 200 55 200 145");
                c.setTool(CadCanvasView.TOOL_LINE);
            });
            inst.waitForIdleSync();

            // Intentionally miss the reference points by 1-2 mm. Production
            // snapping must place the new geometry exactly on the guidepoints.
            stylusLine(inst, frame(scenario), 101.4f, 101.2f, 159.1f, 101.3f);
            inst.waitForIdleSync();

            scenario.onActivity(activity -> {
                Shapr3DGuideCadCanvasView c = canvas(activity);
                CadCanvasView.Entity made = c.selected;
                assertNotNull(made);
                assertEquals("خط", made.shortName());
                List<CadCanvasView.SnapPoint> q = made.snapPoints();
                assertTrue(q.size() >= 2);
                assertPoint("endpoint snap", q.get(0), 100f, 100f);
                assertPoint("midpoint snap", q.get(1), 160f, 100f);
                c.setTool(CadCanvasView.TOOL_LINE);
            });
            inst.waitForIdleSync();

            stylusLine(inst, frame(scenario), 130.5f, 70.5f, 201.3f, 101.2f);
            inst.waitForIdleSync();

            scenario.onActivity(activity -> {
                Shapr3DGuideCadCanvasView c = canvas(activity);
                CadCanvasView.Entity made = c.selected;
                assertNotNull(made);
                List<CadCanvasView.SnapPoint> q = made.snapPoints();
                assertTrue(q.size() >= 2);
                assertPoint("intersection snap", q.get(1), 200f, 100f);
                Log.i(TAG, "LINE_SNAP_RESULT endpoint=100,100 midpoint=160,100 intersection=200,100 entities=" + c.entities.size());
            });
        }
    }

    @Test
    public void circleGuidepointsAndExactDiameterWork() {
        Instrumentation inst = InstrumentationRegistry.getInstrumentation();
        try (ActivityScenario<ChobYarActivity> scenario = ActivityScenario.launch(ChobYarActivity.class)) {
            inst.waitForIdleSync();
            scenario.onActivity(activity -> {
                Shapr3DGuideCadCanvasView c = canvas(activity);
                c.clearAll();
                c.setTool(CadCanvasView.TOOL_CIRCLE);
            });
            inst.waitForIdleSync();

            stylusLine(inst, frame(scenario), 320f, 220f, 360f, 220f);
            inst.waitForIdleSync();

            scenario.onActivity(activity -> {
                Shapr3DGuideCadCanvasView c = canvas(activity);
                assertNotNull(c.selected);
                assertEquals("دایره", c.selected.shortName());
                assertEquals("قطر = 80 mm", c.applySelectedDimension("80"));
                PointF center = c.selected.center();
                assertNear("circle center x", 320f, center.x);
                assertNear("circle center y", 220f, center.y);
                float radius = farthestSnapDistance(c.selected, center);
                assertNear("numeric circle radius", 40f, radius);
                // Keep the circle and switch to Line so the next real pen gesture
                // has to snap to center and a quadrant, not merely expose labels.
                c.setTool(CadCanvasView.TOOL_LINE);
            });
            inst.waitForIdleSync();

            stylusLine(inst, frame(scenario), 321.5f, 221.2f, 359.1f, 221.0f);
            inst.waitForIdleSync();

            scenario.onActivity(activity -> {
                Shapr3DGuideCadCanvasView c = canvas(activity);
                CadCanvasView.Entity line = c.selected;
                assertEquals("خط", line.shortName());
                List<CadCanvasView.SnapPoint> q = line.snapPoints();
                assertPoint("circle center snap", q.get(0), 320f, 220f);
                assertPoint("circle quadrant snap", q.get(1), 360f, 220f);
                Log.i(TAG, "CIRCLE_RESULT center=320,220 diameter=80 centerSnap=320,220 quadrantSnap=360,220");
            });
        }
    }

    @Test
    public void penArcCreatesCircularGeometryAndExactRadius() {
        Instrumentation inst = InstrumentationRegistry.getInstrumentation();
        try (ActivityScenario<ChobYarActivity> scenario = ActivityScenario.launch(ChobYarActivity.class)) {
            inst.waitForIdleSync();
            scenario.onActivity(activity -> {
                Shapr3DGuideCadCanvasView c = canvas(activity);
                c.clearAll();
                c.setTool(CadCanvasView.TOOL_ARC);
                assertTrue("Production Arc mode did not activate", c.isShaprArcMode());
            });
            inst.waitForIdleSync();

            // A visibly curved stroke is required by ShaprArcCadCanvasView.
            float[][] worldPath = {
                    {420f, 120f}, {450f, 80f}, {480f, 70f}, {510f, 80f}, {540f, 120f}
            };
            stylusPath(inst, frame(scenario), worldPath);
            inst.waitForIdleSync();

            scenario.onActivity(activity -> {
                Shapr3DGuideCadCanvasView c = canvas(activity);
                assertNotNull("Curved pen stroke did not create an Arc", c.selected);
                assertEquals("قوس", c.selected.shortName());
                String result = c.applySelectedDimension("75");
                assertTrue("Arc numeric dimension rejected: " + result, result.startsWith("شعاع ="));
                PointF center = c.selected.center();
                List<CadCanvasView.SnapPoint> snaps = c.selected.snapPoints();
                assertTrue("Arc must expose center + endpoints", snaps.size() >= 3);
                float r0 = distance(center.x, center.y, snaps.get(1).x, snaps.get(1).y);
                float r1 = distance(center.x, center.y, snaps.get(2).x, snaps.get(2).y);
                assertNear("arc start radius", 75f, r0);
                assertNear("arc end radius", 75f, r1);
                Log.i(TAG, "ARC_RESULT type=Arc radius=" + r0 + " endpointRadius=" + r1 + " snapPoints=" + snaps.size());
            });
        }
    }

    @Test
    public void exactLineRectangleAndCircleDimensionsAreStable() {
        Instrumentation inst = InstrumentationRegistry.getInstrumentation();
        try (ActivityScenario<ChobYarActivity> scenario = ActivityScenario.launch(ChobYarActivity.class)) {
            inst.waitForIdleSync();
            scenario.onActivity(activity -> {
                Shapr3DGuideCadCanvasView c = canvas(activity);
                c.clearAll();

                c.executeCommand("LINE 40 40 90 40");
                assertEquals("طول = 125 mm", c.applySelectedDimension("125"));
                List<CadCanvasView.SnapPoint> l = c.selected.snapPoints();
                assertNear("line dimension", 125f, distance(l.get(0).x, l.get(0).y, l.get(1).x, l.get(1).y));

                c.executeCommand("RECT 260 300 50 30");
                assertEquals("اندازه = 120 mm × 80 mm", c.applySelectedDimension("120 80"));
                RectF rb = c.selected.bounds();
                assertNear("rectangle width", 120f, rb.width());
                assertNear("rectangle height", 80f, rb.height());

                c.executeCommand("CIRCLE 500 300 20");
                assertEquals("قطر = 90 mm", c.applySelectedDimension("90"));
                PointF cc = c.selected.center();
                assertNear("circle radius from exact diameter", 45f, farthestSnapDistance(c.selected, cc));

                Log.i(TAG, "DIMENSION_RESULT line=125 rect=120x80 circleDiameter=90 entities=" + c.entities.size());
            });
        }
    }

    private static Shapr3DGuideCadCanvasView canvas(ChobYarActivity activity) {
        Shapr3DGuideCadCanvasView c = find(activity.getWindow().getDecorView());
        assertNotNull("Production Shapr3DGuideCadCanvasView not found", c);
        return c;
    }

    private static Shapr3DGuideCadCanvasView find(View v) {
        if (v instanceof Shapr3DGuideCadCanvasView) return (Shapr3DGuideCadCanvasView) v;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                Shapr3DGuideCadCanvasView c = find(g.getChildAt(i));
                if (c != null) return c;
            }
        }
        return null;
    }

    private static Frame frame(ActivityScenario<ChobYarActivity> scenario) {
        Frame f = new Frame();
        scenario.onActivity(activity -> {
            Shapr3DGuideCadCanvasView c = canvas(activity);
            int[] p = new int[2];
            c.getLocationOnScreen(p);
            f.left = p[0];
            f.top = p[1];
            f.scale = c.viewScale;
            f.offsetX = c.offsetX;
            f.offsetY = c.offsetY;
        });
        return f;
    }

    private static PointF screen(Frame f, float wx, float wy) {
        float k = PX_PER_MM * f.scale;
        return new PointF(f.left + f.offsetX + wx * k, f.top + f.offsetY + wy * k);
    }

    private static void stylusLine(Instrumentation inst, Frame f,
                                   float x0, float y0, float x1, float y1) {
        stylusPath(inst, f, new float[][]{{x0, y0}, {x0 + (x1-x0)*.5f, y0 + (y1-y0)*.5f}, {x1, y1}});
    }

    private static void stylusPath(Instrumentation inst, Frame f, float[][] world) {
        long down = SystemClock.uptimeMillis();
        PointF first = screen(f, world[0][0], world[0][1]);
        send(inst, event(down, now(), MotionEvent.ACTION_DOWN, first.x, first.y));
        for (int i = 1; i < world.length; i++) {
            SystemClock.sleep(45L);
            PointF p = screen(f, world[i][0], world[i][1]);
            int action = i == world.length - 1 ? MotionEvent.ACTION_UP : MotionEvent.ACTION_MOVE;
            send(inst, event(down, now(), action, p.x, p.y));
        }
    }

    private static MotionEvent event(long down, long time, int action, float x, float y) {
        MotionEvent.PointerProperties pp = new MotionEvent.PointerProperties();
        pp.id = 0;
        pp.toolType = MotionEvent.TOOL_TYPE_STYLUS;
        MotionEvent.PointerCoords pc = new MotionEvent.PointerCoords();
        pc.x = x;
        pc.y = y;
        pc.pressure = 1f;
        pc.size = .3f;
        pc.touchMajor = 8f;
        pc.touchMinor = 8f;
        return MotionEvent.obtain(down, time, action, 1,
                new MotionEvent.PointerProperties[]{pp},
                new MotionEvent.PointerCoords[]{pc},
                0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_STYLUS, 0);
    }

    private static void send(Instrumentation inst, MotionEvent e) {
        try { inst.sendPointerSync(e); }
        finally { e.recycle(); }
    }

    private static long now() { return SystemClock.uptimeMillis(); }

    private static float farthestSnapDistance(CadCanvasView.Entity e, PointF c) {
        float best = 0f;
        for (CadCanvasView.SnapPoint p : e.snapPoints()) {
            best = Math.max(best, distance(c.x, c.y, p.x, p.y));
        }
        return best;
    }

    private static float distance(float x0, float y0, float x1, float y1) {
        return (float) Math.hypot(x1-x0, y1-y0);
    }

    private static void assertPoint(String msg, CadCanvasView.SnapPoint p, float x, float y) {
        assertNear(msg + " x", x, p.x);
        assertNear(msg + " y", y, p.y);
    }

    private static void assertNear(String msg, float expected, float actual) {
        assertEquals(msg, expected, actual, EPS);
    }

    private static final class Frame {
        float left, top, scale, offsetX, offsetY;
    }
}

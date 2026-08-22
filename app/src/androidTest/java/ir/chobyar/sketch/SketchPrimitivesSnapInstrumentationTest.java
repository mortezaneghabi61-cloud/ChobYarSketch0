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

/** Production-canvas coverage for Sketch primitives, snaps, numeric dimensions and executable CAD commands. */
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
                c.executeCommand("LINE 100 100 220 100");
                c.executeCommand("LINE 200 55 200 145");
                c.setTool(CadCanvasView.TOOL_LINE);
            });
            inst.waitForIdleSync();

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
                String dimension = c.applySelectedDimension("80");
                assertTrue("Unexpected circle dimension result: " + dimension, dimension.startsWith("قطر = 80.0 mm"));
                PointF center = c.selected.center();
                assertNear("circle center x", 320f, center.x);
                assertNear("circle center y", 220f, center.y);
                assertNear("numeric circle radius", 40f, farthestSnapDistance(c.selected, center));
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

            float[][] worldPath = {
                    {110f, 220f}, {150f, 165f}, {190f, 145f}, {230f, 165f}, {270f, 220f}
            };
            stylusPath(inst, frame(scenario), worldPath);
            inst.waitForIdleSync();

            scenario.onActivity(activity -> {
                Shapr3DGuideCadCanvasView c = canvas(activity);
                assertNotNull("Curved pen stroke did not create an Arc", c.selected);
                assertEquals("قوس", c.selected.shortName());
                String result = c.applySelectedDimension("75");
                assertTrue("Unexpected Arc dimension result: " + result, result.startsWith("شعاع = 75.0 mm"));
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
                String lineDim = c.applySelectedDimension("125");
                assertTrue("Unexpected line dimension result: " + lineDim, lineDim.startsWith("طول = 125.0 mm"));
                List<CadCanvasView.SnapPoint> l = c.selected.snapPoints();
                assertNear("line dimension", 125f, distance(l.get(0).x, l.get(0).y, l.get(1).x, l.get(1).y));

                c.executeCommand("RECT 260 300 50 30");
                String rectDim = c.applySelectedDimension("120 80");
                assertTrue("Unexpected rectangle dimension result: " + rectDim, rectDim.startsWith("اندازه = 120.0 mm × 80.0 mm"));
                RectF rb = c.selected.bounds();
                assertNear("rectangle width", 120f, rb.width());
                assertNear("rectangle height", 80f, rb.height());

                c.executeCommand("CIRCLE 500 300 20");
                String circleDim = c.applySelectedDimension("90");
                assertTrue("Unexpected circle dimension result: " + circleDim, circleDim.startsWith("قطر = 90.0 mm"));
                PointF cc = c.selected.center();
                assertNear("circle radius from exact diameter", 45f, farthestSnapDistance(c.selected, cc));

                Log.i(TAG, "DIMENSION_RESULT line=125 rect=120x80 circleDiameter=90 entities=" + c.entities.size());
            });
        }
    }

    @Test
    public void executableCadCommandSequenceChangesRealGeometry() {
        Instrumentation inst = InstrumentationRegistry.getInstrumentation();
        try (ActivityScenario<ChobYarActivity> scenario = ActivityScenario.launch(ChobYarActivity.class)) {
            inst.waitForIdleSync();
            scenario.onActivity(activity -> {
                Shapr3DGuideCadCanvasView c = canvas(activity);
                c.clearAll();
                int base = c.entities.size();

                String rect = c.executeCommand("RECT 100 120 600 400");
                assertTrue("RECT command rejected: " + rect, rect.contains("مستطیل"));
                assertEquals(base + 1, c.entities.size());
                RectF r0 = c.selected.bounds();
                assertNear("RECT width", 600f, r0.width());
                assertNear("RECT height", 400f, r0.height());
                PointF p0 = c.selected.center();

                String moved = c.executeCommand("MOVE 25 -10");
                assertTrue("MOVE command rejected: " + moved, moved.contains("جابه"));
                PointF p1 = c.selected.center();
                assertNear("MOVE dx", 25f, p1.x - p0.x);
                assertNear("MOVE dy", -10f, p1.y - p0.y);

                String copied = c.executeCommand("COPY 50 0");
                assertTrue("COPY command rejected: " + copied, copied.contains("کپی"));
                assertEquals(base + 2, c.entities.size());
                PointF p2 = c.selected.center();
                assertNear("COPY dx", 50f, p2.x - p1.x);
                assertNear("COPY dy", 0f, p2.y - p1.y);

                String offset = c.executeCommand("OFFSET 18");
                assertTrue("OFFSET command rejected: " + offset, offset.startsWith("Offset = 18.0 mm"));
                assertEquals(base + 3, c.entities.size());
                RectF ro = c.selected.bounds();
                assertNear("OFFSET width", 636f, ro.width());
                assertNear("OFFSET height", 436f, ro.height());

                String rotated = c.executeCommand("ROTATE 90");
                assertTrue("ROTATE command rejected: " + rotated, rotated.startsWith("چرخش 90.00°"));
                RectF rr = c.selected.bounds();
                assertNear("ROTATE width", 436f, rr.width());
                assertNear("ROTATE height", 636f, rr.height());

                String scaled = c.executeCommand("SCALE 0.5");
                assertTrue("SCALE command rejected: " + scaled, scaled.startsWith("Scale × 0.50"));
                RectF rs = c.selected.bounds();
                assertNear("SCALE width", 218f, rs.width());
                assertNear("SCALE height", 318f, rs.height());

                String mirrored = c.executeCommand("MIRROR X 0");
                assertTrue("MIRROR command rejected: " + mirrored, mirrored.contains("محور X"));
                assertEquals(base + 3, c.entities.size());

                String array = c.executeCommand("ARRAY 3 40 0");
                assertTrue("ARRAY command rejected: " + array, array.startsWith("Array: 3"));
                assertEquals(base + 5, c.entities.size());

                String line = c.executeCommand("LINE 0 0 300 400");
                assertTrue("LINE command rejected: " + line, line.contains("خط"));
                List<CadCanvasView.SnapPoint> ls = c.selected.snapPoints();
                assertNear("LINE 3-4-5 length", 500f,
                        distance(ls.get(0).x, ls.get(0).y, ls.get(1).x, ls.get(1).y));

                String circle = c.executeCommand("CIRCLE 300 300 45");
                assertTrue("CIRCLE command rejected: " + circle, circle.contains("دایره"));
                assertNear("CIRCLE radius", 45f, farthestSnapDistance(c.selected, c.selected.center()));

                String arc = c.executeCommand("ARC 500 500 75 0 120");
                assertTrue("ARC command rejected: " + arc, arc.contains("قوس"));
                assertEquals("قوس", c.selected.shortName());
                List<CadCanvasView.SnapPoint> as = c.selected.snapPoints();
                assertTrue("ARC command must expose center + endpoints", as.size() >= 3);
                PointF ac = c.selected.center();
                assertNear("ARC command start radius", 75f, distance(ac.x, ac.y, as.get(1).x, as.get(1).y));
                assertNear("ARC command end radius", 75f, distance(ac.x, ac.y, as.get(2).x, as.get(2).y));
                assertEquals(base + 8, c.entities.size());

                Log.i(TAG, "COMMAND_RESULT rect=600x400 move=25,-10 copy=50,0 offset=18 rotate=90 scale=0.5 mirror=X array=3 line=500 circleR=45 arcR=75 entities=" + c.entities.size());
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
        try {
            boolean injected = inst.getUiAutomation().injectInputEvent(e, true);
            assertTrue("Stylus input injection was rejected", injected);
        } finally {
            e.recycle();
        }
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

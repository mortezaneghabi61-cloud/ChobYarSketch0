package ir.chobyar.sketch;

import android.app.Instrumentation;
import android.graphics.PointF;
import android.util.Log;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Method;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Production-canvas regression tests for persistent parametric Sketch constraints.
 * Each relationship is applied, geometry is deliberately disturbed, then the
 * real solver is allowed to redraw and must restore the relationship.
 */
@RunWith(AndroidJUnit4.class)
public final class SketchConstraintSolverInstrumentationTest {
    private static final String TAG = "SketchConstraintSolver";
    private static final float EPS = 0.12f;

    @Test
    public void horizontalVerticalParallelAndPerpendicularPersist() {
        Instrumentation inst = InstrumentationRegistry.getInstrumentation();
        try (ActivityScenario<ChobYarActivity> scenario = ActivityScenario.launch(ChobYarActivity.class)) {
            inst.waitForIdleSync();

            scenario.onActivity(activity -> {
                Shapr3DGuideCadCanvasView c = canvas(activity);
                reset(c);

                CadCanvasView.Entity h = make(c, "LINE 40 50 160 57");
                select(c, h);
                String hv = c.applyHorizontalVerticalConstraint();
                assertTrue("H/V rejected: " + hv, hv.contains("H/V"));
                assertHorizontal(h);
                PointF hb = endpoint(h, 1);
                h.moveControlPoint(1, hb.x, hb.y + 33f);
                reassertModelAuthority(c);

                CadCanvasView.Entity p0 = make(c, "LINE 40 140 180 140");
                CadCanvasView.Entity p1 = make(c, "LINE 60 190 170 225");
                select(c, p0, p1);
                String parallel = c.applyParallelConstraint();
                assertTrue("Parallel rejected: " + parallel, parallel.contains("Parallel"));
                assertParallel(p0, p1);
                PointF p1b = endpoint(p1, 1);
                p1.moveControlPoint(1, p1b.x - 30f, p1b.y + 42f);
                reassertModelAuthority(c);

                CadCanvasView.Entity q0 = make(c, "LINE 240 70 360 70");
                CadCanvasView.Entity q1 = make(c, "LINE 300 90 345 185");
                select(c, q0, q1);
                String perp = c.applyPerpendicularConstraint();
                assertTrue("Perpendicular rejected: " + perp, perp.contains("Perpendicular"));
                assertPerpendicular(q0, q1);
                PointF q1b = endpoint(q1, 1);
                q1.moveControlPoint(1, q1b.x + 55f, q1b.y - 28f);
                reassertModelAuthority(c);
            });

            inst.waitForIdleSync();
            scenario.onActivity(activity -> {
                Shapr3DGuideCadCanvasView c = canvas(activity);
                CadCanvasView.Entity h = c.entities.get(0);
                CadCanvasView.Entity p0 = c.entities.get(1), p1 = c.entities.get(2);
                CadCanvasView.Entity q0 = c.entities.get(3), q1 = c.entities.get(4);
                assertHorizontal(h);
                assertParallel(p0, p1);
                assertPerpendicular(q0, q1);
                Log.i(TAG, "AXIS_RELATION_RESULT horizontal=true parallel=true perpendicular=true persistent=true");
            });
        }
    }

    @Test
    public void coincidentAndMidpointPersistAfterEndpointPerturbation() throws Exception {
        Instrumentation inst = InstrumentationRegistry.getInstrumentation();
        try (ActivityScenario<ChobYarActivity> scenario = ActivityScenario.launch(ChobYarActivity.class)) {
            inst.waitForIdleSync();
            final int[] linked = new int[2];

            scenario.onActivity(activity -> {
                try {
                    Shapr3DGuideCadCanvasView c = canvas(activity);
                    reset(c);

                    CadCanvasView.Entity a = make(c, "LINE 60 70 150 70");
                    CadCanvasView.Entity b = make(c, "LINE 153 73 230 145");
                    select(c, a, b);
                    String coincident = invokeCoincident(c);
                    assertTrue("Coincident rejected: " + coincident,
                            coincident.contains("Coincident") || coincident.contains("text") || coincident.contains("text"));
                    closestEndpointPair(a, b, linked);
                    assertCoincident(a, linked[0], b, linked[1]);
                    PointF bp = endpoint(b, linked[1]);
                    b.moveControlPoint(linked[1], bp.x + 24f, bp.y + 19f);
                    c.invalidate();

                    CadCanvasView.Entity host = make(c, "LINE 270 210 410 210");
                    CadCanvasView.Entity probe = make(c, "LINE 330 140 342 195");
                    select(c, probe, host);
                    String midpoint = c.applyMidpointConstraint();
                    assertTrue("Midpoint rejected: " + midpoint, midpoint.contains("Midpoint"));
                    assertEndpointAtMidpoint(probe, host);
                    int pi = closestEndpointTo(probe, midpoint(host));
                    PointF pp = endpoint(probe, pi);
                    probe.moveControlPoint(pi, pp.x - 27f, pp.y - 31f);
                    c.invalidate();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });

            inst.waitForIdleSync();
            scenario.onActivity(activity -> {
                CadCanvasView.Entity a = canvas(activity).entities.get(0);
                CadCanvasView.Entity b = canvas(activity).entities.get(1);
                CadCanvasView.Entity host = canvas(activity).entities.get(2);
                CadCanvasView.Entity probe = canvas(activity).entities.get(3);
                assertCoincident(a, linked[0], b, linked[1]);
                assertEndpointAtMidpoint(probe, host);
                Log.i(TAG, "POINT_RELATION_RESULT coincident=true midpoint=true persistent=true");
            });
        }
    }

    @Test
    public void equalAndConcentricPersistForCurvesAndLines() {
        Instrumentation inst = InstrumentationRegistry.getInstrumentation();
        try (ActivityScenario<ChobYarActivity> scenario = ActivityScenario.launch(ChobYarActivity.class)) {
            inst.waitForIdleSync();
            scenario.onActivity(activity -> {
                Shapr3DGuideCadCanvasView c = canvas(activity);
                reset(c);

                CadCanvasView.Entity l0 = make(c, "LINE 40 60 160 60");
                CadCanvasView.Entity l1 = make(c, "LINE 40 100 115 100");
                select(c, l0, l1);
                String eqLine = c.applyEqualConstraint();
                assertTrue("Line Equal rejected: " + eqLine, eqLine.contains("Equal"));
                assertNear("equal line length", length(l0), length(l1));
                PointF l1a = endpoint(l1, 0);
                l1.moveControlPoint(1, l1a.x + 43f, l1a.y + 26f);
                c.invalidate();

                CadCanvasView.Entity c0 = make(c, "CIRCLE 260 120 38");
                CadCanvasView.Entity c1 = make(c, "CIRCLE 340 165 21");
                select(c, c0, c1);
                String equalCurve = c.applyEqualConstraint();
                assertTrue("Curve Equal rejected: " + equalCurve, equalCurve.contains("Equal"));
                assertNear("equal radius", radius(c0), radius(c1));
                String concentric = c.applyConcentricConstraint();
                assertTrue("Concentric rejected: " + concentric, concentric.contains("Concentric"));
                assertPointNear("concentric center", c0.center(), c1.center());

                c1.translate(31f, -17f);
                CadCanvasView.SnapPoint edge = c1.snapPoints().get(1);
                c1.moveControlPoint(1, edge.x + 14f, edge.y);
                c.invalidate();
            });

            inst.waitForIdleSync();
            scenario.onActivity(activity -> {
                Shapr3DGuideCadCanvasView c = canvas(activity);
                CadCanvasView.Entity l0 = c.entities.get(0), l1 = c.entities.get(1);
                CadCanvasView.Entity c0 = c.entities.get(2), c1 = c.entities.get(3);
                assertNear("persistent equal line", length(l0), length(l1));
                assertNear("persistent equal radius", radius(c0), radius(c1));
                assertPointNear("persistent concentric", c0.center(), c1.center());
                Log.i(TAG, "EQUAL_CONCENTRIC_RESULT lineEqual=true radiusEqual=true concentric=true persistent=true");
            });
        }
    }

    @Test
    public void tangentAndSymmetryPersist() {
        Instrumentation inst = InstrumentationRegistry.getInstrumentation();
        try (ActivityScenario<ChobYarActivity> scenario = ActivityScenario.launch(ChobYarActivity.class)) {
            inst.waitForIdleSync();
            scenario.onActivity(activity -> {
                Shapr3DGuideCadCanvasView c = canvas(activity);
                reset(c);

                CadCanvasView.Entity circle = make(c, "CIRCLE 180 180 45");
                CadCanvasView.Entity tangent = make(c, "LINE 225 180 315 95");
                select(c, tangent, circle);
                String tang = c.applyTangentConstraint();
                assertTrue("Tangent rejected: " + tang, tang.contains("Tangent"));
                assertTangent(tangent, circle);
                int moving = endpointOnCircle(tangent, circle);
                PointF tp = endpoint(tangent, moving);
                tangent.moveControlPoint(moving, tp.x + 25f, tp.y + 33f);
                c.invalidate();

                CadCanvasView.Entity source = make(c, "LINE 80 280 130 235");
                CadCanvasView.Entity mirror = make(c, "LINE 305 260 350 310");
                CadCanvasView.Entity axis = make(c, "LINE 220 200 220 360");
                select(c, source, mirror, axis);
                String sym = c.applySymmetryConstraint();
                assertTrue("Symmetry rejected: " + sym, sym.contains("Symmetry"));
                assertMirrorAcrossVertical(source, mirror, 220f);
                PointF mp = endpoint(mirror, 1);
                mirror.moveControlPoint(1, mp.x + 40f, mp.y - 25f);
                c.invalidate();
            });

            inst.waitForIdleSync();
            scenario.onActivity(activity -> {
                Shapr3DGuideCadCanvasView c = canvas(activity);
                CadCanvasView.Entity circle = c.entities.get(0), tangent = c.entities.get(1);
                CadCanvasView.Entity source = c.entities.get(2), mirror = c.entities.get(3);
                assertTangent(tangent, circle);
                assertMirrorAcrossVertical(source, mirror, 220f);
                Log.i(TAG, "TANGENT_SYMMETRY_RESULT tangent=true symmetry=true persistent=true");
            });
        }
    }

    @Test
    public void lockBlocksTransformAndDrivingDimension() {
        Instrumentation inst = InstrumentationRegistry.getInstrumentation();
        try (ActivityScenario<ChobYarActivity> scenario = ActivityScenario.launch(ChobYarActivity.class)) {
            inst.waitForIdleSync();
            scenario.onActivity(activity -> {
                Shapr3DGuideCadCanvasView c = canvas(activity);
                reset(c);
                CadCanvasView.Entity line = make(c, "LINE 80 100 200 100");
                select(c, line);
                PointF before = line.center();
                float len = length(line);

                String locked = c.toggleSelectedLock();
                assertEquals("Lock did not commit model-owned state", "1 selection(s) locked", locked);
                if (c instanceof K33MirroredCadCanvasView) {
                    K33MirroredCadCanvasView model = (K33MirroredCadCanvasView)c;
                    model.requireSketchMirrorParity();
                    assertEquals("Lock must be represented by one model FIXED constraint", 1, model.sketchConstraintCount());
                    assertEquals("Legacy object-identity lock truth must stay empty", 0, model.legacySelectionLockTruthCount());
                    assertTrue("Model Lock must be undoable", model.sketchAuthorityCanUndo());
                }

                c.moveSelected(55f, 42f);
                String dim = c.applySelectedDimension("250");
                assertTrue("Locked dimension was not rejected: " + dim, dim.contains("Lock"));
                assertPointNear("locked center", before, line.center());
                assertNear("locked length", len, length(line));

                String unlocked = c.toggleSelectedLock();
                assertEquals("Unlock did not remove model-owned state", "1 selection(s) unlocked", unlocked);
                if (c instanceof K33MirroredCadCanvasView) {
                    K33MirroredCadCanvasView model = (K33MirroredCadCanvasView)c;
                    model.requireSketchMirrorParity();
                    assertEquals("Unlock must remove model FIXED authority", 0, model.sketchConstraintCount());
                    assertEquals("Unlock must not reconstruct legacy object identity", 0, model.legacySelectionLockTruthCount());
                }
                c.moveSelected(10f, 5f);
                assertNear("unlocked dx", 10f, line.center().x - before.x);
                assertNear("unlocked dy", 5f, line.center().y - before.y);
                Log.i(TAG, "LOCK_RESULT blockedMove=true blockedDimension=true unlockMove=true modelOwned=true");
            });
        }
    }

    private static void reassertModelAuthority(Shapr3DGuideCadCanvasView c) {
        if (c instanceof K33MirroredCadCanvasView) {
            c.moveSelected(0f, 0f);
        } else {
            c.invalidate();
        }
    }

    private static Shapr3DGuideCadCanvasView canvas(ChobYarActivity activity) {
        Shapr3DGuideCadCanvasView c = find(activity.getWindow().getDecorView());
        assertNotNull("Production Shapr3DGuideCadCanvasView not found", c);
        return c;
    }

    private static Shapr3DGuideCadCanvasView find(android.view.View v) {
        if (v instanceof Shapr3DGuideCadCanvasView) return (Shapr3DGuideCadCanvasView) v;
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                Shapr3DGuideCadCanvasView c = find(g.getChildAt(i));
                if (c != null) return c;
            }
        }
        return null;
    }

    private static void reset(Shapr3DGuideCadCanvasView c) {
        c.clearAll();
        c.selectedObjects.clear();
        c.selected = null;
    }

    private static CadCanvasView.Entity make(Shapr3DGuideCadCanvasView c, String command) {
        String result = c.executeCommand(command);
        assertNotNull("Command returned null: " + command, result);
        assertNotNull("Command did not select geometry: " + command + " => " + result, c.selected);
        return c.selected;
    }

    private static void select(Shapr3DGuideCadCanvasView c, CadCanvasView.Entity... entities) {
        c.selectedObjects.clear();
        for (CadCanvasView.Entity e : entities) c.selectedObjects.add(e);
        c.selected = entities.length == 0 ? null : entities[entities.length - 1];
    }

    private static String invokeCoincident(Shapr3DGuideCadCanvasView c) throws Exception {
        Method m = ParametricSketchCanvasView.class.getDeclaredMethod("applyManualCoincident");
        m.setAccessible(true);
        Object out = m.invoke(c);
        return out == null ? "" : String.valueOf(out);
    }

    private static PointF endpoint(CadCanvasView.Entity e, int index) {
        java.util.List<CadCanvasView.SnapPoint> q = e.snapPoints();
        assertTrue("Entity does not expose two endpoints", q.size() >= 2);
        CadCanvasView.SnapPoint p = q.get(index);
        return new PointF(p.x, p.y);
    }

    private static float length(CadCanvasView.Entity e) {
        PointF a = endpoint(e, 0), b = endpoint(e, 1);
        return dist(a, b);
    }

    private static float radius(CadCanvasView.Entity e) {
        PointF c = e.center();
        float best = 0f;
        for (CadCanvasView.SnapPoint p : e.snapPoints()) best = Math.max(best, dist(c, new PointF(p.x, p.y)));
        return best;
    }

    private static PointF midpoint(CadCanvasView.Entity e) {
        PointF a = endpoint(e, 0), b = endpoint(e, 1);
        return new PointF((a.x + b.x) * .5f, (a.y + b.y) * .5f);
    }

    private static void assertHorizontal(CadCanvasView.Entity e) {
        PointF a = endpoint(e, 0), b = endpoint(e, 1);
        assertNear("horizontal dy", 0f, b.y - a.y);
    }

    private static void assertParallel(CadCanvasView.Entity a, CadCanvasView.Entity b) {
        PointF a0 = endpoint(a, 0), a1 = endpoint(a, 1), b0 = endpoint(b, 0), b1 = endpoint(b, 1);
        float ax = a1.x-a0.x, ay = a1.y-a0.y, bx = b1.x-b0.x, by = b1.y-b0.y;
        float cross = ax*by - ay*bx;
        float denom = Math.max(1f, (float)Math.hypot(ax,ay)*(float)Math.hypot(bx,by));
        assertEquals("parallel normalized cross", 0f, cross/denom, 0.002f);
    }

    private static void assertPerpendicular(CadCanvasView.Entity a, CadCanvasView.Entity b) {
        PointF a0 = endpoint(a, 0), a1 = endpoint(a, 1), b0 = endpoint(b, 0), b1 = endpoint(b, 1);
        float ax = a1.x-a0.x, ay = a1.y-a0.y, bx = b1.x-b0.x, by = b1.y-b0.y;
        float dot = ax*bx + ay*by;
        float denom = Math.max(1f, (float)Math.hypot(ax,ay)*(float)Math.hypot(bx,by));
        assertEquals("perpendicular normalized dot", 0f, dot/denom, 0.002f);
    }

    private static void closestEndpointPair(CadCanvasView.Entity a, CadCanvasView.Entity b, int[] out) {
        float best = Float.MAX_VALUE;
        for (int i=0;i<2;i++) for (int j=0;j<2;j++) {
            float d = dist(endpoint(a,i), endpoint(b,j));
            if (d < best) { best=d; out[0]=i; out[1]=j; }
        }
    }

    private static void assertCoincident(CadCanvasView.Entity a, int ai, CadCanvasView.Entity b, int bi) {
        assertPointNear("coincident endpoints", endpoint(a,ai), endpoint(b,bi));
    }

    private static int closestEndpointTo(CadCanvasView.Entity e, PointF p) {
        return dist(endpoint(e,0),p) <= dist(endpoint(e,1),p) ? 0 : 1;
    }

    private static void assertEndpointAtMidpoint(CadCanvasView.Entity probe, CadCanvasView.Entity host) {
        PointF m = midpoint(host);
        float d = Math.min(dist(endpoint(probe,0),m), dist(endpoint(probe,1),m));
        assertEquals("endpoint-to-midpoint distance", 0f, d, EPS);
    }

    private static int endpointOnCircle(CadCanvasView.Entity line, CadCanvasView.Entity curve) {
        PointF c = curve.center(); float r = radius(curve);
        float d0 = Math.abs(dist(endpoint(line,0),c)-r), d1 = Math.abs(dist(endpoint(line,1),c)-r);
        return d0 <= d1 ? 0 : 1;
    }

    private static void assertTangent(CadCanvasView.Entity line, CadCanvasView.Entity curve) {
        int t = endpointOnCircle(line, curve); int fixed = 1-t;
        PointF p = endpoint(line,t), f = endpoint(line,fixed), c = curve.center(); float r=radius(curve);
        assertEquals("tangent point on curve", r, dist(p,c), 0.15f);
        float rx=p.x-c.x, ry=p.y-c.y, lx=f.x-p.x, ly=f.y-p.y;
        float denom=Math.max(1f,(float)Math.hypot(rx,ry)*(float)Math.hypot(lx,ly));
        assertEquals("radius perpendicular to tangent",0f,(rx*lx+ry*ly)/denom,0.003f);
    }

    private static void assertMirrorAcrossVertical(CadCanvasView.Entity source, CadCanvasView.Entity mirror, float axisX) {
        PointF s0=endpoint(source,0), s1=endpoint(source,1), m0=endpoint(mirror,0), m1=endpoint(mirror,1);
        PointF r0=new PointF(2f*axisX-s0.x,s0.y), r1=new PointF(2f*axisX-s1.x,s1.y);
        float same=dist(m0,r0)+dist(m1,r1), swap=dist(m0,r1)+dist(m1,r0);
        assertEquals("symmetry error",0f,Math.min(same,swap),0.25f);
    }

    private static float dist(PointF a, PointF b) {
        return (float)Math.hypot(a.x-b.x,a.y-b.y);
    }

    private static void assertPointNear(String msg, PointF a, PointF b) {
        assertEquals(msg + " x", a.x, b.x, EPS);
        assertEquals(msg + " y", a.y, b.y, EPS);
    }

    private static void assertNear(String msg, float expected, float actual) {
        assertEquals(msg, expected, actual, EPS);
    }
}

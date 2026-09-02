package ir.chobyar.sketch;

import android.app.Instrumentation;
import android.graphics.PointF;
import android.util.Log;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collection;

import ir.chobyar.sketch.core.SketchConstraint;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Production-canvas regression tests for persistent parametric Sketch constraints.
 * Model-owned relationships are reasserted by semantic transactions; rendering
 * remains presentation-only for migrated authority slices such as Tangent and Symmetry.
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
                    reassertModelAuthority(c);

                    CadCanvasView.Entity host = make(c, "LINE 270 210 410 210");
                    CadCanvasView.Entity probe = make(c, "LINE 330 140 342 195");
                    select(c, probe, host);
                    String midpoint = c.applyMidpointConstraint();
                    assertTrue("Midpoint rejected: " + midpoint, midpoint.contains("Midpoint"));
                    assertEndpointAtMidpoint(probe, host);
                    int pi = closestEndpointTo(probe, midpoint(host));
                    PointF pp = endpoint(probe, pi);
                    probe.moveControlPoint(pi, pp.x - 27f, pp.y - 31f);
                    reassertModelAuthority(c);
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
                reassertModelAuthority(c);

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
                reassertModelAuthority(c);
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
            final String[] stableIds = new String[5];
            scenario.onActivity(activity -> {
                Shapr3DGuideCadCanvasView c = canvas(activity);
                reset(c);

                assertTrue("Production canvas must expose model-owned Tangent/Symmetry authority",
                        c instanceof K33MirroredCadCanvasView);
                K33MirroredCadCanvasView model = (K33MirroredCadCanvasView)c;

                CadCanvasView.Entity circle = make(c, "CIRCLE 180 180 45");
                CadCanvasView.Entity tangent = make(c, "LINE 300 280 390 245");
                stableIds[0] = circle.stableId();
                stableIds[1] = tangent.stableId();
                select(c, tangent, circle);
                String tang = c.applyTangentConstraint();
                assertTrue("Tangent rejected: " + tang, tang.contains("Tangent"));
                assertModelOwnedTangent(model, stableIds[1], stableIds[0]);
                assertSupportingLineTangent(tangent, circle);
                assertNoEndpointOnCurve(tangent, circle);

                CadCanvasView.Entity source = make(c, "LINE 80 280 130 235");
                CadCanvasView.Entity mirror = make(c, "LINE 305 260 350 310");
                CadCanvasView.Entity axis = make(c, "LINE 220 200 220 360");
                stableIds[2] = source.stableId();
                stableIds[3] = mirror.stableId();
                stableIds[4] = axis.stableId();
                select(c, source, mirror, axis);
                String sym = c.applySymmetryConstraint();
                assertTrue("Symmetry rejected: " + sym, sym.contains("Symmetry"));
                assertModelOwnedSymmetry(model, stableIds[2], stableIds[3], stableIds[4]);
                assertMirrorAcrossVertical(source, mirror, 220f);
            });

            inst.waitForIdleSync();
            scenario.onActivity(activity -> {
                K33MirroredCadCanvasView c = (K33MirroredCadCanvasView)canvas(activity);
                CadCanvasView.Entity circle = entityByStableId(c, stableIds[0]);
                CadCanvasView.Entity tangent = entityByStableId(c, stableIds[1]);
                CadCanvasView.Entity source = entityByStableId(c, stableIds[2]);
                CadCanvasView.Entity mirror = entityByStableId(c, stableIds[3]);
                assertModelOwnedTangent(c, stableIds[1], stableIds[0]);
                assertModelOwnedSymmetry(c, stableIds[2], stableIds[3], stableIds[4]);
                assertSupportingLineTangent(tangent, circle);
                assertNoEndpointOnCurve(tangent, circle);
                assertMirrorAcrossVertical(source, mirror, 220f);

                String saved = c.exportSketchProjectState();
                c.clearAll();
                String restored = c.importSketchProjectState(saved);
                assertTrue("Tangent/Symmetry project state failed to reload: " + restored,
                        !restored.contains("could not be restored"));
                c.requireSketchMirrorParity();
            });

            inst.waitForIdleSync();
            scenario.onActivity(activity -> {
                K33MirroredCadCanvasView c = (K33MirroredCadCanvasView)canvas(activity);
                CadCanvasView.Entity circle = entityByStableId(c, stableIds[0]);
                CadCanvasView.Entity tangent = entityByStableId(c, stableIds[1]);
                CadCanvasView.Entity source = entityByStableId(c, stableIds[2]);
                CadCanvasView.Entity mirror = entityByStableId(c, stableIds[3]);
                assertModelOwnedTangent(c, stableIds[1], stableIds[0]);
                assertModelOwnedSymmetry(c, stableIds[2], stableIds[3], stableIds[4]);
                assertSupportingLineTangent(tangent, circle);
                assertNoEndpointOnCurve(tangent, circle);
                assertMirrorAcrossVertical(source, mirror, 220f);
                Log.i(TAG, "TANGENT_SYMMETRY_RESULT tangent=true symmetry=true persistent=true modelOwned=true");
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

    private static CadCanvasView.Entity entityByStableId(K33MirroredCadCanvasView c, String stableId) {
        for (CadCanvasView.Entity entity : c.entities) {
            if (stableId.equals(entity.stableId())) return entity;
        }
        throw new AssertionError("Missing persisted entity " + stableId);
    }

    private static void assertModelOwnedTangent(K33MirroredCadCanvasView c,
                                                String lineId, String curveId) {
        int tangentCount = 0;
        int hiddenContactCount = 0;
        for (SketchConstraint constraint : c.sketchConstraints()) {
            boolean referencesPair = constraint.referencedEntityIds().contains(lineId)
                    && constraint.referencedEntityIds().contains(curveId);
            if (constraint.kind == SketchConstraint.Kind.TANGENT && referencesPair) tangentCount++;
            if ((constraint.kind == SketchConstraint.Kind.COINCIDENT
                    || constraint.kind == SketchConstraint.Kind.POINT_ON_ENTITY)
                    && referencesPair) hiddenContactCount++;
        }
        assertEquals("Exactly one model-owned stable-ID Tangent must persist", 1, tangentCount);
        assertEquals("Tangent must not persist an implicit endpoint contact constraint", 0, hiddenContactCount);
        c.requireSketchMirrorParity();
    }

    private static void assertModelOwnedSymmetry(K33MirroredCadCanvasView c,
                                                 String sourceId, String mirrorId, String axisId) {
        int symmetryCount = 0;
        int hiddenContactCount = 0;
        for (SketchConstraint constraint : c.sketchConstraints()) {
            boolean referencesAnySymmetryPair = constraint.referencedEntityIds().contains(sourceId)
                    && constraint.referencedEntityIds().contains(mirrorId);
            if (constraint.kind == SketchConstraint.Kind.SYMMETRY
                    && sourceId.equals(constraint.primaryEntityId)
                    && mirrorId.equals(constraint.secondaryEntityId)
                    && axisId.equals(constraint.tertiaryEntityId)) symmetryCount++;
            if ((constraint.kind == SketchConstraint.Kind.COINCIDENT
                    || constraint.kind == SketchConstraint.Kind.POINT_ON_ENTITY)
                    && referencesAnySymmetryPair) hiddenContactCount++;
        }
        assertEquals("Exactly one model-owned three-stable-ID Symmetry must persist", 1, symmetryCount);
        assertEquals("Symmetry must not create hidden endpoint-contact metadata", 0, hiddenContactCount);
        assertEquals("Migrated Symmetry must not populate legacy object-identity truth",
                0, legacySymmetryTruthCount(c));
        c.requireSketchMirrorParity();
    }

    private static int legacySymmetryTruthCount(K33MirroredCadCanvasView c) {
        try {
            Field field = ShaprLabCanvasView.class.getDeclaredField("symmetryRelations");
            field.setAccessible(true);
            Object value = field.get(c);
            if (value instanceof Collection) return ((Collection<?>) value).size();
            throw new AssertionError("Unexpected legacy symmetryRelations store: " + value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Cannot inspect legacy Symmetry authority", e);
        }
    }

    private static void assertSupportingLineTangent(CadCanvasView.Entity line,
                                                    CadCanvasView.Entity curve) {
        PointF a = endpoint(line, 0), b = endpoint(line, 1), center = curve.center();
        float dx = b.x - a.x, dy = b.y - a.y;
        float supportingDistance = Math.abs(dx * (center.y - a.y) - dy * (center.x - a.x))
                / Math.max(EPS, (float)Math.hypot(dx, dy));
        assertEquals("supporting line tangent distance", radius(curve), supportingDistance, EPS);
    }

    private static void assertNoEndpointOnCurve(CadCanvasView.Entity line,
                                                CadCanvasView.Entity curve) {
        PointF center = curve.center();
        float r = radius(curve);
        assertTrue("Tangent must not force endpoint 0 onto the curve",
                Math.abs(dist(endpoint(line, 0), center) - r) > 1f);
        assertTrue("Tangent must not force endpoint 1 onto the curve",
                Math.abs(dist(endpoint(line, 1), center) - r) > 1f);
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

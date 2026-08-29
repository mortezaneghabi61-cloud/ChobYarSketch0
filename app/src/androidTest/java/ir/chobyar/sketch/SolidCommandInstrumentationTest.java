package ir.chobyar.sketch;

import android.app.Instrumentation;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Field;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** Hard contracts for real 3D operations on the production Shapr3DGuide canvas. */
@RunWith(AndroidJUnit4.class)
public final class SolidCommandInstrumentationTest {
    private static final String TAG = "SolidCommandContract";
    private static final float EPS = 0.06f;

    @Test
    public void extrudeCommandCreatesExactThirtyMillimeterBodyAndHistoryUndoRedo() {
        Instrumentation inst = InstrumentationRegistry.getInstrumentation();
        try (ActivityScenario<ChobYarActivity> scenario = ActivityScenario.launch(ChobYarActivity.class)) {
            inst.waitForIdleSync();
            scenario.onActivity(activity -> {
                Shapr3DGuideCadCanvasView c = canvas(activity);
                c.clearAll();

                String rect = c.executeCommand("RECT 40 40 120 80");
                assertTrue("RECT rejected: " + rect, rect.contains("Rectangle"));
                assertNotNull(c.selected);

                String extrude = c.executeCommand("EXTRUDE 30");
                assertTrue("EXTRUDE rejected: " + extrude, extrude.contains("created"));
                assertTrue("Extrude must report millimeters: " + extrude, extrude.contains("30") && extrude.contains("mm"));
                assertEquals("Extrude must create one body", 1, c.bodyCount());
                assertTrue("Extrude must switch to 3D overview", c.is3DOverview());

                SolidCSG solid = selectedCsg(c);
                assertNotNull("Extrude created a body without CSG", solid);
                assertTrue("Extrude CSG must have faces", solid.polygons().size() >= 6);
                Bounds b = bounds(solid);
                float[] extents = sorted3(b.dx(), b.dy(), b.dz());
                assertNear("Extrude smallest extent (height)", 30f, extents[0]);
                assertNear("Extrude middle extent", 80f, extents[1]);
                assertNear("Extrude largest extent", 120f, extents[2]);

                String rebuilt = c.rebuildHistory();
                assertTrue("History rebuild did not report success: " + rebuilt, rebuilt.contains("History") && !rebuilt.contains("Error"));

                String undo = c.undoLastFeature();
                assertTrue("3D undo rejected: " + undo, undo.contains("Extrude"));
                assertEquals("Undo must remove Extrude body", 0, c.bodyCount());

                String redo = c.redoLastFeature();
                assertTrue("3D redo rejected: " + redo, redo.contains("Extrude"));
                assertEquals("Redo must restore Extrude body", 1, c.bodyCount());
                Bounds redone = bounds(selectedCsg(c));
                float[] redoExtents = sorted3(redone.dx(), redone.dy(), redone.dz());
                assertNear("Redo height", 30f, redoExtents[0]);
                assertNear("Redo width", 80f, redoExtents[1]);
                assertNear("Redo length", 120f, redoExtents[2]);

                Log.i(TAG, "EXTRUDE_RESULT bodyCount=1 extents=30x80x120 history=true undo=true redo=true faces=" + selectedCsg(c).polygons().size());
            });
        }
    }

    @Test
    public void revolveCreatesRealBodyAndSurvivesHistoryRebuild() {
        Instrumentation inst = InstrumentationRegistry.getInstrumentation();
        try (ActivityScenario<ChobYarActivity> scenario = ActivityScenario.launch(ChobYarActivity.class)) {
            inst.waitForIdleSync();
            scenario.onActivity(activity -> {
                Shapr3DGuideCadCanvasView c = canvas(activity);
                c.clearAll();

                String rect = c.executeCommand("RECT 40 30 35 70");
                assertTrue("REVOLVE profile RECT rejected: " + rect, rect.contains("Rectangle"));
                Object profile = c.selected;
                assertNotNull(profile);

                String revolve = c.createRevolve(profile, null, false, 360f);
                assertTrue("REVOLVE rejected: " + revolve, revolve.contains("Revolve created"));
                assertEquals("Revolve must create one body", 1, c.bodyCount());

                SolidCSG solid = selectedCsg(c);
                assertNotNull("Revolve body has no CSG", solid);
                assertTrue("Revolve must create non-trivial topology", solid.polygons().size() > 20);
                Bounds before = bounds(solid);
                assertTrue("Revolve X extent must be positive", before.dx() > 1f);
                assertTrue("Revolve Y extent must be positive", before.dy() > 1f);
                assertTrue("Revolve Z extent must be positive", before.dz() > 1f);

                String rebuilt = c.rebuildHistory();
                assertTrue("Revolve history was not rebuilt: " + rebuilt, rebuilt.contains("Form 1") && !rebuilt.contains("Form Error"));
                SolidCSG after = selectedCsg(c);
                assertNotNull(after);
                assertEquals("Revolve topology changed face count after rebuild", solid.polygons().size(), after.polygons().size());
                Bounds afterBounds = bounds(after);
                assertNear("Revolve rebuild X extent", before.dx(), afterBounds.dx());
                assertNear("Revolve rebuild Y extent", before.dy(), afterBounds.dy());
                assertNear("Revolve rebuild Z extent", before.dz(), afterBounds.dz());

                Log.i(TAG, "REVOLVE_RESULT bodyCount=1 angle=360 persistent=true faces=" + after.polygons().size()
                        + " extents=" + fmt(afterBounds.dx()) + "x" + fmt(afterBounds.dy()) + "x" + fmt(afterBounds.dz()));
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

    private static SolidCSG selectedCsg(Shapr3DGuideCadCanvasView c) {
        try {
            Field selected = SolidCadCanvasView.class.getDeclaredField("selectedBody");
            selected.setAccessible(true);
            Object body = selected.get(c);
            if (body == null) return null;
            Field csg = body.getClass().getDeclaredField("csg");
            csg.setAccessible(true);
            return (SolidCSG) csg.get(body);
        } catch (Exception e) {
            throw new AssertionError("Cannot inspect selected production body", e);
        }
    }

    private static Bounds bounds(SolidCSG csg) {
        Bounds b = new Bounds();
        for (SolidCSG.Polygon p : csg.polygons()) {
            for (SolidCSG.Vertex v : p.vertices) b.add(v.pos.x, v.pos.y, v.pos.z);
        }
        assertTrue("Body bounds are empty", b.seen);
        return b;
    }

    private static float[] sorted3(float a, float b, float c) {
        float[] x = {Math.abs(a), Math.abs(b), Math.abs(c)};
        java.util.Arrays.sort(x);
        return x;
    }

    private static void assertNear(String label, float expected, float actual) {
        assertTrue(label + " expected=" + expected + " actual=" + actual,
                Math.abs(expected - actual) <= EPS);
    }

    private static String fmt(float v) { return String.format(java.util.Locale.US, "%.2f", v); }

    private static final class Bounds {
        boolean seen;
        float minX = Float.POSITIVE_INFINITY, minY = Float.POSITIVE_INFINITY, minZ = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY, maxZ = Float.NEGATIVE_INFINITY;
        void add(float x, float y, float z) {
            seen = true;
            minX = Math.min(minX, x); minY = Math.min(minY, y); minZ = Math.min(minZ, z);
            maxX = Math.max(maxX, x); maxY = Math.max(maxY, y); maxZ = Math.max(maxZ, z);
        }
        float dx() { return maxX - minX; }
        float dy() { return maxY - minY; }
        float dz() { return maxZ - minZ; }
    }
}

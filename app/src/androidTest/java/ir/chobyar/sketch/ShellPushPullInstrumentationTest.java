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

/** Exact geometric contracts for production Shell and axial Push/Pull. */
@RunWith(AndroidJUnit4.class)
public final class ShellPushPullInstrumentationTest {
    private static final String TAG = "ShellPushPullContract";

    @Test
    public void shellFiveMillimetersCreatesExactOpenShellAndPersists() {
        Instrumentation inst = InstrumentationRegistry.getInstrumentation();
        try (ActivityScenario<ChobYarActivity> scenario = ActivityScenario.launch(ChobYarActivity.class)) {
            inst.waitForIdleSync();
            scenario.onActivity(activity -> {
                Shapr3DGuideCadCanvasView c = box(activity);
                String result = c.executeCommand("SHELL3D 5");
                assertTrue("SHELL3D rejected: " + result, result.contains("Shell") && result.contains("5"));
                assertEquals("Shell must keep one Body", 1, c.bodyCount());

                SolidCSG shell = selectedCsg(c);
                Bounds b = bounds(shell);
                float[] e = sorted3(b.dx(), b.dy(), b.dz());
                near("Shell thickness extent", 20f, e[0], .08f);
                near("Shell width", 80f, e[1], .08f);
                near("Shell length", 100f, e[2], .08f);

                // Outer 100*80*20 minus inner 90*70*15. One 5mm cap remains.
                double expected = 65500d;
                double actual = volume(shell);
                near("Shell exact volume", (float) expected, (float) actual, 1.0f);
                assertTrue("Shell must create cavity topology", shell.polygons().size() > 6);

                String rebuilt = c.rebuildHistory();
                assertTrue("Shell History replay failed: " + rebuilt, rebuilt.contains("Direct 1") && !rebuilt.contains("Error"));
                SolidCSG replay = selectedCsg(c);
                near("Shell volume after History rebuild", (float) actual, (float) volume(replay), .5f);
                assertEquals("Shell face count changed after rebuild", shell.polygons().size(), replay.polygons().size());

                Log.i(TAG, "SHELL3D_RESULT bodyCount=1 extents=20x80x100 wall=5 volume="
                        + fmt(actual) + " expected=65500.00 persistent=true faces=" + replay.polygons().size());
            });
        }
    }

    @Test
    public void pushPullTopPlusTenMakesExactThirtyMillimeterBodyAndPersists() {
        Instrumentation inst = InstrumentationRegistry.getInstrumentation();
        try (ActivityScenario<ChobYarActivity> scenario = ActivityScenario.launch(ChobYarActivity.class)) {
            inst.waitForIdleSync();
            scenario.onActivity(activity -> {
                Shapr3DGuideCadCanvasView c = box(activity);
                String result = c.executeCommand("PUSHPULL3D TOP 10");
                assertTrue("PUSHPULL3D rejected: " + result, result.contains("Face Offset") && result.contains("10"));
                assertEquals("Push/Pull must keep one Body", 1, c.bodyCount());

                SolidCSG pushed = selectedCsg(c);
                Bounds b = bounds(pushed);
                float[] e = sorted3(b.dx(), b.dy(), b.dz());
                near("Push/Pull height", 30f, e[0], .08f);
                near("Push/Pull width", 80f, e[1], .08f);
                near("Push/Pull length", 100f, e[2], .08f);
                double actual = volume(pushed);
                near("Push/Pull exact volume", 240000f, (float) actual, 1.0f);

                String rebuilt = c.rebuildHistory();
                assertTrue("Push/Pull History replay failed: " + rebuilt, rebuilt.contains("Direct 1") && !rebuilt.contains("Error"));
                SolidCSG replay = selectedCsg(c);
                near("Push/Pull volume after History rebuild", (float) actual, (float) volume(replay), .5f);
                assertEquals("Push/Pull face count changed after rebuild", pushed.polygons().size(), replay.polygons().size());

                Log.i(TAG, "PUSHPULL3D_RESULT bodyCount=1 face=TOP distance=10 extents=30x80x100 volume="
                        + fmt(actual) + " expected=240000.00 persistent=true faces=" + replay.polygons().size());
            });
        }
    }

    private static Shapr3DGuideCadCanvasView box(ChobYarActivity activity) {
        Shapr3DGuideCadCanvasView c = canvas(activity);
        c.clearAll();
        String rect = c.executeCommand("RECT 0 0 100 80");
        assertTrue("RECT rejected: " + rect, rect.contains("Rectangle"));
        String ext = c.executeCommand("EXTRUDE 20");
        assertTrue("EXTRUDE rejected: " + ext, ext.contains("created"));
        assertEquals(1, c.bodyCount());
        return c;
    }

    private static Shapr3DGuideCadCanvasView canvas(ChobYarActivity activity) {
        Shapr3DGuideCadCanvasView c = find(activity.getWindow().getDecorView());
        assertNotNull("Production Shapr3DGuide canvas not found", c);
        return c;
    }

    private static Shapr3DGuideCadCanvasView find(View v) {
        if (v instanceof Shapr3DGuideCadCanvasView) return (Shapr3DGuideCadCanvasView) v;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i=0;i<g.getChildCount();i++) {
                Shapr3DGuideCadCanvasView c = find(g.getChildAt(i));
                if (c != null) return c;
            }
        }
        return null;
    }

    private static SolidCSG selectedCsg(Shapr3DGuideCadCanvasView c) {
        try {
            Field sf = SolidCadCanvasView.class.getDeclaredField("selectedBody"); sf.setAccessible(true);
            Object body = sf.get(c); assertNotNull("No selected Body", body);
            Field cf = body.getClass().getDeclaredField("csg"); cf.setAccessible(true);
            SolidCSG csg = (SolidCSG) cf.get(body); assertNotNull(csg); return csg;
        } catch (Exception e) { throw new AssertionError("Cannot inspect selected Body", e); }
    }

    private static double volume(SolidCSG csg) {
        double sixV=0d;
        for (SolidCSG.Polygon p:csg.polygons()) {
            if (p.vertices.size()<3) continue;
            Geometry3D.Vec3 a=p.vertices.get(0).pos;
            for(int i=1;i<p.vertices.size()-1;i++) {
                Geometry3D.Vec3 b=p.vertices.get(i).pos, d=p.vertices.get(i+1).pos;
                sixV += a.dot(b.cross(d));
            }
        }
        return Math.abs(sixV/6d);
    }

    private static Bounds bounds(SolidCSG csg) {
        Bounds b=new Bounds();
        for(SolidCSG.Polygon p:csg.polygons()) for(SolidCSG.Vertex v:p.vertices) b.add(v.pos.x,v.pos.y,v.pos.z);
        assertTrue("Body bounds empty",b.seen); return b;
    }
    private static float[] sorted3(float a,float b,float c){float[] x={Math.abs(a),Math.abs(b),Math.abs(c)};java.util.Arrays.sort(x);return x;}
    private static void near(String label,float expected,float actual,float eps){assertTrue(label+" expected="+expected+" actual="+actual,Math.abs(expected-actual)<=eps);}
    private static String fmt(double v){return String.format(java.util.Locale.US,"%.2f",v);}
    private static final class Bounds{
        boolean seen;float minX=Float.POSITIVE_INFINITY,minY=Float.POSITIVE_INFINITY,minZ=Float.POSITIVE_INFINITY,maxX=Float.NEGATIVE_INFINITY,maxY=Float.NEGATIVE_INFINITY,maxZ=Float.NEGATIVE_INFINITY;
        void add(float x,float y,float z){seen=true;minX=Math.min(minX,x);minY=Math.min(minY,y);minZ=Math.min(minZ,z);maxX=Math.max(maxX,x);maxY=Math.max(maxY,y);maxZ=Math.max(maxZ,z);}float dx(){return maxX-minX;}float dy(){return maxY-minY;}float dz(){return maxZ-minZ;}
    }
}

from pathlib import Path

SOURCE = Path('app/src/main/java/ir/chobyar/sketch/DirectModelCadCanvasView.java')
TEST = Path('app/src/androidTest/java/ir/chobyar/sketch/DirectFinishInstrumentationTest.java')

text = SOURCE.read_text(encoding='utf-8')
marker = "    // ------------------------------------------------------------------\n    // Direct feature history\n"
block = r'''    /** Deterministic non-modal 3D finishing entry for command/tests. */
    public String applyAllFillet(float radiusMm) {
        if (!(radiusMm > 0f)) return "شعاع Fillet باید بزرگ‌تر از صفر باشد";
        Object body = selectedBody();
        if (body == null) return "اول یک Body را انتخاب کن";
        return recordAndApply(body, new DirectOp(EditKind.ALL_FILLET, radiusMm, 0, null));
    }

    /** Deterministic non-modal 3D finishing entry for command/tests. */
    public String applyAllChamfer(float distanceMm) {
        if (!(distanceMm > 0f)) return "فاصله Chamfer باید بزرگ‌تر از صفر باشد";
        Object body = selectedBody();
        if (body == null) return "اول یک Body را انتخاب کن";
        return recordAndApply(body, new DirectOp(EditKind.ALL_CHAMFER, distanceMm, 0, null));
    }

    @Override
    public String executeCommand(String raw) {
        if (raw != null) {
            String s = normalizeDigits(raw).trim().replace(',', ' ');
            if (!s.isEmpty()) {
                String[] a = s.split("\\s+");
                String op = a[0].toUpperCase(Locale.US);
                boolean fillet = "FILLET3D".equals(op) || "FILLETALL".equals(op);
                boolean chamfer = "CHAMFER3D".equals(op) || "CHAMFERALL".equals(op);
                if (fillet || chamfer) {
                    if (a.length != 2) return op + " — یک اندازه بر حسب mm لازم است؛ مثال: " + op + " 5";
                    try {
                        float mm = parseLengthMm(a[1]);
                        return fillet ? applyAllFillet(mm) : applyAllChamfer(mm);
                    } catch (Exception e) {
                        return "اندازه Fillet/Chamfer درست نیست";
                    }
                }
            }
        }
        return super.executeCommand(raw);
    }

'''
if 'applyAllFillet(float radiusMm)' not in text:
    if marker not in text:
        raise SystemExit('Direct feature history anchor not found')
    text = text.replace(marker, block + marker, 1)
    SOURCE.write_text(text, encoding='utf-8')

TEST.parent.mkdir(parents=True, exist_ok=True)
TEST.write_text(r'''package ir.chobyar.sketch;

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

/** Exact geometric contracts for production Prism Fillet/Chamfer. */
@RunWith(AndroidJUnit4.class)
public final class DirectFinishInstrumentationTest {
    private static final String TAG = "DirectFinishContract";
    private static final float BOUND_EPS = .08f;

    @Test
    public void filletAllTenMillimetersChangesRealCsgAndSurvivesHistoryRebuild() {
        Instrumentation inst = InstrumentationRegistry.getInstrumentation();
        try (ActivityScenario<ChobYarActivity> scenario = ActivityScenario.launch(ChobYarActivity.class)) {
            inst.waitForIdleSync();
            scenario.onActivity(activity -> {
                Shapr3DGuideCadCanvasView c = box(activity);
                SolidCSG before = selectedCsg(c);
                assertEquals("Base box must have six faces", 6, before.polygons().size());

                String result = c.executeCommand("FILLET3D 10");
                assertTrue("FILLET3D rejected: " + result, result.contains("Fillet All") && result.contains("10"));
                assertEquals("Fillet must keep one Body", 1, c.bodyCount());

                SolidCSG after = selectedCsg(c);
                Bounds b = bounds(after);
                float[] e = sorted3(b.dx(), b.dy(), b.dz());
                assertNear("Fillet thickness", 20f, e[0], BOUND_EPS);
                assertNear("Fillet width", 80f, e[1], BOUND_EPS);
                assertNear("Fillet length", 100f, e[2], BOUND_EPS);
                assertTrue("Fillet must add real topology", after.polygons().size() > before.polygons().size());

                double expectedArea = 100d * 80d - (4d - Math.PI) * 10d * 10d;
                double expectedVolume = expectedArea * 20d;
                double actualVolume = volume(after);
                assertNear("Fillet volume", (float) expectedVolume, (float) actualVolume, 55f);

                String rebuilt = c.rebuildHistory();
                assertTrue("Fillet Direct History not replayed: " + rebuilt, rebuilt.contains("Direct 1") && !rebuilt.contains("خطا"));
                SolidCSG replay = selectedCsg(c);
                double replayVolume = volume(replay);
                assertNear("Fillet volume after History rebuild", (float) actualVolume, (float) replayVolume, 1.5f);
                assertEquals("Fillet face count changed after rebuild", after.polygons().size(), replay.polygons().size());

                Log.i(TAG, "FILLET3D_RESULT bodyCount=1 extents=20x80x100 radius=10 volume="
                        + fmt(actualVolume) + " expected=" + fmt(expectedVolume)
                        + " persistent=true faces=" + replay.polygons().size());
            });
        }
    }

    @Test
    public void chamferAllTenMillimetersCreatesExactEightCornerProfileAndPersists() {
        Instrumentation inst = InstrumentationRegistry.getInstrumentation();
        try (ActivityScenario<ChobYarActivity> scenario = ActivityScenario.launch(ChobYarActivity.class)) {
            inst.waitForIdleSync();
            scenario.onActivity(activity -> {
                Shapr3DGuideCadCanvasView c = box(activity);
                SolidCSG before = selectedCsg(c);

                String result = c.executeCommand("CHAMFER3D 10");
                assertTrue("CHAMFER3D rejected: " + result, result.contains("Chamfer All") && result.contains("10"));
                assertEquals("Chamfer must keep one Body", 1, c.bodyCount());

                SolidCSG after = selectedCsg(c);
                Bounds b = bounds(after);
                float[] e = sorted3(b.dx(), b.dy(), b.dz());
                assertNear("Chamfer thickness", 20f, e[0], BOUND_EPS);
                assertNear("Chamfer width", 80f, e[1], BOUND_EPS);
                assertNear("Chamfer length", 100f, e[2], BOUND_EPS);
                assertTrue("Chamfer must add topology", after.polygons().size() > before.polygons().size());

                double expectedVolume = 7800d * 20d;
                double actualVolume = volume(after);
                assertNear("Chamfer exact volume", (float) expectedVolume, (float) actualVolume, 1.0f);

                String rebuilt = c.rebuildHistory();
                assertTrue("Chamfer Direct History not replayed: " + rebuilt, rebuilt.contains("Direct 1") && !rebuilt.contains("خطا"));
                SolidCSG replay = selectedCsg(c);
                assertNear("Chamfer volume after History rebuild", (float) actualVolume, (float) volume(replay), 0.5f);
                assertEquals("Chamfer face count changed after rebuild", after.polygons().size(), replay.polygons().size());

                Log.i(TAG, "CHAMFER3D_RESULT bodyCount=1 extents=20x80x100 distance=10 volume="
                        + fmt(actualVolume) + " expected=156000.00 persistent=true faces=" + replay.polygons().size());
            });
        }
    }

    private static Shapr3DGuideCadCanvasView box(ChobYarActivity activity) {
        Shapr3DGuideCadCanvasView c = canvas(activity);
        c.clearAll();
        String rect = c.executeCommand("RECT 0 0 100 80");
        assertTrue("RECT rejected: " + rect, rect.contains("مستطیل"));
        String ext = c.executeCommand("EXTRUDE 20");
        assertTrue("EXTRUDE rejected: " + ext, ext.contains("ساخته شد"));
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
            assertNotNull("No selected Body", body);
            Field f = body.getClass().getDeclaredField("csg");
            f.setAccessible(true);
            SolidCSG csg = (SolidCSG) f.get(body);
            assertNotNull("Selected Body has no CSG", csg);
            return csg;
        } catch (Exception e) {
            throw new AssertionError("Cannot inspect selected Body", e);
        }
    }

    private static double volume(SolidCSG csg) {
        double sixV = 0d;
        for (SolidCSG.Polygon p : csg.polygons()) {
            if (p.vertices.size() < 3) continue;
            Geometry3D.Vec3 a = p.vertices.get(0).pos;
            for (int i = 1; i < p.vertices.size() - 1; i++) {
                Geometry3D.Vec3 b = p.vertices.get(i).pos;
                Geometry3D.Vec3 d = p.vertices.get(i + 1).pos;
                sixV += a.dot(b.cross(d));
            }
        }
        return Math.abs(sixV / 6d);
    }

    private static Bounds bounds(SolidCSG csg) {
        Bounds b = new Bounds();
        for (SolidCSG.Polygon p : csg.polygons())
            for (SolidCSG.Vertex v : p.vertices) b.add(v.pos.x, v.pos.y, v.pos.z);
        assertTrue("Body bounds empty", b.seen);
        return b;
    }

    private static float[] sorted3(float a, float b, float c) {
        float[] x = {Math.abs(a), Math.abs(b), Math.abs(c)};
        java.util.Arrays.sort(x);
        return x;
    }

    private static void assertNear(String label, float expected, float actual, float eps) {
        assertTrue(label + " expected=" + expected + " actual=" + actual,
                Math.abs(expected - actual) <= eps);
    }

    private static String fmt(double v) {
        return String.format(java.util.Locale.US, "%.2f", v);
    }

    private static final class Bounds {
        boolean seen;
        float minX=Float.POSITIVE_INFINITY,minY=Float.POSITIVE_INFINITY,minZ=Float.POSITIVE_INFINITY;
        float maxX=Float.NEGATIVE_INFINITY,maxY=Float.NEGATIVE_INFINITY,maxZ=Float.NEGATIVE_INFINITY;
        void add(float x,float y,float z){
            seen=true;minX=Math.min(minX,x);minY=Math.min(minY,y);minZ=Math.min(minZ,z);
            maxX=Math.max(maxX,x);maxY=Math.max(maxY,y);maxZ=Math.max(maxZ,z);
        }
        float dx(){return maxX-minX;} float dy(){return maxY-minY;} float dz(){return maxZ-minZ;}
    }
}
''', encoding='utf-8')

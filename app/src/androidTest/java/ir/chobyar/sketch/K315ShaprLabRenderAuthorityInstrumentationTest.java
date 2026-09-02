package ir.chobyar.sketch;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Field;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;

import ir.chobyar.sketch.core.SketchConstraint;
import ir.chobyar.sketch.core.SketchEntity;
import ir.chobyar.sketch.core.SketchGeometry;

/** K3.15 fence: ShaprLab rendering is presentation-only and identity relations are never authority. */
@RunWith(AndroidJUnit4.class)
public final class K315ShaprLabRenderAuthorityInstrumentationTest {

    @Test public void directShaprLabMustFailClosedWithoutCreatingIdentityRelationTruth() throws Exception {
        onMain(() -> {
            ShaprLabCanvasView lab = new ShaprLabCanvasView(context());
            lab.layout(0, 0, 1200, 900);

            CadCanvasView.Entity a = line(lab, "LINE 80 200 180 200");
            CadCanvasView.Entity b = line(lab, "LINE 80 300 230 300");
            select(lab, a, b);
            assertTrue(lab.applyEqualConstraint().contains("unavailable"));
            assertEquals(0, relationCount(lab, "equalRelations"));

            select(lab, a, b);
            assertTrue(lab.applyMidpointConstraint().contains("unavailable"));
            assertEquals(0, relationCount(lab, "midpointRelations"));

            CadCanvasView.Entity circle = circle(lab, "CIRCLE 420 250 45");
            select(lab, a, circle);
            assertTrue(lab.applyTangentConstraint().contains("unavailable"));
            assertEquals(0, relationCount(lab, "tangentRelations"));

            CadCanvasView.Entity axis = line(lab, "LINE 300 120 300 400");
            select(lab, a, b, axis);
            assertTrue(lab.applySymmetryConstraint().contains("unavailable"));
            assertEquals(0, relationCount(lab, "symmetryRelations"));
            return true;
        });
    }

    @Test public void drawMustNotRepairPerturbedProjectionOrMutateModelHistoryIdsOrPersistence() throws Exception {
        onMain(() -> {
            K33MirroredCadCanvasView cad = cad();
            CadCanvasView.LineEntity driven = (CadCanvasView.LineEntity) line(cad, "LINE 80 240 150 280");
            CadCanvasView.Entity host = line(cad, "LINE 260 300 480 300");
            String drivenId = driven.stableId();
            String hostId = host.stableId();
            select(cad, driven, host);
            assertEquals("Midpoint applied", cad.applyMidpointConstraint());
            cad.requireSketchMirrorParity();

            long transitionsBefore = cad.sketchAuthorityTransitionCount();
            int constraintsBefore = cad.sketchConstraintCount();
            List<String> entityIdsBefore = entityIds(cad);
            List<String> constraintIdsBefore = constraintIds(cad);
            double[] modelBefore = modelLineSignature(cad, drivenId);

            driven.x1 += 61f;
            driven.y1 -= 37f;
            driven.x2 += 29f;
            driven.y2 += 43f;
            float[] drift = legacyLineSignature(driven);
            String persistenceBeforeDraw = cad.exportSketchProjectState();

            Bitmap bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888);
            cad.onDraw(new Canvas(bitmap));

            assertLegacyLineSame(drift, driven);
            assertLineSame(modelBefore, modelLineSignature(cad, drivenId));
            assertEquals(transitionsBefore, cad.sketchAuthorityTransitionCount());
            assertEquals(constraintsBefore, cad.sketchConstraintCount());
            assertEquals(entityIdsBefore, entityIds(cad));
            assertEquals(constraintIdsBefore, constraintIds(cad));
            assertEquals(persistenceBeforeDraw, cad.exportSketchProjectState());
            assertEquals(0, relationCount(cad, "midpointRelations"));
            assertTrue(hasConstraint(cad, SketchConstraint.Kind.MIDPOINT, drivenId, hostId));
            return true;
        });
    }

    @Test public void actionMountConstraintsMustUseExistingModelAuthorityOnly() throws Exception {
        onMain(() -> {
            K33MirroredCadCanvasView cad = cad();
            assertEquals("Action Mount LAB created", cad.buildActionMountLab());
            assertTrue(countKind(cad, SketchConstraint.Kind.EQUAL) > 0);
            assertTrue(countKind(cad, SketchConstraint.Kind.MIDPOINT) > 0);
            assertTrue(countKind(cad, SketchConstraint.Kind.TANGENT) > 0);
            assertTrue(countKind(cad, SketchConstraint.Kind.SYMMETRY) > 0);
            assertEquals(0, relationCount(cad, "equalRelations"));
            assertEquals(0, relationCount(cad, "midpointRelations"));
            assertEquals(0, relationCount(cad, "tangentRelations"));
            assertEquals(0, relationCount(cad, "symmetryRelations"));

            String saved = cad.exportSketchProjectState();
            K33MirroredCadCanvasView restored = cad();
            String result = restored.importSketchProjectState(saved);
            assertFalse(result.contains("could not be restored"));
            assertTrue(countKind(restored, SketchConstraint.Kind.EQUAL) > 0);
            assertTrue(countKind(restored, SketchConstraint.Kind.MIDPOINT) > 0);
            assertTrue(countKind(restored, SketchConstraint.Kind.TANGENT) > 0);
            assertTrue(countKind(restored, SketchConstraint.Kind.SYMMETRY) > 0);
            return true;
        });
    }

    private static Context context() { return ApplicationProvider.getApplicationContext(); }

    private static K33MirroredCadCanvasView cad() {
        K33MirroredCadCanvasView cad = new K33MirroredCadCanvasView(context());
        cad.layout(0, 0, 1400, 1000);
        return cad;
    }

    private static CadCanvasView.Entity line(ChobYarShaprCanvasView cad, String command) {
        cad.executeCommand(command);
        return cad.selected;
    }

    private static CadCanvasView.Entity circle(ChobYarShaprCanvasView cad, String command) {
        cad.executeCommand(command);
        return cad.selected;
    }

    private static void select(ChobYarShaprCanvasView cad, CadCanvasView.Entity... values) {
        cad.selectedObjects.clear();
        for (CadCanvasView.Entity value : values) cad.selectedObjects.add(value);
        cad.selected = values.length == 1 ? values[0] : null;
    }

    private static int relationCount(Object cad, String name) {
        try {
            Field field = ShaprLabCanvasView.class.getDeclaredField(name);
            field.setAccessible(true);
            Object value = field.get(cad);
            return value instanceof Collection ? ((Collection<?>) value).size() : -1;
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Cannot inspect legacy relation store " + name, e);
        }
    }

    private static int countKind(K33MirroredCadCanvasView cad, SketchConstraint.Kind kind) {
        int count = 0;
        for (SketchConstraint c : cad.sketchConstraints()) if (c.kind == kind) count++;
        return count;
    }

    private static List<String> entityIds(K33MirroredCadCanvasView cad) {
        java.util.ArrayList<String> ids = new java.util.ArrayList<>();
        for (SketchEntity entity : cad.sketchMirrorEntities()) ids.add(entity.id());
        return ids;
    }

    private static List<String> constraintIds(K33MirroredCadCanvasView cad) {
        java.util.ArrayList<String> ids = new java.util.ArrayList<>();
        for (SketchConstraint constraint : cad.sketchConstraints()) ids.add(constraint.id);
        return ids;
    }

    private static boolean hasConstraint(K33MirroredCadCanvasView cad, SketchConstraint.Kind kind,
                                         String primary, String secondary) {
        for (SketchConstraint c : cad.sketchConstraints()) {
            if (c.kind == kind && primary.equals(c.primaryEntityId)
                    && secondary.equals(c.secondaryEntityId)) return true;
        }
        return false;
    }

    private static double[] modelLineSignature(K33MirroredCadCanvasView cad, String id) {
        for (SketchEntity entity : cad.sketchMirrorEntities()) {
            if (id.equals(entity.id())) {
                SketchGeometry.Line line = (SketchGeometry.Line) entity;
                return new double[]{line.a.xMm, line.a.yMm, line.b.xMm, line.b.yMm};
            }
        }
        throw new AssertionError("Missing model line " + id);
    }

    private static float[] legacyLineSignature(CadCanvasView.LineEntity line) {
        return new float[]{line.x1, line.y1, line.x2, line.y2};
    }

    private static void assertLegacyLineSame(float[] expected, CadCanvasView.LineEntity actual) {
        assertEquals(expected[0], actual.x1, 0f);
        assertEquals(expected[1], actual.y1, 0f);
        assertEquals(expected[2], actual.x2, 0f);
        assertEquals(expected[3], actual.y2, 0f);
    }

    private static void assertLineSame(double[] expected, double[] actual) {
        assertEquals(expected[0], actual[0], 0d);
        assertEquals(expected[1], actual[1], 0d);
        assertEquals(expected[2], actual[2], 0d);
        assertEquals(expected[3], actual[3], 0d);
    }

    private static <T> T onMain(Callable<T> callable) throws Exception {
        FutureTask<T> task = new FutureTask<>(callable);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(task);
        return task.get();
    }
}

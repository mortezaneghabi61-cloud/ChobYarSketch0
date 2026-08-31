package ir.chobyar.sketch;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;

import ir.chobyar.sketch.core.SketchConstraint;
import ir.chobyar.sketch.core.SketchEntity;
import ir.chobyar.sketch.core.SketchGeometry;

/**
 * K3.6d production fence: MIDPOINT is model-owned and rendering must never
 * repair stale legacy geometry or mutate authoritative SketchDocument state.
 */
@RunWith(AndroidJUnit4.class)
public final class K36dProductionMidpointDrawAuthorityInstrumentationTest {
    private static final double EPS = 1.0e-7;

    @Test public void drawNeverEnforcesLegacyMidpointOrMutatesModelAuthority() throws Exception {
        onMain(() -> {
            K33MirroredCadCanvasView cad = cad();
            cad.executeCommand("LINE 40 20 40 60");
            CadCanvasView.LineEntity drivenLegacy = (CadCanvasView.LineEntity) cad.selected;
            String drivenId = drivenLegacy.stableId();

            cad.executeCommand("LINE 0 0 100 0");
            CadCanvasView.LineEntity hostLegacy = (CadCanvasView.LineEntity) cad.selected;
            String hostId = hostLegacy.stableId();

            cad.executeCommand("LINE 140 10 180 30");
            CadCanvasView.LineEntity freeLegacy = (CadCanvasView.LineEntity) cad.selected;
            String freeId = freeLegacy.stableId();

            selectTwo(cad, drivenLegacy, hostLegacy);
            String result = cad.applyMidpointConstraint();
            assertTrue("Production MIDPOINT command must report application: " + result,
                    result.contains("Midpoint"));

            SketchGeometry.Line drivenBeforeDraw = copy(line(cad, drivenId));
            SketchGeometry.Line hostBeforeDraw = copy(line(cad, hostId));
            SketchGeometry.Line freeBeforeDraw = copy(line(cad, freeId));
            List<ConstraintSignature> constraintsBeforeDraw = signatures(cad.sketchConstraints());
            boolean canUndoBeforeDraw = cad.sketchAuthorityCanUndo();
            boolean canRedoBeforeDraw = cad.sketchAuthorityCanRedo();
            long transitionsBeforeDraw = cad.sketchAuthorityTransitionCount();

            // Deliberately drift only the legacy/View projection after constraint apply.
            // Rendering must not act as a solver or repair boundary for MIDPOINT.
            drivenLegacy.x1 = 333f;
            drivenLegacy.y1 = 334f;
            drivenLegacy.x2 = 355f;
            drivenLegacy.y2 = 366f;
            freeLegacy.x1 = 411f;
            freeLegacy.y1 = 412f;
            freeLegacy.x2 = 413f;
            freeLegacy.y2 = 414f;

            Bitmap bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888);
            cad.onDraw(new Canvas(bitmap));

            // RED on the audited main: ShaprLabCanvasView.onDraw() currently calls
            // enforceRelations(), whose MidpointRelation.enforce() rewrites x1/y1.
            assertEquals("onDraw must not repair MIDPOINT-owned legacy endpoint X", 333f, drivenLegacy.x1, 0f);
            assertEquals("onDraw must not repair MIDPOINT-owned legacy endpoint Y", 334f, drivenLegacy.y1, 0f);
            assertEquals("onDraw must preserve the free endpoint X", 355f, drivenLegacy.x2, 0f);
            assertEquals("onDraw must preserve the free endpoint Y", 366f, drivenLegacy.y2, 0f);

            assertEquals(411f, freeLegacy.x1, 0f);
            assertEquals(412f, freeLegacy.y1, 0f);
            assertEquals(413f, freeLegacy.x2, 0f);
            assertEquals(414f, freeLegacy.y2, 0f);

            assertLineSame(drivenBeforeDraw, line(cad, drivenId));
            assertLineSame(hostBeforeDraw, line(cad, hostId));
            assertLineSame(freeBeforeDraw, line(cad, freeId));
            assertEquals(constraintsBeforeDraw, signatures(cad.sketchConstraints()));
            assertEquals(canUndoBeforeDraw, cad.sketchAuthorityCanUndo());
            assertEquals(canRedoBeforeDraw, cad.sketchAuthorityCanRedo());
            assertEquals(transitionsBeforeDraw, cad.sketchAuthorityTransitionCount());

            SketchConstraint midpoint = singleMidpoint(cad, drivenId, hostId);
            assertEquals(0, midpoint.primaryPointIndex);
            assertEquals(drivenId, midpoint.primaryEntityId);
            assertEquals(hostId, midpoint.secondaryEntityId);
            assertEquals("Production MIDPOINT must not populate the inherited identity relation store",
                    0, legacyMidpointTruthCount(cad));

            assertEquals(drivenId, drivenLegacy.stableId());
            assertEquals(hostId, hostLegacy.stableId());
            assertEquals(freeId, freeLegacy.stableId());
            assertFalse("Draw must not manufacture redo history", cad.sketchAuthorityCanRedo());
            return true;
        });
    }

    private static K33MirroredCadCanvasView cad() {
        Context context = ApplicationProvider.getApplicationContext();
        K33MirroredCadCanvasView cad = new K33MirroredCadCanvasView(context);
        cad.layout(0, 0, 1200, 900);
        return cad;
    }

    private static void selectTwo(K33MirroredCadCanvasView cad,
                                  CadCanvasView.Entity first, CadCanvasView.Entity second) {
        cad.selectedObjects.clear();
        cad.selectedObjects.add(first);
        cad.selectedObjects.add(second);
        cad.selected = null;
    }

    private static SketchGeometry.Line line(K33MirroredCadCanvasView cad, String id) {
        for (SketchEntity entity : cad.sketchMirrorEntities()) {
            if (id.equals(entity.id())) return (SketchGeometry.Line) entity;
        }
        throw new AssertionError("Missing model line " + id);
    }

    private static SketchGeometry.Line copy(SketchGeometry.Line line) {
        return new SketchGeometry.Line(line.id(),
                new SketchGeometry.Point(line.a.xMm, line.a.yMm),
                new SketchGeometry.Point(line.b.xMm, line.b.yMm));
    }

    private static void assertLineSame(SketchGeometry.Line expected, SketchGeometry.Line actual) {
        assertEquals(expected.a.xMm, actual.a.xMm, EPS);
        assertEquals(expected.a.yMm, actual.a.yMm, EPS);
        assertEquals(expected.b.xMm, actual.b.xMm, EPS);
        assertEquals(expected.b.yMm, actual.b.yMm, EPS);
    }

    private static SketchConstraint singleMidpoint(K33MirroredCadCanvasView cad,
                                                    String drivenId, String hostId) {
        SketchConstraint found = null;
        for (SketchConstraint constraint : cad.sketchConstraints()) {
            if (constraint.kind != SketchConstraint.Kind.MIDPOINT) continue;
            if (!drivenId.equals(constraint.primaryEntityId)) continue;
            if (!hostId.equals(constraint.secondaryEntityId)) continue;
            if (found != null) throw new AssertionError("Duplicate model MIDPOINT constraints");
            found = constraint;
        }
        if (found == null) throw new AssertionError("Production MIDPOINT did not create model-owned constraint");
        return found;
    }

    private static int legacyMidpointTruthCount(K33MirroredCadCanvasView cad) {
        try {
            Field field = ShaprLabCanvasView.class.getDeclaredField("midpointRelations");
            field.setAccessible(true);
            Object value = field.get(cad);
            return value instanceof List ? ((List<?>) value).size() : -1000;
        } catch (Exception e) {
            throw new AssertionError("Cannot inspect legacy MIDPOINT relation store", e);
        }
    }

    private static List<ConstraintSignature> signatures(List<SketchConstraint> values) {
        ArrayList<ConstraintSignature> out = new ArrayList<>();
        for (SketchConstraint c : values) out.add(new ConstraintSignature(c));
        return out;
    }

    private static final class ConstraintSignature {
        final String id;
        final SketchConstraint.Kind kind;
        final String primary;
        final int primaryPoint;
        final String secondary;
        final int secondaryPoint;
        final double value;
        final boolean driving;

        ConstraintSignature(SketchConstraint c) {
            id = c.id;
            kind = c.kind;
            primary = c.primaryEntityId;
            primaryPoint = c.primaryPointIndex;
            secondary = c.secondaryEntityId;
            secondaryPoint = c.secondaryPointIndex;
            value = c.value;
            driving = c.driving;
        }

        @Override public boolean equals(Object value) {
            if (!(value instanceof ConstraintSignature)) return false;
            ConstraintSignature other = (ConstraintSignature) value;
            return java.util.Objects.equals(id, other.id)
                    && kind == other.kind
                    && java.util.Objects.equals(primary, other.primary)
                    && primaryPoint == other.primaryPoint
                    && java.util.Objects.equals(secondary, other.secondary)
                    && secondaryPoint == other.secondaryPoint
                    && Double.doubleToLongBits(this.value) == Double.doubleToLongBits(other.value)
                    && driving == other.driving;
        }

        @Override public int hashCode() {
            return java.util.Objects.hash(id, kind, primary, primaryPoint, secondary,
                    secondaryPoint, Double.doubleToLongBits(value), driving);
        }
    }

    private static <T> T onMain(Callable<T> callable) throws Exception {
        FutureTask<T> task = new FutureTask<>(callable);
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().runOnMainSync(task);
        return task.get();
    }
}

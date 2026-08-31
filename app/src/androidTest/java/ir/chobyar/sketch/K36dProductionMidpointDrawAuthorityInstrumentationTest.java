package ir.chobyar.sketch;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;

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
            assertEquals("Midpoint applied", result);

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

    @Test public void midpointUndoRedoSaveOpenAndHostDeletePreserveStableRelationship() throws Exception {
        onMain(() -> {
            K33MirroredCadCanvasView cad = cad();
            cad.executeCommand("LINE 40 20 40 60");
            CadCanvasView.LineEntity drivenLegacy = (CadCanvasView.LineEntity) cad.selected;
            String drivenId = drivenLegacy.stableId();
            SketchGeometry.Line originalDriven = copy(line(cad, drivenId));
            cad.executeCommand("LINE 0 0 100 0");
            CadCanvasView.LineEntity hostLegacy = (CadCanvasView.LineEntity) cad.selected;
            String hostId = hostLegacy.stableId();

            selectTwo(cad, drivenLegacy, hostLegacy);
            assertEquals("Midpoint applied", cad.applyMidpointConstraint());
            cad.requireSketchMirrorParity();
            SketchConstraint applied = singleMidpoint(cad, drivenId, hostId);
            String constraintId = applied.id;
            assertEquals(0, applied.primaryPointIndex);
            assertMidpointGeometry(cad, drivenId, hostId, 0);
            assertEquals(0, legacyMidpointTruthCount(cad));

            cad.undo();
            cad.requireSketchMirrorParity();
            assertNull(midpointOrNull(cad, drivenId, hostId));
            assertLineSame(originalDriven, line(cad, drivenId));
            assertNotNull(legacyEntityFor(cad, drivenId));
            assertNotNull(legacyEntityFor(cad, hostId));

            assertTrue(cad.redoSketch());
            cad.requireSketchMirrorParity();
            SketchConstraint redone = singleMidpoint(cad, drivenId, hostId);
            assertEquals("Redo must restore the same stable relationship id", constraintId, redone.id);
            assertEquals(0, redone.primaryPointIndex);
            assertMidpointGeometry(cad, drivenId, hostId, 0);
            assertEquals(0, legacyMidpointTruthCount(cad));

            String saved = cad.exportSketchProjectState();
            K33MirroredCadCanvasView reopened = cad();
            assertTrue(reopened.importSketchProjectState(saved).length() > 0);
            reopened.requireSketchMirrorParity();
            SketchConstraint restored = singleMidpoint(reopened, drivenId, hostId);
            assertEquals(constraintId, restored.id);
            assertEquals(0, restored.primaryPointIndex);
            assertMidpointGeometry(reopened, drivenId, hostId, 0);
            assertEquals(0, legacyMidpointTruthCount(reopened));
            assertFalse("Open must reset session Undo", reopened.sketchAuthorityCanUndo());
            assertFalse("Open must reset session Redo", reopened.sketchAuthorityCanRedo());

            CadCanvasView.Entity reopenedHost = legacyEntityFor(reopened, hostId);
            assertNotNull(reopenedHost);
            selectOne(reopened, reopenedHost);
            reopened.deleteSelected();
            reopened.requireSketchMirrorParity();
            assertNull(modelEntityOrNull(reopened, hostId));
            assertNull(midpointOrNull(reopened, drivenId, hostId));

            reopened.undo();
            reopened.requireSketchMirrorParity();
            assertNotNull(modelEntityOrNull(reopened, hostId));
            SketchConstraint restoredAfterUndo = singleMidpoint(reopened, drivenId, hostId);
            assertEquals(constraintId, restoredAfterUndo.id);
            assertEquals(0, restoredAfterUndo.primaryPointIndex);
            assertMidpointGeometry(reopened, drivenId, hostId, 0);
            return true;
        });
    }

    @Test public void compatiblePointFixedMidpointKeepsAnchorAndLeavesFreeDofRotatable() throws Exception {
        onMain(() -> {
            K33MirroredCadCanvasView cad = cad();
            cad.executeCommand("LINE 50 0 80 20");
            CadCanvasView.LineEntity drivenLegacy = (CadCanvasView.LineEntity) cad.selected;
            String drivenId = drivenLegacy.stableId();
            cad.executeCommand("LINE 0 0 100 0");
            CadCanvasView.LineEntity hostLegacy = (CadCanvasView.LineEntity) cad.selected;
            String hostId = hostLegacy.stableId();

            lockPoint(cad, drivenLegacy, drivenId, 0);
            SketchGeometry.Line beforeApply = copy(line(cad, drivenId));
            selectTwo(cad, drivenLegacy, hostLegacy);
            assertEquals("Midpoint applied", cad.applyMidpointConstraint());
            cad.requireSketchMirrorParity();
            assertTrue(hasPointFixed(cad, drivenId, 0));
            assertFalse(hasPointFixed(cad, drivenId, 1));
            assertMidpointGeometry(cad, drivenId, hostId, 0);
            assertEquals(beforeApply.a.xMm, line(cad, drivenId).a.xMm, EPS);
            assertEquals(beforeApply.a.yMm, line(cad, drivenId).a.yMm, EPS);
            assertEquals(0, legacyMidpointTruthCount(cad));

            selectOne(cad, drivenLegacy);
            SketchGeometry.Line beforeRotate = copy(line(cad, drivenId));
            String rotate = cad.rotateSelected(35f);
            assertEquals("Rotation applied", rotate);
            cad.requireSketchMirrorParity();
            SketchGeometry.Line rotated = line(cad, drivenId);
            assertEquals(beforeRotate.a.xMm, rotated.a.xMm, EPS);
            assertEquals(beforeRotate.a.yMm, rotated.a.yMm, EPS);
            assertTrue("MIDPOINT must leave the non-driven endpoint as a free rotational DOF",
                    pointChanged(beforeRotate.b, rotated.b));
            assertMidpointGeometry(cad, drivenId, hostId, 0);
            assertTrue(hasPointFixed(cad, drivenId, 0));
            assertFalse(hasPointFixed(cad, drivenId, 1));
            return true;
        });
    }

    @Test public void incompatiblePointFixedMidpointFailsAtomicallyWithoutLegacyTruth() throws Exception {
        onMain(() -> {
            K33MirroredCadCanvasView cad = cad();
            cad.executeCommand("LINE 10 10 30 25");
            CadCanvasView.LineEntity drivenLegacy = (CadCanvasView.LineEntity) cad.selected;
            String drivenId = drivenLegacy.stableId();
            cad.executeCommand("LINE 0 0 100 0");
            CadCanvasView.LineEntity hostLegacy = (CadCanvasView.LineEntity) cad.selected;
            String hostId = hostLegacy.stableId();

            lockPoint(cad, drivenLegacy, drivenId, 0);
            SketchGeometry.Line drivenBefore = copy(line(cad, drivenId));
            SketchGeometry.Line hostBefore = copy(line(cad, hostId));
            List<ConstraintSignature> beforeConstraints = signatures(cad.sketchConstraints());
            boolean undoBefore = cad.sketchAuthorityCanUndo();

            selectTwo(cad, drivenLegacy, hostLegacy);
            String result = cad.applyMidpointConstraint();
            assertEquals("Constraint could not be solved; geometry was left unchanged", result);
            cad.requireSketchMirrorParity();
            assertLineSame(drivenBefore, line(cad, drivenId));
            assertLineSame(hostBefore, line(cad, hostId));
            assertEquals(beforeConstraints, signatures(cad.sketchConstraints()));
            assertNull(midpointOrNull(cad, drivenId, hostId));
            assertTrue(hasPointFixed(cad, drivenId, 0));
            assertFalse(hasPointFixed(cad, drivenId, 1));
            assertEquals(undoBefore, cad.sketchAuthorityCanUndo());
            assertEquals(0, legacyMidpointTruthCount(cad));
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

    private static void selectOne(K33MirroredCadCanvasView cad, CadCanvasView.Entity entity) {
        cad.selectedObjects.clear();
        cad.selectedObjects.add(entity);
        cad.selected = entity;
    }

    private static void lockPoint(K33MirroredCadCanvasView cad, CadCanvasView.LineEntity legacy,
                                  String id, int pointIndex) {
        selectOne(cad, legacy);
        cad.setTool(CadCanvasView.TOOL_SELECT);
        SketchGeometry.Line model = line(cad, id);
        SketchGeometry.Point point = pointIndex == 0 ? model.a : model.b;
        tap(cad, screenX(cad, point.xMm), screenY(cad, point.yMm));
        assertEquals(id, cad.pointLockTargetEntityId());
        assertEquals(pointIndex, cad.pointLockTargetPointIndex());
        assertEquals("Point locked", cad.toggleSelectedLock());
        assertTrue(hasPointFixed(cad, id, pointIndex));
    }

    private static void tap(K33MirroredCadCanvasView cad, float x, float y) {
        long down = SystemClock.uptimeMillis();
        MotionEvent d = MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, x, y, 0);
        d.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        try { cad.onTouchEvent(d); } finally { d.recycle(); }
        MotionEvent u = MotionEvent.obtain(down, down + 16L, MotionEvent.ACTION_UP, x, y, 0);
        u.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        try { cad.onTouchEvent(u); } finally { u.recycle(); }
    }

    private static float screenX(K33MirroredCadCanvasView cad, double xMm) {
        return (float) (xMm * 3.0 * cad.viewScale + cad.offsetX);
    }

    private static float screenY(K33MirroredCadCanvasView cad, double yMm) {
        return (float) (yMm * 3.0 * cad.viewScale + cad.offsetY);
    }

    private static boolean hasPointFixed(K33MirroredCadCanvasView cad, String id, int pointIndex) {
        for (SketchConstraint constraint : cad.sketchConstraints()) {
            if (constraint.kind == SketchConstraint.Kind.FIXED
                    && id.equals(constraint.primaryEntityId)
                    && constraint.fixesPoint()
                    && constraint.primaryPointIndex == pointIndex) return true;
        }
        return false;
    }

    private static SketchGeometry.Line line(K33MirroredCadCanvasView cad, String id) {
        return (SketchGeometry.Line) modelEntity(cad, id);
    }

    private static SketchEntity modelEntity(K33MirroredCadCanvasView cad, String id) {
        SketchEntity value = modelEntityOrNull(cad, id);
        if (value == null) throw new AssertionError("Missing model entity " + id);
        return value;
    }

    private static SketchEntity modelEntityOrNull(K33MirroredCadCanvasView cad, String id) {
        for (SketchEntity entity : cad.sketchMirrorEntities()) {
            if (id.equals(entity.id())) return entity;
        }
        return null;
    }

    private static CadCanvasView.Entity legacyEntityFor(K33MirroredCadCanvasView cad, String id) {
        for (CadCanvasView.Entity entity : cad.entities) {
            if (entity != null && id.equals(entity.stableId())) return entity;
        }
        return null;
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

    private static boolean pointChanged(SketchGeometry.Point a, SketchGeometry.Point b) {
        return Math.abs(a.xMm - b.xMm) > EPS || Math.abs(a.yMm - b.yMm) > EPS;
    }

    private static void assertMidpointGeometry(K33MirroredCadCanvasView cad,
                                               String drivenId, String hostId, int pointIndex) {
        SketchGeometry.Line driven = line(cad, drivenId);
        SketchGeometry.Line host = line(cad, hostId);
        SketchGeometry.Point point = pointIndex == 0 ? driven.a : driven.b;
        assertEquals((host.a.xMm + host.b.xMm) * 0.5d, point.xMm, EPS);
        assertEquals((host.a.yMm + host.b.yMm) * 0.5d, point.yMm, EPS);
    }

    private static SketchConstraint singleMidpoint(K33MirroredCadCanvasView cad,
                                                    String drivenId, String hostId) {
        SketchConstraint found = midpointOrNull(cad, drivenId, hostId);
        if (found == null) throw new AssertionError("Production MIDPOINT did not create model-owned constraint");
        return found;
    }

    private static SketchConstraint midpointOrNull(K33MirroredCadCanvasView cad,
                                                    String drivenId, String hostId) {
        SketchConstraint found = null;
        for (SketchConstraint constraint : cad.sketchConstraints()) {
            if (constraint.kind != SketchConstraint.Kind.MIDPOINT) continue;
            if (!drivenId.equals(constraint.primaryEntityId)) continue;
            if (!hostId.equals(constraint.secondaryEntityId)) continue;
            if (found != null) throw new AssertionError("Duplicate model MIDPOINT constraints");
            found = constraint;
        }
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

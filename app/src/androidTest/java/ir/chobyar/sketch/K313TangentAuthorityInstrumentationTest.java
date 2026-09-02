package ir.chobyar.sketch;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;

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

/** K3.13 API35 fence: Tangent semantic authority is model-owned and draw is presentation-only. */
@RunWith(AndroidJUnit4.class)
public final class K313TangentAuthorityInstrumentationTest {
    private static final double EPS = 1.0e-5;

    @Test public void lineCircleTangentMustBeModelOwnedWithStableIdsAndTransactionalHistory() throws Exception {
        onMain(() -> {
            K33MirroredCadCanvasView cad = cad();
            cad.executeCommand("CIRCLE 180 180 45");
            CadCanvasView.Entity circleLegacy = cad.selected;
            String circleId = circleLegacy.stableId();

            cad.executeCommand("LINE 225 180 315 95");
            CadCanvasView.Entity lineLegacy = cad.selected;
            String lineId = lineLegacy.stableId();
            selectTwo(cad, lineLegacy, circleLegacy);

            String result = cad.applyTangentConstraint();
            assertTrue("Tangent rejected: " + result, result.contains("Tangent"));

            assertEquals("Tangent must be represented by exactly one model-owned stable-ID constraint",
                    1, countTangent(cad, lineId, circleId));
            assertEquals("Migrated Tangent must not populate ShaprLab object-identity truth",
                    0, legacyTangentTruthCount(cad));
            cad.requireSketchMirrorParity();
            assertModelTangent(cad, lineId, circleId);
            assertTrue("Model-owned Tangent must be undoable", cad.sketchAuthorityCanUndo());

            cad.undo();
            cad.requireSketchMirrorParity();
            assertEquals(0, countTangent(cad, lineId, circleId));
            assertEquals(0, legacyTangentTruthCount(cad));

            assertTrue("Tangent redo must restore the model transaction", cad.redoSketch());
            cad.requireSketchMirrorParity();
            assertEquals(1, countTangent(cad, lineId, circleId));
            assertEquals(0, legacyTangentTruthCount(cad));
            assertModelTangent(cad, lineId, circleId);
            return true;
        });
    }

    @Test public void separatedLineCircleTangentPreservesLineLengthDirectionAndDoesNotForceContact() throws Exception {
        onMain(() -> {
            K33MirroredCadCanvasView cad = cad();
            cad.executeCommand("CIRCLE 180 180 45");
            CadCanvasView.Entity circleLegacy = cad.selected;
            String circleId = circleLegacy.stableId();

            cad.executeCommand("LINE 300 280 390 245");
            CadCanvasView.Entity lineLegacy = cad.selected;
            String lineId = lineLegacy.stableId();
            SketchGeometry.Line before = modelLine(cad, lineId);
            double beforeLength = before.lengthMm();
            double beforeUx = (before.b.xMm - before.a.xMm) / beforeLength;
            double beforeUy = (before.b.yMm - before.a.yMm) / beforeLength;
            selectTwo(cad, lineLegacy, circleLegacy);

            assertEquals("Tangent applied", cad.applyTangentConstraint());
            cad.requireSketchMirrorParity();
            SketchGeometry.Line solved = modelLine(cad, lineId);
            SketchGeometry.Circle circle = (SketchGeometry.Circle) modelEntity(cad, circleId);
            assertEquals("Free Tangent must preserve line length", beforeLength, solved.lengthMm(), EPS);
            assertEquals("Free Tangent must preserve line x direction", beforeUx,
                    (solved.b.xMm - solved.a.xMm) / solved.lengthMm(), EPS);
            assertEquals("Free Tangent must preserve line y direction", beforeUy,
                    (solved.b.yMm - solved.a.yMm) / solved.lengthMm(), EPS);
            assertModelTangent(cad, lineId, circleId);
            assertTrue("Tangent must not add hidden endpoint contact at endpoint A",
                    Math.abs(distance(solved.a, circle.center) - circle.radiusMm) > 1.0);
            assertTrue("Tangent must not add hidden endpoint contact at endpoint B",
                    Math.abs(distance(solved.b, circle.center) - circle.radiusMm) > 1.0);
            assertEquals(1, countTangent(cad, lineId, circleId));
            assertEquals(0, legacyTangentTruthCount(cad));
            return true;
        });
    }

    @Test public void drawMustNeverRepairLegacyTangentProjectionOrMutateModelAuthority() throws Exception {
        onMain(() -> {
            K33MirroredCadCanvasView cad = cad();
            cad.executeCommand("CIRCLE 180 180 45");
            CadCanvasView.Entity circleLegacy = cad.selected;
            String circleId = circleLegacy.stableId();

            cad.executeCommand("LINE 225 180 315 95");
            CadCanvasView.LineEntity lineLegacy = (CadCanvasView.LineEntity) cad.selected;
            String lineId = lineLegacy.stableId();
            selectTwo(cad, lineLegacy, circleLegacy);
            String result = cad.applyTangentConstraint();
            assertTrue("Tangent rejected: " + result, result.contains("Tangent"));

            double[] modelBefore = modelLineSignature(cad, lineId);
            int modelConstraintCountBefore = cad.sketchConstraintCount();
            long transitionsBefore = cad.sketchAuthorityTransitionCount();
            int legacyTangentCountBefore = legacyTangentTruthCount(cad);

            lineLegacy.x1 = 411f;
            lineLegacy.y1 = 337f;
            float[] drift = legacyLineSignature(lineLegacy);

            Bitmap bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888);
            cad.onDraw(new Canvas(bitmap));

            assertLegacyLineSame("onDraw must be presentation-only for Tangent", drift, lineLegacy);
            assertModelLineSame("onDraw must not mutate Tangent model geometry", modelBefore,
                    modelLineSignature(cad, lineId));
            assertEquals("onDraw must not add/remove semantic constraints",
                    modelConstraintCountBefore, cad.sketchConstraintCount());
            assertEquals("onDraw must not create authority history",
                    transitionsBefore, cad.sketchAuthorityTransitionCount());
            assertEquals("onDraw must not rewrite legacy relation ownership",
                    legacyTangentCountBefore, legacyTangentTruthCount(cad));
            assertEquals(lineId, lineLegacy.stableId());
            assertEquals(circleId, circleLegacy.stableId());
            return true;
        });
    }

    @Test public void arcTangentPersistsAcrossProjectRoundTripAndDeleteCascades() throws Exception {
        onMain(() -> {
            K33MirroredCadCanvasView cad = cad();
            cad.executeCommand("ARC 180 180 45 20 120");
            CadCanvasView.Entity arcLegacy = cad.selected;
            String arcId = arcLegacy.stableId();
            cad.executeCommand("LINE 270 180 225 205");
            CadCanvasView.Entity lineLegacy = cad.selected;
            String lineId = lineLegacy.stableId();
            selectTwo(cad, lineLegacy, arcLegacy);

            assertEquals("Tangent applied", cad.applyTangentConstraint());
            cad.requireSketchMirrorParity();
            assertEquals(1, countTangent(cad, lineId, arcId));
            assertModelTangent(cad, lineId, arcId);
            assertEquals(0, legacyTangentTruthCount(cad));
            String saved = cad.exportSketchProjectState();

            K33MirroredCadCanvasView restored = cad();
            restored.importSketchProjectState(saved);
            restored.requireSketchMirrorParity();
            assertEquals(1, countTangent(restored, lineId, arcId));
            assertModelTangent(restored, lineId, arcId);
            assertEquals(0, legacyTangentTruthCount(restored));

            CadCanvasView.Entity restoredLine = legacyEntity(restored, lineId);
            restored.selectedObjects.clear();
            restored.selectedObjects.add(restoredLine);
            restored.selected = restoredLine;
            restored.deleteSelected();
            restored.requireSketchMirrorParity();
            assertFalse(hasModelEntity(restored, lineId));
            assertTrue(hasModelEntity(restored, arcId));
            assertEquals("Deleting a referenced entity must cascade Tangent metadata",
                    0, countTangent(restored, lineId, arcId));
            return true;
        });
    }

    @Test public void pointFixedExternalPivotIsPreservedWhileFreeEndpointSolvesTangent() throws Exception {
        onMain(() -> {
            K33MirroredCadCanvasView cad = cad();
            cad.executeCommand("CIRCLE 180 180 45");
            CadCanvasView.Entity circleLegacy = cad.selected;
            String circleId = circleLegacy.stableId();
            cad.executeCommand("LINE 300 180 235 215");
            CadCanvasView.LineEntity lineLegacy = (CadCanvasView.LineEntity) cad.selected;
            String lineId = lineLegacy.stableId();

            lockPoint(cad, lineLegacy, lineId, 0);
            SketchGeometry.Line before = modelLine(cad, lineId);
            double fixedX = before.a.xMm;
            double fixedY = before.a.yMm;
            double beforeLength = before.lengthMm();
            selectTwo(cad, lineLegacy, circleLegacy);

            assertEquals("Tangent applied", cad.applyTangentConstraint());
            cad.requireSketchMirrorParity();
            SketchGeometry.Line solved = modelLine(cad, lineId);
            assertEquals("Point-FIXED x must be preserved", fixedX, solved.a.xMm, 0.0);
            assertEquals("Point-FIXED y must be preserved", fixedY, solved.a.yMm, 0.0);
            assertEquals("Point-FIXED Tangent must preserve line length", beforeLength, solved.lengthMm(), EPS);
            assertTrue(hasPointFixed(cad, lineId, 0));
            assertFalse(hasPointFixed(cad, lineId, 1));
            assertModelTangent(cad, lineId, circleId);
            assertEquals(0, legacyTangentTruthCount(cad));
            return true;
        });
    }

    @Test public void wholeFixedTangentConflictFailsAtomicallyWithoutMetadataOrHistoryMutation() throws Exception {
        onMain(() -> {
            K33MirroredCadCanvasView cad = cad();
            cad.executeCommand("CIRCLE 180 180 45");
            CadCanvasView.Entity circleLegacy = cad.selected;
            String circleId = circleLegacy.stableId();
            cad.executeCommand("LINE 280 280 360 280");
            CadCanvasView.Entity lineLegacy = cad.selected;
            String lineId = lineLegacy.stableId();

            cad.selectedObjects.clear();
            cad.selectedObjects.add(lineLegacy);
            cad.selected = lineLegacy;
            assertEquals("1 selection(s) locked", cad.toggleSelectedLock());
            cad.requireSketchMirrorParity();

            double[] geometryBefore = modelLineSignature(cad, lineId);
            int constraintsBefore = cad.sketchConstraintCount();
            long transitionsBefore = cad.sketchAuthorityTransitionCount();
            boolean undoBefore = cad.sketchAuthorityCanUndo();
            boolean redoBefore = cad.sketchAuthorityCanRedo();
            selectTwo(cad, lineLegacy, circleLegacy);

            String result = cad.applyTangentConstraint();
            assertTrue("Impossible whole-FIXED Tangent must fail closed: " + result,
                    result.contains("could not be solved") || result.contains("unchanged"));
            cad.requireSketchMirrorParity();
            assertEquals(0, countTangent(cad, lineId, circleId));
            assertEquals(constraintsBefore, cad.sketchConstraintCount());
            assertEquals(transitionsBefore, cad.sketchAuthorityTransitionCount());
            assertEquals(undoBefore, cad.sketchAuthorityCanUndo());
            assertEquals(redoBefore, cad.sketchAuthorityCanRedo());
            assertModelLineSame("Failed Tangent must leave geometry unchanged",
                    geometryBefore, modelLineSignature(cad, lineId));
            assertEquals(0, legacyTangentTruthCount(cad));
            return true;
        });
    }

    private static K33MirroredCadCanvasView cad() {
        Context context = ApplicationProvider.getApplicationContext();
        K33MirroredCadCanvasView cad = new K33MirroredCadCanvasView(context);
        cad.layout(0, 0, 1400, 1000);
        return cad;
    }

    private static void selectTwo(K33MirroredCadCanvasView cad,
                                  CadCanvasView.Entity first, CadCanvasView.Entity second) {
        cad.selectedObjects.clear();
        cad.selectedObjects.add(first);
        cad.selectedObjects.add(second);
        cad.selected = null;
    }

    private static int countTangent(K33MirroredCadCanvasView cad, String first, String second) {
        int count = 0;
        for (SketchConstraint constraint : cad.sketchConstraints()) {
            if (constraint.kind != SketchConstraint.Kind.TANGENT || constraint.secondaryEntityId == null) continue;
            boolean same = first.equals(constraint.primaryEntityId)
                    && second.equals(constraint.secondaryEntityId);
            boolean reverse = second.equals(constraint.primaryEntityId)
                    && first.equals(constraint.secondaryEntityId);
            if (same || reverse) count++;
        }
        return count;
    }

    private static int legacyTangentTruthCount(K33MirroredCadCanvasView cad) {
        try {
            Field field = ShaprLabCanvasView.class.getDeclaredField("tangentRelations");
            field.setAccessible(true);
            Object value = field.get(cad);
            if (value instanceof Collection) return ((Collection<?>) value).size();
            throw new AssertionError("Unexpected tangentRelations store: " + value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Cannot inspect legacy Tangent authority", e);
        }
    }

    private static SketchEntity modelEntity(K33MirroredCadCanvasView cad, String id) {
        List<SketchEntity> entities = cad.sketchMirrorEntities();
        for (SketchEntity entity : entities) if (id.equals(entity.id())) return entity;
        throw new AssertionError("Model entity not found: " + id + "; mirrorError=" + cad.sketchMirrorError());
    }

    private static boolean hasModelEntity(K33MirroredCadCanvasView cad, String id) {
        for (SketchEntity entity : cad.sketchMirrorEntities()) if (id.equals(entity.id())) return true;
        return false;
    }

    private static CadCanvasView.Entity legacyEntity(K33MirroredCadCanvasView cad, String id) {
        for (CadCanvasView.Entity entity : cad.entities) if (id.equals(entity.stableId())) return entity;
        throw new AssertionError("Legacy projection not found: " + id);
    }

    private static SketchGeometry.Line modelLine(K33MirroredCadCanvasView cad, String id) {
        return (SketchGeometry.Line) modelEntity(cad, id);
    }

    private static double[] modelLineSignature(K33MirroredCadCanvasView cad, String id) {
        SketchGeometry.Line line = modelLine(cad, id);
        return new double[]{line.a.xMm, line.a.yMm, line.b.xMm, line.b.yMm};
    }

    private static void assertModelTangent(K33MirroredCadCanvasView cad, String lineId, String curveId) {
        SketchGeometry.Line line = modelLine(cad, lineId);
        SketchEntity curve = modelEntity(cad, curveId);
        SketchGeometry.Point center;
        double radius;
        if (curve instanceof SketchGeometry.Circle) {
            SketchGeometry.Circle circle = (SketchGeometry.Circle) curve;
            center = circle.center;
            radius = circle.radiusMm;
        } else {
            SketchGeometry.Arc arc = (SketchGeometry.Arc) curve;
            center = arc.center;
            radius = arc.radiusMm;
        }
        double dx = line.b.xMm - line.a.xMm;
        double dy = line.b.yMm - line.a.yMm;
        double length = Math.hypot(dx, dy);
        double cross = dx * (center.yMm - line.a.yMm) - dy * (center.xMm - line.a.xMm);
        assertEquals("Model line must be tangent to model curve", radius, Math.abs(cross) / length, EPS);
    }

    private static boolean hasPointFixed(K33MirroredCadCanvasView cad, String id, int pointIndex) {
        for (SketchConstraint constraint : cad.sketchConstraints()) {
            if (constraint.kind == SketchConstraint.Kind.FIXED
                    && constraint.fixesPoint()
                    && id.equals(constraint.primaryEntityId)
                    && constraint.primaryPointIndex == pointIndex) return true;
        }
        return false;
    }

    private static void lockPoint(K33MirroredCadCanvasView cad, CadCanvasView.LineEntity legacy,
                                  String stableId, int pointIndex) {
        CadCanvasView.ControlPoint p = legacy.controlPoints().get(pointIndex);
        cad.setTool(CadCanvasView.TOOL_SELECT);
        tap(cad, screenX(cad, p.x), screenY(cad, p.y));
        assertEquals(stableId, cad.pointLockTargetEntityId());
        assertEquals(pointIndex, cad.pointLockTargetPointIndex());
        assertEquals("Point locked", cad.toggleSelectedLock());
    }

    private static float[] legacyLineSignature(CadCanvasView.LineEntity line) {
        return new float[]{line.x1, line.y1, line.x2, line.y2};
    }

    private static void assertLegacyLineSame(String message, float[] expected,
                                             CadCanvasView.LineEntity actual) {
        float[] values = legacyLineSignature(actual);
        assertEquals(message + " x1", expected[0], values[0], 0f);
        assertEquals(message + " y1", expected[1], values[1], 0f);
        assertEquals(message + " x2", expected[2], values[2], 0f);
        assertEquals(message + " y2", expected[3], values[3], 0f);
    }

    private static void assertModelLineSame(String message, double[] expected, double[] actual) {
        assertEquals(message + " ax", expected[0], actual[0], 0.0);
        assertEquals(message + " ay", expected[1], actual[1], 0.0);
        assertEquals(message + " bx", expected[2], actual[2], 0.0);
        assertEquals(message + " by", expected[3], actual[3], 0.0);
    }

    private static double distance(SketchGeometry.Point a, SketchGeometry.Point b) {
        return Math.hypot(b.xMm - a.xMm, b.yMm - a.yMm);
    }

    private static float screenX(K33MirroredCadCanvasView cad, double xMm) {
        return (float) (xMm * 3.0 * cad.viewScale + cad.offsetX);
    }

    private static float screenY(K33MirroredCadCanvasView cad, double yMm) {
        return (float) (yMm * 3.0 * cad.viewScale + cad.offsetY);
    }

    private static void tap(K33MirroredCadCanvasView cad, float x, float y) {
        long down = SystemClock.uptimeMillis();
        send(cad, one(down, down, MotionEvent.ACTION_DOWN, x, y));
        send(cad, one(down, down + 16L, MotionEvent.ACTION_UP, x, y));
    }

    private static void send(K33MirroredCadCanvasView cad, MotionEvent event) {
        try {
            assertTrue(cad.onTouchEvent(event));
        } finally {
            event.recycle();
        }
    }

    private static MotionEvent one(long down, long time, int action, float x, float y) {
        MotionEvent.PointerProperties property = new MotionEvent.PointerProperties();
        property.id = 0;
        property.toolType = MotionEvent.TOOL_TYPE_FINGER;
        MotionEvent.PointerCoords coords = new MotionEvent.PointerCoords();
        coords.x = x;
        coords.y = y;
        coords.pressure = 1f;
        coords.size = 1f;
        return MotionEvent.obtain(down, time, action, 1,
                new MotionEvent.PointerProperties[]{property},
                new MotionEvent.PointerCoords[]{coords},
                0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0);
    }

    private static <T> T onMain(Callable<T> callable) throws Exception {
        FutureTask<T> task = new FutureTask<>(callable);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(task);
        return task.get();
    }
}
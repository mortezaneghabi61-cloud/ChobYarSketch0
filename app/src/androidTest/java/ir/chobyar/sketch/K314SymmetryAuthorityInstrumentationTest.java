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
import java.util.Collection;

import ir.chobyar.sketch.core.SketchConstraint;
import ir.chobyar.sketch.core.SketchEntity;
import ir.chobyar.sketch.core.SketchGeometry;

/**
 * K3.14 API35 RED fence for Symmetry authority.
 *
 * The production contract is intentionally stronger than the legacy ShaprLab
 * relation: Symmetry must be model-owned by stable IDs, draw must be
 * presentation-only, persistence/delete must retain/cascade the relationship,
 * and an impossible whole-FIXED driven line must fail atomically.
 */
@RunWith(AndroidJUnit4.class)
public final class K314SymmetryAuthorityInstrumentationTest {
    private static final double EPS = 1.0e-5;

    @Test public void symmetryMustBeModelOwnedWithStableIdsAndNoLegacyTruth() {
        K33MirroredCadCanvasView cad = cad();
        CadCanvasView.Entity source = line(cad, "LINE 70 260 130 220");
        CadCanvasView.Entity mirror = line(cad, "LINE 300 255 350 305");
        CadCanvasView.Entity axis = line(cad, "LINE 215 170 240 360");
        String sourceId = source.stableId();
        String mirrorId = mirror.stableId();
        String axisId = axis.stableId();
        selectThree(cad, source, mirror, axis);

        String result = cad.applySymmetryConstraint();
        assertTrue("Symmetry rejected: " + result, result.contains("Symmetry"));
        assertEquals("Symmetry must be one model-owned stable-ID constraint",
                1, countModelSymmetry(cad, sourceId, mirrorId, axisId));
        assertEquals("Migrated Symmetry must not populate ShaprLab object-identity truth",
                0, legacySymmetryTruthCount(cad));
        cad.requireSketchMirrorParity();
        assertMirrorAcrossAxis(modelLine(cad, sourceId), modelLine(cad, mirrorId), modelLine(cad, axisId));
        assertTrue("Model-owned Symmetry must be undoable", cad.sketchAuthorityCanUndo());

        cad.undo();
        cad.requireSketchMirrorParity();
        assertEquals(0, countModelSymmetry(cad, sourceId, mirrorId, axisId));
        assertEquals(0, legacySymmetryTruthCount(cad));
        assertTrue("Symmetry redo must restore one model transaction", cad.redoSketch());
        cad.requireSketchMirrorParity();
        assertEquals(1, countModelSymmetry(cad, sourceId, mirrorId, axisId));
        assertEquals(0, legacySymmetryTruthCount(cad));
    }

    @Test public void obliqueAxisSymmetryPreservesSourceAndAxisAndDrivesOnlyMirror() {
        K33MirroredCadCanvasView cad = cad();
        CadCanvasView.Entity source = line(cad, "LINE 70 250 145 205");
        CadCanvasView.Entity mirror = line(cad, "LINE 330 260 365 330");
        CadCanvasView.Entity axis = line(cad, "LINE 190 160 265 370");
        String sourceId = source.stableId();
        String mirrorId = mirror.stableId();
        String axisId = axis.stableId();
        double[] sourceBefore = modelLineSignature(cad, sourceId);
        double[] axisBefore = modelLineSignature(cad, axisId);
        selectThree(cad, source, mirror, axis);

        assertEquals("Symmetry applied", cad.applySymmetryConstraint());
        cad.requireSketchMirrorParity();
        assertLineSame("Symmetry must not move source", sourceBefore, modelLineSignature(cad, sourceId));
        assertLineSame("Symmetry must not move axis", axisBefore, modelLineSignature(cad, axisId));
        assertMirrorAcrossAxis(modelLine(cad, sourceId), modelLine(cad, mirrorId), modelLine(cad, axisId));
        assertEquals(1, countModelSymmetry(cad, sourceId, mirrorId, axisId));
        assertEquals(0, legacySymmetryTruthCount(cad));
    }

    @Test public void drawMustNeverRepairLegacySymmetryProjectionOrMutateModelAuthority() {
        K33MirroredCadCanvasView cad = cad();
        CadCanvasView.Entity source = line(cad, "LINE 80 280 130 235");
        CadCanvasView.LineEntity mirror = (CadCanvasView.LineEntity) line(cad, "LINE 305 260 350 310");
        CadCanvasView.Entity axis = line(cad, "LINE 220 200 220 360");
        String sourceId = source.stableId();
        String mirrorId = mirror.stableId();
        String axisId = axis.stableId();
        selectThree(cad, source, mirror, axis);
        assertEquals("Symmetry applied", cad.applySymmetryConstraint());
        cad.requireSketchMirrorParity();
        assertEquals(1, countModelSymmetry(cad, sourceId, mirrorId, axisId));
        assertEquals(0, legacySymmetryTruthCount(cad));

        double[] modelBefore = modelLineSignature(cad, mirrorId);
        int constraintsBefore = cad.sketchConstraintCount();
        long transitionsBefore = cad.sketchAuthorityTransitionCount();
        mirror.x1 += 67f;
        mirror.y1 -= 31f;
        mirror.x2 += 23f;
        mirror.y2 += 44f;
        float[] legacyDrift = legacyLineSignature(mirror);

        Bitmap bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888);
        cad.onDraw(new Canvas(bitmap));

        assertLegacyLineSame("onDraw must be presentation-only for Symmetry", legacyDrift, mirror);
        assertLineSame("onDraw must not mutate Symmetry model geometry", modelBefore,
                modelLineSignature(cad, mirrorId));
        assertEquals(constraintsBefore, cad.sketchConstraintCount());
        assertEquals(transitionsBefore, cad.sketchAuthorityTransitionCount());
        assertEquals(0, legacySymmetryTruthCount(cad));
    }

    @Test public void symmetryPersistsAcrossProjectRoundTripAndAxisDeleteCascades() {
        K33MirroredCadCanvasView cad = cad();
        CadCanvasView.Entity source = line(cad, "LINE 80 280 130 235");
        CadCanvasView.Entity mirror = line(cad, "LINE 305 260 350 310");
        CadCanvasView.Entity axis = line(cad, "LINE 220 200 220 360");
        String sourceId = source.stableId();
        String mirrorId = mirror.stableId();
        String axisId = axis.stableId();
        selectThree(cad, source, mirror, axis);
        assertEquals("Symmetry applied", cad.applySymmetryConstraint());
        cad.requireSketchMirrorParity();
        assertEquals(1, countModelSymmetry(cad, sourceId, mirrorId, axisId));
        String saved = cad.exportSketchProjectState();

        K33MirroredCadCanvasView restored = cad();
        String imported = restored.importSketchProjectState(saved);
        assertFalse("Symmetry project state failed to reload: " + imported,
                imported.contains("could not be restored"));
        restored.requireSketchMirrorParity();
        assertEquals(1, countModelSymmetry(restored, sourceId, mirrorId, axisId));
        assertEquals(0, legacySymmetryTruthCount(restored));
        assertMirrorAcrossAxis(modelLine(restored, sourceId), modelLine(restored, mirrorId), modelLine(restored, axisId));

        CadCanvasView.Entity restoredAxis = legacyEntity(restored, axisId);
        restored.selectedObjects.clear();
        restored.selectedObjects.add(restoredAxis);
        restored.selected = restoredAxis;
        restored.deleteSelected();
        restored.requireSketchMirrorParity();
        assertFalse(hasModelEntity(restored, axisId));
        assertTrue(hasModelEntity(restored, sourceId));
        assertTrue(hasModelEntity(restored, mirrorId));
        assertEquals("Deleting the symmetry axis must cascade model Symmetry metadata",
                0, countModelSymmetry(restored, sourceId, mirrorId, axisId));
        assertEquals(0, legacySymmetryTruthCount(restored));
    }

    @Test public void wholeFixedMirrorConflictFailsAtomicallyWithoutMetadataOrHistoryMutation() {
        K33MirroredCadCanvasView cad = cad();
        CadCanvasView.Entity source = line(cad, "LINE 70 260 130 220");
        CadCanvasView.Entity mirror = line(cad, "LINE 330 310 390 350");
        CadCanvasView.Entity axis = line(cad, "LINE 220 180 220 360");
        String sourceId = source.stableId();
        String mirrorId = mirror.stableId();
        String axisId = axis.stableId();

        cad.selectedObjects.clear();
        cad.selectedObjects.add(mirror);
        cad.selected = mirror;
        assertEquals("1 selection(s) locked", cad.toggleSelectedLock());
        cad.requireSketchMirrorParity();
        double[] mirrorBefore = modelLineSignature(cad, mirrorId);
        int constraintsBefore = cad.sketchConstraintCount();
        long transitionsBefore = cad.sketchAuthorityTransitionCount();
        boolean undoBefore = cad.sketchAuthorityCanUndo();
        boolean redoBefore = cad.sketchAuthorityCanRedo();

        selectThree(cad, source, mirror, axis);
        String result = cad.applySymmetryConstraint();
        assertTrue("Impossible whole-FIXED Symmetry must fail closed: " + result,
                result.contains("could not be solved") || result.contains("unchanged"));
        cad.requireSketchMirrorParity();
        assertEquals(0, countModelSymmetry(cad, sourceId, mirrorId, axisId));
        assertEquals(constraintsBefore, cad.sketchConstraintCount());
        assertEquals(transitionsBefore, cad.sketchAuthorityTransitionCount());
        assertEquals(undoBefore, cad.sketchAuthorityCanUndo());
        assertEquals(redoBefore, cad.sketchAuthorityCanRedo());
        assertLineSame("Failed Symmetry must leave whole-FIXED mirror unchanged",
                mirrorBefore, modelLineSignature(cad, mirrorId));
        assertEquals(0, legacySymmetryTruthCount(cad));
    }

    private static K33MirroredCadCanvasView cad() {
        Context context = ApplicationProvider.getApplicationContext();
        K33MirroredCadCanvasView cad = new K33MirroredCadCanvasView(context);
        cad.layout(0, 0, 1400, 1000);
        return cad;
    }

    private static CadCanvasView.Entity line(K33MirroredCadCanvasView cad, String command) {
        cad.executeCommand(command);
        return cad.selected;
    }

    private static void selectThree(K33MirroredCadCanvasView cad,
                                    CadCanvasView.Entity source,
                                    CadCanvasView.Entity mirror,
                                    CadCanvasView.Entity axis) {
        cad.selectedObjects.clear();
        cad.selectedObjects.add(source);
        cad.selectedObjects.add(mirror);
        cad.selectedObjects.add(axis);
        cad.selected = null;
    }

    private static int countModelSymmetry(K33MirroredCadCanvasView cad,
                                          String sourceId, String mirrorId, String axisId) {
        int count = 0;
        for (SketchConstraint constraint : cad.sketchConstraints()) {
            if (!"SYMMETRY".equals(constraint.kind.name())) continue;
            if (!sourceId.equals(constraint.primaryEntityId)) continue;
            if (!mirrorId.equals(constraint.secondaryEntityId)) continue;
            if (!axisId.equals(tertiaryEntityId(constraint))) continue;
            count++;
        }
        return count;
    }

    private static String tertiaryEntityId(SketchConstraint constraint) {
        try {
            Field field = SketchConstraint.class.getDeclaredField("tertiaryEntityId");
            field.setAccessible(true);
            Object value = field.get(constraint);
            return value == null ? null : String.valueOf(value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Model Symmetry must expose a stable axis entity id", e);
        }
    }

    private static int legacySymmetryTruthCount(K33MirroredCadCanvasView cad) {
        try {
            Field field = ShaprLabCanvasView.class.getDeclaredField("symmetryRelations");
            field.setAccessible(true);
            Object value = field.get(cad);
            if (value instanceof Collection) return ((Collection<?>) value).size();
            throw new AssertionError("Unexpected symmetryRelations store: " + value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Cannot inspect legacy Symmetry authority", e);
        }
    }

    private static SketchEntity modelEntity(K33MirroredCadCanvasView cad, String id) {
        for (SketchEntity entity : cad.sketchMirrorEntities()) if (id.equals(entity.id())) return entity;
        throw new AssertionError("Model entity not found: " + id + "; mirrorError=" + cad.sketchMirrorError());
    }

    private static SketchGeometry.Line modelLine(K33MirroredCadCanvasView cad, String id) {
        return (SketchGeometry.Line) modelEntity(cad, id);
    }

    private static boolean hasModelEntity(K33MirroredCadCanvasView cad, String id) {
        for (SketchEntity entity : cad.sketchMirrorEntities()) if (id.equals(entity.id())) return true;
        return false;
    }

    private static CadCanvasView.Entity legacyEntity(K33MirroredCadCanvasView cad, String id) {
        for (CadCanvasView.Entity entity : cad.entities) if (id.equals(entity.stableId())) return entity;
        throw new AssertionError("Legacy projection not found: " + id);
    }

    private static double[] modelLineSignature(K33MirroredCadCanvasView cad, String id) {
        SketchGeometry.Line line = modelLine(cad, id);
        return new double[]{line.a.xMm, line.a.yMm, line.b.xMm, line.b.yMm};
    }

    private static float[] legacyLineSignature(CadCanvasView.LineEntity line) {
        return new float[]{line.x1, line.y1, line.x2, line.y2};
    }

    private static void assertLegacyLineSame(String message, float[] expected, CadCanvasView.LineEntity actual) {
        assertEquals(message + " x1", expected[0], actual.x1, 0f);
        assertEquals(message + " y1", expected[1], actual.y1, 0f);
        assertEquals(message + " x2", expected[2], actual.x2, 0f);
        assertEquals(message + " y2", expected[3], actual.y2, 0f);
    }

    private static void assertLineSame(String message, double[] expected, double[] actual) {
        assertEquals(message + " x1", expected[0], actual[0], 0.0);
        assertEquals(message + " y1", expected[1], actual[1], 0.0);
        assertEquals(message + " x2", expected[2], actual[2], 0.0);
        assertEquals(message + " y2", expected[3], actual[3], 0.0);
    }

    private static void assertMirrorAcrossAxis(SketchGeometry.Line source,
                                               SketchGeometry.Line mirror,
                                               SketchGeometry.Line axis) {
        SketchGeometry.Point r0 = reflect(source.a, axis.a, axis.b);
        SketchGeometry.Point r1 = reflect(source.b, axis.a, axis.b);
        double same = distance(mirror.a, r0) + distance(mirror.b, r1);
        double swap = distance(mirror.a, r1) + distance(mirror.b, r0);
        if (same <= swap) {
            assertPointNear("mirror endpoint A", r0, mirror.a);
            assertPointNear("mirror endpoint B", r1, mirror.b);
        } else {
            assertPointNear("mirror endpoint A swapped", r1, mirror.a);
            assertPointNear("mirror endpoint B swapped", r0, mirror.b);
        }
    }

    private static SketchGeometry.Point reflect(SketchGeometry.Point p,
                                                SketchGeometry.Point a,
                                                SketchGeometry.Point b) {
        double dx = b.xMm - a.xMm;
        double dy = b.yMm - a.yMm;
        double len2 = dx * dx + dy * dy;
        if (len2 <= 1.0e-12) throw new AssertionError("Symmetry axis is degenerate");
        double t = ((p.xMm - a.xMm) * dx + (p.yMm - a.yMm) * dy) / len2;
        double qx = a.xMm + t * dx;
        double qy = a.yMm + t * dy;
        return new SketchGeometry.Point(2.0 * qx - p.xMm, 2.0 * qy - p.yMm);
    }

    private static void assertPointNear(String message, SketchGeometry.Point expected, SketchGeometry.Point actual) {
        assertEquals(message + " x", expected.xMm, actual.xMm, EPS);
        assertEquals(message + " y", expected.yMm, actual.yMm, EPS);
    }

    private static double distance(SketchGeometry.Point a, SketchGeometry.Point b) {
        return Math.hypot(a.xMm - b.xMm, a.yMm - b.yMm);
    }
}

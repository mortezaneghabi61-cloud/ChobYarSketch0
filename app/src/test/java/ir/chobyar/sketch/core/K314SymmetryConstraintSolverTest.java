package ir.chobyar.sketch.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;

/** JVM contract for K3.14 model-owned three-line Symmetry authority. */
public final class K314SymmetryConstraintSolverTest {
    private static final double EPS = 1.0e-6;
    private final DeterministicSketchConstraintSolver solver = new DeterministicSketchConstraintSolver();

    @Test public void modelExposesThreeStableIdSymmetryConstraint() throws Exception {
        SketchConstraint.Kind kind = Enum.valueOf(SketchConstraint.Kind.class, "SYMMETRY");
        assertNotNull(kind);
        Method factory = SketchConstraint.class.getDeclaredMethod(
                "symmetry", String.class, String.class, String.class, String.class);
        Field tertiary = SketchConstraint.class.getDeclaredField("tertiaryEntityId");
        assertNotNull(factory);
        assertNotNull(tertiary);

        SketchConstraint constraint = (SketchConstraint) factory.invoke(
                null, "sym-1", "source", "mirror", "axis");
        assertEquals(SketchConstraint.Kind.SYMMETRY, constraint.kind);
        assertEquals("source", constraint.primaryEntityId);
        assertEquals("mirror", constraint.secondaryEntityId);
        assertEquals("axis", constraint.tertiaryEntityId);
        assertTrue(constraint.referencedEntityIds().containsAll(
                Arrays.asList("source", "mirror", "axis")));
    }

    @Test public void obliqueAxisReflectsSourceIntoDrivenMirrorWithoutMovingSourceOrAxis() {
        SketchGeometry.Line source = line("source", 4, 7, 24, 15);
        SketchGeometry.Line mirror = line("mirror", 70, 70, 90, 78);
        SketchGeometry.Line axis = line("axis", 34, -12, 48, 43);
        SketchConstraint symmetry = SketchConstraint.symmetry("s", "source", "mirror", "axis");

        SketchConstraintSolver.Result result = solver.solve(
                Arrays.asList(source, mirror, axis), Collections.singletonList(symmetry));

        assertTrue(result.solved());
        SketchGeometry.Line solvedSource = (SketchGeometry.Line) entity(result, "source");
        SketchGeometry.Line solvedMirror = (SketchGeometry.Line) entity(result, "mirror");
        SketchGeometry.Line solvedAxis = (SketchGeometry.Line) entity(result, "axis");
        assertLineEquals(source, solvedSource);
        assertLineEquals(axis, solvedAxis);
        assertMirrorAcrossAxis(source, solvedMirror, axis);
    }

    @Test public void endpointCorrespondenceChoosesNearestEquivalentOrientationWithoutMovingReferences() {
        SketchGeometry.Line source = line("source", 5, 5, 18, 11);
        SketchGeometry.Line axis = line("axis", 30, -20, 30, 40);
        SketchGeometry.Point reflectedA = reflect(source.a, axis);
        SketchGeometry.Point reflectedB = reflect(source.b, axis);
        SketchGeometry.Line mirror = new SketchGeometry.Line("mirror",
                new SketchGeometry.Point(reflectedB.xMm + 0.2, reflectedB.yMm - 0.1),
                new SketchGeometry.Point(reflectedA.xMm - 0.2, reflectedA.yMm + 0.1));

        SketchConstraintSolver.Result result = solver.solve(
                Arrays.asList(source, mirror, axis),
                Collections.singletonList(SketchConstraint.symmetry("s", "source", "mirror", "axis")));

        assertTrue(result.solved());
        SketchGeometry.Line solved = (SketchGeometry.Line) entity(result, "mirror");
        assertPointEquals(reflectedB, solved.a);
        assertPointEquals(reflectedA, solved.b);
        assertLineEquals(source, (SketchGeometry.Line) entity(result, "source"));
        assertLineEquals(axis, (SketchGeometry.Line) entity(result, "axis"));
    }

    @Test public void compatiblePointFixedMirrorEndpointRemainsAuthoritative() {
        SketchGeometry.Line source = line("source", 0, 0, 10, 6);
        SketchGeometry.Line axis = line("axis", 20, -10, 20, 30);
        SketchGeometry.Point reflectedA = reflect(source.a, axis);
        SketchGeometry.Point reflectedB = reflect(source.b, axis);
        SketchGeometry.Line mirror = new SketchGeometry.Line("mirror", reflectedA,
                new SketchGeometry.Point(reflectedB.xMm + 9, reflectedB.yMm + 7));

        SketchConstraintSolver.Result result = solver.solve(
                Arrays.asList(source, mirror, axis),
                Arrays.asList(
                        SketchConstraint.fixedPoint("fix", "mirror", 0),
                        SketchConstraint.symmetry("s", "source", "mirror", "axis")));

        assertTrue(result.solved());
        SketchGeometry.Line solved = (SketchGeometry.Line) entity(result, "mirror");
        assertPointEquals(reflectedA, solved.a);
        assertPointEquals(reflectedB, solved.b);
        assertMirrorAcrossAxis(source, solved, axis);
    }

    @Test public void incompatiblePointFixedMirrorEndpointFailsClosedAsConflict() {
        SketchGeometry.Line source = line("source", 0, 0, 10, 6);
        SketchGeometry.Line mirror = line("mirror", 100, 100, 110, 106);
        SketchGeometry.Line axis = line("axis", 20, -10, 20, 30);

        SketchConstraintSolver.Result result = solver.solve(
                Arrays.asList(source, mirror, axis),
                Arrays.asList(
                        SketchConstraint.fixedPoint("fix", "mirror", 0),
                        SketchConstraint.symmetry("s", "source", "mirror", "axis")));

        assertFalse(result.solved());
        assertEquals(SketchConstraintSolver.Status.CONFLICT, result.status);
    }

    @Test public void wholeFixedDrivenMirrorFailsClosedAsConflict() {
        SketchGeometry.Line source = line("source", 0, 0, 10, 6);
        SketchGeometry.Line mirror = line("mirror", 100, 100, 110, 106);
        SketchGeometry.Line axis = line("axis", 20, -10, 20, 30);

        SketchConstraintSolver.Result result = solver.solve(
                Arrays.asList(source, mirror, axis),
                Arrays.asList(
                        SketchConstraint.fixed("fix", "mirror"),
                        SketchConstraint.symmetry("s", "source", "mirror", "axis")));

        assertFalse(result.solved());
        assertEquals(SketchConstraintSolver.Status.CONFLICT, result.status);
    }

    @Test public void documentConflictIsAtomicWithoutSymmetryMetadataGeometryOrHistoryMutation() {
        SketchDocument document = new SketchDocument();
        document.add(line("source", 0, 0, 10, 6));
        document.add(line("mirror", 100, 100, 110, 106));
        document.add(line("axis", 20, -10, 20, 30));
        document.addConstraintsAndSolve(
                Collections.singletonList(SketchConstraint.fixed("fix", "mirror")), solver);

        long revisionBefore = document.revision();
        int countBefore = document.constraintCount();
        boolean undoBefore = document.canUndo();
        boolean redoBefore = document.canRedo();
        SketchGeometry.Line mirrorBefore = (SketchGeometry.Line) document.entity("mirror");

        try {
            document.addConstraintsAndSolve(Collections.singletonList(
                    SketchConstraint.symmetry("s", "source", "mirror", "axis")), solver);
            fail("Impossible whole-FIXED Symmetry must fail atomically");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage() != null && !expected.getMessage().isEmpty());
        }

        assertEquals(revisionBefore, document.revision());
        assertEquals(countBefore, document.constraintCount());
        assertEquals(undoBefore, document.canUndo());
        assertEquals(redoBefore, document.canRedo());
        assertLineEquals(mirrorBefore, (SketchGeometry.Line) document.entity("mirror"));
        assertEquals(0, symmetryCount(document));
    }

    @Test public void deletingAxisCascadesThreeEntitySymmetryMetadataButKeepsSourceAndMirror() {
        SketchDocument document = new SketchDocument();
        document.add(line("source", 0, 0, 10, 6));
        document.add(line("mirror", 100, 100, 110, 106));
        document.add(line("axis", 20, -10, 20, 30));
        document.addConstraintsAndSolve(Collections.singletonList(
                SketchConstraint.symmetry("s", "source", "mirror", "axis")), solver);
        assertEquals(1, symmetryCount(document));

        document.selectOnly("axis");
        assertEquals(1, document.removeSelected());

        assertEquals(0, symmetryCount(document));
        assertNotNull(document.entity("source"));
        assertNotNull(document.entity("mirror"));
        assertEquals(null, document.entity("axis"));
    }

    private static int symmetryCount(SketchDocument document) {
        int count = 0;
        for (SketchConstraint c : document.constraints()) {
            if (c.kind == SketchConstraint.Kind.SYMMETRY) count++;
        }
        return count;
    }

    private static SketchGeometry.Line line(String id, double x1, double y1, double x2, double y2) {
        return new SketchGeometry.Line(id,
                new SketchGeometry.Point(x1, y1),
                new SketchGeometry.Point(x2, y2));
    }

    private static SketchEntity entity(SketchConstraintSolver.Result result, String id) {
        for (SketchEntity entity : result.entities()) if (id.equals(entity.id())) return entity;
        throw new AssertionError("Missing solved entity " + id);
    }

    private static SketchGeometry.Point reflect(SketchGeometry.Point p, SketchGeometry.Line axis) {
        double dx = axis.b.xMm - axis.a.xMm;
        double dy = axis.b.yMm - axis.a.yMm;
        double lengthSquared = dx * dx + dy * dy;
        double t = ((p.xMm - axis.a.xMm) * dx + (p.yMm - axis.a.yMm) * dy) / lengthSquared;
        double qx = axis.a.xMm + t * dx;
        double qy = axis.a.yMm + t * dy;
        return new SketchGeometry.Point(2.0 * qx - p.xMm, 2.0 * qy - p.yMm);
    }

    private static void assertMirrorAcrossAxis(SketchGeometry.Line source,
                                               SketchGeometry.Line mirror,
                                               SketchGeometry.Line axis) {
        SketchGeometry.Point r0 = reflect(source.a, axis);
        SketchGeometry.Point r1 = reflect(source.b, axis);
        double same = distance(mirror.a, r0) + distance(mirror.b, r1);
        double swapped = distance(mirror.a, r1) + distance(mirror.b, r0);
        if (same <= swapped) {
            assertPointEquals(r0, mirror.a);
            assertPointEquals(r1, mirror.b);
        } else {
            assertPointEquals(r1, mirror.a);
            assertPointEquals(r0, mirror.b);
        }
    }

    private static double distance(SketchGeometry.Point a, SketchGeometry.Point b) {
        return Math.hypot(b.xMm - a.xMm, b.yMm - a.yMm);
    }

    private static void assertPointEquals(SketchGeometry.Point expected, SketchGeometry.Point actual) {
        assertEquals(expected.xMm, actual.xMm, EPS);
        assertEquals(expected.yMm, actual.yMm, EPS);
    }

    private static void assertLineEquals(SketchGeometry.Line expected, SketchGeometry.Line actual) {
        assertEquals(expected.a.xMm, actual.a.xMm, 0.0);
        assertEquals(expected.a.yMm, actual.a.yMm, 0.0);
        assertEquals(expected.b.xMm, actual.b.xMm, 0.0);
        assertEquals(expected.b.yMm, actual.b.yMm, 0.0);
    }
}

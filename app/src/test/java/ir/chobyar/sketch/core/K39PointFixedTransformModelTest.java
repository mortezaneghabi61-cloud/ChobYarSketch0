package ir.chobyar.sketch.core;

import org.junit.Test;

import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** JVM fence for atomic solver-aware point-FIXED transforms. */
public final class K39PointFixedTransformModelTest {
    private final SketchConstraintSolver solver = new DeterministicSketchConstraintSolver();

    @Test public void lineAnchorSurvivesRotateScaleMirrorUndoRedo() {
        SketchDocument doc = new SketchDocument();
        SketchGeometry.Line line = new SketchGeometry.Line("line",
                new SketchGeometry.Point(10, 10), new SketchGeometry.Point(40, 30));
        doc.add(line);
        doc.selectOnly("line");
        doc.addConstraintsAndSolve(Collections.singletonList(
                SketchConstraint.fixedPoint("lock", "line", 0)), solver);

        SketchGeometry.Point anchor = ((SketchGeometry.Line) doc.entity("line")).a;
        doc.rotatePointFixedSelectionAndSolve(90, solver);
        assertPoint(anchor, ((SketchGeometry.Line) doc.entity("line")).a);
        double rotatedLength = ((SketchGeometry.Line) doc.entity("line")).lengthMm();

        doc.scalePointFixedSelectionAndSolve(1.5, solver);
        SketchGeometry.Line scaled = (SketchGeometry.Line) doc.entity("line");
        assertPoint(anchor, scaled.a);
        assertEquals(rotatedLength * 1.5, scaled.lengthMm(), 1e-7);

        doc.mirrorPointFixedSelectionAndSolve(true, 0, solver);
        SketchGeometry.Line mirrored = (SketchGeometry.Line) doc.entity("line");
        assertPoint(anchor, mirrored.a);
        SketchGeometry.Point redoFree = mirrored.b;

        assertTrue(doc.undo());
        assertPoint(anchor, ((SketchGeometry.Line) doc.entity("line")).a);
        assertTrue(doc.redo());
        SketchGeometry.Line redone = (SketchGeometry.Line) doc.entity("line");
        assertPoint(anchor, redone.a);
        assertPoint(redoFree, redone.b);
        assertTrue(doc.constraint("lock").fixesPoint());
        assertEquals(0, doc.constraint("lock").primaryPointIndex);
    }

    @Test public void circleAndArcCenterTransformsKeepCenter() {
        SketchDocument circleDoc = new SketchDocument();
        circleDoc.add(new SketchGeometry.Circle("circle", new SketchGeometry.Point(20, 25), 12));
        circleDoc.selectOnly("circle");
        circleDoc.addConstraintsAndSolve(Collections.singletonList(
                SketchConstraint.fixedPoint("circle-lock", "circle", 0)), solver);
        circleDoc.scalePointFixedSelectionAndSolve(1.75, solver);
        SketchGeometry.Circle circle = (SketchGeometry.Circle) circleDoc.entity("circle");
        assertPoint(new SketchGeometry.Point(20, 25), circle.center);
        assertEquals(21.0, circle.radiusMm, 1e-8);

        SketchDocument arcDoc = new SketchDocument();
        arcDoc.add(new SketchGeometry.Arc("arc", new SketchGeometry.Point(30, 35), 10, 20, 110));
        arcDoc.selectOnly("arc");
        arcDoc.addConstraintsAndSolve(Collections.singletonList(
                SketchConstraint.fixedPoint("arc-lock", "arc", 0)), solver);
        arcDoc.rotatePointFixedSelectionAndSolve(35, solver);
        arcDoc.scalePointFixedSelectionAndSolve(2, solver);
        arcDoc.mirrorPointFixedSelectionAndSolve(true, 0, solver);
        SketchGeometry.Arc arc = (SketchGeometry.Arc) arcDoc.entity("arc");
        assertPoint(new SketchGeometry.Point(30, 35), arc.center);
        assertEquals(20.0, arc.radiusMm, 1e-8);
        assertEquals(-110.0, arc.sweepDeg, 1e-8);
        assertTrue(arcDoc.constraint("arc-lock").fixesPoint());
    }

    @Test public void transformedPointFixedJsonRestoresGeometryAndConstraint() {
        String raw = "{"
                + "\"schemaVersion\":2,"
                + "\"entities\":[{\"id\":\"line-1\",\"type\":\"LINE\","
                + "\"x1\":10,\"y1\":10,\"x2\":-20,\"y2\":55}],"
                + "\"modelConstraintSchemaVersion\":1,"
                + "\"modelConstraints\":[{"
                + "\"id\":\"lock-end\",\"kind\":\"FIXED\","
                + "\"primaryEntityId\":\"line-1\",\"primaryPointIndex\":0,"
                + "\"secondaryPointIndex\":-1,\"driving\":true"
                + "}]}";
        SketchDocument restored = new SketchDocument();
        LegacySketchStateBridge.restoreDocument(restored, raw);
        SketchGeometry.Line line = (SketchGeometry.Line) restored.entity("line-1");
        assertPoint(new SketchGeometry.Point(10, 10), line.a);
        assertPoint(new SketchGeometry.Point(-20, 55), line.b);
        assertTrue(restored.constraint("lock-end").fixesPoint());
        assertEquals(0, restored.constraint("lock-end").primaryPointIndex);
        assertTrue(LegacySketchStateBridge.hasParity(restored, raw));
    }

    private static void assertPoint(SketchGeometry.Point expected, SketchGeometry.Point actual) {
        assertEquals(expected.xMm, actual.xMm, 1e-9);
        assertEquals(expected.yMm, actual.yMm, 1e-9);
    }
}

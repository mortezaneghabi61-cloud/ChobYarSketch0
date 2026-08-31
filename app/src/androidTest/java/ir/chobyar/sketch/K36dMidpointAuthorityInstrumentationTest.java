package ir.chobyar.sketch;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Collections;

import ir.chobyar.sketch.core.DeterministicSketchConstraintSolver;
import ir.chobyar.sketch.core.SketchConstraint;
import ir.chobyar.sketch.core.SketchDocument;
import ir.chobyar.sketch.core.SketchGeometry;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** API35 regression for durable model-owned MIDPOINT authority. */
@RunWith(AndroidJUnit4.class)
public final class K36dMidpointAuthorityInstrumentationTest {
    private static final DeterministicSketchConstraintSolver SOLVER = new DeterministicSketchConstraintSolver();

    private static SketchGeometry.Line line(String id, double x1, double y1, double x2, double y2) {
        return new SketchGeometry.Line(id,
                new SketchGeometry.Point(x1, y1), new SketchGeometry.Point(x2, y2));
    }

    private static void assertMidpoint(SketchDocument d) {
        SketchGeometry.Line host = (SketchGeometry.Line) d.entity("host");
        SketchGeometry.Line driven = (SketchGeometry.Line) d.entity("driven");
        assertEquals((host.a.xMm + host.b.xMm) * 0.5, driven.a.xMm, 1e-9);
        assertEquals((host.a.yMm + host.b.yMm) * 0.5, driven.a.yMm, 1e-9);
    }

    @Test public void midpointSurvivesHostEditReopenUndoRedoAndDeleteCascade() {
        SketchDocument d = new SketchDocument();
        d.add(line("host", 0, 0, 20, 4));
        d.addWithConstraintsAndSolve(
                line("driven", 3, 9, 3, 15),
                Collections.singletonList(SketchConstraint.midpoint("mid-api35", "driven", 0, "host")),
                SOLVER);
        assertMidpoint(d);

        d.selectOnly("host");
        d.translateSelectionAndSolve(8, -6, SOLVER);
        assertMidpoint(d);

        SketchDocument reopened = new SketchDocument();
        reopened.restoreExternal(d.entities(), Collections.<String>emptyList(), d.constraints());
        SketchConstraint restored = reopened.constraint("mid-api35");
        assertNotNull(restored);
        assertEquals(SketchConstraint.Kind.MIDPOINT, restored.kind);
        assertEquals("driven", restored.primaryEntityId);
        assertEquals(0, restored.primaryPointIndex);
        assertEquals("host", restored.secondaryEntityId);
        assertMidpoint(reopened);

        assertTrue(reopened.remove("host"));
        assertNull(reopened.constraint("mid-api35"));
        assertTrue(reopened.undo());
        assertNotNull(reopened.constraint("mid-api35"));
        assertMidpoint(reopened);
        assertTrue(reopened.redo());
        assertNull(reopened.entity("host"));
        assertNull(reopened.constraint("mid-api35"));
    }

    @Test public void productionDrawNeverEnforcesLegacyMidpointOrMutatesModelAuthority() throws Exception {
        new K36dProductionMidpointDrawAuthorityInstrumentationTest()
                .drawNeverEnforcesLegacyMidpointOrMutatesModelAuthority();
    }

    @Test public void productionMidpointUndoRedoSaveOpenAndHostDeletePreserveStableRelationship() throws Exception {
        new K36dProductionMidpointDrawAuthorityInstrumentationTest()
                .midpointUndoRedoSaveOpenAndHostDeletePreserveStableRelationship();
    }

    @Test public void productionCompatiblePointFixedMidpointKeepsAnchorAndLeavesFreeDofRotatable() throws Exception {
        new K36dProductionMidpointDrawAuthorityInstrumentationTest()
                .compatiblePointFixedMidpointKeepsAnchorAndLeavesFreeDofRotatable();
    }

    @Test public void productionIncompatiblePointFixedMidpointFailsAtomicallyWithoutLegacyTruth() throws Exception {
        new K36dProductionMidpointDrawAuthorityInstrumentationTest()
                .incompatiblePointFixedMidpointFailsAtomicallyWithoutLegacyTruth();
    }
}

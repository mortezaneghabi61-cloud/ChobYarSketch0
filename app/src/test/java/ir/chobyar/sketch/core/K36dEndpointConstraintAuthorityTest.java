package ir.chobyar.sketch.core;

import org.junit.Test;
import java.util.Collections;
import static org.junit.Assert.*;

public class K36dEndpointConstraintAuthorityTest {
    // Exact-head CI retrigger marker: no behavioral change.
    private static SketchGeometry.Line line(String id,double x1,double y1,double x2,double y2) {
        return new SketchGeometry.Line(id,new SketchGeometry.Point(x1,y1),new SketchGeometry.Point(x2,y2));
    }

    @Test public void addWithCoincidentSolveIsOneUndoAndRedoRestoresIds() {
        SketchDocument d=new SketchDocument();
        d.add(line("host",0,0,10,0));
        SketchConstraint c=SketchConstraint.coincident("c1","new",0,"host",1);
        d.addWithConstraintsAndSolve(line("new",10,0,20,7),Collections.singletonList(c),new DeterministicSketchConstraintSolver());
        assertEquals(2,d.size()); assertEquals(1,d.constraintCount());
        SketchGeometry.Line solved=(SketchGeometry.Line)d.entity("new");
        assertEquals(10,solved.a.xMm,1e-9); assertEquals(0,solved.a.yMm,1e-9);
        assertTrue(d.undo()); assertNull(d.entity("new")); assertEquals(0,d.constraintCount());
        assertTrue(d.redo()); assertNotNull(d.entity("new"));
        assertEquals(0,d.constraint("c1").primaryPointIndex); assertEquals(1,d.constraint("c1").secondaryPointIndex);
    }

    @Test public void pointOnEntityMovePropagatesAndDeleteCascades() {
        SketchDocument d=new SketchDocument();
        d.add(line("host",0,0,20,0)); d.add(line("owner",5,0,5,8));
        d.addConstraintsAndSolve(Collections.singletonList(SketchConstraint.pointOnEntity("p1","owner",0,"host")),new DeterministicSketchConstraintSolver());
        d.selectOnly("host");
        d.translateSelectionAndSolve(0,4,new DeterministicSketchConstraintSolver());
        SketchGeometry.Line moved=(SketchGeometry.Line)d.entity("owner");
        assertEquals(4,moved.a.yMm,1e-6);
        assertTrue(d.remove("host")); assertEquals(0,d.constraintCount());
    }

    @Test public void pointOnEntityProjectsToSupportingLineBeyondVisibleSegment() {
        SketchDocument d=new SketchDocument();
        d.add(line("host",0,0,10,0));
        d.add(line("owner",15,4,15,9));

        d.addConstraintsAndSolve(Collections.singletonList(
                SketchConstraint.pointOnEntity("p-extension","owner",0,"host")),
                new DeterministicSketchConstraintSolver());

        SketchGeometry.Line solved=(SketchGeometry.Line)d.entity("owner");
        assertEquals(15.0,solved.a.xMm,1e-9);
        assertEquals(0.0,solved.a.yMm,1e-9);
        assertEquals(1,d.constraintCount());
        assertEquals("host",d.constraint("p-extension").secondaryEntityId);

        assertTrue(d.undo());
        SketchGeometry.Line before=(SketchGeometry.Line)d.entity("owner");
        assertEquals(15.0,before.a.xMm,1e-9);
        assertEquals(4.0,before.a.yMm,1e-9);
        assertEquals(0,d.constraintCount());
        assertTrue(d.redo());
        SketchGeometry.Line redone=(SketchGeometry.Line)d.entity("owner");
        assertEquals(15.0,redone.a.xMm,1e-9);
        assertEquals(0.0,redone.a.yMm,1e-9);
        assertEquals(1,d.constraintCount());
    }

    @Test public void pointOnEntitySupportsCircleHostAndTracksTranslatedHost() {
        SketchDocument d=new SketchDocument();
        d.add(new SketchGeometry.Circle("circle",new SketchGeometry.Point(0,0),10));
        d.add(line("owner",6,8,6,14));
        d.addConstraintsAndSolve(Collections.singletonList(
                SketchConstraint.pointOnEntity("p-circle","owner",0,"circle")),
                new DeterministicSketchConstraintSolver());

        SketchGeometry.Line solved=(SketchGeometry.Line)d.entity("owner");
        assertEquals(6.0,solved.a.xMm,1e-9);
        assertEquals(8.0,solved.a.yMm,1e-9);

        d.selectOnly("circle");
        d.translateSelectionAndSolve(5,0,new DeterministicSketchConstraintSolver());
        SketchGeometry.Line moved=(SketchGeometry.Line)d.entity("owner");
        SketchGeometry.Circle circle=(SketchGeometry.Circle)d.entity("circle");
        assertEquals(10.0,Math.hypot(moved.a.xMm-circle.center.xMm,moved.a.yMm-circle.center.yMm),1e-7);
        assertEquals(1,d.constraintCount());
    }

    @Test public void pointOnEntitySupportsArcUnderlyingCurve() {
        SketchDocument d=new SketchDocument();
        d.add(new SketchGeometry.Arc("arc",new SketchGeometry.Point(0,0),10,0,90));
        d.add(line("owner",-15,0,-15,5));
        d.addConstraintsAndSolve(Collections.singletonList(
                SketchConstraint.pointOnEntity("p-arc","owner",0,"arc")),
                new DeterministicSketchConstraintSolver());

        SketchGeometry.Line solved=(SketchGeometry.Line)d.entity("owner");
        assertEquals(-10.0,solved.a.xMm,1e-9);
        assertEquals(0.0,solved.a.yMm,1e-9);
        assertEquals(1,d.constraintCount());
    }

    @Test public void degenerateLineHostFailsClosedWithoutEnteringAuthority() {
        SketchDocument d=new SketchDocument();
        d.add(line("owner",9,7,9,12));
        long revisionBefore=d.revision();
        boolean canUndoBefore=d.canUndo();

        try {
            d.add(line("host",4,4,4,4));
            fail("zero-length line host must be rejected before entering model authority");
        } catch (IllegalArgumentException expected) {
            assertEquals("Invalid sketch geometry: host", expected.getMessage());
        }

        assertEquals(revisionBefore,d.revision());
        assertEquals(canUndoBefore,d.canUndo());
        assertEquals(1,d.size());
        assertNull(d.entity("host"));
        assertEquals(0,d.constraintCount());
        SketchGeometry.Line owner=(SketchGeometry.Line)d.entity("owner");
        assertEquals(9.0,owner.a.xMm,1e-9);
        assertEquals(7.0,owner.a.yMm,1e-9);
    }

    @Test public void danglingCreateConstraintFailsBeforeMutationOrHistory() {
        SketchDocument d=new SketchDocument();
        d.add(line("host",0,0,20,0));
        assertFalse(d.canRedo());
        try {
            d.addWithConstraintsAndSolve(line("new",5,0,5,5),Collections.singletonList(
                    SketchConstraint.pointOnEntity("bad","new",0,"missing")),new DeterministicSketchConstraintSolver());
            fail("dangling reference must reject");
        } catch (IllegalArgumentException expected) {}
        assertNull(d.entity("new")); assertEquals(1,d.size()); assertEquals(0,d.constraintCount());
        assertTrue(d.undo()); assertEquals(0,d.size());
    }

    @Test public void malformedEndpointIndexesAreRejectedBeforeEnteringModelAuthority() {
        try {
            SketchConstraint.pointOnEntity("bad-poe","owner",7,"host");
            fail("POINT_ON_ENTITY must reject non-endpoint point indexes");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("endpoint 0 or 1"));
        }

        try {
            SketchConstraint.coincident("bad-coincident","a",0,"b",7);
            fail("COINCIDENT must reject non-endpoint secondary point indexes");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("endpoint 0 or 1"));
        }
    }

    @Test public void danglingPersistenceRestoreIsFailClosedAndPreservesCurrentAuthority() {
        SketchDocument d=new SketchDocument();
        d.add(line("host",0,0,20,0));
        d.add(line("owner",5,0,5,8));
        d.addConstraintsAndSolve(Collections.singletonList(
                SketchConstraint.pointOnEntity("existing","owner",0,"host")),
                new DeterministicSketchConstraintSolver());
        long revisionBefore=d.revision();
        assertTrue(d.canUndo());

        try {
            d.restoreExternal(
                    Collections.singletonList(line("replacement",0,0,5,0)),
                    Collections.singletonList("replacement"),
                    Collections.singletonList(SketchConstraint.pointOnEntity("dangling","replacement",0,"missing")));
            fail("persistence restore must reject dangling stable-id references before mutation");
        } catch (IllegalArgumentException expected) {}

        assertEquals(revisionBefore,d.revision());
        assertEquals(2,d.size());
        assertEquals(1,d.constraintCount());
        assertNotNull(d.entity("host"));
        assertNotNull(d.entity("owner"));
        assertNotNull(d.constraint("existing"));
        assertNull(d.entity("replacement"));
        assertTrue(d.canUndo());
    }

    @Test public void deletingCoincidentHostCascadesAndUndoRedoRestoresConstraintAtomically() {
        SketchDocument d=new SketchDocument();
        d.add(line("host",0,0,10,0));
        d.addWithConstraintsAndSolve(
                line("owner",10,0,18,5),
                Collections.singletonList(SketchConstraint.coincident("c-delete","owner",0,"host",1)),
                new DeterministicSketchConstraintSolver());
        assertEquals(2,d.size());
        assertEquals(1,d.constraintCount());

        assertTrue(d.remove("host"));
        assertNull(d.entity("host"));
        assertNotNull(d.entity("owner"));
        assertEquals(0,d.constraintCount());

        assertTrue(d.undo());
        assertNotNull(d.entity("host"));
        assertNotNull(d.entity("owner"));
        assertNotNull(d.constraint("c-delete"));
        assertEquals(1,d.constraintCount());

        assertTrue(d.redo());
        assertNull(d.entity("host"));
        assertNotNull(d.entity("owner"));
        assertEquals(0,d.constraintCount());
    }

    @Test public void deletingPointOnEntityHostCascadesAndUndoRedoRestoresStableReferenceAtomically() {
        SketchDocument d=new SketchDocument();
        d.add(line("host",0,0,20,0));
        d.add(line("owner",6,4,6,10));
        d.addConstraintsAndSolve(
                Collections.singletonList(SketchConstraint.pointOnEntity("p-delete","owner",0,"host")),
                new DeterministicSketchConstraintSolver());
        SketchGeometry.Line solved=(SketchGeometry.Line)d.entity("owner");
        assertEquals(6.0,solved.a.xMm,1e-9);
        assertEquals(0.0,solved.a.yMm,1e-9);
        assertEquals("host",d.constraint("p-delete").secondaryEntityId);
        assertEquals(0,d.constraint("p-delete").primaryPointIndex);

        assertTrue(d.remove("host"));
        assertNull(d.entity("host"));
        assertNotNull(d.entity("owner"));
        assertNull(d.constraint("p-delete"));
        assertEquals(0,d.constraintCount());

        assertTrue(d.undo());
        assertNotNull(d.entity("host"));
        assertNotNull(d.entity("owner"));
        SketchConstraint restored=d.constraint("p-delete");
        assertNotNull(restored);
        assertEquals("owner",restored.primaryEntityId);
        assertEquals(0,restored.primaryPointIndex);
        assertEquals("host",restored.secondaryEntityId);
        assertEquals(1,d.constraintCount());

        assertTrue(d.redo());
        assertNull(d.entity("host"));
        assertNotNull(d.entity("owner"));
        assertNull(d.constraint("p-delete"));
        assertEquals(0,d.constraintCount());
    }
}

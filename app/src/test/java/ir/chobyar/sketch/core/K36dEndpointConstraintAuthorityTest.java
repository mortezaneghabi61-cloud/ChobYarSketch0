package ir.chobyar.sketch.core;

import org.junit.Test;
import java.util.Collections;
import static org.junit.Assert.*;

public class K36dEndpointConstraintAuthorityTest {
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
}

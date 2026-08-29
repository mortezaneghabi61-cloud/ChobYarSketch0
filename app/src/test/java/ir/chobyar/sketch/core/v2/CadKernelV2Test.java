package ir.chobyar.sketch.core.v2;

import org.junit.Test;
import static org.junit.Assert.*;

public class CadKernelV2Test {
    @Test public void lineAndUndoRedoAreDeterministic(){
        CadKernelV2 k=new CadKernelV2();
        long id=k.line(0,0,100,0);
        assertEquals(1,id);
        assertEquals(100.0,k.sketch().get(id).a.distance(k.sketch().get(id).b),1e-9);
        k.move(id,25,10);
        assertEquals(25.0,k.sketch().get(id).a.x,1e-9);
        assertTrue(k.undo());
        assertEquals(0.0,k.sketch().get(id).a.x,1e-9);
        assertTrue(k.redo());
        assertEquals(25.0,k.sketch().get(id).a.x,1e-9);
    }

    @Test public void constraintsUseMillimetres(){
        CadKernelV2 k=new CadKernelV2();
        long id=k.line(0,0,30,40);
        k.constrain(SketchGeometry.ConstraintType.DISTANCE,id,0,100);
        assertEquals(100.0,k.sketch().get(id).a.distance(k.sketch().get(id).b),1e-7);
        k.constrain(SketchGeometry.ConstraintType.ANGLE,id,0,90);
        assertEquals(90.0,Math.toDegrees(Math.atan2(
                k.sketch().get(id).b.y-k.sketch().get(id).a.y,
                k.sketch().get(id).b.x-k.sketch().get(id).a.x)),1e-7);
    }

    @Test public void circleAndArcRemainAnalytic(){
        CadKernelV2 k=new CadKernelV2();
        long c=k.circle(10,20,12.5);
        long a=k.arc(0,0,25,0,180);
        assertEquals(SketchGeometry.Kind.CIRCLE,k.sketch().get(c).kind);
        assertEquals(12.5,k.sketch().get(c).radius,1e-9);
        assertEquals(SketchGeometry.Kind.ARC,k.sketch().get(a).kind);
        assertEquals(25.0,k.sketch().get(a).radius,1e-9);
        assertEquals(65,k.sketch().polyline(a).size());
    }
}

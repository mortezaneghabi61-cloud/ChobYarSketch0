package ir.chobyar.sketch.core.v2;

import org.junit.Test;
import static org.junit.Assert.*;
import java.util.*;

public class GeometryOpsTest {
    @Test public void lineIntersectionIsExact(){
        SketchGeometry s=new SketchGeometry();
        long a=s.addLine(new Vec2(0,0),new Vec2(100,100));
        long b=s.addLine(new Vec2(0,100),new Vec2(100,0));
        List<Vec2> p=GeometryOps.intersections(s.get(a),s.get(b));
        assertEquals(1,p.size()); assertEquals(50,p.get(0).x,1e-9); assertEquals(50,p.get(0).y,1e-9);
    }
    @Test public void tangentCircleLineReturnsOnePoint(){
        SketchGeometry s=new SketchGeometry();
        long l=s.addLine(new Vec2(-100,10),new Vec2(100,10));
        long c=s.addCircle(new Vec2(0,0),10);
        assertEquals(1,GeometryOps.intersections(s.get(l),s.get(c)).size());
    }
    @Test public void offsetPreservesDistance(){
        Vec2[] o=GeometryOps.offsetLine(new Vec2(0,0),new Vec2(100,0),10);
        assertEquals(10,o[0].y,1e-9); assertEquals(10,o[1].y,1e-9);
    }
}

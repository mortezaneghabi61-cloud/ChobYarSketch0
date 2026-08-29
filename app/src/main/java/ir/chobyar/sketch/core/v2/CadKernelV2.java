package ir.chobyar.sketch.core.v2;

import java.util.*;

/**
 * New application-facing kernel contract. It owns model state, history and deterministic
 * sketch operations; Android Views/JNI/OCCT adapters are consumers, not the source of truth.
 */
public final class CadKernelV2 {
    public static final int VERSION=2;
    private SketchGeometry sketch=new SketchGeometry();
    private final ArrayDeque<SketchGeometry> undo=new ArrayDeque<>();
    private final ArrayDeque<SketchGeometry> redo=new ArrayDeque<>();

    public SketchGeometry sketch(){return sketch;}

    public long line(double x1,double y1,double x2,double y2){checkpoint();return sketch.addLine(new Vec2(x1,y1),new Vec2(x2,y2));}
    public long circle(double x,double y,double r){checkpoint();return sketch.addCircle(new Vec2(x,y),r);}
    public long arc(double x,double y,double r,double startDeg,double endDeg){checkpoint();return sketch.addArc(new Vec2(x,y),r,Math.toRadians(startDeg),Math.toRadians(endDeg));}

    public void constrain(SketchGeometry.ConstraintType type,long e1,long e2,double value){checkpoint();sketch.addConstraint(type,e1,e2,value);sketch.solve(12,1e-7);}
    public void move(long id,double dx,double dy){checkpoint();sketch.move(id,new Vec2(dx,dy));sketch.solve(12,1e-7);}
    public boolean undo(){if(undo.isEmpty())return false;redo.push(sketch);sketch=undo.pop();return true;}
    public boolean redo(){if(redo.isEmpty())return false;undo.push(sketch);sketch=redo.pop();return true;}

    private void checkpoint(){undo.push(copy(sketch));redo.clear();if(undo.size()>100)undo.removeLast();}

    private static SketchGeometry copy(SketchGeometry s){
        SketchGeometry n=new SketchGeometry();
        Map<Long,Long> map=new HashMap<>();
        for(SketchGeometry.Entity e:s.entities()){
            long id;
            if(e.kind==SketchGeometry.Kind.LINE)id=n.addLine(new Vec2(e.a.x,e.a.y),new Vec2(e.b.x,e.b.y));
            else if(e.kind==SketchGeometry.Kind.CIRCLE)id=n.addCircle(new Vec2(e.center.x,e.center.y),e.radius);
            else id=n.addArc(new Vec2(e.center.x,e.center.y),e.radius,e.startAngle,e.endAngle);
            map.put(e.id,id);
        }
        for(SketchGeometry.Constraint c:s.constraints())n.addConstraint(c.type,map.get(c.e1),c.e2==0?0:map.get(c.e2),c.value);
        return n;
    }
}

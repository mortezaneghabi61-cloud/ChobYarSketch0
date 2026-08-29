package ir.chobyar.sketch.core.v2;

import java.util.*;

/**
 * Clean, UI-independent parametric sketch model.
 * All coordinates and dimensions are millimetres; rendering is deliberately outside this layer.
 */
public final class SketchGeometry {
    public enum Kind { LINE, CIRCLE, ARC }
    public enum ConstraintType { HORIZONTAL, VERTICAL, COINCIDENT, DISTANCE, DISTANCE_X, DISTANCE_Y, RADIUS, ANGLE }

    public static final class Entity {
        public final long id;
        public final Kind kind;
        public Vec2 a,b,center;
        public double radius;
        public double startAngle,endAngle;
        Entity(long id,Kind kind){this.id=id;this.kind=kind;}
        static Entity line(long id,Vec2 a,Vec2 b){Entity e=new Entity(id,Kind.LINE);e.a=a;e.b=b;return e;}
        static Entity circle(long id,Vec2 c,double r){Entity e=new Entity(id,Kind.CIRCLE);e.center=c;e.radius=r;return e;}
        static Entity arc(long id,Vec2 c,double r,double s,double t){Entity e=new Entity(id,Kind.ARC);e.center=c;e.radius=r;e.startAngle=s;e.endAngle=t;return e;}
    }

    public static final class Constraint {
        public final long id; public final ConstraintType type; public final long e1,e2;
        public final double value;
        Constraint(long id,ConstraintType type,long e1,long e2,double value){this.id=id;this.type=type;this.e1=e1;this.e2=e2;this.value=value;}
    }

    private long nextEntity=1,nextConstraint=1;
    private final LinkedHashMap<Long,Entity> entities=new LinkedHashMap<>();
    private final LinkedHashMap<Long,Constraint> constraints=new LinkedHashMap<>();

    public long addLine(Vec2 a,Vec2 b){ long id=nextEntity++; entities.put(id,Entity.line(id,a,b)); return id; }
    public long addCircle(Vec2 c,double r){ if(r<=0)throw new IllegalArgumentException("radius"); long id=nextEntity++; entities.put(id,Entity.circle(id,c,r)); return id; }
    public long addArc(Vec2 c,double r,double start,double end){ if(r<=0)throw new IllegalArgumentException("radius"); long id=nextEntity++; entities.put(id,Entity.arc(id,c,r,start,end)); return id; }
    public Entity get(long id){ return entities.get(id); }
    public Collection<Entity> entities(){ return Collections.unmodifiableCollection(entities.values()); }
    public Collection<Constraint> constraints(){ return Collections.unmodifiableCollection(constraints.values()); }

    public long addConstraint(ConstraintType type,long e1,long e2,double value){
        if(!entities.containsKey(e1))throw new IllegalArgumentException("e1");
        if(e2!=0&&!entities.containsKey(e2))throw new IllegalArgumentException("e2");
        long id=nextConstraint++; constraints.put(id,new Constraint(id,type,e1,e2,value)); return id;
    }

    public void move(long id,Vec2 delta){
        Entity e=required(id);
        if(e.kind==Kind.CIRCLE||e.kind==Kind.ARC)e.center=e.center.add(delta);
        else {e.a=e.a.add(delta);e.b=e.b.add(delta);}
    }
    public void setLineEnd(long id,boolean first,Vec2 p){Entity e=required(id);if(e.kind!=Kind.LINE)throw new IllegalArgumentException("not line");if(first)e.a=p;else e.b=p;}
    public void setCircleRadius(long id,double r){Entity e=required(id);if(e.kind==Kind.LINE)throw new IllegalArgumentException("not circle");if(r<=0)throw new IllegalArgumentException("radius");e.radius=r;}
    public void remove(long id){entities.remove(id);constraints.values().removeIf(c->c.e1==id||c.e2==id);}

    private Entity required(long id){Entity e=entities.get(id);if(e==null)throw new IllegalArgumentException("unknown entity "+id);return e;}

    /** Deterministic lightweight constraint pass. Exact solving remains replaceable without changing the UI contract. */
    public boolean solve(int iterations,double tolerance){
        boolean changed=false;
        for(int k=0;k<Math.max(1,iterations);k++){
            boolean pass=false;
            for(Constraint c:constraints.values()){
                Entity a=entities.get(c.e1), b=entities.get(c.e2); if(a==null)continue;
                switch(c.type){
                    case HORIZONTAL: if(a.kind==Kind.LINE){double y=(a.a.y+a.b.y)*0.5;a.a=new Vec2(a.a.x,y);a.b=new Vec2(a.b.x,y);pass=true;} break;
                    case VERTICAL: if(a.kind==Kind.LINE){double x=(a.a.x+a.b.x)*0.5;a.a=new Vec2(x,a.a.y);a.b=new Vec2(x,a.b.y);pass=true;} break;
                    case RADIUS: if(a.kind!=Kind.LINE&&Math.abs(a.radius-c.value)>tolerance){a.radius=c.value;pass=true;} break;
                    case DISTANCE: if(a.kind==Kind.LINE&&Math.abs(a.a.distance(a.b)-c.value)>tolerance){Vec2 d=a.b.sub(a.a);double n=d.length();if(n>1e-12)a.b=a.a.add(d.mul(c.value/n));pass=true;} break;
                    case DISTANCE_X: if(a.kind==Kind.LINE){double dx=c.value; a.b=new Vec2(a.a.x+dx,a.b.y);pass=true;} break;
                    case DISTANCE_Y: if(a.kind==Kind.LINE){double dy=c.value; a.b=new Vec2(a.b.x,a.a.y+dy);pass=true;} break;
                    case ANGLE: if(a.kind==Kind.LINE){double n=a.b.sub(a.a).length();if(n>1e-12){Vec2 d=new Vec2(Math.cos(Math.toRadians(c.value))*n,Math.sin(Math.toRadians(c.value))*n);a.b=a.a.add(d);pass=true;}} break;
                    case COINCIDENT: if(a.kind==Kind.LINE&&b!=null&&b.kind==Kind.LINE){b.a=a.b;pass=true;} break;
                }
            }
            changed|=pass;if(!pass)break;
        }
        return changed;
    }

    public List<Vec2> polyline(long id){
        Entity e=required(id); List<Vec2> out=new ArrayList<>();
        if(e.kind==Kind.LINE){out.add(e.a);out.add(e.b);return out;}
        int n=64; double s=e.kind==Kind.ARC?e.startAngle:0,t=e.kind==Kind.ARC?e.endAngle:Math.PI*2;
        for(int i=0;i<=n;i++){double q=s+(t-s)*i/n;out.add(e.center.add(new Vec2(Math.cos(q),Math.sin(q)).mul(e.radius)));} return out;
    }
}

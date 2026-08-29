package ir.chobyar.sketch.core.v2;

import java.util.*;

/** Deterministic analytic 2D operations used by the interaction layer. */
public final class GeometryOps {
    private GeometryOps(){}

    public static final class Hit {
        public final long first, second; public final Vec2 point;
        Hit(long first,long second,Vec2 point){this.first=first;this.second=second;this.point=point;}
    }

    public static List<Vec2> intersections(SketchGeometry.Entity a, SketchGeometry.Entity b){
        if(a.kind==SketchGeometry.Kind.LINE && b.kind==SketchGeometry.Kind.LINE){
            Vec2 p=a.a,r=a.b.sub(a.a),q=b.a,s=b.b.sub(b.a);
            double den=r.cross(s); if(Math.abs(den)<1e-10)return Collections.emptyList();
            double t=q.sub(p).cross(s)/den, u=q.sub(p).cross(r)/den;
            if(t>=-1e-10&&t<=1+1e-10&&u>=-1e-10&&u<=1+1e-10)
                return Collections.singletonList(p.add(r.mul(t)));
            return Collections.emptyList();
        }
        if(a.kind==SketchGeometry.Kind.LINE && b.kind!=SketchGeometry.Kind.LINE)
            return lineCircleLike(a,b);
        if(b.kind==SketchGeometry.Kind.LINE && a.kind!=SketchGeometry.Kind.LINE)
            return lineCircleLike(b,a);
        if(a.kind!=SketchGeometry.Kind.LINE && b.kind!=SketchGeometry.Kind.LINE){
            double dx=b.center.x-a.center.x,dy=b.center.y-a.center.y,d=Math.hypot(dx,dy);
            if(d<1e-10)return Collections.emptyList();
            if(d>a.radius+b.radius+1e-10||d<Math.abs(a.radius-b.radius)-1e-10)return Collections.emptyList();
            double x=(a.radius*a.radius-b.radius*b.radius+d*d)/(2*d);
            double h2=a.radius*a.radius-x*x;if(h2<0)h2=0;double h=Math.sqrt(h2);
            Vec2 base=a.center.add(new Vec2(dx/d,dy/d).mul(x));
            Vec2 off=new Vec2(-dy/d,dx/d).mul(h);
            if(h<1e-10)return Collections.singletonList(base);
            return Arrays.asList(base.add(off),base.sub(off));
        }
        return Collections.emptyList();
    }

    private static List<Vec2> lineCircleLike(SketchGeometry.Entity l,SketchGeometry.Entity c){
        Vec2 d=l.b.sub(l.a),f=l.a.sub(c.center);
        double A=d.dot(d); if(A<1e-15)return Collections.emptyList();
        double B=2*f.dot(d),C=f.dot(f)-c.radius*c.radius,disc=B*B-4*A*C;
        if(disc<-1e-10)return Collections.emptyList(); if(disc<0)disc=0;
        double root=Math.sqrt(disc); List<Vec2> out=new ArrayList<>();
        double[] ts={( -B-root)/(2*A),(-B+root)/(2*A)};
        for(double t:ts)if(t>=-1e-10&&t<=1+1e-10)out.add(l.a.add(d.mul(t)));
        return out;
    }

    public static Vec2 projectPointToLine(Vec2 p,Vec2 a,Vec2 b){
        Vec2 d=b.sub(a); double n=d.dot(d); if(n<1e-15)return a;
        double t=p.sub(a).dot(d)/n; return a.add(d.mul(t));
    }

    public static double distancePointToSegment(Vec2 p,Vec2 a,Vec2 b){
        return p.distance(clampPointToSegment(p,a,b));
    }

    public static Vec2 clampPointToSegment(Vec2 p,Vec2 a,Vec2 b){
        Vec2 d=b.sub(a); double n=d.dot(d); if(n<1e-15)return a;
        double t=Math.max(0,Math.min(1,p.sub(a).dot(d)/n)); return a.add(d.mul(t));
    }

    /** Offset of a line segment by signed distance; returns its two shifted endpoints. */
    public static Vec2[] offsetLine(Vec2 a,Vec2 b,double distance){
        Vec2 n=b.sub(a).normalized(); Vec2 normal=new Vec2(-n.y,n.x).mul(distance);
        return new Vec2[]{a.add(normal),b.add(normal)};
    }
}

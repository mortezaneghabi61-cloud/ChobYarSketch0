package ir.chobyar.sketch;

import android.graphics.PointF;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Dependency-free polygonal CSG kernel used by the Android prototype.
 *
 * Besides BSP Boolean operations this layer now owns the first parametric solid
 * generators used by the modeling workflow: Extrude, Revolve, Sweep and Loft.
 * Curves are tessellated for now; the UI contract is intentionally compatible
 * with a future exact B-Rep kernel.
 */
final class SolidCSG {

    static final class Vertex {
        final Geometry3D.Vec3 pos;
        Vertex(Geometry3D.Vec3 pos) { this.pos = pos; }
        Vertex copy() { return new Vertex(new Geometry3D.Vec3(pos.x, pos.y, pos.z)); }
        Vertex interpolate(Vertex other, float t) {
            return new Vertex(new Geometry3D.Vec3(
                    pos.x + (other.pos.x - pos.x) * t,
                    pos.y + (other.pos.y - pos.y) * t,
                    pos.z + (other.pos.z - pos.z) * t));
        }
    }

    static final class Plane {
        static final float EPSILON = 1e-4f;
        static final int COPLANAR = 0;
        static final int FRONT = 1;
        static final int BACK = 2;
        static final int SPANNING = 3;

        Geometry3D.Vec3 normal;
        float w;

        Plane(Geometry3D.Vec3 normal, float w) {
            this.normal = normal;
            this.w = w;
        }

        static Plane fromPoints(Geometry3D.Vec3 a, Geometry3D.Vec3 b, Geometry3D.Vec3 c) {
            Geometry3D.Vec3 n = b.sub(a).cross(c.sub(a)).normalized();
            return new Plane(n, n.dot(a));
        }

        Plane copy() { return new Plane(new Geometry3D.Vec3(normal.x, normal.y, normal.z), w); }

        void flip() {
            normal = normal.mul(-1f);
            w = -w;
        }

        void splitPolygon(Polygon polygon,
                          List<Polygon> coplanarFront,
                          List<Polygon> coplanarBack,
                          List<Polygon> front,
                          List<Polygon> back) {
            int polygonType = COPLANAR;
            int[] types = new int[polygon.vertices.size()];
            for (int i = 0; i < polygon.vertices.size(); i++) {
                float t = normal.dot(polygon.vertices.get(i).pos) - w;
                int type = t < -EPSILON ? BACK : (t > EPSILON ? FRONT : COPLANAR);
                polygonType |= type;
                types[i] = type;
            }

            switch (polygonType) {
                case COPLANAR:
                    if (normal.dot(polygon.plane.normal) > 0f) coplanarFront.add(polygon);
                    else coplanarBack.add(polygon);
                    break;
                case FRONT:
                    front.add(polygon);
                    break;
                case BACK:
                    back.add(polygon);
                    break;
                default:
                    List<Vertex> f = new ArrayList<>();
                    List<Vertex> b = new ArrayList<>();
                    for (int i = 0; i < polygon.vertices.size(); i++) {
                        int j = (i + 1) % polygon.vertices.size();
                        int ti = types[i], tj = types[j];
                        Vertex vi = polygon.vertices.get(i), vj = polygon.vertices.get(j);
                        if (ti != BACK) f.add(vi);
                        if (ti != FRONT) b.add(vi.copy());
                        if ((ti | tj) == SPANNING) {
                            Geometry3D.Vec3 delta = vj.pos.sub(vi.pos);
                            float denom = normal.dot(delta);
                            if (Math.abs(denom) > 1e-8f) {
                                float t = (w - normal.dot(vi.pos)) / denom;
                                Vertex v = vi.interpolate(vj, t);
                                f.add(v);
                                b.add(v.copy());
                            }
                        }
                    }
                    if (f.size() >= 3) front.add(new Polygon(f));
                    if (b.size() >= 3) back.add(new Polygon(b));
                    break;
            }
        }
    }

    static final class Polygon {
        final List<Vertex> vertices;
        Plane plane;

        Polygon(List<Vertex> vertices) {
            this.vertices = new ArrayList<>(vertices);
            this.plane = computePlane(this.vertices);
        }

        private static Plane computePlane(List<Vertex> v) {
            if (v.size() < 3) return new Plane(new Geometry3D.Vec3(0,0,1), 0);
            Geometry3D.Vec3 a = v.get(0).pos;
            for (int i = 1; i < v.size() - 1; i++) {
                Geometry3D.Vec3 b = v.get(i).pos;
                Geometry3D.Vec3 c = v.get(i + 1).pos;
                Geometry3D.Vec3 cross = b.sub(a).cross(c.sub(a));
                if (cross.length() > 1e-6f) return Plane.fromPoints(a, b, c);
            }
            return new Plane(new Geometry3D.Vec3(0,0,1), 0);
        }

        Polygon copy() {
            List<Vertex> out = new ArrayList<>();
            for (Vertex v : vertices) out.add(v.copy());
            return new Polygon(out);
        }

        void flip() {
            Collections.reverse(vertices);
            plane.flip();
        }

        Geometry3D.Vec3 centroid() {
            float x=0,y=0,z=0;
            for (Vertex v : vertices) { x+=v.pos.x; y+=v.pos.y; z+=v.pos.z; }
            float n=Math.max(1,vertices.size());
            return new Geometry3D.Vec3(x/n,y/n,z/n);
        }
    }

    private static final class Node {
        Plane plane;
        final List<Polygon> polygons = new ArrayList<>();
        Node front;
        Node back;

        Node() {}
        Node(List<Polygon> polygons) { build(polygons); }

        Node copy() {
            Node n = new Node();
            n.plane = plane == null ? null : plane.copy();
            for (Polygon p : polygons) n.polygons.add(p.copy());
            if (front != null) n.front = front.copy();
            if (back != null) n.back = back.copy();
            return n;
        }

        void invert() {
            for (Polygon p : polygons) p.flip();
            if (plane != null) plane.flip();
            if (front != null) front.invert();
            if (back != null) back.invert();
            Node temp = front; front = back; back = temp;
        }

        List<Polygon> clipPolygons(List<Polygon> input) {
            if (plane == null) return clonePolygons(input);
            List<Polygon> frontList = new ArrayList<>();
            List<Polygon> backList = new ArrayList<>();
            for (Polygon p : input) plane.splitPolygon(p, frontList, backList, frontList, backList);
            if (front != null) frontList = front.clipPolygons(frontList);
            if (back != null) backList = back.clipPolygons(backList);
            else backList.clear();
            frontList.addAll(backList);
            return frontList;
        }

        void clipTo(Node bsp) {
            List<Polygon> clipped = bsp.clipPolygons(polygons);
            polygons.clear(); polygons.addAll(clipped);
            if (front != null) front.clipTo(bsp);
            if (back != null) back.clipTo(bsp);
        }

        List<Polygon> allPolygons() {
            List<Polygon> out = clonePolygons(polygons);
            if (front != null) out.addAll(front.allPolygons());
            if (back != null) out.addAll(back.allPolygons());
            return out;
        }

        void build(List<Polygon> input) {
            if (input == null || input.isEmpty()) return;
            if (plane == null) plane = input.get(0).plane.copy();
            List<Polygon> frontList = new ArrayList<>();
            List<Polygon> backList = new ArrayList<>();
            for (Polygon p : input) plane.splitPolygon(p, polygons, polygons, frontList, backList);
            if (!frontList.isEmpty()) {
                if (front == null) front = new Node();
                front.build(frontList);
            }
            if (!backList.isEmpty()) {
                if (back == null) back = new Node();
                back.build(backList);
            }
        }
    }

    private final List<Polygon> polygons;

    private SolidCSG(List<Polygon> polygons) { this.polygons = clonePolygons(polygons); }

    static SolidCSG fromPolygons(List<Polygon> polygons) { return new SolidCSG(polygons); }
    List<Polygon> polygons() { return polygons; }
    SolidCSG copy() { return new SolidCSG(polygons); }
    boolean isEmpty() { return polygons.isEmpty(); }

    SolidCSG union(SolidCSG other) {
        Node a = new Node(clonePolygons(polygons));
        Node b = new Node(clonePolygons(other.polygons));
        a.clipTo(b); b.clipTo(a); b.invert(); b.clipTo(a); b.invert(); a.build(b.allPolygons());
        return fromPolygons(a.allPolygons());
    }

    SolidCSG subtract(SolidCSG other) {
        Node a = new Node(clonePolygons(polygons));
        Node b = new Node(clonePolygons(other.polygons));
        a.invert(); a.clipTo(b); b.clipTo(a); b.invert(); b.clipTo(a); b.invert(); a.build(b.allPolygons()); a.invert();
        return fromPolygons(a.allPolygons());
    }

    SolidCSG intersect(SolidCSG other) {
        Node a = new Node(clonePolygons(polygons));
        Node b = new Node(clonePolygons(other.polygons));
        a.invert(); b.clipTo(a); b.invert(); a.clipTo(b); b.clipTo(a); a.build(b.allPolygons()); a.invert();
        return fromPolygons(a.allPolygons());
    }

    static SolidCSG extrude(List<PointF> rawProfile, Geometry3D.Plane3D plane, float signedHeightMm) {
        List<PointF> profile = cleanProfile(rawProfile);
        if (profile.size() < 3 || Math.abs(signedHeightMm) < 1e-4f) return empty();
        if (signedArea(profile) < 0f) Collections.reverse(profile);
        Geometry3D.Vec3 dir = plane.normal;
        float h = signedHeightMm;
        if (h < 0f) { h=-h; dir=dir.mul(-1f); Collections.reverse(profile); }

        List<Geometry3D.Vec3> bottom = new ArrayList<>();
        List<Geometry3D.Vec3> top = new ArrayList<>();
        Geometry3D.Vec3 offset = dir.mul(h);
        for (PointF p : profile) { Geometry3D.Vec3 b=plane.point(p.x,p.y); bottom.add(b); top.add(b.add(offset)); }

        List<Polygon> polys = new ArrayList<>();
        addCap(polys,bottom,true);
        addCap(polys,top,false);
        connectRings(polys,bottom,top,true);
        return fromPolygons(polys);
    }

    /** Revolves a closed sketch profile around an arbitrary 3D axis. */
    static SolidCSG revolve(List<PointF> rawProfile,
                            Geometry3D.Plane3D profilePlane,
                            Geometry3D.Vec3 axisPoint,
                            Geometry3D.Vec3 axisDirection,
                            float signedAngleDeg,
                            int requestedSteps) {
        List<PointF> profile=cleanProfile(rawProfile);
        Geometry3D.Vec3 axis=axisDirection==null?null:axisDirection.normalized();
        float abs=Math.abs(signedAngleDeg);
        if(profile.size()<3||axis==null||axis.length()<1e-6f||abs<0.01f)return empty();
        if(signedArea(profile)<0f)Collections.reverse(profile);
        int steps=Math.max(4,Math.min(144,requestedSteps));
        boolean full=abs>=359.999f;
        int ringCount=full?steps:steps+1;
        double total=Math.toRadians(signedAngleDeg);
        List<List<Geometry3D.Vec3>> rings=new ArrayList<>();
        for(int i=0;i<ringCount;i++){
            double t=full?(double)i/steps:(double)i/steps;
            double a=total*t;
            List<Geometry3D.Vec3> ring=new ArrayList<>();
            for(PointF p:profile)ring.add(rotateAroundAxis(profilePlane.point(p.x,p.y),axisPoint,axis,a));
            rings.add(ring);
        }
        List<Polygon> polys=new ArrayList<>();
        int pairs=full?ringCount:ringCount-1;
        for(int i=0;i<pairs;i++)connectRings(polys,rings.get(i),rings.get((i+1)%ringCount),true);
        if(!full){addCap(polys,rings.get(0),true);addCap(polys,rings.get(rings.size()-1),false);}
        return fromPolygons(polys);
    }

    /**
     * Revolves a closed profile while translating it along the same axis.
     *
     * A zero axial height is an ordinary revolve.  A non-zero height is the
     * helical-revolve operation used for real screw/lathe threads: angle
     * controls the number of turns and axialHeightMm controls the total lead.
     * The start and end profiles are capped, so the result is a closed solid
     * that can immediately participate in Union/Subtract/Intersect.
     */
    static SolidCSG helicalRevolve(List<PointF> rawProfile,
                                   Geometry3D.Plane3D profilePlane,
                                   Geometry3D.Vec3 axisPoint,
                                   Geometry3D.Vec3 axisDirection,
                                   float signedAngleDeg,
                                   float axialHeightMm,
                                   int requestedSteps) {
        if (Math.abs(axialHeightMm) < 1e-5f) {
            return revolve(rawProfile,profilePlane,axisPoint,axisDirection,signedAngleDeg,requestedSteps);
        }
        List<PointF> profile=cleanProfile(rawProfile);
        Geometry3D.Vec3 axis=axisDirection==null?null:axisDirection.normalized();
        float abs=Math.abs(signedAngleDeg);
        if(profile.size()<3||axis==null||axis.length()<1e-6f||abs<0.01f)return empty();
        if(signedArea(profile)<0f)Collections.reverse(profile);

        int steps=Math.max(8,Math.min(1440,requestedSteps));
        double total=Math.toRadians(signedAngleDeg);
        List<List<Geometry3D.Vec3>> rings=new ArrayList<>();
        for(int i=0;i<=steps;i++){
            float t=(float)i/steps;
            double angle=total*t;
            Geometry3D.Vec3 lead=axis.mul(axialHeightMm*t);
            List<Geometry3D.Vec3> ring=new ArrayList<>();
            for(PointF p:profile){
                Geometry3D.Vec3 turned=rotateAroundAxis(profilePlane.point(p.x,p.y),axisPoint,axis,angle);
                ring.add(turned.add(lead));
            }
            rings.add(ring);
        }
        List<Polygon> polys=new ArrayList<>();
        addCap(polys,rings.get(0),true);
        for(int i=0;i<steps;i++)connectRings(polys,rings.get(i),rings.get(i+1),true);
        addCap(polys,rings.get(rings.size()-1),false);
        return fromPolygons(polys);
    }

    /** Sweeps a closed profile through a 3D polyline path using transported frames. */
    static SolidCSG sweep(List<PointF> rawProfile,
                          Geometry3D.Plane3D profilePlane,
                          List<Geometry3D.Vec3> rawPath) {
        List<PointF> profile=cleanProfile(rawProfile);
        List<Geometry3D.Vec3> path=cleanPath(rawPath);
        if(profile.size()<3||path.size()<2)return empty();
        if(signedArea(profile)<0f)Collections.reverse(profile);
        PointF c=centroid2(profile);
        List<List<Geometry3D.Vec3>> rings=new ArrayList<>();
        Geometry3D.Vec3 previousU=profilePlane.u;
        for(int i=0;i<path.size();i++){
            Geometry3D.Vec3 tangent;
            if(i==0)tangent=path.get(1).sub(path.get(0)).normalized();
            else if(i==path.size()-1)tangent=path.get(i).sub(path.get(i-1)).normalized();
            else tangent=path.get(i+1).sub(path.get(i-1)).normalized();
            if(tangent.length()<1e-6f)return empty();
            Geometry3D.Vec3 u=projectPerpendicular(previousU,tangent).normalized();
            if(u.length()<1e-6f)u=projectPerpendicular(profilePlane.v,tangent).normalized();
            if(u.length()<1e-6f)u=anyPerpendicular(tangent);
            Geometry3D.Vec3 v=tangent.cross(u).normalized();
            previousU=u;
            List<Geometry3D.Vec3> ring=new ArrayList<>();
            for(PointF p:profile){
                float du=p.x-c.x,dv=p.y-c.y;
                ring.add(path.get(i).add(u.mul(du)).add(v.mul(dv)));
            }
            rings.add(ring);
        }
        List<Polygon> polys=new ArrayList<>();
        addCap(polys,rings.get(0),true);
        for(int i=0;i<rings.size()-1;i++)connectRings(polys,rings.get(i),rings.get(i+1),true);
        addCap(polys,rings.get(rings.size()-1),false);
        return fromPolygons(polys);
    }

    /** Creates a solid transition between two closed profiles on arbitrary sketch planes. */
    static SolidCSG loft(List<PointF> rawA,Geometry3D.Plane3D planeA,
                         List<PointF> rawB,Geometry3D.Plane3D planeB,
                         int sampleCount) {
        List<PointF> a=cleanProfile(rawA),b=cleanProfile(rawB);
        if(a.size()<3||b.size()<3)return empty();
        int n=Math.max(8,Math.min(128,Math.max(sampleCount,Math.max(a.size(),b.size()))));
        a=resampleClosed(a,n);b=resampleClosed(b,n);
        if(signedArea(a)<0f)Collections.reverse(a);
        if(signedArea(b)<0f)Collections.reverse(b);
        List<Geometry3D.Vec3> ra=new ArrayList<>(),rb=new ArrayList<>();
        for(PointF p:a)ra.add(planeA.point(p.x,p.y));
        for(PointF p:b)rb.add(planeB.point(p.x,p.y));
        List<Polygon> polys=new ArrayList<>();
        addCap(polys,ra,true);
        connectRings(polys,ra,rb,true);
        addCap(polys,rb,false);
        return fromPolygons(polys);
    }

    private static SolidCSG empty(){return fromPolygons(new ArrayList<>());}

    private static void connectRings(List<Polygon> out,List<Geometry3D.Vec3> a,List<Geometry3D.Vec3> b,boolean forward){
        int n=Math.min(a.size(),b.size());
        for(int i=0;i<n;i++){
            int j=(i+1)%n;
            List<Geometry3D.Vec3> q=new ArrayList<>();
            if(forward){q.add(a.get(i));q.add(a.get(j));q.add(b.get(j));q.add(b.get(i));}
            else{q.add(a.get(i));q.add(b.get(i));q.add(b.get(j));q.add(a.get(j));}
            addPolygon(out,q);
        }
    }

    private static void addCap(List<Polygon> out,List<Geometry3D.Vec3> ring,boolean reverse){
        List<Geometry3D.Vec3> p=new ArrayList<>(ring);
        if(reverse)Collections.reverse(p);
        addPolygon(out,p);
    }

    private static void addPolygon(List<Polygon> out,List<Geometry3D.Vec3> points){
        List<Geometry3D.Vec3> clean=new ArrayList<>();
        for(Geometry3D.Vec3 p:points){
            if(p==null)continue;
            if(clean.isEmpty()||clean.get(clean.size()-1).sub(p).length()>1e-5f)clean.add(p);
        }
        if(clean.size()>2&&clean.get(0).sub(clean.get(clean.size()-1)).length()<1e-5f)clean.remove(clean.size()-1);
        if(clean.size()<3)return;
        Geometry3D.Vec3 a=clean.get(0);boolean valid=false;
        for(int i=1;i<clean.size()-1;i++)if(clean.get(i).sub(a).cross(clean.get(i+1).sub(a)).length()>1e-5f){valid=true;break;}
        if(!valid)return;
        List<Vertex> verts=new ArrayList<>();for(Geometry3D.Vec3 p:clean)verts.add(new Vertex(p));
        out.add(new Polygon(verts));
    }

    private static Geometry3D.Vec3 rotateAroundAxis(Geometry3D.Vec3 p,Geometry3D.Vec3 origin,Geometry3D.Vec3 axis,double angle){
        Geometry3D.Vec3 r=p.sub(origin);
        float c=(float)Math.cos(angle),s=(float)Math.sin(angle);
        Geometry3D.Vec3 term1=r.mul(c);
        Geometry3D.Vec3 term2=axis.cross(r).mul(s);
        Geometry3D.Vec3 term3=axis.mul(axis.dot(r)*(1f-c));
        return origin.add(term1.add(term2).add(term3));
    }

    private static Geometry3D.Vec3 projectPerpendicular(Geometry3D.Vec3 v,Geometry3D.Vec3 normal){return v.sub(normal.mul(v.dot(normal)));}
    private static Geometry3D.Vec3 anyPerpendicular(Geometry3D.Vec3 n){
        Geometry3D.Vec3 ref=Math.abs(n.z)<0.85f?new Geometry3D.Vec3(0,0,1):new Geometry3D.Vec3(0,1,0);
        return ref.cross(n).normalized();
    }

    private static List<Geometry3D.Vec3> cleanPath(List<Geometry3D.Vec3> input){
        List<Geometry3D.Vec3> out=new ArrayList<>();if(input==null)return out;
        for(Geometry3D.Vec3 p:input)if(p!=null&&(out.isEmpty()||p.sub(out.get(out.size()-1)).length()>1e-4f))out.add(p);
        return out;
    }

    private static List<PointF> cleanProfile(List<PointF> input) {
        List<PointF> out = new ArrayList<>();
        if (input == null) return out;
        for (PointF p : input) {
            if (p == null) continue;
            if (out.isEmpty() || dist(out.get(out.size()-1), p) > 1e-4f) out.add(new PointF(p.x,p.y));
        }
        if (out.size() > 2 && dist(out.get(0), out.get(out.size()-1)) < 1e-4f) out.remove(out.size()-1);
        return out;
    }

    private static List<PointF> resampleClosed(List<PointF> p,int count){
        List<PointF> out=new ArrayList<>();if(p.size()<2||count<3)return out;
        float[] cumulative=new float[p.size()+1];
        for(int i=0;i<p.size();i++)cumulative[i+1]=cumulative[i]+dist(p.get(i),p.get((i+1)%p.size()));
        float total=cumulative[p.size()];if(total<1e-6f)return new ArrayList<>(p);
        for(int k=0;k<count;k++){
            float target=total*k/count;int seg=0;
            while(seg<p.size()-1&&cumulative[seg+1]<target)seg++;
            PointF a=p.get(seg),b=p.get((seg+1)%p.size());float len=cumulative[seg+1]-cumulative[seg];
            float t=len<1e-6f?0f:(target-cumulative[seg])/len;
            out.add(new PointF(a.x+(b.x-a.x)*t,a.y+(b.y-a.y)*t));
        }
        return out;
    }

    private static PointF centroid2(List<PointF> p){float x=0,y=0;for(PointF q:p){x+=q.x;y+=q.y;}return new PointF(x/p.size(),y/p.size());}

    private static float signedArea(List<PointF> p) {
        float a = 0f;
        for (int i=0;i<p.size();i++) { PointF q=p.get(i), r=p.get((i+1)%p.size()); a += q.x*r.y-r.x*q.y; }
        return a*0.5f;
    }

    private static float dist(PointF a, PointF b) { return (float)Math.hypot(a.x-b.x,a.y-b.y); }

    private static List<Polygon> clonePolygons(List<Polygon> input) {
        List<Polygon> out = new ArrayList<>();
        if (input != null) for (Polygon p : input) out.add(p.copy());
        return out;
    }
}

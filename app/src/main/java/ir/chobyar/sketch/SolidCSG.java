package ir.chobyar.sketch;

import android.graphics.PointF;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Dependency-free polygonal CSG kernel used by the Android prototype.
 *
 * This is a real volumetric constructive-solid-geometry implementation based on
 * BSP polygon splitting. Curved sketch profiles are tessellated before entering
 * the kernel; later the same UI contract can be backed by an exact B-Rep kernel.
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
            for (Polygon p : input) {
                plane.splitPolygon(p, frontList, backList, frontList, backList);
            }
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
            for (Polygon p : input) {
                plane.splitPolygon(p, polygons, polygons, frontList, backList);
            }
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

    private SolidCSG(List<Polygon> polygons) {
        this.polygons = clonePolygons(polygons);
    }

    static SolidCSG fromPolygons(List<Polygon> polygons) { return new SolidCSG(polygons); }

    List<Polygon> polygons() { return polygons; }

    SolidCSG copy() { return new SolidCSG(polygons); }

    boolean isEmpty() { return polygons.isEmpty(); }

    SolidCSG union(SolidCSG other) {
        Node a = new Node(clonePolygons(polygons));
        Node b = new Node(clonePolygons(other.polygons));
        a.clipTo(b);
        b.clipTo(a);
        b.invert();
        b.clipTo(a);
        b.invert();
        a.build(b.allPolygons());
        return fromPolygons(a.allPolygons());
    }

    SolidCSG subtract(SolidCSG other) {
        Node a = new Node(clonePolygons(polygons));
        Node b = new Node(clonePolygons(other.polygons));
        a.invert();
        a.clipTo(b);
        b.clipTo(a);
        b.invert();
        b.clipTo(a);
        b.invert();
        a.build(b.allPolygons());
        a.invert();
        return fromPolygons(a.allPolygons());
    }

    SolidCSG intersect(SolidCSG other) {
        Node a = new Node(clonePolygons(polygons));
        Node b = new Node(clonePolygons(other.polygons));
        a.invert();
        b.clipTo(a);
        b.invert();
        a.clipTo(b);
        b.clipTo(a);
        a.build(b.allPolygons());
        a.invert();
        return fromPolygons(a.allPolygons());
    }

    static SolidCSG extrude(List<PointF> rawProfile, Geometry3D.Plane3D plane, float signedHeightMm) {
        List<PointF> profile = cleanProfile(rawProfile);
        if (profile.size() < 3 || Math.abs(signedHeightMm) < 1e-4f) return fromPolygons(new ArrayList<>());

        if (signedArea(profile) < 0f) Collections.reverse(profile);
        Geometry3D.Vec3 dir = plane.normal;
        float h = signedHeightMm;
        if (h < 0f) {
            h = -h;
            dir = dir.mul(-1f);
            Collections.reverse(profile);
        }

        List<Geometry3D.Vec3> bottom = new ArrayList<>();
        List<Geometry3D.Vec3> top = new ArrayList<>();
        Geometry3D.Vec3 offset = dir.mul(h);
        for (PointF p : profile) {
            Geometry3D.Vec3 b = plane.point(p.x, p.y);
            bottom.add(b);
            top.add(b.add(offset));
        }

        List<Polygon> polys = new ArrayList<>();
        List<Vertex> bottomFace = new ArrayList<>();
        for (int i = bottom.size()-1; i >= 0; i--) bottomFace.add(new Vertex(bottom.get(i)));
        polys.add(new Polygon(bottomFace));

        List<Vertex> topFace = new ArrayList<>();
        for (Geometry3D.Vec3 p : top) topFace.add(new Vertex(p));
        polys.add(new Polygon(topFace));

        for (int i = 0; i < bottom.size(); i++) {
            int j = (i + 1) % bottom.size();
            List<Vertex> side = new ArrayList<>();
            side.add(new Vertex(bottom.get(i)));
            side.add(new Vertex(bottom.get(j)));
            side.add(new Vertex(top.get(j)));
            side.add(new Vertex(top.get(i)));
            polys.add(new Polygon(side));
        }
        return fromPolygons(polys);
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

    private static float signedArea(List<PointF> p) {
        float a = 0f;
        for (int i=0;i<p.size();i++) {
            PointF q=p.get(i), r=p.get((i+1)%p.size());
            a += q.x*r.y-r.x*q.y;
        }
        return a*0.5f;
    }

    private static float dist(PointF a, PointF b) {
        return (float)Math.hypot(a.x-b.x,a.y-b.y);
    }

    private static List<Polygon> clonePolygons(List<Polygon> input) {
        List<Polygon> out = new ArrayList<>();
        if (input != null) for (Polygon p : input) out.add(p.copy());
        return out;
    }
}

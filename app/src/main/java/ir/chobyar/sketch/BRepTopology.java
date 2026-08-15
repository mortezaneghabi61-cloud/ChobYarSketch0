package ir.chobyar.sketch;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Lightweight boundary-representation topology extracted from the current
 * polygonal solid kernel.
 *
 * This is the first explicit B-Rep layer in ChobYar: Bodies are represented as
 * Faces bounded by Edges bounded by Vertices, with adjacency, measurements and
 * manifold validation. Straight edges and planar faces are geometrically exact;
 * tessellated curves are still polygonal until the native exact-curve kernel is
 * introduced.
 */
final class BRepTopology {

    static final class TopoVertex {
        final String id;
        final Geometry3D.Vec3 point;
        TopoVertex(String id, Geometry3D.Vec3 point) { this.id=id; this.point=point; }
    }

    static final class TopoEdge {
        final String id;
        final TopoVertex a;
        final TopoVertex b;
        final List<Integer> faceIndices = new ArrayList<>();
        TopoEdge(String id, TopoVertex a, TopoVertex b) { this.id=id; this.a=a; this.b=b; }
        float lengthMm() { return b.point.sub(a.point).length(); }
        boolean isBoundary() { return faceIndices.size()==1; }
        boolean isManifold() { return faceIndices.size()==2; }
    }

    static final class TopoFace {
        final String id;
        final SolidCSG.Polygon polygon;
        final Geometry3D.Vec3 normal;
        final Geometry3D.Vec3 centroid;
        final float areaMm2;
        final float perimeterMm;
        final String planeGroup;
        TopoFace(String id, SolidCSG.Polygon polygon, Geometry3D.Vec3 normal,
                 Geometry3D.Vec3 centroid, float areaMm2, float perimeterMm,
                 String planeGroup) {
            this.id=id; this.polygon=polygon; this.normal=normal; this.centroid=centroid;
            this.areaMm2=areaMm2; this.perimeterMm=perimeterMm; this.planeGroup=planeGroup;
        }
    }

    final List<TopoVertex> vertices = new ArrayList<>();
    final List<TopoEdge> edges = new ArrayList<>();
    final List<TopoFace> faces = new ArrayList<>();
    final Map<String,List<Integer>> coplanarFaceGroups = new LinkedHashMap<>();

    final int boundaryEdgeCount;
    final int nonManifoldEdgeCount;
    final int sharpEdgeCount;
    final float surfaceAreaMm2;
    final float volumeMm3;

    private BRepTopology(List<TopoVertex> vertices,
                         List<TopoEdge> edges,
                         List<TopoFace> faces,
                         Map<String,List<Integer>> groups,
                         int boundaryEdges,
                         int nonManifoldEdges,
                         int sharpEdges,
                         float area,
                         float volume) {
        this.vertices.addAll(vertices);
        this.edges.addAll(edges);
        this.faces.addAll(faces);
        this.coplanarFaceGroups.putAll(groups);
        this.boundaryEdgeCount=boundaryEdges;
        this.nonManifoldEdgeCount=nonManifoldEdges;
        this.sharpEdgeCount=sharpEdges;
        this.surfaceAreaMm2=area;
        this.volumeMm3=volume;
    }

    static BRepTopology build(SolidCSG csg) {
        if (csg == null || csg.isEmpty()) {
            return new BRepTopology(new ArrayList<>(),new ArrayList<>(),new ArrayList<>(),
                    new LinkedHashMap<>(),0,0,0,0f,0f);
        }

        Map<String,TopoVertex> vertexByKey = new LinkedHashMap<>();
        Map<String,TopoEdge> edgeByKey = new LinkedHashMap<>();
        List<TopoFace> faces = new ArrayList<>();
        Map<String,List<Integer>> groups = new LinkedHashMap<>();
        float surface=0f;
        double signedVolume=0.0;

        List<SolidCSG.Polygon> polygons=csg.polygons();
        for(int fi=0;fi<polygons.size();fi++) {
            SolidCSG.Polygon p=polygons.get(fi);
            if(p==null||p.vertices.size()<3)continue;
            float area=polygonArea(p);
            float perimeter=polygonPerimeter(p);
            Geometry3D.Vec3 normal=p.plane.normal.normalized();
            Geometry3D.Vec3 centroid=p.centroid();
            String planeGroup=planeKey(normal,p.plane.w);
            String faceId="F-"+shortHash(planeGroup+"|"+vecKey(centroid)+"|"+q(area));
            TopoFace face=new TopoFace(faceId,p,normal,centroid,area,perimeter,planeGroup);
            int faceIndex=faces.size();
            faces.add(face);
            List<Integer> same=groups.get(planeGroup);
            if(same==null){same=new ArrayList<>();groups.put(planeGroup,same);} same.add(faceIndex);
            surface+=area;
            signedVolume+=polygonSignedVolume(p);

            for(int i=0;i<p.vertices.size();i++) {
                Geometry3D.Vec3 pa=p.vertices.get(i).pos;
                Geometry3D.Vec3 pb=p.vertices.get((i+1)%p.vertices.size()).pos;
                String ka=vecKey(pa),kb=vecKey(pb);
                TopoVertex va=vertexByKey.get(ka);
                if(va==null){va=new TopoVertex("V-"+shortHash(ka),copy(pa));vertexByKey.put(ka,va);}
                TopoVertex vb=vertexByKey.get(kb);
                if(vb==null){vb=new TopoVertex("V-"+shortHash(kb),copy(pb));vertexByKey.put(kb,vb);}
                String ek=ka.compareTo(kb)<=0?ka+"|"+kb:kb+"|"+ka;
                TopoEdge edge=edgeByKey.get(ek);
                if(edge==null){edge=new TopoEdge("E-"+shortHash(ek),va,vb);edgeByKey.put(ek,edge);}
                if(!edge.faceIndices.contains(faceIndex))edge.faceIndices.add(faceIndex);
            }
        }

        List<TopoVertex> vertices=new ArrayList<>(vertexByKey.values());
        List<TopoEdge> edges=new ArrayList<>(edgeByKey.values());
        int boundary=0,nonManifold=0,sharp=0;
        for(TopoEdge e:edges) {
            if(e.faceIndices.size()==1)boundary++;
            else if(e.faceIndices.size()!=2)nonManifold++;
            if(e.faceIndices.size()==2) {
                TopoFace a=faces.get(e.faceIndices.get(0));
                TopoFace b=faces.get(e.faceIndices.get(1));
                float d=clamp(a.normal.dot(b.normal),-1f,1f);
                float angle=(float)Math.toDegrees(Math.acos(d));
                // Ignore tiny tessellation bends; count feature-like creases.
                if(angle>8f && angle<352f)sharp++;
            }
        }

        return new BRepTopology(vertices,edges,faces,groups,boundary,nonManifold,sharp,
                surface,(float)Math.abs(signedVolume));
    }

    boolean isClosedManifold() {
        if(faces.isEmpty())return false;
        if(boundaryEdgeCount!=0||nonManifoldEdgeCount!=0)return false;
        for(TopoEdge e:edges)if(!e.isManifold())return false;
        return true;
    }

    TopoFace findFace(SolidCSG.Polygon polygon) {
        if(polygon==null)return null;
        for(TopoFace f:faces)if(f.polygon==polygon)return f;
        Geometry3D.Vec3 c=polygon.centroid();
        Geometry3D.Vec3 n=polygon.plane.normal.normalized();
        TopoFace best=null;float bestScore=Float.MAX_VALUE;
        for(TopoFace f:faces) {
            float dc=f.centroid.sub(c).length();
            float dn=1f-clamp(f.normal.dot(n),-1f,1f);
            float score=dc+dn*100f;
            if(score<bestScore){bestScore=score;best=f;}
        }
        return bestScore<0.5f?best:null;
    }

    String summary() {
        return "B-Rep Topology\n"
                +"Vertex: "+vertices.size()+"   Edge: "+edges.size()+"   Face: "+faces.size()+"\n"
                +"سطوح هم‌صفحه: "+coplanarFaceGroups.size()+"   Edge تیز: "+sharpEdgeCount+"\n"
                +"Surface: "+dualArea(surfaceAreaMm2)+"\n"
                +"Volume: "+dualVolume(volumeMm3)+"\n"
                +(isClosedManifold()?"✓ Solid بسته و Manifold":"⚠ Boundary="+boundaryEdgeCount+" • Non-manifold="+nonManifoldEdgeCount);
    }

    static String faceInfo(TopoFace f) {
        if(f==null)return"Face انتخاب نشده";
        return f.id+"\nArea: "+dualArea(f.areaMm2)+"\nPerimeter: "+dualLength(f.perimeterMm)
                +"\nNormal: ("+num(f.normal.x)+", "+num(f.normal.y)+", "+num(f.normal.z)+")";
    }

    private static float polygonArea(SolidCSG.Polygon p) {
        Geometry3D.Vec3 a=p.vertices.get(0).pos;float area=0f;
        for(int i=1;i<p.vertices.size()-1;i++) {
            Geometry3D.Vec3 b=p.vertices.get(i).pos,c=p.vertices.get(i+1).pos;
            area+=b.sub(a).cross(c.sub(a)).length()*0.5f;
        }
        return area;
    }

    private static float polygonPerimeter(SolidCSG.Polygon p) {
        float d=0f;for(int i=0;i<p.vertices.size();i++)d+=p.vertices.get((i+1)%p.vertices.size()).pos.sub(p.vertices.get(i).pos).length();return d;
    }

    private static double polygonSignedVolume(SolidCSG.Polygon p) {
        Geometry3D.Vec3 a=p.vertices.get(0).pos;double v=0.0;
        for(int i=1;i<p.vertices.size()-1;i++) {
            Geometry3D.Vec3 b=p.vertices.get(i).pos,c=p.vertices.get(i+1).pos;
            v+=a.dot(b.cross(c))/6.0;
        }
        return v;
    }

    private static String planeKey(Geometry3D.Vec3 n,float w) {
        // Preserve orientation because opposite caps are distinct semantic faces.
        return q(n.x)+","+q(n.y)+","+q(n.z)+"@"+q(w);
    }

    private static Geometry3D.Vec3 copy(Geometry3D.Vec3 p){return new Geometry3D.Vec3(p.x,p.y,p.z);}
    private static String vecKey(Geometry3D.Vec3 p){return q(p.x)+","+q(p.y)+","+q(p.z);}
    private static long q(float v){return Math.round(v*1000f);}
    private static String shortHash(String s){return Integer.toHexString(s.hashCode()).toUpperCase(Locale.US);}
    private static float clamp(float v,float a,float b){return Math.max(a,Math.min(b,v));}
    private static String num(float v){String s=String.format(Locale.US,"%.3f",v);while(s.contains(".")&&(s.endsWith("0")||s.endsWith(".")))s=s.substring(0,s.length()-1);return s;}
    private static String dualLength(float mm){return num(mm/10f)+" cm / "+num(mm)+" mm";}
    private static String dualArea(float mm2){return num(mm2/100f)+" cm² / "+num(mm2)+" mm²";}
    private static String dualVolume(float mm3){return num(mm3/1000f)+" cm³ / "+num(mm3)+" mm³";}
}

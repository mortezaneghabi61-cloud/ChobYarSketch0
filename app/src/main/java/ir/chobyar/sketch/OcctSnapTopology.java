package ir.chobyar.sketch;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Lightweight snapping topology reconstructed from the display triangulation of
 * the exact OCCT TopoDS_Shape.
 *
 * This is deliberately stricter than treating every mesh node as CAD topology:
 * - internal coplanar triangle diagonals are removed;
 * - sharp/boundary segments are chained into logical body edges;
 * - degree-2 tessellation nodes on curves are not exposed as fake vertices;
 * - planar triangles are grouped into logical faces;
 * - inner planar boundary loops produce hole-center notable points.
 *
 * Native OCCT handles remain the source of geometry. This class only derives the
 * interaction references needed by the Android sketch workspace.
 */
final class OcctSnapTopology {
    static final int VERTEX = 1;
    static final int EDGE_MIDPOINT = 2;
    static final int FACE_CENTER = 3;
    static final int HOLE_CENTER = 4;

    static final class Notable {
        final int kind;
        final Geometry3D.Vec3 p;
        Notable(int kind, Geometry3D.Vec3 p){this.kind=kind;this.p=p;}
    }

    static final class EdgeChain {
        final List<Geometry3D.Vec3> points;
        final boolean closed;
        EdgeChain(List<Geometry3D.Vec3> points, boolean closed){this.points=points;this.closed=closed;}
    }

    static final class Result {
        final List<Notable> points = new ArrayList<>();
        final List<EdgeChain> edges = new ArrayList<>();
    }

    private static final class Triangle {
        final Geometry3D.Vec3 a,b,c,center,normal;
        final double area,plane;
        FaceGroup group;
        Triangle(Geometry3D.Vec3 a,Geometry3D.Vec3 b,Geometry3D.Vec3 c){
            this.a=a;this.b=b;this.c=c;
            center=new Geometry3D.Vec3((a.x+b.x+c.x)/3f,(a.y+b.y+c.y)/3f,(a.z+b.z+c.z)/3f);
            Geometry3D.Vec3 cross=b.sub(a).cross(c.sub(a));
            double len=cross.length();
            area=len*.5;
            normal=len<1e-9?new Geometry3D.Vec3(0,0,1):cross.mul((float)(1.0/len));
            plane=normal.dot(center);
        }
    }

    private static final class FaceGroup {
        Geometry3D.Vec3 normal;
        double plane,area,sx,sy,sz;
        final List<Triangle> triangles=new ArrayList<>();
        FaceGroup(Triangle t){normal=t.normal;plane=t.plane;add(t);}
        void add(Triangle t){
            triangles.add(t);t.group=this;
            double w=Math.max(t.area,1e-10);area+=w;
            sx+=t.center.x*w;sy+=t.center.y*w;sz+=t.center.z*w;
        }
        Geometry3D.Vec3 center(){double d=Math.max(area,1e-10);return new Geometry3D.Vec3((float)(sx/d),(float)(sy/d),(float)(sz/d));}
    }

    private static final class MeshEdge {
        final Geometry3D.Vec3 a,b;
        final String ka,kb;
        Geometry3D.Vec3 n1,n2;
        int count=1;
        MeshEdge(Geometry3D.Vec3 a,Geometry3D.Vec3 b,Geometry3D.Vec3 n){
            this.a=a;this.b=b;ka=key(a);kb=key(b);n1=n;
        }
        void addNormal(Geometry3D.Vec3 n){if(n2==null)n2=n;count++;}
        boolean logical(){return count==1||n2==null||Math.abs(n1.dot(n2))<0.985f;}
    }

    private OcctSnapTopology(){}

    static Result analyze(long handle){
        Result out=new Result();
        if(handle==0L)return out;
        double[] xyz=NativeBRepKernel.occtTriangulate(handle,0.16);
        if(xyz==null||xyz.length<9)return out;

        List<Triangle> triangles=new ArrayList<>();
        for(int i=0;i+8<xyz.length;i+=9){
            Geometry3D.Vec3 a=v(xyz,i),b=v(xyz,i+3),c=v(xyz,i+6);
            Triangle t=new Triangle(a,b,c);
            if(t.area>1e-8)triangles.add(t);
        }
        if(triangles.isEmpty())return out;

        List<FaceGroup> faces=faceGroups(triangles);
        for(FaceGroup face:faces){
            addNotable(out.points,FACE_CENTER,face.center(),0.05f);
            for(Geometry3D.Vec3 h:holeCenters(face))addNotable(out.points,HOLE_CENTER,h,0.05f);
        }

        List<MeshEdge> logical=logicalEdges(triangles);
        List<EdgeChain> chains=chains(logical);
        out.edges.addAll(chains);

        Map<String,Integer> degree=new HashMap<>();
        Map<String,Geometry3D.Vec3> positions=new HashMap<>();
        for(MeshEdge e:logical){
            degree.put(e.ka,degree.getOrDefault(e.ka,0)+1);
            degree.put(e.kb,degree.getOrDefault(e.kb,0)+1);
            positions.put(e.ka,e.a);positions.put(e.kb,e.b);
        }
        // A degree-2 node is normally a tessellation point along a curve and is
        // intentionally not shown as a fake B-Rep vertex.
        for(Map.Entry<String,Integer> e:degree.entrySet()){
            if(e.getValue()!=2)addNotable(out.points,VERTEX,positions.get(e.getKey()),0.04f);
        }
        for(EdgeChain chain:chains){
            if(!chain.closed && chain.points.size()>=2){
                Geometry3D.Vec3 mid=halfLengthPoint(chain.points);
                if(mid!=null)addNotable(out.points,EDGE_MIDPOINT,mid,0.04f);
            }
        }
        return out;
    }

    private static List<FaceGroup> faceGroups(List<Triangle> ts){
        List<FaceGroup> groups=new ArrayList<>();
        for(Triangle t:ts){
            FaceGroup hit=null;
            for(FaceGroup g:groups){
                if(g.normal.dot(t.normal)>0.99935f && Math.abs(g.plane-t.plane)<0.14){hit=g;break;}
            }
            if(hit==null){hit=new FaceGroup(t);groups.add(hit);}else hit.add(t);
        }
        return groups;
    }

    /** Finds inner planar boundary loops; their perimeter centroid is a robust hole center for circular holes. */
    private static List<Geometry3D.Vec3> holeCenters(FaceGroup face){
        List<Geometry3D.Vec3> out=new ArrayList<>();
        if(face==null||face.triangles.size()<2)return out;
        Map<String,LoopEdge> map=new HashMap<>();
        for(Triangle t:face.triangles){
            addLoopEdge(map,t.a,t.b);addLoopEdge(map,t.b,t.c);addLoopEdge(map,t.c,t.a);
        }
        List<LoopEdge> boundary=new ArrayList<>();
        for(LoopEdge e:map.values())if(e.count==1)boundary.add(e);
        if(boundary.size()<3)return out;
        List<List<Geometry3D.Vec3>> loops=boundaryLoops(boundary);
        if(loops.size()<2)return out;
        int outer=-1;double outerPer=-1;
        for(int i=0;i<loops.size();i++){
            double p=perimeter(loops.get(i),true);if(p>outerPer){outerPer=p;outer=i;}
        }
        for(int i=0;i<loops.size();i++){
            if(i==outer||loops.get(i).size()<3)continue;
            out.add(perimeterCentroid(loops.get(i)));
        }
        return out;
    }

    private static final class LoopEdge {
        final Geometry3D.Vec3 a,b;final String ka,kb;int count=1;
        LoopEdge(Geometry3D.Vec3 a,Geometry3D.Vec3 b){this.a=a;this.b=b;ka=key(a);kb=key(b);}
    }
    private static void addLoopEdge(Map<String,LoopEdge> map,Geometry3D.Vec3 a,Geometry3D.Vec3 b){
        String ka=key(a),kb=key(b),k=ka.compareTo(kb)<=0?ka+"|"+kb:kb+"|"+ka;
        LoopEdge e=map.get(k);if(e==null)map.put(k,new LoopEdge(a,b));else e.count++;
    }

    private static List<List<Geometry3D.Vec3>> boundaryLoops(List<LoopEdge> edges){
        List<List<Geometry3D.Vec3>> out=new ArrayList<>();
        Map<String,List<Integer>> at=new HashMap<>();
        for(int i=0;i<edges.size();i++){
            LoopEdge e=edges.get(i);at.computeIfAbsent(e.ka,k->new ArrayList<>()).add(i);at.computeIfAbsent(e.kb,k->new ArrayList<>()).add(i);
        }
        boolean[] used=new boolean[edges.size()];
        for(int seed=0;seed<edges.size();seed++){
            if(used[seed])continue;
            LoopEdge first=edges.get(seed);List<Geometry3D.Vec3> loop=new ArrayList<>();
            used[seed]=true;loop.add(first.a);loop.add(first.b);String current=first.kb,start=first.ka;
            int guard=edges.size()+2;
            while(!current.equals(start)&&guard-->0){
                int next=-1;for(int idx:at.getOrDefault(current,new ArrayList<>()))if(!used[idx]){next=idx;break;}
                if(next<0)break;used[next]=true;LoopEdge e=edges.get(next);
                if(e.ka.equals(current)){loop.add(e.b);current=e.kb;}else{loop.add(e.a);current=e.ka;}
            }
            if(current.equals(start)&&loop.size()>=4)out.add(loop);
        }
        return out;
    }

    private static List<MeshEdge> logicalEdges(List<Triangle> ts){
        Map<String,MeshEdge> map=new HashMap<>();
        for(Triangle t:ts){addMeshEdge(map,t.a,t.b,t.normal);addMeshEdge(map,t.b,t.c,t.normal);addMeshEdge(map,t.c,t.a,t.normal);}
        List<MeshEdge> out=new ArrayList<>();for(MeshEdge e:map.values())if(e.logical()&&dist(e.a,e.b)>1e-5)out.add(e);return out;
    }
    private static void addMeshEdge(Map<String,MeshEdge> map,Geometry3D.Vec3 a,Geometry3D.Vec3 b,Geometry3D.Vec3 n){
        String ka=key(a),kb=key(b),k=ka.compareTo(kb)<=0?ka+"|"+kb:kb+"|"+ka;
        MeshEdge e=map.get(k);if(e==null)map.put(k,new MeshEdge(a,b,n));else e.addNormal(n);
    }

    private static List<EdgeChain> chains(List<MeshEdge> edges){
        List<EdgeChain> out=new ArrayList<>();if(edges.isEmpty())return out;
        Map<String,List<Integer>> at=new HashMap<>();Map<String,Integer> degree=new HashMap<>();
        for(int i=0;i<edges.size();i++){
            MeshEdge e=edges.get(i);at.computeIfAbsent(e.ka,k->new ArrayList<>()).add(i);at.computeIfAbsent(e.kb,k->new ArrayList<>()).add(i);
            degree.put(e.ka,degree.getOrDefault(e.ka,0)+1);degree.put(e.kb,degree.getOrDefault(e.kb,0)+1);
        }
        boolean[] used=new boolean[edges.size()];
        for(int i=0;i<edges.size();i++){
            if(used[i])continue;MeshEdge e=edges.get(i);
            String start=degree.getOrDefault(e.ka,0)!=2?e.ka:(degree.getOrDefault(e.kb,0)!=2?e.kb:null);
            if(start==null)continue;
            out.add(walkChain(i,start,edges,at,degree,used));
        }
        for(int i=0;i<edges.size();i++)if(!used[i])out.add(walkCycle(i,edges,at,used));
        return out;
    }

    private static EdgeChain walkChain(int seed,String start,List<MeshEdge> edges,Map<String,List<Integer>> at,Map<String,Integer> degree,boolean[] used){
        List<Geometry3D.Vec3> pts=new ArrayList<>();String current=start;int edgeIndex=seed;int guard=edges.size()+2;
        while(edgeIndex>=0&&!used[edgeIndex]&&guard-->0){
            MeshEdge e=edges.get(edgeIndex);used[edgeIndex]=true;
            boolean forward=e.ka.equals(current);Geometry3D.Vec3 a=forward?e.a:e.b,b=forward?e.b:e.a;String next=forward?e.kb:e.ka;
            if(pts.isEmpty())pts.add(a);pts.add(b);current=next;
            if(degree.getOrDefault(current,0)!=2)break;
            edgeIndex=-1;for(int idx:at.getOrDefault(current,new ArrayList<>()))if(!used[idx]){edgeIndex=idx;break;}
        }
        return new EdgeChain(pts,false);
    }

    private static EdgeChain walkCycle(int seed,List<MeshEdge> edges,Map<String,List<Integer>> at,boolean[] used){
        List<Geometry3D.Vec3> pts=new ArrayList<>();MeshEdge first=edges.get(seed);String start=first.ka,current=start;int edgeIndex=seed;int guard=edges.size()+2;
        while(edgeIndex>=0&&!used[edgeIndex]&&guard-->0){
            MeshEdge e=edges.get(edgeIndex);used[edgeIndex]=true;boolean forward=e.ka.equals(current);Geometry3D.Vec3 a=forward?e.a:e.b,b=forward?e.b:e.a;String next=forward?e.kb:e.ka;
            if(pts.isEmpty())pts.add(a);pts.add(b);current=next;if(current.equals(start))break;
            edgeIndex=-1;for(int idx:at.getOrDefault(current,new ArrayList<>()))if(!used[idx]){edgeIndex=idx;break;}
        }
        return new EdgeChain(pts,current.equals(start));
    }

    private static Geometry3D.Vec3 halfLengthPoint(List<Geometry3D.Vec3> p){
        if(p.size()<2)return null;double total=perimeter(p,false),target=total*.5,run=0;
        for(int i=1;i<p.size();i++){
            Geometry3D.Vec3 a=p.get(i-1),b=p.get(i);double d=dist(a,b);
            if(run+d>=target){float t=d<1e-9?0f:(float)((target-run)/d);return a.add(b.sub(a).mul(t));}run+=d;
        }
        return p.get(p.size()-1);
    }

    private static double perimeter(List<Geometry3D.Vec3> p,boolean closed){double s=0;for(int i=1;i<p.size();i++)s+=dist(p.get(i-1),p.get(i));if(closed&&p.size()>2)s+=dist(p.get(p.size()-1),p.get(0));return s;}
    private static Geometry3D.Vec3 perimeterCentroid(List<Geometry3D.Vec3> p){
        double sx=0,sy=0,sz=0,w=0;for(int i=1;i<p.size();i++){Geometry3D.Vec3 a=p.get(i-1),b=p.get(i);double d=dist(a,b);sx+=(a.x+b.x)*.5*d;sy+=(a.y+b.y)*.5*d;sz+=(a.z+b.z)*.5*d;w+=d;}
        if(w<1e-9)return p.get(0);return new Geometry3D.Vec3((float)(sx/w),(float)(sy/w),(float)(sz/w));
    }

    private static void addNotable(List<Notable> out,int kind,Geometry3D.Vec3 p,float tol){if(p==null)return;for(Notable n:out)if(n.kind==kind&&dist(n.p,p)<=tol)return;out.add(new Notable(kind,p));}
    private static Geometry3D.Vec3 v(double[] a,int i){return new Geometry3D.Vec3((float)a[i],(float)a[i+1],(float)a[i+2]);}
    private static double dist(Geometry3D.Vec3 a,Geometry3D.Vec3 b){double x=a.x-b.x,y=a.y-b.y,z=a.z-b.z;return Math.sqrt(x*x+y*y+z*z);}
    private static String key(Geometry3D.Vec3 p){return q(p.x)+","+q(p.y)+","+q(p.z);}
    private static long q(double v){return Math.round(v*40.0);}// 0.025 mm buckets
}

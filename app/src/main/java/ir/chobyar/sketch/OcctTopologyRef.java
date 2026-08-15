package ir.chobyar.sketch;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Stable logical reference to an OCCT Face/Edge used by parametric direct edits.
 *
 * OCCT topology indices are intentionally not stored because Boolean/form edits can
 * renumber sub-shapes. Instead a reference stores a geometric signature and a
 * normalized position inside the body bounds. During History replay the current
 * OCCT triangulation is inspected and the closest logical topology is rematched.
 * This is a deterministic topological-naming bridge for the Android prototype;
 * the ID itself never changes during the feature lifetime.
 */
final class OcctTopologyRef {
    static final int EDGE=1;
    static final int FACE=2;

    static final class Ref {
        final int kind;
        final String id;
        final double measure;
        final Geometry3D.Vec3 anchor;
        final Geometry3D.Vec3 vector;
        final double nx,ny,nz;

        Ref(int kind,String id,double measure,Geometry3D.Vec3 anchor,Geometry3D.Vec3 vector,
            double nx,double ny,double nz){
            this.kind=kind;
            this.id=id;
            this.measure=measure;
            this.anchor=anchor;
            this.vector=vector;
            this.nx=nx;this.ny=ny;this.nz=nz;
        }

        String shortLabel(){return id+(kind==FACE?" • Face":" • Edge");}
    }

    static final class Resolution {
        final Geometry3D.Vec3 anchor;
        final double score;
        Resolution(Geometry3D.Vec3 anchor,double score){this.anchor=anchor;this.score=score;}
        boolean confident(){return score<95.0;}
    }

    private static final class Bounds {
        double minX=Double.POSITIVE_INFINITY,minY=Double.POSITIVE_INFINITY,minZ=Double.POSITIVE_INFINITY;
        double maxX=Double.NEGATIVE_INFINITY,maxY=Double.NEGATIVE_INFINITY,maxZ=Double.NEGATIVE_INFINITY;
        void add(Geometry3D.Vec3 p){
            minX=Math.min(minX,p.x);minY=Math.min(minY,p.y);minZ=Math.min(minZ,p.z);
            maxX=Math.max(maxX,p.x);maxY=Math.max(maxY,p.y);maxZ=Math.max(maxZ,p.z);
        }
        boolean valid(){return minX<=maxX&&minY<=maxY&&minZ<=maxZ;}
        double diag(){double x=maxX-minX,y=maxY-minY,z=maxZ-minZ;return Math.max(1e-6,Math.sqrt(x*x+y*y+z*z));}
        double fx(double x){return fraction(x,minX,maxX);}
        double fy(double y){return fraction(y,minY,maxY);}
        double fz(double z){return fraction(z,minZ,maxZ);}
        Geometry3D.Vec3 fromFraction(double x,double y,double z){
            return new Geometry3D.Vec3((float)lerp(minX,maxX,x),(float)lerp(minY,maxY,y),(float)lerp(minZ,maxZ,z));
        }
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
            area=len*0.5;
            normal=len<1e-9?new Geometry3D.Vec3(0,0,1):cross.mul((float)(1.0/len));
            plane=normal.dot(center);
        }
    }

    private static final class FaceGroup {
        Geometry3D.Vec3 normal;
        double plane;
        double area;
        double sx,sy,sz;
        final List<Triangle> triangles=new ArrayList<>();
        FaceGroup(Triangle t){normal=t.normal;plane=t.plane;add(t);}
        void add(Triangle t){
            triangles.add(t);t.group=this;
            double w=Math.max(t.area,1e-9);area+=w;sx+=t.center.x*w;sy+=t.center.y*w;sz+=t.center.z*w;
        }
        Geometry3D.Vec3 center(){double d=Math.max(area,1e-9);return new Geometry3D.Vec3((float)(sx/d),(float)(sy/d),(float)(sz/d));}
    }

    private static final class EdgeCandidate {
        final Geometry3D.Vec3 a,b,mid,dir;
        final double length;
        Geometry3D.Vec3 n1,n2;
        int count;
        EdgeCandidate(Geometry3D.Vec3 a,Geometry3D.Vec3 b,Geometry3D.Vec3 normal){
            this.a=a;this.b=b;
            mid=a.add(b).mul(0.5f);
            Geometry3D.Vec3 d=b.sub(a);length=d.length();dir=length<1e-9?new Geometry3D.Vec3(1,0,0):d.mul((float)(1.0/length));
            n1=normal;count=1;
        }
        void addNormal(Geometry3D.Vec3 n){if(count==1)n2=n;count++;}
        boolean logicalBoundary(){return count==1||n2==null||Math.abs(n1.dot(n2))<0.98f;}
    }

    private static final class Mesh {
        final List<Triangle> triangles=new ArrayList<>();
        final Bounds bounds=new Bounds();
    }

    private OcctTopologyRef(){}

    static Ref captureFace(long handle,Geometry3D.Vec3 touchAnchor,String stableId){
        Mesh mesh=mesh(handle);if(mesh==null||mesh.triangles.isEmpty()||touchAnchor==null)return null;
        List<FaceGroup> groups=faceGroups(mesh);
        Triangle picked=null;double best=Double.POSITIVE_INFINITY;
        for(Triangle t:mesh.triangles){double d=dist2(t.center,touchAnchor);if(d<best){best=d;picked=t;}}
        if(picked==null||picked.group==null)return null;
        FaceGroup g=picked.group;Geometry3D.Vec3 c=g.center();
        return new Ref(FACE,stableId,g.area,c,g.normal,
                mesh.bounds.fx(c.x),mesh.bounds.fy(c.y),mesh.bounds.fz(c.z));
    }

    static Ref captureEdge(long handle,Geometry3D.Vec3 touchAnchor,
                           Geometry3D.Vec3 selectedA,Geometry3D.Vec3 selectedB,String stableId){
        Mesh mesh=mesh(handle);if(mesh==null||touchAnchor==null)return null;
        List<EdgeCandidate> edges=edgeCandidates(mesh);if(edges.isEmpty())return null;
        Geometry3D.Vec3 selectedDir=null;
        if(selectedA!=null&&selectedB!=null){Geometry3D.Vec3 d=selectedB.sub(selectedA);if(d.length()>1e-8f)selectedDir=d.normalized();}
        EdgeCandidate best=null;double score=Double.POSITIVE_INFINITY;
        double diag=mesh.bounds.diag();
        for(EdgeCandidate e:edges){
            double s=Math.sqrt(dist2(e.mid,touchAnchor))/diag*100.0;
            if(selectedDir!=null)s+=(1.0-Math.abs(selectedDir.dot(e.dir)))*18.0;
            if(s<score){score=s;best=e;}
        }
        if(best==null)return null;
        Geometry3D.Vec3 c=best.mid;
        return new Ref(EDGE,stableId,best.length,c,best.dir,
                mesh.bounds.fx(c.x),mesh.bounds.fy(c.y),mesh.bounds.fz(c.z));
    }

    static Resolution resolve(long handle,Ref ref){
        if(ref==null)return null;Mesh mesh=mesh(handle);if(mesh==null)return null;
        Geometry3D.Vec3 expected=mesh.bounds.fromFraction(ref.nx,ref.ny,ref.nz);
        double diag=mesh.bounds.diag();
        if(ref.kind==FACE){
            FaceGroup best=null;double score=Double.POSITIVE_INFINITY;
            for(FaceGroup g:faceGroups(mesh)){
                Geometry3D.Vec3 c=g.center();
                double pos=Math.sqrt(dist2(c,expected))/diag*65.0;
                double measure=relative(ref.measure,g.area)*22.0;
                double orient=ref.vector==null?0.0:(1.0-Math.abs(ref.vector.dot(g.normal)))*25.0;
                double s=pos+measure+orient;
                if(s<score){score=s;best=g;}
            }
            return best==null?null:new Resolution(best.center(),score);
        }
        EdgeCandidate best=null;double score=Double.POSITIVE_INFINITY;
        for(EdgeCandidate e:edgeCandidates(mesh)){
            double pos=Math.sqrt(dist2(e.mid,expected))/diag*70.0;
            double measure=relative(ref.measure,e.length)*14.0;
            double orient=ref.vector==null?0.0:(1.0-Math.abs(ref.vector.dot(e.dir)))*16.0;
            double s=pos+measure+orient;
            if(s<score){score=s;best=e;}
        }
        return best==null?null:new Resolution(best.mid,score);
    }

    static String debug(Ref ref){
        if(ref==null)return"بدون Topology ID";
        return ref.shortLabel()+" • "+String.format(Locale.US,"%.2f",ref.measure)+(ref.kind==FACE?" mm²":" mm");
    }

    private static Mesh mesh(long handle){
        double[] xyz=NativeBRepKernel.occtTriangulate(handle,0.24);if(xyz==null||xyz.length<9)return null;
        Mesh m=new Mesh();
        for(int i=0;i+8<xyz.length;i+=9){
            Geometry3D.Vec3 a=v(xyz,i),b=v(xyz,i+3),c=v(xyz,i+6);
            m.bounds.add(a);m.bounds.add(b);m.bounds.add(c);
            Triangle t=new Triangle(a,b,c);if(t.area>1e-8)m.triangles.add(t);
        }
        return m.bounds.valid()?m:null;
    }

    private static Geometry3D.Vec3 v(double[] a,int i){return new Geometry3D.Vec3((float)a[i],(float)a[i+1],(float)a[i+2]);}

    private static List<FaceGroup> faceGroups(Mesh mesh){
        List<FaceGroup> out=new ArrayList<>();
        for(Triangle t:mesh.triangles){
            FaceGroup found=null;
            for(FaceGroup g:out){
                if(g.normal.dot(t.normal)>0.9992f&&Math.abs(g.plane-t.plane)<0.18){found=g;break;}
            }
            if(found==null){found=new FaceGroup(t);out.add(found);}else found.add(t);
        }
        return out;
    }

    private static List<EdgeCandidate> edgeCandidates(Mesh mesh){
        Map<String,EdgeCandidate> map=new HashMap<>();
        for(Triangle t:mesh.triangles){addEdge(map,t.a,t.b,t.normal);addEdge(map,t.b,t.c,t.normal);addEdge(map,t.c,t.a,t.normal);}
        List<EdgeCandidate> out=new ArrayList<>();
        for(EdgeCandidate e:map.values())if(e.length>1e-5&&e.logicalBoundary())out.add(e);
        return out;
    }

    private static void addEdge(Map<String,EdgeCandidate> map,Geometry3D.Vec3 a,Geometry3D.Vec3 b,Geometry3D.Vec3 n){
        String ka=pointKey(a),kb=pointKey(b);String key=ka.compareTo(kb)<=0?ka+"|"+kb:kb+"|"+ka;
        EdgeCandidate e=map.get(key);if(e==null)map.put(key,new EdgeCandidate(a,b,n));else e.addNormal(n);
    }

    private static String pointKey(Geometry3D.Vec3 p){return q(p.x)+","+q(p.y)+","+q(p.z);}
    private static long q(double v){return Math.round(v*50.0);}// 0.02 mm buckets
    private static double relative(double a,double b){return Math.abs(a-b)/Math.max(1e-6,Math.max(Math.abs(a),Math.abs(b)));}
    private static double dist2(Geometry3D.Vec3 a,Geometry3D.Vec3 b){double x=a.x-b.x,y=a.y-b.y,z=a.z-b.z;return x*x+y*y+z*z;}
    private static double fraction(double v,double min,double max){double d=max-min;return Math.abs(d)<1e-9?0.5:(v-min)/d;}
    private static double lerp(double a,double b,double t){return a+(b-a)*Math.max(0.0,Math.min(1.0,t));}
}
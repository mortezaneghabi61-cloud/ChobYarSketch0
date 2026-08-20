package ir.chobyar.sketch;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Stable logical reference to an OCCT Face/Edge used by parametric direct edits.
 *
 * Exact analytic edge and supported face descriptors are the primary source for
 * topology identity. Display triangulation is retained only as a controlled
 * fallback on ABIs/surface types where the exact OCCT descriptor path is not
 * available. References store geometric signatures plus normalized positions so
 * History rebuilds can rematch logical topology after OCCT renumbers sub-shapes.
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
        final int signatureKind;
        final double secondaryMeasure;

        Ref(int kind,String id,double measure,Geometry3D.Vec3 anchor,Geometry3D.Vec3 vector,
            double nx,double ny,double nz){
            this(kind,id,measure,anchor,vector,nx,ny,nz,0,0.0);
        }

        Ref(int kind,String id,double measure,Geometry3D.Vec3 anchor,Geometry3D.Vec3 vector,
            double nx,double ny,double nz,int signatureKind,double secondaryMeasure){
            this.kind=kind;
            this.id=id;
            this.measure=measure;
            this.anchor=anchor;
            this.vector=vector;
            this.nx=nx;this.ny=ny;this.nz=nz;
            this.signatureKind=signatureKind;
            this.secondaryMeasure=secondaryMeasure;
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
        void add(double x,double y,double z){add(new Geometry3D.Vec3((float)x,(float)y,(float)z));}
        boolean valid(){return minX<=maxX&&minY<=maxY&&minZ<=maxZ;}
        double diag(){double x=maxX-minX,y=maxY-minY,z=maxZ-minZ;return Math.max(1e-6,Math.sqrt(x*x+y*y+z*z));}
        double fx(double x){return fraction(x,minX,maxX);}
        double fy(double y){return fraction(y,minY,maxY);}
        double fz(double z){return fraction(z,minZ,maxZ);}
        Geometry3D.Vec3 fromFraction(double x,double y,double z){
            return new Geometry3D.Vec3((float)lerp(minX,maxX,x),(float)lerp(minY,maxY,y),(float)lerp(minZ,maxZ,z));
        }
    }

    private static final class ExactEdgeCandidate {
        final int type;
        final Geometry3D.Vec3 p1,p2,center,anchor,vector;
        final double radius,span,measure;
        ExactEdgeCandidate(int type,Geometry3D.Vec3 p1,Geometry3D.Vec3 p2,
                           Geometry3D.Vec3 center,Geometry3D.Vec3 anchor,
                           Geometry3D.Vec3 vector,double radius,double span,double measure){
            this.type=type;this.p1=p1;this.p2=p2;this.center=center;this.anchor=anchor;
            this.vector=vector;this.radius=radius;this.span=span;this.measure=measure;
        }
    }

    private static final class ExactFaceCandidate {
        final int type,index;
        final Geometry3D.Vec3 center,origin,axis;
        final double area,radius;
        ExactFaceCandidate(int type,int index,Geometry3D.Vec3 center,Geometry3D.Vec3 origin,
                           Geometry3D.Vec3 axis,double area,double radius){
            this.type=type;this.index=index;this.center=center;this.origin=origin;
            this.axis=axis;this.area=area;this.radius=radius;
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
        if(touchAnchor==null)return null;
        List<ExactFaceCandidate> exact=exactFaceCandidates(NativeBRepKernel.occtFaceDescriptors(handle));
        if(!exact.isEmpty()){
            Ref ref=captureExactFace(exact,touchAnchor,stableId);
            if(ref!=null)return ref;
        }
        return captureMeshFace(handle,touchAnchor,stableId);
    }

    static Ref captureEdge(long handle,Geometry3D.Vec3 touchAnchor,
                           Geometry3D.Vec3 selectedA,Geometry3D.Vec3 selectedB,String stableId){
        if(touchAnchor==null)return null;
        List<ExactEdgeCandidate> exact=exactEdgeCandidates(NativeBRepKernel.occtEdgeDescriptors(handle));
        if(!exact.isEmpty()){
            Ref ref=captureExactEdge(exact,touchAnchor,selectedA,selectedB,stableId);
            if(ref!=null)return ref;
        }
        return captureMeshEdge(handle,touchAnchor,selectedA,selectedB,stableId);
    }

    static Resolution resolve(long handle,Ref ref){
        if(ref==null)return null;
        if(ref.kind==EDGE){
            List<ExactEdgeCandidate> exact=exactEdgeCandidates(NativeBRepKernel.occtEdgeDescriptors(handle));
            if(!exact.isEmpty()){
                Resolution resolution=resolveExactEdge(exact,ref);
                if(resolution!=null)return resolution;
            }
        }else if(ref.kind==FACE){
            List<ExactFaceCandidate> exact=exactFaceCandidates(NativeBRepKernel.occtFaceDescriptors(handle));
            if(!exact.isEmpty()){
                Resolution resolution=resolveExactFace(exact,ref);
                if(resolution!=null)return resolution;
            }
        }
        Mesh mesh=mesh(handle);if(mesh==null)return null;
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

    /** Deterministic exact-face seam for x86 emulator regression tests. */
    static Ref captureFaceDescriptorsForTest(double[] descriptors,Geometry3D.Vec3 touchAnchor,String stableId){
        return captureExactFace(exactFaceCandidates(descriptors),touchAnchor,stableId);
    }

    static Resolution resolveFaceDescriptorsForTest(double[] descriptors,Ref ref){
        return resolveExactFace(exactFaceCandidates(descriptors),ref);
    }

    /** Deterministic exact-descriptor seam for x86 emulator regression tests. */
    static Ref captureEdgeDescriptorsForTest(double[] descriptors,Geometry3D.Vec3 touchAnchor,
                                             Geometry3D.Vec3 selectedA,Geometry3D.Vec3 selectedB,
                                             String stableId){
        return captureExactEdge(exactEdgeCandidates(descriptors),touchAnchor,selectedA,selectedB,stableId);
    }

    /** Deterministic History-rematch seam that never needs a native library. */
    static Resolution resolveEdgeDescriptorsForTest(double[] descriptors,Ref ref){
        return resolveExactEdge(exactEdgeCandidates(descriptors),ref);
    }

    static String debug(Ref ref){
        if(ref==null)return"بدون Topology ID";
        return ref.shortLabel()+" • "+String.format(Locale.US,"%.2f",ref.measure)+(ref.kind==FACE?" mm²":" mm");
    }

    private static Ref captureExactFace(List<ExactFaceCandidate> faces,Geometry3D.Vec3 touchAnchor,String stableId){
        if(faces==null||faces.isEmpty()||touchAnchor==null)return null;
        Bounds bounds=exactFaceBounds(faces);if(!bounds.valid())return null;
        ExactFaceCandidate best=null;double score=Double.POSITIVE_INFINITY;double diag=bounds.diag();
        for(ExactFaceCandidate f:faces){
            double s=distanceToExactFace(f,touchAnchor)/diag*90.0+
                    Math.sqrt(dist2(f.center,touchAnchor))/diag*10.0;
            if(s<score){score=s;best=f;}
        }
        if(best==null)return null;Geometry3D.Vec3 c=best.center;
        return new Ref(FACE,stableId,best.area,c,best.axis,
                bounds.fx(c.x),bounds.fy(c.y),bounds.fz(c.z),best.type,best.radius);
    }

    private static Resolution resolveExactFace(List<ExactFaceCandidate> faces,Ref ref){
        if(faces==null||faces.isEmpty()||ref==null||ref.kind!=FACE)return null;
        Bounds bounds=exactFaceBounds(faces);if(!bounds.valid())return null;
        Geometry3D.Vec3 expected=bounds.fromFraction(ref.nx,ref.ny,ref.nz);double diag=bounds.diag();
        ExactFaceCandidate best=null;double score=Double.POSITIVE_INFINITY;
        for(ExactFaceCandidate f:faces){
            if(ref.signatureKind!=0&&f.type!=ref.signatureKind)continue;
            double pos=Math.sqrt(dist2(f.center,expected))/diag*55.0;
            double area=relative(ref.measure,f.area)*22.0;
            double axis=ref.vector==null?0.0:(1.0-Math.abs(ref.vector.dot(f.axis)))*18.0;
            double radius=(ref.signatureKind==NativeBRepKernel.OCCT_FACE_CYLINDER||f.type==NativeBRepKernel.OCCT_FACE_CYLINDER)
                    ?relative(ref.secondaryMeasure,f.radius)*20.0:0.0;
            double s=pos+area+axis+radius;
            if(s<score){score=s;best=f;}
        }
        return best==null?null:new Resolution(best.center,score);
    }

    private static List<ExactFaceCandidate> exactFaceCandidates(double[] d){
        List<ExactFaceCandidate> out=new ArrayList<>();
        if(d==null)return out;final int n=NativeBRepKernel.OCCT_FACE_RECORD_SIZE;
        for(int i=0;i+n-1<d.length;i+=n){
            int kind=(int)Math.round(d[i]),index=(int)Math.round(d[i+1]);
            if(kind!=NativeBRepKernel.OCCT_FACE_PLANE&&kind!=NativeBRepKernel.OCCT_FACE_CYLINDER)continue;
            Geometry3D.Vec3 center=v(d,i+2),origin=v(d,i+5),axis=v(d,i+8);double len=axis.length();
            double area=Math.abs(d[i+11]),radius=Math.abs(d[i+12]);
            if(len<1e-7||area<1e-9)continue;axis=axis.mul((float)(1.0/len));
            if(kind==NativeBRepKernel.OCCT_FACE_CYLINDER&&radius<1e-7)continue;
            out.add(new ExactFaceCandidate(kind,index,center,origin,axis,area,radius));
        }
        return out;
    }

    private static Bounds exactFaceBounds(List<ExactFaceCandidate> faces){
        Bounds b=new Bounds();
        for(ExactFaceCandidate f:faces){b.add(f.center);b.add(f.origin);}
        return b;
    }

    private static double distanceToExactFace(ExactFaceCandidate f,Geometry3D.Vec3 p){
        Geometry3D.Vec3 q=p.sub(f.origin);
        if(f.type==NativeBRepKernel.OCCT_FACE_PLANE)return Math.abs(q.dot(f.axis));
        double axial=q.dot(f.axis);Geometry3D.Vec3 radial=q.sub(f.axis.mul((float)axial));
        return Math.abs(radial.length()-f.radius);
    }

    private static Ref captureExactEdge(List<ExactEdgeCandidate> edges,Geometry3D.Vec3 touchAnchor,
                                        Geometry3D.Vec3 selectedA,Geometry3D.Vec3 selectedB,String stableId){
        if(edges==null||edges.isEmpty()||touchAnchor==null)return null;
        Bounds bounds=exactBounds(edges);if(!bounds.valid())return null;
        Geometry3D.Vec3 selectedDir=null;
        if(selectedA!=null&&selectedB!=null){Geometry3D.Vec3 d=selectedB.sub(selectedA);if(d.length()>1e-8f)selectedDir=d.normalized();}
        ExactEdgeCandidate best=null;double score=Double.POSITIVE_INFINITY;double diag=bounds.diag();
        for(ExactEdgeCandidate e:edges){
            double s=distanceToExactEdge(e,touchAnchor)/diag*100.0;
            if(selectedDir!=null&&e.type==NativeBRepKernel.OCCT_EDGE_LINE)
                s+=(1.0-Math.abs(selectedDir.dot(e.vector)))*18.0;
            if(s<score){score=s;best=e;}
        }
        if(best==null)return null;
        Geometry3D.Vec3 c=best.anchor;
        return new Ref(EDGE,stableId,best.measure,c,best.vector,
                bounds.fx(c.x),bounds.fy(c.y),bounds.fz(c.z));
    }

    private static Resolution resolveExactEdge(List<ExactEdgeCandidate> edges,Ref ref){
        if(edges==null||edges.isEmpty()||ref==null||ref.kind!=EDGE)return null;
        Bounds bounds=exactBounds(edges);if(!bounds.valid())return null;
        Geometry3D.Vec3 expected=bounds.fromFraction(ref.nx,ref.ny,ref.nz);double diag=bounds.diag();
        ExactEdgeCandidate best=null;double score=Double.POSITIVE_INFINITY;
        for(ExactEdgeCandidate e:edges){
            double pos=Math.sqrt(dist2(e.anchor,expected))/diag*70.0;
            double measure=relative(ref.measure,e.measure)*14.0;
            double orient=ref.vector==null?0.0:(1.0-Math.abs(ref.vector.dot(e.vector)))*16.0;
            double s=pos+measure+orient;
            if(s<score){score=s;best=e;}
        }
        return best==null?null:new Resolution(best.anchor,score);
    }

    private static List<ExactEdgeCandidate> exactEdgeCandidates(double[] d){
        List<ExactEdgeCandidate> out=new ArrayList<>();
        if(d==null)return out;final int n=NativeBRepKernel.OCCT_EDGE_RECORD_SIZE;
        for(int i=0;i+n-1<d.length;i+=n){
            int kind=(int)Math.round(d[i]);
            Geometry3D.Vec3 p1=v(d,i+2),p2=v(d,i+5);
            if(kind==NativeBRepKernel.OCCT_EDGE_LINE){
                Geometry3D.Vec3 delta=p2.sub(p1);double len=delta.length();if(len<1e-7)continue;
                Geometry3D.Vec3 anchor=p1.add(p2).mul(.5f),dir=delta.mul((float)(1.0/len));
                out.add(new ExactEdgeCandidate(kind,p1,p2,null,anchor,dir,0.0,0.0,len));continue;
            }
            if(kind!=NativeBRepKernel.OCCT_EDGE_CIRCLE&&kind!=NativeBRepKernel.OCCT_EDGE_ARC)continue;
            Geometry3D.Vec3 center=v(d,i+8),normal=v(d,i+11);double nl=normal.length();double radius=Math.abs(d[i+14]);
            if(nl<1e-7||radius<1e-7)continue;normal=normal.mul((float)(1.0/nl));
            double span=kind==NativeBRepKernel.OCCT_EDGE_CIRCLE?Math.PI*2.0:Math.abs(d[i+16]-d[i+15]);
            if(!(span>1e-8))continue;span=Math.min(Math.PI*2.0,span);
            Geometry3D.Vec3 anchor=kind==NativeBRepKernel.OCCT_EDGE_CIRCLE?center:arcMidpoint(center,p1,normal,(d[i+16]-d[i+15])*.5);
            out.add(new ExactEdgeCandidate(kind,p1,p2,center,anchor,normal,radius,span,radius*span));
        }
        return out;
    }

    private static Bounds exactBounds(List<ExactEdgeCandidate> edges){
        Bounds b=new Bounds();
        for(ExactEdgeCandidate e:edges){
            b.add(e.p1);b.add(e.p2);b.add(e.anchor);
            if(e.center!=null&&e.radius>0.0){
                Geometry3D.Vec3 n=e.vector;
                double ex=e.radius*Math.sqrt(Math.max(0.0,1.0-n.x*n.x));
                double ey=e.radius*Math.sqrt(Math.max(0.0,1.0-n.y*n.y));
                double ez=e.radius*Math.sqrt(Math.max(0.0,1.0-n.z*n.z));
                b.add(e.center.x-ex,e.center.y-ey,e.center.z-ez);
                b.add(e.center.x+ex,e.center.y+ey,e.center.z+ez);
            }
        }
        return b;
    }

    private static double distanceToExactEdge(ExactEdgeCandidate e,Geometry3D.Vec3 p){
        if(e.type==NativeBRepKernel.OCCT_EDGE_LINE)return pointSegmentDistance(p,e.p1,e.p2);
        Geometry3D.Vec3 radial=p.sub(e.center);double plane=radial.dot(e.vector);
        Geometry3D.Vec3 projected=radial.sub(e.vector.mul((float)plane));
        double radialError=Math.abs(projected.length()-e.radius);
        double circleDistance=Math.hypot(Math.abs(plane),radialError);
        if(e.type==NativeBRepKernel.OCCT_EDGE_CIRCLE)return circleDistance;
        if(arcContains(e,projected))return circleDistance;
        return Math.min(Math.sqrt(dist2(p,e.p1)),Math.sqrt(dist2(p,e.p2)));
    }

    private static boolean arcContains(ExactEdgeCandidate e,Geometry3D.Vec3 projected){
        double len=projected.length();if(len<1e-9)return false;
        Geometry3D.Vec3 u=e.p1.sub(e.center);double ul=u.length();if(ul<1e-9)return false;
        u=u.mul((float)(1.0/ul));Geometry3D.Vec3 r=projected.mul((float)(1.0/len));
        double a=Math.atan2(e.vector.dot(u.cross(r)),u.dot(r));if(a<0)a+=Math.PI*2.0;
        return a<=e.span+1e-4;
    }

    private static Geometry3D.Vec3 arcMidpoint(Geometry3D.Vec3 center,Geometry3D.Vec3 p1,
                                               Geometry3D.Vec3 normal,double halfParam){
        Geometry3D.Vec3 u=p1.sub(center);double c=Math.cos(halfParam),s=Math.sin(halfParam);
        Geometry3D.Vec3 term1=u.mul((float)c);
        Geometry3D.Vec3 term2=normal.cross(u).mul((float)s);
        Geometry3D.Vec3 term3=normal.mul((float)(normal.dot(u)*(1.0-c)));
        return center.add(term1.add(term2).add(term3));
    }

    private static double pointSegmentDistance(Geometry3D.Vec3 p,Geometry3D.Vec3 a,Geometry3D.Vec3 b){
        Geometry3D.Vec3 ab=b.sub(a);double den=ab.dot(ab);if(den<1e-12)return Math.sqrt(dist2(p,a));
        double t=p.sub(a).dot(ab)/den;t=Math.max(0.0,Math.min(1.0,t));
        Geometry3D.Vec3 q=a.add(ab.mul((float)t));return Math.sqrt(dist2(p,q));
    }

    private static Ref captureMeshFace(long handle,Geometry3D.Vec3 touchAnchor,String stableId){
        Mesh mesh=mesh(handle);if(mesh==null||mesh.triangles.isEmpty()||touchAnchor==null)return null;
        faceGroups(mesh);
        Triangle picked=null;double best=Double.POSITIVE_INFINITY;
        for(Triangle triangle:mesh.triangles){double d=dist2(triangle.center,touchAnchor);if(d<best){best=d;picked=triangle;}}
        if(picked==null||picked.group==null)return null;
        FaceGroup g=picked.group;Geometry3D.Vec3 c=g.center();
        return new Ref(FACE,stableId,g.area,c,g.normal,
                mesh.bounds.fx(c.x),mesh.bounds.fy(c.y),mesh.bounds.fz(c.z));
    }

    private static Ref captureMeshEdge(long handle,Geometry3D.Vec3 touchAnchor,
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

package ir.chobyar.sketch;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Analytic primitive layer used as the exact geometric master for common curved
 * solids while the renderer/Boolean backend is still polygonal SolidCSG.
 *
 * Cylinder, cone/frustum and sphere parameters are kept as mathematical values
 * (axis, center and radii). Tessellation is produced only when a display/CSG
 * representation is required. This is the bridge toward a native exact B-Rep
 * kernel without forcing the UI to change again later.
 */
final class AnalyticSolidKernel {

    enum Kind { CYLINDER, CONE, SPHERE }

    abstract static class Primitive {
        final Kind kind;
        Primitive(Kind kind){this.kind=kind;}
        abstract SolidCSG tessellate(int quality);
        abstract double volumeMm3();
        abstract double areaMm2();
        abstract String detail();
    }

    static final class Cylinder extends Primitive {
        final Geometry3D.Vec3 baseCenter;
        final Geometry3D.Vec3 axis;
        final float radiusMm;
        final float heightMm;
        Cylinder(Geometry3D.Vec3 baseCenter,Geometry3D.Vec3 axis,float radiusMm,float heightMm){
            super(Kind.CYLINDER);
            this.baseCenter=baseCenter;
            this.axis=axis.normalized();
            this.radiusMm=Math.abs(radiusMm);
            this.heightMm=Math.abs(heightMm);
        }
        @Override SolidCSG tessellate(int quality){return makeCylinder(baseCenter,axis,radiusMm,heightMm,quality);}
        @Override double volumeMm3(){return Math.PI*radiusMm*radiusMm*heightMm;}
        @Override double areaMm2(){return 2.0*Math.PI*radiusMm*(radiusMm+heightMm);}
        @Override String detail(){return "Cylinder • R="+n(radiusMm)+" mm • H="+n(heightMm)+" mm";}
    }

    static final class Cone extends Primitive {
        final Geometry3D.Vec3 baseCenter;
        final Geometry3D.Vec3 axis;
        final float baseRadiusMm;
        final float topRadiusMm;
        final float heightMm;
        Cone(Geometry3D.Vec3 baseCenter,Geometry3D.Vec3 axis,float baseRadiusMm,float topRadiusMm,float heightMm){
            super(Kind.CONE);
            this.baseCenter=baseCenter;
            this.axis=axis.normalized();
            this.baseRadiusMm=Math.abs(baseRadiusMm);
            this.topRadiusMm=Math.abs(topRadiusMm);
            this.heightMm=Math.abs(heightMm);
        }
        @Override SolidCSG tessellate(int quality){return makeCone(baseCenter,axis,baseRadiusMm,topRadiusMm,heightMm,quality);}
        @Override double volumeMm3(){
            double r=baseRadiusMm,R=topRadiusMm,h=heightMm;
            return Math.PI*h*(r*r+r*R+R*R)/3.0;
        }
        @Override double areaMm2(){
            double r=baseRadiusMm,R=topRadiusMm,h=heightMm;
            double s=Math.sqrt((r-R)*(r-R)+h*h);
            return Math.PI*(r*r+R*R+(r+R)*s);
        }
        @Override String detail(){return "Cone/Frustum • R1="+n(baseRadiusMm)+" mm • R2="+n(topRadiusMm)+" mm • H="+n(heightMm)+" mm";}
    }

    static final class Sphere extends Primitive {
        final Geometry3D.Vec3 center;
        final float radiusMm;
        Sphere(Geometry3D.Vec3 center,float radiusMm){
            super(Kind.SPHERE);this.center=center;this.radiusMm=Math.abs(radiusMm);
        }
        @Override SolidCSG tessellate(int quality){return makeSphere(center,radiusMm,quality);}
        @Override double volumeMm3(){return 4.0*Math.PI*radiusMm*radiusMm*radiusMm/3.0;}
        @Override double areaMm2(){return 4.0*Math.PI*radiusMm*radiusMm;}
        @Override String detail(){return "Sphere • R="+n(radiusMm)+" mm";}
    }

    static SolidCSG makeCylinder(Geometry3D.Vec3 base,Geometry3D.Vec3 axis,float radius,float height,int quality){
        if(base==null||axis==null||axis.length()<1e-6f||radius<=0f||height<=0f)return empty();
        int n=segments(quality);
        Geometry3D.Vec3 w=axis.normalized();
        Geometry3D.Vec3 u=perpendicular(w),v=w.cross(u).normalized();
        List<Geometry3D.Vec3> bottom=ring(base,u,v,radius,n);
        List<Geometry3D.Vec3> top=ring(base.add(w.mul(height)),u,v,radius,n);
        List<SolidCSG.Polygon> polys=new ArrayList<>();
        addCap(polys,bottom,true);addCap(polys,top,false);connect(polys,bottom,top);
        return SolidCSG.fromPolygons(polys);
    }

    static SolidCSG makeCone(Geometry3D.Vec3 base,Geometry3D.Vec3 axis,float r0,float r1,float height,int quality){
        if(base==null||axis==null||axis.length()<1e-6f||height<=0f||r0<0f||r1<0f||(r0<=0f&&r1<=0f))return empty();
        int n=segments(quality);Geometry3D.Vec3 w=axis.normalized();
        Geometry3D.Vec3 u=perpendicular(w),v=w.cross(u).normalized();
        Geometry3D.Vec3 topCenter=base.add(w.mul(height));
        List<SolidCSG.Polygon> polys=new ArrayList<>();
        List<Geometry3D.Vec3> a=r0>1e-5f?ring(base,u,v,r0,n):Collections.emptyList();
        List<Geometry3D.Vec3> b=r1>1e-5f?ring(topCenter,u,v,r1,n):Collections.emptyList();
        if(!a.isEmpty())addCap(polys,a,true);
        if(!b.isEmpty())addCap(polys,b,false);
        if(!a.isEmpty()&&!b.isEmpty())connect(polys,a,b);
        else if(!a.isEmpty()){
            for(int i=0;i<n;i++)addPolygon(polys,a.get(i),a.get((i+1)%n),topCenter);
        }else{
            for(int i=0;i<n;i++)addPolygon(polys,base,b.get((i+1)%n),b.get(i));
        }
        return SolidCSG.fromPolygons(polys);
    }

    static SolidCSG makeSphere(Geometry3D.Vec3 center,float radius,int quality){
        if(center==null||radius<=0f)return empty();
        int lon=segments(quality),lat=Math.max(12,lon/2);
        List<SolidCSG.Polygon> polys=new ArrayList<>();
        Geometry3D.Vec3 north=center.add(new Geometry3D.Vec3(0,0,radius));
        Geometry3D.Vec3 south=center.add(new Geometry3D.Vec3(0,0,-radius));
        List<List<Geometry3D.Vec3>> rings=new ArrayList<>();
        for(int i=1;i<lat;i++){
            double theta=Math.PI*i/lat;
            float z=(float)(Math.cos(theta)*radius);
            float rr=(float)(Math.sin(theta)*radius);
            List<Geometry3D.Vec3> ring=new ArrayList<>();
            for(int j=0;j<lon;j++){
                double a=2.0*Math.PI*j/lon;
                ring.add(new Geometry3D.Vec3(center.x+(float)Math.cos(a)*rr,center.y+(float)Math.sin(a)*rr,center.z+z));
            }
            rings.add(ring);
        }
        if(rings.isEmpty())return empty();
        List<Geometry3D.Vec3> first=rings.get(0);
        for(int j=0;j<lon;j++)addPolygon(polys,north,first.get(j),first.get((j+1)%lon));
        for(int i=0;i<rings.size()-1;i++){
            List<Geometry3D.Vec3>a=rings.get(i),b=rings.get(i+1);
            for(int j=0;j<lon;j++){
                int k=(j+1)%lon;
                addPolygon(polys,a.get(j),b.get(j),b.get(k),a.get(k));
            }
        }
        List<Geometry3D.Vec3> last=rings.get(rings.size()-1);
        for(int j=0;j<lon;j++)addPolygon(polys,south,last.get((j+1)%lon),last.get(j));
        return SolidCSG.fromPolygons(polys);
    }

    /** Recognizes unmodified analytic primitives from their tessellated boundary. */
    static Primitive recognize(SolidCSG csg){
        if(csg==null||csg.isEmpty())return null;
        Primitive capBased=recognizeCylinderOrCone(csg);
        if(capBased!=null)return capBased;
        return recognizeSphere(csg);
    }

    private static Primitive recognizeCylinderOrCone(SolidCSG csg){
        List<SolidCSG.Polygon> caps=new ArrayList<>();
        for(SolidCSG.Polygon p:csg.polygons())if(p.vertices.size()>=12)caps.add(p);
        if(caps.size()>=2){
            SolidCSG.Polygon a=caps.get(0),b=null;
            for(int i=1;i<caps.size();i++)if(a.plane.normal.dot(caps.get(i).plane.normal)<-0.95f){b=caps.get(i);break;}
            if(b!=null){
                Geometry3D.Vec3 ca=a.centroid(),cb=b.centroid();Geometry3D.Vec3 axis=cb.sub(ca);float h=axis.length();
                if(h<1e-4f)return null;axis=axis.normalized();
                float r0=meanRadius(a,ca,axis),r1=meanRadius(b,cb,axis);
                if(r0<=0f||r1<=0f||radialSpread(a,ca,axis,r0)>Math.max(0.03f,r0*0.003f)||radialSpread(b,cb,axis,r1)>Math.max(0.03f,r1*0.003f))return null;
                if(Math.abs(r0-r1)<=Math.max(0.03f,Math.max(r0,r1)*0.003f))return new Cylinder(ca,axis,(r0+r1)*0.5f,h);
                return new Cone(ca,axis,r0,r1,h);
            }
        }
        if(caps.size()==1){
            SolidCSG.Polygon cap=caps.get(0);Geometry3D.Vec3 c=cap.centroid();Geometry3D.Vec3 n=cap.plane.normal.normalized();
            float r=meanRadius(cap,c,n);if(r<=0f||radialSpread(cap,c,n,r)>Math.max(0.03f,r*0.003f))return null;
            Geometry3D.Vec3 apex=findApex(csg,cap,c,n,r);
            if(apex!=null){
                Geometry3D.Vec3 d=apex.sub(c);float h=Math.abs(d.dot(n));if(h>1e-3f){Geometry3D.Vec3 axis=d.normalized();return new Cone(c,axis,r,0f,h);}
            }
        }
        return null;
    }

    private static Geometry3D.Vec3 findApex(SolidCSG csg,SolidCSG.Polygon cap,Geometry3D.Vec3 center,Geometry3D.Vec3 normal,float radius){
        Geometry3D.Vec3 best=null;float bestAbs=0f;
        for(SolidCSG.Polygon p:csg.polygons())for(SolidCSG.Vertex v:p.vertices){
            Geometry3D.Vec3 d=v.pos.sub(center);float axial=d.dot(normal);Geometry3D.Vec3 radial=d.sub(normal.mul(axial));
            if(Math.abs(axial)>bestAbs&&radial.length()<Math.max(0.05f,radius*0.01f)){bestAbs=Math.abs(axial);best=v.pos;}
        }
        return best;
    }

    private static Primitive recognizeSphere(SolidCSG csg){
        List<Geometry3D.Vec3> pts=uniqueVertices(csg);if(pts.size()<24)return null;
        float minX=Float.MAX_VALUE,minY=Float.MAX_VALUE,minZ=Float.MAX_VALUE,maxX=-Float.MAX_VALUE,maxY=-Float.MAX_VALUE,maxZ=-Float.MAX_VALUE;
        for(Geometry3D.Vec3 p:pts){minX=Math.min(minX,p.x);minY=Math.min(minY,p.y);minZ=Math.min(minZ,p.z);maxX=Math.max(maxX,p.x);maxY=Math.max(maxY,p.y);maxZ=Math.max(maxZ,p.z);}
        Geometry3D.Vec3 c=new Geometry3D.Vec3((minX+maxX)*0.5f,(minY+maxY)*0.5f,(minZ+maxZ)*0.5f);
        float mean=0f;for(Geometry3D.Vec3 p:pts)mean+=p.sub(c).length();mean/=pts.size();if(mean<=1e-4f)return null;
        float spread=0f;for(Geometry3D.Vec3 p:pts)spread=Math.max(spread,Math.abs(p.sub(c).length()-mean));
        float dx=maxX-minX,dy=maxY-minY,dz=maxZ-minZ;
        if(spread<=Math.max(0.04f,mean*0.004f)&&Math.abs(dx-dy)<mean*0.02f&&Math.abs(dx-dz)<mean*0.02f)return new Sphere(c,mean);
        return null;
    }

    private static List<Geometry3D.Vec3> uniqueVertices(SolidCSG csg){
        List<Geometry3D.Vec3> out=new ArrayList<>();
        for(SolidCSG.Polygon p:csg.polygons())for(SolidCSG.Vertex v:p.vertices){
            boolean found=false;for(Geometry3D.Vec3 q:out)if(q.sub(v.pos).length()<1e-4f){found=true;break;}if(!found)out.add(v.pos);
        }
        return out;
    }

    private static float meanRadius(SolidCSG.Polygon p,Geometry3D.Vec3 c,Geometry3D.Vec3 axis){
        float s=0f;for(SolidCSG.Vertex v:p.vertices){Geometry3D.Vec3 d=v.pos.sub(c);d=d.sub(axis.mul(d.dot(axis)));s+=d.length();}return s/Math.max(1,p.vertices.size());
    }
    private static float radialSpread(SolidCSG.Polygon p,Geometry3D.Vec3 c,Geometry3D.Vec3 axis,float mean){
        float s=0f;for(SolidCSG.Vertex v:p.vertices){Geometry3D.Vec3 d=v.pos.sub(c);d=d.sub(axis.mul(d.dot(axis)));s=Math.max(s,Math.abs(d.length()-mean));}return s;
    }

    private static int segments(int q){return Math.max(24,Math.min(144,q<=0?72:q));}
    private static Geometry3D.Vec3 perpendicular(Geometry3D.Vec3 n){Geometry3D.Vec3 ref=Math.abs(n.z)<0.85f?new Geometry3D.Vec3(0,0,1):new Geometry3D.Vec3(0,1,0);return ref.cross(n).normalized();}
    private static List<Geometry3D.Vec3> ring(Geometry3D.Vec3 c,Geometry3D.Vec3 u,Geometry3D.Vec3 v,float r,int n){List<Geometry3D.Vec3> out=new ArrayList<>();for(int i=0;i<n;i++){double a=2*Math.PI*i/n;out.add(c.add(u.mul((float)Math.cos(a)*r)).add(v.mul((float)Math.sin(a)*r)));}return out;}
    private static void connect(List<SolidCSG.Polygon> out,List<Geometry3D.Vec3>a,List<Geometry3D.Vec3>b){int n=Math.min(a.size(),b.size());for(int i=0;i<n;i++){int j=(i+1)%n;addPolygon(out,a.get(i),a.get(j),b.get(j),b.get(i));}}
    private static void addCap(List<SolidCSG.Polygon> out,List<Geometry3D.Vec3> ring,boolean reverse){List<Geometry3D.Vec3> q=new ArrayList<>(ring);if(reverse)Collections.reverse(q);addPolygon(out,q.toArray(new Geometry3D.Vec3[0]));}
    private static void addPolygon(List<SolidCSG.Polygon> out,Geometry3D.Vec3... pts){List<SolidCSG.Vertex> v=new ArrayList<>();for(Geometry3D.Vec3 p:pts)if(p!=null)v.add(new SolidCSG.Vertex(p));if(v.size()>=3)out.add(new SolidCSG.Polygon(v));}
    private static SolidCSG empty(){return SolidCSG.fromPolygons(new ArrayList<>());}
    private static String n(double v){String s=String.format(Locale.US,"%.3f",v);while(s.contains(".")&&(s.endsWith("0")||s.endsWith(".")))s=s.substring(0,s.length()-1);return s;}
}

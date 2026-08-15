package ir.chobyar.sketch;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Exact analytic intersection layer for the Java prototype.
 *
 * The Boolean volume is still clipped by SolidCSG, but this kernel computes the
 * mathematical intersection between analytic curved surfaces and true planar
 * B-Rep faces without using the tessellation facets.  The resulting Circle,
 * Ellipse and straight intersection edges are therefore independent of preview
 * segment count and can later be handed directly to a native B-Rep kernel.
 */
final class ExactIntersectionKernel {

    enum CurveType {
        CIRCLE,
        ELLIPSE,
        PARALLEL_LINES,
        TANGENT_LINE,
        POINT,
        HYPERBOLA,
        PARABOLA,
        DEGENERATE,
        NONE
    }

    static final class PlaneSection {
        final CurveType type;
        final AnalyticSolidKernel.Primitive source;
        final Geometry3D.Vec3 planePoint;
        final Geometry3D.Vec3 planeNormal;
        final Geometry3D.Vec3 planeU;
        final Geometry3D.Vec3 planeV;
        final double A, B, C, D, E, F;

        Geometry3D.Vec3 center;
        Geometry3D.Vec3 axisA;
        Geometry3D.Vec3 axisB;
        float radiusA;
        float radiusB;

        Geometry3D.Vec3 lineA0, lineA1, lineB0, lineB1;
        Geometry3D.Vec3 point;

        PlaneSection(CurveType type,
                     AnalyticSolidKernel.Primitive source,
                     Geometry3D.Vec3 planePoint,
                     Geometry3D.Vec3 planeNormal,
                     Geometry3D.Vec3 planeU,
                     Geometry3D.Vec3 planeV,
                     double A,double B,double C,double D,double E,double F) {
            this.type=type;
            this.source=source;
            this.planePoint=planePoint;
            this.planeNormal=planeNormal;
            this.planeU=planeU;
            this.planeV=planeV;
            this.A=A;this.B=B;this.C=C;this.D=D;this.E=E;this.F=F;
        }

        boolean isClosedCurve() {
            return type==CurveType.CIRCLE || type==CurveType.ELLIPSE;
        }

        List<Geometry3D.Vec3> sample(int count) {
            if(isClosedCurve() && center!=null && axisA!=null && axisB!=null && radiusA>0f && radiusB>0f) {
                int n=Math.max(24,Math.min(256,count));
                List<Geometry3D.Vec3> out=new ArrayList<>();
                for(int i=0;i<n;i++) {
                    double a=2.0*Math.PI*i/n;
                    Geometry3D.Vec3 p=center
                            .add(axisA.mul((float)Math.cos(a)*radiusA))
                            .add(axisB.mul((float)Math.sin(a)*radiusB));
                    if(withinFiniteSource(source,p,0.08f)) out.add(p);
                }
                return out;
            }
            if(type==CurveType.PARALLEL_LINES || type==CurveType.TANGENT_LINE) {
                List<Geometry3D.Vec3> out=new ArrayList<>();
                if(lineA0!=null){out.add(lineA0);out.add(mid(lineA0,lineA1));out.add(lineA1);}
                if(type==CurveType.PARALLEL_LINES && lineB0!=null){out.add(lineB0);out.add(mid(lineB0,lineB1));out.add(lineB1);}
                return out;
            }
            if(type==CurveType.POINT && point!=null)return Collections.singletonList(point);
            return Collections.emptyList();
        }

        float finiteCoverage(int count) {
            if(!isClosedCurve())return 1f;
            int n=Math.max(24,Math.min(256,count));int inside=0;
            for(int i=0;i<n;i++) {
                double a=2.0*Math.PI*i/n;
                Geometry3D.Vec3 p=center
                        .add(axisA.mul((float)Math.cos(a)*radiusA))
                        .add(axisB.mul((float)Math.sin(a)*radiusB));
                if(withinFiniteSource(source,p,0.08f))inside++;
            }
            return inside/(float)n;
        }

        String compact() {
            if(type==CurveType.CIRCLE)return "Circle • Ø "+dual(radiusA*2f);
            if(type==CurveType.ELLIPSE)return "Ellipse • 2a "+dual(radiusA*2f)+" • 2b "+dual(radiusB*2f);
            if(type==CurveType.PARALLEL_LINES)return "2 خط تقاطع دقیق";
            if(type==CurveType.TANGENT_LINE)return "خط مماس دقیق";
            if(type==CurveType.POINT)return "نقطه تماس دقیق";
            if(type==CurveType.HYPERBOLA)return "Hyperbola • معادله تحلیلی";
            if(type==CurveType.PARABOLA)return "Parabola • معادله تحلیلی";
            return type.name();
        }

        String detail() {
            StringBuilder s=new StringBuilder(compact());
            if(center!=null)s.append("\nCenter: ").append(vec(center));
            if(type==CurveType.CIRCLE||type==CurveType.ELLIPSE) {
                s.append("\nNormal: ").append(vec(planeNormal));
                s.append("\nCoverage on finite primitive: ").append(num(finiteCoverage(96)*100f)).append("%");
            }
            s.append("\nConic: ").append(equation());
            return s.toString();
        }

        String equation() {
            return num((float)A)+"u² + "+num((float)B)+"uv + "+num((float)C)+"v² + "
                    +num((float)D)+"u + "+num((float)E)+"v + "+num((float)F)+" = 0";
        }
    }

    static final class BoundaryCurve {
        final String id;
        final String faceGroup;
        final PlaneSection section;
        BoundaryCurve(String id,String faceGroup,PlaneSection section){
            this.id=id;this.faceGroup=faceGroup;this.section=section;
        }
    }

    private ExactIntersectionKernel() {}

    static PlaneSection section(AnalyticSolidKernel.Primitive primitive,
                                Geometry3D.Vec3 planePoint,
                                Geometry3D.Vec3 planeNormal) {
        if(primitive==null||planePoint==null||planeNormal==null||planeNormal.length()<1e-7f)return null;
        Geometry3D.Vec3 n=planeNormal.normalized();

        if(primitive instanceof AnalyticSolidKernel.Cylinder) {
            AnalyticSolidKernel.Cylinder c=(AnalyticSolidKernel.Cylinder)primitive;
            float alpha=Math.abs(n.dot(c.axis));
            if(alpha<1e-5f)return cylinderParallelSection(c,planePoint,n);
        }

        Geometry3D.Vec3 u=perpendicular(n);
        Geometry3D.Vec3 v=n.cross(u).normalized();
        double f00=implicit(primitive,planePoint);
        double fp0=implicit(primitive,planePoint.add(u));
        double fm0=implicit(primitive,planePoint.sub(u));
        double f0p=implicit(primitive,planePoint.add(v));
        double f0m=implicit(primitive,planePoint.sub(v));
        double fpp=implicit(primitive,planePoint.add(u).add(v));
        double fpm=implicit(primitive,planePoint.add(u).sub(v));
        double fmp=implicit(primitive,planePoint.sub(u).add(v));
        double fmm=implicit(primitive,planePoint.sub(u).sub(v));

        double A=(fp0+fm0-2.0*f00)/2.0;
        double C=(f0p+f0m-2.0*f00)/2.0;
        double D=(fp0-fm0)/2.0;
        double E=(f0p-f0m)/2.0;
        double B=(fpp-fpm-fmp+fmm)/4.0;
        double F=f00;

        return classify(primitive,planePoint,n,u,v,A,B,C,D,E,F);
    }

    static List<BoundaryCurve> intersectWithPlanarBody(AnalyticSolidKernel.Primitive primitive,
                                                       SolidCSG host) {
        if(primitive==null||host==null||host.isEmpty())return Collections.emptyList();
        BRepTopology topo=BRepTopology.build(host);
        List<BoundaryCurve> out=new ArrayList<>();
        Set<String> seen=new LinkedHashSet<>();
        for(String groupKey:topo.coplanarFaceGroups.keySet()) {
            List<Integer> indices=topo.coplanarFaceGroups.get(groupKey);
            if(indices==null||indices.isEmpty())continue;
            BRepTopology.TopoFace face=topo.faces.get(indices.get(0));
            PlaneSection section=section(primitive,face.centroid,face.normal);
            if(section==null||section.type==CurveType.NONE||section.type==CurveType.DEGENERATE)continue;
            if(!touchesFaceGroup(section,topo,indices))continue;
            String key=curveKey(section);
            if(seen.add(key))out.add(new BoundaryCurve("X-"+Integer.toHexString((groupKey+"|"+key).hashCode()).toUpperCase(Locale.US),groupKey,section));
        }
        return out;
    }

    /** Exact sphere/sphere intersection. Other curved/curved pairs remain future native-kernel work. */
    static PlaneSection intersectPrimitivePair(AnalyticSolidKernel.Primitive a,
                                               AnalyticSolidKernel.Primitive b) {
        if(a instanceof AnalyticSolidKernel.Sphere && b instanceof AnalyticSolidKernel.Sphere) {
            AnalyticSolidKernel.Sphere s0=(AnalyticSolidKernel.Sphere)a;
            AnalyticSolidKernel.Sphere s1=(AnalyticSolidKernel.Sphere)b;
            Geometry3D.Vec3 delta=s1.center.sub(s0.center);float d=delta.length();
            float r0=s0.radiusMm,r1=s1.radiusMm;
            if(d<1e-6f)return null;
            if(d>r0+r1+1e-5f||d<Math.abs(r0-r1)-1e-5f)return null;
            Geometry3D.Vec3 n=delta.normalized();
            float x=(r0*r0-r1*r1+d*d)/(2f*d);
            float rr2=r0*r0-x*x;
            Geometry3D.Vec3 c=s0.center.add(n.mul(x));
            Geometry3D.Vec3 u=perpendicular(n),v=n.cross(u).normalized();
            if(rr2<=1e-6f){
                PlaneSection p=new PlaneSection(CurveType.POINT,a,c,n,u,v,1,0,1,0,0,0);
                p.point=c;return p;
            }
            float r=(float)Math.sqrt(rr2);
            PlaneSection p=new PlaneSection(CurveType.CIRCLE,a,c,n,u,v,1,0,1,0,0,-r*r);
            p.center=c;p.axisA=u;p.axisB=v;p.radiusA=r;p.radiusB=r;return p;
        }
        return null;
    }

    private static PlaneSection classify(AnalyticSolidKernel.Primitive primitive,
                                         Geometry3D.Vec3 p0,Geometry3D.Vec3 n,
                                         Geometry3D.Vec3 u,Geometry3D.Vec3 v,
                                         double A,double B,double C,double D,double E,double F) {
        double scale=Math.max(1.0,Math.max(Math.abs(A)+Math.abs(B)+Math.abs(C),Math.abs(D)+Math.abs(E)+Math.abs(F)));
        double eps=1e-8*scale;
        double det=4.0*A*C-B*B;

        if(Math.abs(A)+Math.abs(B)+Math.abs(C)<eps) {
            if(Math.abs(D)+Math.abs(E)<eps)return new PlaneSection(CurveType.NONE,primitive,p0,n,u,v,A,B,C,D,E,F);
            return new PlaneSection(CurveType.DEGENERATE,primitive,p0,n,u,v,A,B,C,D,E,F);
        }

        if(Math.abs(det)<eps) {
            CurveType t=CurveType.PARABOLA;
            return new PlaneSection(t,primitive,p0,n,u,v,A,B,C,D,E,F);
        }

        double cx=(B*E-2.0*C*D)/det;
        double cy=(B*D-2.0*A*E)/det;
        double fc=eval(A,B,C,D,E,F,cx,cy);
        double trace=A+C;
        double root=Math.sqrt((A-C)*(A-C)+B*B);
        double l1=(trace+root)/2.0,l2=(trace-root)/2.0;
        double discr=B*B-4.0*A*C;

        if(discr<0.0) {
            double r1sq=-fc/l1,r2sq=-fc/l2;
            if(!(r1sq>0.0&&r2sq>0.0))return new PlaneSection(CurveType.NONE,primitive,p0,n,u,v,A,B,C,D,E,F);
            float r1=(float)Math.sqrt(r1sq),r2=(float)Math.sqrt(r2sq);
            double phi=0.5*Math.atan2(B,A-C);
            Geometry3D.Vec3 e1=u.mul((float)Math.cos(phi)).add(v.mul((float)Math.sin(phi))).normalized();
            Geometry3D.Vec3 e2=n.cross(e1).normalized();
            Geometry3D.Vec3 center=p0.add(u.mul((float)cx)).add(v.mul((float)cy));
            CurveType type=Math.abs(r1-r2)<=Math.max(1e-3f,Math.max(r1,r2)*1e-4f)?CurveType.CIRCLE:CurveType.ELLIPSE;
            PlaneSection s=new PlaneSection(type,primitive,p0,n,u,v,A,B,C,D,E,F);
            s.center=center;s.axisA=e1;s.axisB=e2;s.radiusA=r1;s.radiusB=r2;
            if(s.radiusA<s.radiusB){float tr=s.radiusA;s.radiusA=s.radiusB;s.radiusB=tr;Geometry3D.Vec3 tv=s.axisA;s.axisA=s.axisB;s.axisB=tv;}
            return s;
        }
        if(discr>0.0)return new PlaneSection(CurveType.HYPERBOLA,primitive,p0,n,u,v,A,B,C,D,E,F);
        return new PlaneSection(CurveType.DEGENERATE,primitive,p0,n,u,v,A,B,C,D,E,F);
    }

    private static PlaneSection cylinderParallelSection(AnalyticSolidKernel.Cylinder c,
                                                        Geometry3D.Vec3 planePoint,
                                                        Geometry3D.Vec3 n) {
        Geometry3D.Vec3 radialN=n.sub(c.axis.mul(n.dot(c.axis))).normalized();
        float signed=radialN.dot(planePoint.sub(c.baseCenter));
        float abs=Math.abs(signed),r=c.radiusMm;
        Geometry3D.Vec3 u=perpendicular(n),v=n.cross(u).normalized();
        if(abs>r+1e-4f)return new PlaneSection(CurveType.NONE,c,planePoint,n,u,v,0,0,0,0,0,1);
        Geometry3D.Vec3 tangent=c.axis.cross(radialN).normalized();
        float side=(float)Math.sqrt(Math.max(0f,r*r-signed*signed));
        Geometry3D.Vec3 q=c.baseCenter.add(radialN.mul(signed));
        if(side<=1e-4f) {
            PlaneSection s=new PlaneSection(CurveType.TANGENT_LINE,c,planePoint,n,u,v,0,0,1,0,0,0);
            s.lineA0=q;s.lineA1=q.add(c.axis.mul(c.heightMm));return s;
        }
        PlaneSection s=new PlaneSection(CurveType.PARALLEL_LINES,c,planePoint,n,u,v,0,0,1,0,0,-side*side);
        s.lineA0=q.add(tangent.mul(side));s.lineA1=s.lineA0.add(c.axis.mul(c.heightMm));
        s.lineB0=q.sub(tangent.mul(side));s.lineB1=s.lineB0.add(c.axis.mul(c.heightMm));
        return s;
    }

    private static double implicit(AnalyticSolidKernel.Primitive primitive, Geometry3D.Vec3 p) {
        if(primitive instanceof AnalyticSolidKernel.Sphere) {
            AnalyticSolidKernel.Sphere s=(AnalyticSolidKernel.Sphere)primitive;
            Geometry3D.Vec3 d=p.sub(s.center);return d.dot(d)-s.radiusMm*s.radiusMm;
        }
        if(primitive instanceof AnalyticSolidKernel.Cylinder) {
            AnalyticSolidKernel.Cylinder c=(AnalyticSolidKernel.Cylinder)primitive;
            Geometry3D.Vec3 d=p.sub(c.baseCenter);float t=d.dot(c.axis);
            Geometry3D.Vec3 radial=d.sub(c.axis.mul(t));return radial.dot(radial)-c.radiusMm*c.radiusMm;
        }
        AnalyticSolidKernel.Cone c=(AnalyticSolidKernel.Cone)primitive;
        Geometry3D.Vec3 d=p.sub(c.baseCenter);float t=d.dot(c.axis);
        Geometry3D.Vec3 radial=d.sub(c.axis.mul(t));
        double k=(c.topRadiusMm-c.baseRadiusMm)/Math.max(1e-9,c.heightMm);
        double radius=c.baseRadiusMm+k*t;
        return radial.dot(radial)-radius*radius;
    }

    private static boolean withinFiniteSource(AnalyticSolidKernel.Primitive source,Geometry3D.Vec3 p,float tol) {
        if(source instanceof AnalyticSolidKernel.Sphere)return true;
        Geometry3D.Vec3 base;Geometry3D.Vec3 axis;float h;
        if(source instanceof AnalyticSolidKernel.Cylinder){AnalyticSolidKernel.Cylinder c=(AnalyticSolidKernel.Cylinder)source;base=c.baseCenter;axis=c.axis;h=c.heightMm;}
        else{AnalyticSolidKernel.Cone c=(AnalyticSolidKernel.Cone)source;base=c.baseCenter;axis=c.axis;h=c.heightMm;}
        float t=p.sub(base).dot(axis);return t>=-tol&&t<=h+tol;
    }

    private static boolean touchesFaceGroup(PlaneSection section,BRepTopology topo,List<Integer> indices) {
        List<Geometry3D.Vec3> samples=section.sample(128);
        if(samples.isEmpty())return false;
        for(Geometry3D.Vec3 p:samples) {
            for(Integer index:indices) {
                if(index==null||index<0||index>=topo.faces.size())continue;
                if(pointInFace(p,topo.faces.get(index)))return true;
            }
        }
        return false;
    }

    private static boolean pointInFace(Geometry3D.Vec3 p,BRepTopology.TopoFace face) {
        if(face==null||face.polygon==null||face.polygon.vertices.size()<3)return false;
        Geometry3D.Vec3 n=face.normal.normalized();
        if(Math.abs(n.dot(p.sub(face.centroid)))>0.12f)return false;
        Geometry3D.Vec3 u=perpendicular(n),v=n.cross(u).normalized();
        float px=p.sub(face.centroid).dot(u),py=p.sub(face.centroid).dot(v);
        boolean inside=false;int count=face.polygon.vertices.size();
        for(int i=0,j=count-1;i<count;j=i++) {
            Geometry3D.Vec3 pi3=face.polygon.vertices.get(i).pos.sub(face.centroid);
            Geometry3D.Vec3 pj3=face.polygon.vertices.get(j).pos.sub(face.centroid);
            float xi=pi3.dot(u),yi=pi3.dot(v),xj=pj3.dot(u),yj=pj3.dot(v);
            if(distancePointSegment(px,py,xi,yi,xj,yj)<0.08f)return true;
            boolean cross=((yi>py)!=(yj>py)) && (px < (xj-xi)*(py-yi)/(yj-yi+1e-20f)+xi);
            if(cross)inside=!inside;
        }
        return inside;
    }

    private static float distancePointSegment(float px,float py,float ax,float ay,float bx,float by) {
        float dx=bx-ax,dy=by-ay,len2=dx*dx+dy*dy;
        if(len2<1e-12f)return (float)Math.hypot(px-ax,py-ay);
        float t=((px-ax)*dx+(py-ay)*dy)/len2;t=Math.max(0f,Math.min(1f,t));
        return (float)Math.hypot(px-(ax+t*dx),py-(ay+t*dy));
    }

    private static String curveKey(PlaneSection s) {
        if(s.center!=null)return s.type+"|"+q(s.center.x)+","+q(s.center.y)+","+q(s.center.z)+"|"+q(s.radiusA)+","+q(s.radiusB)+"|"+q(s.planeNormal.x)+","+q(s.planeNormal.y)+","+q(s.planeNormal.z);
        if(s.lineA0!=null)return s.type+"|"+q(s.lineA0.x)+","+q(s.lineA0.y)+","+q(s.lineA0.z)+"|"+q(s.lineA1.x)+","+q(s.lineA1.y)+","+q(s.lineA1.z);
        return s.type+"|"+q((float)s.A)+","+q((float)s.B)+","+q((float)s.C)+","+q((float)s.D)+","+q((float)s.E)+","+q((float)s.F);
    }

    private static long q(float v){return Math.round(v*1000f);}
    private static double eval(double A,double B,double C,double D,double E,double F,double x,double y){return A*x*x+B*x*y+C*y*y+D*x+E*y+F;}
    private static Geometry3D.Vec3 perpendicular(Geometry3D.Vec3 n){Geometry3D.Vec3 ref=Math.abs(n.z)<0.85f?new Geometry3D.Vec3(0,0,1):new Geometry3D.Vec3(0,1,0);return ref.cross(n).normalized();}
    private static Geometry3D.Vec3 mid(Geometry3D.Vec3 a,Geometry3D.Vec3 b){return a==null||b==null?null:a.add(b).mul(0.5f);}
    private static String num(float v){String s=String.format(Locale.US,"%.4f",v);while(s.contains(".")&&(s.endsWith("0")||s.endsWith(".")))s=s.substring(0,s.length()-1);return s;}
    private static String dual(float mm){return num(mm/10f)+" cm / "+num(mm)+" mm";}
    private static String vec(Geometry3D.Vec3 v){return v==null?"—":"("+num(v.x)+", "+num(v.y)+", "+num(v.z)+")";}
}

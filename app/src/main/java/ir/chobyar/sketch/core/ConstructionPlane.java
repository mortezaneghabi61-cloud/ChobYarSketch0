package ir.chobyar.sketch.core;

import java.util.Objects;

/** Immutable, project-owned construction geometry. */
public final class ConstructionPlane {
    public static final String XY_ID="plane:xy";
    public static final String XZ_ID="plane:xz";
    public static final String YZ_ID="plane:yz";
    private static final double EPS=1.0e-9;

    public enum Provenance { BASE_XY, BASE_XZ, BASE_YZ, OFFSET, REFERENCE }

    public static final class Vector {
        public final double x,y,z;
        public Vector(double x,double y,double z){
            if(!finite(x)||!finite(y)||!finite(z))throw new IllegalArgumentException("Plane vector must be finite");
            this.x=x;this.y=y;this.z=z;
        }
        public double length(){return Math.sqrt(x*x+y*y+z*z);}
        public double dot(Vector other){return x*other.x+y*other.y+z*other.z;}
        public Vector cross(Vector other){return new Vector(y*other.z-z*other.y,z*other.x-x*other.z,x*other.y-y*other.x);}
        public Vector plus(Vector other){return new Vector(x+other.x,y+other.y,z+other.z);}
        public Vector times(double value){return new Vector(x*value,y*value,z*value);}
        public Vector normalized(){double n=length();if(n<=EPS)throw new IllegalArgumentException("Plane vector is degenerate");return new Vector(x/n,y/n,z/n);}
    }

    /** Typed input used only by deterministic legacy migration. */
    public static final class Legacy {
        public final String sketchId,label;
        public final Vector origin,u,v;
        public Legacy(String sketchId,String label,Vector origin,Vector u,Vector v){
            this.sketchId=require(sketchId,"Legacy sketch id");this.label=require(label,"Legacy plane label");
            this.origin=Objects.requireNonNull(origin,"origin");this.u=Objects.requireNonNull(u,"u");this.v=Objects.requireNonNull(v,"v");
        }
    }

    public final String id,displayName,sourcePlaneId;
    public final Vector origin,uAxis,vAxis,normal;
    public final boolean visible;
    public final Provenance provenance;
    public final double offsetDistanceMm;
    public final long creationOrder;

    private ConstructionPlane(String id,String displayName,Vector origin,Vector uAxis,Vector vAxis,Vector normal,
                              boolean visible,Provenance provenance,String sourcePlaneId,double offsetDistanceMm,long creationOrder){
        this.id=require(id,"Plane id");this.displayName=require(displayName,"Plane name");
        this.origin=Objects.requireNonNull(origin,"origin");this.uAxis=Objects.requireNonNull(uAxis,"uAxis");
        this.vAxis=Objects.requireNonNull(vAxis,"vAxis");this.normal=Objects.requireNonNull(normal,"normal");
        this.provenance=Objects.requireNonNull(provenance,"provenance");this.visible=visible;
        if(creationOrder<0)throw new IllegalArgumentException("Plane creation order is invalid");
        this.creationOrder=creationOrder;
        validateBasis(uAxis,vAxis,normal);
        if(provenance==Provenance.OFFSET){
            this.sourcePlaneId=require(sourcePlaneId,"Offset source plane id");
            if(this.id.equals(this.sourcePlaneId))throw new IllegalArgumentException("Plane cannot offset itself");
            if(!finite(offsetDistanceMm))throw new IllegalArgumentException("Offset distance must be finite");
            this.offsetDistanceMm=offsetDistanceMm;
        }else{
            if(sourcePlaneId!=null&&!sourcePlaneId.trim().isEmpty())throw new IllegalArgumentException("Built-in plane cannot have a source");
            if(offsetDistanceMm!=0.0)throw new IllegalArgumentException("Built-in plane cannot have an offset");
            this.sourcePlaneId=null;this.offsetDistanceMm=0.0;
        }
    }

    public static ConstructionPlane baseXY(){return base(XY_ID,"XY / Top",Provenance.BASE_XY,new Vector(1,0,0),new Vector(0,1,0),0);}
    public static ConstructionPlane baseXZ(){return base(XZ_ID,"XZ / Front",Provenance.BASE_XZ,new Vector(1,0,0),new Vector(0,0,1),1);}
    public static ConstructionPlane baseYZ(){return base(YZ_ID,"YZ / Side",Provenance.BASE_YZ,new Vector(0,1,0),new Vector(0,0,1),2);}
    private static ConstructionPlane base(String id,String name,Provenance type,Vector u,Vector v,long order){
        return new ConstructionPlane(id,name,new Vector(0,0,0),u,v,u.cross(v).normalized(),true,type,null,0,order);
    }

    public static ConstructionPlane offset(String id,String name,String sourceId,double distance,Vector origin,
                                           Vector u,Vector v,Vector normal,boolean visible,long order){
        return new ConstructionPlane(id,name,origin,u,v,normal,visible,Provenance.OFFSET,sourceId,distance,order);
    }

    public static ConstructionPlane reference(String id,String name,Vector origin,Vector u,Vector v,Vector normal,boolean visible,long order){
        return new ConstructionPlane(id,name,origin,u,v,normal,visible,Provenance.REFERENCE,null,0,order);
    }

    public ConstructionPlane renamed(String name){return new ConstructionPlane(id,name,origin,uAxis,vAxis,normal,visible,provenance,sourcePlaneId,offsetDistanceMm,creationOrder);}
    public ConstructionPlane withVisibility(boolean value){return new ConstructionPlane(id,displayName,origin,uAxis,vAxis,normal,value,provenance,sourcePlaneId,offsetDistanceMm,creationOrder);}

    public boolean builtIn(){return provenance==Provenance.BASE_XY||provenance==Provenance.BASE_XZ||provenance==Provenance.BASE_YZ;}

    private static void validateBasis(Vector u,Vector v,Vector normal){
        double ul=u.length(),vl=v.length(),nl=normal.length();
        if(ul<=EPS||vl<=EPS||nl<=EPS)throw new IllegalArgumentException("Plane basis is degenerate");
        double uv=Math.abs(u.dot(v)/(ul*vl));
        if(uv>1.0e-6)throw new IllegalArgumentException("Plane axes must be perpendicular");
        Vector expected=u.cross(v).normalized(),actual=normal.normalized();
        if(expected.dot(actual)<1.0-1.0e-6)throw new IllegalArgumentException("Plane normal does not match its axes");
    }

    private static String require(String value,String label){String out=value==null?"":value.trim();if(out.isEmpty())throw new IllegalArgumentException(label+" is empty");return out;}
    private static boolean finite(double value){return Double.isFinite(value)&&Math.abs(value)<=1.0e12;}
}

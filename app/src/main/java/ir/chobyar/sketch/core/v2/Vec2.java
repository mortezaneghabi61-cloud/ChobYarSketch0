package ir.chobyar.sketch.core.v2;

import java.util.Objects;

/** Immutable 2D vector in model millimetres. */
public final class Vec2 {
    public final double x, y;
    public Vec2(double x, double y) { this.x=x; this.y=y; }
    public Vec2 add(Vec2 b){ return new Vec2(x+b.x,y+b.y); }
    public Vec2 sub(Vec2 b){ return new Vec2(x-b.x,y-b.y); }
    public Vec2 mul(double s){ return new Vec2(x*s,y*s); }
    public double dot(Vec2 b){ return x*b.x+y*b.y; }
    public double cross(Vec2 b){ return x*b.y-y*b.x; }
    public double length(){ return Math.hypot(x,y); }
    public double distance(Vec2 b){ return sub(b).length(); }
    public Vec2 normalized(){ double n=length(); return n<1e-12?new Vec2(0,0):mul(1.0/n); }
    public Vec2 rotate(double radians){ double c=Math.cos(radians),s=Math.sin(radians); return new Vec2(c*x-s*y,s*x+c*y); }
    @Override public boolean equals(Object o){ if(!(o instanceof Vec2))return false; Vec2 v=(Vec2)o; return Double.compare(x,v.x)==0&&Double.compare(y,v.y)==0; }
    @Override public int hashCode(){ return Objects.hash(x,y); }
    @Override public String toString(){ return "("+x+","+y+")"; }
}

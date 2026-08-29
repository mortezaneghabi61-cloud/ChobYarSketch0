package ir.chobyar.sketch;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.PointF;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * CAD-style sketch inspection on top of the exact OCCT workspace.
 *
 * TOOL_MEASURE is selection-aware:
 * - with a selected sketch entity it reports geometric properties directly;
 * - with no selection it falls back to the legacy point-to-point measure tool.
 *
 * This avoids forcing the user to draw a temporary measurement line just to
 * inspect perimeter, area or angle.
 */
public class OcctMeasureCadCanvasView extends OcctStableCadCanvasView {

    private Field selectedField;
    private Field selectedObjectsField;

    public OcctMeasureCadCanvasView(Context context) {
        super(context);
        try {
            selectedField = field(CadCanvasView.class, "selected");
            selectedObjectsField = field(SmartCadCanvasView.class, "selectedObjects");
        } catch (Exception ignored) {}
    }

    private static Field field(Class<?> owner, String name) throws NoSuchFieldException {
        Field f = owner.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }

    @Override
    public void setTool(int newTool) {
        if (newTool == TOOL_MEASURE && !selection().isEmpty()) {
            showSketchMeasureInspector();
            return;
        }
        super.setTool(newTool);
    }

    @Override
    public String selectedInfo() {
        String base = super.selectedInfo();
        List<Object> selected = selection();
        if (selected.isEmpty()) return base;
        Metric m = inspect(selected);
        return m.shortSummary.isEmpty() ? base : base + " | " + m.shortSummary;
    }

    public void showSketchMeasureInspector() {
        List<Object> selected = selection();
        if (selected.isEmpty()) {
            super.setTool(TOOL_MEASURE);
            return;
        }
        Metric m = inspect(selected);
        new AlertDialog.Builder(getContext())
                .setTitle("Measure • Measure Sketch")
                .setMessage(m.details)
                .setPositiveButton("Close", null)
                .setNeutralButton("Measure PointtextPoint", (d,w) -> OcctMeasureCadCanvasView.super.setTool(TOOL_MEASURE))
                .show();
    }

    private Metric inspect(List<Object> selected) {
        if (selected.size() >= 2 && isLine(selected.get(0)) && isLine(selected.get(1))) {
            Object a = selected.get(0), b = selected.get(1);
            double ax = num(a,"x2")-num(a,"x1"), ay = num(a,"y2")-num(a,"y1");
            double bx = num(b,"x2")-num(b,"x1"), by = num(b,"y2")-num(b,"y1");
            double la = Math.hypot(ax,ay), lb = Math.hypot(bx,by);
            double angle = angleBetween(ax,ay,bx,by);
            String details = "text Line selected \n  \n "
                    + "Line 1: " + dualLength(la) + "\n"
                    + "Line 2: " + dualLength(lb) + "\n"
                    + "Angle text text Line: " + fmt(angle) + "°";
            return new Metric("Angle " + fmt(angle) + "°", details);
        }

        if (selected.size() == 1) return inspectOne(selected.get(0));

        double totalLength = 0d, totalArea = 0d;
        int areaCount = 0;
        StringBuilder rows = new StringBuilder();
        for (int i=0;i<selected.size();i++) {
            Metric m = inspectOne(selected.get(i));
            if (i>0) rows.append("\n\n");
            rows.append(i+1).append(". ").append(m.details.replace("\n", " • "));
            totalLength += m.perimeterOrLength;
            if (m.area >= 0d) { totalArea += m.area; areaCount++; }
        }
        StringBuilder head = new StringBuilder("text Selection: ").append(selected.size()).append("\n");
        if (totalLength > 0) head.append("text Length/text: ").append(dualLength(totalLength)).append("\n");
        if (areaCount > 0) head.append("text text text: ").append(dualArea(totalArea)).append("\n");
        head.append("\n").append(rows);
        String shortText = totalLength > 0 ? "Σ " + dualLength(totalLength) : "Measure";
        return new Metric(shortText, head.toString(), totalLength, areaCount>0?totalArea:-1d);
    }

    private Metric inspectOne(Object e) {
        if (e == null) return new Metric("", "text text not selected");
        String type = e.getClass().getSimpleName();

        if ("LineEntity".equals(type)) {
            double dx=num(e,"x2")-num(e,"x1"), dy=num(e,"y2")-num(e,"y1");
            double len=Math.hypot(dx,dy);
            double angle=Math.toDegrees(Math.atan2(dy,dx));
            if(angle<0) angle+=180d;
            if(angle>=180d) angle-=180d;
            String d="Line \n Length: "+dualLength(len)+" \n Angle text text X: "+fmt(angle)+"°";
            return new Metric(dualLength(len)+" • "+fmt(angle)+"°", d, len, -1d);
        }

        if ("RectEntity".equals(type)) {
            List<PointF> p=pointArray(e,"p");
            return polygonMetric("Rectangle",p,true);
        }

        if ("CircleEntity".equals(type)) {
            double r=Math.abs(num(e,"r"));
            double perimeter=2d*Math.PI*r, area=Math.PI*r*r;
            String d="Circle \n Radius: "+dualLength(r)
                    +" \n Diameter: "+dualLength(2d*r)
                    +" \n text: "+dualLength(perimeter)
                    +" \n text: "+dualArea(area)
                    +" \n Angle text: 360°";
            return new Metric("text "+dualLength(perimeter)+" • text "+dualArea(area),d,perimeter,area);
        }

        if ("ArcEntity".equals(type)) {
            double r=Math.abs(num(e,"r")), sweep=Math.abs(num(e,"sweep"));
            double arcLength=Math.toRadians(sweep)*r;
            double chord=2d*r*Math.sin(Math.toRadians(sweep)/2d);
            String d="text \n Radius: "+dualLength(r)
                    +" \n Angle text: "+fmt(sweep)+"°"
                    +" \n Length text: "+dualLength(arcLength)
                    +" \n text: "+dualLength(Math.abs(chord));
            return new Metric("text "+dualLength(arcLength)+" • "+fmt(sweep)+"°",d,arcLength,-1d);
        }

        if ("PolygonEntity".equals(type)) {
            return polygonMetric("Polygon",points(e,"points"),true);
        }

        if ("PolylineEntity".equals(type)) {
            List<PointF> p=points(e,"points");
            boolean closed=bool(e,"closed");
            if(closed) return polygonMetric("Polyline text",p,true);
            double len=pathLength(p,false);
            String angles=vertexAngles(p,false);
            String d="Polyline text \n Length text: "+dualLength(len)+(angles.isEmpty()?"":" \n Angle text: "+angles);
            return new Metric("Length "+dualLength(len),d,len,-1d);
        }

        return new Metric("", "text text text text text text text text text: \n "+type);
    }

    private Metric polygonMetric(String label,List<PointF> p,boolean closed) {
        if(p==null||p.size()<2) return new Metric("",label+" invalid text");
        double perimeter=pathLength(p,closed);
        double area=closed&&p.size()>=3?Math.abs(shoelace(p)):-1d;
        String angles=closed&&p.size()>=3?vertexAngles(p,true):"";
        StringBuilder d=new StringBuilder(label)
                .append(" \n text: ").append(dualLength(perimeter));
        if(area>=0)d.append(" \n text: ").append(dualArea(area));
        if(!angles.isEmpty())d.append(" \n Angle text: ").append(angles);
        String shortText="text "+dualLength(perimeter)+(area>=0?" • text "+dualArea(area):"");
        return new Metric(shortText,d.toString(),perimeter,area);
    }

    private static double pathLength(List<PointF> p,boolean closed) {
        double s=0d;
        for(int i=1;i<p.size();i++)s+=dist(p.get(i-1),p.get(i));
        if(closed&&p.size()>2)s+=dist(p.get(p.size()-1),p.get(0));
        return s;
    }

    private static double shoelace(List<PointF> p) {
        double s=0d;
        for(int i=0;i<p.size();i++){
            PointF a=p.get(i),b=p.get((i+1)%p.size());
            s+=(double)a.x*b.y-(double)b.x*a.y;
        }
        return s/2d;
    }

    private static String vertexAngles(List<PointF> p,boolean closed) {
        if(p.size()<3)return"";
        int start=closed?0:1,end=closed?p.size():p.size()-1;
        StringBuilder out=new StringBuilder();
        int shown=0;
        for(int i=start;i<end;i++){
            PointF prev=p.get((i-1+p.size())%p.size());
            PointF cur=p.get(i);
            PointF next=p.get((i+1)%p.size());
            double ax=prev.x-cur.x,ay=prev.y-cur.y,bx=next.x-cur.x,by=next.y-cur.y;
            double a=angleBetween(ax,ay,bx,by);
            if(shown++>0)out.append(", ");
            out.append(fmt(a)).append("°");
            if(shown>=8 && end-start>8){out.append(" …");break;}
        }
        return out.toString();
    }

    private static double angleBetween(double ax,double ay,double bx,double by) {
        double la=Math.hypot(ax,ay),lb=Math.hypot(bx,by);
        if(la<1e-9||lb<1e-9)return 0d;
        double c=(ax*bx+ay*by)/(la*lb);
        c=Math.max(-1d,Math.min(1d,c));
        double deg=Math.toDegrees(Math.acos(c));
        return deg>180d?360d-deg:deg;
    }

    private static double dist(PointF a,PointF b){return Math.hypot(b.x-a.x,b.y-a.y);}

    @SuppressWarnings("unchecked")
    private List<Object> selection() {
        try {
            if(selectedObjectsField!=null){
                Object v=selectedObjectsField.get(this);
                if(v instanceof List && !((List<?>)v).isEmpty())return new ArrayList<>((List<Object>)v);
            }
            if(selectedField!=null){
                Object one=selectedField.get(this);
                if(one!=null){List<Object> out=new ArrayList<>();out.add(one);return out;}
            }
        } catch (Exception ignored) {}
        return new ArrayList<>();
    }

    private static boolean isLine(Object e){return e!=null&&"LineEntity".equals(e.getClass().getSimpleName());}

    private static double num(Object o,String name){
        try{Field f=findField(o.getClass(),name);if(f==null)return 0d;Object v=f.get(o);return v instanceof Number?((Number)v).doubleValue():0d;}
        catch(Exception e){return 0d;}
    }

    private static boolean bool(Object o,String name){
        try{Field f=findField(o.getClass(),name);return f!=null&&f.getBoolean(o);}catch(Exception e){return false;}
    }

    private static List<PointF> pointArray(Object o,String name){
        List<PointF> out=new ArrayList<>();
        try{Field f=findField(o.getClass(),name);Object v=f==null?null:f.get(o);if(v instanceof PointF[])for(PointF p:(PointF[])v)out.add(new PointF(p.x,p.y));}
        catch(Exception ignored){}
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<PointF> points(Object o,String name){
        List<PointF> out=new ArrayList<>();
        try{Field f=findField(o.getClass(),name);Object v=f==null?null:f.get(o);if(v instanceof List)for(Object q:(List<Object>)v)if(q instanceof PointF){PointF p=(PointF)q;out.add(new PointF(p.x,p.y));}}
        catch(Exception ignored){}
        return out;
    }

    private static Field findField(Class<?> c,String name){
        Class<?> x=c;
        while(x!=null){try{Field f=x.getDeclaredField(name);f.setAccessible(true);return f;}catch(Exception e){x=x.getSuperclass();}}
        return null;
    }

    private static String dualLength(double mm){return fmt(mm)+" mm";}
    private static String dualArea(double mm2){return fmt(mm2)+" mm²";}
    private static String fmt(double v){
        String s=String.format(Locale.US,"%.2f",v);
        while(s.contains(".")&&(s.endsWith("0")||s.endsWith(".")))s=s.substring(0,s.length()-1);
        return s;
    }

    private static final class Metric {
        final String shortSummary;
        final String details;
        final double perimeterOrLength;
        final double area;
        Metric(String s,String d){this(s,d,0d,-1d);}
        Metric(String s,String d,double l,double a){shortSummary=s;details=d;perimeterOrLength=l;area=a;}
    }
}

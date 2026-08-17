package ir.chobyar.sketch;

import android.app.AlertDialog;
import android.content.Context;
import android.widget.Toast;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Exact-intersection workspace layered on the analytic Boolean model.
 *
 * Boolean volume clipping is intentionally still delegated to SolidCSG for this
 * prototype, but intersection EDGES are now computed from the mathematical
 * Cylinder/Cone/Sphere master surfaces against planar B-Rep faces.  Circle and
 * ellipse loops therefore retain exact radii/axes regardless of display mesh
 * quality. Sphere/Sphere also has an exact curved/curved intersection circle.
 */
public class ExactBooleanCadCanvasView extends AnalyticBooleanCadCanvasView {

    private static final class BooleanFeatureRef {
        final String operation;
        final Object left;
        final Object right;
        final Object output;
        BooleanFeatureRef(String operation,Object left,Object right,Object output){
            this.operation=operation;this.left=left;this.right=right;this.output=output;
        }
    }

    private static final class ExactEdgeRecord {
        final String role;
        final String source;
        final ExactIntersectionKernel.PlaneSection section;
        ExactEdgeRecord(String role,String source,ExactIntersectionKernel.PlaneSection section){
            this.role=role;this.source=source;this.section=section;
        }
    }

    private Field historyField;
    private Field selectedBodyField;
    private Field bodiesField;

    public ExactBooleanCadCanvasView(Context context){
        super(context);
        initExactReflection();
    }

    private void initExactReflection(){
        try{
            historyField=field(ParametricHistorySolidCadCanvasView.class,"history");
            selectedBodyField=field(SolidCadCanvasView.class,"selectedBody");
            bodiesField=field(SolidCadCanvasView.class,"bodies");
        }catch(Exception ignored){}
    }

    private static Field field(Class<?> owner,String name)throws NoSuchFieldException{
        Field f=owner.getDeclaredField(name);f.setAccessible(true);return f;
    }

    @Override
    public void showSolidManager(){
        Object body=selectedBody();
        BooleanFeatureRef feature=findBooleanFeature(body);
        List<ExactEdgeRecord> exact=body==null?Collections.emptyList():exactEdges(body);
        String summary=body==null?"Body انتخاب نشده":bodyName(body);
        if(feature!=null)summary+=" • "+friendly(feature.operation);
        if(!exact.isEmpty())summary+=" • "+exact.size()+" Exact Edge";

        String[] items={
                "▣ Solid / Boolean / Primitiveهای دقیق",
                "⌁ Exact Intersection Edges / لبه‌های تقاطع ریاضی",
                "◎ Exact Hole Loops / قطر و محور سوراخ",
                "∿ Analytic Boundary Inspector",
                "✓ وضعیت Exact Kernel",
                is3DOverview()?"□ برگشت به Sketch 2D":"◇ نمایش 3D"
        };
        new AlertDialog.Builder(getContext())
                .setTitle("Solid 3D • Exact Edge")
                .setMessage(summary+"\n\nCircle/Ellipse تقاطع از معادله سطح محاسبه می‌شود، نه از Polygonهای نمایش.")
                .setItems(items,(d,w)->{
                    if(w==0)ExactBooleanCadCanvasView.super.showSolidManager();
                    else if(w==1)showExactIntersectionReport();
                    else if(w==2)showExactHoleLoops();
                    else if(w==3)showBoundaryInspector();
                    else if(w==4)showExactKernelStatus();
                    else toast(toggle3DOverview());
                })
                .setNegativeButton("بستن",null).show();
    }

    @Override
    public String selectedInfo(){
        String base=super.selectedInfo();
        Object body=selectedBody();
        if(body==null)return base;
        int n=exactEdges(body).size();
        return n==0?base:base+" | Exact Edge "+n;
    }

    public void showExactIntersectionReport(){
        Object body=selectedBody();
        if(body==null){ensure3D();toast("اول یک Body را انتخاب کن");return;}
        BooleanFeatureRef feature=findBooleanFeature(body);
        if(feature==null){toast("این Body هنوز نتیجه Boolean نیست");return;}
        List<ExactEdgeRecord> edges=exactEdges(body);
        StringBuilder msg=new StringBuilder();
        msg.append("Operation: ").append(friendly(feature.operation));
        msg.append("\nLeft: ").append(bodyName(feature.left));
        msg.append("\nRight: ").append(bodyName(feature.right));
        msg.append("\n\nExact intersection edges: ").append(edges.size());
        if(edges.isEmpty()){
            msg.append("\n\nبرای این ترکیب هنوز Curve بسته قابل Trim پیدا نشد. سطح‌های تحلیلی همچنان در History حفظ می‌شوند؛ curved/curved عمومی مرحله Native B-Rep است.");
        }else{
            for(int i=0;i<edges.size();i++){
                ExactEdgeRecord e=edges.get(i);
                msg.append("\n\n").append(i+1).append(". ").append(e.role)
                        .append(" • ").append(e.source).append("\n")
                        .append(e.section.detail());
            }
            msg.append("\n\nاین پارامترها مستقل از تعداد Segmentهای Preview هستند.");
        }
        new AlertDialog.Builder(getContext()).setTitle("Exact Intersection • "+bodyName(body))
                .setMessage(msg.toString()).setPositiveButton("باشه",null).show();
    }

    public void showExactHoleLoops(){
        Object body=selectedBody();
        if(body==null){ensure3D();toast("اول Body را انتخاب کن");return;}
        BooleanFeatureRef f=findBooleanFeature(body);
        if(f==null||!"SUBTRACT".equals(f.operation)){toast("برای گزارش سوراخ، نتیجه Subtract را انتخاب کن");return;}
        List<ExactEdgeRecord> all=exactEdges(body),loops=new ArrayList<>();
        for(ExactEdgeRecord e:all)if(e.section.type==ExactIntersectionKernel.CurveType.CIRCLE||e.section.type==ExactIntersectionKernel.CurveType.ELLIPSE)loops.add(e);
        if(loops.isEmpty()){toast("Loop بسته دقیق برای این Subtract پیدا نشد");return;}
        StringBuilder msg=new StringBuilder();
        for(int i=0;i<loops.size();i++){
            ExactIntersectionKernel.PlaneSection s=loops.get(i).section;
            msg.append(i+1).append(") ").append(s.type==ExactIntersectionKernel.CurveType.CIRCLE?"Circle":"Ellipse").append("\n");
            if(s.type==ExactIntersectionKernel.CurveType.CIRCLE){
                msg.append("Diameter: ").append(dual(s.radiusA*2f)).append("\nRadius: ").append(dual(s.radiusA));
            }else{
                msg.append("Major diameter: ").append(dual(s.radiusA*2f))
                        .append("\nMinor diameter: ").append(dual(s.radiusB*2f));
            }
            msg.append("\nCenter: ").append(vec(s.center));
            msg.append("\nNormal: ").append(vec(s.planeNormal)).append("\n\n");
        }
        new AlertDialog.Builder(getContext()).setTitle("Exact Hole Loops • mm")
                .setMessage(msg.toString().trim()).setPositiveButton("باشه",null).show();
    }

    private void showExactKernelStatus(){
        Object body=selectedBody();
        List<ExactEdgeRecord> edges=body==null?Collections.emptyList():exactEdges(body);
        String msg="✓ Plane B-Rep faces: exact\n"
                +"✓ Cylinder/Cone/Sphere master surfaces: analytic\n"
                +"✓ Plane ↔ analytic surface intersections: exact conic equation\n"
                +"✓ Circle/Ellipse edge dimensions: exact mm\n"
                +"✓ Sphere ↔ Sphere intersection circle: exact\n"
                +"\nSelected Body exact edges: "+edges.size()
                +"\n\nهنوز Polygonal: حجم نهایی Union/Subtract/Intersect و Trim عمومی curved↔curved. قدم بعدی جایگزینی همین بخش با Native B-Rep است.";
        new AlertDialog.Builder(getContext()).setTitle("Exact Kernel Status")
                .setMessage(msg).setPositiveButton("باشه",null).show();
    }

    private List<ExactEdgeRecord> exactEdges(Object output){
        BooleanFeatureRef f=findBooleanFeature(output);
        if(f==null)return Collections.emptyList();
        List<ExactEdgeRecord> out=new ArrayList<>();
        Map<String,ExactEdgeRecord> unique=new LinkedHashMap<>();

        AnalyticSolidKernel.Primitive lp=primitive(f.left),rp=primitive(f.right);
        if(lp!=null&&rp!=null){
            ExactIntersectionKernel.PlaneSection pair=ExactIntersectionKernel.intersectPrimitivePair(lp,rp);
            if(pair!=null)add(unique,new ExactEdgeRecord("CURVED↔CURVED",bodyName(f.left)+" × "+bodyName(f.right),pair));
        }

        // For Subtract, the right body is the cutter. Its exact surface meeting
        // host planar faces produces the semantic hole/opening loops.
        if("SUBTRACT".equals(f.operation)){
            if(rp!=null && lp==null)addPlanarIntersections(unique,"CUT EDGE",bodyName(f.right),rp,bodyCsg(f.left));
            // Nested hosts can contain both planar and analytic boundaries. We
            // still extract trustworthy plane intersections from their current B-Rep.
            if(rp!=null && lp==null && findBooleanFeature(f.left)!=null){
                addPlanarIntersections(unique,"CUT EDGE",bodyName(f.right),rp,bodyCsg(f.left));
            }
        }else{
            if(rp!=null&&lp==null)addPlanarIntersections(unique,"RIGHT TRIM",bodyName(f.right),rp,bodyCsg(f.left));
            if(lp!=null&&rp==null)addPlanarIntersections(unique,"LEFT TRIM",bodyName(f.left),lp,bodyCsg(f.right));
        }

        out.addAll(unique.values());
        return out;
    }

    private void addPlanarIntersections(Map<String,ExactEdgeRecord> out,String role,String source,
                                        AnalyticSolidKernel.Primitive primitive,SolidCSG host){
        for(ExactIntersectionKernel.BoundaryCurve c:ExactIntersectionKernel.intersectWithPlanarBody(primitive,host)){
            ExactEdgeRecord r=new ExactEdgeRecord(role,source,c.section);
            add(out,r);
        }
    }

    private static void add(Map<String,ExactEdgeRecord> out,ExactEdgeRecord r){
        ExactIntersectionKernel.PlaneSection s=r.section;
        String key=s.type+"|"+(s.center==null?"":q(s.center.x)+","+q(s.center.y)+","+q(s.center.z))+"|"+q(s.radiusA)+","+q(s.radiusB)+"|"+q(s.planeNormal.x)+","+q(s.planeNormal.y)+","+q(s.planeNormal.z);
        if(!out.containsKey(key))out.put(key,r);
    }

    private AnalyticSolidKernel.Primitive primitive(Object body){
        SolidCSG c=bodyCsg(body);return c==null?null:AnalyticSolidKernel.recognize(c);
    }

    private BooleanFeatureRef findBooleanFeature(Object output){
        if(output==null)return null;
        for(Object f:history()){
            if(f==null||!"BooleanFeature".equals(f.getClass().getSimpleName()))continue;
            try{
                Field of=findField(f.getClass(),"outputBody");if(of==null||of.get(f)!=output)continue;
                Field op=findField(f.getClass(),"operation"),lf=findField(f.getClass(),"leftBody"),rf=findField(f.getClass(),"rightBody");
                if(op==null||lf==null||rf==null)return null;
                return new BooleanFeatureRef(String.valueOf(op.get(f)),lf.get(f),rf.get(f),output);
            }catch(Exception ignored){}
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private List<Object> history(){
        try{Object v=historyField==null?null:historyField.get(this);return v instanceof List?(List<Object>)v:Collections.emptyList();}
        catch(Exception e){return Collections.emptyList();}
    }

    @SuppressWarnings("unchecked")
    private List<Object> bodies(){
        try{Object v=bodiesField==null?null:bodiesField.get(this);return v instanceof List?(List<Object>)v:Collections.emptyList();}
        catch(Exception e){return Collections.emptyList();}
    }

    private Object selectedBody(){try{return selectedBodyField==null?null:selectedBodyField.get(this);}catch(Exception e){return null;}}

    private SolidCSG bodyCsg(Object body){
        if(body==null)return null;
        try{Field f=findField(body.getClass(),"csg");Object v=f==null?null:f.get(body);return v instanceof SolidCSG?(SolidCSG)v:null;}
        catch(Exception e){return null;}
    }

    private String bodyName(Object body){
        if(body==null)return"Body";
        try{Field f=findField(body.getClass(),"name");Object v=f==null?null:f.get(body);return v==null?"Body":String.valueOf(v);}
        catch(Exception e){return"Body";}
    }

    private static Field findField(Class<?> c,String name){Class<?> x=c;while(x!=null){try{Field f=x.getDeclaredField(name);f.setAccessible(true);return f;}catch(Exception e){x=x.getSuperclass();}}return null;}
    private void ensure3D(){if(!is3DOverview())toggle3DOverview();}
    private void toast(String s){if(s!=null&&!s.trim().isEmpty())Toast.makeText(getContext(),s,Toast.LENGTH_SHORT).show();}

    private static String friendly(String op){return"UNION".equals(op)?"Union":"SUBTRACT".equals(op)?"Subtract":"Intersect";}
    private static long q(float v){return Math.round(v*1000f);}
    private static String num(float v){String s=String.format(Locale.US,"%.4f",v);while(s.contains(".")&&(s.endsWith("0")||s.endsWith(".")))s=s.substring(0,s.length()-1);return s;}
    private static String dual(float mm){return num(mm)+" mm";}
    private static String vec(Geometry3D.Vec3 v){return v==null?"—":"("+num(v.x)+", "+num(v.y)+", "+num(v.z)+")";}
}

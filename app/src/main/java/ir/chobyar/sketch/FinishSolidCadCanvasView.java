package ir.chobyar.sketch;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.PointF;
import android.text.InputType;
import android.widget.EditText;
import android.widget.Toast;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;

/**
 * Polygonal 3D finishing layer for the current ChobYar solid prototype.
 *
 * These operations intentionally target prism-like / Extrude bodies while the
 * project is still backed by the polygonal CSG kernel. They are real volumetric
 * edits, not visual effects: Fillet rounds vertical prism edges, Chamfer cuts
 * vertical prism corners, and Shell hollows a prism with an open top.
 *
 * Exact arbitrary-edge B-Rep finishing is the next kernel-level milestone. The
 * UI contract here is designed so that the later exact kernel can replace the
 * implementation without changing how the user invokes the tools.
 */
public class FinishSolidCadCanvasView extends ParametricHistorySolidCadCanvasView {

    private enum Kind { FILLET, CHAMFER, SHELL }

    private static final class FinishOp {
        final Kind kind;
        float valueMm;
        FinishOp(Kind kind, float valueMm) { this.kind=kind; this.valueMm=valueMm; }
        String label() {
            String n=kind==Kind.FILLET?"Fillet":kind==Kind.CHAMFER?"Chamfer":"Shell";
            return n+" • "+dual(valueMm);
        }
    }

    private static final class PrismData {
        final List<PointF> profile;
        final Geometry3D.Plane3D plane;
        final float heightMm;
        PrismData(List<PointF> profile, Geometry3D.Plane3D plane, float heightMm) {
            this.profile=profile; this.plane=plane; this.heightMm=heightMm;
        }
    }

    private final IdentityHashMap<Object,List<FinishOp>> finishByBody = new IdentityHashMap<>();
    private final IdentityHashMap<Object,SolidCSG> baseByBody = new IdentityHashMap<>();

    private Field selectedBodyField;

    public FinishSolidCadCanvasView(Context context) {
        super(context);
        try {
            selectedBodyField=field(SolidCadCanvasView.class,"selectedBody");
        } catch (Exception ignored) {}
    }

    private static Field field(Class<?> owner,String name) throws NoSuchFieldException {
        Field f=owner.getDeclaredField(name); f.setAccessible(true); return f;
    }

    public void showFinishManager() {
        Object body=selectedBody();
        String bodyText=body==null?"اول در نمای 3D یک Body را انتخاب کن":"Body انتخاب‌شده: "+bodyName(body);
        String[] items={
                "⌒ Fillet 3D / گردکردن لبه‌های عمودی",
                "◩ Chamfer 3D / پخ گوشه‌های عمودی",
                "▱ Shell / توخالی با دهانه باز",
                "⏱ Finish History / ویرایش عملیات",
                "↺ حذف Finishهای Body"
        };
        new AlertDialog.Builder(getContext())
                .setTitle("Finish 3D")
                .setMessage(bodyText+"\n\nدر هسته فعلی این ابزارها روی Bodyهای Extrude/Prism اعمال می‌شوند. مقدار طول را به mm وارد کن.")
                .setItems(items,(d,w)->{
                    if(w==0)askFinish(Kind.FILLET);
                    else if(w==1)askFinish(Kind.CHAMFER);
                    else if(w==2)askFinish(Kind.SHELL);
                    else if(w==3)showFinishHistory();
                    else toast(clearFinishes());
                })
                .setNegativeButton("بستن",null)
                .show();
    }

    private void askFinish(Kind kind) {
        if(selectedBody()==null){toast("اول یک Body را انتخاب کن");return;}
        List<FinishOp> ops=finishByBody.get(selectedBody());
        if(ops!=null && !ops.isEmpty() && ops.get(ops.size()-1).kind==Kind.SHELL && kind!=Kind.SHELL){
            toast("بعد از Shell فعلاً Finish دیگری اضافه نمی‌شود؛ اول Shell را حذف کن");
            return;
        }
        String title=kind==Kind.FILLET?"Fillet 3D — شعاع":kind==Kind.CHAMFER?"Chamfer 3D — فاصله":"Shell — ضخامت دیواره";
        String hint=kind==Kind.SHELL?"مثال: 18mm یا 1.8cm":"مثال: 5mm یا 0.5cm";
        EditText input=new EditText(getContext());
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setHint(hint);
        input.setText(kind==Kind.SHELL?"2mm":"5mm");
        input.setSelectAllOnFocus(true);
        new AlertDialog.Builder(getContext())
                .setTitle(title+" • mm")
                .setMessage(kind==Kind.FILLET
                        ?"گوشه‌های عمودی Body گرد می‌شوند. شعاع نباید از ضلع‌های پروفایل بزرگ‌تر باشد."
                        :kind==Kind.CHAMFER
                        ?"گوشه‌های عمودی Body به اندازه واردشده پخ می‌شوند."
                        :"داخل Body خالی می‌شود، کف باقی می‌ماند و سطح بالا باز می‌شود.")
                .setView(input)
                .setPositiveButton("اعمال",(d,w)->{
                    try{toast(applyFinish(kind,parseLengthMm(input.getText().toString())));}
                    catch(Exception e){toast("اندازه درست وارد نشده");}
                })
                .setNegativeButton("لغو",null)
                .show();
    }

    private String applyFinish(Kind kind,float valueMm) {
        Object body=selectedBody();
        if(body==null)return"اول یک Body را انتخاب کن";
        if(!(valueMm>0f))return"مقدار باید بزرگ‌تر از صفر باشد";
        SolidCSG current=bodyCsg(body);
        if(current==null||current.isEmpty())return"Body معتبر نیست";

        if(!baseByBody.containsKey(body)){
            PrismData p=analyzePrism(current);
            if(p==null)return"این Finish فعلاً برای Bodyهای Extrude/Prism است";
            baseByBody.put(body,current.copy());
        }

        List<FinishOp> ops=finishByBody.get(body);
        if(ops==null){ops=new ArrayList<>();finishByBody.put(body,ops);}
        if(kind==Kind.SHELL){
            for(FinishOp op:ops)if(op.kind==Kind.SHELL)return"برای این Body قبلاً Shell ثبت شده";
        }
        FinishOp op=new FinishOp(kind,valueMm);
        ops.add(op);
        String result=rebuildFinishes(body);
        if(result.startsWith("خطا")){
            ops.remove(ops.size()-1);
            rebuildFinishes(body);
            return result;
        }
        setOverview(true);
        invalidate();
        return op.label()+" اعمال شد";
    }

    public void showFinishHistory() {
        Object body=selectedBody();
        if(body==null){toast("اول یک Body را انتخاب کن");return;}
        List<FinishOp> ops=finishByBody.get(body);
        if(ops==null||ops.isEmpty()){toast("برای این Body هنوز Finish ثبت نشده");return;}
        String[] rows=new String[ops.size()];
        for(int i=0;i<ops.size();i++)rows[i]=(i+1)+". "+ops.get(i).label();
        new AlertDialog.Builder(getContext())
                .setTitle("Finish History • "+bodyName(body))
                .setMessage("هر عملیات را لمس کن تا مقدارش را ویرایش یا همان Feature را حذف کنی.")
                .setItems(rows,(d,w)->editFinish(body,w))
                .setNeutralButton("بازسازی",(d,w)->toast(rebuildFinishes(body)))
                .setNegativeButton("بستن",null)
                .show();
    }

    private void editFinish(Object body,int index) {
        List<FinishOp> ops=finishByBody.get(body);
        if(ops==null||index<0||index>=ops.size())return;
        FinishOp op=ops.get(index);
        EditText input=new EditText(getContext());
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setText(num(op.valueMm)+"mm"); input.setSelectAllOnFocus(true);
        new AlertDialog.Builder(getContext())
                .setTitle("ویرایش "+op.label())
                .setView(input)
                .setPositiveButton("اعمال",(d,w)->{
                    try{op.valueMm=parseLengthMm(input.getText().toString());toast(rebuildFinishes(body));}
                    catch(Exception e){toast("اندازه درست وارد نشده");}
                })
                .setNeutralButton("حذف Feature",(d,w)->{
                    ops.remove(index);
                    if(ops.isEmpty()){finishByBody.remove(body);restoreBase(body);}
                    else toast(rebuildFinishes(body));
                    invalidate();
                })
                .setNegativeButton("لغو",null)
                .show();
    }

    private String clearFinishes() {
        Object body=selectedBody();
        if(body==null)return"اول یک Body را انتخاب کن";
        finishByBody.remove(body);
        restoreBase(body);
        baseByBody.remove(body);
        invalidate();
        return"Finishهای Body حذف شدند";
    }

    private void restoreBase(Object body) {
        SolidCSG base=baseByBody.get(body);
        if(base!=null)setBodyCsg(body,base.copy());
    }

    private String rebuildFinishes(Object body) {
        SolidCSG base=baseByBody.get(body);
        if(base==null)return"خطا: پایه Body پیدا نشد";
        List<FinishOp> ops=finishByBody.get(body);
        if(ops==null||ops.isEmpty()){setBodyCsg(body,base.copy());return"Finish خالی است";}
        SolidCSG result=base.copy();
        for(FinishOp op:ops){
            PrismData p=analyzePrism(result);
            if(p==null)return"خطا: این ترتیب Finish روی شکل فعلی قابل محاسبه نیست";
            if(op.kind==Kind.FILLET){
                List<PointF> rounded=roundProfile(p.profile,op.valueMm);
                if(rounded==null||rounded.size()<3)return"خطا: شعاع Fillet برای این Body بزرگ است";
                result=SolidCSG.extrude(rounded,p.plane,p.heightMm);
            }else if(op.kind==Kind.CHAMFER){
                List<PointF> cut=chamferProfile(p.profile,op.valueMm);
                if(cut==null||cut.size()<3)return"خطا: اندازه Chamfer برای این Body بزرگ است";
                result=SolidCSG.extrude(cut,p.plane,p.heightMm);
            }else{
                if(op.valueMm>=p.heightMm*0.49f)return"خطا: ضخامت Shell از ارتفاع Body زیاد است";
                List<PointF> innerProfile=insetProfile(p.profile,op.valueMm);
                if(innerProfile==null||innerProfile.size()<3)return"خطا: ضخامت Shell برای این پروفایل زیاد است";
                Geometry3D.Plane3D innerPlane=p.plane.offset(op.valueMm,"Shell inner");
                SolidCSG inner=SolidCSG.extrude(innerProfile,innerPlane,p.heightMm+op.valueMm*1.5f);
                SolidCSG hollow=result.subtract(inner);
                if(hollow.isEmpty())return"خطا: Shell نامعتبر شد";
                result=hollow;
            }
            if(result==null||result.isEmpty())return"خطا: Finish Body نامعتبر ساخت";
        }
        setBodyCsg(body,result);
        invalidate();
        return"Finish بازسازی شد • "+ops.size()+" Feature";
    }

    /**
     * Parent History rebuilds the parametric base first. If that changed a Body
     * that owns finishing features, capture the new base and then reapply the
     * finishing stack so Sketch edits continue through to the final shape.
     */
    @Override
    public String rebuildHistory() {
        IdentityHashMap<Object,String> before=new IdentityHashMap<>();
        for(Object body:finishByBody.keySet())before.put(body,csgSignature(bodyCsg(body)));
        String result=super.rebuildHistory();
        for(Object body:new ArrayList<>(finishByBody.keySet())){
            SolidCSG after=bodyCsg(body);
            if(after==null)continue;
            String old=before.get(body),now=csgSignature(after);
            if(old==null||!old.equals(now))baseByBody.put(body,after.copy());
            rebuildFinishes(body);
        }
        return result;
    }

    @Override
    public void clearAll() {
        super.clearAll();
        finishByBody.clear();
        baseByBody.clear();
    }

    // ------------------------------------------------------------------
    // Prism recognition
    // ------------------------------------------------------------------

    private PrismData analyzePrism(SolidCSG csg) {
        if(csg==null||csg.polygons().size()<3)return null;
        SolidCSG.Polygon a=csg.polygons().get(0),b=csg.polygons().get(1);
        if(a.vertices.size()<3||a.vertices.size()!=b.vertices.size())return null;
        if(a.plane.normal.dot(b.plane.normal)>-0.92f)return null;
        Geometry3D.Vec3 ca=a.centroid(),cb=b.centroid();
        Geometry3D.Vec3 axis=cb.sub(ca);float h=axis.length();
        if(h<1e-4f)return null;
        Geometry3D.Vec3 n=axis.normalized();
        Geometry3D.Vec3 origin=a.vertices.get(0).pos;
        Geometry3D.Vec3 edge=a.vertices.get(1).pos.sub(origin);
        if(edge.length()<1e-5f)return null;
        Geometry3D.Vec3 u=edge.normalized();
        Geometry3D.Vec3 v=n.cross(u).normalized();
        if(v.length()<0.5f)return null;
        Geometry3D.Plane3D plane=new Geometry3D.Plane3D(origin,u,v,"Finish Plane");
        List<PointF> profile=new ArrayList<>();
        for(SolidCSG.Vertex vertex:a.vertices){
            Geometry3D.Vec3 d=vertex.pos.sub(origin);
            profile.add(new PointF(d.dot(plane.u),d.dot(plane.v)));
        }
        if(Math.abs(signedArea(profile))<1e-4f)return null;
        return new PrismData(profile,plane,h);
    }

    // ------------------------------------------------------------------
    // 2D profile finishing math
    // ------------------------------------------------------------------

    private static List<PointF> chamferProfile(List<PointF> p,float d) {
        if(p==null||p.size()<3||d<=0)return null;
        float area=signedArea(p);float sign=area>=0?1f:-1f;
        List<PointF> out=new ArrayList<>();int n=p.size();
        for(int i=0;i<n;i++){
            PointF prev=p.get((i-1+n)%n),cur=p.get(i),next=p.get((i+1)%n);
            float e1x=cur.x-prev.x,e1y=cur.y-prev.y,e2x=next.x-cur.x,e2y=next.y-cur.y;
            float cross=e1x*e2y-e1y*e2x;
            if(cross*sign<=1e-5f){out.add(new PointF(cur.x,cur.y));continue;}
            float l1=len(prev,cur),l2=len(cur,next);float t=Math.min(d,Math.min(l1,l2)*0.45f);
            if(t<=1e-5f){out.add(new PointF(cur.x,cur.y));continue;}
            out.add(moveToward(cur,prev,t));out.add(moveToward(cur,next,t));
        }
        return validProfile(out,area)?out:null;
    }

    private static List<PointF> roundProfile(List<PointF> p,float radius) {
        if(p==null||p.size()<3||radius<=0)return null;
        float area=signedArea(p);float sign=area>=0?1f:-1f;
        List<PointF> out=new ArrayList<>();int n=p.size();
        for(int i=0;i<n;i++){
            PointF prev=p.get((i-1+n)%n),cur=p.get(i),next=p.get((i+1)%n);
            float inx=cur.x-prev.x,iny=cur.y-prev.y,outx=next.x-cur.x,outy=next.y-cur.y;
            float cross=inx*outy-iny*outx;
            if(cross*sign<=1e-5f){out.add(new PointF(cur.x,cur.y));continue;}
            float l1=len(prev,cur),l2=len(cur,next);
            float ax=(prev.x-cur.x)/l1,ay=(prev.y-cur.y)/l1;
            float bx=(next.x-cur.x)/l2,by=(next.y-cur.y)/l2;
            float dot=clamp(ax*bx+ay*by,-1f,1f);
            double theta=Math.acos(dot);
            if(theta<0.05||Math.PI-theta<0.02){out.add(new PointF(cur.x,cur.y));continue;}
            float t=(float)(radius/Math.tan(theta/2.0));
            float maxT=Math.min(l1,l2)*0.45f;
            if(t>maxT)t=maxT;
            if(t<=1e-5f){out.add(new PointF(cur.x,cur.y));continue;}
            float rEff=(float)(t*Math.tan(theta/2.0));
            float sx=ax+bx,sy=ay+by,sl=(float)Math.hypot(sx,sy);
            if(sl<1e-5f){out.add(new PointF(cur.x,cur.y));continue;}
            sx/=sl;sy/=sl;
            float centerDist=(float)(rEff/Math.sin(theta/2.0));
            PointF center=new PointF(cur.x+sx*centerDist,cur.y+sy*centerDist);
            PointF p1=new PointF(cur.x+ax*t,cur.y+ay*t);
            PointF p2=new PointF(cur.x+bx*t,cur.y+by*t);
            double a1=Math.atan2(p1.y-center.y,p1.x-center.x);
            double a2=Math.atan2(p2.y-center.y,p2.x-center.x);
            double delta=a2-a1;
            if(sign>0){while(delta<0)delta+=Math.PI*2;while(delta>Math.PI*2)delta-=Math.PI*2;}
            else{while(delta>0)delta-=Math.PI*2;while(delta<-Math.PI*2)delta+=Math.PI*2;}
            int segments=Math.max(3,(int)Math.ceil(Math.abs(delta)/(Math.PI/18.0)));
            out.add(p1);
            for(int s=1;s<segments;s++){
                double a=a1+delta*s/segments;
                out.add(new PointF(center.x+(float)Math.cos(a)*rEff,center.y+(float)Math.sin(a)*rEff));
            }
            out.add(p2);
        }
        return validProfile(out,area)?out:null;
    }

    private static List<PointF> insetProfile(List<PointF> p,float d) {
        if(p==null||p.size()<3||d<=0)return null;
        int n=p.size();float area=signedArea(p);float sign=area>=0?1f:-1f;
        PointF[] a=new PointF[n],b=new PointF[n];
        for(int i=0;i<n;i++){
            PointF p0=p.get(i),p1=p.get((i+1)%n);float dx=p1.x-p0.x,dy=p1.y-p0.y,l=(float)Math.hypot(dx,dy);
            if(l<1e-5f)return null;
            float nx=-dy/l*sign,ny=dx/l*sign;
            a[i]=new PointF(p0.x+nx*d,p0.y+ny*d);
            b[i]=new PointF(p1.x+nx*d,p1.y+ny*d);
        }
        List<PointF> out=new ArrayList<>();
        for(int i=0;i<n;i++){
            int prev=(i-1+n)%n;PointF x=lineIntersection(a[prev],b[prev],a[i],b[i]);
            if(x==null){
                PointF cur=p.get(i);float n1x=a[prev].x-p.get(prev).x,n1y=a[prev].y-p.get(prev).y;
                float n2x=a[i].x-cur.x,n2y=a[i].y-cur.y;float sx=n1x+n2x,sy=n1y+n2y,l=(float)Math.hypot(sx,sy);
                if(l<1e-5f)return null;x=new PointF(cur.x+sx/l*d,cur.y+sy/l*d);
            }
            out.add(x);
        }
        return validProfile(out,area)?out:null;
    }

    private static PointF lineIntersection(PointF a,PointF b,PointF c,PointF d) {
        float x1=a.x,y1=a.y,x2=b.x,y2=b.y,x3=c.x,y3=c.y,x4=d.x,y4=d.y;
        float den=(x1-x2)*(y3-y4)-(y1-y2)*(x3-x4);
        if(Math.abs(den)<1e-6f)return null;
        float t=((x1-x3)*(y3-y4)-(y1-y3)*(x3-x4))/den;
        return new PointF(x1+t*(x2-x1),y1+t*(y2-y1));
    }

    private static boolean validProfile(List<PointF> p,float originalArea) {
        if(p==null||p.size()<3)return false;float a=signedArea(p);
        return Math.abs(a)>1e-4f && a*originalArea>0f && !selfIntersects(p);
    }

    private static boolean selfIntersects(List<PointF> p) {
        int n=p.size();
        for(int i=0;i<n;i++){
            PointF a=p.get(i),b=p.get((i+1)%n);
            for(int j=i+1;j<n;j++){
                if(j==i||j==(i+1)%n||(i==0&&j==n-1))continue;
                PointF c=p.get(j),d=p.get((j+1)%n);
                if(segmentsIntersect(a,b,c,d))return true;
            }
        }
        return false;
    }

    private static boolean segmentsIntersect(PointF a,PointF b,PointF c,PointF d) {
        float o1=orient(a,b,c),o2=orient(a,b,d),o3=orient(c,d,a),o4=orient(c,d,b);
        return o1*o2<-1e-6f && o3*o4<-1e-6f;
    }
    private static float orient(PointF a,PointF b,PointF c){return(b.x-a.x)*(c.y-a.y)-(b.y-a.y)*(c.x-a.x);}
    private static PointF moveToward(PointF from,PointF to,float d){float l=len(from,to);return l<1e-6f?new PointF(from.x,from.y):new PointF(from.x+(to.x-from.x)*d/l,from.y+(to.y-from.y)*d/l);}
    private static float len(PointF a,PointF b){return(float)Math.hypot(b.x-a.x,b.y-a.y);}
    private static float signedArea(List<PointF> p){float a=0;for(int i=0;i<p.size();i++){PointF q=p.get(i),r=p.get((i+1)%p.size());a+=q.x*r.y-r.x*q.y;}return a*0.5f;}
    private static float clamp(float v,float a,float b){return Math.max(a,Math.min(b,v));}

    // ------------------------------------------------------------------
    // Body/reflection helpers
    // ------------------------------------------------------------------

    private Object selectedBody(){try{return selectedBodyField==null?null:selectedBodyField.get(this);}catch(Exception e){return null;}}
    private SolidCSG bodyCsg(Object body){try{Field f=findField(body.getClass(),"csg");Object v=f==null?null:f.get(body);return v instanceof SolidCSG?(SolidCSG)v:null;}catch(Exception e){return null;}}
    private void setBodyCsg(Object body,SolidCSG csg){try{Field f=findField(body.getClass(),"csg");if(f!=null)f.set(body,csg);}catch(Exception ignored){}}
    private String bodyName(Object body){try{Field f=findField(body.getClass(),"name");Object v=f==null?null:f.get(body);return v==null?"Body":String.valueOf(v);}catch(Exception e){return"Body";}}
    private static Field findField(Class<?> c,String name){Class<?> x=c;while(x!=null){try{Field f=x.getDeclaredField(name);f.setAccessible(true);return f;}catch(Exception e){x=x.getSuperclass();}}return null;}

    private static String csgSignature(SolidCSG csg) {
        if(csg==null)return"null";StringBuilder b=new StringBuilder();b.append(csg.polygons().size()).append('|');
        int count=0;for(SolidCSG.Polygon p:csg.polygons()){Geometry3D.Vec3 c=p.centroid();b.append(p.vertices.size()).append(':').append(Math.round(c.x*10)).append(',').append(Math.round(c.y*10)).append(',').append(Math.round(c.z*10)).append(';');if(++count>=12)break;}return b.toString();
    }

    private void setOverview(boolean value) {
        try{Field f=field(SpatialCadCanvasView.class,"overview3D");f.setBoolean(this,value);}catch(Exception ignored){}
    }

    private static float parseLengthMm(String raw){
        String s=normalizeDigits(raw).trim().toLowerCase(Locale.US).replace(" ","");
        if(s.endsWith("mm"))return Float.parseFloat(s.substring(0,s.length()-2));
        if(s.endsWith("cm"))return Float.parseFloat(s.substring(0,s.length()-2))*10f;
        return Float.parseFloat(s);
    }
    private static String normalizeDigits(String s){if(s==null)return"";StringBuilder b=new StringBuilder();for(int i=0;i<s.length();i++){char c=s.charAt(i);if(c>='۰'&&c<='۹')b.append((char)('0'+c-'۰'));else if(c>='٠'&&c<='٩')b.append((char)('0'+c-'٠'));else b.append(c);}return b.toString();}
    private static String num(float v){String s=String.format(Locale.US,"%.2f",v);while(s.contains(".")&&(s.endsWith("0")||s.endsWith(".")))s=s.substring(0,s.length()-1);return s;}
    private static String dual(float mm){return num(mm)+" mm";}
    private void toast(String s){if(s!=null&&!s.trim().isEmpty())Toast.makeText(getContext(),s,Toast.LENGTH_SHORT).show();}
}

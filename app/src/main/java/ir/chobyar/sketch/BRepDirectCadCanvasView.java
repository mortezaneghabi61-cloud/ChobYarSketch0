package ir.chobyar.sketch;

import android.app.AlertDialog;
import android.content.Context;
import android.text.InputType;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;

/**
 * First explicit B-Rep/topology workspace for ChobYar.
 *
 * The current SolidCSG mesh remains the geometric backend, but this layer no
 * longer treats a body as an anonymous polygon soup. It reconstructs a true
 * boundary graph (Vertex -> Edge -> Face), validates manifold connectivity,
 * measures faces/edges/volume and exposes body transforms that work on any
 * current SolidCSG result (Extrude, Boolean, Revolve, Sweep or Loft).
 *
 * Planar faces and straight edges are exact. Exact analytic cylinders/arcs and
 * arbitrary-edge blends still require the future native exact-curve kernel.
 */
public class BRepDirectCadCanvasView extends DirectModelCadCanvasView {

    private final IdentityHashMap<Object, ArrayDeque<SolidCSG>> transformUndo = new IdentityHashMap<>();

    private Field selectedBodyField;
    private Field selectedFaceField;
    private Field selectedEdgeAField;
    private Field selectedEdgeBField;
    private Field selectedEdgeBodyField;

    public BRepDirectCadCanvasView(Context context) {
        super(context);
        try {
            selectedBodyField=field(SolidCadCanvasView.class,"selectedBody");
            selectedFaceField=field(SolidCadCanvasView.class,"selectedFace");
            selectedEdgeAField=field(DirectModelCadCanvasView.class,"selectedEdgeA");
            selectedEdgeBField=field(DirectModelCadCanvasView.class,"selectedEdgeB");
            selectedEdgeBodyField=field(DirectModelCadCanvasView.class,"selectedEdgeBody");
        } catch (Exception ignored) {}
    }

    private static Field field(Class<?> owner,String name)throws NoSuchFieldException {
        Field f=owner.getDeclaredField(name);f.setAccessible(true);return f;
    }

    /** Adaptive entry: everyday direct tools remain one tap away, B-Rep tools sit beside them. */
    @Override
    public void showDirectManager() {
        Object body=selectedBody();
        String bodyLabel=body==null?"Body انتخاب نشده":bodyName(body);
        String[] items={
                "✥ ابزار مستقیم Edge / Face (Fillet, Chamfer, Push/Pull, Shell)",
                "⌘ B-Rep Inspector / ساختار Body",
                "▱ اندازه Face انتخاب‌شده",
                "— اندازه Edge انتخاب‌شده",
                "↔ Move Body در X / Y / Z",
                "⟳ Rotate Body حول X / Y / Z",
                "↶ Undo آخرین جابه‌جایی/چرخش Body",
                "✓ بررسی سلامت Topology"
        };
        new AlertDialog.Builder(getContext())
                .setTitle("Edit 3D • B-Rep")
                .setMessage(bodyLabel+"\n\nکاربر عادی: گزینه اول.\nکار حرفه‌ای: Topology، اندازه‌های دقیق و Transform سه‌بعدی همین‌جا در دسترس است.")
                .setItems(items,(d,w)->{
                    if(w==0)super.showDirectManager();
                    else if(w==1)showTopologyInspector();
                    else if(w==2)showSelectedFaceMeasure();
                    else if(w==3)showSelectedEdgeMeasure();
                    else if(w==4)showMoveBodyDialog();
                    else if(w==5)showRotateBodyAxisDialog();
                    else if(w==6)toast(undoBodyTransform());
                    else showTopologyHealth();
                })
                .setNegativeButton("بستن",null).show();
    }

    public void showTopologyInspector() {
        Object body=selectedBody();
        if(body==null){ensure3D();toast("اول روی یک Body بزن");return;}
        BRepTopology topo=topology(body);
        BRepTopology.TopoFace face=topo.findFace(selectedFace());
        String message=topo.summary();
        if(face!=null)message+="\n\nFace انتخاب‌شده:\n"+BRepTopology.faceInfo(face);
        new AlertDialog.Builder(getContext())
                .setTitle("B-Rep Inspector • "+bodyName(body))
                .setMessage(message)
                .setPositiveButton("باشه",null).show();
    }

    private void showTopologyHealth() {
        Object body=selectedBody();
        if(body==null){toast("اول یک Body را انتخاب کن");return;}
        BRepTopology t=topology(body);
        String message=t.isClosedManifold()
                ?"✓ Body یک پوسته بسته و Manifold دارد. هر Edge دقیقاً بین دو Face مشترک است."
                :"⚠ Topology نیاز به بررسی دارد.\nBoundary Edge: "+t.boundaryEdgeCount+"\nNon-manifold Edge: "+t.nonManifoldEdgeCount;
        message+="\n\nVertex: "+t.vertices.size()+" • Edge: "+t.edges.size()+" • Face: "+t.faces.size();
        new AlertDialog.Builder(getContext()).setTitle("Topology Health").setMessage(message).setPositiveButton("باشه",null).show();
    }

    private void showSelectedFaceMeasure() {
        Object body=selectedBody();
        SolidCSG.Polygon selected=selectedFace();
        if(body==null||selected==null){ensure3D();toast("اول روی Face موردنظر بزن");return;}
        BRepTopology.TopoFace f=topology(body).findFace(selected);
        if(f==null){toast("Face در Topology پیدا نشد");return;}
        new AlertDialog.Builder(getContext())
                .setTitle("Face • "+f.id)
                .setMessage(BRepTopology.faceInfo(f))
                .setPositiveButton("باشه",null).show();
    }

    private void showSelectedEdgeMeasure() {
        Object body=selectedBody();
        Geometry3D.Vec3 a=selectedEdgeA(),b=selectedEdgeB();
        if(body==null||a==null||b==null||selectedEdgeBody()!=body){
            ensure3D();toast("اول از ابزار مستقیم، Edge را انتخاب کن");return;
        }
        float mm=b.sub(a).length();
        Geometry3D.Vec3 d=b.sub(a).normalized();
        String msg="Length: "+dual(mm)+"\nDirection: ("+num(d.x)+", "+num(d.y)+", "+num(d.z)+")";
        new AlertDialog.Builder(getContext()).setTitle("Edge Measurement").setMessage(msg).setPositiveButton("باشه",null).show();
    }

    // ------------------------------------------------------------------
    // Generic body transforms - valid for every current polygonal SolidCSG body
    // ------------------------------------------------------------------

    private void showMoveBodyDialog() {
        Object body=selectedBody();
        if(body==null){ensure3D();toast("اول یک Body را انتخاب کن");return;}
        LinearLayout box=new LinearLayout(getContext());box.setOrientation(LinearLayout.VERTICAL);box.setPadding(dp(20),dp(8),dp(20),0);
        EditText x=axisInput(box,"X", "0mm");
        EditText y=axisInput(box,"Y", "0mm");
        EditText z=axisInput(box,"Z", "0mm");
        new AlertDialog.Builder(getContext())
                .setTitle("Move Body • mm")
                .setMessage("مقدار مثبت/منفی را برای هر محور به میلی‌متر وارد کن؛ مثال: -15")
                .setView(box)
                .setPositiveButton("حرکت",(d,w)->{
                    try{
                        float dx=parseLengthMm(x.getText().toString());
                        float dy=parseLengthMm(y.getText().toString());
                        float dz=parseLengthMm(z.getText().toString());
                        toast(moveBody(body,dx,dy,dz));
                    }catch(Exception e){toast("مقدار X/Y/Z درست وارد نشده");}
                }).setNegativeButton("لغو",null).show();
    }

    private EditText axisInput(LinearLayout parent,String axis,String initial) {
        TextView label=new TextView(getContext());label.setText(axis+"  (mm)");label.setTextSize(13f);parent.addView(label);
        EditText input=new EditText(getContext());input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setText(initial);input.setSelectAllOnFocus(true);parent.addView(input);
        return input;
    }

    private String moveBody(Object body,float dx,float dy,float dz) {
        if(Math.abs(dx)+Math.abs(dy)+Math.abs(dz)<1e-6f)return"حرکت صفر است";
        SolidCSG c=bodyCsg(body);if(c==null||c.isEmpty())return"Body معتبر نیست";
        pushTransformUndo(body,c);
        setBodyCsg(body,transform(c,p->new Geometry3D.Vec3(p.x+dx,p.y+dy,p.z+dz)));
        clearSubSelection();ensure3D();invalidate();
        return "Move Body • X "+dual(dx)+" • Y "+dual(dy)+" • Z "+dual(dz);
    }

    private void showRotateBodyAxisDialog() {
        if(selectedBody()==null){ensure3D();toast("اول یک Body را انتخاب کن");return;}
        String[] axes={"X","Y","Z"};
        new AlertDialog.Builder(getContext()).setTitle("Rotate Body • محور")
                .setItems(axes,(d,w)->showRotateAngle(w)).setNegativeButton("لغو",null).show();
    }

    private void showRotateAngle(int axisIndex) {
        Object body=selectedBody();if(body==null)return;
        EditText input=new EditText(getContext());input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL|InputType.TYPE_NUMBER_FLAG_SIGNED);
        input.setText("90");input.setSelectAllOnFocus(true);
        String axis=axisIndex==0?"X":axisIndex==1?"Y":"Z";
        new AlertDialog.Builder(getContext()).setTitle("Rotate حول "+axis)
                .setMessage("زاویه بر حسب درجه؛ مرکز دوران = مرکز Body")
                .setView(input)
                .setPositiveButton("چرخش",(d,w)->{
                    try{float deg=Float.parseFloat(normalizeDigits(input.getText().toString()));toast(rotateBody(body,axisIndex,deg));}
                    catch(Exception e){toast("زاویه درست وارد نشده");}
                }).setNegativeButton("لغو",null).show();
    }

    private String rotateBody(Object body,int axisIndex,float deg) {
        if(Math.abs(deg)<1e-5f)return"زاویه صفر است";
        SolidCSG c=bodyCsg(body);if(c==null||c.isEmpty())return"Body معتبر نیست";
        Geometry3D.Vec3 center=bodyCenter(c);
        Geometry3D.Vec3 axis=axisIndex==0?new Geometry3D.Vec3(1,0,0):axisIndex==1?new Geometry3D.Vec3(0,1,0):new Geometry3D.Vec3(0,0,1);
        double rad=Math.toRadians(deg);
        pushTransformUndo(body,c);
        setBodyCsg(body,transform(c,p->rotateAroundAxis(p,center,axis,rad)));
        clearSubSelection();ensure3D();invalidate();
        return "Rotate Body • "+(axisIndex==0?"X":axisIndex==1?"Y":"Z")+" = "+num(deg)+"°";
    }

    private String undoBodyTransform() {
        Object body=selectedBody();if(body==null)return"اول یک Body را انتخاب کن";
        ArrayDeque<SolidCSG> stack=transformUndo.get(body);
        if(stack==null||stack.isEmpty())return"Undo Transform خالی است";
        setBodyCsg(body,stack.removeLast());clearSubSelection();invalidate();
        return"آخرین Move/Rotate برگشت";
    }

    private void pushTransformUndo(Object body,SolidCSG csg) {
        ArrayDeque<SolidCSG> stack=transformUndo.get(body);
        if(stack==null){stack=new ArrayDeque<>();transformUndo.put(body,stack);}
        if(stack.size()>=20)stack.removeFirst();
        stack.addLast(csg.copy());
    }

    private interface PointTransform { Geometry3D.Vec3 map(Geometry3D.Vec3 p); }

    private static SolidCSG transform(SolidCSG source,PointTransform op) {
        List<SolidCSG.Polygon> out=new ArrayList<>();
        for(SolidCSG.Polygon polygon:source.polygons()) {
            List<SolidCSG.Vertex> verts=new ArrayList<>();
            for(SolidCSG.Vertex v:polygon.vertices)verts.add(new SolidCSG.Vertex(op.map(v.pos)));
            if(verts.size()>=3)out.add(new SolidCSG.Polygon(verts));
        }
        return SolidCSG.fromPolygons(out);
    }

    private static Geometry3D.Vec3 rotateAroundAxis(Geometry3D.Vec3 p,Geometry3D.Vec3 origin,Geometry3D.Vec3 axis,double angle) {
        Geometry3D.Vec3 r=p.sub(origin);float c=(float)Math.cos(angle),s=(float)Math.sin(angle);
        return origin.add(r.mul(c).add(axis.cross(r).mul(s)).add(axis.mul(axis.dot(r)*(1f-c))));
    }

    private static Geometry3D.Vec3 bodyCenter(SolidCSG csg) {
        double x=0,y=0,z=0;int n=0;
        for(SolidCSG.Polygon p:csg.polygons())for(SolidCSG.Vertex v:p.vertices){x+=v.pos.x;y+=v.pos.y;z+=v.pos.z;n++;}
        if(n==0)return new Geometry3D.Vec3(0,0,0);
        return new Geometry3D.Vec3((float)(x/n),(float)(y/n),(float)(z/n));
    }

    // ------------------------------------------------------------------
    // Reflection / helpers
    // ------------------------------------------------------------------

    private BRepTopology topology(Object body){return BRepTopology.build(bodyCsg(body));}
    private Object selectedBody(){try{return selectedBodyField==null?null:selectedBodyField.get(this);}catch(Exception e){return null;}}
    private SolidCSG.Polygon selectedFace(){try{Object v=selectedFaceField==null?null:selectedFaceField.get(this);return v instanceof SolidCSG.Polygon?(SolidCSG.Polygon)v:null;}catch(Exception e){return null;}}
    private Geometry3D.Vec3 selectedEdgeA(){try{Object v=selectedEdgeAField==null?null:selectedEdgeAField.get(this);return v instanceof Geometry3D.Vec3?(Geometry3D.Vec3)v:null;}catch(Exception e){return null;}}
    private Geometry3D.Vec3 selectedEdgeB(){try{Object v=selectedEdgeBField==null?null:selectedEdgeBField.get(this);return v instanceof Geometry3D.Vec3?(Geometry3D.Vec3)v:null;}catch(Exception e){return null;}}
    private Object selectedEdgeBody(){try{return selectedEdgeBodyField==null?null:selectedEdgeBodyField.get(this);}catch(Exception e){return null;}}

    private SolidCSG bodyCsg(Object body){try{Field f=findField(body.getClass(),"csg");Object v=f==null?null:f.get(body);return v instanceof SolidCSG?(SolidCSG)v:null;}catch(Exception e){return null;}}
    private void setBodyCsg(Object body,SolidCSG csg){try{Field f=findField(body.getClass(),"csg");if(f!=null)f.set(body,csg);}catch(Exception ignored){}}
    private String bodyName(Object body){try{Field f=findField(body.getClass(),"name");Object v=f==null?null:f.get(body);return v==null?"Body":String.valueOf(v);}catch(Exception e){return"Body";}}
    private static Field findField(Class<?> c,String name){Class<?> x=c;while(x!=null){try{Field f=x.getDeclaredField(name);f.setAccessible(true);return f;}catch(Exception e){x=x.getSuperclass();}}return null;}

    private void clearSubSelection() {
        try{if(selectedFaceField!=null)selectedFaceField.set(this,null);}catch(Exception ignored){}
        try{if(selectedEdgeAField!=null)selectedEdgeAField.set(this,null);if(selectedEdgeBField!=null)selectedEdgeBField.set(this,null);if(selectedEdgeBodyField!=null)selectedEdgeBodyField.set(this,null);}catch(Exception ignored){}
    }

    private void ensure3D(){if(!is3DOverview())toggle3DOverview();}
    private int dp(int v){return Math.round(v*getResources().getDisplayMetrics().density);}

    private static float parseLengthMm(String raw) {
        String s=normalizeDigits(raw).trim().toLowerCase(Locale.US).replace(" ","");
        if(s.isEmpty())return 0f;
        if(s.endsWith("mm"))return Float.parseFloat(s.substring(0,s.length()-2));
        if(s.endsWith("cm"))return Float.parseFloat(s.substring(0,s.length()-2))*10f;
        return Float.parseFloat(s);
    }
    private static String normalizeDigits(String s){if(s==null)return"";StringBuilder b=new StringBuilder();for(int i=0;i<s.length();i++){char c=s.charAt(i);if(c>='۰'&&c<='۹')b.append((char)('0'+c-'۰'));else if(c>='٠'&&c<='٩')b.append((char)('0'+c-'٠'));else b.append(c);}return b.toString();}
    private static String num(float v){String s=String.format(Locale.US,"%.3f",v);while(s.contains(".")&&(s.endsWith("0")||s.endsWith(".")))s=s.substring(0,s.length()-1);return s;}
    private static String dual(float mm){return num(mm)+" mm";}
    private void toast(String s){if(s!=null&&!s.trim().isEmpty())Toast.makeText(getContext(),s,Toast.LENGTH_SHORT).show();}
}

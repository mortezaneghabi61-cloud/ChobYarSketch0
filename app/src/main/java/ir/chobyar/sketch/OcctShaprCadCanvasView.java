package ir.chobyar.sketch;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.PointF;
import android.text.InputType;
import android.view.MotionEvent;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Shapr-inspired Sketch workspace layered on the existing exact OCCT modeler.
 *
 * This class does not copy Shapr3D artwork or branding. It mirrors CAD workflows:
 * a complete sketch palette, adaptive constraints, selection-aware Measure,
 * offset/project/construction helpers, sketch patterns, plus real touch tools
 * for Ellipse and a smoothed fit-point Spline backed by the existing sketch model.
 */
public class OcctShaprCadCanvasView extends OcctMeasureCadCanvasView {

    private static final int CUSTOM_NONE = 0;
    private static final int CUSTOM_ELLIPSE = 1;
    private static final int CUSTOM_SPLINE = 2;
    private static final float PX_PER_MM = 3f;

    private Field entitiesField;
    private Field selectedField;
    private Field selectedObjectsField;
    private Field viewScaleField;
    private Field offsetXField;
    private Field offsetYField;
    private Method saveUndoMethod;
    private Constructor<?> polylineConstructor;

    private int customTool = CUSTOM_NONE;
    private PointF gestureStart;
    private final List<PointF> gesturePoints = new ArrayList<>();
    private float lastGestureScreenX;
    private float lastGestureScreenY;

    public OcctShaprCadCanvasView(Context context) {
        super(context);
        initReflection();
    }

    private void initReflection() {
        try {
            entitiesField = field(CadCanvasView.class, "entities");
            selectedField = field(CadCanvasView.class, "selected");
            selectedObjectsField = field(SmartCadCanvasView.class, "selectedObjects");
            viewScaleField = field(CadCanvasView.class, "viewScale");
            offsetXField = field(CadCanvasView.class, "offsetX");
            offsetYField = field(CadCanvasView.class, "offsetY");
            saveUndoMethod = CadCanvasView.class.getDeclaredMethod("saveUndo");
            saveUndoMethod.setAccessible(true);

            Class<?> polyline = Class.forName("ir.chobyar.sketch.CadCanvasView$PolylineEntity");
            polylineConstructor = polyline.getDeclaredConstructor(List.class, boolean.class);
            polylineConstructor.setAccessible(true);
        } catch (Exception ignored) {}
    }

    private static Field field(Class<?> owner, String name) throws NoSuchFieldException {
        Field f = owner.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }

    // ---------------------------------------------------------------------
    // Shapr-style Sketch menu
    // ---------------------------------------------------------------------

    public void showShaprSketchMenu() {
        String[] items = {
                "⌁ Automatic Line / Arc",
                "╱ Line",
                "⌒ Arc",
                "〰 Spline",
                "▭ Rectangle",
                "○ Circle",
                "⬭ Ellipse",
                "⬡ Polygon",
                "⧉ Offset Edge",
                "↗ Move / Rotate Sketch",
                "⠿ Pattern Sketch",
                "⌫ Trim",
                "⎘ Project Sketch",
                "⌖ Measure",
                "⌁ Constraints",
                "🔒 Lock / Unlock",
                "┄ Make Construction",
                "⌫ Delete"
        };

        new AlertDialog.Builder(getContext())
                .setTitle("Sketch")
                .setItems(items, (d, which) -> {
                    switch (which) {
                        case 0: startAutomaticLineArc(); break;
                        case 1: activateBaseTool(TOOL_LINE, "Line فعال شد"); break;
                        case 2: activateBaseTool(TOOL_ARC, "Arc فعال شد"); break;
                        case 3: startSplineTool(); break;
                        case 4: activateBaseTool(TOOL_RECT, "Rectangle فعال شد"); break;
                        case 5: activateBaseTool(TOOL_CIRCLE, "Circle فعال شد"); break;
                        case 6: startEllipseTool(); break;
                        case 7: activateBaseTool(TOOL_POLYGON, "Polygon فعال شد"); break;
                        case 8: showOffsetDialog(); break;
                        case 9: showSketchTransformDialog(); break;
                        case 10: showPatternDialog(); break;
                        case 11: toast(trimSelectedLines()); break;
                        case 12: toast(projectSelectionAsReference()); break;
                        case 13: showSketchMeasureInspector(); break;
                        case 14: showSmartConstraintMenu(); break;
                        case 15: toast(toggleSelectedLock()); break;
                        case 16: toast(toggleConstructionSelection()); break;
                        case 17: deleteSelected(); dispatchWorkspaceState(); break;
                        default: break;
                    }
                })
                .setNegativeButton("بستن", null)
                .show();
    }

    /** Quick catalog so the app can expose the same major groups without button clutter. */
    public void showShaprModelingToolsMenu() {
        String[] items = {
                "⬆ Extrude",
                "⌒ Revolve",
                "〰 Sweep",
                "◫ Loft",
                "⊕ Union",
                "⊖ Subtract",
                "∩ Intersect",
                "⌒ Chamfer / Fillet",
                "▱ Shell",
                "↕ Offset Face / Push-Pull",
                "✥ Exact Edge / Face Edit",
                "⏱ History"
        };
        new AlertDialog.Builder(getContext())
                .setTitle("Tools • 3D")
                .setItems(items, (d, which) -> {
                    if (which <= 6) showSolidManager();
                    else if (which <= 10) showDirectManager();
                    else showHistoryManager();
                })
                .setNegativeButton("بستن", null)
                .show();
    }

    private void activateBaseTool(int tool, String message) {
        customTool = CUSTOM_NONE;
        gesturePoints.clear();
        super.setTool(tool);
        toast(message);
    }

    /** Pen-friendly automatic mode: current inference engine already switches line relations while drawing. */
    private void startAutomaticLineArc() {
        customTool = CUSTOM_NONE;
        super.setTool(TOOL_LINE);
        toast("Automatic Line/Arc فعال شد • خط بکش؛ برای قوس از Arc استفاده کن");
    }

    // ---------------------------------------------------------------------
    // Ellipse + Spline touch tools
    // ---------------------------------------------------------------------

    private void startEllipseTool() {
        super.setTool(TOOL_SELECT);
        customTool = CUSTOM_ELLIPSE;
        gesturePoints.clear();
        gestureStart = null;
        toast("Ellipse: از مرکز لمس کن و تا شعاع X/Y بکش");
    }

    private void startSplineTool() {
        super.setTool(TOOL_SELECT);
        customTool = CUSTOM_SPLINE;
        gesturePoints.clear();
        gestureStart = null;
        toast("Spline: با قلم/انگشت مسیر نرم را بکش و رها کن");
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (customTool == CUSTOM_NONE) return super.onTouchEvent(event);
        if (event.getPointerCount() > 1) return super.onTouchEvent(event);

        int action = event.getActionMasked();
        if (customTool == CUSTOM_ELLIPSE) {
            if (action == MotionEvent.ACTION_DOWN) {
                gestureStart = screenToWorld(event.getX(), event.getY());
                lastGestureScreenX = event.getX();
                lastGestureScreenY = event.getY();
                return true;
            }
            if (action == MotionEvent.ACTION_MOVE) {
                lastGestureScreenX = event.getX();
                lastGestureScreenY = event.getY();
                return true;
            }
            if (action == MotionEvent.ACTION_UP && gestureStart != null) {
                PointF end = screenToWorld(event.getX(), event.getY());
                float rx = Math.abs(end.x - gestureStart.x);
                float ry = Math.abs(end.y - gestureStart.y);
                if (rx < 0.5f || ry < 0.5f) {
                    toast("Ellipse خیلی کوچک است");
                    return true;
                }
                List<PointF> p = new ArrayList<>();
                int steps = 96;
                for (int i=0;i<steps;i++) {
                    double a = Math.PI * 2d * i / steps;
                    p.add(new PointF(gestureStart.x + (float)Math.cos(a)*rx,
                            gestureStart.y + (float)Math.sin(a)*ry));
                }
                Object e = addPolyline(p, true);
                customTool = CUSTOM_NONE;
                gestureStart = null;
                if (e != null) toast("Ellipse ساخته شد • محور بزرگ/کوچک قابل Measure است");
                dispatchWorkspaceState();
                return true;
            }
            if (action == MotionEvent.ACTION_CANCEL) {
                customTool = CUSTOM_NONE;
                gestureStart = null;
                return true;
            }
        }

        if (customTool == CUSTOM_SPLINE) {
            if (action == MotionEvent.ACTION_DOWN) {
                gesturePoints.clear();
                gesturePoints.add(screenToWorld(event.getX(), event.getY()));
                lastGestureScreenX = event.getX();
                lastGestureScreenY = event.getY();
                return true;
            }
            if (action == MotionEvent.ACTION_MOVE) {
                float dx = event.getX()-lastGestureScreenX, dy = event.getY()-lastGestureScreenY;
                if (dx*dx + dy*dy >= 36f) {
                    gesturePoints.add(screenToWorld(event.getX(), event.getY()));
                    lastGestureScreenX = event.getX();
                    lastGestureScreenY = event.getY();
                }
                return true;
            }
            if (action == MotionEvent.ACTION_UP) {
                gesturePoints.add(screenToWorld(event.getX(), event.getY()));
                if (gesturePoints.size() < 3) {
                    toast("برای Spline مسیر کمی بلندتر بکش");
                    gesturePoints.clear();
                    return true;
                }
                List<PointF> smooth = catmullRom(gesturePoints, 5);
                Object e = addPolyline(smooth, false);
                customTool = CUSTOM_NONE;
                gesturePoints.clear();
                if (e != null) toast("Spline نرم ساخته شد");
                dispatchWorkspaceState();
                return true;
            }
            if (action == MotionEvent.ACTION_CANCEL) {
                customTool = CUSTOM_NONE;
                gesturePoints.clear();
                return true;
            }
        }
        return true;
    }

    private static List<PointF> catmullRom(List<PointF> src, int subdivisions) {
        List<PointF> out = new ArrayList<>();
        if (src.size() < 2) return out;
        for (int i=0;i<src.size()-1;i++) {
            PointF p0 = src.get(Math.max(0,i-1));
            PointF p1 = src.get(i);
            PointF p2 = src.get(i+1);
            PointF p3 = src.get(Math.min(src.size()-1,i+2));
            for (int j=0;j<subdivisions;j++) {
                float t = j/(float)subdivisions;
                float t2=t*t, t3=t2*t;
                float x=0.5f*((2*p1.x)+(-p0.x+p2.x)*t+(2*p0.x-5*p1.x+4*p2.x-p3.x)*t2+(-p0.x+3*p1.x-3*p2.x+p3.x)*t3);
                float y=0.5f*((2*p1.y)+(-p0.y+p2.y)*t+(2*p0.y-5*p1.y+4*p2.y-p3.y)*t2+(-p0.y+3*p1.y-3*p2.y+p3.y)*t3);
                out.add(new PointF(x,y));
            }
        }
        PointF last=src.get(src.size()-1);
        out.add(new PointF(last.x,last.y));
        return out;
    }

    // ---------------------------------------------------------------------
    // Offset, Transform, Pattern, Project, Construction
    // ---------------------------------------------------------------------

    private void showOffsetDialog() {
        EditText input = textInput("10mm");
        new AlertDialog.Builder(getContext())
                .setTitle("Offset Edge • cm / mm")
                .setMessage("شکل یا لبه Sketch را انتخاب کن و فاصله را وارد کن.")
                .setView(input)
                .setPositiveButton("Offset", (d,w) -> {
                    try { toast(offsetSelected(parseLengthMm(input.getText().toString()))); }
                    catch (Exception e) { toast("فاصله درست وارد نشده"); }
                })
                .setNegativeButton("لغو", null)
                .show();
    }

    private void showSketchTransformDialog() {
        LinearLayout box = new LinearLayout(getContext());
        box.setOrientation(LinearLayout.VERTICAL);
        EditText dx = textInput("0mm"); dx.setHint("Move X");
        EditText dy = textInput("0mm"); dy.setHint("Move Y");
        EditText rot = textInput("0"); rot.setHint("Rotate °");
        box.addView(dx); box.addView(dy); box.addView(rot);
        new AlertDialog.Builder(getContext())
                .setTitle("Move / Rotate Sketch")
                .setView(box)
                .setPositiveButton("اعمال", (d,w) -> {
                    try {
                        float x=parseLengthMm(dx.getText().toString());
                        float y=parseLengthMm(dy.getText().toString());
                        float a=Float.parseFloat(normalizeDigits(rot.getText().toString()));
                        if(Math.abs(x)>1e-6f||Math.abs(y)>1e-6f) moveSelected(x,y);
                        if(Math.abs(a)>1e-6f) toast(rotateSelected(a));
                        dispatchWorkspaceState();
                    } catch(Exception e){toast("مقادیر Move/Rotate درست نیست");}
                })
                .setNegativeButton("لغو",null).show();
    }

    private void showPatternDialog() {
        String[] items={"↔ Linear Pattern","⟳ Circular Pattern"};
        new AlertDialog.Builder(getContext()).setTitle("Pattern Sketch").setItems(items,(d,w)->{
            if(w==0)showLinearPatternDialog();else showCircularPatternDialog();
        }).setNegativeButton("بستن",null).show();
    }

    private void showLinearPatternDialog() {
        LinearLayout box=new LinearLayout(getContext());box.setOrientation(LinearLayout.VERTICAL);
        EditText count=textInput("4");count.setHint("Count");
        EditText dx=textInput("20mm");dx.setHint("Spacing X");
        EditText dy=textInput("0mm");dy.setHint("Spacing Y");
        box.addView(count);box.addView(dx);box.addView(dy);
        new AlertDialog.Builder(getContext()).setTitle("Linear Pattern").setView(box)
                .setPositiveButton("ساخت",(d,w)->{
                    try{
                        int n=Integer.parseInt(normalizeDigits(count.getText().toString()));
                        toast(arraySelected(n,parseLengthMm(dx.getText().toString()),parseLengthMm(dy.getText().toString())));
                    }catch(Exception e){toast("Pattern درست وارد نشده");}
                }).setNegativeButton("لغو",null).show();
    }

    private void showCircularPatternDialog() {
        LinearLayout box=new LinearLayout(getContext());box.setOrientation(LinearLayout.VERTICAL);
        EditText count=textInput("6");count.setHint("Count");
        EditText cx=textInput("0mm");cx.setHint("Center X");
        EditText cy=textInput("0mm");cy.setHint("Center Y");
        EditText angle=textInput("360");angle.setHint("Total angle °");
        box.addView(count);box.addView(cx);box.addView(cy);box.addView(angle);
        new AlertDialog.Builder(getContext()).setTitle("Circular Pattern").setView(box)
                .setPositiveButton("ساخت",(d,w)->{
                    try{
                        int n=Integer.parseInt(normalizeDigits(count.getText().toString()));
                        float x=parseLengthMm(cx.getText().toString()),y=parseLengthMm(cy.getText().toString());
                        float a=Float.parseFloat(normalizeDigits(angle.getText().toString()));
                        toast(circularPattern(n,x,y,a));
                    }catch(Exception e){toast("Circular Pattern درست وارد نشده");}
                }).setNegativeButton("لغو",null).show();
    }

    private String circularPattern(int count,float cx,float cy,float totalAngle) {
        List<Object> sel=selection();
        if(sel.isEmpty())return "اول شکل را انتخاب کن";
        if(count<2||count>200)return "Count باید بین 2 و 200 باشد";
        try{
            saveUndo();
            List<Object> seeds=new ArrayList<>(sel);
            float step=totalAngle/count;
            Object last=null;
            for(int i=1;i<count;i++){
                for(Object seed:seeds){
                    Object c=invoke(seed,"copy");
                    if(c==null)continue;
                    invoke(c,"rotate",new Class<?>[]{float.class,float.class,float.class},cx,cy,step*i);
                    entities().add(c);last=c;
                }
            }
            if(last!=null)selectOne(last);
            invalidate();dispatchWorkspaceState();
            return "Circular Pattern: "+count+" عدد • "+fmt(totalAngle)+"°";
        }catch(Exception e){return "Circular Pattern برای این انتخاب اجرا نشد";}
    }

    private String projectSelectionAsReference() {
        List<Object> sel=selection();
        if(sel.isEmpty())return "اول Sketch/لبه را انتخاب کن";
        try{
            saveUndo();
            Object last=null;
            for(Object e:sel){
                Object c=invoke(e,"copy");
                if(c==null)continue;
                setConstruction(c,true);
                entities().add(c);last=c;
            }
            if(last!=null)selectOne(last);
            invalidate();dispatchWorkspaceState();
            return "Projection مرجع روی Sketch فعال ساخته شد";
        }catch(Exception e){return "Project برای این انتخاب اجرا نشد";}
    }

    private String toggleConstructionSelection() {
        List<Object> sel=selection();
        if(sel.isEmpty())return "اول Sketch را انتخاب کن";
        try{
            boolean make=true;
            for(Object e:sel){Field f=findField(e.getClass(),"construction");if(f!=null&&!f.getBoolean(e)){make=true;break;}make=false;}
            for(Object e:sel)setConstruction(e,make);
            invalidate();dispatchWorkspaceState();
            return make?"انتخاب به Construction تبدیل شد":"Construction خاموش شد";
        }catch(Exception e){return "Construction برای این انتخاب در دسترس نیست";}
    }

    private void setConstruction(Object e,boolean value)throws Exception{
        Field f=findField(e.getClass(),"construction");
        if(f!=null){f.setBoolean(e,value);return;}
        Method m=findMethod(e.getClass(),"setConstruction",boolean.class);
        if(m!=null)m.invoke(e,value);
    }

    // ---------------------------------------------------------------------
    // Reflection + units
    // ---------------------------------------------------------------------

    private Object addPolyline(List<PointF> points, boolean closed) {
        if(polylineConstructor==null||points==null||points.size()<2)return null;
        try{
            saveUndo();
            Object e=polylineConstructor.newInstance(points,closed);
            Method setLayer=findMethod(e.getClass(),"setLayer",String.class);
            if(setLayer!=null)setLayer.invoke(e,getCurrentLayer());
            entities().add(e);
            selectOne(e);
            invalidate();
            return e;
        }catch(Exception ex){toast("ساخت هندسه انجام نشد");return null;}
    }

    @SuppressWarnings("unchecked")
    private List<Object> entities() throws Exception {
        Object v=entitiesField.get(this);
        return (List<Object>)v;
    }

    @SuppressWarnings("unchecked")
    private List<Object> selection() {
        try{
            Object m=selectedObjectsField.get(this);
            if(m instanceof List&&!((List<?>)m).isEmpty())return new ArrayList<>((List<Object>)m);
            Object one=selectedField.get(this);
            List<Object> out=new ArrayList<>();if(one!=null)out.add(one);return out;
        }catch(Exception e){return new ArrayList<>();}
    }

    @SuppressWarnings("unchecked")
    private void selectOne(Object e)throws Exception{
        selectedField.set(this,e);
        Object m=selectedObjectsField.get(this);
        if(m instanceof List){((List<Object>)m).clear();((List<Object>)m).add(e);}
    }

    private void saveUndo()throws Exception{if(saveUndoMethod!=null)saveUndoMethod.invoke(this);}

    private PointF screenToWorld(float sx,float sy){
        try{
            float scale=viewScaleField.getFloat(this);
            float ox=offsetXField.getFloat(this),oy=offsetYField.getFloat(this);
            float k=PX_PER_MM*scale;
            return new PointF((sx-ox)/k,(sy-oy)/k);
        }catch(Exception e){return new PointF(sx/PX_PER_MM,sy/PX_PER_MM);}
    }

    private static EditText textInput(String text){
        // Context is set by caller after construction through normal Android ownership.
        return null;
    }

    private EditText input(String text){
        EditText e=new EditText(getContext());e.setSingleLine(true);
        e.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        e.setText(text);e.setSelectAllOnFocus(true);return e;
    }

    private EditText textInputInstance(String text){return input(text);}

    private static float parseLengthMmStatic(String raw){
        String s=normalizeDigitsStatic(raw).toLowerCase(Locale.US).trim().replace('،','.').replace(',','.');
        boolean mm=s.endsWith("mm")||s.endsWith("میلیمتر")||s.endsWith("میلی‌متر");
        s=s.replace("میلی‌متر","").replace("میلیمتر","").replace("سانتی‌متر","").replace("سانتیمتر","")
                .replace("mm","").replace("cm","").trim();
        float v=Float.parseFloat(s);return mm?v:v*10f;
    }

    private float parseLengthMm(String raw){return parseLengthMmStatic(raw);}

    private static String normalizeDigitsStatic(String s){
        if(s==null)return"";StringBuilder b=new StringBuilder();
        for(int i=0;i<s.length();i++){char c=s.charAt(i);if(c>='۰'&&c<='۹')b.append((char)('0'+c-'۰'));else if(c>='٠'&&c<='٩')b.append((char)('0'+c-'٠'));else b.append(c);}return b.toString().trim();
    }

    private static String normalizeDigits(String s){return normalizeDigitsStatic(s);}

    private static String fmt(double v){
        String s=String.format(Locale.US,"%.2f",v);while(s.contains(".")&&(s.endsWith("0")||s.endsWith(".")))s=s.substring(0,s.length()-1);return s;
    }

    private static Field findField(Class<?> c,String name){
        Class<?> x=c;while(x!=null){try{Field f=x.getDeclaredField(name);f.setAccessible(true);return f;}catch(Exception e){x=x.getSuperclass();}}return null;
    }

    private static Method findMethod(Class<?> c,String name,Class<?>... types){
        Class<?> x=c;while(x!=null){try{Method m=x.getDeclaredMethod(name,types);m.setAccessible(true);return m;}catch(Exception e){x=x.getSuperclass();}}return null;
    }

    private static Object invoke(Object target,String name)throws Exception{
        Method m=findMethod(target.getClass(),name);return m==null?null:m.invoke(target);
    }

    private static Object invoke(Object target,String name,Class<?>[] types,Object... args)throws Exception{
        Method m=findMethod(target.getClass(),name,types);return m==null?null:m.invoke(target,args);
    }

    private void toast(String s){Toast.makeText(getContext(),s,Toast.LENGTH_SHORT).show();}
}

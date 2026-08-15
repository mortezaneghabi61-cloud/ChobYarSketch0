package ir.chobyar.sketch;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PointF;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Dual-unit presentation layer for ChobYar.
 *
 * The model stays internally in millimeters. Existing centimeter labels remain
 * familiar for woodworking/general use, while this layer adds the exact mm value
 * directly beside every visible dimension. Numeric editors and typed commands
 * also accept explicit cm/mm suffixes (8cm == 80mm). Bare numbers keep the
 * current ChobYar convention and are interpreted as centimeters.
 */
public class DualUnitSolidCadCanvasView extends SolidCadCanvasView {

    private static final float PX_PER_MM = 3f;
    private static final Pattern EXPLICIT_UNIT = Pattern.compile(
            "([-+]?\\d+(?:\\.\\d+)?)\\s*(cm|mm)", Pattern.CASE_INSENSITIVE);
    private static final Pattern CM_TEXT = Pattern.compile("([-+]?\\d+(?:\\.\\d+)?)\\s*cm", Pattern.CASE_INSENSITIVE);

    private final Paint mmTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private Field entitiesField;
    private Field viewScaleField;
    private Field offsetXField;
    private Field offsetYField;
    private Field drawingField;
    private Field startXField;
    private Field startYField;
    private Field endXField;
    private Field endYField;
    private Method isVisibleMethod;

    public DualUnitSolidCadCanvasView(Context context) {
        super(context);
        mmTextPaint.setColor(Color.rgb(95, 105, 122));
        mmTextPaint.setTextSize(21f);
        mmTextPaint.setTextAlign(Paint.Align.CENTER);
        initDualReflection();
    }

    private void initDualReflection() {
        try {
            entitiesField = field(CadCanvasView.class, "entities");
            viewScaleField = field(CadCanvasView.class, "viewScale");
            offsetXField = field(CadCanvasView.class, "offsetX");
            offsetYField = field(CadCanvasView.class, "offsetY");
            drawingField = field(CadCanvasView.class, "drawing");
            startXField = field(CadCanvasView.class, "startX");
            startYField = field(CadCanvasView.class, "startY");
            endXField = field(CadCanvasView.class, "endX");
            endYField = field(CadCanvasView.class, "endY");
            Class<?> entity = Class.forName("ir.chobyar.sketch.CadCanvasView$Entity");
            isVisibleMethod = CadCanvasView.class.getDeclaredMethod("isVisible", entity);
            isVisibleMethod.setAccessible(true);
        } catch (Exception ignored) {
        }
    }

    private static Field field(Class<?> owner, String name) throws NoSuchFieldException {
        Field f = owner.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }

    /** User-visible unit policy. */
    public String dualUnitSummary() {
        return "نمایش اندازه: سانتی‌متر + میلی‌متر | ورود: 8cm یا 80mm";
    }

    @Override
    public String selectedInfo() {
        return addMillimetersToCmText(super.selectedInfo());
    }

    @Override
    public String exactDimensionTitle() {
        String title = super.exactDimensionTitle();
        title = title.replace(" — cm", "").replace(" — سانتی‌متر", "");
        return title + " — cm / mm";
    }

    @Override
    public String exactDimensionHint() {
        Object selected = selectedObject();
        if (selected == null) return "اول فقط یک شکل را انتخاب کن";
        String type = selected.getClass().getSimpleName();
        if ("LineEntity".equals(type)) return "طول؛ مثال: 80cm یا 800mm";
        if ("RectEntity".equals(type)) return "عرض و ارتفاع؛ مثال: 60cm 40cm یا 600mm 400mm";
        if ("CircleEntity".equals(type)) return "قطر؛ مثال: 8cm یا 80mm";
        if ("ArcEntity".equals(type)) return "شعاع؛ مثال: 5cm یا 50mm";
        if ("PolygonEntity".equals(type)) return "شعاع؛ مثال: 8cm یا 80mm";
        return "برای این شکل ویرایش عددی تعریف نشده";
    }

    @Override
    public String exactDimensionMessage() {
        return selectedInfo() + "\n\n" + exactDimensionHint()
                + "\nعدد بدون پسوند مثل قبل سانتی‌متر حساب می‌شود.";
    }

    @Override
    public String applySelectedDimension(String raw) {
        return super.applySelectedDimension(explicitUnitsToCentimeters(raw));
    }

    @Override
    public String executeCommand(String raw) {
        return addMillimetersToCmText(super.executeCommand(explicitUnitsToCentimeters(raw)));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (!isShowDimensions()) return;
        drawMillimeterDimensions(canvas);
        drawLiveMillimeterDimension(canvas);
    }

    private void drawMillimeterDimensions(Canvas canvas) {
        for (Object e : entities()) {
            if (e == null || !visible(e) || construction(e)) continue;
            String type = e.getClass().getSimpleName();
            try {
                if ("LineEntity".equals(type) || "MeasureEntity".equals(type)) {
                    float x1=getFloat(e,"x1"), y1=getFloat(e,"y1"), x2=getFloat(e,"x2"), y2=getFloat(e,"y2");
                    PointF m=worldToScreen((x1+x2)/2f,(y1+y2)/2f);
                    mmLabel(canvas, mm((float)Math.hypot(x2-x1,y2-y1)), m.x, m.y+12f);
                } else if ("RectEntity".equals(type)) {
                    Field pf=findField(e.getClass(),"p");
                    if(pf!=null){
                        PointF[] p=(PointF[])pf.get(e);
                        PointF a=worldToScreen((p[0].x+p[1].x)/2f,(p[0].y+p[1].y)/2f);
                        PointF b=worldToScreen((p[1].x+p[2].x)/2f,(p[1].y+p[2].y)/2f);
                        mmLabel(canvas,mm(dist(p[0],p[1])),a.x,a.y+12f);
                        mmLabel(canvas,mm(dist(p[1],p[2])),b.x+34f,b.y+22f);
                    }
                } else if ("CircleEntity".equals(type)) {
                    float x=getFloat(e,"x"),y=getFloat(e,"y"),r=Math.abs(getFloat(e,"r"));
                    PointF p=worldToScreen(x,y-r);
                    mmLabel(canvas,"Ø "+mm(r*2f),p.x,p.y+12f);
                } else if ("ArcEntity".equals(type)) {
                    float x=getFloat(e,"x"),y=getFloat(e,"y"),r=Math.abs(getFloat(e,"r"));
                    PointF p=worldToScreen(x,y-r);
                    mmLabel(canvas,"R "+mm(r),p.x,p.y+12f);
                } else if ("PolygonEntity".equals(type)) {
                    List<PointF> pts=points(e);
                    if(!pts.isEmpty()){
                        PointF c=centroid(pts);
                        PointF s=worldToScreen(c.x,c.y);
                        mmLabel(canvas,"R "+mm(dist(c,pts.get(0))),s.x,s.y+22f);
                    }
                } else if ("PointEntity".equals(type)) {
                    float x=getFloat(e,"x"),y=getFloat(e,"y");
                    PointF s=worldToScreen(x,y);
                    mmLabel(canvas,"("+num(x)+", "+num(y)+") mm",s.x+58f,s.y+12f);
                }
            } catch (Exception ignored) {
            }
        }
    }

    private void drawLiveMillimeterDimension(Canvas canvas) {
        try {
            if (drawingField == null || !drawingField.getBoolean(this)) return;
            float x1=startXField.getFloat(this), y1=startYField.getFloat(this);
            float x2=endXField.getFloat(this), y2=endYField.getFloat(this);
            int tool=getTool();
            String text=null;
            if(tool==TOOL_LINE||tool==TOOL_MEASURE) text=mm((float)Math.hypot(x2-x1,y2-y1));
            else if(tool==TOOL_RECT) text=mm(Math.abs(x2-x1))+" × "+mm(Math.abs(y2-y1));
            else if(tool==TOOL_CIRCLE) text="Ø "+mm((float)Math.hypot(x2-x1,y2-y1)*2f);
            else if(tool==TOOL_ARC||tool==TOOL_POLYGON) text="R "+mm((float)Math.hypot(x2-x1,y2-y1));
            if(text!=null){
                PointF s=worldToScreen((x1+x2)/2f,(y1+y2)/2f);
                mmLabel(canvas,text,s.x,s.y-2f);
            }
        } catch (Exception ignored) {
        }
    }

    private void mmLabel(Canvas canvas,String text,float x,float y){
        canvas.drawText(text,x,y,mmTextPaint);
    }

    /**
     * Explicit units can be mixed in one input, e.g. "60cm 400mm".
     * Bare values remain centimeters for backward compatibility.
     */
    private String explicitUnitsToCentimeters(String raw) {
        if (raw == null) return null;
        String normalized = normalizeDigits(raw);
        Matcher m = EXPLICIT_UNIT.matcher(normalized);
        StringBuffer out = new StringBuffer();
        while (m.find()) {
            float value = Float.parseFloat(m.group(1));
            String unit = m.group(2).toLowerCase(Locale.US);
            float cm = "mm".equals(unit) ? value / 10f : value;
            m.appendReplacement(out, Matcher.quoteReplacement(num(cm)));
        }
        m.appendTail(out);
        return out.toString();
    }

    private String addMillimetersToCmText(String text) {
        if (text == null || text.isEmpty()) return text;
        Matcher m = CM_TEXT.matcher(text);
        StringBuffer out = new StringBuffer();
        while (m.find()) {
            float cm = Float.parseFloat(m.group(1));
            String replacement = num(cm)+" cm / "+num(cm*10f)+" mm";
            m.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(out);
        return out.toString();
    }

    @SuppressWarnings("unchecked")
    private List<Object> entities() {
        try { return entitiesField == null ? new ArrayList<>() : (List<Object>)entitiesField.get(this); }
        catch (Exception e) { return new ArrayList<>(); }
    }

    private boolean visible(Object e) {
        try { return isVisibleMethod == null || Boolean.TRUE.equals(isVisibleMethod.invoke(this,e)); }
        catch (Exception ex) { return true; }
    }

    private static boolean construction(Object e) {
        Object v=call(e,"isConstruction");
        return v instanceof Boolean && (Boolean)v;
    }

    private Object selectedObject() {
        try {
            Field selected = field(CadCanvasView.class,"selected");
            return selected.get(this);
        } catch (Exception e) {
            return null;
        }
    }

    private float viewScale(){try{return viewScaleField==null?1f:viewScaleField.getFloat(this);}catch(Exception e){return 1f;}}
    private float offsetX(){try{return offsetXField==null?0f:offsetXField.getFloat(this);}catch(Exception e){return 0f;}}
    private float offsetY(){try{return offsetYField==null?0f:offsetYField.getFloat(this);}catch(Exception e){return 0f;}}
    private PointF worldToScreen(float x,float y){float s=PX_PER_MM*viewScale();return new PointF(offsetX()+x*s,offsetY()+y*s);}

    private static Field findField(Class<?> c,String name){
        Class<?> x=c;
        while(x!=null){
            try{Field f=x.getDeclaredField(name);f.setAccessible(true);return f;}
            catch(Exception e){x=x.getSuperclass();}
        }
        return null;
    }

    private static float getFloat(Object o,String name)throws Exception{
        Field f=findField(o.getClass(),name);
        if(f==null)throw new NoSuchFieldException(name);
        return f.getFloat(o);
    }

    @SuppressWarnings("unchecked")
    private static List<PointF> points(Object e)throws Exception{
        Field f=findField(e.getClass(),"points");
        if(f==null)return new ArrayList<>();
        return (List<PointF>)f.get(e);
    }

    private static PointF centroid(List<PointF> p){
        float x=0,y=0;
        for(PointF q:p){x+=q.x;y+=q.y;}
        return p.isEmpty()?new PointF():new PointF(x/p.size(),y/p.size());
    }

    private static float dist(PointF a,PointF b){return(float)Math.hypot(b.x-a.x,b.y-a.y);}
    private static String mm(float value){return num(value)+" mm";}

    private static Object call(Object target,String name){
        if(target==null)return null;
        Class<?> c=target.getClass();
        while(c!=null){
            try{Method m=c.getDeclaredMethod(name);m.setAccessible(true);return m.invoke(target);}
            catch(NoSuchMethodException e){c=c.getSuperclass();}
            catch(Exception e){return null;}
        }
        return null;
    }

    private static String normalizeDigits(String s){
        if(s==null)return"";
        StringBuilder b=new StringBuilder(s.length());
        for(int i=0;i<s.length();i++){
            char c=s.charAt(i);
            if(c>='۰'&&c<='۹')b.append((char)('0'+(c-'۰')));
            else if(c>='٠'&&c<='٩')b.append((char)('0'+(c-'٠')));
            else b.append(c);
        }
        return b.toString();
    }

    private static String num(float value){
        String s=String.format(Locale.US,"%.2f",value);
        while(s.contains(".")&&(s.endsWith("0")||s.endsWith(".")))s=s.substring(0,s.length()-1);
        return s;
    }
}

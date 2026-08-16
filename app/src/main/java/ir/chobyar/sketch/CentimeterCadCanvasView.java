package ir.chobyar.sketch;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PointF;
import android.graphics.RectF;
import android.text.InputType;
import android.view.MotionEvent;
import android.widget.EditText;
import android.widget.Toast;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Millimeter-first presentation layer. Geometry, typed values, dimensions and
 * CAD exchange all use the same unit, avoiding hidden conversions while drawing.
 */
public class CentimeterCadCanvasView extends AdvancedCadCanvasView {

    private static final float MM_PER_CM = 10f;
    private static final float PX_PER_MM = 3f;
    private static final Pattern MM_VALUE = Pattern.compile("(-?\\d+(?:\\.\\d+)?)\\s*mm");

    private final Paint cmTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private boolean showCmDimensions = true;

    private Field showDimensionsField;
    private Field entitiesField;
    private Field selectedObjectsField;
    private Field viewScaleField;
    private Field offsetXField;
    private Field offsetYField;
    private Field drawingField;
    private Field startXField;
    private Field startYField;
    private Field endXField;
    private Field endYField;
    private Method isVisibleMethod;

    private int cmPalettePressed = -1;

    public CentimeterCadCanvasView(Context context) {
        super(context);
        cmTextPaint.setColor(Color.rgb(35, 85, 180));
        cmTextPaint.setTextSize(27f);
        cmTextPaint.setTextAlign(Paint.Align.CENTER);
        initCmReflection();
        forceBaseDimensionsOff();
    }

    private void initCmReflection() {
        try {
            showDimensionsField = field(CadCanvasView.class, "showDimensions");
            entitiesField = field(CadCanvasView.class, "entities");
            viewScaleField = field(CadCanvasView.class, "viewScale");
            offsetXField = field(CadCanvasView.class, "offsetX");
            offsetYField = field(CadCanvasView.class, "offsetY");
            drawingField = field(CadCanvasView.class, "drawing");
            startXField = field(CadCanvasView.class, "startX");
            startYField = field(CadCanvasView.class, "startY");
            endXField = field(CadCanvasView.class, "endX");
            endYField = field(CadCanvasView.class, "endY");
            selectedObjectsField = field(SmartCadCanvasView.class, "selectedObjects");
            isVisibleMethod = CadCanvasView.class.getDeclaredMethod("isVisible", entityInterface());
            isVisibleMethod.setAccessible(true);
        } catch (Exception ignored) {
        }
    }

    private Class<?> entityInterface() throws ClassNotFoundException {
        return Class.forName("ir.chobyar.sketch.CadCanvasView$Entity");
    }

    private static Field field(Class<?> c, String name) throws NoSuchFieldException {
        Field f = c.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }

    private void forceBaseDimensionsOff() {
        try {
            if (showDimensionsField != null) showDimensionsField.setBoolean(this, false);
        } catch (Exception ignored) {
        }
    }

    @Override
    public void toggleDimensions() {
        showCmDimensions = !showCmDimensions;
        forceBaseDimensionsOff();
        invalidate();
    }

    @Override
    public boolean isShowDimensions() {
        return showCmDimensions;
    }

    @Override
    public String selectedInfo() {
        return super.selectedInfo();
    }

    @Override
    public String applySelectedDimension(String raw) {
        if (raw == null || raw.trim().isEmpty()) return "عدد وارد نشده";
        try {
            return super.applySelectedDimension(raw);
        } catch (Exception e) {
            return "اندازه را به میلی‌متر درست وارد کن";
        }
    }

    public String exactDimensionTitle() {
        Object e = singleSelected();
        if (e == null) return "اندازه دقیق — میلی‌متر";
        String type = e.getClass().getSimpleName();
        if ("LineEntity".equals(type)) return "طول خط — mm";
        if ("RectEntity".equals(type)) return "عرض و ارتفاع مستطیل — mm";
        if ("CircleEntity".equals(type)) return "قطر دایره — mm";
        if ("ArcEntity".equals(type)) return "شعاع قوس — mm";
        if ("PolygonEntity".equals(type)) return "شعاع چندضلعی — mm";
        return "اندازه دقیق — mm";
    }

    public String exactDimensionHint() {
        Object e = singleSelected();
        if (e == null) return "اول فقط یک شکل را انتخاب کن";
        String type = e.getClass().getSimpleName();
        if ("LineEntity".equals(type)) return "فقط طول خط؛ مثال: 800";
        if ("RectEntity".equals(type)) return "عرض و ارتفاع؛ مثال: 600 400";
        if ("CircleEntity".equals(type)) return "فقط قطر دایره؛ مثال: 80";
        if ("ArcEntity".equals(type)) return "فقط شعاع قوس؛ مثال: 50";
        if ("PolygonEntity".equals(type)) return "فقط شعاع چندضلعی؛ مثال: 80";
        return "برای این شکل ویرایش عددی تعریف نشده";
    }

    public String exactDimensionMessage() {
        Object e = singleSelected();
        if (e == null) {
            return selectionCount() > 1
                    ? "برای اندازه دقیق فقط یک شکل را انتخاب کن."
                    : "اول یک شکل را انتخاب کن.";
        }
        return selectedInfo() + "\n\n" + exactDimensionHint() + " mm";
    }

    public String exactDimensionCurrentValue() {
        Object e = singleSelected();
        if (e == null) return "";
        try {
            String type = e.getClass().getSimpleName();
            if ("LineEntity".equals(type)) {
                float x1 = getFloat(e,"x1"), y1=getFloat(e,"y1"), x2=getFloat(e,"x2"), y2=getFloat(e,"y2");
                return cm((float)Math.hypot(x2-x1,y2-y1));
            }
            if ("RectEntity".equals(type)) {
                Field pField = findField(e.getClass(), "p");
                if (pField != null) {
                    PointF[] p = (PointF[]) pField.get(e);
                    float w = dist(p[0],p[1]);
                    float h = dist(p[1],p[2]);
                    return cm(w) + " " + cm(h);
                }
            }
            if ("CircleEntity".equals(type)) return cm(getFloat(e,"r")*2f);
            if ("ArcEntity".equals(type)) return cm(getFloat(e,"r"));
            if ("PolygonEntity".equals(type)) {
                List<PointF> pts = points(e);
                if (!pts.isEmpty()) {
                    PointF c = centroid(pts);
                    return cm(dist(c,pts.get(0)));
                }
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    public boolean canEditExactDimension() {
        Object e = singleSelected();
        if (e == null) return false;
        String t = e.getClass().getSimpleName();
        return "LineEntity".equals(t) || "RectEntity".equals(t) || "CircleEntity".equals(t)
                || "ArcEntity".equals(t) || "PolygonEntity".equals(t);
    }

    @Override
    public String executeCommand(String raw) {
        if (raw == null) return "";
        String s = normalizeDigits(raw).trim().replace(',', ' ');
        if (s.isEmpty()) return "";
        try {
            return super.executeCommand(s);
        } catch (Exception e) {
            return "فرمت عدد درست نیست";
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        forceBaseDimensionsOff();
        super.onDraw(canvas);
        if (showCmDimensions) {
            drawEntityDimensionsCm(canvas);
            drawLiveDimensionCm(canvas);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (interceptAdvancedPaletteCm(event)) return true;
        return super.onTouchEvent(event);
    }

    private boolean interceptAdvancedPaletteCm(MotionEvent event) {
        if (!hasTwoSelectedLines()) {
            cmPalettePressed = -1;
            return false;
        }
        RectF[] rects = paletteRects();
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            for (int i=0;i<rects.length;i++) {
                if (rects[i].contains(event.getX(),event.getY())) {
                    cmPalettePressed=i;
                    return true;
                }
            }
            return false;
        }
        if (cmPalettePressed >= 0) {
            if (action == MotionEvent.ACTION_UP) {
                int i=cmPalettePressed;
                cmPalettePressed=-1;
                if (rects[i].contains(event.getX(),event.getY())) runCmPaletteTool(i);
            } else if (action == MotionEvent.ACTION_CANCEL) {
                cmPalettePressed=-1;
            }
            return true;
        }
        return false;
    }

    private RectF[] paletteRects() {
        RectF[] out = new RectF[5];
        float gap=6f, margin=8f;
        float available=Math.max(300f,getWidth()-2f*margin);
        float w=Math.min(132f,(available-gap*4f)/5f);
        float total=w*5f+gap*4f;
        float left=Math.max(margin,(getWidth()-total)/2f);
        float h=52f;
        float top=Math.max(10f,getHeight()-h-48f);
        for(int i=0;i<5;i++){
            float x=left+i*(w+gap);
            out[i]=new RectF(x,top,x+w,top+h);
        }
        return out;
    }

    private void runCmPaletteTool(int index) {
        if (index==0) showResultCm(trimSelectedLines());
        else if (index==1) showResultCm(extendSelectedLines());
        else if (index==2) askCmDistance("Fillet — شعاع", "شعاع به میلی‌متر؛ مثال: 10", true);
        else if (index==3) askCmDistance("Chamfer — پخ", "فاصله پخ به میلی‌متر؛ مثال: 10", false);
        else showResultCm(joinSelectedLines());
    }

    private void askCmDistance(String title,String hint,boolean fillet) {
        EditText input=new EditText(getContext());
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL);
        input.setHint(hint);
        input.setText("10");
        input.setSelectAllOnFocus(true);
        new AlertDialog.Builder(getContext())
                .setTitle(title+" — mm")
                .setMessage("مقدار را به میلی‌متر وارد کن.")
                .setView(input)
                .setPositiveButton("اعمال",(d,w)->{
                    try{
                        float mm=Float.parseFloat(normalizeDigits(input.getText().toString().trim()));
                        showResultCm(fillet?super.filletSelectedLines(mm):super.chamferSelectedLines(mm));
                    }catch(Exception e){showResultCm("عدد درست وارد نشده");}
                })
                .setNegativeButton("لغو",null)
                .show();
    }

    private void showResultCm(String text) {
        Toast.makeText(getContext(),mmTextToCm(text),Toast.LENGTH_SHORT).show();
    }

    private void drawEntityDimensionsCm(Canvas canvas) {
        for (Object e : entities()) {
            if (e == null || !visible(e) || construction(e)) continue;
            String type=e.getClass().getSimpleName();
            try {
                if ("LineEntity".equals(type) || "MeasureEntity".equals(type)) {
                    float x1=getFloat(e,"x1"),y1=getFloat(e,"y1"),x2=getFloat(e,"x2"),y2=getFloat(e,"y2");
                    PointF m=worldToScreen((x1+x2)/2f,(y1+y2)/2f);
                    label(canvas,cmLabel((float)Math.hypot(x2-x1,y2-y1)),m.x,m.y-10f);
                } else if ("RectEntity".equals(type)) {
                    Field pf=findField(e.getClass(),"p");
                    if(pf!=null){
                        PointF[]p=(PointF[])pf.get(e);
                        PointF a=worldToScreen((p[0].x+p[1].x)/2f,(p[0].y+p[1].y)/2f);
                        PointF b=worldToScreen((p[1].x+p[2].x)/2f,(p[1].y+p[2].y)/2f);
                        label(canvas,cmLabel(dist(p[0],p[1])),a.x,a.y-10f);
                        label(canvas,cmLabel(dist(p[1],p[2])),b.x+34f,b.y);
                    }
                } else if ("CircleEntity".equals(type)) {
                    float x=getFloat(e,"x"),y=getFloat(e,"y"),r=getFloat(e,"r");
                    PointF p=worldToScreen(x,y-r);
                    label(canvas,"Ø "+cmLabel(r*2f),p.x,p.y-10f);
                } else if ("ArcEntity".equals(type)) {
                    float x=getFloat(e,"x"),y=getFloat(e,"y"),r=getFloat(e,"r");
                    PointF p=worldToScreen(x,y-r);
                    label(canvas,"R "+cmLabel(r),p.x,p.y-10f);
                } else if ("PolygonEntity".equals(type)) {
                    List<PointF>pts=points(e);
                    if(!pts.isEmpty()){
                        PointF c=centroid(pts);
                        PointF s=worldToScreen(c.x,c.y);
                        label(canvas,pts.size()+" ضلع | R "+cmLabel(dist(c,pts.get(0))),s.x,s.y);
                    }
                } else if ("PointEntity".equals(type)) {
                    PointF s=worldToScreen(getFloat(e,"x"),getFloat(e,"y"));
                    label(canvas,"("+cm(getFloat(e,"x"))+", "+cm(getFloat(e,"y"))+") mm",s.x+55f,s.y-12f);
                } else if ("AngleEntity".equals(type)) {
                    float ax=getFloat(e,"ax"),ay=getFloat(e,"ay"),cx=getFloat(e,"cx"),cy=getFloat(e,"cy"),bx=getFloat(e,"bx"),by=getFloat(e,"by");
                    PointF s=worldToScreen(cx,cy);
                    label(canvas,format(angleAt(ax,ay,cx,cy,bx,by))+"°",s.x+45f,s.y-18f);
                }
            } catch (Exception ignored) {
            }
        }
    }

    private void drawLiveDimensionCm(Canvas canvas) {
        try {
            if (drawingField==null || !drawingField.getBoolean(this)) return;
            float x1=startXField.getFloat(this),y1=startYField.getFloat(this),x2=endXField.getFloat(this),y2=endYField.getFloat(this);
            int tool=getTool();
            String text=null;
            if(tool==TOOL_LINE||tool==TOOL_MEASURE) text=cmLabel((float)Math.hypot(x2-x1,y2-y1));
            else if(tool==TOOL_RECT) text=cmLabel(Math.abs(x2-x1))+" × "+cmLabel(Math.abs(y2-y1));
            else if(tool==TOOL_CIRCLE) text="Ø "+cmLabel((float)Math.hypot(x2-x1,y2-y1)*2f);
            else if(tool==TOOL_ARC||tool==TOOL_POLYGON) text="R "+cmLabel((float)Math.hypot(x2-x1,y2-y1));
            if(text!=null){
                PointF s=worldToScreen((x1+x2)/2f,(y1+y2)/2f);
                label(canvas,text,s.x,s.y-24f);
            }
        }catch(Exception ignored){}
    }

    private void label(Canvas canvas,String text,float x,float y) {
        canvas.drawText(text,x,y,cmTextPaint);
    }

    private String dimensionInputCmToMm(String raw) {
        String s=normalizeDigits(raw).trim().replace('×',' ').replace(',',' ');
        String[] a=s.split("\\s+");
        StringBuilder b=new StringBuilder();
        for(String token:a){
            if(token.isEmpty())continue;
            float v=Float.parseFloat(token)*MM_PER_CM;
            if(b.length()>0)b.append(' ');
            b.append(String.format(Locale.US,"%.4f",v));
        }
        return b.toString();
    }

    private String commandCmToMm(String raw) {
        String[] a=raw.split("\\s+");
        if(a.length==0)return raw;
        String cmd=a[0].toUpperCase(Locale.US);
        int[] indexes=null;
        if(eq(cmd,"L","LINE")) indexes=new int[]{1,2,3,4};
        else if(eq(cmd,"REC","RECT","RECTANG")) indexes=new int[]{1,2,3,4};
        else if(eq(cmd,"C","CIRCLE")) indexes=new int[]{1,2,3};
        else if(eq(cmd,"PO","POINT")) indexes=new int[]{1,2};
        else if(eq(cmd,"A","ARC")) indexes=new int[]{1,2,3};
        else if(eq(cmd,"POL","POLYGON")) indexes=new int[]{2,3,4};
        else if(eq(cmd,"M","MOVE","CO","COPY")) indexes=new int[]{1,2};
        else if(eq(cmd,"O","OFFSET","LENGTH","RADIUS","DIAMETER","P","PUSHPULL","EXTRUDE","F","FILLET","CHA","CHAMFER")) indexes=new int[]{1};
        else if(eq(cmd,"SIZE")) indexes=new int[]{1,2};
        else if(eq(cmd,"MI","MIRROR")) indexes=new int[]{2};
        else if(eq(cmd,"AR","ARRAY")) indexes=new int[]{2,3};
        else if(eq(cmd,"GUIDE")) indexes=new int[]{2};
        else if(eq(cmd,"TAPE","DIST")) indexes=new int[]{1,2,3,4};
        if(indexes==null)return raw;
        for(int i:indexes){
            if(i<a.length && isNumber(a[i])) a[i]=String.format(Locale.US,"%.4f",Float.parseFloat(a[i])*MM_PER_CM);
        }
        StringBuilder b=new StringBuilder();
        for(String token:a){if(b.length()>0)b.append(' ');b.append(token);}
        return b.toString();
    }

    private static boolean eq(String value,String...values){for(String v:values)if(v.equals(value))return true;return false;}
    private static boolean isNumber(String s){try{Float.parseFloat(s);return true;}catch(Exception e){return false;}}

    private String mmTextToCm(String text) {
        if(text==null)return null;
        Matcher m=MM_VALUE.matcher(text);
        StringBuffer out=new StringBuffer();
        while(m.find()){
            float mm=Float.parseFloat(m.group(1));
            m.appendReplacement(out,Matcher.quoteReplacement(cm(mm)+" cm"));
        }
        m.appendTail(out);
        return out.toString();
    }

    private String cmLabel(float mm){return cm(mm)+" mm";}
    private String cm(float mm){return trim(String.format(Locale.US,"%.2f",mm));}
    private static String trim(String s){while(s.contains(".")&&(s.endsWith("0")||s.endsWith("."))){s=s.substring(0,s.length()-1);}return s;}
    private static String format(float v){return trim(String.format(Locale.US,"%.1f",v));}
    private static float dist(PointF a,PointF b){return(float)Math.hypot(b.x-a.x,b.y-a.y);}

    @SuppressWarnings("unchecked")
    private List<Object> entities(){
        try{return entitiesField==null?new ArrayList<>(): (List<Object>)entitiesField.get(this);}catch(Exception e){return new ArrayList<>();}
    }

    @SuppressWarnings("unchecked")
    private List<Object> selection(){
        try{return selectedObjectsField==null?new ArrayList<>(): (List<Object>)selectedObjectsField.get(this);}catch(Exception e){return new ArrayList<>();}
    }

    private int selectionCount(){return selection().size();}
    private Object singleSelected(){List<Object>s=selection();return s.size()==1?s.get(0):null;}
    private boolean hasTwoSelectedLines(){List<Object>s=selection();return s.size()==2&&"LineEntity".equals(s.get(0).getClass().getSimpleName())&&"LineEntity".equals(s.get(1).getClass().getSimpleName());}

    private boolean visible(Object e){
        try{return isVisibleMethod==null||Boolean.TRUE.equals(isVisibleMethod.invoke(this,e));}catch(Exception ex){return true;}
    }
    private boolean construction(Object e){Object v=call(e,"isConstruction");return v instanceof Boolean&&(Boolean)v;}

    private float viewScale(){try{return viewScaleField.getFloat(this);}catch(Exception e){return 1f;}}
    private float offsetX(){try{return offsetXField.getFloat(this);}catch(Exception e){return 0f;}}
    private float offsetY(){try{return offsetYField.getFloat(this);}catch(Exception e){return 0f;}}
    private PointF worldToScreen(float x,float y){float s=PX_PER_MM*viewScale();return new PointF(offsetX()+x*s,offsetY()+y*s);}

    private static Field findField(Class<?> c,String name){Class<?>x=c;while(x!=null){try{Field f=x.getDeclaredField(name);f.setAccessible(true);return f;}catch(Exception e){x=x.getSuperclass();}}return null;}
    private static float getFloat(Object o,String name)throws Exception{Field f=findField(o.getClass(),name);if(f==null)throw new NoSuchFieldException(name);return f.getFloat(o);}
    @SuppressWarnings("unchecked")
    private static List<PointF> points(Object e)throws Exception{Field f=findField(e.getClass(),"points");if(f==null)return new ArrayList<>();return (List<PointF>)f.get(e);}
    private static PointF centroid(List<PointF>p){float x=0,y=0;for(PointF q:p){x+=q.x;y+=q.y;}return p.isEmpty()?new PointF():new PointF(x/p.size(),y/p.size());}
    private static float angleAt(float ax,float ay,float cx,float cy,float bx,float by){double a1=Math.atan2(ay-cy,ax-cx),a2=Math.atan2(by-cy,bx-cx),d=Math.toDegrees(a2-a1);while(d<0)d+=360;while(d>=360)d-=360;if(d>180)d=360-d;return(float)d;}

    private static Object call(Object target,String name){
        if(target==null)return null;
        Class<?>c=target.getClass();
        while(c!=null){try{Method m=c.getDeclaredMethod(name);m.setAccessible(true);return m.invoke(target);}catch(NoSuchMethodException e){c=c.getSuperclass();}catch(Exception e){return null;}}
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
}

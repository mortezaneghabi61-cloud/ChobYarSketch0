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

/**
 * Millimeter-first presentation layer. Geometry, typed values, dimensions and
 * CAD exchange all use the same unit, avoiding hidden conversions while drawing.
 */
public class CentimeterCadCanvasView extends AdvancedCadCanvasView {

    private static final float PX_PER_MM = 3f;

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
        if (raw == null || raw.trim().isEmpty()) return getContext().getString(R.string.exact_dimension_value_required);
        try {
            return super.applySelectedDimension(raw);
        } catch (Exception e) {
            return getContext().getString(R.string.exact_dimension_invalid_mm);
        }
    }

    public String exactDimensionTitle() {
        Object e = singleSelected();
        if (e == null) return "Exact Dimension — mm";
        String type = e.getClass().getSimpleName();
        if ("LineEntity".equals(type)) return "Length Line — mm";
        if ("RectEntity".equals(type)) return "Rectangle Width and Height — mm";
        if ("CircleEntity".equals(type)) return "Diameter Circle — mm";
        if ("ArcEntity".equals(type)) return "Radius Arc — mm";
        if ("PolygonEntity".equals(type)) return "Radius Polygon — mm";
        return "Exact Dimension — mm";
    }

    public String exactDimensionHint() {
        Object e = singleSelected();
        if (e == null) return "First, select only one shape";
        String type = e.getClass().getSimpleName();
        if ("LineEntity".equals(type)) return "Only Length Line; Example: 800";
        if ("RectEntity".equals(type)) return "Width and Height; Example: 600 400";
        if ("CircleEntity".equals(type)) return getContext().getString(R.string.circle_diameter_hint);
        if ("ArcEntity".equals(type)) return "Radius Arc only; Example: 50";
        if ("PolygonEntity".equals(type)) return getContext().getString(R.string.polygon_radius_hint);
        return "No numerical editing is defined for this figure";
    }

    public String exactDimensionMessage() {
        Object e = singleSelected();
        if (e == null) {
            return selectionCount() > 1
                    ? getContext().getString(R.string.exact_dimension_single_selection_required)
                    : "Select geometry first.";
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
                return mmValue((float)Math.hypot(x2-x1,y2-y1));
            }
            if ("RectEntity".equals(type)) {
                Field pField = findField(e.getClass(), "p");
                if (pField != null) {
                    PointF[] p = (PointF[]) pField.get(e);
                    float w = dist(p[0],p[1]);
                    float h = dist(p[1],p[2]);
                    return mmValue(w) + " " + mmValue(h);
                }
            }
            if ("CircleEntity".equals(type)) return mmValue(getFloat(e,"r")*2f);
            if ("ArcEntity".equals(type)) return mmValue(getFloat(e,"r"));
            if ("PolygonEntity".equals(type)) {
                List<PointF> pts = points(e);
                if (!pts.isEmpty()) {
                    PointF c = centroid(pts);
                    return mmValue(dist(c,pts.get(0)));
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
            return "Number format is invalid";
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        forceBaseDimensionsOff();
        super.onDraw(canvas);
        if (showCmDimensions) drawRelevantDimensionsMm(canvas);
    }

    /** Only explicit measurements and a small multi-selection are labelled. */
    private void drawRelevantDimensionsMm(Canvas canvas) {
        List<Object> selected=selection();
        int selectedCount=selected.size();
        int rendered=0;
        for (Object e : entities()) {
            if (e == null || !visible(e) || construction(e)) continue;
            String type=e.getClass().getSimpleName();
            boolean explicitMeasure="MeasureEntity".equals(type)||"AngleEntity".equals(type);
            boolean selectedForComparison=selectedCount>1&&containsIdentity(selected,e);
            if(!explicitMeasure&&!selectedForComparison)continue;
            if(!explicitMeasure&&rendered>=8)continue;
            try {
                if ("LineEntity".equals(type) || "MeasureEntity".equals(type)) {
                    float x1=getFloat(e,"x1"),y1=getFloat(e,"y1"),x2=getFloat(e,"x2"),y2=getFloat(e,"y2");
                    PointF m=worldToScreen((x1+x2)/2f,(y1+y2)/2f);
                    label(canvas,mmLabel((float)Math.hypot(x2-x1,y2-y1)),m.x,m.y-10f);
                } else if ("RectEntity".equals(type)) {
                    Field pf=findField(e.getClass(),"p");
                    if(pf!=null){
                        PointF[]p=(PointF[])pf.get(e);
                        PointF a=worldToScreen((p[0].x+p[1].x)/2f,(p[0].y+p[1].y)/2f);
                        PointF b=worldToScreen((p[1].x+p[2].x)/2f,(p[1].y+p[2].y)/2f);
                        label(canvas,mmLabel(dist(p[0],p[1])),a.x,a.y-10f);
                        label(canvas,mmLabel(dist(p[1],p[2])),b.x+34f,b.y);
                    }
                } else if ("CircleEntity".equals(type)) {
                    float x=getFloat(e,"x"),y=getFloat(e,"y"),r=getFloat(e,"r");
                    PointF p=worldToScreen(x,y-r);
                    label(canvas,"Ø "+mmLabel(r*2f),p.x,p.y-10f);
                } else if ("ArcEntity".equals(type)) {
                    float x=getFloat(e,"x"),y=getFloat(e,"y"),r=getFloat(e,"r");
                    PointF p=worldToScreen(x,y-r);
                    label(canvas,"R "+mmLabel(r),p.x,p.y-10f);
                } else if ("PolygonEntity".equals(type)) {
                    List<PointF>pts=points(e);
                    if(!pts.isEmpty()){
                        PointF c=centroid(pts);
                        PointF s=worldToScreen(c.x,c.y);
                        label(canvas,pts.size()+" side | R "+mmLabel(dist(c,pts.get(0))),s.x,s.y);
                    }
                } else if ("PointEntity".equals(type)) {
                    // Coordinates belong in the Measure inspector, not as a
                    // permanent label over the modeling canvas.
                } else if ("AngleEntity".equals(type)) {
                    float ax=getFloat(e,"ax"),ay=getFloat(e,"ay"),cx=getFloat(e,"cx"),cy=getFloat(e,"cy"),bx=getFloat(e,"bx"),by=getFloat(e,"by");
                    PointF s=worldToScreen(cx,cy);
                    label(canvas,format(angleAt(ax,ay,cx,cy,bx,by))+"°",s.x+45f,s.y-18f);
                }
                rendered++;
            } catch (Exception ignored) {
            }
        }
    }

    private void label(Canvas canvas,String text,float x,float y) {
        canvas.drawText(text,x,y,cmTextPaint);
    }

    private static boolean containsIdentity(List<Object> values,Object target){for(Object value:values)if(value==target)return true;return false;}

    private String mmLabel(float mm){return mmValue(mm)+" mm";}
    private String mmValue(float mm){return trim(String.format(Locale.US,"%.2f",mm));}
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
            b.append(c);
        }
        return b.toString();
    }
}

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

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Next 2D CAD editing layer.
 *
 * When exactly two Line entities are selected a small contextual palette appears
 * directly on the canvas with Trim / Extend / Fillet / Chamfer / Join.
 * This keeps the main toolbar from becoming even more crowded.
 */
public class AdvancedCadCanvasView extends SmartCadCanvasView {

    private final Paint toolPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint toolText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint toolStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF[] toolRects = new RectF[5];
    private final String[] toolLabels = {"Trim", "Extend", "Fillet", "Chamfer", "Join"};
    private int pressedTool = -1;

    private Field smartSelectedField;
    private Field entitiesField;
    private Field baseSelectedField;
    private Method saveUndoMethod;

    public AdvancedCadCanvasView(Context context) {
        super(context);
        for (int i = 0; i < toolRects.length; i++) toolRects[i] = new RectF();

        toolPaint.setColor(Color.rgb(246, 248, 252));
        toolPaint.setStyle(Paint.Style.FILL);
        toolStroke.setColor(Color.rgb(42, 94, 175));
        toolStroke.setStyle(Paint.Style.STROKE);
        toolStroke.setStrokeWidth(2f);
        toolText.setColor(Color.rgb(30, 65, 120));
        toolText.setTextAlign(Paint.Align.CENTER);
        toolText.setTextSize(24f);

        initAdvancedReflection();
    }

    private void initAdvancedReflection() {
        try {
            smartSelectedField = SmartCadCanvasView.class.getDeclaredField("selectedObjects");
            smartSelectedField.setAccessible(true);
            entitiesField = CadCanvasView.class.getDeclaredField("entities");
            entitiesField.setAccessible(true);
            baseSelectedField = CadCanvasView.class.getDeclaredField("selected");
            baseSelectedField.setAccessible(true);
            saveUndoMethod = CadCanvasView.class.getDeclaredMethod("saveUndo");
            saveUndoMethod.setAccessible(true);
        } catch (Exception ignored) {
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        layoutContextTools();
        if (!hasExactlyTwoLines()) {
            clearToolRects();
            return;
        }

        for (int i = 0; i < toolRects.length; i++) {
            RectF r = toolRects[i];
            canvas.drawRoundRect(r, 16f, 16f, toolPaint);
            canvas.drawRoundRect(r, 16f, 16f, toolStroke);
            canvas.drawText(toolLabels[i], r.centerX(), r.centerY() + 8f, toolText);
        }
    }

    private void layoutContextTools() {
        if (!hasExactlyTwoLines() || getWidth() <= 0 || getHeight() <= 0) {
            clearToolRects();
            return;
        }
        float gap = 6f;
        float margin = 8f;
        float available = Math.max(300f, getWidth() - 2f * margin);
        float w = Math.min(132f, (available - gap * 4f) / 5f);
        float total = w * 5f + gap * 4f;
        float left = Math.max(margin, (getWidth() - total) / 2f);
        float h = 52f;
        float top = Math.max(10f, getHeight() - h - 48f);
        for (int i = 0; i < 5; i++) {
            float x = left + i * (w + gap);
            toolRects[i].set(x, top, x + w, top + h);
        }
    }

    private void clearToolRects() {
        for (RectF r : toolRects) r.setEmpty();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (hasExactlyTwoLines()) {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                for (int i = 0; i < toolRects.length; i++) {
                    if (!toolRects[i].isEmpty() && toolRects[i].contains(event.getX(), event.getY())) {
                        pressedTool = i;
                        return true;
                    }
                }
            } else if (pressedTool >= 0) {
                if (action == MotionEvent.ACTION_UP) {
                    int index = pressedTool;
                    pressedTool = -1;
                    if (toolRects[index].contains(event.getX(), event.getY())) runContextTool(index);
                } else if (action == MotionEvent.ACTION_CANCEL) {
                    pressedTool = -1;
                }
                return true;
            }
        }
        return super.onTouchEvent(event);
    }

    private void runContextTool(int index) {
        switch (index) {
            case 0:
                showResult(trimSelectedLines());
                break;
            case 1:
                showResult(extendSelectedLines());
                break;
            case 2:
                askDistance("Fillet — شعاع", "شعاع mm، مثال 10", true);
                break;
            case 3:
                askDistance("Chamfer — پخ", "فاصله از گوشه mm، مثال 10", false);
                break;
            case 4:
                showResult(joinSelectedLines());
                break;
        }
    }

    private void askDistance(String title, String hint, boolean fillet) {
        EditText input = new EditText(getContext());
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        input.setHint(hint);
        input.setText("10");
        input.setSelectAllOnFocus(true);

        new AlertDialog.Builder(getContext())
                .setTitle(title)
                .setMessage("دو خط انتخاب‌شده روی گوشه ویرایش می‌شوند.")
                .setView(input)
                .setPositiveButton("اعمال", (d, w) -> {
                    try {
                        float value = Float.parseFloat(normalizeDigits(input.getText().toString().trim()));
                        showResult(fillet ? filletSelectedLines(value) : chamferSelectedLines(value));
                    } catch (Exception e) {
                        showResult("عدد درست وارد نشده");
                    }
                })
                .setNegativeButton("لغو", null)
                .show();
    }

    /** Trim crossing line segments to their intersection, keeping the longer side of each line. */
    public String trimSelectedLines() {
        List<Object> pair = twoSelectedLines();
        if (pair == null) return "برای Trim دقیقاً دو خط را با چندانتخاب انتخاب کن";
        LineData a = readLine(pair.get(0));
        LineData b = readLine(pair.get(1));
        PointF p = infiniteIntersection(a, b);
        if (p == null) return "این دو خط موازی هستند";
        if (!pointOnSegment(p, a, 0.05f) || !pointOnSegment(p, b, 0.05f))
            return "محل برخورد بیرون خط‌هاست؛ برای این حالت Extend را بزن";

        saveUndo();
        keepFarSideAt(pair.get(0), a, p);
        keepFarSideAt(pair.get(1), b, p);
        invalidate();
        return "Trim انجام شد — قسمت کوتاه‌تر هر خط حذف شد";
    }

    /** Extends only the selected lines that do not currently reach the common infinite-line intersection. */
    public String extendSelectedLines() {
        List<Object> pair = twoSelectedLines();
        if (pair == null) return "برای Extend دقیقاً دو خط را با چندانتخاب انتخاب کن";
        LineData a = readLine(pair.get(0));
        LineData b = readLine(pair.get(1));
        PointF p = infiniteIntersection(a, b);
        if (p == null) return "خط‌های موازی به هم نمی‌رسند";

        boolean aHas = pointOnSegment(p, a, 0.05f);
        boolean bHas = pointOnSegment(p, b, 0.05f);
        if (aHas && bHas) return "دو خط همین حالا همدیگر را قطع می‌کنند؛ Trim را استفاده کن";

        saveUndo();
        if (!aHas) moveNearestEndpoint(pair.get(0), a, p);
        if (!bHas) moveNearestEndpoint(pair.get(1), b, p);
        invalidate();
        return "Extend انجام شد و خط‌ها به محل تقاطع رسیدند";
    }

    /** Creates a straight chamfer between two selected line directions. */
    public String chamferSelectedLines(float setback) {
        if (setback <= 0f) return "فاصله پخ باید بزرگ‌تر از صفر باشد";
        List<Object> pair = twoSelectedLines();
        if (pair == null) return "برای Chamfer دقیقاً دو خط را انتخاب کن";
        LineData a = readLine(pair.get(0));
        LineData b = readLine(pair.get(1));
        PointF corner = infiniteIntersection(a, b);
        if (corner == null) return "روی خط‌های موازی Chamfer ساخته نمی‌شود";

        Branch ba = keptBranch(a, corner);
        Branch bb = keptBranch(b, corner);
        if (ba.length <= setback || bb.length <= setback)
            return "فاصله پخ از طول یکی از خط‌ها بزرگ‌تر است";

        PointF pa = add(corner, mul(ba.u, setback));
        PointF pb = add(corner, mul(bb.u, setback));

        saveUndo();
        setEndpoint(pair.get(0), ba.nearIndex, pa);
        setEndpoint(pair.get(1), bb.nearIndex, pb);
        Object bridge = newLine(pa.x, pa.y, pb.x, pb.y, pair.get(0));
        if (bridge != null) entities().add(bridge);
        invalidate();
        return "Chamfer = " + fmt(setback) + " mm ساخته شد";
    }

    /** Creates a tangent circular fillet between two selected line directions. */
    public String filletSelectedLines(float radius) {
        if (radius <= 0f) return "شعاع Fillet باید بزرگ‌تر از صفر باشد";
        List<Object> pair = twoSelectedLines();
        if (pair == null) return "برای Fillet دقیقاً دو خط را انتخاب کن";
        LineData a = readLine(pair.get(0));
        LineData b = readLine(pair.get(1));
        PointF corner = infiniteIntersection(a, b);
        if (corner == null) return "روی خط‌های موازی Fillet ساخته نمی‌شود";

        Branch ba = keptBranch(a, corner);
        Branch bb = keptBranch(b, corner);
        float dot = clamp(ba.u.x * bb.u.x + ba.u.y * bb.u.y, -1f, 1f);
        double theta = Math.acos(dot);
        double degrees = Math.toDegrees(theta);
        if (degrees < 5.0 || degrees > 175.0)
            return "زاویه دو خط برای Fillet مناسب نیست";

        float tangent = (float)(radius / Math.tan(theta / 2.0));
        if (tangent <= 0f || tangent >= ba.length || tangent >= bb.length)
            return "شعاع Fillet برای طول این خط‌ها بزرگ است";

        PointF pa = add(corner, mul(ba.u, tangent));
        PointF pb = add(corner, mul(bb.u, tangent));
        PointF bisector = unit(ba.u.x + bb.u.x, ba.u.y + bb.u.y);
        float centerDistance = (float)(radius / Math.sin(theta / 2.0));
        PointF center = add(corner, mul(bisector, centerDistance));

        float start = angleDeg(center, pa);
        float end = angleDeg(center, pb);
        float sweep = shortestSweep(start, end);

        saveUndo();
        setEndpoint(pair.get(0), ba.nearIndex, pa);
        setEndpoint(pair.get(1), bb.nearIndex, pb);
        Object arc = newArc(center.x, center.y, radius, start, sweep, pair.get(0));
        if (arc != null) entities().add(arc);
        invalidate();
        return "Fillet R " + fmt(radius) + " mm ساخته شد";
    }

    /** Joins two almost-collinear lines that touch or have only a small gap. */
    public String joinSelectedLines() {
        List<Object> pair = twoSelectedLines();
        if (pair == null) return "برای Join دقیقاً دو خط را انتخاب کن";
        LineData a = readLine(pair.get(0));
        LineData b = readLine(pair.get(1));
        if (a == null || b == null) return "فقط دو Line قابل Join هستند";

        PointF da = unit(a.x2-a.x1, a.y2-a.y1);
        PointF db = unit(b.x2-b.x1, b.y2-b.y1);
        float cross = Math.abs(da.x*db.y-da.y*db.x);
        if (cross > 0.025f) return "دو خط هم‌راستا نیستند";

        EndpointPair nearest = nearestEndpointPair(a, b);
        if (nearest.distance > 5f) return "فاصله دو خط برای Join بیشتر از 5 mm است";

        PointF farA = nearest.aIndex == 0 ? new PointF(a.x2,a.y2) : new PointF(a.x1,a.y1);
        PointF farB = nearest.bIndex == 0 ? new PointF(b.x2,b.y2) : new PointF(b.x1,b.y1);

        saveUndo();
        writeLine(pair.get(0), farA.x, farA.y, farB.x, farB.y);
        entities().remove(pair.get(1));
        List<Object> one = new ArrayList<>();
        one.add(pair.get(0));
        setSmartSelection(one);
        invalidate();
        return "دو خط Join شدند";
    }

    @Override
    public String executeCommand(String raw) {
        if (raw == null) return "";
        String s = normalizeDigits(raw).trim().replace(',', ' ');
        if (s.isEmpty()) return "";
        String[] a = s.split("\\s+");
        String cmd = a[0].toUpperCase(Locale.US);
        try {
            if ("TR".equals(cmd) || "TRIM".equals(cmd)) return trimSelectedLines();
            if ("EX".equals(cmd) || "EXTEND".equals(cmd)) return extendSelectedLines();
            if ("F".equals(cmd) || "FILLET".equals(cmd)) {
                if (a.length < 2) return "شعاع Fillet را وارد کن";
                return filletSelectedLines(Float.parseFloat(a[1]));
            }
            if ("CHA".equals(cmd) || "CHAMFER".equals(cmd)) {
                if (a.length < 2) return "فاصله Chamfer را وارد کن";
                return chamferSelectedLines(Float.parseFloat(a[1]));
            }
            if ("J".equals(cmd) || "JOIN".equals(cmd)) return joinSelectedLines();
        } catch (Exception e) {
            return "فرمت عدد درست نیست";
        }
        return super.executeCommand(s);
    }

    private boolean hasExactlyTwoLines() {
        List<Object> pair = selectedObjects();
        return pair.size() == 2 && isLine(pair.get(0)) && isLine(pair.get(1));
    }

    private List<Object> twoSelectedLines() {
        List<Object> s = selectedObjects();
        if (s.size() != 2 || !isLine(s.get(0)) || !isLine(s.get(1))) return null;
        return new ArrayList<>(s);
    }

    private boolean isLine(Object e) {
        return e != null && "LineEntity".equals(e.getClass().getSimpleName());
    }

    @SuppressWarnings("unchecked")
    private List<Object> selectedObjects() {
        try {
            if (smartSelectedField == null) return new ArrayList<>();
            return (List<Object>) smartSelectedField.get(this);
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    @SuppressWarnings("unchecked")
    private List<Object> entities() {
        try {
            if (entitiesField == null) return new ArrayList<>();
            return (List<Object>) entitiesField.get(this);
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private void saveUndo() {
        try { if (saveUndoMethod != null) saveUndoMethod.invoke(this); }
        catch (Exception ignored) {}
    }

    private void setSmartSelection(List<Object> list) {
        List<Object> smart = selectedObjects();
        smart.clear();
        smart.addAll(list);
        try {
            if (baseSelectedField != null) baseSelectedField.set(this, list.size()==1 ? list.get(0) : null);
        } catch (Exception ignored) {}
    }

    private LineData readLine(Object line) {
        if (!isLine(line)) return null;
        try {
            return new LineData(getFloat(line,"x1"), getFloat(line,"y1"),
                    getFloat(line,"x2"), getFloat(line,"y2"));
        } catch (Exception e) {
            return null;
        }
    }

    private void writeLine(Object line, float x1,float y1,float x2,float y2) {
        setFloat(line,"x1",x1); setFloat(line,"y1",y1);
        setFloat(line,"x2",x2); setFloat(line,"y2",y2);
    }

    private void setEndpoint(Object line, int index, PointF p) {
        if (index == 0) { setFloat(line,"x1",p.x); setFloat(line,"y1",p.y); }
        else { setFloat(line,"x2",p.x); setFloat(line,"y2",p.y); }
    }

    private void moveNearestEndpoint(Object line, LineData d, PointF p) {
        float d1 = dist(d.x1,d.y1,p.x,p.y);
        float d2 = dist(d.x2,d.y2,p.x,p.y);
        setEndpoint(line, d1 <= d2 ? 0 : 1, p);
    }

    private void keepFarSideAt(Object line, LineData d, PointF p) {
        moveNearestEndpoint(line,d,p);
    }

    private Branch keptBranch(LineData d, PointF corner) {
        float d1 = dist(d.x1,d.y1,corner.x,corner.y);
        float d2 = dist(d.x2,d.y2,corner.x,corner.y);
        if (d1 <= d2) {
            PointF u = unit(d.x2-corner.x, d.y2-corner.y);
            return new Branch(0,u,d2);
        } else {
            PointF u = unit(d.x1-corner.x, d.y1-corner.y);
            return new Branch(1,u,d1);
        }
    }

    private Object newLine(float x1,float y1,float x2,float y2,Object metaFrom) {
        try {
            Class<?> c = Class.forName("ir.chobyar.sketch.CadCanvasView$LineEntity");
            Constructor<?> ctor = c.getDeclaredConstructor(float.class,float.class,float.class,float.class);
            ctor.setAccessible(true);
            Object e = ctor.newInstance(x1,y1,x2,y2);
            copyMeta(metaFrom,e);
            return e;
        } catch (Exception e) {
            return null;
        }
    }

    private Object newArc(float x,float y,float r,float start,float sweep,Object metaFrom) {
        try {
            Class<?> c = Class.forName("ir.chobyar.sketch.CadCanvasView$ArcEntity");
            Constructor<?> ctor = c.getDeclaredConstructor(float.class,float.class,float.class,float.class,float.class);
            ctor.setAccessible(true);
            Object e = ctor.newInstance(x,y,r,start,sweep);
            copyMeta(metaFrom,e);
            return e;
        } catch (Exception e) {
            return null;
        }
    }

    private void copyMeta(Object from,Object to) {
        Object layer = call(from,"getLayer");
        Object color = call(from,"getColor");
        if (layer instanceof String) call(to,"setLayer",new Class<?>[]{String.class},layer);
        if (color instanceof Number) call(to,"setColor",new Class<?>[]{int.class},((Number)color).intValue());
    }

    private static PointF infiniteIntersection(LineData a,LineData b) {
        if (a==null||b==null) return null;
        float den=(a.x1-a.x2)*(b.y1-b.y2)-(a.y1-a.y2)*(b.x1-b.x2);
        if (Math.abs(den)<1e-6f) return null;
        float c1=a.x1*a.y2-a.y1*a.x2;
        float c2=b.x1*b.y2-b.y1*b.x2;
        float x=(c1*(b.x1-b.x2)-(a.x1-a.x2)*c2)/den;
        float y=(c1*(b.y1-b.y2)-(a.y1-a.y2)*c2)/den;
        return new PointF(x,y);
    }

    private static boolean pointOnSegment(PointF p,LineData l,float eps) {
        float len=dist(l.x1,l.y1,l.x2,l.y2);
        float sum=dist(l.x1,l.y1,p.x,p.y)+dist(p.x,p.y,l.x2,l.y2);
        return Math.abs(sum-len)<=Math.max(eps,len*0.0005f);
    }

    private static EndpointPair nearestEndpointPair(LineData a,LineData b) {
        PointF[] aa={new PointF(a.x1,a.y1),new PointF(a.x2,a.y2)};
        PointF[] bb={new PointF(b.x1,b.y1),new PointF(b.x2,b.y2)};
        EndpointPair best=new EndpointPair(0,0,Float.MAX_VALUE);
        for(int i=0;i<2;i++)for(int j=0;j<2;j++){
            float d=dist(aa[i].x,aa[i].y,bb[j].x,bb[j].y);
            if(d<best.distance)best=new EndpointPair(i,j,d);
        }
        return best;
    }

    private static PointF unit(float x,float y) {
        float l=(float)Math.hypot(x,y);
        return l<1e-7f?new PointF(1,0):new PointF(x/l,y/l);
    }
    private static PointF add(PointF a,PointF b){return new PointF(a.x+b.x,a.y+b.y);}
    private static PointF mul(PointF a,float k){return new PointF(a.x*k,a.y*k);}
    private static float dist(float x1,float y1,float x2,float y2){return(float)Math.hypot(x2-x1,y2-y1);}
    private static float clamp(float v,float lo,float hi){return Math.max(lo,Math.min(hi,v));}
    private static float angleDeg(PointF c,PointF p){return(float)Math.toDegrees(Math.atan2(p.y-c.y,p.x-c.x));}
    private static float shortestSweep(float start,float end){float d=end-start;while(d>180)d-=360;while(d<-180)d+=360;return d;}
    private static String fmt(float v){return String.format(Locale.US,"%.1f",v);}

    private static String normalizeDigits(String s) {
        StringBuilder b=new StringBuilder();
        for(int i=0;i<s.length();i++){
            char c=s.charAt(i);
            if(c>='۰'&&c<='۹')b.append((char)('0'+c-'۰'));
            else if(c>='٠'&&c<='٩')b.append((char)('0'+c-'٠'));
            else b.append(c);
        }
        return b.toString();
    }

    private static Field findField(Class<?> c,String name) {
        Class<?> x=c;
        while(x!=null){
            try{Field f=x.getDeclaredField(name);f.setAccessible(true);return f;}
            catch(Exception e){x=x.getSuperclass();}
        }
        return null;
    }

    private static float getFloat(Object o,String name) throws Exception {
        Field f=findField(o.getClass(),name);
        if(f==null)throw new NoSuchFieldException(name);
        return ((Number)f.get(o)).floatValue();
    }

    private static void setFloat(Object o,String name,float value) {
        try{Field f=findField(o.getClass(),name);if(f!=null)f.setFloat(o,value);}catch(Exception ignored){}
    }

    private static Object call(Object target,String name){return call(target,name,new Class<?>[0]);}
    private static Object call(Object target,String name,Class<?>[] types,Object...args){
        if(target==null)return null;
        Class<?> c=target.getClass();
        while(c!=null){
            try{Method m=c.getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(target,args);}
            catch(NoSuchMethodException e){c=c.getSuperclass();}
            catch(Exception e){return null;}
        }
        return null;
    }

    private void showResult(String s) {
        Toast.makeText(getContext(),s,Toast.LENGTH_SHORT).show();
        invalidate();
    }

    private static class LineData {
        final float x1,y1,x2,y2;
        LineData(float x1,float y1,float x2,float y2){this.x1=x1;this.y1=y1;this.x2=x2;this.y2=y2;}
    }
    private static class Branch {
        final int nearIndex; final PointF u; final float length;
        Branch(int nearIndex,PointF u,float length){this.nearIndex=nearIndex;this.u=u;this.length=length;}
    }
    private static class EndpointPair {
        final int aIndex,bIndex; final float distance;
        EndpointPair(int aIndex,int bIndex,float distance){this.aIndex=aIndex;this.bIndex=bIndex;this.distance=distance;}
    }
}

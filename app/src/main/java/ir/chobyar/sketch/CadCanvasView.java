package ir.chobyar.sketch;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class CadCanvasView extends View {

    public static final int TOOL_SELECT = 0;
    public static final int TOOL_POINT = 1;
    public static final int TOOL_LINE = 2;
    public static final int TOOL_RECT = 3;
    public static final int TOOL_CIRCLE = 4;
    public static final int TOOL_MEASURE = 5;
    public static final int TOOL_ARC = 6;

    private static final float PX_PER_MM = 3f;
    private static final float GRID_MM = 10f;
    private static final float SNAP_RADIUS_PX = 22f;

    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint axisXPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint axisYPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint entityPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint selectedPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint measurePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint snapPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final List<Entity> entities = new ArrayList<>();
    private final List<Entity> undoSnapshot = new ArrayList<>();
    private Entity selected;

    private int tool = TOOL_LINE;
    private boolean showGrid = true;
    private boolean showAxes = true;
    private boolean snapEnabled = true;
    private boolean orthoEnabled = false;

    private final ScaleGestureDetector scaleDetector;
    private float viewScale = 1f;
    private float offsetX = 120f;
    private float offsetY = 160f;
    private boolean multiTouch = false;
    private float lastMultiX;
    private float lastMultiY;

    private float startX, startY, endX, endY;
    private boolean drawing = false;
    private float snapX, snapY;
    private boolean snapVisible = false;

    public CadCanvasView(Context context) {
        super(context);
        setBackgroundColor(Color.rgb(250, 250, 250));

        gridPaint.setColor(Color.rgb(225, 225, 225));
        axisXPaint.setColor(Color.rgb(210, 55, 55));
        axisYPaint.setColor(Color.rgb(55, 150, 75));
        entityPaint.setColor(Color.rgb(25, 25, 25));
        selectedPaint.setColor(Color.rgb(35, 105, 225));
        measurePaint.setColor(Color.rgb(210, 85, 35));
        textPaint.setColor(Color.rgb(35, 85, 180));
        textPaint.setTextAlign(Paint.Align.CENTER);
        snapPaint.setColor(Color.rgb(245, 145, 20));
        snapPaint.setStyle(Paint.Style.STROKE);

        entityPaint.setStyle(Paint.Style.STROKE);
        selectedPaint.setStyle(Paint.Style.STROKE);
        measurePaint.setStyle(Paint.Style.STROKE);

        scaleDetector = new ScaleGestureDetector(context,
                new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override
                    public boolean onScale(ScaleGestureDetector detector) {
                        float oldScale = viewScale;
                        viewScale *= detector.getScaleFactor();
                        viewScale = clamp(viewScale, 0.15f, 12f);
                        float ratio = viewScale / oldScale;
                        float fx = detector.getFocusX();
                        float fy = detector.getFocusY();
                        offsetX = fx - (fx - offsetX) * ratio;
                        offsetY = fy - (fy - offsetY) * ratio;
                        invalidate();
                        return true;
                    }
                });
    }

    public void setTool(int newTool) {
        tool = newTool;
        drawing = false;
        snapVisible = false;
        invalidate();
    }

    public int getTool() { return tool; }
    public boolean isShowAxes() { return showAxes; }
    public boolean isShowGrid() { return showGrid; }
    public boolean isSnapEnabled() { return snapEnabled; }
    public boolean isOrthoEnabled() { return orthoEnabled; }

    public void toggleAxes() { showAxes = !showAxes; invalidate(); }
    public void toggleGrid() { showGrid = !showGrid; invalidate(); }
    public void toggleSnap() { snapEnabled = !snapEnabled; invalidate(); }
    public void toggleOrtho() { orthoEnabled = !orthoEnabled; invalidate(); }

    public void clearAll() {
        saveUndo();
        entities.clear();
        selected = null;
        invalidate();
    }

    public void undo() {
        if (undoSnapshot.isEmpty()) return;
        entities.clear();
        for (Entity e : undoSnapshot) entities.add(e.copy());
        undoSnapshot.clear();
        selected = null;
        invalidate();
    }

    public void deleteSelected() {
        if (selected == null) return;
        saveUndo();
        entities.remove(selected);
        selected = null;
        invalidate();
    }

    public void copySelected(float dx, float dy) {
        if (selected == null) return;
        saveUndo();
        Entity c = selected.copy();
        c.translate(dx, dy);
        entities.add(c);
        selected = c;
        invalidate();
    }

    public void moveSelected(float dx, float dy) {
        if (selected == null) return;
        saveUndo();
        selected.translate(dx, dy);
        invalidate();
    }

    public void offsetSelected(float distance) {
        if (!(selected instanceof LineEntity)) return;
        saveUndo();
        LineEntity l = (LineEntity) selected;
        float dx = l.x2 - l.x1;
        float dy = l.y2 - l.y1;
        float len = (float)Math.hypot(dx, dy);
        if (len < 0.001f) return;
        float nx = -dy / len * distance;
        float ny = dx / len * distance;
        LineEntity o = new LineEntity(l.x1 + nx, l.y1 + ny, l.x2 + nx, l.y2 + ny);
        entities.add(o);
        selected = o;
        invalidate();
    }

    public void fitAll() {
        if (entities.isEmpty()) {
            viewScale = 1f;
            offsetX = getWidth() * 0.25f;
            offsetY = getHeight() * 0.25f;
            invalidate();
            return;
        }
        RectF all = new RectF();
        boolean first = true;
        for (Entity e : entities) {
            RectF b = e.bounds();
            if (first) { all.set(b); first = false; }
            else all.union(b);
        }
        float w = Math.max(20f, all.width());
        float h = Math.max(20f, all.height());
        float sx = getWidth() / (w * PX_PER_MM * 1.25f);
        float sy = getHeight() / (h * PX_PER_MM * 1.25f);
        viewScale = clamp(Math.min(sx, sy), 0.15f, 12f);
        float cx = (all.left + all.right) / 2f;
        float cy = (all.top + all.bottom) / 2f;
        offsetX = getWidth() / 2f - cx * PX_PER_MM * viewScale;
        offsetY = getHeight() / 2f - cy * PX_PER_MM * viewScale;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.save();
        canvas.translate(offsetX, offsetY);
        canvas.scale(PX_PER_MM * viewScale, PX_PER_MM * viewScale);

        float px = 1f / (PX_PER_MM * viewScale);
        gridPaint.setStrokeWidth(px);
        axisXPaint.setStrokeWidth(2.2f * px);
        axisYPaint.setStrokeWidth(2.2f * px);
        entityPaint.setStrokeWidth(2.4f * px);
        selectedPaint.setStrokeWidth(4f * px);
        measurePaint.setStrokeWidth(2f * px);
        snapPaint.setStrokeWidth(2f * px);
        textPaint.setTextSize(28f * px);

        float left = screenToWorldX(0);
        float right = screenToWorldX(getWidth());
        float top = screenToWorldY(0);
        float bottom = screenToWorldY(getHeight());

        if (showGrid) drawGrid(canvas, left, top, right, bottom);
        if (showAxes) drawAxes(canvas, left, top, right, bottom, px);

        for (Entity e : entities) {
            e.draw(canvas, e == selected ? selectedPaint : entityPaint, textPaint, measurePaint, px);
        }

        if (drawing) drawPreview(canvas, px);
        if (snapVisible) drawSnapMarker(canvas, snapX, snapY, px);

        canvas.restore();
    }

    private void drawGrid(Canvas c, float left, float top, float right, float bottom) {
        float gx = (float)Math.floor(left / GRID_MM) * GRID_MM;
        float gy = (float)Math.floor(top / GRID_MM) * GRID_MM;
        for (float x = gx; x <= right; x += GRID_MM) c.drawLine(x, top, x, bottom, gridPaint);
        for (float y = gy; y <= bottom; y += GRID_MM) c.drawLine(left, y, right, y, gridPaint);
    }

    private void drawAxes(Canvas c, float left, float top, float right, float bottom, float px) {
        c.drawLine(left, 0, right, 0, axisXPaint);
        c.drawLine(0, top, 0, bottom, axisYPaint);
        Paint label = textPaint;
        label.setTextSize(24f * px);
        label.setTextAlign(Paint.Align.LEFT);
        c.drawText("X", right - 14f * px, -5f * px, axisXPaint);
        c.drawText("Y", 5f * px, top + 18f * px, axisYPaint);
        c.drawCircle(0, 0, 4f * px, axisXPaint);
        label.setTextAlign(Paint.Align.CENTER);
    }

    private void drawPreview(Canvas c, float px) {
        Paint p = tool == TOOL_MEASURE ? measurePaint : selectedPaint;
        if (tool == TOOL_LINE || tool == TOOL_MEASURE) {
            c.drawLine(startX, startY, endX, endY, p);
            drawLength(c, startX, startY, endX, endY, textPaint, px);
        } else if (tool == TOOL_RECT) {
            c.drawRect(Math.min(startX,endX), Math.min(startY,endY), Math.max(startX,endX), Math.max(startY,endY), p);
            drawRectDims(c, startX,startY,endX,endY,textPaint,px);
        } else if (tool == TOOL_CIRCLE) {
            float r = dist(startX,startY,endX,endY);
            c.drawCircle(startX,startY,r,p);
            c.drawText("R " + mm(r), startX, startY-r-6f*px, textPaint);
        } else if (tool == TOOL_ARC) {
            float r = dist(startX,startY,endX,endY);
            RectF b = new RectF(startX-r,startY-r,startX+r,startY+r);
            c.drawArc(b, 180, 180, false, p);
        }
    }

    private void drawSnapMarker(Canvas c, float x, float y, float px) {
        float s = 7f * px;
        c.drawRect(x-s, y-s, x+s, y+s, snapPaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        scaleDetector.onTouchEvent(event);

        if (event.getPointerCount() >= 2) {
            float mx = (event.getX(0)+event.getX(1))/2f;
            float my = (event.getY(0)+event.getY(1))/2f;
            if (!multiTouch) {
                multiTouch = true;
                lastMultiX = mx; lastMultiY = my;
                drawing = false;
            } else if (!scaleDetector.isInProgress()) {
                offsetX += mx-lastMultiX;
                offsetY += my-lastMultiY;
                lastMultiX = mx; lastMultiY = my;
                invalidate();
            }
            return true;
        }

        if (multiTouch) {
            if (event.getActionMasked() == MotionEvent.ACTION_UP || event.getActionMasked() == MotionEvent.ACTION_POINTER_UP) multiTouch = false;
            return true;
        }

        float[] p = snapPoint(screenToWorldX(event.getX()), screenToWorldY(event.getY()));
        float x = p[0], y = p[1];

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (tool == TOOL_SELECT) {
                    selected = findHit(x, y);
                    invalidate();
                    return true;
                }
                if (tool == TOOL_POINT) {
                    saveUndo();
                    entities.add(new PointEntity(x,y));
                    invalidate();
                    return true;
                }
                startX=x; startY=y; endX=x; endY=y; drawing=true; invalidate(); return true;

            case MotionEvent.ACTION_MOVE:
                if (!drawing) return true;
                endX=x; endY=y;
                applyOrtho();
                invalidate(); return true;

            case MotionEvent.ACTION_UP:
                if (!drawing) return true;
                endX=x; endY=y;
                applyOrtho();
                saveUndo();
                if (tool == TOOL_LINE) entities.add(new LineEntity(startX,startY,endX,endY));
                else if (tool == TOOL_RECT) entities.add(new RectEntity(startX,startY,endX,endY));
                else if (tool == TOOL_CIRCLE) entities.add(new CircleEntity(startX,startY,dist(startX,startY,endX,endY)));
                else if (tool == TOOL_MEASURE) entities.add(new MeasureEntity(startX,startY,endX,endY));
                else if (tool == TOOL_ARC) entities.add(new ArcEntity(startX,startY,dist(startX,startY,endX,endY),180,180));
                drawing=false; invalidate(); return true;

            case MotionEvent.ACTION_CANCEL:
                drawing=false; invalidate(); return true;
        }
        return true;
    }

    private void applyOrtho() {
        if (!orthoEnabled || (tool != TOOL_LINE && tool != TOOL_MEASURE)) return;
        if (Math.abs(endX-startX) >= Math.abs(endY-startY)) endY = startY;
        else endX = startX;
    }

    private float[] snapPoint(float x, float y) {
        snapVisible = false;
        if (!snapEnabled) return new float[]{x,y};
        float radius = SNAP_RADIUS_PX / (PX_PER_MM * viewScale);
        SnapCandidate best = null;
        for (Entity e : entities) {
            for (float[] q : e.snapPoints()) {
                float d = dist(x,y,q[0],q[1]);
                if (d <= radius && (best == null || d < best.d)) best = new SnapCandidate(q[0],q[1],d);
            }
        }
        if (best != null) {
            snapX=best.x; snapY=best.y; snapVisible=true;
            return new float[]{best.x,best.y};
        }
        float gx = Math.round(x / GRID_MM) * GRID_MM;
        float gy = Math.round(y / GRID_MM) * GRID_MM;
        if (dist(x,y,gx,gy) <= radius*0.7f) {
            snapX=gx; snapY=gy; snapVisible=true;
            return new float[]{gx,gy};
        }
        return new float[]{x,y};
    }

    private Entity findHit(float x, float y) {
        float tol = 18f/(PX_PER_MM*viewScale);
        Entity best=null; float bd=Float.MAX_VALUE;
        for (int i=entities.size()-1;i>=0;i--) {
            Entity e=entities.get(i);
            float d=e.hitDistance(x,y);
            if(d<tol && d<bd){best=e;bd=d;}
        }
        return best;
    }

    private void saveUndo() {
        undoSnapshot.clear();
        for (Entity e : entities) undoSnapshot.add(e.copy());
    }

    private float screenToWorldX(float sx) { return (sx-offsetX)/(PX_PER_MM*viewScale); }
    private float screenToWorldY(float sy) { return (sy-offsetY)/(PX_PER_MM*viewScale); }

    public String executeCommand(String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        if (s.isEmpty()) return "";
        String[] a = s.replace(',', ' ').trim().split("\\s+");
        String cmd = a[0].toUpperCase(Locale.US);
        try {
            switch (cmd) {
                case "L": case "LINE":
                    require(a,5); saveUndo(); entities.add(new LineEntity(f(a,1),f(a,2),f(a,3),f(a,4))); invalidate(); return "خط ساخته شد";
                case "REC": case "RECT": case "RECTANG":
                    require(a,5); saveUndo(); float x=f(a,1),y=f(a,2),w=f(a,3),h=f(a,4); entities.add(new RectEntity(x,y,x+w,y+h)); invalidate(); return "مستطیل ساخته شد";
                case "C": case "CIRCLE":
                    require(a,4); saveUndo(); entities.add(new CircleEntity(f(a,1),f(a,2),Math.abs(f(a,3)))); invalidate(); return "دایره ساخته شد";
                case "PO": case "POINT":
                    require(a,3); saveUndo(); entities.add(new PointEntity(f(a,1),f(a,2))); invalidate(); return "نقطه ساخته شد";
                case "A": case "ARC":
                    require(a,6); saveUndo(); entities.add(new ArcEntity(f(a,1),f(a,2),Math.abs(f(a,3)),f(a,4),f(a,5))); invalidate(); return "قوس ساخته شد";
                case "M": case "MOVE": require(a,3); moveSelected(f(a,1),f(a,2)); return selected==null?"اول شکل را انتخاب کن":"جابه‌جا شد";
                case "CO": case "COPY": require(a,3); copySelected(f(a,1),f(a,2)); return selected==null?"اول شکل را انتخاب کن":"کپی شد";
                case "O": case "OFFSET": require(a,2); offsetSelected(f(a,1)); return selected instanceof LineEntity?"آفست ساخته شد":"فعلاً Offset روی خط فعال است";
                case "ERASE": case "DELETE": deleteSelected(); return "حذف شد";
                case "U": case "UNDO": undo(); return "Undo";
                case "Z": case "ZOOM": case "FIT": fitAll(); return "نمایش Fit شد";
                case "AXIS": toggleAxes(); return showAxes?"محورها روشن":"محورها خاموش";
                case "GRID": toggleGrid(); return showGrid?"Grid روشن":"Grid خاموش";
                case "SNAP": toggleSnap(); return snapEnabled?"Snap روشن":"Snap خاموش";
                case "ORTHO": toggleOrtho(); return orthoEnabled?"Ortho روشن":"Ortho خاموش";
                case "CLEAR": clearAll(); return "صفحه پاک شد";
                case "SELECT": setTool(TOOL_SELECT); return "حالت انتخاب";
                case "DIM": setTool(TOOL_MEASURE); return "حالت اندازه‌گذاری";
                case "PL": case "PLINE": return "Polyline در مرحله بعدی موتور Sketch اضافه می‌شود";
                case "TR": case "TRIM": return "Trim در صف توسعه هندسه است";
                case "EX": case "EXTEND": return "Extend در صف توسعه هندسه است";
                case "F": case "FILLET": return "Fillet در صف توسعه هندسه است";
                case "CHA": case "CHAMFER": return "Chamfer در صف توسعه هندسه است";
                case "MI": case "MIRROR": return "Mirror در صف توسعه هندسه است";
                case "RO": case "ROTATE": return "Rotate در صف توسعه هندسه است";
                case "SC": case "SCALE": return "Scale در صف توسعه هندسه است";
                case "AR": case "ARRAY": return "Array در صف توسعه هندسه است";
                case "J": case "JOIN": return "Join در صف توسعه هندسه است";
                case "BR": case "BREAK": return "Break در صف توسعه هندسه است";
                case "H": case "HATCH": return "Hatch در صف توسعه ترسیم است";
                case "LA": case "LAYER": return "Layer manager در مرحله UI بعدی اضافه می‌شود";
                case "EXTRUDE": case "REVOLVE": case "LOFT": case "SWEEP": case "SHELL": case "UNION": case "SUBTRACT": case "INTERSECT": case "PROJECT":
                    return "فرمان سه‌بعدی ثبت شد؛ برای اجرای واقعی نیاز به هسته مدل‌سازی 3D داریم";
                default: return "فرمان ناشناخته: " + cmd;
            }
        } catch (Exception ex) {
            return "فرمت فرمان درست نیست";
        }
    }

    public String buildDxf() {
        StringBuilder d = new StringBuilder();
        d.append("0\nSECTION\n2\nHEADER\n0\nENDSEC\n0\nSECTION\n2\nENTITIES\n");
        for (Entity e:entities) e.appendDxf(d);
        d.append("0\nENDSEC\n0\nEOF\n");
        return d.toString();
    }

    private static void require(String[] a,int n){if(a.length<n)throw new IllegalArgumentException();}
    private static float f(String[] a,int i){return Float.parseFloat(a[i]);}
    private static float clamp(float v,float min,float max){return Math.max(min,Math.min(max,v));}
    private static float dist(float x1,float y1,float x2,float y2){return(float)Math.hypot(x2-x1,y2-y1);}
    private static String mm(float v){return String.format(Locale.US,"%.1f mm",v);}

    private static void drawLength(Canvas c,float x1,float y1,float x2,float y2,Paint t,float px){c.drawText(mm(dist(x1,y1,x2,y2)),(x1+x2)/2f,(y1+y2)/2f-4f*px,t);}
    private static void drawRectDims(Canvas c,float x1,float y1,float x2,float y2,Paint t,float px){float l=Math.min(x1,x2),r=Math.max(x1,x2),top=Math.min(y1,y2),b=Math.max(y1,y2);c.drawText(mm(r-l),(l+r)/2f,top-5f*px,t);c.drawText(mm(b-top),r+12f*px,(top+b)/2f,t);}
    private static float pointSeg(float px,float py,float x1,float y1,float x2,float y2){float dx=x2-x1,dy=y2-y1;float l2=dx*dx+dy*dy;if(l2<1e-6f)return dist(px,py,x1,y1);float t=((px-x1)*dx+(py-y1)*dy)/l2;t=clamp(t,0,1);return dist(px,py,x1+t*dx,y1+t*dy);}

    private static class SnapCandidate { float x,y,d; SnapCandidate(float x,float y,float d){this.x=x;this.y=y;this.d=d;} }

    private interface Entity {
        void draw(Canvas c,Paint p,Paint text,Paint measure,float px);
        float hitDistance(float x,float y);
        List<float[]> snapPoints();
        void translate(float dx,float dy);
        Entity copy();
        RectF bounds();
        void appendDxf(StringBuilder d);
    }

    private static class PointEntity implements Entity {
        float x,y; PointEntity(float x,float y){this.x=x;this.y=y;}
        public void draw(Canvas c,Paint p,Paint t,Paint m,float px){c.drawCircle(x,y,4f*px,p);c.drawText(String.format(Locale.US,"(%.1f, %.1f)",x,y),x+18f*px,y-8f*px,t);}
        public float hitDistance(float a,float b){return dist(a,b,x,y);} public List<float[]> snapPoints(){List<float[]>q=new ArrayList<>();q.add(new float[]{x,y});return q;}
        public void translate(float dx,float dy){x+=dx;y+=dy;} public Entity copy(){return new PointEntity(x,y);} public RectF bounds(){return new RectF(x,y,x,y);}
        public void appendDxf(StringBuilder d){d.append("0\nPOINT\n8\n0\n10\n").append(x).append("\n20\n").append(-y).append("\n30\n0\n");}
    }

    private static class LineEntity implements Entity {
        float x1,y1,x2,y2; LineEntity(float x1,float y1,float x2,float y2){this.x1=x1;this.y1=y1;this.x2=x2;this.y2=y2;}
        public void draw(Canvas c,Paint p,Paint t,Paint m,float px){c.drawLine(x1,y1,x2,y2,p);drawLength(c,x1,y1,x2,y2,t,px);}
        public float hitDistance(float x,float y){return pointSeg(x,y,x1,y1,x2,y2);} public List<float[]> snapPoints(){List<float[]>q=new ArrayList<>();q.add(new float[]{x1,y1});q.add(new float[]{x2,y2});q.add(new float[]{(x1+x2)/2f,(y1+y2)/2f});return q;}
        public void translate(float dx,float dy){x1+=dx;x2+=dx;y1+=dy;y2+=dy;} public Entity copy(){return new LineEntity(x1,y1,x2,y2);} public RectF bounds(){return new RectF(Math.min(x1,x2),Math.min(y1,y2),Math.max(x1,x2),Math.max(y1,y2));}
        public void appendDxf(StringBuilder d){d.append("0\nLINE\n8\n0\n10\n").append(x1).append("\n20\n").append(-y1).append("\n30\n0\n11\n").append(x2).append("\n21\n").append(-y2).append("\n31\n0\n");}
    }

    private static class RectEntity implements Entity {
        float l,t,r,b; RectEntity(float x1,float y1,float x2,float y2){l=Math.min(x1,x2);r=Math.max(x1,x2);t=Math.min(y1,y2);b=Math.max(y1,y2);}
        public void draw(Canvas c,Paint p,Paint tx,Paint m,float px){c.drawRect(l,t,r,b,p);drawRectDims(c,l,t,r,b,tx,px);}
        public float hitDistance(float x,float y){return Math.min(Math.min(pointSeg(x,y,l,t,r,t),pointSeg(x,y,r,t,r,b)),Math.min(pointSeg(x,y,r,b,l,b),pointSeg(x,y,l,b,l,t)));}
        public List<float[]> snapPoints(){List<float[]>q=new ArrayList<>();q.add(new float[]{l,t});q.add(new float[]{r,t});q.add(new float[]{r,b});q.add(new float[]{l,b});q.add(new float[]{(l+r)/2,t});q.add(new float[]{(l+r)/2,b});q.add(new float[]{l,(t+b)/2});q.add(new float[]{r,(t+b)/2});q.add(new float[]{(l+r)/2,(t+b)/2});return q;}
        public void translate(float dx,float dy){l+=dx;r+=dx;t+=dy;b+=dy;} public Entity copy(){return new RectEntity(l,t,r,b);} public RectF bounds(){return new RectF(l,t,r,b);}
        public void appendDxf(StringBuilder d){new LineEntity(l,t,r,t).appendDxf(d);new LineEntity(r,t,r,b).appendDxf(d);new LineEntity(r,b,l,b).appendDxf(d);new LineEntity(l,b,l,t).appendDxf(d);}
    }

    private static class CircleEntity implements Entity {
        float x,y,r; CircleEntity(float x,float y,float r){this.x=x;this.y=y;this.r=r;}
        public void draw(Canvas c,Paint p,Paint t,Paint m,float px){c.drawCircle(x,y,r,p);c.drawText("Ø "+mm(r*2),x,y-r-6f*px,t);}
        public float hitDistance(float a,float b){return Math.abs(dist(a,b,x,y)-r);} public List<float[]> snapPoints(){List<float[]>q=new ArrayList<>();q.add(new float[]{x,y});q.add(new float[]{x+r,y});q.add(new float[]{x-r,y});q.add(new float[]{x,y+r});q.add(new float[]{x,y-r});return q;}
        public void translate(float dx,float dy){x+=dx;y+=dy;} public Entity copy(){return new CircleEntity(x,y,r);} public RectF bounds(){return new RectF(x-r,y-r,x+r,y+r);}
        public void appendDxf(StringBuilder d){d.append("0\nCIRCLE\n8\n0\n10\n").append(x).append("\n20\n").append(-y).append("\n30\n0\n40\n").append(r).append("\n");}
    }

    private static class ArcEntity implements Entity {
        float x,y,r,start,sweep; ArcEntity(float x,float y,float r,float start,float sweep){this.x=x;this.y=y;this.r=r;this.start=start;this.sweep=sweep;}
        public void draw(Canvas c,Paint p,Paint t,Paint m,float px){RectF b=new RectF(x-r,y-r,x+r,y+r);c.drawArc(b,start,sweep,false,p);c.drawText("R "+mm(r),x,y-r-5f*px,t);}
        public float hitDistance(float a,float b){return Math.abs(dist(a,b,x,y)-r);} public List<float[]> snapPoints(){List<float[]>q=new ArrayList<>();q.add(new float[]{x,y});q.add(new float[]{x+(float)Math.cos(Math.toRadians(start))*r,y+(float)Math.sin(Math.toRadians(start))*r});float e=start+sweep;q.add(new float[]{x+(float)Math.cos(Math.toRadians(e))*r,y+(float)Math.sin(Math.toRadians(e))*r});return q;}
        public void translate(float dx,float dy){x+=dx;y+=dy;} public Entity copy(){return new ArcEntity(x,y,r,start,sweep);} public RectF bounds(){return new RectF(x-r,y-r,x+r,y+r);}
        public void appendDxf(StringBuilder d){d.append("0\nARC\n8\n0\n10\n").append(x).append("\n20\n").append(-y).append("\n30\n0\n40\n").append(r).append("\n50\n").append(start).append("\n51\n").append(start+sweep).append("\n");}
    }

    private static class MeasureEntity implements Entity {
        float x1,y1,x2,y2; MeasureEntity(float x1,float y1,float x2,float y2){this.x1=x1;this.y1=y1;this.x2=x2;this.y2=y2;}
        public void draw(Canvas c,Paint p,Paint t,Paint m,float px){c.drawLine(x1,y1,x2,y2,m);drawLength(c,x1,y1,x2,y2,t,px);}
        public float hitDistance(float x,float y){return pointSeg(x,y,x1,y1,x2,y2);} public List<float[]> snapPoints(){List<float[]>q=new ArrayList<>();q.add(new float[]{x1,y1});q.add(new float[]{x2,y2});return q;}
        public void translate(float dx,float dy){x1+=dx;x2+=dx;y1+=dy;y2+=dy;} public Entity copy(){return new MeasureEntity(x1,y1,x2,y2);} public RectF bounds(){return new RectF(Math.min(x1,x2),Math.min(y1,y2),Math.max(x1,x2),Math.max(y1,y2));}
        public void appendDxf(StringBuilder d){}
    }
}

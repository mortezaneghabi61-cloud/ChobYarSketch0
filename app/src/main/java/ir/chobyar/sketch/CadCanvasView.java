package ir.chobyar.sketch;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PointF;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class CadCanvasView extends View {

    public static final int TOOL_SELECT = 0;
    public static final int TOOL_POINT = 1;
    public static final int TOOL_LINE = 2;
    public static final int TOOL_RECT = 3;
    public static final int TOOL_CIRCLE = 4;
    public static final int TOOL_MEASURE = 5;
    public static final int TOOL_ARC = 6;
    public static final int TOOL_POLYGON = 7;
    public static final int TOOL_FREE = 8;
    public static final int TOOL_GUIDE = 9;

    private static final float PX_PER_MM = 3f;
    private static final float GRID_MM = 10f;
    private static final float SNAP_RADIUS_PX = 22f;
    private static final int MAX_UNDO = 30;

    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint axisXPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint axisYPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint entityPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint selectedPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint measurePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint snapPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint guidePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hiddenInfoPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final List<Entity> entities = new ArrayList<>();
    private final ArrayDeque<List<Entity>> undoStack = new ArrayDeque<>();
    private final Map<String, Boolean> layers = new LinkedHashMap<>();
    private final Map<String, Scene> scenes = new HashMap<>();

    private Entity selected;
    private int tool = TOOL_LINE;
    private boolean showGrid = true;
    private boolean showAxes = true;
    private boolean showGuides = true;
    private boolean showDimensions = true;
    private boolean snapEnabled = true;
    private boolean orthoEnabled = false;

    private String currentLayer = "0";
    private int currentColor = Color.rgb(25, 25, 25);
    private int polygonSides = 6;

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
    private final List<PointF> freePoints = new ArrayList<>();

    public CadCanvasView(Context context) {
        super(context);
        setBackgroundColor(Color.rgb(250, 250, 250));
        setFocusable(true);

        layers.put("0", true);

        gridPaint.setColor(Color.rgb(225, 225, 225));
        axisXPaint.setColor(Color.rgb(210, 55, 55));
        axisYPaint.setColor(Color.rgb(55, 150, 75));
        entityPaint.setStyle(Paint.Style.STROKE);
        entityPaint.setStrokeCap(Paint.Cap.ROUND);
        entityPaint.setStrokeJoin(Paint.Join.ROUND);
        selectedPaint.setColor(Color.rgb(35, 105, 225));
        selectedPaint.setStyle(Paint.Style.STROKE);
        selectedPaint.setStrokeCap(Paint.Cap.ROUND);
        selectedPaint.setStrokeJoin(Paint.Join.ROUND);
        measurePaint.setColor(Color.rgb(210, 85, 35));
        measurePaint.setStyle(Paint.Style.STROKE);
        textPaint.setColor(Color.rgb(35, 85, 180));
        textPaint.setTextAlign(Paint.Align.CENTER);
        snapPaint.setColor(Color.rgb(245, 145, 20));
        snapPaint.setStyle(Paint.Style.STROKE);
        guidePaint.setColor(Color.rgb(65, 145, 200));
        guidePaint.setStyle(Paint.Style.STROKE);
        hiddenInfoPaint.setColor(Color.GRAY);
        hiddenInfoPaint.setTextAlign(Paint.Align.LEFT);

        scaleDetector = new ScaleGestureDetector(context,
                new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override
                    public boolean onScale(ScaleGestureDetector detector) {
                        float oldScale = viewScale;
                        viewScale *= detector.getScaleFactor();
                        viewScale = clamp(viewScale, 0.12f, 16f);
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
        freePoints.clear();
        invalidate();
    }

    public int getTool() { return tool; }
    public boolean isShowAxes() { return showAxes; }
    public boolean isShowGrid() { return showGrid; }
    public boolean isShowGuides() { return showGuides; }
    public boolean isShowDimensions() { return showDimensions; }
    public boolean isSnapEnabled() { return snapEnabled; }
    public boolean isOrthoEnabled() { return orthoEnabled; }
    public String getCurrentLayer() { return currentLayer; }

    public void toggleAxes() { showAxes = !showAxes; invalidate(); }
    public void toggleGrid() { showGrid = !showGrid; invalidate(); }
    public void toggleGuides() { showGuides = !showGuides; invalidate(); }
    public void toggleDimensions() { showDimensions = !showDimensions; invalidate(); }
    public void toggleSnap() { snapEnabled = !snapEnabled; invalidate(); }
    public void toggleOrtho() { orthoEnabled = !orthoEnabled; invalidate(); }

    public String selectedInfo() {
        if (selected == null) return "هیچ شکلی انتخاب نشده";
        return selected.describe();
    }

    public void clearAll() {
        saveUndo();
        entities.clear();
        selected = null;
        invalidate();
    }

    public void undo() {
        if (undoStack.isEmpty()) return;
        List<Entity> snapshot = undoStack.removeLast();
        entities.clear();
        for (Entity e : snapshot) entities.add(e.copy());
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

    public String offsetSelected(float distance) {
        if (selected == null) return "اول شکل را انتخاب کن";
        Entity o = selected.offsetCopy(distance);
        if (o == null) return "Offset برای این نوع شکل هنوز فعال نیست";
        saveUndo();
        copyMeta(selected, o);
        entities.add(o);
        selected = o;
        invalidate();
        return "Offset = " + mm(distance);
    }

    public String rotateSelected(float deg) {
        if (selected == null) return "اول شکل را انتخاب کن";
        saveUndo();
        PointF c = selected.center();
        selected.rotate(c.x, c.y, deg);
        invalidate();
        return "چرخش " + fmt(deg) + "°";
    }

    public String scaleSelected(float factor) {
        if (selected == null) return "اول شکل را انتخاب کن";
        if (factor <= 0f) return "Scale باید بزرگ‌تر از صفر باشد";
        saveUndo();
        PointF c = selected.center();
        selected.scale(c.x, c.y, factor);
        invalidate();
        return "Scale × " + fmt(factor);
    }

    public String mirrorSelected(boolean acrossXAxis, float axisValue) {
        if (selected == null) return "اول شکل را انتخاب کن";
        saveUndo();
        if (acrossXAxis) selected.mirrorHorizontal(axisValue);
        else selected.mirrorVertical(axisValue);
        invalidate();
        return acrossXAxis ? "قرینه نسبت به محور X" : "قرینه نسبت به محور Y";
    }

    public String arraySelected(int count, float dx, float dy) {
        if (selected == null) return "اول شکل را انتخاب کن";
        if (count < 2 || count > 200) return "تعداد Array باید بین 2 و 200 باشد";
        saveUndo();
        Entity seed = selected.copy();
        for (int i = 1; i < count; i++) {
            Entity c = seed.copy();
            c.translate(dx * i, dy * i);
            entities.add(c);
            selected = c;
        }
        invalidate();
        return "Array: " + count + " عدد";
    }

    public String applySelectedDimension(String raw) {
        if (selected == null) return "اول شکل را انتخاب کن";
        if (raw == null || raw.trim().isEmpty()) return "عدد وارد نشده";
        String[] a = raw.trim().replace('×', ' ').replace(',', ' ').split("\\s+");
        try {
            if (selected instanceof LineEntity) {
                float v = Math.abs(Float.parseFloat(a[0]));
                saveUndo();
                ((LineEntity) selected).setLength(v);
                invalidate();
                return "طول = " + mm(v);
            }
            if (selected instanceof RectEntity) {
                if (a.length < 2) return "برای مستطیل دو عدد وارد کن؛ مثال: 600 400";
                float w = Math.abs(Float.parseFloat(a[0]));
                float h = Math.abs(Float.parseFloat(a[1]));
                saveUndo();
                ((RectEntity) selected).setSize(w, h);
                invalidate();
                return "اندازه = " + mm(w) + " × " + mm(h);
            }
            if (selected instanceof CircleEntity) {
                float d = Math.abs(Float.parseFloat(a[0]));
                saveUndo();
                ((CircleEntity) selected).r = d / 2f;
                invalidate();
                return "قطر = " + mm(d);
            }
            if (selected instanceof ArcEntity) {
                float r = Math.abs(Float.parseFloat(a[0]));
                saveUndo();
                ((ArcEntity) selected).r = r;
                invalidate();
                return "شعاع = " + mm(r);
            }
            if (selected instanceof PolygonEntity) {
                float r = Math.abs(Float.parseFloat(a[0]));
                saveUndo();
                ((PolygonEntity) selected).setRadius(r);
                invalidate();
                return "شعاع چندضلعی = " + mm(r);
            }
            return "ویرایش عددی برای این نوع شکل تعریف نشده";
        } catch (Exception ex) {
            return "فرمت عدد درست نیست";
        }
    }

    public String setLayer(String name) {
        if (name == null || name.trim().isEmpty()) return "نام لایه خالی است";
        currentLayer = name.trim();
        layers.put(currentLayer, true);
        return "لایه جاری: " + currentLayer;
    }

    public String assignSelectedLayer(String name) {
        if (selected == null) return "اول شکل را انتخاب کن";
        if (name == null || name.trim().isEmpty()) return "نام لایه خالی است";
        layers.put(name.trim(), true);
        selected.setLayer(name.trim());
        invalidate();
        return "شکل به لایه " + name.trim() + " منتقل شد";
    }

    public String setLayerVisible(String name, boolean visible) {
        if (name == null || name.trim().isEmpty()) return "نام لایه خالی است";
        layers.put(name.trim(), visible);
        if (!visible && selected != null && name.trim().equals(selected.getLayer())) selected = null;
        invalidate();
        return "لایه " + name.trim() + (visible ? " روشن شد" : " مخفی شد");
    }

    public String setMaterial(String material) {
        int color = materialColor(material);
        if (selected != null) {
            selected.setColor(color);
            invalidate();
            return "متریال شکل: " + material;
        }
        currentColor = color;
        return "متریال ترسیم جدید: " + material;
    }

    public void fitAll() {
        RectF all = null;
        for (Entity e : entities) {
            if (!isVisible(e) || e.isConstruction()) continue;
            RectF b = e.bounds();
            if (all == null) all = new RectF(b);
            else all.union(b);
        }
        if (all == null) {
            viewScale = 1f;
            offsetX = getWidth() * 0.25f;
            offsetY = getHeight() * 0.25f;
            invalidate();
            return;
        }
        float w = Math.max(20f, all.width());
        float h = Math.max(20f, all.height());
        float sx = getWidth() / (w * PX_PER_MM * 1.25f);
        float sy = getHeight() / (h * PX_PER_MM * 1.25f);
        viewScale = clamp(Math.min(sx, sy), 0.12f, 16f);
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
        guidePaint.setStrokeWidth(1.4f * px);
        textPaint.setTextSize(27f * px);

        float left = screenToWorldX(0);
        float right = screenToWorldX(getWidth());
        float top = screenToWorldY(0);
        float bottom = screenToWorldY(getHeight());

        if (showGrid) drawGrid(canvas, left, top, right, bottom);
        if (showAxes) drawAxes(canvas, left, top, right, bottom, px);

        for (Entity e : entities) {
            if (!isVisible(e)) continue;
            if (e.isConstruction()) {
                if (showGuides) e.draw(canvas, guidePaint, textPaint, measurePaint, px, showDimensions);
                continue;
            }
            Paint p = e == selected ? selectedPaint : entityPaint;
            if (e != selected) p.setColor(e.getColor());
            e.draw(canvas, p, textPaint, measurePaint, px, showDimensions);
        }

        if (drawing) drawPreview(canvas, px);
        if (snapVisible) drawSnapMarker(canvas, snapX, snapY, px);

        canvas.restore();

        hiddenInfoPaint.setTextSize(24f);
        int hidden = hiddenEntityCount();
        if (hidden > 0) canvas.drawText("Hidden: " + hidden, 12, getHeight() - 16, hiddenInfoPaint);
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
        c.drawText("X", right - 14f * px, -5f * px, axisXPaint);
        c.drawText("Y", 5f * px, top + 18f * px, axisYPaint);
        c.drawCircle(0, 0, 4f * px, axisXPaint);
    }

    private void drawPreview(Canvas c, float px) {
        Paint p = tool == TOOL_MEASURE ? measurePaint : selectedPaint;
        if (tool == TOOL_LINE || tool == TOOL_MEASURE) {
            c.drawLine(startX, startY, endX, endY, p);
            if (showDimensions) drawLength(c, startX, startY, endX, endY, textPaint, px);
        } else if (tool == TOOL_RECT) {
            RectEntity r = new RectEntity(startX, startY, endX, endY);
            r.draw(c, p, textPaint, measurePaint, px, showDimensions);
        } else if (tool == TOOL_CIRCLE) {
            float r = dist(startX, startY, endX, endY);
            c.drawCircle(startX, startY, r, p);
            if (showDimensions) c.drawText("R " + mm(r), startX, startY-r-6f*px, textPaint);
        } else if (tool == TOOL_ARC) {
            float r = dist(startX, startY, endX, endY);
            RectF b = new RectF(startX-r, startY-r, startX+r, startY+r);
            c.drawArc(b, 180, 180, false, p);
        } else if (tool == TOOL_POLYGON) {
            PolygonEntity poly = PolygonEntity.regular(polygonSides, startX, startY, dist(startX,startY,endX,endY));
            poly.draw(c, p, textPaint, measurePaint, px, showDimensions);
        } else if (tool == TOOL_FREE && freePoints.size() > 1) {
            Path path = new Path();
            path.moveTo(freePoints.get(0).x, freePoints.get(0).y);
            for (int i = 1; i < freePoints.size(); i++) path.lineTo(freePoints.get(i).x, freePoints.get(i).y);
            c.drawPath(path, p);
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
                lastMultiX = mx;
                lastMultiY = my;
                drawing = false;
                freePoints.clear();
            } else if (!scaleDetector.isInProgress()) {
                offsetX += mx-lastMultiX;
                offsetY += my-lastMultiY;
                lastMultiX = mx;
                lastMultiY = my;
                invalidate();
            }
            return true;
        }

        if (multiTouch) {
            if (event.getActionMasked() == MotionEvent.ACTION_UP ||
                    event.getActionMasked() == MotionEvent.ACTION_POINTER_UP) multiTouch = false;
            return true;
        }

        float rawX = screenToWorldX(event.getX());
        float rawY = screenToWorldY(event.getY());
        float[] p = tool == TOOL_FREE ? new float[]{rawX, rawY} : snapPoint(rawX, rawY);
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
                    addPrepared(new PointEntity(x,y));
                    invalidate();
                    return true;
                }
                if (tool == TOOL_GUIDE) {
                    saveUndo();
                    addPrepared(new GuideEntity(true, x));
                    addPrepared(new GuideEntity(false, y));
                    invalidate();
                    return true;
                }
                startX=x;
                startY=y;
                endX=x;
                endY=y;
                drawing=true;
                if (tool == TOOL_FREE) {
                    freePoints.clear();
                    freePoints.add(new PointF(x,y));
                }
                invalidate();
                return true;

            case MotionEvent.ACTION_MOVE:
                if (!drawing) return true;
                endX=x;
                endY=y;
                applyOrtho();
                if (tool == TOOL_FREE) {
                    if (freePoints.isEmpty() || dist(freePoints.get(freePoints.size()-1).x,
                            freePoints.get(freePoints.size()-1).y, x, y) > 0.7f) {
                        freePoints.add(new PointF(x,y));
                    }
                }
                invalidate();
                return true;

            case MotionEvent.ACTION_UP:
                if (!drawing) return true;
                endX=x;
                endY=y;
                applyOrtho();
                saveUndo();
                if (tool == TOOL_LINE) addPrepared(new LineEntity(startX,startY,endX,endY));
                else if (tool == TOOL_RECT) addPrepared(new RectEntity(startX,startY,endX,endY));
                else if (tool == TOOL_CIRCLE) addPrepared(new CircleEntity(startX,startY,dist(startX,startY,endX,endY)));
                else if (tool == TOOL_MEASURE) addPrepared(new MeasureEntity(startX,startY,endX,endY));
                else if (tool == TOOL_ARC) addPrepared(new ArcEntity(startX,startY,dist(startX,startY,endX,endY),180,180));
                else if (tool == TOOL_POLYGON) addPrepared(PolygonEntity.regular(polygonSides,startX,startY,dist(startX,startY,endX,endY)));
                else if (tool == TOOL_FREE && freePoints.size() > 1) addPrepared(new PolylineEntity(freePoints, false));
                drawing=false;
                freePoints.clear();
                invalidate();
                return true;

            case MotionEvent.ACTION_CANCEL:
                drawing=false;
                freePoints.clear();
                invalidate();
                return true;
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
            if (!isVisible(e) || e.isConstruction()) continue;
            for (float[] q : e.snapPoints()) {
                float d = dist(x,y,q[0],q[1]);
                if (d <= radius && (best == null || d < best.d)) best = new SnapCandidate(q[0],q[1],d);
            }
        }
        if (best != null) {
            snapX=best.x;
            snapY=best.y;
            snapVisible=true;
            return new float[]{best.x,best.y};
        }
        float gx = Math.round(x / GRID_MM) * GRID_MM;
        float gy = Math.round(y / GRID_MM) * GRID_MM;
        if (dist(x,y,gx,gy) <= radius*0.7f) {
            snapX=gx;
            snapY=gy;
            snapVisible=true;
            return new float[]{gx,gy};
        }
        return new float[]{x,y};
    }

    private Entity findHit(float x, float y) {
        float tol = 18f/(PX_PER_MM*viewScale);
        Entity best=null;
        float bd=Float.MAX_VALUE;
        for (int i=entities.size()-1;i>=0;i--) {
            Entity e=entities.get(i);
            if (!isVisible(e) || e.isConstruction()) continue;
            float d=e.hitDistance(x,y);
            if(d<tol && d<bd){
                best=e;
                bd=d;
            }
        }
        return best;
    }

    private void saveUndo() {
        List<Entity> snapshot = new ArrayList<>();
        for (Entity e : entities) snapshot.add(e.copy());
        undoStack.addLast(snapshot);
        while (undoStack.size() > MAX_UNDO) undoStack.removeFirst();
    }

    private void addPrepared(Entity e) {
        e.setLayer(currentLayer);
        e.setColor(currentColor);
        entities.add(e);
    }

    private void copyMeta(Entity from, Entity to) {
        to.setLayer(from.getLayer());
        to.setColor(from.getColor());
    }

    private boolean isVisible(Entity e) {
        Boolean visible = layers.get(e.getLayer());
        return visible == null || visible;
    }

    private int hiddenEntityCount() {
        int n = 0;
        for (Entity e : entities) if (!isVisible(e)) n++;
        return n;
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
                case "L":
                case "LINE":
                    require(a,5);
                    saveUndo();
                    addPrepared(new LineEntity(f(a,1),f(a,2),f(a,3),f(a,4)));
                    invalidate();
                    return "خط ساخته شد";

                case "REC":
                case "RECT":
                case "RECTANG":
                    require(a,5);
                    saveUndo();
                    float rx=f(a,1), ry=f(a,2), rw=f(a,3), rh=f(a,4);
                    addPrepared(new RectEntity(rx,ry,rx+rw,ry+rh));
                    invalidate();
                    return "مستطیل " + mm(Math.abs(rw)) + " × " + mm(Math.abs(rh));

                case "C":
                case "CIRCLE":
                    require(a,4);
                    saveUndo();
                    addPrepared(new CircleEntity(f(a,1),f(a,2),Math.abs(f(a,3))));
                    invalidate();
                    return "دایره ساخته شد";

                case "PO":
                case "POINT":
                    require(a,3);
                    saveUndo();
                    addPrepared(new PointEntity(f(a,1),f(a,2)));
                    invalidate();
                    return "نقطه ساخته شد";

                case "A":
                case "ARC":
                    require(a,6);
                    saveUndo();
                    addPrepared(new ArcEntity(f(a,1),f(a,2),Math.abs(f(a,3)),f(a,4),f(a,5)));
                    invalidate();
                    return "قوس ساخته شد";

                case "POL":
                case "POLYGON":
                    require(a,5);
                    int sides = Math.max(3, Math.min(64, Integer.parseInt(a[1])));
                    saveUndo();
                    addPrepared(PolygonEntity.regular(sides,f(a,2),f(a,3),Math.abs(f(a,4))));
                    invalidate();
                    return "چندضلعی " + sides + " ضلعی ساخته شد";

                case "POLYSIDES":
                    require(a,2);
                    polygonSides = Math.max(3, Math.min(64, Integer.parseInt(a[1])));
                    return "تعداد ضلع ابزار چندضلعی: " + polygonSides;

                case "M":
                case "MOVE":
                    require(a,3);
                    if (selected == null) return "اول شکل را انتخاب کن";
                    moveSelected(f(a,1),f(a,2));
                    return "جابه‌جا شد";

                case "CO":
                case "COPY":
                    require(a,3);
                    if (selected == null) return "اول شکل را انتخاب کن";
                    copySelected(f(a,1),f(a,2));
                    return "کپی شد";

                case "O":
                case "OFFSET":
                    require(a,2);
                    return offsetSelected(f(a,1));

                case "RO":
                case "ROTATE":
                    require(a,2);
                    return rotateSelected(f(a,1));

                case "SC":
                case "SCALE":
                    require(a,2);
                    return scaleSelected(f(a,1));

                case "MI":
                case "MIRROR":
                    require(a,2);
                    if ("X".equalsIgnoreCase(a[1])) {
                        float axis = a.length >= 3 ? f(a,2) : 0f;
                        return mirrorSelected(true, axis);
                    }
                    if ("Y".equalsIgnoreCase(a[1])) {
                        float axis = a.length >= 3 ? f(a,2) : 0f;
                        return mirrorSelected(false, axis);
                    }
                    return "مثال: MIRROR X 0 یا MIRROR Y 0";

                case "AR":
                case "ARRAY":
                    require(a,4);
                    return arraySelected(Integer.parseInt(a[1]), f(a,2), f(a,3));

                case "LENGTH":
                    require(a,2);
                    return applySelectedDimension(a[1]);

                case "SIZE":
                    require(a,3);
                    return applySelectedDimension(a[1] + " " + a[2]);

                case "RADIUS":
                    require(a,2);
                    if (selected instanceof CircleEntity) {
                        saveUndo();
                        ((CircleEntity) selected).r = Math.abs(f(a,1));
                        invalidate();
                        return "شعاع = " + mm(Math.abs(f(a,1)));
                    }
                    if (selected instanceof ArcEntity) {
                        saveUndo();
                        ((ArcEntity) selected).r = Math.abs(f(a,1));
                        invalidate();
                        return "شعاع = " + mm(Math.abs(f(a,1)));
                    }
                    return "دایره یا قوس را انتخاب کن";

                case "DIAMETER":
                    require(a,2);
                    if (selected instanceof CircleEntity) return applySelectedDimension(a[1]);
                    return "دایره را انتخاب کن";

                case "GUIDE":
                    require(a,3);
                    saveUndo();
                    if ("X".equalsIgnoreCase(a[1])) addPrepared(new GuideEntity(true, f(a,2)));
                    else if ("Y".equalsIgnoreCase(a[1])) addPrepared(new GuideEntity(false, f(a,2)));
                    else return "مثال: GUIDE X 50 یا GUIDE Y 50";
                    invalidate();
                    return "خط راهنما ساخته شد";

                case "TAPE":
                case "DIST":
                    require(a,5);
                    saveUndo();
                    addPrepared(new MeasureEntity(f(a,1),f(a,2),f(a,3),f(a,4)));
                    invalidate();
                    return "فاصله = " + mm(dist(f(a,1),f(a,2),f(a,3),f(a,4)));

                case "ANGLE":
                case "PROTRACTOR":
                    require(a,7);
                    saveUndo();
                    addPrepared(new AngleEntity(f(a,1),f(a,2),f(a,3),f(a,4),f(a,5),f(a,6)));
                    invalidate();
                    return "زاویه اندازه‌گذاری شد";

                case "LAYER":
                    require(a,2);
                    return setLayer(a[1]);

                case "ASSIGNLAYER":
                    require(a,2);
                    return assignSelectedLayer(a[1]);

                case "LAYERHIDE":
                    require(a,2);
                    return setLayerVisible(a[1], false);

                case "LAYERSHOW":
                    require(a,2);
                    return setLayerVisible(a[1], true);

                case "MATERIAL":
                    require(a,2);
                    return setMaterial(a[1]);

                case "SCENE":
                    require(a,3);
                    if ("SAVE".equalsIgnoreCase(a[1])) {
                        scenes.put(a[2], new Scene(viewScale, offsetX, offsetY, showGrid, showAxes));
                        return "Scene ذخیره شد: " + a[2];
                    }
                    if ("LOAD".equalsIgnoreCase(a[1])) {
                        Scene scene = scenes.get(a[2]);
                        if (scene == null) return "Scene پیدا نشد";
                        viewScale = scene.scale;
                        offsetX = scene.x;
                        offsetY = scene.y;
                        showGrid = scene.grid;
                        showAxes = scene.axes;
                        invalidate();
                        return "Scene بارگذاری شد: " + a[2];
                    }
                    return "SCENE SAVE name یا SCENE LOAD name";

                case "P":
                case "PUSHPULL":
                case "EXTRUDE":
                    require(a,2);
                    if (selected == null) return "اول سطح بسته را انتخاب کن";
                    if (!selected.canExtrude()) return "این شکل سطح بسته قابل اکسترود نیست";
                    saveUndo();
                    selected.setExtrusion(Math.abs(f(a,1)));
                    invalidate();
                    return "Push/Pull = " + mm(Math.abs(f(a,1))) + " (پیش‌نمایش 2.5D)";

                case "ERASE":
                case "DELETE":
                    if (selected == null) return "اول شکل را انتخاب کن";
                    deleteSelected();
                    return "حذف شد";

                case "U":
                case "UNDO":
                    undo();
                    return "Undo";

                case "Z":
                case "ZOOM":
                case "FIT":
                    fitAll();
                    return "نمایش Fit شد";

                case "AXIS":
                    toggleAxes();
                    return showAxes ? "محورها روشن" : "محورها خاموش";

                case "GRID":
                    toggleGrid();
                    return showGrid ? "Grid روشن" : "Grid خاموش";

                case "GUIDES":
                    toggleGuides();
                    return showGuides ? "Guide روشن" : "Guide خاموش";

                case "DIMS":
                case "DIMENSIONS":
                    toggleDimensions();
                    return showDimensions ? "ابعاد روشن" : "ابعاد خاموش";

                case "SNAP":
                    toggleSnap();
                    return snapEnabled ? "Snap روشن" : "Snap خاموش";

                case "ORTHO":
                    toggleOrtho();
                    return orthoEnabled ? "Ortho روشن" : "Ortho خاموش";

                case "CLEAR":
                    clearAll();
                    return "صفحه پاک شد";

                case "SELECT":
                    setTool(TOOL_SELECT);
                    return "حالت انتخاب";

                case "DIM":
                    setTool(TOOL_MEASURE);
                    return "حالت اندازه‌گذاری";

                case "FREE":
                    setTool(TOOL_FREE);
                    return "حالت Freehand";

                case "GROUP":
                case "COMPONENT":
                    return "Group/Component در معماری مدل ثبت شده؛ انتخاب چندگانه در نسخه بعد فعال می‌شود";

                case "FOLLOWME":
                case "REVOLVE":
                case "LOFT":
                case "SWEEP":
                case "SHELL":
                case "UNION":
                case "SUBTRACT":
                case "INTERSECT":
                case "PROJECT":
                    return "این فرمان به هسته Solid 3D نیاز دارد؛ فعلاً Push/Pull پیش‌نمایش 2.5D فعال است";

                default:
                    return "فرمان ناشناخته: " + cmd;
            }
        } catch (Exception ex) {
            return "فرمت فرمان درست نیست";
        }
    }

    public String buildDxf() {
        StringBuilder d = new StringBuilder();
        d.append("0\nSECTION\n2\nHEADER\n9\n$INSUNITS\n70\n4\n0\nENDSEC\n");
        d.append("0\nSECTION\n2\nENTITIES\n");
        for (Entity e : entities) {
            if (!e.isConstruction()) e.appendDxf(d);
        }
        d.append("0\nENDSEC\n0\nEOF\n");
        return d.toString();
    }

    private static void require(String[] a,int n){ if(a.length<n) throw new IllegalArgumentException(); }
    private static float f(String[] a,int i){ return Float.parseFloat(a[i]); }
    private static float clamp(float v,float min,float max){ return Math.max(min,Math.min(max,v)); }
    private static float dist(float x1,float y1,float x2,float y2){ return(float)Math.hypot(x2-x1,y2-y1); }
    private static String mm(float v){ return String.format(Locale.US,"%.1f mm",v); }
    private static String fmt(float v){ return String.format(Locale.US,"%.2f",v); }

    private static int materialColor(String m) {
        if (m == null) return Color.rgb(25,25,25);
        String s = m.trim().toUpperCase(Locale.US);
        if ("WOOD".equals(s) || "CHOB".equals(s) || "چوب".equals(m)) return Color.rgb(125,85,45);
        if ("MDF".equals(s)) return Color.rgb(145,110,70);
        if ("METAL".equals(s) || "فلز".equals(m)) return Color.rgb(90,100,110);
        if ("GLASS".equals(s) || "شیشه".equals(m)) return Color.rgb(80,150,175);
        if ("CONSTRUCTION".equals(s)) return Color.rgb(65,145,200);
        return Color.rgb(25,25,25);
    }

    private static void drawLength(Canvas c,float x1,float y1,float x2,float y2,Paint t,float px){
        c.drawText(mm(dist(x1,y1,x2,y2)),(x1+x2)/2f,(y1+y2)/2f-4f*px,t);
    }

    private static float pointSeg(float px,float py,float x1,float y1,float x2,float y2){
        float dx=x2-x1,dy=y2-y1;
        float l2=dx*dx+dy*dy;
        if(l2<1e-6f)return dist(px,py,x1,y1);
        float t=((px-x1)*dx+(py-y1)*dy)/l2;
        t=clamp(t,0,1);
        return dist(px,py,x1+t*dx,y1+t*dy);
    }

    private static PointF rotatePoint(float x,float y,float cx,float cy,float deg){
        double r=Math.toRadians(deg);
        float dx=x-cx,dy=y-cy;
        return new PointF(
                cx+(float)(dx*Math.cos(r)-dy*Math.sin(r)),
                cy+(float)(dx*Math.sin(r)+dy*Math.cos(r)));
    }

    private static PointF scalePoint(float x,float y,float cx,float cy,float factor){
        return new PointF(cx+(x-cx)*factor,cy+(y-cy)*factor);
    }

    private static float angleAt(float ax,float ay,float cx,float cy,float bx,float by){
        double a1=Math.atan2(ay-cy,ax-cx);
        double a2=Math.atan2(by-cy,bx-cx);
        double d=Math.toDegrees(a2-a1);
        while(d<0)d+=360;
        while(d>=360)d-=360;
        if(d>180)d=360-d;
        return(float)d;
    }

    private static class SnapCandidate {
        float x,y,d;
        SnapCandidate(float x,float y,float d){this.x=x;this.y=y;this.d=d;}
    }

    private static class Scene {
        float scale,x,y;
        boolean grid,axes;
        Scene(float scale,float x,float y,boolean grid,boolean axes){
            this.scale=scale;this.x=x;this.y=y;this.grid=grid;this.axes=axes;
        }
    }

    private interface Entity {
        void draw(Canvas c,Paint p,Paint text,Paint measure,float px,boolean showDimensions);
        float hitDistance(float x,float y);
        List<float[]> snapPoints();
        void translate(float dx,float dy);
        void rotate(float cx,float cy,float deg);
        void scale(float cx,float cy,float factor);
        void mirrorVertical(float axisX);
        void mirrorHorizontal(float axisY);
        Entity copy();
        RectF bounds();
        PointF center();
        Entity offsetCopy(float distance);
        void appendDxf(StringBuilder d);
        String describe();
        String getLayer();
        void setLayer(String layer);
        int getColor();
        void setColor(int color);
        boolean isConstruction();
        boolean canExtrude();
        void setExtrusion(float h);
        float getExtrusion();
    }

    private abstract static class BaseEntity implements Entity {
        String layer="0";
        int color=Color.rgb(25,25,25);
        float extrusion=0f;

        public String getLayer(){return layer;}
        public void setLayer(String layer){this.layer=layer==null?"0":layer;}
        public int getColor(){return color;}
        public void setColor(int color){this.color=color;}
        public boolean isConstruction(){return false;}
        public boolean canExtrude(){return false;}
        public void setExtrusion(float h){extrusion=h;}
        public float getExtrusion(){return extrusion;}

        void copyMetaTo(BaseEntity other){
            other.layer=layer;
            other.color=color;
            other.extrusion=extrusion;
        }
    }

    private static class PointEntity extends BaseEntity {
        float x,y;
        PointEntity(float x,float y){this.x=x;this.y=y;}

        public void draw(Canvas c,Paint p,Paint t,Paint m,float px,boolean dims){
            c.drawCircle(x,y,4f*px,p);
            if(dims)c.drawText(String.format(Locale.US,"(%.1f, %.1f)",x,y),x+18f*px,y-8f*px,t);
        }
        public float hitDistance(float a,float b){return dist(a,b,x,y);}
        public List<float[]> snapPoints(){List<float[]>q=new ArrayList<>();q.add(new float[]{x,y});return q;}
        public void translate(float dx,float dy){x+=dx;y+=dy;}
        public void rotate(float cx,float cy,float deg){PointF p=rotatePoint(x,y,cx,cy,deg);x=p.x;y=p.y;}
        public void scale(float cx,float cy,float f){PointF p=scalePoint(x,y,cx,cy,f);x=p.x;y=p.y;}
        public void mirrorVertical(float axisX){x=2*axisX-x;}
        public void mirrorHorizontal(float axisY){y=2*axisY-y;}
        public Entity copy(){PointEntity e=new PointEntity(x,y);copyMetaTo(e);return e;}
        public RectF bounds(){return new RectF(x,y,x,y);}
        public PointF center(){return new PointF(x,y);}
        public Entity offsetCopy(float d){return null;}
        public String describe(){return "Point ("+fmt(x)+", "+fmt(y)+") | Layer "+layer;}
        public void appendDxf(StringBuilder d){
            d.append("0\nPOINT\n8\n").append(layer).append("\n10\n").append(x)
                    .append("\n20\n").append(-y).append("\n30\n0\n");
        }
    }

    private static class LineEntity extends BaseEntity {
        float x1,y1,x2,y2;
        LineEntity(float x1,float y1,float x2,float y2){this.x1=x1;this.y1=y1;this.x2=x2;this.y2=y2;}

        void setLength(float len){
            float dx=x2-x1,dy=y2-y1;
            float old=(float)Math.hypot(dx,dy);
            if(old<1e-6f){x2=x1+len;y2=y1;return;}
            x2=x1+dx/old*len;
            y2=y1+dy/old*len;
        }

        public void draw(Canvas c,Paint p,Paint t,Paint m,float px,boolean dims){
            c.drawLine(x1,y1,x2,y2,p);
            if(dims)drawLength(c,x1,y1,x2,y2,t,px);
        }
        public float hitDistance(float x,float y){return pointSeg(x,y,x1,y1,x2,y2);}
        public List<float[]> snapPoints(){
            List<float[]>q=new ArrayList<>();
            q.add(new float[]{x1,y1});q.add(new float[]{x2,y2});
            q.add(new float[]{(x1+x2)/2f,(y1+y2)/2f});
            return q;
        }
        public void translate(float dx,float dy){x1+=dx;x2+=dx;y1+=dy;y2+=dy;}
        public void rotate(float cx,float cy,float deg){
            PointF a=rotatePoint(x1,y1,cx,cy,deg),b=rotatePoint(x2,y2,cx,cy,deg);
            x1=a.x;y1=a.y;x2=b.x;y2=b.y;
        }
        public void scale(float cx,float cy,float f){
            PointF a=scalePoint(x1,y1,cx,cy,f),b=scalePoint(x2,y2,cx,cy,f);
            x1=a.x;y1=a.y;x2=b.x;y2=b.y;
        }
        public void mirrorVertical(float axisX){x1=2*axisX-x1;x2=2*axisX-x2;}
        public void mirrorHorizontal(float axisY){y1=2*axisY-y1;y2=2*axisY-y2;}
        public Entity copy(){LineEntity e=new LineEntity(x1,y1,x2,y2);copyMetaTo(e);return e;}
        public RectF bounds(){return new RectF(Math.min(x1,x2),Math.min(y1,y2),Math.max(x1,x2),Math.max(y1,y2));}
        public PointF center(){return new PointF((x1+x2)/2f,(y1+y2)/2f);}
        public Entity offsetCopy(float distance){
            float dx=x2-x1,dy=y2-y1,len=(float)Math.hypot(dx,dy);
            if(len<1e-6f)return null;
            float nx=-dy/len*distance,ny=dx/len*distance;
            LineEntity e=new LineEntity(x1+nx,y1+ny,x2+nx,y2+ny);copyMetaTo(e);return e;
        }
        public String describe(){return "Line | L="+mm(dist(x1,y1,x2,y2))+" | Layer "+layer;}
        public void appendDxf(StringBuilder d){
            d.append("0\nLINE\n8\n").append(layer).append("\n10\n").append(x1)
                    .append("\n20\n").append(-y1).append("\n30\n0\n11\n").append(x2)
                    .append("\n21\n").append(-y2).append("\n31\n0\n");
        }
    }

    private static class RectEntity extends BaseEntity {
        final PointF[] p=new PointF[4];
        RectEntity(float x1,float y1,float x2,float y2){
            p[0]=new PointF(x1,y1);
            p[1]=new PointF(x2,y1);
            p[2]=new PointF(x2,y2);
            p[3]=new PointF(x1,y2);
        }
        RectEntity(PointF[] src){
            for(int i=0;i<4;i++)p[i]=new PointF(src[i].x,src[i].y);
        }

        void setSize(float w,float h){
            PointF o=p[0];
            float ux=p[1].x-o.x,uy=p[1].y-o.y;
            float ul=(float)Math.hypot(ux,uy);
            if(ul<1e-6f){ux=1;uy=0;ul=1;}
            ux/=ul;uy/=ul;
            float vx=p[3].x-o.x,vy=p[3].y-o.y;
            float vl=(float)Math.hypot(vx,vy);
            if(vl<1e-6f){vx=-uy;vy=ux;vl=1;}
            vx/=vl;vy/=vl;
            p[1].set(o.x+ux*w,o.y+uy*w);
            p[3].set(o.x+vx*h,o.y+vy*h);
            p[2].set(p[1].x+vx*h,p[1].y+vy*h);
        }

        public boolean canExtrude(){return true;}

        public void draw(Canvas c,Paint paint,Paint t,Paint m,float px,boolean dims){
            drawPolygonPath(c,p,paint);
            if(extrusion>0.01f)drawExtrudedPolygon(c,p,extrusion,paint,px);
            if(dims){
                c.drawText(mm(dist(p[0].x,p[0].y,p[1].x,p[1].y)),
                        (p[0].x+p[1].x)/2f,(p[0].y+p[1].y)/2f-5f*px,t);
                c.drawText(mm(dist(p[1].x,p[1].y,p[2].x,p[2].y)),
                        (p[1].x+p[2].x)/2f+12f*px,(p[1].y+p[2].y)/2f,t);
                if(extrusion>0.01f)c.drawText("H "+mm(extrusion),center().x,center().y,t);
            }
        }
        public float hitDistance(float x,float y){
            float best=Float.MAX_VALUE;
            for(int i=0;i<4;i++)best=Math.min(best,pointSeg(x,y,p[i].x,p[i].y,p[(i+1)%4].x,p[(i+1)%4].y));
            return best;
        }
        public List<float[]> snapPoints(){return polygonSnaps(p);}
        public void translate(float dx,float dy){for(PointF q:p){q.x+=dx;q.y+=dy;}}
        public void rotate(float cx,float cy,float deg){for(int i=0;i<4;i++)p[i]=rotatePoint(p[i].x,p[i].y,cx,cy,deg);}
        public void scale(float cx,float cy,float f){for(int i=0;i<4;i++)p[i]=scalePoint(p[i].x,p[i].y,cx,cy,f);}
        public void mirrorVertical(float axisX){for(PointF q:p)q.x=2*axisX-q.x;}
        public void mirrorHorizontal(float axisY){for(PointF q:p)q.y=2*axisY-q.y;}
        public Entity copy(){RectEntity e=new RectEntity(p);copyMetaTo(e);return e;}
        public RectF bounds(){return boundsOf(p);}
        public PointF center(){return centroid(p);}
        public Entity offsetCopy(float d){
            PointF c=center();
            float w=dist(p[0].x,p[0].y,p[1].x,p[1].y);
            float h=dist(p[0].x,p[0].y,p[3].x,p[3].y);
            if(w<1e-6f||h<1e-6f)return null;
            float nw=w+2f*d,nh=h+2f*d;
            if(nw<=0||nh<=0)return null;
            RectEntity e=(RectEntity)copy();
            float ux=nw/w,uy=nh/h;
            PointF cc=e.center();
            for(PointF q:e.p){
                float dx=q.x-cc.x,dy=q.y-cc.y;
                float angle=(float)Math.atan2(p[1].y-p[0].y,p[1].x-p[0].x);
                float ca=(float)Math.cos(-angle),sa=(float)Math.sin(-angle);
                float lx=dx*ca-dy*sa,ly=dx*sa+dy*ca;
                lx*=ux;ly*=uy;
                ca=(float)Math.cos(angle);sa=(float)Math.sin(angle);
                q.x=cc.x+lx*ca-ly*sa;
                q.y=cc.y+lx*sa+ly*ca;
            }
            return e;
        }
        public String describe(){
            return "Rectangle | "+mm(dist(p[0].x,p[0].y,p[1].x,p[1].y))+" × "+
                    mm(dist(p[1].x,p[1].y,p[2].x,p[2].y))+
                    (extrusion>0?" × H "+mm(extrusion):"")+" | Layer "+layer;
        }
        public void appendDxf(StringBuilder d){
            appendPolylineDxf(d,p,true,layer);
        }
    }

    private static class CircleEntity extends BaseEntity {
        float x,y,r;
        CircleEntity(float x,float y,float r){this.x=x;this.y=y;this.r=r;}

        public boolean canExtrude(){return true;}

        public void draw(Canvas c,Paint p,Paint t,Paint m,float px,boolean dims){
            c.drawCircle(x,y,r,p);
            if(extrusion>0.01f){
                float sx=-extrusion*0.28f,sy=-extrusion*0.18f;
                c.drawCircle(x+sx,y+sy,r,p);
                c.drawLine(x+r,y,x+r+sx,y+sy,p);
                c.drawLine(x-r,y,x-r+sx,y+sy,p);
            }
            if(dims){
                c.drawText("Ø "+mm(r*2),x,y-r-6f*px,t);
                if(extrusion>0.01f)c.drawText("H "+mm(extrusion),x,y,t);
            }
        }
        public float hitDistance(float a,float b){return Math.abs(dist(a,b,x,y)-r);}
        public List<float[]> snapPoints(){
            List<float[]>q=new ArrayList<>();
            q.add(new float[]{x,y});
            q.add(new float[]{x+r,y});q.add(new float[]{x-r,y});
            q.add(new float[]{x,y+r});q.add(new float[]{x,y-r});
            return q;
        }
        public void translate(float dx,float dy){x+=dx;y+=dy;}
        public void rotate(float cx,float cy,float deg){PointF p=rotatePoint(x,y,cx,cy,deg);x=p.x;y=p.y;}
        public void scale(float cx,float cy,float f){PointF p=scalePoint(x,y,cx,cy,f);x=p.x;y=p.y;r*=Math.abs(f);}
        public void mirrorVertical(float axisX){x=2*axisX-x;}
        public void mirrorHorizontal(float axisY){y=2*axisY-y;}
        public Entity copy(){CircleEntity e=new CircleEntity(x,y,r);copyMetaTo(e);return e;}
        public RectF bounds(){return new RectF(x-r,y-r,x+r,y+r);}
        public PointF center(){return new PointF(x,y);}
        public Entity offsetCopy(float d){
            if(r+d<=0)return null;
            CircleEntity e=new CircleEntity(x,y,r+d);copyMetaTo(e);return e;
        }
        public String describe(){return "Circle | Ø "+mm(r*2)+(extrusion>0?" | H "+mm(extrusion):"")+" | Layer "+layer;}
        public void appendDxf(StringBuilder d){
            d.append("0\nCIRCLE\n8\n").append(layer).append("\n10\n").append(x)
                    .append("\n20\n").append(-y).append("\n30\n0\n40\n").append(r).append("\n");
        }
    }

    private static class ArcEntity extends BaseEntity {
        float x,y,r,start,sweep;
        ArcEntity(float x,float y,float r,float start,float sweep){this.x=x;this.y=y;this.r=r;this.start=start;this.sweep=sweep;}

        public void draw(Canvas c,Paint p,Paint t,Paint m,float px,boolean dims){
            RectF b=new RectF(x-r,y-r,x+r,y+r);
            c.drawArc(b,start,sweep,false,p);
            if(dims)c.drawText("R "+mm(r),x,y-r-5f*px,t);
        }
        public float hitDistance(float a,float b){return Math.abs(dist(a,b,x,y)-r);}
        public List<float[]> snapPoints(){
            List<float[]>q=new ArrayList<>();
            q.add(new float[]{x,y});
            q.add(new float[]{x+(float)Math.cos(Math.toRadians(start))*r,y+(float)Math.sin(Math.toRadians(start))*r});
            float e=start+sweep;
            q.add(new float[]{x+(float)Math.cos(Math.toRadians(e))*r,y+(float)Math.sin(Math.toRadians(e))*r});
            return q;
        }
        public void translate(float dx,float dy){x+=dx;y+=dy;}
        public void rotate(float cx,float cy,float deg){PointF p=rotatePoint(x,y,cx,cy,deg);x=p.x;y=p.y;start+=deg;}
        public void scale(float cx,float cy,float f){PointF p=scalePoint(x,y,cx,cy,f);x=p.x;y=p.y;r*=Math.abs(f);}
        public void mirrorVertical(float axisX){x=2*axisX-x;start=180-start;sweep=-sweep;}
        public void mirrorHorizontal(float axisY){y=2*axisY-y;start=-start;sweep=-sweep;}
        public Entity copy(){ArcEntity e=new ArcEntity(x,y,r,start,sweep);copyMetaTo(e);return e;}
        public RectF bounds(){return new RectF(x-r,y-r,x+r,y+r);}
        public PointF center(){return new PointF(x,y);}
        public Entity offsetCopy(float d){
            if(r+d<=0)return null;
            ArcEntity e=new ArcEntity(x,y,r+d,start,sweep);copyMetaTo(e);return e;
        }
        public String describe(){return "Arc | R "+mm(r)+" | "+fmt(sweep)+"° | Layer "+layer;}
        public void appendDxf(StringBuilder d){
            d.append("0\nARC\n8\n").append(layer).append("\n10\n").append(x)
                    .append("\n20\n").append(-y).append("\n30\n0\n40\n").append(r)
                    .append("\n50\n").append(start).append("\n51\n").append(start+sweep).append("\n");
        }
    }

    private static class PolygonEntity extends BaseEntity {
        final List<PointF> points=new ArrayList<>();

        PolygonEntity(List<PointF> pts){for(PointF p:pts)points.add(new PointF(p.x,p.y));}

        static PolygonEntity regular(int sides,float cx,float cy,float r){
            List<PointF> pts=new ArrayList<>();
            for(int i=0;i<sides;i++){
                double a=-Math.PI/2+2*Math.PI*i/sides;
                pts.add(new PointF(cx+(float)Math.cos(a)*r,cy+(float)Math.sin(a)*r));
            }
            return new PolygonEntity(pts);
        }

        void setRadius(float r){
            PointF c=center();
            float current=0f;
            for(PointF p:points)current+=dist(c.x,c.y,p.x,p.y);
            current/=Math.max(1,points.size());
            if(current<1e-6f)return;
            scale(c.x,c.y,r/current);
        }

        public boolean canExtrude(){return true;}

        public void draw(Canvas c,Paint p,Paint t,Paint m,float px,boolean dims){
            PointF[] arr=points.toArray(new PointF[0]);
            drawPolygonPath(c,arr,p);
            if(extrusion>0.01f)drawExtrudedPolygon(c,arr,extrusion,p,px);
            if(dims){
                PointF cc=center();
                float r=points.isEmpty()?0:dist(cc.x,cc.y,points.get(0).x,points.get(0).y);
                c.drawText(points.size()+" sides | R "+mm(r),cc.x,cc.y,t);
                if(extrusion>0.01f)c.drawText("H "+mm(extrusion),cc.x,cc.y+12f*px,t);
            }
        }
        public float hitDistance(float x,float y){
            if(points.isEmpty())return Float.MAX_VALUE;
            float best=Float.MAX_VALUE;
            for(int i=0;i<points.size();i++){
                PointF a=points.get(i),b=points.get((i+1)%points.size());
                best=Math.min(best,pointSeg(x,y,a.x,a.y,b.x,b.y));
            }
            return best;
        }
        public List<float[]> snapPoints(){
            List<float[]>q=new ArrayList<>();
            for(int i=0;i<points.size();i++){
                PointF a=points.get(i),b=points.get((i+1)%points.size());
                q.add(new float[]{a.x,a.y});
                q.add(new float[]{(a.x+b.x)/2f,(a.y+b.y)/2f});
            }
            PointF c=center();q.add(new float[]{c.x,c.y});
            return q;
        }
        public void translate(float dx,float dy){for(PointF p:points){p.x+=dx;p.y+=dy;}}
        public void rotate(float cx,float cy,float deg){for(int i=0;i<points.size();i++)points.set(i,rotatePoint(points.get(i).x,points.get(i).y,cx,cy,deg));}
        public void scale(float cx,float cy,float f){for(int i=0;i<points.size();i++)points.set(i,scalePoint(points.get(i).x,points.get(i).y,cx,cy,f));}
        public void mirrorVertical(float axisX){for(PointF p:points)p.x=2*axisX-p.x;}
        public void mirrorHorizontal(float axisY){for(PointF p:points)p.y=2*axisY-p.y;}
        public Entity copy(){PolygonEntity e=new PolygonEntity(points);copyMetaTo(e);return e;}
        public RectF bounds(){return boundsOf(points.toArray(new PointF[0]));}
        public PointF center(){return centroid(points.toArray(new PointF[0]));}
        public Entity offsetCopy(float d){
            PointF c=center();
            float r=0f;
            for(PointF p:points)r+=dist(c.x,c.y,p.x,p.y);
            r/=Math.max(1,points.size());
            if(r+d<=0||r<1e-6f)return null;
            PolygonEntity e=(PolygonEntity)copy();
            e.scale(c.x,c.y,(r+d)/r);
            return e;
        }
        public String describe(){return "Polygon "+points.size()+" sides"+(extrusion>0?" | H "+mm(extrusion):"")+" | Layer "+layer;}
        public void appendDxf(StringBuilder d){appendPolylineDxf(d,points.toArray(new PointF[0]),true,layer);}
    }

    private static class PolylineEntity extends BaseEntity {
        final List<PointF> points=new ArrayList<>();
        final boolean closed;

        PolylineEntity(List<PointF> pts,boolean closed){
            for(PointF p:pts)points.add(new PointF(p.x,p.y));
            this.closed=closed;
        }

        public void draw(Canvas c,Paint p,Paint t,Paint m,float px,boolean dims){
            if(points.size()<2)return;
            Path path=new Path();
            path.moveTo(points.get(0).x,points.get(0).y);
            for(int i=1;i<points.size();i++)path.lineTo(points.get(i).x,points.get(i).y);
            if(closed)path.close();
            c.drawPath(path,p);
        }
        public float hitDistance(float x,float y){
            float best=Float.MAX_VALUE;
            for(int i=0;i<points.size()-1;i++)best=Math.min(best,pointSeg(x,y,points.get(i).x,points.get(i).y,points.get(i+1).x,points.get(i+1).y));
            return best;
        }
        public List<float[]> snapPoints(){
            List<float[]>q=new ArrayList<>();
            for(PointF p:points)q.add(new float[]{p.x,p.y});
            return q;
        }
        public void translate(float dx,float dy){for(PointF p:points){p.x+=dx;p.y+=dy;}}
        public void rotate(float cx,float cy,float deg){for(int i=0;i<points.size();i++)points.set(i,rotatePoint(points.get(i).x,points.get(i).y,cx,cy,deg));}
        public void scale(float cx,float cy,float f){for(int i=0;i<points.size();i++)points.set(i,scalePoint(points.get(i).x,points.get(i).y,cx,cy,f));}
        public void mirrorVertical(float axisX){for(PointF p:points)p.x=2*axisX-p.x;}
        public void mirrorHorizontal(float axisY){for(PointF p:points)p.y=2*axisY-p.y;}
        public Entity copy(){PolylineEntity e=new PolylineEntity(points,closed);copyMetaTo(e);return e;}
        public RectF bounds(){return boundsOf(points.toArray(new PointF[0]));}
        public PointF center(){return centroid(points.toArray(new PointF[0]));}
        public Entity offsetCopy(float d){return null;}
        public String describe(){return (closed?"Polyline closed":"Freehand")+" | "+points.size()+" points | Layer "+layer;}
        public void appendDxf(StringBuilder d){appendPolylineDxf(d,points.toArray(new PointF[0]),closed,layer);}
    }

    private static class MeasureEntity extends BaseEntity {
        float x1,y1,x2,y2;
        MeasureEntity(float x1,float y1,float x2,float y2){this.x1=x1;this.y1=y1;this.x2=x2;this.y2=y2;}

        public void draw(Canvas c,Paint p,Paint t,Paint m,float px,boolean dims){
            c.drawLine(x1,y1,x2,y2,m);
            drawArrow(c,x1,y1,x2,y2,m,px);
            if(dims)drawLength(c,x1,y1,x2,y2,t,px);
        }
        public float hitDistance(float x,float y){return pointSeg(x,y,x1,y1,x2,y2);}
        public List<float[]> snapPoints(){List<float[]>q=new ArrayList<>();q.add(new float[]{x1,y1});q.add(new float[]{x2,y2});return q;}
        public void translate(float dx,float dy){x1+=dx;x2+=dx;y1+=dy;y2+=dy;}
        public void rotate(float cx,float cy,float deg){PointF a=rotatePoint(x1,y1,cx,cy,deg),b=rotatePoint(x2,y2,cx,cy,deg);x1=a.x;y1=a.y;x2=b.x;y2=b.y;}
        public void scale(float cx,float cy,float f){PointF a=scalePoint(x1,y1,cx,cy,f),b=scalePoint(x2,y2,cx,cy,f);x1=a.x;y1=a.y;x2=b.x;y2=b.y;}
        public void mirrorVertical(float axisX){x1=2*axisX-x1;x2=2*axisX-x2;}
        public void mirrorHorizontal(float axisY){y1=2*axisY-y1;y2=2*axisY-y2;}
        public Entity copy(){MeasureEntity e=new MeasureEntity(x1,y1,x2,y2);copyMetaTo(e);return e;}
        public RectF bounds(){return new RectF(Math.min(x1,x2),Math.min(y1,y2),Math.max(x1,x2),Math.max(y1,y2));}
        public PointF center(){return new PointF((x1+x2)/2f,(y1+y2)/2f);}
        public Entity offsetCopy(float d){return null;}
        public String describe(){return "Dimension | "+mm(dist(x1,y1,x2,y2));}
        public void appendDxf(StringBuilder d){}
    }

    private static class AngleEntity extends BaseEntity {
        float ax,ay,cx,cy,bx,by;
        AngleEntity(float ax,float ay,float cx,float cy,float bx,float by){
            this.ax=ax;this.ay=ay;this.cx=cx;this.cy=cy;this.bx=bx;this.by=by;
        }
        public void draw(Canvas c,Paint p,Paint t,Paint m,float px,boolean dims){
            c.drawLine(cx,cy,ax,ay,m);c.drawLine(cx,cy,bx,by,m);
            if(dims)c.drawText(fmt(angleAt(ax,ay,cx,cy,bx,by))+"°",cx+15f*px,cy-10f*px,t);
        }
        public float hitDistance(float x,float y){return Math.min(pointSeg(x,y,cx,cy,ax,ay),pointSeg(x,y,cx,cy,bx,by));}
        public List<float[]> snapPoints(){List<float[]>q=new ArrayList<>();q.add(new float[]{ax,ay});q.add(new float[]{cx,cy});q.add(new float[]{bx,by});return q;}
        public void translate(float dx,float dy){ax+=dx;ay+=dy;cx+=dx;cy+=dy;bx+=dx;by+=dy;}
        public void rotate(float x,float y,float deg){
            PointF a=rotatePoint(ax,ay,x,y,deg),cc=rotatePoint(cx,cy,x,y,deg),b=rotatePoint(bx,by,x,y,deg);
            ax=a.x;ay=a.y;cx=cc.x;cy=cc.y;bx=b.x;by=b.y;
        }
        public void scale(float x,float y,float f){
            PointF a=scalePoint(ax,ay,x,y,f),cc=scalePoint(cx,cy,x,y,f),b=scalePoint(bx,by,x,y,f);
            ax=a.x;ay=a.y;cx=cc.x;cy=cc.y;bx=b.x;by=b.y;
        }
        public void mirrorVertical(float axisX){ax=2*axisX-ax;cx=2*axisX-cx;bx=2*axisX-bx;}
        public void mirrorHorizontal(float axisY){ay=2*axisY-ay;cy=2*axisY-cy;by=2*axisY-by;}
        public Entity copy(){AngleEntity e=new AngleEntity(ax,ay,cx,cy,bx,by);copyMetaTo(e);return e;}
        public RectF bounds(){PointF[]p={new PointF(ax,ay),new PointF(cx,cy),new PointF(bx,by)};return boundsOf(p);}
        public PointF center(){return new PointF(cx,cy);}
        public Entity offsetCopy(float d){return null;}
        public String describe(){return "Angle | "+fmt(angleAt(ax,ay,cx,cy,bx,by))+"°";}
        public void appendDxf(StringBuilder d){}
    }

    private static class GuideEntity extends BaseEntity {
        final boolean vertical;
        float value;
        GuideEntity(boolean vertical,float value){
            this.vertical=vertical;
            this.value=value;
            color=Color.rgb(65,145,200);
        }
        public boolean isConstruction(){return true;}
        public void draw(Canvas c,Paint p,Paint t,Paint m,float px,boolean dims){
            float far=100000f;
            if(vertical)c.drawLine(value,-far,value,far,p);
            else c.drawLine(-far,value,far,value,p);
            if(dims)c.drawText((vertical?"X ":"Y ")+mm(value),vertical?value:0,vertical?0:value,t);
        }
        public float hitDistance(float x,float y){return vertical?Math.abs(x-value):Math.abs(y-value);}
        public List<float[]> snapPoints(){return new ArrayList<>();}
        public void translate(float dx,float dy){value+=vertical?dx:dy;}
        public void rotate(float cx,float cy,float deg){}
        public void scale(float cx,float cy,float f){}
        public void mirrorVertical(float axisX){if(vertical)value=2*axisX-value;}
        public void mirrorHorizontal(float axisY){if(!vertical)value=2*axisY-value;}
        public Entity copy(){GuideEntity e=new GuideEntity(vertical,value);copyMetaTo(e);return e;}
        public RectF bounds(){return vertical?new RectF(value,0,value,0):new RectF(0,value,0,value);}
        public PointF center(){return vertical?new PointF(value,0):new PointF(0,value);}
        public Entity offsetCopy(float d){GuideEntity e=new GuideEntity(vertical,value+d);copyMetaTo(e);return e;}
        public String describe(){return "Guide "+(vertical?"X ":"Y ")+mm(value);}
        public void appendDxf(StringBuilder d){}
    }

    private static List<float[]> polygonSnaps(PointF[] p){
        List<float[]>q=new ArrayList<>();
        for(int i=0;i<p.length;i++){
            PointF a=p[i],b=p[(i+1)%p.length];
            q.add(new float[]{a.x,a.y});
            q.add(new float[]{(a.x+b.x)/2f,(a.y+b.y)/2f});
        }
        PointF c=centroid(p);q.add(new float[]{c.x,c.y});
        return q;
    }

    private static RectF boundsOf(PointF[] p){
        if(p.length==0)return new RectF();
        float l=p[0].x,r=p[0].x,t=p[0].y,b=p[0].y;
        for(PointF q:p){l=Math.min(l,q.x);r=Math.max(r,q.x);t=Math.min(t,q.y);b=Math.max(b,q.y);}
        return new RectF(l,t,r,b);
    }

    private static PointF centroid(PointF[] p){
        if(p.length==0)return new PointF();
        float x=0,y=0;
        for(PointF q:p){x+=q.x;y+=q.y;}
        return new PointF(x/p.length,y/p.length);
    }

    private static void drawPolygonPath(Canvas c,PointF[] p,Paint paint){
        if(p.length==0)return;
        Path path=new Path();path.moveTo(p[0].x,p[0].y);
        for(int i=1;i<p.length;i++)path.lineTo(p[i].x,p[i].y);
        path.close();c.drawPath(path,paint);
    }

    private static void drawExtrudedPolygon(Canvas c,PointF[] p,float h,Paint paint,float px){
        if(p.length<3)return;
        float sx=-h*0.28f,sy=-h*0.18f;
        PointF[] top=new PointF[p.length];
        for(int i=0;i<p.length;i++)top[i]=new PointF(p[i].x+sx,p[i].y+sy);
        drawPolygonPath(c,top,paint);
        for(int i=0;i<p.length;i++)c.drawLine(p[i].x,p[i].y,top[i].x,top[i].y,paint);
    }

    private static void drawArrow(Canvas c,float x1,float y1,float x2,float y2,Paint p,float px){
        float dx=x2-x1,dy=y2-y1,len=(float)Math.hypot(dx,dy);
        if(len<1e-6f)return;
        float ux=dx/len,uy=dy/len;
        float s=7f*px;
        c.drawLine(x1,y1,x1+ux*s-uy*s*0.6f,y1+uy*s+ux*s*0.6f,p);
        c.drawLine(x1,y1,x1+ux*s+uy*s*0.6f,y1+uy*s-ux*s*0.6f,p);
        c.drawLine(x2,y2,x2-ux*s-uy*s*0.6f,y2-uy*s+ux*s*0.6f,p);
        c.drawLine(x2,y2,x2-ux*s+uy*s*0.6f,y2-uy*s-ux*s*0.6f,p);
    }

    private static void appendPolylineDxf(StringBuilder d,PointF[] p,boolean closed,String layer){
        if(p.length==0)return;
        d.append("0\nLWPOLYLINE\n8\n").append(layer).append("\n90\n").append(p.length)
                .append("\n70\n").append(closed?1:0).append("\n");
        for(PointF q:p)d.append("10\n").append(q.x).append("\n20\n").append(-q.y).append("\n");
    }
}

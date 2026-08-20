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
    private static final float MIN_VIEW_SCALE = 0.02f;
    private static final float MAX_VIEW_SCALE = 64f;
    private static final float SNAP_RADIUS_PX = 30f;
    private static final float HIT_RADIUS_PX = 28f;
    private static final float HANDLE_RADIUS_PX = 18f;
    private static final float MIN_DRAW_PX = 12f;
    private static final int MAX_UNDO = 50;

    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint axisXPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint axisYPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint entityPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint selectedPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint measurePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint snapPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint guidePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handleFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handleStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint centerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint screenTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    protected final List<Entity> entities = new ArrayList<>();
    private final ArrayDeque<List<Entity>> undoStack = new ArrayDeque<>();
    private final ArrayDeque<List<Entity>> redoStack = new ArrayDeque<>();
    private final Map<String, Boolean> layers = new LinkedHashMap<>();
    private final Map<String, Scene> scenes = new HashMap<>();

    protected Entity selected;
    private int tool = TOOL_SELECT;
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
    protected float viewScale = 1f;
    protected float offsetX = 120f;
    protected float offsetY = 160f;
    private boolean multiTouch = false;
    private float lastMultiX;
    private float lastMultiY;

    protected float startX, startY, endX, endY;
    private float downScreenX, downScreenY;
    protected boolean drawing = false;
    private boolean draggingSelection = false;
    private boolean dragUndoSaved = false;
    private int activeHandle = -1;
    private float lastDragX, lastDragY;

    private float snapX, snapY;
    private boolean snapVisible = false;
    private String snapLabel = "";
    private final List<PointF> freePoints = new ArrayList<>();

    public CadCanvasView(Context context) {
        super(context);
        setBackgroundColor(Color.rgb(250, 250, 250));
        setFocusable(true);
        layers.put("0", true);

        gridPaint.setColor(Color.rgb(226, 226, 226));
        axisXPaint.setColor(Color.rgb(205, 55, 55));
        axisYPaint.setColor(Color.rgb(45, 145, 75));

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

        snapPaint.setColor(Color.rgb(245, 135, 15));
        snapPaint.setStyle(Paint.Style.STROKE);

        guidePaint.setColor(Color.rgb(65, 145, 200));
        guidePaint.setStyle(Paint.Style.STROKE);

        handleFillPaint.setColor(Color.WHITE);
        handleFillPaint.setStyle(Paint.Style.FILL);
        handleStrokePaint.setColor(Color.rgb(35, 105, 225));
        handleStrokePaint.setStyle(Paint.Style.STROKE);

        centerPaint.setColor(Color.rgb(35, 105, 225));
        centerPaint.setStyle(Paint.Style.STROKE);

        screenTextPaint.setColor(Color.DKGRAY);
        screenTextPaint.setTextSize(25f);

        scaleDetector = new ScaleGestureDetector(context,
                new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override
                    public boolean onScale(ScaleGestureDetector detector) {
                        float oldScale = viewScale;
                        float gesture = detector.getScaleFactor();
                        float accelerated = (float)Math.pow(gesture, 1.65d);
                        viewScale = clamp(viewScale * accelerated, MIN_VIEW_SCALE, MAX_VIEW_SCALE);
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
        draggingSelection = false;
        dragUndoSaved = false;
        activeHandle = -1;
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
    public void toggleSnap() { snapEnabled = !snapEnabled; snapVisible = false; invalidate(); }
    public void toggleOrtho() { orthoEnabled = !orthoEnabled; invalidate(); }

    public void zoomBy(float factor) {
        if (factor <= 0f) return;
        float oldScale = viewScale;
        viewScale = clamp(viewScale * factor, MIN_VIEW_SCALE, MAX_VIEW_SCALE);
        if (Math.abs(oldScale - viewScale) < 1e-7f) return;
        float ratio = viewScale / oldScale;
        float fx = getWidth() > 0 ? getWidth() / 2f : 0f;
        float fy = getHeight() > 0 ? getHeight() / 2f : 0f;
        offsetX = fx - (fx - offsetX) * ratio;
        offsetY = fy - (fy - offsetY) * ratio;
        invalidate();
    }

    public String selectedInfo() {
        return selected == null ? "هیچ شکلی انتخاب نشده" : selected.describe();
    }

    public void clearAll() {
        saveUndo();
        entities.clear();
        selected = null;
        tool = TOOL_SELECT;
        invalidate();
    }

    public boolean canUndoSketch() { return !undoStack.isEmpty(); }
    public boolean canRedoSketch() { return !redoStack.isEmpty(); }

    public void undo() {
        if (undoStack.isEmpty()) return;
        redoStack.addLast(snapshotEntities());
        while (redoStack.size() > MAX_UNDO) redoStack.removeFirst();
        restoreSnapshot(undoStack.removeLast());
    }

    public boolean redoSketch() {
        if (redoStack.isEmpty()) return false;
        undoStack.addLast(snapshotEntities());
        while (undoStack.size() > MAX_UNDO) undoStack.removeFirst();
        restoreSnapshot(redoStack.removeLast());
        return true;
    }

    public void deleteSelected() {
        if (selected == null) return;
        saveUndo();
        entities.remove(selected);
        selected = null;
        tool = TOOL_SELECT;
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
            if (!isVisible(e)) continue;
            RectF b = e.bounds();
            if (all == null) all = new RectF(b);
            else all.union(b);
        }
        if (all == null) {
            viewScale = 1f;
            // A new sketch starts at the visual centre, not in the upper-left
            // quarter of the display.  This is especially important on phones.
            offsetX = getWidth() * 0.5f;
            offsetY = getHeight() * 0.5f;
            invalidate();
            return;
        }
        float w = Math.max(20f, all.width());
        float h = Math.max(20f, all.height());
        float sx = getWidth() / (w * PX_PER_MM * 1.25f);
        float sy = getHeight() / (h * PX_PER_MM * 1.25f);
        viewScale = clamp(Math.min(sx, sy), MIN_VIEW_SCALE, MAX_VIEW_SCALE);
        PointF c = centerOf(all);
        offsetX = getWidth()/2f - c.x*PX_PER_MM*viewScale;
        offsetY = getHeight()/2f - c.y*PX_PER_MM*viewScale;
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
        handleStrokePaint.setStrokeWidth(2f * px);
        centerPaint.setStrokeWidth(2f * px);
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

        if (selected != null && isVisible(selected)) drawSelectionHandles(canvas, selected, px);
        if (drawing) drawPreview(canvas, px);
        if (snapVisible) drawSnapMarker(canvas, snapX, snapY, px);
        canvas.restore();

        String mode = toolName(tool);
        screenTextPaint.setTextSize(25f);
        String info = selected == null ? "" : " | انتخاب: " + selected.shortName();
        int zoomPercent = Math.max(1, Math.round(viewScale * 100f));
        canvas.drawText("حالت: " + mode + (snapEnabled ? " | Snap" : "") + info + " | Zoom " + zoomPercent + "%",
                12f, getHeight()-18f, screenTextPaint);
    }

    private float adaptiveGridStep() {
        float step = GRID_MM;
        float screen = step * PX_PER_MM * viewScale;
        while (screen < 22f) {
            step *= 5f;
            screen = step * PX_PER_MM * viewScale;
        }
        return step;
    }

    private void drawGrid(Canvas c, float left, float top, float right, float bottom) {
        float step = adaptiveGridStep();
        float gx=(float)Math.floor(left/step)*step;
        float gy=(float)Math.floor(top/step)*step;
        for(float x=gx;x<=right;x+=step)c.drawLine(x,top,x,bottom,gridPaint);
        for(float y=gy;y<=bottom;y+=step)c.drawLine(left,y,right,y,gridPaint);
    }

    private void drawAxes(Canvas c,float left,float top,float right,float bottom,float px){
        c.drawLine(left,0,right,0,axisXPaint);
        c.drawLine(0,top,0,bottom,axisYPaint);
        c.drawText("X",right-14f*px,-5f*px,axisXPaint);
        c.drawText("Y",5f*px,top+18f*px,axisYPaint);
        c.drawCircle(0,0,4f*px,axisXPaint);
    }

    private void drawCenterCross(Canvas c,float x,float y,float px){
        float s=8f*px;
        c.drawLine(x-s,y,x+s,y,centerPaint);
        c.drawLine(x,y-s,x,y+s,centerPaint);
        c.drawCircle(x,y,2.5f*px,centerPaint);
    }

    private void drawPreview(Canvas c,float px){
        Paint p=tool==TOOL_MEASURE?measurePaint:selectedPaint;
        if(tool==TOOL_LINE||tool==TOOL_MEASURE){
            c.drawLine(startX,startY,endX,endY,p);
            if(showDimensions)drawLength(c,startX,startY,endX,endY,textPaint,px);
        }else if(tool==TOOL_RECT){
            RectEntity r=new RectEntity(startX,startY,endX,endY);
            r.draw(c,p,textPaint,measurePaint,px,showDimensions);
        }else if(tool==TOOL_CIRCLE){
            float r=dist(startX,startY,endX,endY);
            c.drawCircle(startX,startY,r,p);
            drawCenterCross(c,startX,startY,px);
            if(showDimensions)c.drawText("Ø "+mm(r*2),startX,startY-r-6f*px,textPaint);
        }else if(tool==TOOL_ARC){
            float r=dist(startX,startY,endX,endY);
            c.drawArc(new RectF(startX-r,startY-r,startX+r,startY+r),180,180,false,p);
            drawCenterCross(c,startX,startY,px);
        }else if(tool==TOOL_POLYGON){
            PolygonEntity.regular(polygonSides,startX,startY,dist(startX,startY,endX,endY))
                    .draw(c,p,textPaint,measurePaint,px,showDimensions);
            drawCenterCross(c,startX,startY,px);
        }else if(tool==TOOL_FREE&&freePoints.size()>1){
            Path path=new Path();
            path.moveTo(freePoints.get(0).x,freePoints.get(0).y);
            for(int i=1;i<freePoints.size();i++)path.lineTo(freePoints.get(i).x,freePoints.get(i).y);
            c.drawPath(path,p);
        }
    }

    private void drawSelectionHandles(Canvas c,Entity e,float px){
        float r=4.2f*px;
        List<ControlPoint> handles=e.controlPoints();
        for(int i=0;i<handles.size();i++){
            ControlPoint h=handles.get(i);
            c.drawCircle(h.x,h.y,r,handleFillPaint);
            c.drawCircle(h.x,h.y,r,handleStrokePaint);
        }
        PointF cc=e.center();
        drawCenterCross(c,cc.x,cc.y,px);
    }

    private void drawSnapMarker(Canvas c,float x,float y,float px){
        float s=8f*px;
        c.drawRect(x-s,y-s,x+s,y+s,snapPaint);
        if(snapLabel!=null&&!snapLabel.isEmpty()){
            Paint.Align old=textPaint.getTextAlign();
            textPaint.setTextAlign(Paint.Align.LEFT);
            c.drawText(snapLabel,x+11f*px,y-9f*px,textPaint);
            textPaint.setTextAlign(old);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        scaleDetector.onTouchEvent(event);

        if(event.getPointerCount()>=2){
            float mx=(event.getX(0)+event.getX(1))/2f;
            float my=(event.getY(0)+event.getY(1))/2f;
            if(!multiTouch){
                multiTouch=true; lastMultiX=mx; lastMultiY=my;
                drawing=false; draggingSelection=false; activeHandle=-1; freePoints.clear();
            }else if(!scaleDetector.isInProgress()){
                offsetX+=mx-lastMultiX; offsetY+=my-lastMultiY;
                lastMultiX=mx; lastMultiY=my; invalidate();
            }
            return true;
        }
        if(multiTouch){
            if(event.getActionMasked()==MotionEvent.ACTION_UP||event.getActionMasked()==MotionEvent.ACTION_POINTER_UP)multiTouch=false;
            return true;
        }

        float rawX=screenToWorldX(event.getX());
        float rawY=screenToWorldY(event.getY());
        float[] snapped=tool==TOOL_FREE?new float[]{rawX,rawY}:snapPoint(rawX,rawY,null);
        float x=snapped[0],y=snapped[1];

        switch(event.getActionMasked()){
            case MotionEvent.ACTION_DOWN:
                downScreenX=event.getX(); downScreenY=event.getY();
                if(tool==TOOL_SELECT){
                    activeHandle=findControlHandle(selected,rawX,rawY);
                    if(activeHandle>=0&&selected!=null){
                        draggingSelection=true;
                        dragUndoSaved=false;
                        snapVisible=false;
                        invalidate();
                        return true;
                    }
                    Entity hit=findHit(rawX,rawY);
                    selected=hit;
                    if(hit!=null){
                        draggingSelection=true;
                        lastDragX=rawX; lastDragY=rawY;
                        dragUndoSaved=false;
                    }
                    activeHandle=-1;
                    snapVisible=false;
                    invalidate();
                    return true;
                }
                if(tool==TOOL_POINT){
                    saveUndo();
                    Entity e=new PointEntity(x,y); addPrepared(e); selected=e; tool=TOOL_SELECT;
                    invalidate(); return true;
                }
                if(tool==TOOL_GUIDE){
                    saveUndo();
                    addPrepared(new GuideEntity(true,x));
                    addPrepared(new GuideEntity(false,y));
                    tool=TOOL_SELECT; invalidate(); return true;
                }
                startX=x; startY=y; endX=x; endY=y; drawing=true;
                if(tool==TOOL_FREE){freePoints.clear();freePoints.add(new PointF(x,y));}
                invalidate(); return true;

            case MotionEvent.ACTION_MOVE:
                if(tool==TOOL_SELECT&&draggingSelection&&selected!=null){
                    float movePx=(float)Math.hypot(event.getX()-downScreenX,event.getY()-downScreenY);
                    if(movePx>3f){
                        if(!dragUndoSaved){saveUndo();dragUndoSaved=true;}
                        if(activeHandle>=0){
                            float[] hp=snapPoint(rawX,rawY,selected);
                            selected.moveControlPoint(activeHandle,hp[0],hp[1]);
                        }else{
                            float dx=rawX-lastDragX,dy=rawY-lastDragY;
                            selected.translate(dx,dy);
                            lastDragX=rawX;lastDragY=rawY;
                        }
                        invalidate();
                    }
                    return true;
                }
                if(!drawing)return true;
                endX=x; endY=y; applyOrtho();
                if(tool==TOOL_FREE){
                    if(freePoints.isEmpty()||dist(freePoints.get(freePoints.size()-1).x,freePoints.get(freePoints.size()-1).y,x,y)>0.7f)
                        freePoints.add(new PointF(x,y));
                }
                invalidate(); return true;

            case MotionEvent.ACTION_UP:
                if(tool==TOOL_SELECT){
                    draggingSelection=false;dragUndoSaved=false;activeHandle=-1;snapVisible=false;invalidate();return true;
                }
                if(!drawing)return true;
                endX=x; endY=y; applyOrtho();
                float drawPx=(float)Math.hypot(event.getX()-downScreenX,event.getY()-downScreenY);
                if(drawPx<MIN_DRAW_PX&&tool!=TOOL_FREE){
                    drawing=false;freePoints.clear();invalidate();return true;
                }
                saveUndo();
                Entity made=null;
                if(tool==TOOL_LINE)made=new LineEntity(startX,startY,endX,endY);
                else if(tool==TOOL_RECT)made=new RectEntity(startX,startY,endX,endY);
                else if(tool==TOOL_CIRCLE)made=new CircleEntity(startX,startY,dist(startX,startY,endX,endY));
                else if(tool==TOOL_MEASURE)made=new MeasureEntity(startX,startY,endX,endY);
                else if(tool==TOOL_ARC)made=new ArcEntity(startX,startY,dist(startX,startY,endX,endY),180,180);
                else if(tool==TOOL_POLYGON)made=PolygonEntity.regular(polygonSides,startX,startY,dist(startX,startY,endX,endY));
                else if(tool==TOOL_FREE&&freePoints.size()>1)made=new PolylineEntity(freePoints,false);
                if(made!=null){addPrepared(made);selected=made;}
                drawing=false;freePoints.clear();snapVisible=false;tool=TOOL_SELECT;invalidate();return true;

            case MotionEvent.ACTION_CANCEL:
                drawing=false;draggingSelection=false;activeHandle=-1;freePoints.clear();snapVisible=false;invalidate();return true;
        }
        return true;
    }

    private void applyOrtho(){
        if(!orthoEnabled||(tool!=TOOL_LINE&&tool!=TOOL_MEASURE))return;
        if(Math.abs(endX-startX)>=Math.abs(endY-startY))endY=startY;else endX=startX;
    }

    private int findControlHandle(Entity e,float x,float y){
        if(e==null)return-1;
        float radius=HANDLE_RADIUS_PX/(PX_PER_MM*viewScale);
        List<ControlPoint> cps=e.controlPoints();
        int best=-1;float bd=Float.MAX_VALUE;
        for(int i=0;i<cps.size();i++){
            ControlPoint cp=cps.get(i);
            float d=dist(x,y,cp.x,cp.y);
            if(d<radius&&d<bd){best=i;bd=d;}
        }
        return best;
    }

    private float[] snapPoint(float x,float y,Entity exclude){
        snapVisible=false;snapLabel="";
        if(!snapEnabled)return new float[]{x,y};
        float radius=SNAP_RADIUS_PX/(PX_PER_MM*viewScale);
        SnapCandidate best=null;

        for(Entity e:entities){
            if(e==exclude||!isVisible(e))continue;
            for(SnapPoint q:e.snapPoints()){
                float d=dist(x,y,q.x,q.y);
                if(d<=radius&&(best==null||d<best.d))best=new SnapCandidate(q.x,q.y,d,q.label);
            }
            PointF near=e.nearestPoint(x,y);
            if(near!=null){
                float d=dist(x,y,near.x,near.y);
                if(d<=radius*.72f&&(best==null||d<best.d))best=new SnapCandidate(near.x,near.y,d,"روی شیء");
            }
        }

        for(int i=0;i<entities.size();i++){
            if(entities.get(i)==exclude||!(entities.get(i)instanceof LineEntity)||!isVisible(entities.get(i)))continue;
            for(int j=i+1;j<entities.size();j++){
                if(entities.get(j)==exclude||!(entities.get(j)instanceof LineEntity)||!isVisible(entities.get(j)))continue;
                PointF ip=lineIntersection((LineEntity)entities.get(i),(LineEntity)entities.get(j));
                if(ip!=null){
                    float d=dist(x,y,ip.x,ip.y);
                    if(d<=radius&&(best==null||d<best.d))best=new SnapCandidate(ip.x,ip.y,d,"تقاطع");
                }
            }
        }

        if(showGuides){
            for(Entity e:entities){
                if(!(e instanceof GuideEntity))continue;
                GuideEntity g=(GuideEntity)e;
                float gx=g.vertical?g.value:x;
                float gy=g.vertical?y:g.value;
                float d=dist(x,y,gx,gy);
                if(d<=radius*.7f&&(best==null||d<best.d))best=new SnapCandidate(gx,gy,d,"راهنما");
            }
        }

        if(best!=null){
            snapX=best.x;snapY=best.y;snapVisible=true;snapLabel=best.label;
            return new float[]{best.x,best.y};
        }
        float gx=Math.round(x/GRID_MM)*GRID_MM,gy=Math.round(y/GRID_MM)*GRID_MM;
        if(dist(x,y,gx,gy)<=radius*.58f){
            snapX=gx;snapY=gy;snapVisible=true;snapLabel="Grid";
            return new float[]{gx,gy};
        }
        return new float[]{x,y};
    }

    private Entity findHit(float x,float y){
        float tol=HIT_RADIUS_PX/(PX_PER_MM*viewScale);
        Entity best=null;float bd=Float.MAX_VALUE;
        for(int i=entities.size()-1;i>=0;i--){
            Entity e=entities.get(i);
            if(!isVisible(e))continue;
            float d=e.selectionDistance(x,y);
            if(d<=tol&&d<bd){best=e;bd=d;}
        }
        return best;
    }

    private List<Entity> snapshotEntities(){
        List<Entity> snapshot=new ArrayList<>();
        for(Entity e:entities)snapshot.add(e.copy());
        return snapshot;
    }

    private void restoreSnapshot(List<Entity> snapshot){
        entities.clear();
        for(Entity e:snapshot)entities.add(e.copy());
        selected=null;
        tool=TOOL_SELECT;
        drawing=false;
        draggingSelection=false;
        dragUndoSaved=false;
        activeHandle=-1;
        multiTouch=false;
        freePoints.clear();
        snapVisible=false;
        invalidate();
    }

    private void saveUndo(){
        undoStack.addLast(snapshotEntities());
        while(undoStack.size()>MAX_UNDO)undoStack.removeFirst();
        // Any new edit after Undo starts a new branch and invalidates Redo.
        redoStack.clear();
    }

    private void addPrepared(Entity e){e.setLayer(currentLayer);e.setColor(currentColor);entities.add(e);}
    private void copyMeta(Entity from,Entity to){to.setLayer(from.getLayer());to.setColor(from.getColor());to.setExtrusion(from.getExtrusion());}
    private boolean isVisible(Entity e){Boolean v=layers.get(e.getLayer());return v==null||v;}
    private float screenToWorldX(float sx){return(sx-offsetX)/(PX_PER_MM*viewScale);}
    private float screenToWorldY(float sy){return(sy-offsetY)/(PX_PER_MM*viewScale);}

    // Stable subclass-facing Sketch core contract. Unique names intentionally
    // avoid collisions with legacy private helpers in intermediate subclasses.
    protected final Entity coreFindHit(float x, float y) { return findHit(x, y); }
    protected final void coreSaveUndo() { saveUndo(); }
    protected final boolean coreIsVisible(Entity e) { return isVisible(e); }
    protected final float coreScreenToWorldX(float sx) { return screenToWorldX(sx); }
    protected final float coreScreenToWorldY(float sy) { return screenToWorldY(sy); }
    protected final void coreObserveScaleGesture(MotionEvent event) {
        scaleDetector.onTouchEvent(event);
    }

    /** Adds exact projected/reference sketch geometry without exposing private entity classes. */
    protected final Entity coreAddConstructionLine(float x1,float y1,float x2,float y2) {
        Entity e=new LineEntity(x1,y1,x2,y2);e.setConstruction(true);addPrepared(e);return e;
    }
    protected final Entity coreAddConstructionCircle(float cx,float cy,float radius) {
        Entity e=new CircleEntity(cx,cy,Math.abs(radius));e.setConstruction(true);addPrepared(e);return e;
    }
    protected final Entity coreAddConstructionArc(float cx,float cy,float radius,float startDeg,float sweepDeg) {
        Entity e=new ArcEntity(cx,cy,Math.abs(radius),startDeg,sweepDeg);e.setConstruction(true);addPrepared(e);return e;
    }

    /** Stable metadata for associative external/reference geometry. */
    protected final void coreSetReferenceTag(Entity e,String tag){if(e!=null)e.setReferenceTag(tag);}
    protected final boolean coreHasReferenceTag(String tag){
        if(tag==null||tag.isEmpty())return false;
        for(Entity e:entities)if(tag.equals(e.getReferenceTag()))return true;
        return false;
    }
    protected final int coreCountReferenceTag(String tag){
        if(tag==null||tag.isEmpty())return 0;int n=0;
        for(Entity e:entities)if(tag.equals(e.getReferenceTag()))n++;
        return n;
    }
    protected final int coreRemoveReferenceTag(String tag){
        if(tag==null||tag.isEmpty())return 0;int n=0;
        for(int i=entities.size()-1;i>=0;i--){
            Entity e=entities.get(i);if(tag.equals(e.getReferenceTag())){if(selected==e)selected=null;entities.remove(i);n++;}
        }
        return n;
    }

    public String executeCommand(String raw){
        if(raw==null)return"";
        String s=raw.trim();if(s.isEmpty())return"";
        String[] a=s.replace(',',' ').trim().split("\\s+");
        String cmd=a[0].toUpperCase(Locale.US);
        try{
            switch(cmd){
                case"L":case"LINE":require(a,5);saveUndo();selected=new LineEntity(f(a,1),f(a,2),f(a,3),f(a,4));addPrepared(selected);tool=TOOL_SELECT;invalidate();return"خط ساخته و انتخاب شد";
                case"REC":case"RECT":case"RECTANG":require(a,5);saveUndo();float rx=f(a,1),ry=f(a,2),rw=f(a,3),rh=f(a,4);selected=new RectEntity(rx,ry,rx+rw,ry+rh);addPrepared(selected);tool=TOOL_SELECT;invalidate();return"مستطیل ساخته و انتخاب شد";
                case"C":case"CIRCLE":require(a,4);saveUndo();selected=new CircleEntity(f(a,1),f(a,2),Math.abs(f(a,3)));addPrepared(selected);tool=TOOL_SELECT;invalidate();return"دایره ساخته و انتخاب شد";
                case"PO":case"POINT":require(a,3);saveUndo();selected=new PointEntity(f(a,1),f(a,2));addPrepared(selected);tool=TOOL_SELECT;invalidate();return"نقطه ساخته شد";
                case"A":case"ARC":require(a,6);saveUndo();selected=new ArcEntity(f(a,1),f(a,2),Math.abs(f(a,3)),f(a,4),f(a,5));addPrepared(selected);tool=TOOL_SELECT;invalidate();return"قوس ساخته و انتخاب شد";
                case"POL":case"POLYGON":require(a,5);int sides=Math.max(3,Math.min(64,Integer.parseInt(a[1])));saveUndo();selected=PolygonEntity.regular(sides,f(a,2),f(a,3),Math.abs(f(a,4)));addPrepared(selected);tool=TOOL_SELECT;invalidate();return"چندضلعی ساخته و انتخاب شد";
                case"POLYSIDES":require(a,2);polygonSides=Math.max(3,Math.min(64,Integer.parseInt(a[1])));return"تعداد ضلع: "+polygonSides;
                case"M":case"MOVE":require(a,3);if(selected==null)return"اول شکل را انتخاب کن";moveSelected(f(a,1),f(a,2));return"جابه‌جا شد";
                case"CO":case"COPY":require(a,3);if(selected==null)return"اول شکل را انتخاب کن";copySelected(f(a,1),f(a,2));return"کپی شد";
                case"O":case"OFFSET":require(a,2);return offsetSelected(f(a,1));
                case"RO":case"ROTATE":require(a,2);return rotateSelected(f(a,1));
                case"SC":case"SCALE":require(a,2);return scaleSelected(f(a,1));
                case"MI":case"MIRROR":require(a,2);if("X".equalsIgnoreCase(a[1]))return mirrorSelected(true,a.length>2?f(a,2):0);if("Y".equalsIgnoreCase(a[1]))return mirrorSelected(false,a.length>2?f(a,2):0);return"MIRROR X 0 یا MIRROR Y 0";
                case"AR":case"ARRAY":require(a,4);return arraySelected(Integer.parseInt(a[1]),f(a,2),f(a,3));
                case"LENGTH":require(a,2);return applySelectedDimension(a[1]);
                case"SIZE":require(a,3);return applySelectedDimension(a[1]+" "+a[2]);
                case"RADIUS":require(a,2);if(selected instanceof CircleEntity){saveUndo();((CircleEntity)selected).r=Math.abs(f(a,1));invalidate();return"شعاع تغییر کرد";}if(selected instanceof ArcEntity){saveUndo();((ArcEntity)selected).r=Math.abs(f(a,1));invalidate();return"شعاع تغییر کرد";}return"دایره یا قوس را انتخاب کن";
                case"DIAMETER":require(a,2);if(selected instanceof CircleEntity)return applySelectedDimension(a[1]);return"دایره را انتخاب کن";
                case"GUIDE":require(a,3);saveUndo();if("X".equalsIgnoreCase(a[1]))addPrepared(new GuideEntity(true,f(a,2)));else if("Y".equalsIgnoreCase(a[1]))addPrepared(new GuideEntity(false,f(a,2)));else return"GUIDE X 50 یا GUIDE Y 50";invalidate();return"Guide ساخته شد";
                case"TAPE":case"DIST":require(a,5);saveUndo();addPrepared(new MeasureEntity(f(a,1),f(a,2),f(a,3),f(a,4)));invalidate();return"فاصله = "+mm(dist(f(a,1),f(a,2),f(a,3),f(a,4)));
                case"ANGLE":case"PROTRACTOR":require(a,7);saveUndo();addPrepared(new AngleEntity(f(a,1),f(a,2),f(a,3),f(a,4),f(a,5),f(a,6)));invalidate();return"زاویه ساخته شد";
                case"LAYER":require(a,2);return setLayer(a[1]);
                case"ASSIGNLAYER":require(a,2);return assignSelectedLayer(a[1]);
                case"LAYERHIDE":require(a,2);return setLayerVisible(a[1],false);
                case"LAYERSHOW":require(a,2);return setLayerVisible(a[1],true);
                case"MATERIAL":require(a,2);return setMaterial(a[1]);
                case"SCENE":require(a,3);if("SAVE".equalsIgnoreCase(a[1])){scenes.put(a[2],new Scene(viewScale,offsetX,offsetY,showGrid,showAxes));return"Scene ذخیره شد";}if("LOAD".equalsIgnoreCase(a[1])){Scene sc=scenes.get(a[2]);if(sc==null)return"Scene پیدا نشد";viewScale=clamp(sc.scale,MIN_VIEW_SCALE,MAX_VIEW_SCALE);offsetX=sc.x;offsetY=sc.y;showGrid=sc.grid;showAxes=sc.axes;invalidate();return"Scene بارگذاری شد";}return"SCENE SAVE name یا SCENE LOAD name";
                case"P":case"PUSHPULL":case"EXTRUDE":require(a,2);if(selected==null)return"اول سطح بسته را انتخاب کن";if(selected.isConstruction())return"Construction قابل Extrude نیست";if(!selected.canExtrude())return"این شکل قابل اکسترود نیست";saveUndo();selected.setExtrusion(Math.abs(f(a,1)));invalidate();return"Push/Pull = "+mm(Math.abs(f(a,1)))+" (2.5D)";
                case"CONSTRUCTION":if(selected==null)return"اول Sketch را انتخاب کن";saveUndo();selected.setConstruction(true);invalidate();return"Construction روشن شد";
                case"NORMAL":case"REGULAR":if(selected==null)return"اول Sketch را انتخاب کن";saveUndo();selected.setConstruction(false);invalidate();return"Construction خاموش شد";
                case"ERASE":case"DELETE":if(selected==null)return"اول شکل را انتخاب کن";deleteSelected();return"حذف شد";
                case"U":case"UNDO":undo();return"Undo";
                case"ZOOMIN":case"ZIN":zoomBy(1.8f);return"Zoom In";
                case"ZOOMOUT":case"ZOUT":zoomBy(0.55f);return"Zoom Out";
                case"Z":case"ZOOM":case"FIT":fitAll();return"Fit شد";
                case"AXIS":toggleAxes();return showAxes?"محورها روشن":"محورها خاموش";
                case"GRID":toggleGrid();return showGrid?"Grid روشن":"Grid خاموش";
                case"GUIDES":toggleGuides();return showGuides?"Guide روشن":"Guide خاموش";
                case"DIMS":case"DIMENSIONS":toggleDimensions();return showDimensions?"ابعاد روشن":"ابعاد خاموش";
                case"SNAP":toggleSnap();return snapEnabled?"Snap روشن":"Snap خاموش";
                case"ORTHO":toggleOrtho();return orthoEnabled?"Ortho روشن":"Ortho خاموش";
                case"CLEAR":clearAll();return"صفحه پاک شد";
                case"SELECT":setTool(TOOL_SELECT);return"حالت انتخاب";
                case"DIM":setTool(TOOL_MEASURE);return"حالت اندازه‌گذاری";
                case"FREE":setTool(TOOL_FREE);return"حالت Freehand";
                case"GROUP":case"COMPONENT":return"انتخاب چندگانه در مرحله بعد";
                case"FOLLOWME":case"REVOLVE":case"LOFT":case"SWEEP":case"SHELL":case"UNION":case"SUBTRACT":case"INTERSECT":case"PROJECT":return"این فرمان به هسته Solid 3D نیاز دارد";
                default:return"فرمان ناشناخته: "+cmd;
            }
        }catch(Exception ex){return"فرمت فرمان درست نیست";}
    }

    public String buildDxf(){
        StringBuilder d=new StringBuilder();
        d.append("0\nSECTION\n2\nHEADER\n9\n$INSUNITS\n70\n4\n0\nENDSEC\n0\nSECTION\n2\nENTITIES\n");
        for(Entity e:entities)if(!e.isConstruction())e.appendDxf(d);
        d.append("0\nENDSEC\n0\nEOF\n");
        return d.toString();
    }

    protected interface Entity{
        void draw(Canvas c,Paint p,Paint text,Paint measure,float px,boolean dims);
        float selectionDistance(float x,float y);
        List<SnapPoint> snapPoints();
        List<ControlPoint> controlPoints();
        void moveControlPoint(int index,float x,float y);
        PointF nearestPoint(float x,float y);
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
        String shortName();
        String getLayer();
        void setLayer(String layer);
        int getColor();
        void setColor(int color);
        boolean isConstruction();
        void setConstruction(boolean construction);
        boolean canExtrude();
        void setExtrusion(float h);
        float getExtrusion();
        String getReferenceTag();
        void setReferenceTag(String tag);
    }

    private abstract static class BaseEntity implements Entity{
        String layer="0";
        int color=Color.rgb(25,25,25);
        float extrusion=0f;
        boolean construction=false;
        String referenceTag="";
        public String getLayer(){return layer;}
        public void setLayer(String l){layer=l==null?"0":l;}
        public int getColor(){return color;}
        public void setColor(int c){color=c;}
        public boolean isConstruction(){return construction;}
        public void setConstruction(boolean value){construction=value;}
        public boolean canExtrude(){return false;}
        public void setExtrusion(float h){extrusion=h;}
        public float getExtrusion(){return extrusion;}
        public String getReferenceTag(){return referenceTag;}
        public void setReferenceTag(String tag){referenceTag=tag==null?"":tag;}
        void meta(BaseEntity e){e.layer=layer;e.color=color;e.extrusion=extrusion;e.construction=construction;e.referenceTag=referenceTag;}
    }

    private static class PointEntity extends BaseEntity{
        float x,y;
        PointEntity(float x,float y){this.x=x;this.y=y;}
        public void draw(Canvas c,Paint p,Paint t,Paint m,float px,boolean dims){c.drawCircle(x,y,4f*px,p);if(dims)c.drawText("("+fmt(x)+", "+fmt(y)+")",x+18f*px,y-8f*px,t);}
        public float selectionDistance(float a,float b){return dist(a,b,x,y);}
        public List<SnapPoint> snapPoints(){List<SnapPoint>q=new ArrayList<>();q.add(new SnapPoint(x,y,"نقطه"));return q;}
        public List<ControlPoint> controlPoints(){List<ControlPoint>q=new ArrayList<>();q.add(new ControlPoint(x,y));return q;}
        public void moveControlPoint(int i,float a,float b){x=a;y=b;}
        public PointF nearestPoint(float a,float b){return new PointF(x,y);}
        public void translate(float dx,float dy){x+=dx;y+=dy;}
        public void rotate(float cx,float cy,float d){PointF p=rotatePoint(x,y,cx,cy,d);x=p.x;y=p.y;}
        public void scale(float cx,float cy,float f){PointF p=scalePoint(x,y,cx,cy,f);x=p.x;y=p.y;}
        public void mirrorVertical(float a){x=2*a-x;}
        public void mirrorHorizontal(float a){y=2*a-y;}
        public Entity copy(){PointEntity e=new PointEntity(x,y);meta(e);return e;}
        public RectF bounds(){return new RectF(x,y,x,y);}
        public PointF center(){return new PointF(x,y);}
        public Entity offsetCopy(float d){return null;}
        public String describe(){return"Point ("+fmt(x)+", "+fmt(y)+") | Layer "+layer;}
        public String shortName(){return"نقطه";}
        public void appendDxf(StringBuilder d){d.append("0\nPOINT\n8\n").append(layer).append("\n10\n").append(x).append("\n20\n").append(-y).append("\n30\n0\n");}
    }

    protected static class LineEntity extends BaseEntity{
        float x1,y1,x2,y2;
        LineEntity(float a,float b,float c,float d){x1=a;y1=b;x2=c;y2=d;}
        void setLength(float len){float dx=x2-x1,dy=y2-y1,l=(float)Math.hypot(dx,dy);if(l<1e-6f){x2=x1+len;y2=y1;}else{x2=x1+dx/l*len;y2=y1+dy/l*len;}}
        public void draw(Canvas c,Paint p,Paint t,Paint m,float px,boolean dims){c.drawLine(x1,y1,x2,y2,p);if(dims)drawLength(c,x1,y1,x2,y2,t,px);}
        public float selectionDistance(float x,float y){return pointSeg(x,y,x1,y1,x2,y2);}
        public List<SnapPoint> snapPoints(){List<SnapPoint>q=new ArrayList<>();q.add(new SnapPoint(x1,y1,"ابتدا"));q.add(new SnapPoint(x2,y2,"انتها"));q.add(new SnapPoint((x1+x2)/2f,(y1+y2)/2f,"وسط"));return q;}
        public List<ControlPoint> controlPoints(){List<ControlPoint>q=new ArrayList<>();q.add(new ControlPoint(x1,y1));q.add(new ControlPoint(x2,y2));return q;}
        public void moveControlPoint(int i,float a,float b){if(i==0){x1=a;y1=b;}else{x2=a;y2=b;}}
        public PointF nearestPoint(float x,float y){return projectSeg(x,y,x1,y1,x2,y2);}
        public void translate(float dx,float dy){x1+=dx;x2+=dx;y1+=dy;y2+=dy;}
        public void rotate(float cx,float cy,float d){PointF a=rotatePoint(x1,y1,cx,cy,d),b=rotatePoint(x2,y2,cx,cy,d);x1=a.x;y1=a.y;x2=b.x;y2=b.y;}
        public void scale(float cx,float cy,float f){PointF a=scalePoint(x1,y1,cx,cy,f),b=scalePoint(x2,y2,cx,cy,f);x1=a.x;y1=a.y;x2=b.x;y2=b.y;}
        public void mirrorVertical(float a){x1=2*a-x1;x2=2*a-x2;}
        public void mirrorHorizontal(float a){y1=2*a-y1;y2=2*a-y2;}
        public Entity copy(){LineEntity e=new LineEntity(x1,y1,x2,y2);meta(e);return e;}
        public RectF bounds(){return new RectF(Math.min(x1,x2),Math.min(y1,y2),Math.max(x1,x2),Math.max(y1,y2));}
        public PointF center(){return new PointF((x1+x2)/2f,(y1+y2)/2f);}
        public Entity offsetCopy(float d){float dx=x2-x1,dy=y2-y1,l=(float)Math.hypot(dx,dy);if(l<1e-6f)return null;LineEntity e=new LineEntity(x1-dy/l*d,y1+dx/l*d,x2-dy/l*d,y2+dx/l*d);meta(e);return e;}
        public String describe(){return"Line | L="+mm(dist(x1,y1,x2,y2))+" | Layer "+layer;}
        public String shortName(){return"خط";}
        public void appendDxf(StringBuilder d){d.append("0\nLINE\n8\n").append(layer).append("\n10\n").append(x1).append("\n20\n").append(-y1).append("\n30\n0\n11\n").append(x2).append("\n21\n").append(-y2).append("\n31\n0\n");}
    }

    private static class RectEntity extends BaseEntity{
        PointF[] p=new PointF[4];
        RectEntity(float x1,float y1,float x2,float y2){p[0]=new PointF(x1,y1);p[1]=new PointF(x2,y1);p[2]=new PointF(x2,y2);p[3]=new PointF(x1,y2);}
        RectEntity(PointF[]s){for(int i=0;i<4;i++)p[i]=new PointF(s[i].x,s[i].y);}
        void setSize(float w,float h){PointF o=p[0];float ux=p[1].x-o.x,uy=p[1].y-o.y,ul=(float)Math.hypot(ux,uy);if(ul<1e-6f){ux=1;uy=0;ul=1;}ux/=ul;uy/=ul;float vx=p[3].x-o.x,vy=p[3].y-o.y,vl=(float)Math.hypot(vx,vy);if(vl<1e-6f){vx=-uy;vy=ux;vl=1;}vx/=vl;vy/=vl;p[1].set(o.x+ux*w,o.y+uy*w);p[3].set(o.x+vx*h,o.y+vy*h);p[2].set(p[1].x+vx*h,p[1].y+vy*h);}
        public boolean canExtrude(){return true;}
        public void draw(Canvas c,Paint paint,Paint t,Paint m,float px,boolean dims){drawPoly(c,p,paint);if(extrusion>0.01f)drawExtruded(c,p,extrusion,paint);if(dims){c.drawText(mm(dist(p[0].x,p[0].y,p[1].x,p[1].y)),(p[0].x+p[1].x)/2f,(p[0].y+p[1].y)/2f-5f*px,t);c.drawText(mm(dist(p[1].x,p[1].y,p[2].x,p[2].y)),(p[1].x+p[2].x)/2f+12f*px,(p[1].y+p[2].y)/2f,t);}}
        public float selectionDistance(float x,float y){if(pointInPolygon(x,y,p))return 0f;float b=Float.MAX_VALUE;for(int i=0;i<4;i++)b=Math.min(b,pointSeg(x,y,p[i].x,p[i].y,p[(i+1)%4].x,p[(i+1)%4].y));return b;}
        public List<SnapPoint> snapPoints(){return polygonSnaps(p);}
        public List<ControlPoint> controlPoints(){List<ControlPoint>q=new ArrayList<>();for(PointF v:p)q.add(new ControlPoint(v.x,v.y));return q;}
        public void moveControlPoint(int i,float x,float y){if(i<0||i>3)return;int opp=(i+2)%4,next=(i+1)%4,prev=(i+3)%4;PointF o=p[opp];PointF u=unit(p[next].x-p[i].x,p[next].y-p[i].y);PointF v=unit(p[prev].x-p[i].x,p[prev].y-p[i].y);float dx=x-o.x,dy=y-o.y;float w=-dot(dx,dy,u.x,u.y),h=-dot(dx,dy,v.x,v.y);p[i].set(o.x-u.x*w-v.x*h,o.y-u.y*w-v.y*h);p[next].set(o.x-v.x*h,o.y-v.y*h);p[prev].set(o.x-u.x*w,o.y-u.y*w);}
        public PointF nearestPoint(float x,float y){PointF best=null;float bd=Float.MAX_VALUE;for(int i=0;i<4;i++){PointF q=projectSeg(x,y,p[i].x,p[i].y,p[(i+1)%4].x,p[(i+1)%4].y);float d=dist(x,y,q.x,q.y);if(d<bd){bd=d;best=q;}}return best;}
        public void translate(float dx,float dy){for(PointF q:p){q.x+=dx;q.y+=dy;}}
        public void rotate(float cx,float cy,float d){for(int i=0;i<4;i++)p[i]=rotatePoint(p[i].x,p[i].y,cx,cy,d);}
        public void scale(float cx,float cy,float f){for(int i=0;i<4;i++)p[i]=scalePoint(p[i].x,p[i].y,cx,cy,f);}
        public void mirrorVertical(float a){for(PointF q:p)q.x=2*a-q.x;}
        public void mirrorHorizontal(float a){for(PointF q:p)q.y=2*a-q.y;}
        public Entity copy(){RectEntity e=new RectEntity(p);meta(e);return e;}
        public RectF bounds(){return boundsOf(p);}
        public PointF center(){return centroid(p);}
        public Entity offsetCopy(float d){PointF c=center();float w=dist(p[0].x,p[0].y,p[1].x,p[1].y),h=dist(p[0].x,p[0].y,p[3].x,p[3].y);if(w+2*d<=0||h+2*d<=0)return null;RectEntity e=(RectEntity)copy();float nw=(w+2*d)/w,nh=(h+2*d)/h;PointF u=unit(p[1].x-p[0].x,p[1].y-p[0].y),v=unit(p[3].x-p[0].x,p[3].y-p[0].y);for(PointF q:e.p){float dx=q.x-c.x,dy=q.y-c.y;float a=dot(dx,dy,u.x,u.y)*nw,b=dot(dx,dy,v.x,v.y)*nh;q.set(c.x+u.x*a+v.x*b,c.y+u.y*a+v.y*b);}return e;}
        public String describe(){return"Rectangle | "+mm(dist(p[0].x,p[0].y,p[1].x,p[1].y))+" × "+mm(dist(p[1].x,p[1].y,p[2].x,p[2].y))+(extrusion>0?" × H "+mm(extrusion):"")+" | Layer "+layer;}
        public String shortName(){return"مستطیل";}
        public void appendDxf(StringBuilder d){appendPolylineDxf(d,p,true,layer);}
    }

    private static class CircleEntity extends BaseEntity{
        float x,y,r;
        CircleEntity(float x,float y,float r){this.x=x;this.y=y;this.r=r;}
        public boolean canExtrude(){return true;}
        public void draw(Canvas c,Paint p,Paint t,Paint m,float px,boolean dims){c.drawCircle(x,y,r,p);float s=6f*px;c.drawLine(x-s,y,x+s,y,p);c.drawLine(x,y-s,x,y+s,p);if(extrusion>0.01f){float sx=-extrusion*.28f,sy=-extrusion*.18f;c.drawCircle(x+sx,y+sy,r,p);c.drawLine(x+r,y,x+r+sx,y+sy,p);c.drawLine(x-r,y,x-r+sx,y+sy,p);}if(dims)c.drawText("Ø "+mm(r*2),x,y-r-6f*px,t);}
        public float selectionDistance(float a,float b){float d=dist(a,b,x,y);return d<=r?0f:d-r;}
        public List<SnapPoint> snapPoints(){List<SnapPoint>q=new ArrayList<>();q.add(new SnapPoint(x,y,"مرکز"));q.add(new SnapPoint(x+r,y,"ربع"));q.add(new SnapPoint(x-r,y,"ربع"));q.add(new SnapPoint(x,y+r,"ربع"));q.add(new SnapPoint(x,y-r,"ربع"));return q;}
        public List<ControlPoint> controlPoints(){List<ControlPoint>q=new ArrayList<>();q.add(new ControlPoint(x,y));q.add(new ControlPoint(x+r,y));return q;}
        public void moveControlPoint(int i,float a,float b){if(i==0){float dx=a-x,dy=b-y;translate(dx,dy);}else r=Math.max(.1f,dist(x,y,a,b));}
        public PointF nearestPoint(float a,float b){float dx=a-x,dy=b-y,l=(float)Math.hypot(dx,dy);if(l<1e-6f)return new PointF(x+r,y);return new PointF(x+dx/l*r,y+dy/l*r);}
        public void translate(float dx,float dy){x+=dx;y+=dy;}
        public void rotate(float cx,float cy,float d){PointF p=rotatePoint(x,y,cx,cy,d);x=p.x;y=p.y;}
        public void scale(float cx,float cy,float f){PointF p=scalePoint(x,y,cx,cy,f);x=p.x;y=p.y;r*=Math.abs(f);}
        public void mirrorVertical(float a){x=2*a-x;}
        public void mirrorHorizontal(float a){y=2*a-y;}
        public Entity copy(){CircleEntity e=new CircleEntity(x,y,r);meta(e);return e;}
        public RectF bounds(){return new RectF(x-r,y-r,x+r,y+r);}
        public PointF center(){return new PointF(x,y);}
        public Entity offsetCopy(float d){if(r+d<=0)return null;CircleEntity e=new CircleEntity(x,y,r+d);meta(e);return e;}
        public String describe(){return"Circle | مرکز ("+fmt(x)+", "+fmt(y)+") | Ø "+mm(r*2)+(extrusion>0?" | H "+mm(extrusion):"")+" | Layer "+layer;}
        public String shortName(){return"دایره";}
        public void appendDxf(StringBuilder d){d.append("0\nCIRCLE\n8\n").append(layer).append("\n10\n").append(x).append("\n20\n").append(-y).append("\n30\n0\n40\n").append(r).append("\n");}
    }

    private static class ArcEntity extends BaseEntity{
        float x,y,r,start,sweep;
        ArcEntity(float x,float y,float r,float s,float w){this.x=x;this.y=y;this.r=r;start=s;sweep=w;}
        public void draw(Canvas c,Paint p,Paint t,Paint m,float px,boolean dims){c.drawArc(new RectF(x-r,y-r,x+r,y+r),start,sweep,false,p);float z=5f*px;c.drawLine(x-z,y,x+z,y,p);c.drawLine(x,y-z,x,y+z,p);if(dims)c.drawText("R "+mm(r),x,y-r-5f*px,t);}
        public float selectionDistance(float a,float b){return Math.abs(dist(a,b,x,y)-r);}
        public List<SnapPoint> snapPoints(){List<SnapPoint>q=new ArrayList<>();q.add(new SnapPoint(x,y,"مرکز"));PointF s=arcPoint(start);PointF e=arcPoint(start+sweep);q.add(new SnapPoint(s.x,s.y,"ابتدا"));q.add(new SnapPoint(e.x,e.y,"انتها"));return q;}
        public List<ControlPoint> controlPoints(){List<ControlPoint>q=new ArrayList<>();q.add(new ControlPoint(x,y));PointF s=arcPoint(start),e=arcPoint(start+sweep);q.add(new ControlPoint(s.x,s.y));q.add(new ControlPoint(e.x,e.y));return q;}
        private PointF arcPoint(float deg){double a=Math.toRadians(deg);return new PointF(x+(float)Math.cos(a)*r,y+(float)Math.sin(a)*r);}
        public void moveControlPoint(int i,float a,float b){if(i==0){translate(a-x,b-y);return;}float angle=(float)Math.toDegrees(Math.atan2(b-y,a-x));if(i==1){float end=start+sweep;r=Math.max(.1f,dist(x,y,a,b));start=angle;sweep=normalizeSweep(end-start);}else{r=Math.max(.1f,dist(x,y,a,b));sweep=normalizeSweep(angle-start);}}
        public PointF nearestPoint(float a,float b){float dx=a-x,dy=b-y,l=(float)Math.hypot(dx,dy);if(l<1e-6f)return null;return new PointF(x+dx/l*r,y+dy/l*r);}
        public void translate(float dx,float dy){x+=dx;y+=dy;}
        public void rotate(float cx,float cy,float d){PointF p=rotatePoint(x,y,cx,cy,d);x=p.x;y=p.y;start+=d;}
        public void scale(float cx,float cy,float f){PointF p=scalePoint(x,y,cx,cy,f);x=p.x;y=p.y;r*=Math.abs(f);}
        public void mirrorVertical(float a){x=2*a-x;start=180-start;sweep=-sweep;}
        public void mirrorHorizontal(float a){y=2*a-y;start=-start;sweep=-sweep;}
        public Entity copy(){ArcEntity e=new ArcEntity(x,y,r,start,sweep);meta(e);return e;}
        public RectF bounds(){return new RectF(x-r,y-r,x+r,y+r);}
        public PointF center(){return new PointF(x,y);}
        public Entity offsetCopy(float d){if(r+d<=0)return null;ArcEntity e=new ArcEntity(x,y,r+d,start,sweep);meta(e);return e;}
        public String describe(){return"Arc | مرکز ("+fmt(x)+", "+fmt(y)+") | R "+mm(r)+" | Layer "+layer;}
        public String shortName(){return"قوس";}
        public void appendDxf(StringBuilder d){d.append("0\nARC\n8\n").append(layer).append("\n10\n").append(x).append("\n20\n").append(-y).append("\n30\n0\n40\n").append(r).append("\n50\n").append(start).append("\n51\n").append(start+sweep).append("\n");}
    }

    private static class PolygonEntity extends BaseEntity{
        final List<PointF> points=new ArrayList<>();
        PolygonEntity(List<PointF>pts){for(PointF p:pts)points.add(new PointF(p.x,p.y));}
        static PolygonEntity regular(int sides,float cx,float cy,float r){List<PointF>pts=new ArrayList<>();for(int i=0;i<sides;i++){double a=-Math.PI/2+2*Math.PI*i/sides;pts.add(new PointF(cx+(float)Math.cos(a)*r,cy+(float)Math.sin(a)*r));}return new PolygonEntity(pts);}
        void setRadius(float r){PointF c=center();float old=points.isEmpty()?1:dist(c.x,c.y,points.get(0).x,points.get(0).y);if(old>1e-6f)scale(c.x,c.y,r/old);}
        public boolean canExtrude(){return true;}
        public void draw(Canvas c,Paint p,Paint t,Paint m,float px,boolean dims){PointF[]a=points.toArray(new PointF[0]);drawPoly(c,a,p);if(extrusion>0.01f)drawExtruded(c,a,extrusion,p);if(dims&&!points.isEmpty()){PointF cc=center();c.drawText(points.size()+" sides | R "+mm(dist(cc.x,cc.y,points.get(0).x,points.get(0).y)),cc.x,cc.y,t);}}
        public float selectionDistance(float x,float y){PointF[]a=points.toArray(new PointF[0]);if(pointInPolygon(x,y,a))return 0f;float b=Float.MAX_VALUE;for(int i=0;i<points.size();i++){PointF p=points.get(i),q=points.get((i+1)%points.size());b=Math.min(b,pointSeg(x,y,p.x,p.y,q.x,q.y));}return b;}
        public List<SnapPoint> snapPoints(){return polygonSnaps(points.toArray(new PointF[0]));}
        public List<ControlPoint> controlPoints(){List<ControlPoint>q=new ArrayList<>();PointF c=center();q.add(new ControlPoint(c.x,c.y));if(!points.isEmpty())q.add(new ControlPoint(points.get(0).x,points.get(0).y));return q;}
        public void moveControlPoint(int i,float x,float y){PointF c=center();if(i==0){translate(x-c.x,y-c.y);}else if(!points.isEmpty()){float old=dist(c.x,c.y,points.get(0).x,points.get(0).y);float n=dist(c.x,c.y,x,y);if(old>1e-6f)scale(c.x,c.y,n/old);}}
        public PointF nearestPoint(float x,float y){PointF best=null;float bd=Float.MAX_VALUE;for(int i=0;i<points.size();i++){PointF a=points.get(i),b=points.get((i+1)%points.size());PointF q=projectSeg(x,y,a.x,a.y,b.x,b.y);float d=dist(x,y,q.x,q.y);if(d<bd){bd=d;best=q;}}return best;}
        public void translate(float dx,float dy){for(PointF p:points){p.x+=dx;p.y+=dy;}}
        public void rotate(float cx,float cy,float d){for(int i=0;i<points.size();i++)points.set(i,rotatePoint(points.get(i).x,points.get(i).y,cx,cy,d));}
        public void scale(float cx,float cy,float f){for(int i=0;i<points.size();i++)points.set(i,scalePoint(points.get(i).x,points.get(i).y,cx,cy,f));}
        public void mirrorVertical(float a){for(PointF p:points)p.x=2*a-p.x;}
        public void mirrorHorizontal(float a){for(PointF p:points)p.y=2*a-p.y;}
        public Entity copy(){PolygonEntity e=new PolygonEntity(points);meta(e);return e;}
        public RectF bounds(){return boundsOf(points.toArray(new PointF[0]));}
        public PointF center(){return centroid(points.toArray(new PointF[0]));}
        public Entity offsetCopy(float d){PointF c=center();if(points.isEmpty())return null;float r=dist(c.x,c.y,points.get(0).x,points.get(0).y);if(r+d<=0)return null;PolygonEntity e=(PolygonEntity)copy();e.scale(c.x,c.y,(r+d)/r);return e;}
        public String describe(){return"Polygon "+points.size()+" sides"+(extrusion>0?" | H "+mm(extrusion):"")+" | Layer "+layer;}
        public String shortName(){return"چندضلعی";}
        public void appendDxf(StringBuilder d){appendPolylineDxf(d,points.toArray(new PointF[0]),true,layer);}
    }

    private static class PolylineEntity extends BaseEntity{
        final List<PointF>points=new ArrayList<>();final boolean closed;
        PolylineEntity(List<PointF>pts,boolean closed){for(PointF p:pts)points.add(new PointF(p.x,p.y));this.closed=closed;}
        public void draw(Canvas c,Paint p,Paint t,Paint m,float px,boolean dims){if(points.size()<2)return;Path path=new Path();path.moveTo(points.get(0).x,points.get(0).y);for(int i=1;i<points.size();i++)path.lineTo(points.get(i).x,points.get(i).y);if(closed)path.close();c.drawPath(path,p);}
        public float selectionDistance(float x,float y){if(closed&&pointInPolygon(x,y,points.toArray(new PointF[0])))return 0f;float b=Float.MAX_VALUE;for(int i=0;i<points.size()-1;i++)b=Math.min(b,pointSeg(x,y,points.get(i).x,points.get(i).y,points.get(i+1).x,points.get(i+1).y));return b;}
        public List<SnapPoint> snapPoints(){List<SnapPoint>q=new ArrayList<>();for(PointF p:points)q.add(new SnapPoint(p.x,p.y,"نقطه"));return q;}
        public List<ControlPoint> controlPoints(){List<ControlPoint>q=new ArrayList<>();int step=Math.max(1,points.size()/20);for(int i=0;i<points.size();i+=step)q.add(new ControlPoint(points.get(i).x,points.get(i).y));return q;}
        public void moveControlPoint(int i,float x,float y){if(points.isEmpty())return;int step=Math.max(1,points.size()/20);int idx=Math.min(points.size()-1,i*step);points.get(idx).set(x,y);}
        public PointF nearestPoint(float x,float y){PointF best=null;float bd=Float.MAX_VALUE;for(int i=0;i<points.size()-1;i++){PointF q=projectSeg(x,y,points.get(i).x,points.get(i).y,points.get(i+1).x,points.get(i+1).y);float d=dist(x,y,q.x,q.y);if(d<bd){bd=d;best=q;}}return best;}
        public void translate(float dx,float dy){for(PointF p:points){p.x+=dx;p.y+=dy;}}
        public void rotate(float cx,float cy,float d){for(int i=0;i<points.size();i++)points.set(i,rotatePoint(points.get(i).x,points.get(i).y,cx,cy,d));}
        public void scale(float cx,float cy,float f){for(int i=0;i<points.size();i++)points.set(i,scalePoint(points.get(i).x,points.get(i).y,cx,cy,f));}
        public void mirrorVertical(float a){for(PointF p:points)p.x=2*a-p.x;}
        public void mirrorHorizontal(float a){for(PointF p:points)p.y=2*a-p.y;}
        public Entity copy(){PolylineEntity e=new PolylineEntity(points,closed);meta(e);return e;}
        public RectF bounds(){return boundsOf(points.toArray(new PointF[0]));}
        public PointF center(){return centroid(points.toArray(new PointF[0]));}
        public Entity offsetCopy(float d){return null;}
        public String describe(){return(closed?"Polyline":"Freehand")+" | "+points.size()+" points | Layer "+layer;}
        public String shortName(){return"خط آزاد";}
        public void appendDxf(StringBuilder d){appendPolylineDxf(d,points.toArray(new PointF[0]),closed,layer);}
    }

    private static class MeasureEntity extends BaseEntity{
        float x1,y1,x2,y2;
        MeasureEntity(float a,float b,float c,float d){x1=a;y1=b;x2=c;y2=d;}
        public void draw(Canvas c,Paint p,Paint t,Paint m,float px,boolean dims){c.drawLine(x1,y1,x2,y2,m);drawArrow(c,x1,y1,x2,y2,m,px);if(dims)drawLength(c,x1,y1,x2,y2,t,px);}
        public float selectionDistance(float x,float y){return pointSeg(x,y,x1,y1,x2,y2);}
        public List<SnapPoint> snapPoints(){List<SnapPoint>q=new ArrayList<>();q.add(new SnapPoint(x1,y1,"ابتدا"));q.add(new SnapPoint(x2,y2,"انتها"));return q;}
        public List<ControlPoint> controlPoints(){List<ControlPoint>q=new ArrayList<>();q.add(new ControlPoint(x1,y1));q.add(new ControlPoint(x2,y2));return q;}
        public void moveControlPoint(int i,float x,float y){if(i==0){x1=x;y1=y;}else{x2=x;y2=y;}}
        public PointF nearestPoint(float x,float y){return projectSeg(x,y,x1,y1,x2,y2);}
        public void translate(float dx,float dy){x1+=dx;x2+=dx;y1+=dy;y2+=dy;}
        public void rotate(float cx,float cy,float d){PointF a=rotatePoint(x1,y1,cx,cy,d),b=rotatePoint(x2,y2,cx,cy,d);x1=a.x;y1=a.y;x2=b.x;y2=b.y;}
        public void scale(float cx,float cy,float f){PointF a=scalePoint(x1,y1,cx,cy,f),b=scalePoint(x2,y2,cx,cy,f);x1=a.x;y1=a.y;x2=b.x;y2=b.y;}
        public void mirrorVertical(float a){x1=2*a-x1;x2=2*a-x2;}
        public void mirrorHorizontal(float a){y1=2*a-y1;y2=2*a-y2;}
        public Entity copy(){MeasureEntity e=new MeasureEntity(x1,y1,x2,y2);meta(e);return e;}
        public RectF bounds(){return new RectF(Math.min(x1,x2),Math.min(y1,y2),Math.max(x1,x2),Math.max(y1,y2));}
        public PointF center(){return new PointF((x1+x2)/2f,(y1+y2)/2f);}
        public Entity offsetCopy(float d){return null;}
        public String describe(){return"Dimension | "+mm(dist(x1,y1,x2,y2));}
        public String shortName(){return"اندازه";}
        public void appendDxf(StringBuilder d){}
    }

    private static class AngleEntity extends BaseEntity{
        float ax,ay,cx,cy,bx,by;
        AngleEntity(float ax,float ay,float cx,float cy,float bx,float by){this.ax=ax;this.ay=ay;this.cx=cx;this.cy=cy;this.bx=bx;this.by=by;}
        public void draw(Canvas c,Paint p,Paint t,Paint m,float px,boolean dims){c.drawLine(cx,cy,ax,ay,m);c.drawLine(cx,cy,bx,by,m);if(dims)c.drawText(fmt(angleAt(ax,ay,cx,cy,bx,by))+"°",cx+15f*px,cy-10f*px,t);}
        public float selectionDistance(float x,float y){return Math.min(pointSeg(x,y,cx,cy,ax,ay),pointSeg(x,y,cx,cy,bx,by));}
        public List<SnapPoint> snapPoints(){List<SnapPoint>q=new ArrayList<>();q.add(new SnapPoint(ax,ay,"اول"));q.add(new SnapPoint(cx,cy,"راس"));q.add(new SnapPoint(bx,by,"دوم"));return q;}
        public List<ControlPoint> controlPoints(){List<ControlPoint>q=new ArrayList<>();q.add(new ControlPoint(ax,ay));q.add(new ControlPoint(cx,cy));q.add(new ControlPoint(bx,by));return q;}
        public void moveControlPoint(int i,float x,float y){if(i==0){ax=x;ay=y;}else if(i==1){cx=x;cy=y;}else{bx=x;by=y;}}
        public PointF nearestPoint(float x,float y){PointF a=projectSeg(x,y,cx,cy,ax,ay),b=projectSeg(x,y,cx,cy,bx,by);return dist(x,y,a.x,a.y)<dist(x,y,b.x,b.y)?a:b;}
        public void translate(float dx,float dy){ax+=dx;ay+=dy;cx+=dx;cy+=dy;bx+=dx;by+=dy;}
        public void rotate(float x,float y,float d){PointF a=rotatePoint(ax,ay,x,y,d),c=rotatePoint(cx,cy,x,y,d),b=rotatePoint(bx,by,x,y,d);ax=a.x;ay=a.y;cx=c.x;cy=c.y;bx=b.x;by=b.y;}
        public void scale(float x,float y,float f){PointF a=scalePoint(ax,ay,x,y,f),c=scalePoint(cx,cy,x,y,f),b=scalePoint(bx,by,x,y,f);ax=a.x;ay=a.y;cx=c.x;cy=c.y;bx=b.x;by=b.y;}
        public void mirrorVertical(float a){ax=2*a-ax;cx=2*a-cx;bx=2*a-bx;}
        public void mirrorHorizontal(float a){ay=2*a-ay;cy=2*a-cy;by=2*a-by;}
        public Entity copy(){AngleEntity e=new AngleEntity(ax,ay,cx,cy,bx,by);meta(e);return e;}
        public RectF bounds(){return boundsOf(new PointF[]{new PointF(ax,ay),new PointF(cx,cy),new PointF(bx,by)});}
        public PointF center(){return new PointF(cx,cy);}
        public Entity offsetCopy(float d){return null;}
        public String describe(){return"Angle | "+fmt(angleAt(ax,ay,cx,cy,bx,by))+"°";}
        public String shortName(){return"زاویه";}
        public void appendDxf(StringBuilder d){}
    }

    private static class GuideEntity extends BaseEntity{
        final boolean vertical;float value;
        GuideEntity(boolean vertical,float value){this.vertical=vertical;this.value=value;color=Color.rgb(65,145,200);}
        public boolean isConstruction(){return true;}
        public void draw(Canvas c,Paint p,Paint t,Paint m,float px,boolean dims){float far=100000f;if(vertical)c.drawLine(value,-far,value,far,p);else c.drawLine(-far,value,far,value,p);if(dims)c.drawText((vertical?"X ":"Y ")+mm(value),vertical?value:0,vertical?0:value,t);}
        public float selectionDistance(float x,float y){return vertical?Math.abs(x-value):Math.abs(y-value);}
        public List<SnapPoint> snapPoints(){return new ArrayList<>();}
        public List<ControlPoint> controlPoints(){return new ArrayList<>();}
        public void moveControlPoint(int i,float x,float y){}
        public PointF nearestPoint(float x,float y){return vertical?new PointF(value,y):new PointF(x,value);}
        public void translate(float dx,float dy){value+=vertical?dx:dy;}
        public void rotate(float cx,float cy,float d){}
        public void scale(float cx,float cy,float f){}
        public void mirrorVertical(float a){if(vertical)value=2*a-value;}
        public void mirrorHorizontal(float a){if(!vertical)value=2*a-value;}
        public Entity copy(){GuideEntity e=new GuideEntity(vertical,value);meta(e);return e;}
        public RectF bounds(){return vertical?new RectF(value,0,value,0):new RectF(0,value,0,value);}
        public PointF center(){return vertical?new PointF(value,0):new PointF(0,value);}
        public Entity offsetCopy(float d){GuideEntity e=new GuideEntity(vertical,value+d);meta(e);return e;}
        public String describe(){return"Guide "+(vertical?"X ":"Y ")+mm(value);}
        public String shortName(){return"راهنما";}
        public void appendDxf(StringBuilder d){}
    }

    protected static class SnapPoint{
        float x,y;String label;
        SnapPoint(float x,float y,String label){this.x=x;this.y=y;this.label=label;}
    }

    protected static class ControlPoint{
        float x,y;
        ControlPoint(float x,float y){this.x=x;this.y=y;}
    }

    private static class SnapCandidate{
        float x,y,d;String label;
        SnapCandidate(float x,float y,float d,String label){this.x=x;this.y=y;this.d=d;this.label=label;}
    }

    private static class Scene{
        float scale,x,y;boolean grid,axes;
        Scene(float scale,float x,float y,boolean grid,boolean axes){this.scale=scale;this.x=x;this.y=y;this.grid=grid;this.axes=axes;}
    }

    private static void require(String[]a,int n){if(a.length<n)throw new IllegalArgumentException();}
    private static float f(String[]a,int i){return Float.parseFloat(a[i]);}
    private static float clamp(float v,float min,float max){return Math.max(min,Math.min(max,v));}
    private static float dist(float x1,float y1,float x2,float y2){return(float)Math.hypot(x2-x1,y2-y1);}
    private static String mm(float v){return String.format(Locale.US,"%.1f mm",v);}
    private static String fmt(float v){return String.format(Locale.US,"%.2f",v);}
    private static PointF centerOf(RectF r){return new PointF((r.left+r.right)/2f,(r.top+r.bottom)/2f);}
    private static float dot(float ax,float ay,float bx,float by){return ax*bx+ay*by;}
    private static PointF unit(float x,float y){float l=(float)Math.hypot(x,y);if(l<1e-6f)return new PointF(1,0);return new PointF(x/l,y/l);}
    private static float normalizeSweep(float d){while(d>360)d-=360;while(d<-360)d+=360;return d;}

    private static String toolName(int t){switch(t){case TOOL_SELECT:return"انتخاب";case TOOL_POINT:return"نقطه";case TOOL_LINE:return"خط";case TOOL_RECT:return"مستطیل";case TOOL_CIRCLE:return"دایره";case TOOL_MEASURE:return"اندازه";case TOOL_ARC:return"قوس";case TOOL_POLYGON:return"چندضلعی";case TOOL_FREE:return"آزاد";case TOOL_GUIDE:return"راهنما";default:return"";}}

    private static int materialColor(String m){if(m==null)return Color.rgb(25,25,25);String s=m.trim().toUpperCase(Locale.US);if("WOOD".equals(s)||"CHOB".equals(s)||"چوب".equals(m))return Color.rgb(125,85,45);if("MDF".equals(s))return Color.rgb(145,110,70);if("METAL".equals(s)||"فلز".equals(m))return Color.rgb(90,100,110);if("GLASS".equals(s)||"شیشه".equals(m))return Color.rgb(80,150,175);return Color.rgb(25,25,25);}

    private static void drawLength(Canvas c,float x1,float y1,float x2,float y2,Paint t,float px){c.drawText(mm(dist(x1,y1,x2,y2)),(x1+x2)/2f,(y1+y2)/2f-4f*px,t);}

    private static float pointSeg(float px,float py,float x1,float y1,float x2,float y2){PointF q=projectSeg(px,py,x1,y1,x2,y2);return dist(px,py,q.x,q.y);}
    private static PointF projectSeg(float px,float py,float x1,float y1,float x2,float y2){float dx=x2-x1,dy=y2-y1,l2=dx*dx+dy*dy;if(l2<1e-6f)return new PointF(x1,y1);float t=((px-x1)*dx+(py-y1)*dy)/l2;t=clamp(t,0,1);return new PointF(x1+t*dx,y1+t*dy);}

    private static PointF rotatePoint(float x,float y,float cx,float cy,float deg){double r=Math.toRadians(deg);float dx=x-cx,dy=y-cy;return new PointF(cx+(float)(dx*Math.cos(r)-dy*Math.sin(r)),cy+(float)(dx*Math.sin(r)+dy*Math.cos(r)));}
    private static PointF scalePoint(float x,float y,float cx,float cy,float factor){return new PointF(cx+(x-cx)*factor,cy+(y-cy)*factor);}

    private static float angleAt(float ax,float ay,float cx,float cy,float bx,float by){double a1=Math.atan2(ay-cy,ax-cx),a2=Math.atan2(by-cy,bx-cx),d=Math.toDegrees(a2-a1);while(d<0)d+=360;while(d>=360)d-=360;if(d>180)d=360-d;return(float)d;}

    private static PointF lineIntersection(LineEntity a,LineEntity b){float x1=a.x1,y1=a.y1,x2=a.x2,y2=a.y2,x3=b.x1,y3=b.y1,x4=b.x2,y4=b.y2;float den=(x1-x2)*(y3-y4)-(y1-y2)*(x3-x4);if(Math.abs(den)<1e-6f)return null;float px=((x1*y2-y1*x2)*(x3-x4)-(x1-x2)*(x3*y4-y3*x4))/den;float py=((x1*y2-y1*x2)*(y3-y4)-(y1-y2)*(x3*y4-y3*x4))/den;if(pointSeg(px,py,x1,y1,x2,y2)>.01f||pointSeg(px,py,x3,y3,x4,y4)>.01f)return null;return new PointF(px,py);}

    private static boolean pointInPolygon(float x,float y,PointF[]p){boolean inside=false;for(int i=0,j=p.length-1;i<p.length;j=i++){float xi=p[i].x,yi=p[i].y,xj=p[j].x,yj=p[j].y;boolean hit=((yi>y)!=(yj>y))&&(x<(xj-xi)*(y-yi)/(yj-yi+1e-12f)+xi);if(hit)inside=!inside;}return inside;}

    private static List<SnapPoint> polygonSnaps(PointF[]p){List<SnapPoint>q=new ArrayList<>();for(int i=0;i<p.length;i++){PointF a=p[i],b=p[(i+1)%p.length];q.add(new SnapPoint(a.x,a.y,"گوشه"));q.add(new SnapPoint((a.x+b.x)/2f,(a.y+b.y)/2f,"وسط"));}PointF c=centroid(p);q.add(new SnapPoint(c.x,c.y,"مرکز"));return q;}

    private static RectF boundsOf(PointF[]p){if(p.length==0)return new RectF();float l=p[0].x,r=p[0].x,t=p[0].y,b=p[0].y;for(PointF q:p){l=Math.min(l,q.x);r=Math.max(r,q.x);t=Math.min(t,q.y);b=Math.max(b,q.y);}return new RectF(l,t,r,b);}
    private static PointF centroid(PointF[]p){if(p.length==0)return new PointF();float x=0,y=0;for(PointF q:p){x+=q.x;y+=q.y;}return new PointF(x/p.length,y/p.length);}

    private static void drawPoly(Canvas c,PointF[]p,Paint paint){if(p.length==0)return;Path path=new Path();path.moveTo(p[0].x,p[0].y);for(int i=1;i<p.length;i++)path.lineTo(p[i].x,p[i].y);path.close();c.drawPath(path,paint);}
    private static void drawExtruded(Canvas c,PointF[]p,float h,Paint paint){if(p.length<3)return;float sx=-h*.28f,sy=-h*.18f;PointF[]top=new PointF[p.length];for(int i=0;i<p.length;i++)top[i]=new PointF(p[i].x+sx,p[i].y+sy);drawPoly(c,top,paint);for(int i=0;i<p.length;i++)c.drawLine(p[i].x,p[i].y,top[i].x,top[i].y,paint);}

    private static void drawArrow(Canvas c,float x1,float y1,float x2,float y2,Paint p,float px){float dx=x2-x1,dy=y2-y1,l=(float)Math.hypot(dx,dy);if(l<1e-6f)return;float ux=dx/l,uy=dy/l,s=7f*px;c.drawLine(x1,y1,x1+ux*s-uy*s*.6f,y1+uy*s+ux*s*.6f,p);c.drawLine(x1,y1,x1+ux*s+uy*s*.6f,y1+uy*s-ux*s*.6f,p);c.drawLine(x2,y2,x2-ux*s-uy*s*.6f,y2-uy*s+ux*s*.6f,p);c.drawLine(x2,y2,x2-ux*s+uy*s*.6f,y2-uy*s-ux*s*.6f,p);}

    private static void appendPolylineDxf(StringBuilder d,PointF[]p,boolean closed,String layer){if(p.length==0)return;d.append("0\nLWPOLYLINE\n8\n").append(layer).append("\n90\n").append(p.length).append("\n70\n").append(closed?1:0).append("\n");for(PointF q:p)d.append("10\n").append(q.x).append("\n20\n").append(-q.y).append("\n");}
}

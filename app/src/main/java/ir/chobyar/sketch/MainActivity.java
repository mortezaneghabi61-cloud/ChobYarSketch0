package ir.chobyar.sketch;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PointF;
import android.graphics.RectF;
import android.net.Uri;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.Toast;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Locale;

public class MainActivity extends Activity {

    private static final int REQUEST_EXPORT_DXF = 1001;
    private DrawingView drawingView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        enterImmersiveMode();

        drawingView = new DrawingView();

        Button freeButton = makeButton("✏ آزاد", () -> drawingView.setTool(DrawingView.TOOL_FREE));
        Button pointButton = makeButton("• نقطه", () -> drawingView.setTool(DrawingView.TOOL_POINT));
        Button lineButton = makeButton("／ خط", () -> drawingView.setTool(DrawingView.TOOL_LINE));
        Button rectButton = makeButton("□ مستطیل", () -> drawingView.setTool(DrawingView.TOOL_RECT));
        Button circleButton = makeButton("○ دایره", () -> drawingView.setTool(DrawingView.TOOL_CIRCLE));
        Button measureButton = makeButton("↔ اندازه", () -> drawingView.setTool(DrawingView.TOOL_MEASURE));
        Button undoButton = makeButton("↶ Undo", () -> drawingView.undo());
        Button clearButton = makeButton("پاک", () -> drawingView.clearAll());
        Button exportButton = makeButton("DXF خروجی", this::exportDxf);

        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setOrientation(LinearLayout.HORIZONTAL);
        toolbar.setPadding(8, 8, 8, 8);
        toolbar.addView(freeButton);
        toolbar.addView(pointButton);
        toolbar.addView(lineButton);
        toolbar.addView(rectButton);
        toolbar.addView(circleButton);
        toolbar.addView(measureButton);
        toolbar.addView(undoButton);
        toolbar.addView(clearButton);
        toolbar.addView(exportButton);

        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.addView(toolbar);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);
        root.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        root.addView(drawingView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));

        setContentView(root);
    }

    private void enterImmersiveMode() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        );
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) enterImmersiveMode();
    }

    private Button makeButton(String text, Runnable action) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setOnClickListener(v -> action.run());
        return button;
    }

    private void exportDxf() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/dxf");
        intent.putExtra(Intent.EXTRA_TITLE, "ChobYarSketch.dxf");
        startActivityForResult(intent, REQUEST_EXPORT_DXF);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_EXPORT_DXF || resultCode != RESULT_OK || data == null) return;

        Uri uri = data.getData();
        if (uri == null) return;

        try (OutputStream out = getContentResolver().openOutputStream(uri)) {
            if (out == null) throw new IllegalStateException("Cannot open output");
            out.write(drawingView.buildDxf().getBytes(StandardCharsets.UTF_8));
            Toast.makeText(this, "DXF ذخیره شد", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "خطا در ذخیره DXF", Toast.LENGTH_LONG).show();
        }
    }

    private class DrawingView extends View {
        static final int TOOL_FREE = 0;
        static final int TOOL_POINT = 1;
        static final int TOOL_LINE = 2;
        static final int TOOL_RECT = 3;
        static final int TOOL_CIRCLE = 4;
        static final int TOOL_MEASURE = 5;

        private static final float PX_PER_MM = 3f;
        private static final float GRID_MM = 10f;
        private static final float SNAP_MM = 5f;

        private int currentTool = TOOL_LINE;
        private final Paint geometryPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint pointPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint measurePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final ArrayList<Shape> shapes = new ArrayList<>();

        private final ScaleGestureDetector scaleDetector;
        private float viewScale = 1f;
        private float offsetX = 0f;
        private float offsetY = 0f;
        private float lastMultiX;
        private float lastMultiY;
        private boolean multiTouch = false;

        private Path currentPath;
        private float startX, startY, endX, endY;
        private boolean drawing = false;

        DrawingView() {
            super(MainActivity.this);
            setBackgroundColor(Color.rgb(250, 250, 250));

            geometryPaint.setColor(Color.rgb(25, 25, 25));
            geometryPaint.setStyle(Paint.Style.STROKE);
            geometryPaint.setStrokeCap(Paint.Cap.ROUND);
            geometryPaint.setStrokeJoin(Paint.Join.ROUND);

            gridPaint.setColor(Color.rgb(225, 225, 225));
            gridPaint.setStrokeWidth(1f);

            textPaint.setColor(Color.rgb(30, 90, 180));
            textPaint.setTextAlign(Paint.Align.CENTER);

            pointPaint.setColor(Color.rgb(20, 100, 210));
            pointPaint.setStyle(Paint.Style.FILL);

            measurePaint.setColor(Color.rgb(210, 70, 40));
            measurePaint.setStyle(Paint.Style.STROKE);

            scaleDetector = new ScaleGestureDetector(MainActivity.this,
                    new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                        @Override
                        public boolean onScale(ScaleGestureDetector detector) {
                            float oldScale = viewScale;
                            viewScale *= detector.getScaleFactor();
                            viewScale = Math.max(0.25f, Math.min(viewScale, 8f));

                            float focusX = detector.getFocusX();
                            float focusY = detector.getFocusY();
                            float ratio = viewScale / oldScale;
                            offsetX = focusX - (focusX - offsetX) * ratio;
                            offsetY = focusY - (focusY - offsetY) * ratio;
                            invalidate();
                            return true;
                        }
                    });
        }

        private float screenToWorldX(float screenX) { return (screenX - offsetX) / (viewScale * PX_PER_MM); }
        private float screenToWorldY(float screenY) { return (screenY - offsetY) / (viewScale * PX_PER_MM); }
        private float snap(float value) { return Math.round(value / SNAP_MM) * SNAP_MM; }
        private float toolX(float screenX) { float world = screenToWorldX(screenX); return currentTool == TOOL_FREE ? world : snap(world); }
        private float toolY(float screenY) { float world = screenToWorldY(screenY); return currentTool == TOOL_FREE ? world : snap(world); }

        void setTool(int tool) { currentTool = tool; currentPath = null; drawing = false; invalidate(); }
        private float screenConstant(float pixels) { return pixels / (viewScale * PX_PER_MM); }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas); canvas.save(); canvas.translate(offsetX, offsetY); canvas.scale(viewScale * PX_PER_MM, viewScale * PX_PER_MM);
            geometryPaint.setStrokeWidth(screenConstant(3f)); gridPaint.setStrokeWidth(screenConstant(1f)); measurePaint.setStrokeWidth(screenConstant(2f)); textPaint.setTextSize(screenConstant(30f));
            float left=screenToWorldX(0), top=screenToWorldY(0), right=screenToWorldX(getWidth()), bottom=screenToWorldY(getHeight());
            float gridStartX=(float)Math.floor(left/GRID_MM)*GRID_MM, gridStartY=(float)Math.floor(top/GRID_MM)*GRID_MM;
            for(float x=gridStartX;x<=right;x+=GRID_MM) canvas.drawLine(x,top,x,bottom,gridPaint);
            for(float y=gridStartY;y<=bottom;y+=GRID_MM) canvas.drawLine(left,y,right,y,gridPaint);
            for(Shape shape:shapes) shape.draw(canvas,geometryPaint,textPaint,pointPaint,measurePaint);
            if(drawing) drawPreview(canvas); canvas.restore();
        }

        private void drawPreview(Canvas canvas) {
            if(currentTool==TOOL_FREE && currentPath!=null) canvas.drawPath(currentPath,geometryPaint);
            else if(currentTool==TOOL_LINE || currentTool==TOOL_MEASURE){ Paint p=currentTool==TOOL_MEASURE?measurePaint:geometryPaint; canvas.drawLine(startX,startY,endX,endY,p); drawLengthLabel(canvas,startX,startY,endX,endY,textPaint); }
            else if(currentTool==TOOL_RECT){ canvas.drawRect(Math.min(startX,endX),Math.min(startY,endY),Math.max(startX,endX),Math.max(startY,endY),geometryPaint); drawRectLabels(canvas,startX,startY,endX,endY,textPaint); }
            else if(currentTool==TOOL_CIRCLE){ float radius=distance(startX,startY,endX,endY); canvas.drawCircle(startX,startY,radius,geometryPaint); drawTextBubble(canvas,"R "+formatMm(radius),startX,startY-radius-screenConstant(10f),textPaint); }
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            scaleDetector.onTouchEvent(event);
            if(event.getPointerCount()>=2){ float midX=(event.getX(0)+event.getX(1))/2f, midY=(event.getY(0)+event.getY(1))/2f; if(!multiTouch){multiTouch=true;lastMultiX=midX;lastMultiY=midY;currentPath=null;drawing=false;} else if(!scaleDetector.isInProgress()){offsetX+=midX-lastMultiX;offsetY+=midY-lastMultiY;lastMultiX=midX;lastMultiY=midY;invalidate();} return true; }
            if(multiTouch){ if(event.getActionMasked()==MotionEvent.ACTION_UP || event.getActionMasked()==MotionEvent.ACTION_POINTER_UP) multiTouch=false; return true; }
            int action=event.getActionMasked();
            switch(action){
                case MotionEvent.ACTION_DOWN: startX=toolX(event.getX());startY=toolY(event.getY());endX=startX;endY=startY; if(currentTool==TOOL_POINT){shapes.add(new PointShape(startX,startY));invalidate();return true;} drawing=true; if(currentTool==TOOL_FREE){currentPath=new Path();currentPath.moveTo(startX,startY);} invalidate();return true;
                case MotionEvent.ACTION_MOVE: endX=toolX(event.getX());endY=toolY(event.getY());if(currentTool==TOOL_FREE&&currentPath!=null)currentPath.lineTo(endX,endY);invalidate();return true;
                case MotionEvent.ACTION_UP: endX=toolX(event.getX());endY=toolY(event.getY()); if(currentTool==TOOL_FREE&&currentPath!=null){shapes.add(new PathShape(new Path(currentPath)));currentPath=null;}else if(currentTool==TOOL_LINE)shapes.add(new LineShape(startX,startY,endX,endY));else if(currentTool==TOOL_RECT)shapes.add(new RectShape(startX,startY,endX,endY));else if(currentTool==TOOL_CIRCLE)shapes.add(new CircleShape(startX,startY,endX,endY));else if(currentTool==TOOL_MEASURE)shapes.add(new MeasureShape(startX,startY,endX,endY)); drawing=false;invalidate();return true;
                case MotionEvent.ACTION_CANCEL: currentPath=null;drawing=false;invalidate();return true;
            } return true;
        }

        void undo(){if(!shapes.isEmpty()){shapes.remove(shapes.size()-1);invalidate();}}
        void clearAll(){shapes.clear();currentPath=null;drawing=false;invalidate();}
        String buildDxf(){StringBuilder dxf=new StringBuilder();dxf.append("0\nSECTION\n2\nHEADER\n0\nENDSEC\n");dxf.append("0\nSECTION\n2\nENTITIES\n");for(Shape shape:shapes)shape.appendDxf(dxf);dxf.append("0\nENDSEC\n0\nEOF\n");return dxf.toString();}
    }

    private static float distance(float x1,float y1,float x2,float y2){float dx=x2-x1,dy=y2-y1;return(float)Math.sqrt(dx*dx+dy*dy);}
    private static String formatMm(float mm){return String.format(Locale.US,"%.1f mm",mm);}
    private static void drawTextBubble(Canvas canvas,String text,float x,float y,Paint textPaint){canvas.drawText(text,x,y,textPaint);}
    private static void drawLengthLabel(Canvas canvas,float x1,float y1,float x2,float y2,Paint textPaint){float mx=(x1+x2)/2f,my=(y1+y2)/2f;drawTextBubble(canvas,formatMm(distance(x1,y1,x2,y2)),mx,my-3f,textPaint);}
    private static void drawRectLabels(Canvas canvas,float x1,float y1,float x2,float y2,Paint textPaint){float left=Math.min(x1,x2),right=Math.max(x1,x2),top=Math.min(y1,y2),bottom=Math.max(y1,y2);drawTextBubble(canvas,formatMm(right-left),(left+right)/2f,top-3f,textPaint);drawTextBubble(canvas,formatMm(bottom-top),right+8f,(top+bottom)/2f,textPaint);}

    private interface Shape{void draw(Canvas canvas,Paint geometryPaint,Paint textPaint,Paint pointPaint,Paint measurePaint);void appendDxf(StringBuilder dxf);}
    private static class PathShape implements Shape{private final Path path;PathShape(Path path){this.path=path;}public void draw(Canvas c,Paint g,Paint t,Paint p,Paint m){c.drawPath(path,g);}public void appendDxf(StringBuilder dxf){}}
    private static class PointShape implements Shape{private final float x,y;PointShape(float x,float y){this.x=x;this.y=y;}public void draw(Canvas c,Paint g,Paint t,Paint p,Paint m){c.drawCircle(x,y,1.8f,p);drawTextBubble(c,String.format(Locale.US,"(%.0f, %.0f)",x,y),x+8f,y-5f,t);}public void appendDxf(StringBuilder dxf){dxf.append("0\nPOINT\n8\n0\n10\n").append(x).append("\n20\n").append(-y).append("\n30\n0\n");}}
    private static class LineShape implements Shape{private final float x1,y1,x2,y2;LineShape(float x1,float y1,float x2,float y2){this.x1=x1;this.y1=y1;this.x2=x2;this.y2=y2;}public void draw(Canvas c,Paint g,Paint t,Paint p,Paint m){c.drawLine(x1,y1,x2,y2,g);drawLengthLabel(c,x1,y1,x2,y2,t);}public void appendDxf(StringBuilder dxf){appendDxfLine(dxf,x1,y1,x2,y2);}}
    private static class RectShape implements Shape{private final RectF rect;RectShape(float x1,float y1,float x2,float y2){rect=new RectF(Math.min(x1,x2),Math.min(y1,y2),Math.max(x1,x2),Math.max(y1,y2));}public void draw(Canvas c,Paint g,Paint t,Paint p,Paint m){c.drawRect(rect,g);drawRectLabels(c,rect.left,rect.top,rect.right,rect.bottom,t);}public void appendDxf(StringBuilder dxf){appendDxfLine(dxf,rect.left,rect.top,rect.right,rect.top);appendDxfLine(dxf,rect.right,rect.top,rect.right,rect.bottom);appendDxfLine(dxf,rect.right,rect.bottom,rect.left,rect.bottom);appendDxfLine(dxf,rect.left,rect.bottom,rect.left,rect.top);}}
    private static class CircleShape implements Shape{private final float centerX,centerY,radius;CircleShape(float x1,float y1,float x2,float y2){centerX=x1;centerY=y1;radius=distance(x1,y1,x2,y2);}public void draw(Canvas c,Paint g,Paint t,Paint p,Paint m){c.drawCircle(centerX,centerY,radius,g);drawTextBubble(c,"R "+formatMm(radius),centerX,centerY-radius-3f,t);}public void appendDxf(StringBuilder dxf){dxf.append("0\nCIRCLE\n8\n0\n10\n").append(centerX).append("\n20\n").append(-centerY).append("\n30\n0\n40\n").append(radius).append("\n");}}
    private static class MeasureShape implements Shape{private final float x1,y1,x2,y2;MeasureShape(float x1,float y1,float x2,float y2){this.x1=x1;this.y1=y1;this.x2=x2;this.y2=y2;}public void draw(Canvas c,Paint g,Paint t,Paint p,Paint m){c.drawLine(x1,y1,x2,y2,m);drawLengthLabel(c,x1,y1,x2,y2,t);}public void appendDxf(StringBuilder dxf){}}
    private static void appendDxfLine(StringBuilder dxf,float x1,float y1,float x2,float y2){dxf.append("0\nLINE\n8\n0\n10\n").append(x1).append("\n20\n").append(-y1).append("\n30\n0\n11\n").append(x2).append("\n21\n").append(-y2).append("\n31\n0\n");}
}

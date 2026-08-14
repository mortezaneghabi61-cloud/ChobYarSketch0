package ir.chobyar.sketch;

import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;

import java.util.ArrayList;

public class MainActivity extends Activity {

    private DrawingView drawingView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        );

        drawingView = new DrawingView();

        Button freeButton = makeButton("✏ آزاد", () -> drawingView.setTool(DrawingView.TOOL_FREE));
        Button lineButton = makeButton("／ خط", () -> drawingView.setTool(DrawingView.TOOL_LINE));
        Button rectButton = makeButton("□ مستطیل", () -> drawingView.setTool(DrawingView.TOOL_RECT));
        Button circleButton = makeButton("○ دایره", () -> drawingView.setTool(DrawingView.TOOL_CIRCLE));
        Button undoButton = makeButton("↶ Undo", () -> drawingView.undo());
        Button clearButton = makeButton("پاک کردن", () -> drawingView.clearAll());

        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setOrientation(LinearLayout.HORIZONTAL);
        toolbar.setPadding(8, 8, 8, 8);
        toolbar.addView(freeButton);
        toolbar.addView(lineButton);
        toolbar.addView(rectButton);
        toolbar.addView(circleButton);
        toolbar.addView(undoButton);
        toolbar.addView(clearButton);

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

    private Button makeButton(String text, Runnable action) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setOnClickListener(v -> action.run());
        return button;
    }

    private class DrawingView extends View {
        static final int TOOL_FREE = 0;
        static final int TOOL_LINE = 1;
        static final int TOOL_RECT = 2;
        static final int TOOL_CIRCLE = 3;

        private static final float GRID_SIZE = 50f;
        private int currentTool = TOOL_FREE;
        private final Paint paint = new Paint();
        private final Paint gridPaint = new Paint();
        private final ArrayList<Shape> shapes = new ArrayList<>();
        private Path currentPath;
        private float startX, startY, endX, endY;
        private boolean drawing = false;

        DrawingView() {
            super(MainActivity.this);
            setBackgroundColor(Color.WHITE);

            paint.setColor(Color.BLACK);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setAntiAlias(true);
            paint.setStrokeWidth(6f);

            gridPaint.setColor(Color.LTGRAY);
            gridPaint.setStrokeWidth(1f);
        }

        private float snap(float value) {
            return Math.round(value / GRID_SIZE) * GRID_SIZE;
        }

        private float toolX(float value) {
            return currentTool == TOOL_FREE ? value : snap(value);
        }

        private float toolY(float value) {
            return currentTool == TOOL_FREE ? value : snap(value);
        }

        void setTool(int tool) {
            currentTool = tool;
            currentPath = null;
            drawing = false;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);

            for (float x = 0; x < getWidth(); x += GRID_SIZE) {
                canvas.drawLine(x, 0, x, getHeight(), gridPaint);
            }
            for (float y = 0; y < getHeight(); y += GRID_SIZE) {
                canvas.drawLine(0, y, getWidth(), y, gridPaint);
            }

            for (Shape shape : shapes) {
                shape.draw(canvas, paint);
            }

            if (!drawing) return;

            if (currentTool == TOOL_FREE && currentPath != null) {
                canvas.drawPath(currentPath, paint);
            } else if (currentTool == TOOL_LINE) {
                canvas.drawLine(startX, startY, endX, endY, paint);
            } else if (currentTool == TOOL_RECT) {
                canvas.drawRect(
                        Math.min(startX, endX), Math.min(startY, endY),
                        Math.max(startX, endX), Math.max(startY, endY), paint);
            } else if (currentTool == TOOL_CIRCLE) {
                float dx = endX - startX;
                float dy = endY - startY;
                float radius = (float) Math.sqrt(dx * dx + dy * dy);
                canvas.drawCircle(startX, startY, radius, paint);
            }
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            int action = event.getActionMasked();

            switch (action) {
                case MotionEvent.ACTION_DOWN:
                    startX = toolX(event.getX());
                    startY = toolY(event.getY());
                    endX = startX;
                    endY = startY;
                    drawing = true;
                    if (currentTool == TOOL_FREE) {
                        currentPath = new Path();
                        currentPath.moveTo(startX, startY);
                    }
                    invalidate();
                    return true;

                case MotionEvent.ACTION_MOVE:
                    endX = toolX(event.getX());
                    endY = toolY(event.getY());
                    if (currentTool == TOOL_FREE && currentPath != null) {
                        for (int i = 0; i < event.getHistorySize(); i++) {
                            currentPath.lineTo(event.getHistoricalX(i), event.getHistoricalY(i));
                        }
                        currentPath.lineTo(endX, endY);
                    }
                    invalidate();
                    return true;

                case MotionEvent.ACTION_UP:
                    endX = toolX(event.getX());
                    endY = toolY(event.getY());

                    if (currentTool == TOOL_FREE && currentPath != null) {
                        currentPath.lineTo(endX, endY);
                        shapes.add(new PathShape(new Path(currentPath)));
                        currentPath = null;
                    } else if (currentTool == TOOL_LINE) {
                        shapes.add(new LineShape(startX, startY, endX, endY));
                    } else if (currentTool == TOOL_RECT) {
                        shapes.add(new RectShape(startX, startY, endX, endY));
                    } else if (currentTool == TOOL_CIRCLE) {
                        shapes.add(new CircleShape(startX, startY, endX, endY));
                    }

                    drawing = false;
                    invalidate();
                    return true;

                case MotionEvent.ACTION_CANCEL:
                    currentPath = null;
                    drawing = false;
                    invalidate();
                    return true;
            }
            return true;
        }

        void undo() {
            if (!shapes.isEmpty()) {
                shapes.remove(shapes.size() - 1);
                invalidate();
            }
        }

        void clearAll() {
            shapes.clear();
            currentPath = null;
            drawing = false;
            invalidate();
        }
    }

    private interface Shape {
        void draw(Canvas canvas, Paint paint);
    }

    private static class PathShape implements Shape {
        private final Path path;
        PathShape(Path path) { this.path = path; }
        @Override public void draw(Canvas canvas, Paint paint) { canvas.drawPath(path, paint); }
    }

    private static class LineShape implements Shape {
        private final float x1, y1, x2, y2;
        LineShape(float x1, float y1, float x2, float y2) {
            this.x1 = x1; this.y1 = y1; this.x2 = x2; this.y2 = y2;
        }
        @Override public void draw(Canvas canvas, Paint paint) { canvas.drawLine(x1, y1, x2, y2, paint); }
    }

    private static class RectShape implements Shape {
        private final RectF rect;
        RectShape(float x1, float y1, float x2, float y2) {
            rect = new RectF(Math.min(x1, x2), Math.min(y1, y2), Math.max(x1, x2), Math.max(y1, y2));
        }
        @Override public void draw(Canvas canvas, Paint paint) { canvas.drawRect(rect, paint); }
    }

    private static class CircleShape implements Shape {
        private final float centerX, centerY, radius;
        CircleShape(float x1, float y1, float x2, float y2) {
            centerX = x1;
            centerY = y1;
            float dx = x2 - x1;
            float dy = y2 - y1;
            radius = (float) Math.sqrt(dx * dx + dy * dy);
        }
        @Override public void draw(Canvas canvas, Paint paint) { canvas.drawCircle(centerX, centerY, radius, paint); }
    }
}

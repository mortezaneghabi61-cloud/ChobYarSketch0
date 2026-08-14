package ir.chobyar.sketch;

import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
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

        Button undoButton = makeButton("↶ برگرد", drawingView::undo);
        Button redoButton = makeButton("↷ دوباره", drawingView::redo);
        Button zoomInButton = makeButton("＋ زوم", drawingView::zoomIn);
        Button zoomOutButton = makeButton("－ زوم", drawingView::zoomOut);
        Button fitButton = makeButton("⛶ جاگذاری", drawingView::fitToScreen);
        Button gridButton = makeButton("# شبکه", drawingView::toggleGrid);
        Button snapButton = makeButton("⊕ چسبش", drawingView::toggleSnap);
        Button clearButton = makeButton("پاک کردن", drawingView::clearAll);

        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setOrientation(LinearLayout.HORIZONTAL);
        toolbar.setPadding(8, 8, 8, 8);

        toolbar.addView(freeButton);
        toolbar.addView(lineButton);
        toolbar.addView(rectButton);
        toolbar.addView(circleButton);
        toolbar.addView(undoButton);
        toolbar.addView(redoButton);
        toolbar.addView(zoomInButton);
        toolbar.addView(zoomOutButton);
        toolbar.addView(fitButton);
        toolbar.addView(gridButton);
        toolbar.addView(snapButton);
        toolbar.addView(clearButton);

        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.addView(toolbar);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);

        root.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        root.addView(drawingView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1
        ));

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
        private static final float MIN_SCALE = 0.25f;
        private static final float MAX_SCALE = 8f;
        private static final float BUTTON_ZOOM_FACTOR = 1.25f;

        private int currentTool = TOOL_FREE;

        private final Paint paint = new Paint();
        private final Paint gridPaint = new Paint();
        private final ArrayList<Shape> shapes = new ArrayList<>();
        private final ArrayList<Shape> redoShapes = new ArrayList<>();
        private final ScaleGestureDetector scaleDetector;

        private Path currentPath;

        private float startX;
        private float startY;
        private float endX;
        private float endY;

        private float scaleFactor = 1f;
        private float offsetX = 0f;
        private float offsetY = 0f;

        private float lastTouchX;
        private float lastTouchY;
        private boolean panning = false;
        private boolean drawing = false;
        private boolean gridVisible = true;
        private boolean snapEnabled = false;

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
            gridPaint.setStyle(Paint.Style.STROKE);
            gridPaint.setStrokeWidth(1f);

            scaleDetector = new ScaleGestureDetector(
                    MainActivity.this,
                    new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                        @Override
                        public boolean onScale(ScaleGestureDetector detector) {
                            float focusX = detector.getFocusX();
                            float focusY = detector.getFocusY();
                            applyScale(scaleFactor * detector.getScaleFactor(), focusX, focusY);
                            return true;
                        }
                    }
            );
        }

        void setTool(int tool) {
            currentTool = tool;
            currentPath = null;
            drawing = false;
            invalidate();
        }

        void toggleGrid() {
            gridVisible = !gridVisible;
            invalidate();
        }

        void toggleSnap() {
            snapEnabled = !snapEnabled;
        }

        void zoomIn() {
            applyScale(scaleFactor * BUTTON_ZOOM_FACTOR, getWidth() / 2f, getHeight() / 2f);
        }

        void zoomOut() {
            applyScale(scaleFactor / BUTTON_ZOOM_FACTOR, getWidth() / 2f, getHeight() / 2f);
        }

        private void applyScale(float requestedScale, float focusX, float focusY) {
            float newScale = Math.max(MIN_SCALE, Math.min(MAX_SCALE, requestedScale));
            if (newScale == scaleFactor) {
                return;
            }

            float worldFocusX = screenToWorldX(focusX);
            float worldFocusY = screenToWorldY(focusY);

            scaleFactor = newScale;
            offsetX = focusX - (worldFocusX * scaleFactor);
            offsetY = focusY - (worldFocusY * scaleFactor);
            invalidate();
        }

        void fitToScreen() {
            RectF bounds = getShapesBounds();
            if (bounds == null || getWidth() <= 0 || getHeight() <= 0) {
                scaleFactor = 1f;
                offsetX = 0f;
                offsetY = 0f;
                invalidate();
                return;
            }

            float padding = 80f;
            float availableWidth = Math.max(1f, getWidth() - (padding * 2f));
            float availableHeight = Math.max(1f, getHeight() - (padding * 2f));
            float contentWidth = Math.max(1f, bounds.width());
            float contentHeight = Math.max(1f, bounds.height());

            scaleFactor = Math.max(
                    MIN_SCALE,
                    Math.min(MAX_SCALE, Math.min(availableWidth / contentWidth, availableHeight / contentHeight))
            );

            offsetX = (getWidth() - (contentWidth * scaleFactor)) / 2f - (bounds.left * scaleFactor);
            offsetY = (getHeight() - (contentHeight * scaleFactor)) / 2f - (bounds.top * scaleFactor);
            invalidate();
        }

        private RectF getShapesBounds() {
            if (shapes.isEmpty()) {
                return null;
            }

            RectF total = null;
            for (Shape shape : shapes) {
                RectF bounds = shape.getBounds();
                if (bounds == null) {
                    continue;
                }
                if (total == null) {
                    total = new RectF(bounds);
                } else {
                    total.union(bounds);
                }
            }
            return total;
        }

        private float snap(float value) {
            if (!snapEnabled) {
                return value;
            }
            return Math.round(value / GRID_SIZE) * GRID_SIZE;
        }

        private float screenToWorldX(float screenX) {
            return (screenX - offsetX) / scaleFactor;
        }

        private float screenToWorldY(float screenY) {
            return (screenY - offsetY) / scaleFactor;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);

            canvas.save();
            canvas.translate(offsetX, offsetY);
            canvas.scale(scaleFactor, scaleFactor);

            if (gridVisible) {
                drawGrid(canvas);
            }

            for (Shape shape : shapes) {
                shape.draw(canvas, paint);
            }

            if (drawing) {
                if (currentTool == TOOL_FREE && currentPath != null) {
                    canvas.drawPath(currentPath, paint);
                } else if (currentTool == TOOL_LINE) {
                    canvas.drawLine(startX, startY, endX, endY, paint);
                } else if (currentTool == TOOL_RECT) {
                    canvas.drawRect(
                            Math.min(startX, endX),
                            Math.min(startY, endY),
                            Math.max(startX, endX),
                            Math.max(startY, endY),
                            paint
                    );
                } else if (currentTool == TOOL_CIRCLE) {
                    float dx = endX - startX;
                    float dy = endY - startY;
                    float radius = (float) Math.sqrt((dx * dx) + (dy * dy));
                    canvas.drawCircle(startX, startY, radius, paint);
                }
            }

            canvas.restore();
        }

        private void drawGrid(Canvas canvas) {
            float left = screenToWorldX(0);
            float top = screenToWorldY(0);
            float right = screenToWorldX(getWidth());
            float bottom = screenToWorldY(getHeight());

            float startGridX = (float) Math.floor(left / GRID_SIZE) * GRID_SIZE;
            float startGridY = (float) Math.floor(top / GRID_SIZE) * GRID_SIZE;

            for (float x = startGridX; x <= right; x += GRID_SIZE) {
                canvas.drawLine(x, top, x, bottom, gridPaint);
            }

            for (float y = startGridY; y <= bottom; y += GRID_SIZE) {
                canvas.drawLine(left, y, right, y, gridPaint);
            }
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            scaleDetector.onTouchEvent(event);

            int action = event.getActionMasked();

            if (event.getPointerCount() >= 2 || scaleDetector.isInProgress()) {
                if (action == MotionEvent.ACTION_POINTER_DOWN || action == MotionEvent.ACTION_DOWN) {
                    panning = true;
                    lastTouchX = event.getX(0);
                    lastTouchY = event.getY(0);
                } else if (action == MotionEvent.ACTION_MOVE && event.getPointerCount() >= 2) {
                    float x = event.getX(0);
                    float y = event.getY(0);
                    offsetX += x - lastTouchX;
                    offsetY += y - lastTouchY;
                    lastTouchX = x;
                    lastTouchY = y;
                    invalidate();
                } else if (action == MotionEvent.ACTION_POINTER_UP || action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                    panning = false;
                    drawing = false;
                    currentPath = null;
                }
                return true;
            }

            float worldX = screenToWorldX(event.getX());
            float worldY = screenToWorldY(event.getY());

            switch (action) {
                case MotionEvent.ACTION_DOWN:
                    startX = currentTool == TOOL_FREE ? worldX : snap(worldX);
                    startY = currentTool == TOOL_FREE ? worldY : snap(worldY);
                    endX = startX;
                    endY = startY;
                    drawing = true;
                    panning = false;

                    if (currentTool == TOOL_FREE) {
                        currentPath = new Path();
                        currentPath.moveTo(startX, startY);
                    }

                    invalidate();
                    return true;

                case MotionEvent.ACTION_MOVE:
                    if (panning) {
                        return true;
                    }

                    endX = currentTool == TOOL_FREE ? worldX : snap(worldX);
                    endY = currentTool == TOOL_FREE ? worldY : snap(worldY);

                    if (currentTool == TOOL_FREE && currentPath != null) {
                        for (int i = 0; i < event.getHistorySize(); i++) {
                            float hx = screenToWorldX(event.getHistoricalX(i));
                            float hy = screenToWorldY(event.getHistoricalY(i));
                            currentPath.lineTo(hx, hy);
                        }
                        currentPath.lineTo(endX, endY);
                    }

                    invalidate();
                    return true;

                case MotionEvent.ACTION_UP:
                    if (!drawing) {
                        return true;
                    }

                    endX = currentTool == TOOL_FREE ? worldX : snap(worldX);
                    endY = currentTool == TOOL_FREE ? worldY : snap(worldY);

                    if (currentTool == TOOL_FREE && currentPath != null) {
                        currentPath.lineTo(endX, endY);
                        addShape(new PathShape(new Path(currentPath)));
                        currentPath = null;
                    } else if (currentTool == TOOL_LINE) {
                        addShape(new LineShape(startX, startY, endX, endY));
                    } else if (currentTool == TOOL_RECT) {
                        addShape(new RectShape(startX, startY, endX, endY));
                    } else if (currentTool == TOOL_CIRCLE) {
                        addShape(new CircleShape(startX, startY, endX, endY));
                    }

                    drawing = false;
                    invalidate();
                    return true;

                case MotionEvent.ACTION_CANCEL:
                    currentPath = null;
                    drawing = false;
                    panning = false;
                    invalidate();
                    return true;

                default:
                    return true;
            }
        }

        private void addShape(Shape shape) {
            shapes.add(shape);
            redoShapes.clear();
        }

        void undo() {
            if (!shapes.isEmpty()) {
                Shape shape = shapes.remove(shapes.size() - 1);
                redoShapes.add(shape);
                invalidate();
            }
        }

        void redo() {
            if (!redoShapes.isEmpty()) {
                Shape shape = redoShapes.remove(redoShapes.size() - 1);
                shapes.add(shape);
                invalidate();
            }
        }

        void clearAll() {
            shapes.clear();
            redoShapes.clear();
            currentPath = null;
            drawing = false;
            scaleFactor = 1f;
            offsetX = 0f;
            offsetY = 0f;
            invalidate();
        }
    }

    private interface Shape {
        void draw(Canvas canvas, Paint paint);
        RectF getBounds();
    }

    private static class PathShape implements Shape {
        private final Path path;

        PathShape(Path path) {
            this.path = path;
        }

        @Override
        public void draw(Canvas canvas, Paint paint) {
            canvas.drawPath(path, paint);
        }

        @Override
        public RectF getBounds() {
            RectF bounds = new RectF();
            path.computeBounds(bounds, true);
            return bounds;
        }
    }

    private static class LineShape implements Shape {
        private final float x1;
        private final float y1;
        private final float x2;
        private final float y2;

        LineShape(float x1, float y1, float x2, float y2) {
            this.x1 = x1;
            this.y1 = y1;
            this.x2 = x2;
            this.y2 = y2;
        }

        @Override
        public void draw(Canvas canvas, Paint paint) {
            canvas.drawLine(x1, y1, x2, y2, paint);
        }

        @Override
        public RectF getBounds() {
            return new RectF(
                    Math.min(x1, x2),
                    Math.min(y1, y2),
                    Math.max(x1, x2),
                    Math.max(y1, y2)
            );
        }
    }

    private static class RectShape implements Shape {
        private final RectF rect;

        RectShape(float x1, float y1, float x2, float y2) {
            rect = new RectF(
                    Math.min(x1, x2),
                    Math.min(y1, y2),
                    Math.max(x1, x2),
                    Math.max(y1, y2)
            );
        }

        @Override
        public void draw(Canvas canvas, Paint paint) {
            canvas.drawRect(rect, paint);
        }

        @Override
        public RectF getBounds() {
            return new RectF(rect);
        }
    }

    private static class CircleShape implements Shape {
        private final float centerX;
        private final float centerY;
        private final float radius;

        CircleShape(float x1, float y1, float x2, float y2) {
            centerX = x1;
            centerY = y1;
            float dx = x2 - x1;
            float dy = y2 - y1;
            radius = (float) Math.sqrt((dx * dx) + (dy * dy));
        }

        @Override
        public void draw(Canvas canvas, Paint paint) {
            canvas.drawCircle(centerX, centerY, radius, paint);
        }

        @Override
        public RectF getBounds() {
            return new RectF(
                    centerX - radius,
                    centerY - radius,
                    centerX + radius,
                    centerY + radius
            );
        }
    }
}

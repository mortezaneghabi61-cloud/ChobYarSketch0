package ir.chobyar.sketch;

import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;

import java.util.ArrayList;

public class MainActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        DrawingView drawingView = new DrawingView();

        Button undoButton = new Button(this);
        undoButton.setText("Undo");
        undoButton.setOnClickListener(v -> drawingView.undo());

        Button clearButton = new Button(this);
        clearButton.setText("پاک کردن");
        clearButton.setOnClickListener(v -> drawingView.clearAll());

        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setOrientation(LinearLayout.HORIZONTAL);
        toolbar.addView(
                undoButton,
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1)
        );
        toolbar.addView(
                clearButton,
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1)
        );

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);

        root.addView(
                toolbar,
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                )
        );

        root.addView(
                drawingView,
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        0,
                        1
                )
        );

        setContentView(root);
    }

    private class DrawingView extends View {

        private final Paint paint = new Paint();
        private final ArrayList<Path> paths = new ArrayList<>();

        private Path currentPath;

        DrawingView() {
            super(MainActivity.this);

            setBackgroundColor(Color.WHITE);

            paint.setColor(Color.BLACK);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setAntiAlias(true);
            paint.setStrokeWidth(7f);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);

            for (Path path : paths) {
                canvas.drawPath(path, paint);
            }

            if (currentPath != null) {
                canvas.drawPath(currentPath, paint);
            }
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {

            int action = event.getActionMasked();

            switch (action) {

                case MotionEvent.ACTION_DOWN:
                    currentPath = new Path();
                    currentPath.moveTo(event.getX(), event.getY());
                    invalidate();
                    return true;

                case MotionEvent.ACTION_MOVE:
                    if (currentPath != null) {

                        for (int i = 0; i < event.getHistorySize(); i++) {
                            currentPath.lineTo(
                                    event.getHistoricalX(i),
                                    event.getHistoricalY(i)
                            );
                        }

                        currentPath.lineTo(event.getX(), event.getY());

                        if (event.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS) {
                            float pressure = event.getPressure();

                            float width = 4f + (pressure * 10f);
                            paint.setStrokeWidth(width);
                        } else {
                            paint.setStrokeWidth(7f);
                        }

                        invalidate();
                    }
                    return true;

                case MotionEvent.ACTION_UP:
                    if (currentPath != null) {
                        currentPath.lineTo(event.getX(), event.getY());
                        paths.add(currentPath);
                        currentPath = null;
                        invalidate();
                    }
                    return true;

                case MotionEvent.ACTION_CANCEL:
                    currentPath = null;
                    invalidate();
                    return true;
            }

            return true;
        }

        void undo() {
            if (!paths.isEmpty()) {
                paths.remove(paths.size() - 1);
                invalidate();
            }
        }

        void clearAll() {
            paths.clear();
            currentPath = null;
            invalidate();
        }
    }
}

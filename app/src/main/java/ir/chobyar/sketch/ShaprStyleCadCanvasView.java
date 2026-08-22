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
import java.util.IdentityHashMap;
import java.util.Locale;

/**
 * Shapr-style numeric interaction layer.
 *
 * While a geometric tool is being dragged, a compact live dimension field is
 * drawn next to the preview. After creation, the same field stays attached to
 * the selected shape. Tapping it opens a type-specific numeric editor and
 * dragging it repositions only the dimension label while the sketch geometry
 * remains untouched. User-facing and internal values are millimeters.
 */
public class ShaprStyleCadCanvasView extends CentimeterCadCanvasView {

    private static final float PX_PER_MM = 3f;
    private static final float LABEL_DRAG_SLOP_PX = 7f;
    private static final float MIN_VIEW_SCALE = 0.02f;
    private static final float MAX_VIEW_SCALE = 64f;

    private final Paint fieldFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fieldStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fieldText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF exactFieldRect = new RectF();
    private final IdentityHashMap<Object, PointF> dimensionLabelOffsets = new IdentityHashMap<>();

    private Field selectedField;
    private Field viewScaleField;
    private Field offsetXField;
    private Field offsetYField;
    private Field drawingField;
    private Field startXField;
    private Field startYField;
    private Field endXField;
    private Field endYField;

    // SmartCadCanvasView has an older generic "exact dimension" chip. The
    // Shapr-style numeric chip supersedes it, so hide that legacy paint/hit box
    // to avoid a duplicate chip becoming visible after the numeric label moves.
    private Paint legacyChipPaint;
    private Paint legacyChipTextPaint;
    private RectF legacyDimensionChip;

    private boolean exactFieldPressed = false;
    private boolean exactFieldDragging = false;
    private boolean exactFieldNavigation = false;
    private float exactNavLastSpan = 0f;
    private float fieldDownX;
    private float fieldDownY;
    private float fieldStartOffsetX;
    private float fieldStartOffsetY;
    private Object fieldGestureEntity;
    private boolean firstDrawHintShown = false;

    public ShaprStyleCadCanvasView(Context context) {
        super(context);

        fieldFill.setColor(Color.WHITE);
        fieldFill.setStyle(Paint.Style.FILL);

        fieldStroke.setColor(Color.rgb(38, 105, 210));
        fieldStroke.setStyle(Paint.Style.STROKE);
        fieldStroke.setStrokeWidth(2.5f);

        fieldText.setColor(Color.rgb(30, 86, 185));
        fieldText.setTextSize(27f);
        fieldText.setTextAlign(Paint.Align.CENTER);

        initReflection();
    }

    private void initReflection() {
        try {
            selectedField = field(CadCanvasView.class, "selected");
            viewScaleField = field(CadCanvasView.class, "viewScale");
            offsetXField = field(CadCanvasView.class, "offsetX");
            offsetYField = field(CadCanvasView.class, "offsetY");
            drawingField = field(CadCanvasView.class, "drawing");
            startXField = field(CadCanvasView.class, "startX");
            startYField = field(CadCanvasView.class, "startY");
            endXField = field(CadCanvasView.class, "endX");
            endYField = field(CadCanvasView.class, "endY");

            Field legacyPaintField = field(SmartCadCanvasView.class, "chipPaint");
            Field legacyTextField = field(SmartCadCanvasView.class, "chipTextPaint");
            Field legacyRectField = field(SmartCadCanvasView.class, "dimensionChip");
            Object p = legacyPaintField.get(this);
            Object t = legacyTextField.get(this);
            Object r = legacyRectField.get(this);
            if (p instanceof Paint) legacyChipPaint = (Paint) p;
            if (t instanceof Paint) legacyChipTextPaint = (Paint) t;
            if (r instanceof RectF) legacyDimensionChip = (RectF) r;
        } catch (Exception ignored) {
        }
    }

    private static Field field(Class<?> owner, String name) throws NoSuchFieldException {
        Field f = owner.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int oldLegacyAlpha = -1;
        int oldLegacyTextAlpha = -1;
        if (legacyChipPaint != null) {
            oldLegacyAlpha = legacyChipPaint.getAlpha();
            legacyChipPaint.setAlpha(0);
        }
        if (legacyChipTextPaint != null) {
            oldLegacyTextAlpha = legacyChipTextPaint.getAlpha();
            legacyChipTextPaint.setAlpha(0);
        }

        super.onDraw(canvas);

        if (legacyChipPaint != null && oldLegacyAlpha >= 0) legacyChipPaint.setAlpha(oldLegacyAlpha);
        if (legacyChipTextPaint != null && oldLegacyTextAlpha >= 0) legacyChipTextPaint.setAlpha(oldLegacyTextAlpha);
        if (legacyDimensionChip != null) legacyDimensionChip.setEmpty();

        exactFieldRect.setEmpty();
        if (isDrawingGeometry()) {
            drawLiveExactField(canvas);
        } else if (getTool() == TOOL_SELECT && canEditExactDimension()) {
            drawSelectedExactField(canvas);
        }
    }

    private void drawLiveExactField(Canvas canvas) {
        try {
            float x1 = startXField.getFloat(this);
            float y1 = startYField.getFloat(this);
            float x2 = endXField.getFloat(this);
            float y2 = endYField.getFloat(this);
            String text = liveText(getTool(), x1, y1, x2, y2);
            if (text == null) return;

            PointF anchor;
            if (getTool() == TOOL_CIRCLE || getTool() == TOOL_ARC || getTool() == TOOL_POLYGON) {
                float r = distance(x1, y1, x2, y2);
                anchor = worldToScreen(x1, y1 - r);
                anchor.y -= 34f;
            } else {
                anchor = worldToScreen((x1 + x2) / 2f, (y1 + y2) / 2f);
                anchor.y -= 36f;
            }
            drawField(canvas, text, anchor.x, anchor.y, false);
        } catch (Exception ignored) {
        }
    }

    private void drawSelectedExactField(Canvas canvas) {
        Object selected = selectedObject();
        if (selected == null) return;

        PointF center = entityCenter(selected);
        if (center == null) return;

        String text = selectedExactText();
        if (text == null || text.isEmpty()) return;

        PointF s = worldToScreen(center.x, center.y);
        PointF offset = dimensionLabelOffsets.get(selected);
        float dx = offset == null ? 0f : offset.x;
        float dy = offset == null ? 0f : offset.y;
        float cy = s.y - 58f + dy;
        drawField(canvas, text, s.x + dx, cy, true);
    }

    private void drawField(Canvas canvas, String text, float cx, float cy, boolean interactive) {
        float w = Math.max(190f, Math.min(310f, fieldText.measureText(text) + 54f));
        float h = 54f;
        float left = clamp(cx - w / 2f, 8f, Math.max(8f, getWidth() - w - 8f));
        float top = clamp(cy - h / 2f, 8f, Math.max(8f, getHeight() - h - 8f));
        RectF r = interactive ? exactFieldRect : new RectF();
        r.set(left, top, left + w, top + h);

        canvas.drawRoundRect(r, 16f, 16f, fieldFill);
        canvas.drawRoundRect(r, 16f, 16f, fieldStroke);
        canvas.drawText(text, r.centerX(), r.centerY() + 9f, fieldText);
    }

    private String liveText(int tool, float x1, float y1, float x2, float y2) {
        float dx = Math.abs(x2 - x1);
        float dy = Math.abs(y2 - y1);
        float d = distance(x1, y1, x2, y2);

        if (tool == TOOL_LINE || tool == TOOL_MEASURE) return "⌨ " + mm(d) + " mm";
        if (tool == TOOL_RECT) return "⌨ " + mm(dx) + " × " + mm(dy) + " mm";
        if (tool == TOOL_CIRCLE) return "⌨ Ø " + mm(d * 2f) + " mm";
        if (tool == TOOL_ARC || tool == TOOL_POLYGON) return "⌨ R " + mm(d) + " mm";
        return null;
    }

    private String selectedExactText() {
        String title = exactDimensionTitle();
        String current = exactDimensionCurrentValue();
        if (current == null || current.trim().isEmpty()) return null;

        if (title.startsWith("طول خط")) return "✎ " + current + " mm";
        if (title.startsWith("عرض و ارتفاع")) {
            String[] a = current.trim().split("\\s+");
            if (a.length >= 2) return "✎ " + a[0] + " × " + a[1] + " mm";
            return "✎ " + current + " mm";
        }
        if (title.startsWith("قطر دایره")) return "✎ Ø " + current + " mm";
        if (title.startsWith("شعاع قوس")) return "✎ R " + current + " mm";
        if (title.startsWith("شعاع چندضلعی")) return "✎ R " + current + " mm";
        return "✎ " + current + " mm";
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();

        if (action == MotionEvent.ACTION_CANCEL) {
            exactFieldNavigation = false;
            exactNavLastSpan = 0f;
        }

        // Navigation always wins once a second finger joins the gesture. When
        // the first finger started on the exact-dimension label, keep a tiny
        // local fallback for API 35: some ScaleGestureDetector sequences do not
        // begin scaling after that intercepted DOWN. The normal CAD pipeline is
        // still called first; fallback scaling runs only if core scale stayed
        // unchanged for the MOVE, so ordinary pinch behavior is never doubled.
        if (event.getPointerCount() >= 2) {
            boolean fromExactField = exactFieldPressed || exactFieldNavigation;
            if (fromExactField && !exactFieldNavigation) {
                exactFieldNavigation = true;
                exactNavLastSpan = pointerSpan(event);
            }

            exactFieldPressed = false;
            exactFieldDragging = false;
            fieldGestureEntity = null;

            float scaleBefore = viewScale();
            boolean handled = super.onTouchEvent(event);

            if (fromExactField && exactFieldNavigation && action == MotionEvent.ACTION_MOVE) {
                float span = pointerSpan(event);
                float scaleAfter = viewScale();
                if (exactNavLastSpan > 1f && span > 1f
                        && Math.abs(scaleAfter - scaleBefore) < 1.0e-5f) {
                    float focusX = (event.getX(0) + event.getX(1)) * 0.5f;
                    float focusY = (event.getY(0) + event.getY(1)) * 0.5f;
                    applyExactNavigationFallback(span / exactNavLastSpan, focusX, focusY);
                }
                exactNavLastSpan = span;
            } else if (fromExactField && exactFieldNavigation) {
                exactNavLastSpan = pointerSpan(event);
            }

            if (action == MotionEvent.ACTION_POINTER_UP || action == MotionEvent.ACTION_CANCEL) {
                exactFieldNavigation = false;
                exactNavLastSpan = 0f;
            }
            return handled;
        }

        if (action == MotionEvent.ACTION_DOWN
                && !exactFieldRect.isEmpty()
                && exactFieldRect.contains(event.getX(), event.getY())) {
            coreObserveScaleGesture(event);
            exactFieldPressed = true;
            exactFieldDragging = false;
            exactFieldNavigation = false;
            exactNavLastSpan = 0f;
            fieldDownX = event.getX();
            fieldDownY = event.getY();
            fieldGestureEntity = selectedObject();
            PointF current = fieldGestureEntity == null ? null : dimensionLabelOffsets.get(fieldGestureEntity);
            fieldStartOffsetX = current == null ? 0f : current.x;
            fieldStartOffsetY = current == null ? 0f : current.y;
            return true;
        }

        if (exactFieldPressed) {
            coreObserveScaleGesture(event);
            if (action == MotionEvent.ACTION_MOVE) {
                float dx = event.getX() - fieldDownX;
                float dy = event.getY() - fieldDownY;
                if (!exactFieldDragging && Math.hypot(dx, dy) >= LABEL_DRAG_SLOP_PX) {
                    exactFieldDragging = true;
                }
                if (exactFieldDragging && fieldGestureEntity != null) {
                    dimensionLabelOffsets.put(fieldGestureEntity,
                            new PointF(fieldStartOffsetX + dx, fieldStartOffsetY + dy));
                    invalidate();
                }
                return true;
            }

            if (action == MotionEvent.ACTION_UP) {
                boolean wasDragging = exactFieldDragging;
                exactFieldPressed = false;
                exactFieldDragging = false;
                fieldGestureEntity = null;
                if (!wasDragging && exactFieldRect.contains(event.getX(), event.getY())) {
                    showInlineDimensionEditor();
                } else if (wasDragging) {
                    invalidate();
                }
                return true;
            }

            if (action == MotionEvent.ACTION_CANCEL) {
                exactFieldPressed = false;
                exactFieldDragging = false;
                fieldGestureEntity = null;
                return true;
            }

            return true;
        }

        int toolBefore = getTool();
        boolean wasGeometryTool = isGeometryTool(toolBefore);
        boolean handled = super.onTouchEvent(event);

        if (action == MotionEvent.ACTION_UP && wasGeometryTool && getTool() == TOOL_SELECT
                && canEditExactDimension() && !firstDrawHintShown) {
            firstDrawHintShown = true;
            Toast.makeText(getContext(),
                    "کادر اندازه: لمس = ویرایش عدد • بکش = جابه‌جایی کادر بدون حرکت شکل",
                    Toast.LENGTH_LONG).show();
        }
        return handled;
    }

    /**
     * API-35 safety net for the one gesture that starts outside the core touch
     * state machine: first finger down on the exact-dimension label. The core
     * receives every two-finger event; this method only supplies scale when the
     * detector demonstrably left viewScale untouched for that MOVE.
     */
    private void applyExactNavigationFallback(float spanRatio, float focusX, float focusY) {
        try {
            if (viewScaleField == null || offsetXField == null || offsetYField == null) return;
            if (!Float.isFinite(spanRatio) || spanRatio <= 0f) return;

            float oldScale = viewScaleField.getFloat(this);
            if (oldScale <= 0f) return;

            float boundedRatio = clamp(spanRatio, 0.5f, 2f);
            float accelerated = (float) Math.pow(boundedRatio, 1.65d);
            float newScale = clamp(oldScale * accelerated, MIN_VIEW_SCALE, MAX_VIEW_SCALE);
            if (Math.abs(newScale - oldScale) < 1.0e-6f) return;

            float ratio = newScale / oldScale;
            float oldOffsetX = offsetXField.getFloat(this);
            float oldOffsetY = offsetYField.getFloat(this);
            viewScaleField.setFloat(this, newScale);
            offsetXField.setFloat(this, focusX - (focusX - oldOffsetX) * ratio);
            offsetYField.setFloat(this, focusY - (focusY - oldOffsetY) * ratio);
            invalidate();
        } catch (Exception ignored) {
        }
    }

    private static float pointerSpan(MotionEvent event) {
        if (event == null || event.getPointerCount() < 2) return 0f;
        return distance(event.getX(0), event.getY(0), event.getX(1), event.getY(1));
    }

    private void showInlineDimensionEditor() {
        if (!canEditExactDimension()) return;

        EditText input = new EditText(getContext());
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setHint(exactDimensionHint());
        String current = exactDimensionCurrentValue();
        if (current != null && !current.isEmpty()) {
            input.setText(current);
            input.setSelectAllOnFocus(true);
        }

        new AlertDialog.Builder(getContext())
                .setTitle(exactDimensionTitle())
                .setMessage(exactDimensionHint() + "\nمقدار را به میلی‌متر وارد کن.")
                .setView(input)
                .setPositiveButton("اعمال", (d, w) -> {
                    String result = applySelectedDimension(input.getText().toString());
                    Toast.makeText(getContext(), result, Toast.LENGTH_SHORT).show();
                    invalidate();
                })
                .setNegativeButton("لغو", null)
                .show();
    }

    private boolean isDrawingGeometry() {
        try {
            return drawingField != null && drawingField.getBoolean(this) && isGeometryTool(getTool());
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean isGeometryTool(int tool) {
        return tool == TOOL_LINE || tool == TOOL_RECT || tool == TOOL_CIRCLE
                || tool == TOOL_ARC || tool == TOOL_POLYGON || tool == TOOL_MEASURE;
    }

    private Object selectedObject() {
        try {
            return selectedField == null ? null : selectedField.get(this);
        } catch (Exception e) {
            return null;
        }
    }

    private PointF entityCenter(Object entity) {
        if (entity == null) return null;
        try {
            Class<?> c = entity.getClass();
            while (c != null) {
                try {
                    Method m = c.getDeclaredMethod("center");
                    m.setAccessible(true);
                    Object p = m.invoke(entity);
                    if (p instanceof PointF) return new PointF((PointF) p);
                    return null;
                } catch (NoSuchMethodException e) {
                    c = c.getSuperclass();
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private float viewScale() {
        try {
            return viewScaleField == null ? 1f : viewScaleField.getFloat(this);
        } catch (Exception e) {
            return 1f;
        }
    }

    private float offsetX() {
        try {
            return offsetXField == null ? 0f : offsetXField.getFloat(this);
        } catch (Exception e) {
            return 0f;
        }
    }

    private float offsetY() {
        try {
            return offsetYField == null ? 0f : offsetYField.getFloat(this);
        } catch (Exception e) {
            return 0f;
        }
    }

    private PointF worldToScreen(float x, float y) {
        float s = PX_PER_MM * viewScale();
        return new PointF(offsetX() + x * s, offsetY() + y * s);
    }

    private static float distance(float x1, float y1, float x2, float y2) {
        return (float) Math.hypot(x2 - x1, y2 - y1);
    }

    private static String mm(float value) {
        String s = String.format(Locale.US, "%.2f", value);
        while (s.contains(".") && (s.endsWith("0") || s.endsWith("."))) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    private static float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }
}

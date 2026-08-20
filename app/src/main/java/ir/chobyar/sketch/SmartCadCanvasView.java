package ir.chobyar.sketch;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PointF;
import android.graphics.RectF;
import android.view.MotionEvent;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

/**
 * Interaction layer over the current CAD engine.
 *
 * Adds professional selection behaviour without changing the geometry model:
 * - window/crossing selection
 * - additive multi-selection
 * - group/ungroup
 * - move a selection by an exact anchor and snap it to another object
 * - group transforms for Move/Copy/Rotate/Scale/Mirror/Array/Offset/Delete
 * - an on-canvas dimension edit chip
 *
 * CadCanvasView exposes a protected sketch-core contract to subclasses. This
 * layer uses that contract directly so selection and viewport behavior cannot
 * silently fail because a private field or method was renamed.
 */
public class SmartCadCanvasView extends CadCanvasView {

    private static final float PX_PER_MM = 3f;
    private static final float GRID_MM = 10f;
    private static final float TAP_SLOP_PX = 10f;
    private static final float SNAP_RADIUS_PX = 34f;

    public interface StatusListener {
        void onStatus(String text);
    }

    public interface DimensionEditListener {
        void onDimensionEditRequested();
    }

    private StatusListener statusListener;
    private DimensionEditListener dimensionEditListener;

    protected final List<Object> selectedObjects = new ArrayList<>();
    private final List<List<Object>> groups = new ArrayList<>();

    private boolean multiSelectMode = false;
    private boolean selectionBoxCandidate = false;
    private boolean selectionBoxActive = false;
    private float boxStartX, boxStartY, boxEndX, boxEndY;
    private Object pendingTapHit;

    private boolean groupDragging = false;
    private boolean groupDragUndoSaved = false;
    private float groupLastWorldX, groupLastWorldY;

    // 0 = off, 1 = waiting for source anchor, 2 = waiting for target
    private int anchorMoveState = 0;
    private PointF anchorSource;

    private boolean chipPressed = false;
    private final RectF dimensionChip = new RectF();

    private final Paint multiSelectionPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint selectionBoxPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint selectionBoxFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint chipPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint chipTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint anchorPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public SmartCadCanvasView(Context context) {
        super(context);
        initPaints();
    }

    private void initPaints() {
        multiSelectionPaint.setColor(Color.rgb(30, 105, 225));
        multiSelectionPaint.setStyle(Paint.Style.STROKE);
        multiSelectionPaint.setStrokeWidth(3f);

        selectionBoxPaint.setColor(Color.rgb(30, 105, 225));
        selectionBoxPaint.setStyle(Paint.Style.STROKE);
        selectionBoxPaint.setStrokeWidth(2f);

        selectionBoxFill.setColor(Color.argb(32, 30, 105, 225));
        selectionBoxFill.setStyle(Paint.Style.FILL);

        chipPaint.setColor(Color.rgb(38, 105, 210));
        chipPaint.setStyle(Paint.Style.FILL);

        chipTextPaint.setColor(Color.WHITE);
        chipTextPaint.setTextSize(28f);
        chipTextPaint.setTextAlign(Paint.Align.CENTER);

        anchorPaint.setColor(Color.rgb(245, 125, 10));
        anchorPaint.setStyle(Paint.Style.STROKE);
        anchorPaint.setStrokeWidth(4f);
    }

    public void setStatusListener(StatusListener listener) {
        statusListener = listener;
    }

    public void setDimensionEditListener(DimensionEditListener listener) {
        dimensionEditListener = listener;
    }

    public String toggleMultiSelectMode() {
        multiSelectMode = !multiSelectMode;
        anchorMoveState = 0;
        invalidate();
        return multiSelectMode
                ? "چندانتخاب روشن: روی شکل‌ها بزن تا اضافه/کم شوند؛ روی فضای خالی بکش تا کادر انتخاب شود"
                : "چندانتخاب خاموش: انتخاب عادی";
    }

    public boolean isMultiSelectMode() {
        return multiSelectMode;
    }

    public String beginAnchorMove() {
        syncFromBaseIfNeeded();
        if (selectedObjects.isEmpty()) return "اول یک یا چند شکل را انتخاب کن";
        anchorMoveState = 1;
        anchorSource = null;
        notifyStatus("جابجایی Snap: نقطه مبدا را روی شکل انتخاب‌شده بزن");
        invalidate();
        return "نقطه مبدا را روی شکل انتخاب‌شده بزن";
    }

    public String groupSelected() {
        syncFromBaseIfNeeded();
        if (selectedObjects.size() < 2) return "برای گروه حداقل دو شکل انتخاب کن";
        pruneGroups();
        for (List<Object> g : groups) {
            if (sameIdentitySet(g, selectedObjects)) return "این شکل‌ها همین حالا یک گروه هستند";
        }
        groups.add(new ArrayList<>(selectedObjects));
        invalidate();
        return selectedObjects.size() + " شکل گروه شدند";
    }

    public String ungroupSelected() {
        syncFromBaseIfNeeded();
        if (selectedObjects.isEmpty()) return "اول یک شکل از گروه را انتخاب کن";
        int removed = 0;
        Iterator<List<Object>> it = groups.iterator();
        while (it.hasNext()) {
            List<Object> g = it.next();
            if (intersectsIdentity(g, selectedObjects)) {
                it.remove();
                removed++;
            }
        }
        invalidate();
        return removed == 0 ? "گروهی برای این انتخاب پیدا نشد" : "گروه باز شد";
    }

    public void clearSmartSelection() {
        selectedObjects.clear();
        setBaseSelected(null);
        anchorMoveState = 0;
        invalidate();
    }

    @Override
    public void setTool(int newTool) {
        super.setTool(newTool);
        selectionBoxCandidate = false;
        selectionBoxActive = false;
        pendingTapHit = null;
        groupDragging = false;
        chipPressed = false;
        if (newTool != TOOL_SELECT) anchorMoveState = 0;
    }

    @Override
    public String selectedInfo() {
        syncFromBaseIfNeeded();
        if (selectedObjects.size() > 1) {
            return selectedObjects.size() + " شکل انتخاب شده";
        }
        return super.selectedInfo();
    }

    @Override
    public void clearAll() {
        super.clearAll();
        selectedObjects.clear();
        groups.clear();
        anchorMoveState = 0;
    }

    @Override
    public void undo() {
        super.undo();
        selectedObjects.clear();
        groups.clear();
        anchorMoveState = 0;
    }

    @Override
    public void deleteSelected() {
        syncFromBaseIfNeeded();
        if (selectedObjects.size() <= 1) {
            super.deleteSelected();
            selectedObjects.clear();
            pruneGroups();
            return;
        }
        coreSaveUndo();
        List<Object> all = entities();
        all.removeAll(selectedObjects);
        selectedObjects.clear();
        setBaseSelected(null);
        pruneGroups();
        invalidate();
    }

    @Override
    public void moveSelected(float dx, float dy) {
        syncFromBaseIfNeeded();
        if (selectedObjects.size() <= 1) {
            super.moveSelected(dx, dy);
            syncFromBaseIfNeeded();
            return;
        }
        coreSaveUndo();
        for (Object e : selectedObjects) translate(e, dx, dy);
        invalidate();
    }

    @Override
    public void copySelected(float dx, float dy) {
        syncFromBaseIfNeeded();
        if (selectedObjects.size() <= 1) {
            super.copySelected(dx, dy);
            syncFromBaseIfNeeded();
            return;
        }
        coreSaveUndo();
        List<Object> copies = new ArrayList<>();
        for (Object e : selectedObjects) {
            Object c = entityCopy(e);
            if (c != null) {
                translate(c, dx, dy);
                entities().add(c);
                copies.add(c);
            }
        }
        setSelection(copies);
        invalidate();
    }

    @Override
    public String offsetSelected(float distance) {
        syncFromBaseIfNeeded();
        if (selectedObjects.size() <= 1) return super.offsetSelected(distance);
        coreSaveUndo();
        List<Object> copies = new ArrayList<>();
        for (Object e : selectedObjects) {
            Object c = entityOffsetCopy(e, distance);
            if (c != null) {
                entities().add(c);
                copies.add(c);
            }
        }
        if (copies.isEmpty()) return "Offset برای شکل‌های انتخاب‌شده قابل اجرا نیست";
        setSelection(copies);
        invalidate();
        return "Offset روی " + copies.size() + " شکل اعمال شد";
    }

    @Override
    public String rotateSelected(float deg) {
        syncFromBaseIfNeeded();
        if (selectedObjects.size() <= 1) return super.rotateSelected(deg);
        PointF c = selectionCenter();
        coreSaveUndo();
        for (Object e : selectedObjects) entityRotate(e, c.x, c.y, deg);
        invalidate();
        return "چرخش " + format(deg) + "° روی " + selectedObjects.size() + " شکل";
    }

    @Override
    public String scaleSelected(float factor) {
        syncFromBaseIfNeeded();
        if (selectedObjects.size() <= 1) return super.scaleSelected(factor);
        if (factor <= 0f) return "Scale باید بزرگ‌تر از صفر باشد";
        PointF c = selectionCenter();
        coreSaveUndo();
        for (Object e : selectedObjects) entityScale(e, c.x, c.y, factor);
        invalidate();
        return "Scale × " + format(factor) + " روی انتخاب";
    }

    @Override
    public String mirrorSelected(boolean acrossXAxis, float axisValue) {
        syncFromBaseIfNeeded();
        if (selectedObjects.size() <= 1) return super.mirrorSelected(acrossXAxis, axisValue);
        coreSaveUndo();
        for (Object e : selectedObjects) {
            if (acrossXAxis) entityMirrorHorizontal(e, axisValue);
            else entityMirrorVertical(e, axisValue);
        }
        invalidate();
        return acrossXAxis ? "قرینه گروه نسبت به محور X" : "قرینه گروه نسبت به محور Y";
    }

    @Override
    public String arraySelected(int count, float dx, float dy) {
        syncFromBaseIfNeeded();
        if (selectedObjects.size() <= 1) return super.arraySelected(count, dx, dy);
        if (count < 2 || count > 200) return "تعداد Array باید بین 2 و 200 باشد";
        coreSaveUndo();
        List<Object> seed = new ArrayList<>(selectedObjects);
        List<Object> lastCopies = new ArrayList<>();
        for (int i = 1; i < count; i++) {
            lastCopies.clear();
            for (Object e : seed) {
                Object c = entityCopy(e);
                if (c != null) {
                    translate(c, dx * i, dy * i);
                    entities().add(c);
                    lastCopies.add(c);
                }
            }
        }
        if (!lastCopies.isEmpty()) setSelection(new ArrayList<>(lastCopies));
        invalidate();
        return "Array گروه: " + count + " ردیف";
    }

    @Override
    public String applySelectedDimension(String raw) {
        syncFromBaseIfNeeded();
        if (selectedObjects.size() > 1) return "برای اندازه دقیق فقط یک شکل را انتخاب کن";
        return super.applySelectedDimension(raw);
    }

    @Override
    public String assignSelectedLayer(String name) {
        syncFromBaseIfNeeded();
        if (selectedObjects.size() <= 1) return super.assignSelectedLayer(name);
        if (name == null || name.trim().isEmpty()) return "نام لایه خالی است";
        coreSaveUndo();
        for (Object e : selectedObjects) entitySetLayer(e, name.trim());
        invalidate();
        return selectedObjects.size() + " شکل به لایه " + name.trim() + " منتقل شد";
    }

    @Override
    public String setMaterial(String material) {
        syncFromBaseIfNeeded();
        if (selectedObjects.size() <= 1) return super.setMaterial(material);
        int color = materialColorLocal(material);
        coreSaveUndo();
        for (Object e : selectedObjects) entitySetColor(e, color);
        invalidate();
        return "متریال روی " + selectedObjects.size() + " شکل اعمال شد";
    }

    @Override
    public String setLayerVisible(String name, boolean visible) {
        String result = super.setLayerVisible(name, visible);
        pruneSelection();
        return result;
    }

    @Override
    public String executeCommand(String raw) {
        if (raw == null) return "";
        String normalized = normalizeDigits(raw).trim().replace(',', ' ');
        if (normalized.isEmpty()) return "";
        String[] a = normalized.split("\\s+");
        String cmd = a[0].toUpperCase(Locale.US);
        try {
            switch (cmd) {
                case "M": case "MOVE":
                    if (a.length < 3) return "دو مقدار dx و dy وارد کن";
                    moveSelected(Float.parseFloat(a[1]), Float.parseFloat(a[2]));
                    return "جابه‌جا شد";
                case "CO": case "COPY":
                    if (a.length < 3) return "دو مقدار dx و dy وارد کن";
                    copySelected(Float.parseFloat(a[1]), Float.parseFloat(a[2]));
                    return "کپی شد";
                case "O": case "OFFSET":
                    if (a.length < 2) return "فاصله Offset را وارد کن";
                    return offsetSelected(Float.parseFloat(a[1]));
                case "RO": case "ROTATE":
                    if (a.length < 2) return "زاویه را وارد کن";
                    return rotateSelected(Float.parseFloat(a[1]));
                case "SC": case "SCALE":
                    if (a.length < 2) return "ضریب Scale را وارد کن";
                    return scaleSelected(Float.parseFloat(a[1]));
                case "MI": case "MIRROR":
                    if (a.length < 2) return "MIRROR X یا MIRROR Y";
                    float axis = a.length > 2 ? Float.parseFloat(a[2]) : 0f;
                    if ("X".equalsIgnoreCase(a[1])) return mirrorSelected(true, axis);
                    if ("Y".equalsIgnoreCase(a[1])) return mirrorSelected(false, axis);
                    return "MIRROR X یا MIRROR Y";
                case "AR": case "ARRAY":
                    if (a.length < 4) return "count dx dy را وارد کن";
                    return arraySelected(Integer.parseInt(a[1]), Float.parseFloat(a[2]), Float.parseFloat(a[3]));
                case "ERASE": case "DELETE":
                    deleteSelected();
                    return "حذف شد";
                case "ASSIGNLAYER":
                    if (a.length < 2) return "نام لایه را وارد کن";
                    return assignSelectedLayer(a[1]);
                case "MATERIAL":
                    if (a.length < 2) return "نام متریال را وارد کن";
                    return setMaterial(a[1]);
                case "GROUP":
                    return groupSelected();
                case "UNGROUP":
                    return ungroupSelected();
                default:
                    String result = super.executeCommand(normalized);
                    syncFromBaseIfNeeded();
                    return result;
            }
        } catch (Exception e) {
            return "فرمت عدد درست نیست";
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        pruneSelection();
        if (selectedObjects.size() > 1) {
            for (Object e : selectedObjects) {
                RectF r = entityBounds(e);
                if (r == null) continue;
                RectF s = worldRectToScreen(r);
                float pad = 8f;
                s.inset(-pad, -pad);
                canvas.drawRoundRect(s, 10f, 10f, multiSelectionPaint);
            }
        }

        if (selectionBoxActive) {
            RectF r = normalizedScreenRect(boxStartX, boxStartY, boxEndX, boxEndY);
            canvas.drawRect(r, selectionBoxFill);
            canvas.drawRect(r, selectionBoxPaint);
        }

        dimensionChip.setEmpty();
        if (selectedObjects.size() == 1 && getTool() == TOOL_SELECT && anchorMoveState == 0) {
            Object e = selectedObjects.get(0);
            PointF c = entityCenter(e);
            if (c != null) {
                PointF s = worldToScreen(c.x, c.y);
                float w = 170f;
                float h = 48f;
                float left = clamp(s.x - w / 2f, 8f, Math.max(8f, getWidth() - w - 8f));
                float top = clamp(s.y - 82f, 8f, Math.max(8f, getHeight() - h - 42f));
                dimensionChip.set(left, top, left + w, top + h);
                canvas.drawRoundRect(dimensionChip, 20f, 20f, chipPaint);
                canvas.drawText("✎ اندازه دقیق", dimensionChip.centerX(),
                        dimensionChip.centerY() + 10f, chipTextPaint);
            }
        }

        if (anchorMoveState > 0) {
            chipTextPaint.setTextSize(25f);
            chipTextPaint.setColor(Color.rgb(210, 90, 15));
            String message = anchorMoveState == 1
                    ? "مبدا جابجایی را روی انتخاب بزن"
                    : "حالا مقصد Snap را بزن";
            canvas.drawText(message, getWidth() / 2f, 36f, chipTextPaint);
            chipTextPaint.setColor(Color.WHITE);
            chipTextPaint.setTextSize(28f);
            if (anchorSource != null) {
                PointF s = worldToScreen(anchorSource.x, anchorSource.y);
                float z = 16f;
                canvas.drawCircle(s.x, s.y, 13f, anchorPaint);
                canvas.drawLine(s.x - z, s.y, s.x + z, s.y, anchorPaint);
                canvas.drawLine(s.x, s.y - z, s.x, s.y + z, anchorPaint);
            }
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        // Never interfere with two-finger pan/zoom.
        if (event.getPointerCount() >= 2) {
            cancelSmartGesture();
            boolean handled = super.onTouchEvent(event);
            syncFromBaseIfNeeded();
            return handled;
        }

        final int action = event.getActionMasked();
        final float sx = event.getX();
        final float sy = event.getY();
        final float wx = screenToWorldXLocal(sx);
        final float wy = screenToWorldYLocal(sy);

        if (action == MotionEvent.ACTION_DOWN && !dimensionChip.isEmpty()
                && dimensionChip.contains(sx, sy)) {
            chipPressed = true;
            return true;
        }
        if (chipPressed) {
            if (action == MotionEvent.ACTION_UP) {
                chipPressed = false;
                if (dimensionChip.contains(sx, sy) && dimensionEditListener != null) {
                    dimensionEditListener.onDimensionEditRequested();
                }
            } else if (action == MotionEvent.ACTION_CANCEL) {
                chipPressed = false;
            }
            return true;
        }

        if (anchorMoveState > 0) {
            if (action == MotionEvent.ACTION_UP) handleAnchorTap(wx, wy);
            return true;
        }

        if (getTool() == TOOL_SELECT) {
            if (multiSelectMode) return handleMultiSelectTouch(event, wx, wy);
            return handleNormalSelectTouch(event, wx, wy);
        }

        boolean handled = super.onTouchEvent(event);
        if (action == MotionEvent.ACTION_UP) syncSelectionFromBase();
        return handled;
    }

    private boolean handleMultiSelectTouch(MotionEvent event, float wx, float wy) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            boxStartX = boxEndX = event.getX();
            boxStartY = boxEndY = event.getY();
            pendingTapHit = coreFindHit(wx, wy);
            selectionBoxCandidate = pendingTapHit == null;
            selectionBoxActive = false;
            return true;
        }
        if (action == MotionEvent.ACTION_MOVE) {
            float d = distancePx(event.getX(), event.getY(), boxStartX, boxStartY);
            if (selectionBoxCandidate && d > TAP_SLOP_PX) {
                selectionBoxActive = true;
                boxEndX = event.getX();
                boxEndY = event.getY();
                invalidate();
            }
            return true;
        }
        if (action == MotionEvent.ACTION_UP) {
            if (selectionBoxActive) {
                boxEndX = event.getX();
                boxEndY = event.getY();
                selectByWindow(true);
            } else if (pendingTapHit != null) {
                toggleObjectOrGroup(pendingTapHit);
            }
            selectionBoxCandidate = false;
            selectionBoxActive = false;
            pendingTapHit = null;
            invalidate();
            return true;
        }
        if (action == MotionEvent.ACTION_CANCEL) {
            cancelSmartGesture();
            return true;
        }
        return true;
    }

    private boolean handleNormalSelectTouch(MotionEvent event, float wx, float wy) {
        int action = event.getActionMasked();

        if (action == MotionEvent.ACTION_DOWN) {
            boxStartX = boxEndX = event.getX();
            boxStartY = boxEndY = event.getY();
            Object hit = coreFindHit(wx, wy);

            List<Object> group = groupFor(hit);
            if (group != null && group.size() > 1) {
                setSelection(new ArrayList<>(group));
                groupDragging = true;
                groupDragUndoSaved = false;
                groupLastWorldX = wx;
                groupLastWorldY = wy;
                return true;
            }

            if (selectedObjects.size() > 1 && containsIdentity(selectedObjects, hit)) {
                groupDragging = true;
                groupDragUndoSaved = false;
                groupLastWorldX = wx;
                groupLastWorldY = wy;
                return true;
            }

            if (hit == null) {
                selectionBoxCandidate = true;
                selectionBoxActive = false;
                pendingTapHit = null;
                return true;
            }

            selectionBoxCandidate = false;
            selectionBoxActive = false;
            boolean handled = super.onTouchEvent(event);
            syncSelectionFromBase();
            return handled;
        }

        if (action == MotionEvent.ACTION_MOVE) {
            if (groupDragging) {
                float dx = wx - groupLastWorldX;
                float dy = wy - groupLastWorldY;
                if (!groupDragUndoSaved && distancePx(event.getX(), event.getY(), boxStartX, boxStartY) > 3f) {
                    coreSaveUndo();
                    groupDragUndoSaved = true;
                }
                if (groupDragUndoSaved) {
                    for (Object e : selectedObjects) translate(e, dx, dy);
                    groupLastWorldX = wx;
                    groupLastWorldY = wy;
                    invalidate();
                }
                return true;
            }

            if (selectionBoxCandidate) {
                float d = distancePx(event.getX(), event.getY(), boxStartX, boxStartY);
                if (d > TAP_SLOP_PX) {
                    selectionBoxActive = true;
                    boxEndX = event.getX();
                    boxEndY = event.getY();
                    invalidate();
                }
                return true;
            }

            return super.onTouchEvent(event);
        }

        if (action == MotionEvent.ACTION_UP) {
            if (groupDragging) {
                groupDragging = false;
                groupDragUndoSaved = false;
                invalidate();
                return true;
            }
            if (selectionBoxCandidate) {
                if (selectionBoxActive) {
                    boxEndX = event.getX();
                    boxEndY = event.getY();
                    selectByWindow(false);
                } else {
                    clearSmartSelection();
                }
                selectionBoxCandidate = false;
                selectionBoxActive = false;
                invalidate();
                return true;
            }
            boolean handled = super.onTouchEvent(event);
            syncSelectionFromBase();
            return handled;
        }

        if (action == MotionEvent.ACTION_CANCEL) {
            cancelSmartGesture();
            return true;
        }

        return super.onTouchEvent(event);
    }

    private void handleAnchorTap(float wx, float wy) {
        if (anchorMoveState == 1) {
            PointF p = nearestSelectedAnchor(wx, wy);
            if (p == null) p = selectionCenter();
            anchorSource = p;
            anchorMoveState = 2;
            notifyStatus("مبدا انتخاب شد؛ حالا مقصد را روی گوشه/مرکز/انتهای شکل دیگر بزن");
            invalidate();
            return;
        }

        if (anchorMoveState == 2 && anchorSource != null) {
            PointF target = snapExternal(wx, wy);
            coreSaveUndo();
            float dx = target.x - anchorSource.x;
            float dy = target.y - anchorSource.y;
            for (Object e : selectedObjects) translate(e, dx, dy);
            anchorMoveState = 0;
            anchorSource = null;
            notifyStatus("جابجایی Snap انجام شد");
            invalidate();
        }
    }

    private void selectByWindow(boolean additive) {
        RectF screen = normalizedScreenRect(boxStartX, boxStartY, boxEndX, boxEndY);
        float wx1 = screenToWorldXLocal(screen.left);
        float wy1 = screenToWorldYLocal(screen.top);
        float wx2 = screenToWorldXLocal(screen.right);
        float wy2 = screenToWorldYLocal(screen.bottom);
        RectF world = new RectF(Math.min(wx1, wx2), Math.min(wy1, wy2),
                Math.max(wx1, wx2), Math.max(wy1, wy2));

        boolean leftToRight = boxEndX >= boxStartX;
        List<Object> found = new ArrayList<>();
        for (Object e : entities()) {
            if (!entityVisible(e) || entityConstruction(e)) continue;
            RectF b = entityBounds(e);
            if (b == null) continue;
            boolean match = leftToRight ? containsRect(world, b) : RectF.intersects(world, b);
            if (match) {
                List<Object> group = groupFor(e);
                if (group != null) addIdentityUnique(found, group);
                else addIdentityUnique(found, e);
            }
        }

        if (!additive) selectedObjects.clear();
        addIdentityUnique(selectedObjects, found);
        syncBaseSelectionWithSmart();
        notifyStatus(selectedObjects.isEmpty()
                ? "چیزی داخل کادر انتخاب نبود"
                : selectedObjects.size() + " شکل انتخاب شد");
    }

    private void toggleObjectOrGroup(Object hit) {
        List<Object> group = groupFor(hit);
        if (group != null) {
            boolean allSelected = true;
            for (Object e : group) if (!containsIdentity(selectedObjects, e)) allSelected = false;
            if (allSelected) removeIdentityAll(selectedObjects, group);
            else addIdentityUnique(selectedObjects, group);
        } else {
            if (containsIdentity(selectedObjects, hit)) removeIdentity(selectedObjects, hit);
            else selectedObjects.add(hit);
        }
        syncBaseSelectionWithSmart();
        notifyStatus(selectedObjects.isEmpty()
                ? "انتخاب خالی"
                : selectedObjects.size() + " شکل انتخاب شده");
    }

    private void syncSelectionFromBase() {
        Object b = baseSelected();
        selectedObjects.clear();
        if (b != null) {
            List<Object> group = groupFor(b);
            if (group != null && !multiSelectMode) selectedObjects.addAll(group);
            else selectedObjects.add(b);
        }
        syncBaseSelectionWithSmart();
        invalidate();
    }

    private void syncFromBaseIfNeeded() {
        pruneSelection();
        if (selectedObjects.isEmpty()) {
            Object b = baseSelected();
            if (b != null) selectedObjects.add(b);
        }
    }

    private void setSelection(List<Object> objects) {
        selectedObjects.clear();
        addIdentityUnique(selectedObjects, objects);
        syncBaseSelectionWithSmart();
    }

    private void syncBaseSelectionWithSmart() {
        if (selectedObjects.size() == 1) setBaseSelected(selectedObjects.get(0));
        else setBaseSelected(null);
        invalidate();
    }

    private void pruneSelection() {
        List<Object> all = entities();
        Iterator<Object> it = selectedObjects.iterator();
        while (it.hasNext()) {
            Object e = it.next();
            if (!containsIdentity(all, e) || !entityVisible(e)) it.remove();
        }
        pruneGroups();
        if (selectedObjects.size() > 1) setBaseSelected(null);
    }

    private void pruneGroups() {
        List<Object> all = entities();
        Iterator<List<Object>> git = groups.iterator();
        while (git.hasNext()) {
            List<Object> g = git.next();
            Iterator<Object> eit = g.iterator();
            while (eit.hasNext()) if (!containsIdentity(all, eit.next())) eit.remove();
            if (g.size() < 2) git.remove();
        }
    }

    private List<Object> groupFor(Object entity) {
        if (entity == null) return null;
        pruneGroups();
        for (List<Object> g : groups) if (containsIdentity(g, entity)) return g;
        return null;
    }

    private PointF nearestSelectedAnchor(float wx, float wy) {
        float maxWorld = SNAP_RADIUS_PX / (PX_PER_MM * viewScale());
        PointF best = null;
        float bestD = Float.MAX_VALUE;
        for (Object e : selectedObjects) {
            for (PointF p : entitySnapPoints(e)) {
                float d = distanceWorld(wx, wy, p.x, p.y);
                if (d < bestD) { bestD = d; best = p; }
            }
            PointF c = entityCenter(e);
            if (c != null) {
                float d = distanceWorld(wx, wy, c.x, c.y);
                if (d < bestD) { bestD = d; best = c; }
            }
        }
        return bestD <= maxWorld * 1.4f ? best : selectionCenter();
    }

    private PointF snapExternal(float wx, float wy) {
        float radius = SNAP_RADIUS_PX / (PX_PER_MM * viewScale());
        PointF best = null;
        float bestD = Float.MAX_VALUE;

        for (Object e : entities()) {
            if (containsIdentity(selectedObjects, e) || !entityVisible(e) || entityConstruction(e)) continue;
            for (PointF p : entitySnapPoints(e)) {
                float d = distanceWorld(wx, wy, p.x, p.y);
                if (d <= radius && d < bestD) {
                    best = p;
                    bestD = d;
                }
            }
            PointF p = entityNearestPoint(e, wx, wy);
            if (p != null) {
                float d = distanceWorld(wx, wy, p.x, p.y);
                if (d <= radius * .70f && d < bestD) {
                    best = new PointF(p.x, p.y);
                    bestD = d;
                }
            }
        }

        if (best != null) return best;

        float gx = Math.round(wx / GRID_MM) * GRID_MM;
        float gy = Math.round(wy / GRID_MM) * GRID_MM;
        if (distanceWorld(wx, wy, gx, gy) <= radius * .58f) return new PointF(gx, gy);
        return new PointF(wx, wy);
    }

    private PointF selectionCenter() {
        RectF r = selectionBounds();
        if (r == null) return new PointF();
        return new PointF(r.centerX(), r.centerY());
    }

    private RectF selectionBounds() {
        RectF all = null;
        for (Object e : selectedObjects) {
            RectF b = entityBounds(e);
            if (b == null) continue;
            if (all == null) all = new RectF(b);
            else all.union(b);
        }
        return all;
    }

    private void cancelSmartGesture() {
        selectionBoxCandidate = false;
        selectionBoxActive = false;
        pendingTapHit = null;
        groupDragging = false;
        groupDragUndoSaved = false;
        chipPressed = false;
        invalidate();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private List<Object> entities() {
        return (List<Object>) (List<?>) super.entities;
    }

    /** Snapshot for higher interaction layers; the owning list stays private to Smart. */
    protected final List<Object> smartSelectionSnapshot() {
        return new ArrayList<>(selectedObjects);
    }

    private Object baseSelected() { return selected; }

    private void setBaseSelected(Object value) {
        selected = value instanceof Entity ? (Entity) value : null;
    }

    private boolean entityVisible(Object e) {
        return e instanceof Entity && coreIsVisible((Entity) e);
    }

    private boolean entityConstruction(Object e) {
        return e instanceof Entity && ((Entity) e).isConstruction();
    }

    private RectF entityBounds(Object e) {
        return e instanceof Entity ? new RectF(((Entity) e).bounds()) : null;
    }

    private PointF entityCenter(Object e) {
        if (!(e instanceof Entity)) return null;
        PointF p = ((Entity) e).center();
        return p == null ? null : new PointF(p.x, p.y);
    }

    private void translate(Object e, float dx, float dy) {
        if (e instanceof Entity) ((Entity) e).translate(dx, dy);
    }

    private Object entityCopy(Object e) {
        return e instanceof Entity ? ((Entity) e).copy() : null;
    }

    private Object entityOffsetCopy(Object e, float distance) {
        return e instanceof Entity ? ((Entity) e).offsetCopy(distance) : null;
    }

    private void entityRotate(Object e, float cx, float cy, float deg) {
        if (e instanceof Entity) ((Entity) e).rotate(cx, cy, deg);
    }

    private void entityScale(Object e, float cx, float cy, float factor) {
        if (e instanceof Entity) ((Entity) e).scale(cx, cy, factor);
    }

    private void entityMirrorHorizontal(Object e, float axis) {
        if (e instanceof Entity) ((Entity) e).mirrorHorizontal(axis);
    }

    private void entityMirrorVertical(Object e, float axis) {
        if (e instanceof Entity) ((Entity) e).mirrorVertical(axis);
    }

    private void entitySetLayer(Object e, String layer) {
        if (e instanceof Entity) ((Entity) e).setLayer(layer);
    }

    private void entitySetColor(Object e, int color) {
        if (e instanceof Entity) ((Entity) e).setColor(color);
    }

    private List<PointF> entitySnapPoints(Object e) {
        List<PointF> out = new ArrayList<>();
        if (!(e instanceof Entity)) return out;
        for (SnapPoint sp : ((Entity) e).snapPoints()) out.add(new PointF(sp.x, sp.y));
        return out;
    }

    private PointF entityNearestPoint(Object e, float wx, float wy) {
        if (!(e instanceof Entity)) return null;
        PointF p = ((Entity) e).nearestPoint(wx, wy);
        return p == null ? null : new PointF(p.x, p.y);
    }

    private float viewScale() { return super.viewScale; }
    private float offsetX() { return super.offsetX; }
    private float offsetY() { return super.offsetY; }

    private float screenToWorldXLocal(float sx) { return coreScreenToWorldX(sx); }
    private float screenToWorldYLocal(float sy) { return coreScreenToWorldY(sy); }

    private PointF worldToScreen(float wx, float wy) {
        float s = PX_PER_MM * viewScale();
        return new PointF(offsetX() + wx * s, offsetY() + wy * s);
    }

    private RectF worldRectToScreen(RectF r) {
        PointF a = worldToScreen(r.left, r.top);
        PointF b = worldToScreen(r.right, r.bottom);
        return new RectF(Math.min(a.x,b.x), Math.min(a.y,b.y), Math.max(a.x,b.x), Math.max(a.y,b.y));
    }

    private static RectF normalizedScreenRect(float x1, float y1, float x2, float y2) {
        return new RectF(Math.min(x1,x2), Math.min(y1,y2), Math.max(x1,x2), Math.max(y1,y2));
    }

    private static boolean containsRect(RectF outer, RectF inner) {
        return inner.left >= outer.left && inner.right <= outer.right
                && inner.top >= outer.top && inner.bottom <= outer.bottom;
    }

    private static float distancePx(float x1, float y1, float x2, float y2) {
        return (float)Math.hypot(x2-x1, y2-y1);
    }

    private static float distanceWorld(float x1, float y1, float x2, float y2) {
        return (float)Math.hypot(x2-x1, y2-y1);
    }

    private static float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }

    private static String format(float v) {
        return String.format(Locale.US, "%.2f", v);
    }

    private void notifyStatus(String text) {
        if (statusListener != null) statusListener.onStatus(text);
    }

    private static boolean containsIdentity(List<Object> list, Object value) {
        if (value == null) return false;
        for (Object x : list) if (x == value) return true;
        return false;
    }

    private static void removeIdentity(List<Object> list, Object value) {
        Iterator<Object> it = list.iterator();
        while (it.hasNext()) if (it.next() == value) { it.remove(); return; }
    }

    private static void removeIdentityAll(List<Object> list, List<Object> values) {
        for (Object value : values) removeIdentity(list, value);
    }

    private static void addIdentityUnique(List<Object> dst, Object value) {
        if (value != null && !containsIdentity(dst, value)) dst.add(value);
    }

    private static void addIdentityUnique(List<Object> dst, List<Object> values) {
        for (Object value : values) addIdentityUnique(dst, value);
    }

    private static boolean intersectsIdentity(List<Object> a, List<Object> b) {
        for (Object x : a) if (containsIdentity(b, x)) return true;
        return false;
    }

    private static boolean sameIdentitySet(List<Object> a, List<Object> b) {
        if (a.size() != b.size()) return false;
        for (Object x : a) if (!containsIdentity(b, x)) return false;
        return true;
    }

    private static int materialColorLocal(String m) {
        if (m == null) return Color.rgb(25,25,25);
        String s = m.trim().toUpperCase(Locale.US);
        if ("WOOD".equals(s) || "CHOB".equals(s) || "چوب".equals(m)) return Color.rgb(125,85,45);
        if ("MDF".equals(s)) return Color.rgb(145,110,70);
        if ("METAL".equals(s) || "فلز".equals(m)) return Color.rgb(90,100,110);
        if ("GLASS".equals(s) || "شیشه".equals(m)) return Color.rgb(80,150,175);
        return Color.rgb(25,25,25);
    }

    private static String normalizeDigits(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.length());
        for (int i=0;i<s.length();i++) {
            char c=s.charAt(i);
            if (c>='۰'&&c<='۹') b.append((char)('0'+(c-'۰')));
            else if (c>='٠'&&c<='٩') b.append((char)('0'+(c-'٠')));
            else b.append(c);
        }
        return b.toString();
    }
}

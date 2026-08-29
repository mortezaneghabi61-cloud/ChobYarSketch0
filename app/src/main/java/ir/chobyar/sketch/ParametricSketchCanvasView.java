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
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Parametric sketch layer for ChobYar.
 *
 * Adds Shapr-style sketch behavior on top of the current 2D engine:
 * - many independent sketch items/spaces (implemented as CAD layers for now)
 * - active sketch switching, visibility, and sketch locking
 * - per-selection Lock/Unlock that blocks drag, handles, dimensions and transforms
 * - editable angular dimensions for one line or the angle between two lines
 * - stronger auto-connect: endpoint-to-endpoint and endpoint-to-line relationships
 * - adaptive constraints palette (H/V, perpendicular, parallel, coincident, lock)
 * - Auto-constraints and constraint-visibility toggles
 *
 * True 3D sketch planes/faces will bind to the solid kernel later; this class
 * deliberately keeps the current geometry in the existing mm-based 2D engine.
 */
public class ParametricSketchCanvasView extends ChobYarShaprCanvasView {

    private static final float PX_PER_MM = 3f;
    private static final float CONNECT_HIT_PX = 32f;
    private static final float UI_H = 48f;

    private Field selectedField;
    private Field entitiesField;
    private Field selectedObjectsField;
    private Field viewScaleField;
    private Field offsetXField;
    private Field offsetYField;
    private Method findHitMethod;
    private Method saveUndoMethod;

    // Parent constraint stores are reflected only so an explicit angle edit can
    // release conflicting direction constraints, the same way a CAD solver must.
    private Field parentAxisLocksField;
    private Field parentRelationsField;
    private Field parentCoincidenceField;

    private final List<SketchSpace> sketchSpaces = new ArrayList<>();
    private int activeSketchIndex = 0;
    private int sketchSerial = 1;

    private final Map<Object, Boolean> elementLocks = new IdentityHashMap<>();
    private final List<PointOnLineLink> pointOnLineLinks = new ArrayList<>();

    private boolean autoConstraints = true;
    private boolean showParametricConstraints = true;
    private boolean lockedGesture = false;

    private final RectF sketchChip = new RectF();
    private final RectF constraintsChip = new RectF();
    private final RectF angleChip = new RectF();
    private final RectF lockChip = new RectF();
    private int pressedChip = 0;

    private final Paint chipFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint chipStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint chipText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint lockedOutline = new Paint(Paint.ANTI_ALIAS_FLAG);

    public ParametricSketchCanvasView(Context context) {
        super(context);
        initReflection();
        initPaints();
        SketchSpace first = new SketchSpace("Sketch 1", "SKETCH_1");
        sketchSpaces.add(first);
        sketchSerial = 2;
        super.setLayer(first.layerName);
        super.setLayerVisible(first.layerName, true);
    }

    private void initReflection() {
        try {
            selectedField = field(CadCanvasView.class, "selected");
            entitiesField = field(CadCanvasView.class, "entities");
            selectedObjectsField = field(SmartCadCanvasView.class, "selectedObjects");
            viewScaleField = field(CadCanvasView.class, "viewScale");
            offsetXField = field(CadCanvasView.class, "offsetX");
            offsetYField = field(CadCanvasView.class, "offsetY");
            findHitMethod = CadCanvasView.class.getDeclaredMethod("findHit", float.class, float.class);
            findHitMethod.setAccessible(true);
            saveUndoMethod = CadCanvasView.class.getDeclaredMethod("saveUndo");
            saveUndoMethod.setAccessible(true);
            parentAxisLocksField = field(ChobYarShaprCanvasView.class, "axisLocks");
            parentRelationsField = field(ChobYarShaprCanvasView.class, "lineRelations");
            parentCoincidenceField = field(ChobYarShaprCanvasView.class, "coincidenceLinks");
        } catch (Exception ignored) {
        }
    }

    private static Field field(Class<?> owner, String name) throws NoSuchFieldException {
        Field f = owner.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }

    private void initPaints() {
        chipFill.setColor(Color.argb(248, 255, 255, 255));
        chipFill.setStyle(Paint.Style.FILL);
        chipStroke.setColor(Color.rgb(120, 160, 225));
        chipStroke.setStyle(Paint.Style.STROKE);
        chipStroke.setStrokeWidth(2f);
        chipText.setColor(Color.rgb(35, 75, 145));
        chipText.setTextSize(22f);
        chipText.setTextAlign(Paint.Align.CENTER);
        lockedOutline.setColor(Color.rgb(48, 158, 96));
        lockedOutline.setStyle(Paint.Style.STROKE);
        lockedOutline.setStrokeWidth(3f);
    }

    // ---------------------------------------------------------------------
    // Sketch items / spaces
    // ---------------------------------------------------------------------

    private static class SketchSpace {
        String name;
        final String layerName;
        boolean locked;
        boolean visible = true;

        SketchSpace(String name, String layerName) {
            this.name = name;
            this.layerName = layerName;
        }
    }

    private SketchSpace activeSketch() {
        if (sketchSpaces.isEmpty()) return null;
        activeSketchIndex = Math.max(0, Math.min(activeSketchIndex, sketchSpaces.size() - 1));
        return sketchSpaces.get(activeSketchIndex);
    }

    public String createSketchSpace(String requestedName) {
        String clean = requestedName == null ? "" : requestedName.trim();
        if (clean.isEmpty()) clean = "Sketch " + sketchSerial;
        String layer = "SKETCH_" + sketchSerial++;
        SketchSpace s = new SketchSpace(clean, layer);
        sketchSpaces.add(s);
        activeSketchIndex = sketchSpaces.size() - 1;
        super.setLayer(s.layerName);
        super.setLayerVisible(s.layerName, true);
        invalidate();
        dispatchWorkspaceState();
        return "Sketch text: " + s.name;
    }

    public String switchSketchSpace(int index) {
        if (index < 0 || index >= sketchSpaces.size()) return "Sketch was not found";
        activeSketchIndex = index;
        SketchSpace s = activeSketch();
        if (s == null) return "Sketch was not found";
        super.setLayer(s.layerName);
        if (!s.visible) {
            s.visible = true;
            super.setLayerVisible(s.layerName, true);
        }
        super.setTool(TOOL_SELECT);
        invalidate();
        dispatchWorkspaceState();
        return "Sketch text: " + s.name + (s.locked ? " — Lock" : "");
    }

    public String toggleActiveSketchLock() {
        SketchSpace s = activeSketch();
        if (s == null) return "Sketch text text text";
        s.locked = !s.locked;
        if (s.locked) super.setTool(TOOL_SELECT);
        invalidate();
        dispatchWorkspaceState();
        return s.locked ? s.name + " Lock text" : s.name + " text text";
    }

    public String toggleActiveSketchVisibility() {
        SketchSpace s = activeSketch();
        if (s == null) return "Sketch text text text";
        s.visible = !s.visible;
        super.setLayerVisible(s.layerName, s.visible);
        invalidate();
        return s.name + (s.visible ? " Show text text" : " Hide text");
    }

    private void showSketchManager() {
        SketchSpace s = activeSketch();
        String lockText = s != null && s.locked ? "🔓 text text Sketch text" : "🔒 Lock Sketch text";
        String visText = s != null && s.visible ? "◉ Hide text Sketch text" : "◉ Show Sketch text";
        String selLock = isSelectionLocked() ? "🔓 text text Selection" : "🔒 Lock Selection";
        String autoText = autoConstraints ? "Auto Constraints: On" : "Auto Constraints: Off";
        String badgeText = showParametricConstraints ? "Show Constraints: On" : "Show Constraints: Off";
        String[] items = {
                "☷ text Sketchtext (" + sketchSpaces.size() + ")",
                "＋ Sketch text",
                lockText,
                visText,
                selLock,
                "⚙ " + autoText,
                "◌ " + badgeText
        };
        new AlertDialog.Builder(getContext())
                .setTitle(s == null ? "Sketches" : "Sketches — " + s.name)
                .setItems(items, (d, which) -> {
                    if (which == 0) showSketchList();
                    else if (which == 1) showCreateSketchDialog();
                    else if (which == 2) toast(toggleActiveSketchLock());
                    else if (which == 3) toast(toggleActiveSketchVisibility());
                    else if (which == 4) toast(toggleSelectedLock());
                    else if (which == 5) { autoConstraints = !autoConstraints; toast(autoConstraints ? "Auto Constraints On" : "Auto Constraints Off"); invalidate(); }
                    else { showParametricConstraints = !showParametricConstraints; invalidate(); }
                })
                .setNegativeButton("Close", null)
                .show();
    }

    private void showSketchList() {
        String[] items = new String[sketchSpaces.size()];
        for (int i = 0; i < sketchSpaces.size(); i++) {
            SketchSpace s = sketchSpaces.get(i);
            items[i] = (i == activeSketchIndex ? "● " : "○ ") + s.name
                    + (s.locked ? "  🔒" : "") + (s.visible ? "" : "  Hide");
        }
        new AlertDialog.Builder(getContext())
                .setTitle("Items / Sketches")
                .setItems(items, (d, which) -> toast(switchSketchSpace(which)))
                .setPositiveButton("Sketch text", (d, w) -> showCreateSketchDialog())
                .setNegativeButton("Close", null)
                .show();
    }

    private void showCreateSketchDialog() {
        EditText input = new EditText(getContext());
        input.setSingleLine(true);
        input.setHint("text: Front View");
        input.setText("Sketch " + sketchSerial);
        input.setSelectAllOnFocus(true);
        new AlertDialog.Builder(getContext())
                .setTitle("Sketch text")
                .setMessage("Sketch text text created text. text Sketchtext text text text text.")
                .setView(input)
                .setPositiveButton("Create", (d, w) -> toast(createSketchSpace(input.getText().toString())))
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ---------------------------------------------------------------------
    // Lock / Unlock
    // ---------------------------------------------------------------------

    public String toggleSelectedLock() {
        List<Object> sel = selectionObjects();
        if (sel.isEmpty()) return "First text text Line text Selection text";
        boolean shouldLock = false;
        for (Object e : sel) if (!Boolean.TRUE.equals(elementLocks.get(e))) { shouldLock = true; break; }
        for (Object e : sel) {
            if (shouldLock) elementLocks.put(e, true);
            else elementLocks.remove(e);
        }
        invalidate();
        dispatchWorkspaceState();
        return shouldLock ? sel.size() + " text Lock text" : sel.size() + " text text text";
    }

    private boolean isEntityLocked(Object e) {
        if (e == null) return false;
        if (Boolean.TRUE.equals(elementLocks.get(e))) return true;
        String layer = entityLayer(e);
        for (SketchSpace s : sketchSpaces) if (s.layerName.equals(layer)) return s.locked;
        return false;
    }

    private boolean isSelectionLocked() {
        List<Object> sel = selectionObjects();
        if (sel.isEmpty()) return false;
        for (Object e : sel) if (isEntityLocked(e)) return true;
        return false;
    }

    private boolean activeSketchLocked() {
        SketchSpace s = activeSketch();
        return s != null && s.locked;
    }

    @Override
    public boolean canEditExactDimension() {
        return !isSelectionLocked() && super.canEditExactDimension();
    }

    @Override
    public String selectedInfo() {
        String base = super.selectedInfo();
        if (base == null) base = "";
        SketchSpace s = activeSketch();
        String extra = s == null ? "" : " | " + s.name;
        if (isSelectionLocked()) extra += " | 🔒 Lock";
        return base + extra;
    }

    @Override
    public void setTool(int newTool) {
        if (newTool != TOOL_SELECT && activeSketchLocked()) {
            super.setTool(TOOL_SELECT);
            toast("Sketch text Lock text; text text First text text");
            return;
        }
        super.setTool(newTool);
    }

    @Override
    public String beginAnchorMove() {
        if (isSelectionLocked()) return "Selection Lock text";
        return super.beginAnchorMove();
    }

    @Override
    public void moveSelected(float dx, float dy) {
        if (isSelectionLocked()) { toast("Selection Lock text"); return; }
        super.moveSelected(dx, dy);
    }

    @Override
    public String rotateSelected(float deg) {
        if (isSelectionLocked()) return "Selection Lock text";
        return super.rotateSelected(deg);
    }

    @Override
    public String scaleSelected(float factor) {
        if (isSelectionLocked()) return "Selection Lock text";
        return super.scaleSelected(factor);
    }

    @Override
    public String mirrorSelected(boolean acrossXAxis, float axisValue) {
        if (isSelectionLocked()) return "Selection Lock text";
        return super.mirrorSelected(acrossXAxis, axisValue);
    }

    @Override
    public String offsetSelected(float distance) {
        if (isSelectionLocked()) return "Selection Lock text";
        return super.offsetSelected(distance);
    }

    @Override
    public String applySelectedDimension(String raw) {
        if (isSelectionLocked()) return "Selection Lock text; Dimension Transform text";
        return super.applySelectedDimension(raw);
    }

    @Override
    public void deleteSelected() {
        if (isSelectionLocked()) { toast("Selection Lock text"); return; }
        List<Object> before = selectionObjects();
        super.deleteSelected();
        for (Object e : before) elementLocks.remove(e);
        pruneLinks();
    }

    @Override
    public String trimSelectedLines() {
        if (isSelectionLocked()) return "Selection Lock text";
        return super.trimSelectedLines();
    }

    @Override
    public String extendSelectedLines() {
        if (isSelectionLocked()) return "Selection Lock text";
        return super.extendSelectedLines();
    }

    @Override
    public String filletSelectedLines(float radius) {
        if (isSelectionLocked()) return "Selection Lock text";
        return super.filletSelectedLines(radius);
    }

    @Override
    public String chamferSelectedLines(float distance) {
        if (isSelectionLocked()) return "Selection Lock text";
        return super.chamferSelectedLines(distance);
    }

    @Override
    public String joinSelectedLines() {
        if (isSelectionLocked()) return "Selection Lock text";
        return super.joinSelectedLines();
    }

    // ---------------------------------------------------------------------
    // Angle dimensions
    // ---------------------------------------------------------------------

    private boolean canEditAngle() {
        List<Object> lines = selectedLines();
        return (lines.size() == 1 || lines.size() == 2) && !isSelectionLocked();
    }

    private String angleLabel() {
        List<Object> lines = selectedLines();
        if (lines.size() == 1) return "θ " + fmt(displayLineAngle(lines.get(0))) + "°";
        if (lines.size() == 2) return "∠ " + fmt(angleBetween(lines.get(0), lines.get(1))) + "°";
        return "";
    }

    private void showAngleEditor() {
        List<Object> lines = selectedLines();
        if (lines.size() != 1 && lines.size() != 2) return;
        if (isSelectionLocked()) { toast("Selection Lock text"); return; }
        EditText input = new EditText(getContext());
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL | InputType.TYPE_NUMBER_FLAG_SIGNED);
        float current = lines.size() == 1 ? displayLineAngle(lines.get(0)) : angleBetween(lines.get(0), lines.get(1));
        input.setText(fmt(current));
        input.setSelectAllOnFocus(true);
        String title = lines.size() == 1 ? "Angle Line text text text" : "Angle text text Line";
        new AlertDialog.Builder(getContext())
                .setTitle(title + " — degrees")
                .setMessage(lines.size() == 1 ? "text: 35" : "Angle 0 until 180 degrees; text: 90")
                .setView(input)
                .setPositiveButton("Apply", (d, w) -> {
                    try {
                        float value = Float.parseFloat(normalizeDigits(input.getText().toString().trim()));
                        String result = lines.size() == 1 ? setSelectedLineAngle(value) : setSelectedLinesAngle(value);
                        toast(result);
                    } catch (Exception e) { toast("Angle was entered incorrectly"); }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    public String setSelectedLineAngle(float degrees) {
        List<Object> lines = selectedLines();
        if (lines.size() != 1) return "text Line text Selection text";
        if (isSelectionLocked()) return "Line Lock text";
        Object line = lines.get(0);
        saveUndo();
        clearDirectionalConstraints(line);
        float current = lineAngle(line);
        float target = nearestDirectedAngle(current, degrees);
        int pivot = connectedEndpointIndex(line);
        setLineAngle(line, target, pivot);
        enforcePointLinks();
        invalidate();
        dispatchWorkspaceState();
        return "Angle Line = " + fmt(displayLineAngle(line)) + "°";
    }

    public String setSelectedLinesAngle(float degrees) {
        if (degrees < 0f || degrees > 180f) return "Angle text text 0 text 180 text";
        List<Object> lines = selectedLines();
        if (lines.size() != 2) return "text Line text Selection text";
        if (isSelectionLocked()) return "Selection Lock text";
        Object fixed = lines.get(0), moving = lines.get(1);
        saveUndo();
        clearDirectionalConstraints(moving);
        float base = lineAngle(fixed);
        float cur = lineAngle(moving);
        float t1 = normalize360(base + degrees);
        float t2 = normalize360(base - degrees);
        float target = angleDistance(cur, t1) <= angleDistance(cur, t2) ? t1 : t2;
        int pivot = commonEndpointIndex(moving, fixed);
        if (pivot < 0) pivot = connectedEndpointIndex(moving);
        setLineAngle(moving, target, pivot);
        enforcePointLinks();
        invalidate();
        dispatchWorkspaceState();
        return "Angle text Linetext = " + fmt(angleBetween(fixed, moving)) + "°";
    }

    private void clearDirectionalConstraints(Object line) {
        try {
            if (parentAxisLocksField != null) {
                Object map = parentAxisLocksField.get(this);
                if (map instanceof Map) ((Map<?, ?>) map).remove(line);
            }
            if (parentRelationsField != null) {
                Object list = parentRelationsField.get(this);
                if (list instanceof List) {
                    Iterator<?> it = ((List<?>) list).iterator();
                    while (it.hasNext()) {
                        Object rel = it.next();
                        Object a = getObjectField(rel, "a");
                        Object b = getObjectField(rel, "b");
                        if (a == line || b == line) it.remove();
                    }
                }
            }
        } catch (Exception ignored) {}
    }

    private int connectedEndpointIndex(Object line) {
        for (PointOnLineLink link : pointOnLineLinks) if (link.endpoint.line == line) return link.endpoint.index;
        try {
            Object list = parentCoincidenceField == null ? null : parentCoincidenceField.get(this);
            if (list instanceof List) {
                for (Object c : (List<?>) list) {
                    Object a = getObjectField(c, "a"), b = getObjectField(c, "b");
                    Object al = getObjectField(a, "line"), bl = getObjectField(b, "line");
                    if (al == line) return getIntField(a, "index", -1);
                    if (bl == line) return getIntField(b, "index", -1);
                }
            }
        } catch (Exception ignored) {}
        return -1;
    }

    private int commonEndpointIndex(Object moving, Object fixed) {
        for (int mi = 0; mi < 2; mi++) {
            PointF mp = endpoint(moving, mi);
            if (mp == null) continue;
            for (int fi = 0; fi < 2; fi++) {
                PointF fp = endpoint(fixed, fi);
                if (fp != null && dist(mp.x, mp.y, fp.x, fp.y) <= 0.15f) return mi;
            }
        }
        return -1;
    }

    private void setLineAngle(Object line, float angle, int pivotIndex) {
        PointF a = endpoint(line, 0), b = endpoint(line, 1);
        if (a == null || b == null) return;
        float len = dist(a.x, a.y, b.x, b.y);
        double r = Math.toRadians(angle);
        float vx = len * (float) Math.cos(r), vy = len * (float) Math.sin(r);
        if (pivotIndex == 0) {
            setEndpoint(line, 1, a.x + vx, a.y + vy);
        } else if (pivotIndex == 1) {
            setEndpoint(line, 0, b.x - vx, b.y - vy);
        } else {
            float cx = (a.x + b.x) / 2f, cy = (a.y + b.y) / 2f;
            setEndpoint(line, 0, cx - vx / 2f, cy - vy / 2f);
            setEndpoint(line, 1, cx + vx / 2f, cy + vy / 2f);
        }
    }

    // ---------------------------------------------------------------------
    // Adaptive constraints menu
    // ---------------------------------------------------------------------

    private void showConstraintsMenu() {
        String[] items = {
                "H/V — Horizontal/Vertical",
                "⊥ Perpendicular — Perpendicular",
                "∥ Parallel — Parallel",
                "● Coincident — text text text text Line",
                isSelectionLocked() ? "🔓 Unlock — text text" : "🔒 Lock — Lock",
                autoConstraints ? "Auto Constraints — On" : "Auto Constraints — Off",
                showParametricConstraints ? "Show text Constraints — On" : "Show text Constraints — Off"
        };
        new AlertDialog.Builder(getContext())
                .setTitle("Constraints / Constraints")
                .setItems(items, (d, which) -> {
                    String result = null;
                    if (which == 0) result = applyHorizontalVerticalConstraint();
                    else if (which == 1) result = applyPerpendicularConstraint();
                    else if (which == 2) result = applyParallelConstraint();
                    else if (which == 3) result = applyManualCoincident();
                    else if (which == 4) result = toggleSelectedLock();
                    else if (which == 5) { autoConstraints = !autoConstraints; result = autoConstraints ? "Auto Constraints On" : "Auto Constraints Off"; }
                    else { showParametricConstraints = !showParametricConstraints; result = showParametricConstraints ? "text Constraints Show text text" : "text Constraints Hide text"; }
                    if (result != null) toast(result);
                    invalidate();
                })
                .setNegativeButton("Close", null)
                .show();
    }

    private String applyManualCoincident() {
        if (isSelectionLocked()) return "Selection Lock text";
        List<Object> lines = selectedLines();
        if (lines.size() != 2) return "text Coincident text Line text Selection text";
        Object a = lines.get(0), b = lines.get(1);
        int ai = 0, bi = 0;
        float best = Float.MAX_VALUE;
        for (int i = 0; i < 2; i++) {
            PointF pa = endpoint(a, i);
            for (int j = 0; j < 2; j++) {
                PointF pb = endpoint(b, j);
                if (pa == null || pb == null) continue;
                float dd = dist(pa.x, pa.y, pb.x, pb.y);
                if (dd < best) { best = dd; ai = i; bi = j; }
            }
        }
        PointF target = endpoint(a, ai);
        if (target == null) return "text text text";
        saveUndo();
        setEndpoint(b, bi, target.x, target.y);
        // Manual Coincident must create the same persistent relation as auto-connect.
        // Register it explicitly instead of relying on a later draw-time proximity scan.
        removePointLink(b, bi);
        registerPersistentCoincident(a, ai, b, bi);
        invalidate();
        return "text Line text text text became";
    }

    // ---------------------------------------------------------------------
    // Strong endpoint / edge connection
    // ---------------------------------------------------------------------

    private static class SnapTarget {
        final Object host;
        final PointF point;
        final boolean endpoint;
        final float distance;
        SnapTarget(Object host, PointF point, boolean endpoint, float distance) {
            this.host = host; this.point = point; this.endpoint = endpoint; this.distance = distance;
        }
    }

    private MotionEvent adjustLinePointer(MotionEvent source) {
        float wx = screenToWorldX(source.getX()), wy = screenToWorldY(source.getY());
        float radius = CONNECT_HIT_PX / (PX_PER_MM * viewScale());
        SnapTarget best = null;
        for (Object e : entities()) {
            if (!isLine(e)) continue;
            for (int i = 0; i < 2; i++) {
                PointF p = endpoint(e, i);
                if (p == null) continue;
                float d = dist(wx, wy, p.x, p.y);
                if (d <= radius && (best == null || d < best.distance)) best = new SnapTarget(e, p, true, d);
            }
        }
        if (best == null) {
            for (Object e : entities()) {
                if (!isLine(e)) continue;
                PointF p = projectToSegment(e, wx, wy);
                if (p == null) continue;
                float d = dist(wx, wy, p.x, p.y);
                if (d <= radius * 0.75f && (best == null || d < best.distance)) best = new SnapTarget(e, p, false, d);
            }
        }
        if (best == null) return null;
        PointF screen = worldToScreen(best.point.x, best.point.y);
        MotionEvent copy = MotionEvent.obtain(source);
        copy.setLocation(screen.x, screen.y);
        return copy;
    }

    private void detectPointOnLineLinks(Object newLine) {
        if (!isLine(newLine)) return;
        for (int i = 0; i < 2; i++) {
            PointF p = endpoint(newLine, i);
            if (p == null) continue;
            for (Object host : entities()) {
                if (host == newLine || !isLine(host)) continue;
                PointF a = endpoint(host, 0), b = endpoint(host, 1);
                if (a == null || b == null) continue;
                if (dist(p.x, p.y, a.x, a.y) <= 0.12f || dist(p.x, p.y, b.x, b.y) <= 0.12f) continue;
                Projection pr = projection(host, p.x, p.y);
                if (pr != null && pr.distance <= 0.10f && pr.t >= -0.01f && pr.t <= 1.01f) {
                    addPointOnLineLink(newLine, i, host, clamp(pr.t, 0f, 1f));
                    break;
                }
            }
        }
    }

    private static class Projection {
        final PointF point; final float t; final float distance;
        Projection(PointF point, float t, float distance) { this.point = point; this.t = t; this.distance = distance; }
    }

    private Projection projection(Object line, float x, float y) {
        PointF a = endpoint(line, 0), b = endpoint(line, 1);
        if (a == null || b == null) return null;
        float dx = b.x - a.x, dy = b.y - a.y;
        float l2 = dx * dx + dy * dy;
        if (l2 <= 0.000001f) return null;
        float t = ((x - a.x) * dx + (y - a.y) * dy) / l2;
        PointF p = new PointF(a.x + t * dx, a.y + t * dy);
        return new Projection(p, t, dist(x, y, p.x, p.y));
    }

    private PointF projectToSegment(Object line, float x, float y) {
        Projection pr = projection(line, x, y);
        if (pr == null) return null;
        float t = clamp(pr.t, 0f, 1f);
        PointF a = endpoint(line, 0), b = endpoint(line, 1);
        return new PointF(a.x + t * (b.x - a.x), a.y + t * (b.y - a.y));
    }

    private void addPointOnLineLink(Object endpointLine, int endpointIndex, Object host, float t) {
        for (PointOnLineLink l : pointOnLineLinks) {
            if (l.endpoint.line == endpointLine && l.endpoint.index == endpointIndex && l.host == host) return;
        }
        pointOnLineLinks.add(new PointOnLineLink(new EndpointRef(endpointLine, endpointIndex), host, t));
    }

    private void removePointLink(Object line, int endpointIndex) {
        Iterator<PointOnLineLink> it = pointOnLineLinks.iterator();
        while (it.hasNext()) {
            PointOnLineLink l = it.next();
            if (l.endpoint.line == line && l.endpoint.index == endpointIndex) it.remove();
        }
    }

    private class EndpointRef {
        final Object line;
        final int index;
        float lastX, lastY;
        EndpointRef(Object line, int index) {
            this.line = line; this.index = index;
            snapshot();
        }
        PointF point() { return endpoint(line, index); }
        void set(PointF p) { setEndpoint(line, index, p.x, p.y); snapshot(); }
        float movement() {
            PointF p = point();
            return p == null ? 0f : dist(lastX, lastY, p.x, p.y);
        }
        void snapshot() {
            PointF p = point();
            if (p != null) { lastX = p.x; lastY = p.y; }
        }
    }

    private class PointOnLineLink {
        final EndpointRef endpoint;
        final Object host;
        float t;
        float hx1, hy1, hx2, hy2;
        PointOnLineLink(EndpointRef endpoint, Object host, float t) {
            this.endpoint = endpoint; this.host = host; this.t = t;
            snapshotHost();
        }
        void enforce() {
            if (!isLine(endpoint.line) || !isLine(host)) return;
            PointF ep = endpoint.point();
            if (ep == null) return;
            float hostMove = dist(hx1, hy1, safeGet(host, "x1"), safeGet(host, "y1"))
                    + dist(hx2, hy2, safeGet(host, "x2"), safeGet(host, "y2"));
            float endpointMove = endpoint.movement();
            if (endpointMove > hostMove + 0.001f) {
                Projection pr = projection(host, ep.x, ep.y);
                if (pr != null) {
                    t = clamp(pr.t, 0f, 1f);
                    PointF a = endpoint(host, 0), b = endpoint(host, 1);
                    endpoint.set(new PointF(a.x + t * (b.x - a.x), a.y + t * (b.y - a.y)));
                }
            } else {
                PointF a = endpoint(host, 0), b = endpoint(host, 1);
                if (a != null && b != null) endpoint.set(new PointF(a.x + t * (b.x - a.x), a.y + t * (b.y - a.y)));
            }
            snapshotHost();
            endpoint.snapshot();
        }
        void snapshotHost() {
            hx1 = safeGet(host, "x1"); hy1 = safeGet(host, "y1");
            hx2 = safeGet(host, "x2"); hy2 = safeGet(host, "y2");
        }
    }

    private void enforcePointLinks() {
        pruneLinks();
        for (PointOnLineLink l : pointOnLineLinks) l.enforce();
    }

    private void pruneLinks() {
        List<Object> all = entities();
        Iterator<PointOnLineLink> it = pointOnLineLinks.iterator();
        while (it.hasNext()) {
            PointOnLineLink l = it.next();
            if (!containsIdentity(all, l.endpoint.line) || !containsIdentity(all, l.host)) it.remove();
        }
        Iterator<Object> lk = elementLocks.keySet().iterator();
        while (lk.hasNext()) if (!containsIdentity(all, lk.next())) lk.remove();
    }

    // ---------------------------------------------------------------------
    // Draw and touch
    // ---------------------------------------------------------------------

    @Override
    protected void onDraw(Canvas canvas) {
        enforcePointLinks();
        super.onDraw(canvas);
        pruneLinks();
        drawParametricUi(canvas);
    }

    private void drawParametricUi(Canvas canvas) {
        drawSketchChip(canvas);
        drawConstraintsChip(canvas);
        drawSelectionLockOutline(canvas);
        drawAngleChip(canvas);
        drawLockChip(canvas);
    }

    private void drawSketchChip(Canvas canvas) {
        SketchSpace s = activeSketch();
        String text = s == null ? "Sketch" : (s.locked ? "🔒 " : "") + s.name;
        float w = Math.max(150f, Math.min(250f, chipText.measureText(text) + 38f));
        float right = getWidth() - 12f;
        sketchChip.set(right - w, 92f, right, 92f + UI_H);
        drawChip(canvas, sketchChip, text);
    }

    private void drawConstraintsChip(Canvas canvas) {
        float right = getWidth() - 12f;
        float w = 170f;
        constraintsChip.set(right - w, 148f, right, 148f + UI_H);
        drawChip(canvas, constraintsChip, autoConstraints ? "⌁ Constraints • Auto" : "⌁ Constraints • Manual");
    }

    private void drawAngleChip(Canvas canvas) {
        angleChip.setEmpty();
        if (!canEditAngle()) return;
        PointF c = selectionCenter(selectionObjects());
        if (c == null) return;
        PointF s = worldToScreen(c.x, c.y);
        String text = angleLabel();
        float w = Math.max(120f, chipText.measureText(text) + 34f);
        float left = clamp(s.x - w / 2f, 10f, Math.max(10f, getWidth() - w - 10f));
        float top = clamp(s.y + 62f, 205f, Math.max(205f, getHeight() - 130f));
        angleChip.set(left, top, left + w, top + UI_H);
        drawChip(canvas, angleChip, text);
    }

    private void drawLockChip(Canvas canvas) {
        lockChip.setEmpty();
        List<Object> sel = selectionObjects();
        if (sel.isEmpty()) return;
        PointF c = selectionCenter(sel);
        if (c == null) return;
        PointF s = worldToScreen(c.x, c.y);
        String text = isSelectionLocked() ? "🔒 Lock" : "🔓 Unlocked";
        float w = 105f;
        float left = clamp(s.x + 70f, 10f, Math.max(10f, getWidth() - w - 10f));
        float top = clamp(s.y + 5f, 205f, Math.max(205f, getHeight() - 130f));
        lockChip.set(left, top, left + w, top + 44f);
        drawChip(canvas, lockChip, text);
    }

    private void drawSelectionLockOutline(Canvas canvas) {
        if (!showParametricConstraints) return;
        for (Object e : entities()) {
            if (!isEntityLocked(e)) continue;
            Object b = call(e, "bounds");
            if (!(b instanceof RectF)) continue;
            RectF r = (RectF) b;
            PointF a = worldToScreen(r.left, r.top), z = worldToScreen(r.right, r.bottom);
            RectF sr = new RectF(Math.min(a.x,z.x), Math.min(a.y,z.y), Math.max(a.x,z.x), Math.max(a.y,z.y));
            sr.inset(-5f, -5f);
            canvas.drawRoundRect(sr, 9f, 9f, lockedOutline);
        }
    }

    private void drawChip(Canvas canvas, RectF r, String text) {
        canvas.drawRoundRect(r, 14f, 14f, chipFill);
        canvas.drawRoundRect(r, 14f, 14f, chipStroke);
        canvas.drawText(text, r.centerX(), r.centerY() + 7f, chipText);
    }

    @Override
    public boolean onTouchEvent(MotionEvent original) {
        int action = original.getActionMasked();
        float sx = original.getX(), sy = original.getY();

        if (handleUiChipTouch(original)) return true;

        if (original.getPointerCount() >= 2) {
            lockedGesture = false;
            boolean handled = super.onTouchEvent(original);
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) enforcePointLinks();
            return handled;
        }

        if (activeSketchLocked() && getTool() != TOOL_SELECT && action == MotionEvent.ACTION_DOWN) {
            super.setTool(TOOL_SELECT);
            toast("Sketch text Lock text");
            return true;
        }

        if (lockedGesture) {
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                lockedGesture = false;
                dispatchWorkspaceState();
            }
            return true;
        }

        if (getTool() == TOOL_SELECT && action == MotionEvent.ACTION_DOWN) {
            float wx = screenToWorldX(sx), wy = screenToWorldY(sy);
            Object hit = findHit(wx, wy);
            if (isEntityLocked(hit)) {
                selectOnly(hit);
                lockedGesture = true;
                toast("text Geometry Lock text; Selection text but Transform text");
                return true;
            }
            if (isSelectionLocked()) {
                PointF c = selectionCenter(selectionObjects());
                if (c != null) {
                    PointF cs = worldToScreen(c.x, c.y);
                    if (dist(sx, sy, cs.x, cs.y) <= 115f) {
                        lockedGesture = true;
                        toast("Selection Lock text");
                        return true;
                    }
                }
            }
        }

        int toolBefore = getTool();
        MotionEvent event = original;
        MotionEvent adjusted = null;
        if (toolBefore == TOOL_LINE && (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_UP)) {
            adjusted = adjustLinePointer(original);
            if (adjusted != null) event = adjusted;
        }

        boolean handled = super.onTouchEvent(event);
        if (adjusted != null) adjusted.recycle();

        if (action == MotionEvent.ACTION_UP && toolBefore == TOOL_LINE && getTool() == TOOL_SELECT) {
            Object made = selectedObject();
            if (isLine(made)) detectPointOnLineLinks(made);
        }

        enforcePointLinks();
        invalidate();
        return handled;
    }

    private boolean handleUiChipTouch(MotionEvent event) {
        int action = event.getActionMasked();
        float x = event.getX(), y = event.getY();
        if (action == MotionEvent.ACTION_DOWN) {
            if (sketchChip.contains(x,y)) { pressedChip = 1; return true; }
            if (constraintsChip.contains(x,y)) { pressedChip = 2; return true; }
            if (!angleChip.isEmpty() && angleChip.contains(x,y)) { pressedChip = 3; return true; }
            if (!lockChip.isEmpty() && lockChip.contains(x,y)) { pressedChip = 4; return true; }
            return false;
        }
        if (pressedChip != 0) {
            if (action == MotionEvent.ACTION_UP) {
                int p = pressedChip; pressedChip = 0;
                if (p == 1 && sketchChip.contains(x,y)) showSketchManager();
                else if (p == 2 && constraintsChip.contains(x,y)) showConstraintsMenu();
                else if (p == 3 && angleChip.contains(x,y)) showAngleEditor();
                else if (p == 4 && lockChip.contains(x,y)) toast(toggleSelectedLock());
            } else if (action == MotionEvent.ACTION_CANCEL) pressedChip = 0;
            return true;
        }
        return false;
    }

    // ---------------------------------------------------------------------
    // Lifecycle/edit cleanup
    // ---------------------------------------------------------------------

    @Override
    public void clearAll() {
        super.clearAll();
        elementLocks.clear();
        pointOnLineLinks.clear();
        invalidate();
    }

    @Override
    public void undo() {
        super.undo();
        // Base undo clones geometry objects; identity-based parametric links are
        // discarded instead of ever pointing at stale instances.
        elementLocks.clear();
        pointOnLineLinks.clear();
        invalidate();
    }

    // ---------------------------------------------------------------------
    // Reflection / geometry helpers
    // ---------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private List<Object> entities() {
        try { return entitiesField == null ? new ArrayList<>() : (List<Object>) entitiesField.get(this); }
        catch (Exception e) { return new ArrayList<>(); }
    }

    @SuppressWarnings("unchecked")
    private List<Object> smartSelection() {
        try { return selectedObjectsField == null ? new ArrayList<>() : (List<Object>) selectedObjectsField.get(this); }
        catch (Exception e) { return new ArrayList<>(); }
    }

    private List<Object> selectionObjects() {
        List<Object> s = smartSelection();
        if (!s.isEmpty()) return new ArrayList<>(s);
        List<Object> one = new ArrayList<>();
        Object b = selectedObject();
        if (b != null) one.add(b);
        return one;
    }

    private List<Object> selectedLines() {
        List<Object> out = new ArrayList<>();
        for (Object e : selectionObjects()) if (isLine(e)) out.add(e);
        return out;
    }

    private Object selectedObject() {
        try { return selectedField == null ? null : selectedField.get(this); }
        catch (Exception e) { return null; }
    }

    private Object findHit(float wx, float wy) {
        try { return findHitMethod == null ? null : findHitMethod.invoke(this, wx, wy); }
        catch (Exception e) { return null; }
    }

    private void selectOnly(Object e) {
        try {
            List<Object> smart = smartSelection();
            smart.clear();
            if (e != null) smart.add(e);
            if (selectedField != null) selectedField.set(this, e);
            invalidate();
            dispatchWorkspaceState();
        } catch (Exception ignored) {}
    }

    private void saveUndo() {
        try { if (saveUndoMethod != null) saveUndoMethod.invoke(this); }
        catch (Exception ignored) {}
    }

    private boolean isLine(Object e) {
        return e != null && "LineEntity".equals(e.getClass().getSimpleName());
    }

    private String entityLayer(Object e) {
        Object v = call(e, "getLayer");
        return v == null ? "" : String.valueOf(v);
    }

    private float viewScale() {
        try { return viewScaleField == null ? 1f : viewScaleField.getFloat(this); }
        catch (Exception e) { return 1f; }
    }

    private float offsetX() {
        try { return offsetXField == null ? 0f : offsetXField.getFloat(this); }
        catch (Exception e) { return 0f; }
    }

    private float offsetY() {
        try { return offsetYField == null ? 0f : offsetYField.getFloat(this); }
        catch (Exception e) { return 0f; }
    }

    private float screenToWorldX(float sx) { return (sx - offsetX()) / (PX_PER_MM * viewScale()); }
    private float screenToWorldY(float sy) { return (sy - offsetY()) / (PX_PER_MM * viewScale()); }

    private PointF worldToScreen(float x, float y) {
        float s = PX_PER_MM * viewScale();
        return new PointF(offsetX() + x * s, offsetY() + y * s);
    }

    private PointF endpoint(Object line, int index) {
        if (!isLine(line)) return null;
        try {
            if (index == 0) return new PointF(getFloat(line,"x1"), getFloat(line,"y1"));
            return new PointF(getFloat(line,"x2"), getFloat(line,"y2"));
        } catch (Exception e) { return null; }
    }

    private void setEndpoint(Object line, int index, float x, float y) {
        if (index == 0) { setFloat(line,"x1",x); setFloat(line,"y1",y); }
        else { setFloat(line,"x2",x); setFloat(line,"y2",y); }
    }

    private float lineAngle(Object line) {
        PointF a = endpoint(line,0), b = endpoint(line,1);
        if (a == null || b == null) return 0f;
        return normalize360((float)Math.toDegrees(Math.atan2(b.y-a.y,b.x-a.x)));
    }

    private float displayLineAngle(Object line) {
        float a = lineAngle(line);
        if (a >= 180f) a -= 180f;
        return a;
    }

    private float angleBetween(Object a, Object b) {
        float d = Math.abs(normalizeDelta(lineAngle(a) - lineAngle(b)));
        return d > 180f ? 360f - d : d;
    }

    private PointF selectionCenter(List<Object> selection) {
        RectF all = null;
        for (Object e : selection) {
            Object value = call(e, "bounds");
            if (!(value instanceof RectF)) continue;
            RectF r = new RectF((RectF)value);
            if (all == null) all = r; else all.union(r);
        }
        return all == null ? null : new PointF(all.centerX(), all.centerY());
    }

    private Object call(Object target, String name) { return call(target, name, new Class<?>[0]); }

    private Object call(Object target, String name, Class<?>[] types, Object... args) {
        if (target == null) return null;
        Class<?> c = target.getClass();
        while (c != null) {
            try {
                Method m = c.getDeclaredMethod(name, types);
                m.setAccessible(true);
                return m.invoke(target, args);
            } catch (NoSuchMethodException e) { c = c.getSuperclass(); }
            catch (Exception e) { return null; }
        }
        return null;
    }

    private static Field findField(Class<?> c, String name) {
        Class<?> x = c;
        while (x != null) {
            try { Field f = x.getDeclaredField(name); f.setAccessible(true); return f; }
            catch (Exception e) { x = x.getSuperclass(); }
        }
        return null;
    }

    private static float getFloat(Object o, String name) throws Exception {
        Field f = findField(o.getClass(), name);
        if (f == null) throw new NoSuchFieldException(name);
        return f.getFloat(o);
    }

    private static float safeGet(Object o, String name) {
        try { return getFloat(o,name); } catch (Exception e) { return 0f; }
    }

    private static void setFloat(Object o, String name, float v) {
        try { Field f = findField(o.getClass(), name); if (f != null) f.setFloat(o,v); }
        catch (Exception ignored) {}
    }

    private static Object getObjectField(Object o, String name) {
        if (o == null) return null;
        try { Field f = findField(o.getClass(), name); return f == null ? null : f.get(o); }
        catch (Exception e) { return null; }
    }

    private static int getIntField(Object o, String name, int fallback) {
        if (o == null) return fallback;
        try { Field f = findField(o.getClass(), name); return f == null ? fallback : f.getInt(o); }
        catch (Exception e) { return fallback; }
    }

    private static boolean containsIdentity(List<Object> list, Object value) {
        for (Object x : list) if (x == value) return true;
        return false;
    }

    private static float nearestDirectedAngle(float reference, float target) {
        float a = normalize360(target), b = normalize360(target + 180f);
        return angleDistance(reference,a) <= angleDistance(reference,b) ? a : b;
    }

    private static float angleDistance(float a, float b) { return Math.abs(normalizeDelta(a-b)); }

    private static float normalizeDelta(float d) {
        while (d > 180f) d -= 360f;
        while (d < -180f) d += 360f;
        return d;
    }

    private static float normalize360(float a) {
        while (a < 0f) a += 360f;
        while (a >= 360f) a -= 360f;
        return a;
    }

    private static float dist(float x1, float y1, float x2, float y2) {
        return (float)Math.hypot(x2-x1,y2-y1);
    }

    private static float clamp(float v, float min, float max) { return Math.max(min, Math.min(max,v)); }

    private static String fmt(float v) {
        String s = String.format(Locale.US, "%.1f", v);
        while (s.contains(".") && (s.endsWith("0") || s.endsWith("."))) s = s.substring(0,s.length()-1);
        return s;
    }

    private static String normalizeDigits(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.length());
        for (int i=0;i<s.length();i++) {
            char c=s.charAt(i);
            b.append(c);
        }
        return b.toString();
    }

    private void toast(String text) {
        if (text == null || text.trim().isEmpty()) return;
        Toast.makeText(getContext(), text, Toast.LENGTH_SHORT).show();
    }
}

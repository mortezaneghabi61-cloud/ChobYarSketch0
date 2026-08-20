package ir.chobyar.sketch;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PointF;
import android.text.InputType;
import android.view.MotionEvent;
import android.widget.EditText;
import android.widget.Toast;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;

/**
 * Direct 3D editing layer for ChobYar.
 *
 * This class deliberately sits on AdvancedParametricSolidCadCanvasView so the
 * Revolve/Sweep/Loft toolset is not lost when direct finishing is enabled.
 * It adds Shapr-like selection of real displayed edges/faces, selected-edge
 * Fillet/Chamfer, face Push/Pull/Offset, whole-prism finishing and Shell.
 *
 * The current backend is still polygonal CSG. Therefore exact arbitrary curved
 * B-Rep edge blends are not claimed here: selected-edge finishing and direct
 * face edits are reliable on prism/extrude-like bodies. The UI/feature contract
 * is already suitable for swapping in an exact B-Rep kernel later.
 */
public class DirectModelCadCanvasView extends AdvancedParametricSolidCadCanvasView {

    private enum EditKind {
        EDGE_FILLET, EDGE_CHAMFER, FACE_OFFSET,
        ALL_FILLET, ALL_CHAMFER, SHELL
    }

    private static final int FACE_TOP = 1;
    private static final int FACE_BOTTOM = 2;
    private static final int FACE_SIDE = 3;

    private static final class DirectOp {
        final EditKind kind;
        float valueMm;
        final int selector;
        final Geometry3D.Vec3 anchor;

        DirectOp(EditKind kind, float valueMm, int selector, Geometry3D.Vec3 anchor) {
            this.kind = kind;
            this.valueMm = valueMm;
            this.selector = selector;
            this.anchor = anchor;
        }

        String label() {
            String n;
            switch (kind) {
                case EDGE_FILLET: n = "Edge Fillet"; break;
                case EDGE_CHAMFER: n = "Edge Chamfer"; break;
                case FACE_OFFSET: n = "Face Offset"; break;
                case ALL_FILLET: n = "Fillet All"; break;
                case ALL_CHAMFER: n = "Chamfer All"; break;
                default: n = "Shell"; break;
            }
            return n + " • " + dual(valueMm);
        }
    }

    private static final class PrismData {
        final List<PointF> profile;
        final Geometry3D.Plane3D plane;
        final Geometry3D.Vec3 axis;
        final float heightMm;
        final SolidCSG.Polygon baseCap;
        final SolidCSG.Polygon topCap;
        final Geometry3D.Vec3 baseCenter;
        final Geometry3D.Vec3 topCenter;

        PrismData(List<PointF> profile, Geometry3D.Plane3D plane,
                  Geometry3D.Vec3 axis, float heightMm,
                  SolidCSG.Polygon baseCap, SolidCSG.Polygon topCap) {
            this.profile = profile;
            this.plane = plane;
            this.axis = axis;
            this.heightMm = heightMm;
            this.baseCap = baseCap;
            this.topCap = topCap;
            this.baseCenter = baseCap.centroid();
            this.topCenter = topCap.centroid();
        }
    }

    private final IdentityHashMap<Object, SolidCSG> directBaseByBody = new IdentityHashMap<>();
    private final IdentityHashMap<Object, List<DirectOp>> directOpsByBody = new IdentityHashMap<>();

    private Field selectedBodyField;
    private Field selectedFaceField;
    private Method projectMethod;

    private boolean edgePickMode = false;
    private boolean edgeMoved = false;
    private float edgeDownX, edgeDownY;
    private Object selectedEdgeBody;
    private Geometry3D.Vec3 selectedEdgeA;
    private Geometry3D.Vec3 selectedEdgeB;

    private final Paint edgeHighlight = new Paint(Paint.ANTI_ALIAS_FLAG);

    public DirectModelCadCanvasView(Context context) {
        super(context);
        edgeHighlight.setColor(Color.rgb(255, 130, 30));
        edgeHighlight.setStrokeWidth(7f);
        edgeHighlight.setStrokeCap(Paint.Cap.ROUND);
        try {
            selectedBodyField = field(SolidCadCanvasView.class, "selectedBody");
            selectedFaceField = field(SolidCadCanvasView.class, "selectedFace");
            projectMethod = SpatialCadCanvasView.class.getDeclaredMethod("project", Geometry3D.Vec3.class);
            projectMethod.setAccessible(true);
        } catch (Exception ignored) {}
    }

    private static Field field(Class<?> owner, String name) throws NoSuchFieldException {
        Field f = owner.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }

    // ------------------------------------------------------------------
    // Adaptive direct-edit UI
    // ------------------------------------------------------------------

    public void showDirectManager() {
        Object body = selectedBody();
        Object face = selectedFace();
        String bodyText = body == null ? "Body انتخاب نشده" : bodyName(body);
        String faceText = face == null ? "Face انتخاب نشده" : "Face آماده Push/Pull است";
        String edgeText = selectedEdgeA == null ? "Edge انتخاب نشده" : "Edge انتخاب شده";

        String[] items = {
                "⌁ انتخاب Edge با لمس روی مدل",
                "⌒ Fillet فقط Edge انتخاب‌شده",
                "◩ Chamfer فقط Edge انتخاب‌شده",
                "↕ Push/Pull یا Offset روی Face انتخاب‌شده",
                "⌒ Fillet همه لبه‌های عمودی",
                "◩ Chamfer همه لبه‌های عمودی",
                "▱ Shell / توخالی",
                "⏱ Direct Edit History",
                "↺ پاک‌کردن Direct Editهای Body"
        };

        new AlertDialog.Builder(getContext())
                .setTitle("Edit 3D • مستقیم")
                .setMessage(bodyText + "\n" + faceText + " • " + edgeText
                        + "\n\nبرای Face فقط روی سطح بزن. برای Edge گزینه اول را بزن و بعد خود لبه را لمس کن."
                        + "\nواحد همه اندازه‌ها: میلی‌متر (mm)")
                .setItems(items, (d,w) -> {
                    if (w == 0) beginEdgePick();
                    else if (w == 1) askEdgeEdit(EditKind.EDGE_FILLET);
                    else if (w == 2) askEdgeEdit(EditKind.EDGE_CHAMFER);
                    else if (w == 3) askFaceOffset();
                    else if (w == 4) askBodyEdit(EditKind.ALL_FILLET);
                    else if (w == 5) askBodyEdit(EditKind.ALL_CHAMFER);
                    else if (w == 6) askBodyEdit(EditKind.SHELL);
                    else if (w == 7) showDirectHistory();
                    else toast(clearDirectEdits());
                })
                .setNegativeButton("بستن", null)
                .show();
    }

    /** Keep the old Finish entry useful: it now opens the more capable adaptive editor. */
    public void showFinishManager() {
        showDirectManager();
    }

    private void beginEdgePick() {
        if (selectedBody() == null) {
            ensure3D();
            toast("اول روی Body بزن، بعد دوباره انتخاب Edge را بزن");
            return;
        }
        ensure3D();
        edgePickMode = true;
        selectedEdgeA = selectedEdgeB = null;
        selectedEdgeBody = null;
        invalidate();
        toast("حالت انتخاب Edge روشن شد — روی لبه موردنظر بزن");
    }

    private void askEdgeEdit(EditKind kind) {
        Object body = selectedBody();
        if (body == null || selectedEdgeA == null || selectedEdgeB == null || selectedEdgeBody != body) {
            beginEdgePick();
            return;
        }
        PrismData p = analyzePrism(bodyCsg(body));
        if (p == null) { toast("این Edge فعلاً برای Bodyهای Prism/Extrude قابل ویرایش مستقیم است"); return; }
        if (!isVerticalEdge(p, selectedEdgeA, selectedEdgeB)) {
            toast("Edge انتخاب شد، ولی Fillet/Chamfer انتخابی فعلاً روی لبه‌های عمودی Prism اجرا می‌شود");
            return;
        }
        String title = kind == EditKind.EDGE_FILLET ? "Fillet Edge — شعاع" : "Chamfer Edge — فاصله";
        askLength(title, "مثال: 5", "5", value -> {
            DirectOp op = new DirectOp(kind, value, 0, midpoint(selectedEdgeA, selectedEdgeB));
            toast(recordAndApply(body, op));
        });
    }

    private void askFaceOffset() {
        Object body = selectedBody();
        SolidCSG.Polygon face = selectedFace();
        if (body == null || face == null) {
            ensure3D();
            toast("اول در نمای 3D روی Face موردنظر بزن");
            return;
        }
        PrismData p = analyzePrism(bodyCsg(body));
        if (p == null) { toast("Push/Pull فعلی روی Faceهای Bodyهای Prism/Extrude اجرا می‌شود"); return; }
        int selector = faceSelector(p, face);
        Geometry3D.Vec3 anchor = face.centroid();
        askLength("Push/Pull Face — فاصله", "مثبت = بیرون، منفی = داخل؛ مثال: 12", "10", value -> {
            if (Math.abs(value) < 1e-5f) { toast("فاصله نباید صفر باشد"); return; }
            toast(recordAndApply(body, new DirectOp(EditKind.FACE_OFFSET, value, selector, anchor)));
        });
    }

    private void askBodyEdit(EditKind kind) {
        Object body = selectedBody();
        if (body == null) { toast("اول یک Body را انتخاب کن"); return; }
        String title = kind == EditKind.ALL_FILLET ? "Fillet همه Edgeها — شعاع"
                : kind == EditKind.ALL_CHAMFER ? "Chamfer همه Edgeها — فاصله"
                : "Shell — ضخامت دیواره";
        String def = kind == EditKind.SHELL ? "2" : "5";
        askLength(title, "میلی‌متر", def, value -> {
            if (!(value > 0f)) { toast("مقدار باید بزرگ‌تر از صفر باشد"); return; }
            toast(recordAndApply(body, new DirectOp(kind, value, 0, null)));
        });
    }

    private interface LengthConsumer { void accept(float mm); }

    private void askLength(String title, String message, String initial, LengthConsumer consumer) {
        EditText input = new EditText(getContext());
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setText(initial);
        input.setSelectAllOnFocus(true);
        new AlertDialog.Builder(getContext())
                .setTitle(title + " • mm")
                .setMessage(message)
                .setView(input)
                .setPositiveButton("اعمال", (d,w) -> {
                    try { consumer.accept(parseLengthMm(input.getText().toString())); }
                    catch (Exception e) { toast("اندازه درست وارد نشده"); }
                })
                .setNegativeButton("لغو", null)
                .show();
    }

    /** Deterministic non-modal 3D finishing entry for command/tests. */
    public String applyAllFillet(float radiusMm) {
        if (!(radiusMm > 0f)) return "شعاع Fillet باید بزرگ‌تر از صفر باشد";
        Object body = selectedBody();
        if (body == null) return "اول یک Body را انتخاب کن";
        return recordAndApply(body, new DirectOp(EditKind.ALL_FILLET, radiusMm, 0, null));
    }

    /** Deterministic non-modal 3D finishing entry for command/tests. */
    public String applyAllChamfer(float distanceMm) {
        if (!(distanceMm > 0f)) return "فاصله Chamfer باید بزرگ‌تر از صفر باشد";
        Object body = selectedBody();
        if (body == null) return "اول یک Body را انتخاب کن";
        return recordAndApply(body, new DirectOp(EditKind.ALL_CHAMFER, distanceMm, 0, null));
    }

    /** Deterministic one-open-face Shell for prism/extrude bodies. */
    public String applyShell3D(float wallMm) {
        if (!(wallMm > 0f)) return "ضخامت Shell باید بزرگ‌تر از صفر باشد";
        Object body = selectedBody();
        if (body == null) return "اول یک Body را انتخاب کن";
        return recordAndApply(body, new DirectOp(EditKind.SHELL, wallMm, 0, null));
    }

    /** Deterministic axial Push/Pull. Positive distance extends the selected cap outward. */
    public String applyAxialFaceOffset(boolean top, float distanceMm) {
        if (Math.abs(distanceMm) < 1e-5f) return "فاصله Push/Pull نباید صفر باشد";
        Object body = selectedBody();
        if (body == null) return "اول یک Body را انتخاب کن";
        return recordAndApply(body, new DirectOp(EditKind.FACE_OFFSET, distanceMm, top ? FACE_TOP : FACE_BOTTOM, null));
    }

    @Override
    public String executeCommand(String raw) {
        if (raw != null) {
            String s = normalizeDigits(raw).trim().replace(',', ' ');
            if (!s.isEmpty()) {
                String[] a = s.split("\\s+");
                String op = a[0].toUpperCase(Locale.US);
                boolean fillet = "FILLET3D".equals(op) || "FILLETALL".equals(op);
                boolean chamfer = "CHAMFER3D".equals(op) || "CHAMFERALL".equals(op);
                if ("SHELL3D".equals(op)) {
                    if (a.length != 2) return "SHELL3D — ضخامت بر حسب mm لازم است؛ مثال: SHELL3D 5";
                    try { return applyShell3D(parseLengthMm(a[1])); }
                    catch (Exception e) { return "ضخامت Shell درست نیست"; }
                }
                if ("PUSHPULL3D".equals(op)) {
                    if (a.length != 3) return "PUSHPULL3D — TOP/BOTTOM و فاصله لازم است؛ مثال: PUSHPULL3D TOP 10";
                    boolean top = "TOP".equalsIgnoreCase(a[1]);
                    boolean bottom = "BOTTOM".equalsIgnoreCase(a[1]);
                    if (!top && !bottom) return "Face باید TOP یا BOTTOM باشد";
                    try { return applyAxialFaceOffset(top, parseLengthMm(a[2])); }
                    catch (Exception e) { return "فاصله Push/Pull درست نیست"; }
                }
                if (fillet || chamfer) {
                    if (a.length != 2) return op + " — یک اندازه بر حسب mm لازم است؛ مثال: " + op + " 5";
                    try {
                        float mm = parseLengthMm(a[1]);
                        return fillet ? applyAllFillet(mm) : applyAllChamfer(mm);
                    } catch (Exception e) {
                        return "اندازه Fillet/Chamfer درست نیست";
                    }
                }
            }
        }
        return super.executeCommand(raw);
    }

    // ------------------------------------------------------------------
    // Direct feature history
    // ------------------------------------------------------------------

    private String recordAndApply(Object body, DirectOp op) {
        SolidCSG current = bodyCsg(body);
        if (current == null || current.isEmpty()) return "Body معتبر نیست";
        if (!directBaseByBody.containsKey(body)) directBaseByBody.put(body, current.copy());
        List<DirectOp> ops = directOpsByBody.get(body);
        if (ops == null) { ops = new ArrayList<>(); directOpsByBody.put(body, ops); }
        if (op.kind == EditKind.SHELL) {
            for (DirectOp x : ops) if (x.kind == EditKind.SHELL) return "برای این Body قبلاً Shell ثبت شده";
        }
        ops.add(op);
        String result = rebuildDirect(body);
        if (result.startsWith("خطا")) {
            ops.remove(ops.size()-1);
            rebuildDirect(body);
            return result;
        }
        selectedEdgeA = selectedEdgeB = null;
        selectedEdgeBody = null;
        setSelectedFace(null);
        ensure3D();
        invalidate();
        return op.label() + " اعمال شد";
    }

    public void showDirectHistory() {
        Object body = selectedBody();
        if (body == null) { toast("اول یک Body را انتخاب کن"); return; }
        List<DirectOp> ops = directOpsByBody.get(body);
        if (ops == null || ops.isEmpty()) { toast("Direct Edit History خالی است"); return; }
        String[] rows = new String[ops.size()];
        for (int i=0;i<ops.size();i++) rows[i] = (i+1) + ". " + ops.get(i).label();
        new AlertDialog.Builder(getContext())
                .setTitle("Direct Edit History • " + bodyName(body))
                .setMessage("Feature را لمس کن تا مقدارش را عوض یا حذف کنی.")
                .setItems(rows, (d,w) -> editDirectOp(body,w))
                .setNeutralButton("بازسازی", (d,w) -> toast(rebuildDirect(body)))
                .setNegativeButton("بستن", null)
                .show();
    }

    private void editDirectOp(Object body, int index) {
        List<DirectOp> ops = directOpsByBody.get(body);
        if (ops == null || index < 0 || index >= ops.size()) return;
        DirectOp op = ops.get(index);
        EditText input = new EditText(getContext());
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setText(num(op.valueMm) + "mm");
        input.setSelectAllOnFocus(true);
        new AlertDialog.Builder(getContext())
                .setTitle("ویرایش " + op.label())
                .setView(input)
                .setPositiveButton("اعمال", (d,w) -> {
                    try { op.valueMm = parseLengthMm(input.getText().toString()); toast(rebuildDirect(body)); }
                    catch (Exception e) { toast("اندازه درست وارد نشده"); }
                })
                .setNeutralButton("حذف Feature", (d,w) -> {
                    ops.remove(index);
                    if (ops.isEmpty()) {
                        restoreDirectBase(body);
                        directOpsByBody.remove(body);
                        directBaseByBody.remove(body);
                    } else toast(rebuildDirect(body));
                    invalidate();
                })
                .setNegativeButton("لغو", null)
                .show();
    }

    private String clearDirectEdits() {
        Object body = selectedBody();
        if (body == null) return "اول یک Body را انتخاب کن";
        restoreDirectBase(body);
        directOpsByBody.remove(body);
        directBaseByBody.remove(body);
        selectedEdgeA = selectedEdgeB = null;
        selectedEdgeBody = null;
        setSelectedFace(null);
        invalidate();
        return "Direct Editهای Body پاک شدند";
    }

    private void restoreDirectBase(Object body) {
        SolidCSG base = directBaseByBody.get(body);
        if (base != null) setBodyCsg(body, base.copy());
    }

    private String rebuildDirect(Object body) {
        SolidCSG base = directBaseByBody.get(body);
        if (base == null) return "خطا: پایه Direct Edit پیدا نشد";
        List<DirectOp> ops = directOpsByBody.get(body);
        if (ops == null || ops.isEmpty()) { setBodyCsg(body, base.copy()); return "Direct Edit خالی است"; }
        SolidCSG result = base.copy();
        for (DirectOp op : ops) {
            PrismData p = analyzePrism(result);
            if (p == null) return "خطا: این Direct Edit فعلاً به Body Prism/Extrude نیاز دارد";
            if (op.kind == EditKind.EDGE_FILLET) {
                int corner = nearestCorner(p, op.anchor);
                List<PointF> q = filletOne(p.profile, corner, op.valueMm);
                if (q == null) return "خطا: شعاع Fillet برای Edge انتخابی بزرگ یا نامعتبر است";
                result = SolidCSG.extrude(q, p.plane, p.heightMm);
            } else if (op.kind == EditKind.EDGE_CHAMFER) {
                int corner = nearestCorner(p, op.anchor);
                List<PointF> q = chamferOne(p.profile, corner, op.valueMm);
                if (q == null) return "خطا: Chamfer برای Edge انتخابی بزرگ یا نامعتبر است";
                result = SolidCSG.extrude(q, p.plane, p.heightMm);
            } else if (op.kind == EditKind.FACE_OFFSET) {
                result = offsetFace(p, op);
                if (result == null || result.isEmpty()) return "خطا: جابه‌جایی Face نامعتبر شد";
            } else if (op.kind == EditKind.ALL_FILLET) {
                List<PointF> q = filletAll(p.profile, op.valueMm);
                if (q == null) return "خطا: شعاع Fillet برای این Body بزرگ است";
                result = SolidCSG.extrude(q, p.plane, p.heightMm);
            } else if (op.kind == EditKind.ALL_CHAMFER) {
                List<PointF> q = chamferAll(p.profile, op.valueMm);
                if (q == null) return "خطا: Chamfer برای این Body بزرگ است";
                result = SolidCSG.extrude(q, p.plane, p.heightMm);
            } else {
                if (op.valueMm >= p.heightMm * 0.49f) return "خطا: ضخامت Shell از ارتفاع Body زیاد است";
                List<PointF> inner = insetProfile(p.profile, op.valueMm);
                if (inner == null) return "خطا: ضخامت Shell برای این پروفایل زیاد است";
                Geometry3D.Plane3D innerPlane = p.plane.offset(op.valueMm, "Shell inner");
                SolidCSG cut = SolidCSG.extrude(inner, innerPlane, p.heightMm + op.valueMm * 1.5f);
                result = result.subtract(cut);
                if (result.isEmpty()) return "خطا: Shell نامعتبر شد";
            }
        }
        setBodyCsg(body, result);
        invalidate();
        return "Direct Edit بازسازی شد • " + ops.size() + " Feature";
    }

    /**
     * When upstream Sketch/Extrude/Form history changes, the parent rebuilds the
     * source body first. If the result changed, capture it as the new direct-edit
     * base and replay the direct feature stack.
     */
    @Override
    public String rebuildHistory() {
        IdentityHashMap<Object,String> before = new IdentityHashMap<>();
        for (Object body : directOpsByBody.keySet()) before.put(body, csgSignature(bodyCsg(body)));
        String parent = super.rebuildHistory();
        int replayed = 0;
        for (Object body : new ArrayList<>(directOpsByBody.keySet())) {
            SolidCSG after = bodyCsg(body);
            if (after == null) continue;
            String old = before.get(body), now = csgSignature(after);
            if (old == null || !old.equals(now)) directBaseByBody.put(body, after.copy());
            String r = rebuildDirect(body);
            if (!r.startsWith("خطا")) replayed++;
        }
        return parent + (replayed > 0 ? " • Direct " + replayed : "");
    }

    // ------------------------------------------------------------------
    // Edge selection in 3D
    // ------------------------------------------------------------------

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (is3DOverview() && selectedEdgeA != null && selectedEdgeB != null && selectedEdgeBody == selectedBody()) {
            PointF a = project(selectedEdgeA), b = project(selectedEdgeB);
            canvas.drawLine(a.x, a.y, b.x, b.y, edgeHighlight);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (edgePickMode) {
            if (action == MotionEvent.ACTION_DOWN) {
                edgeDownX = event.getX(); edgeDownY = event.getY(); edgeMoved = false;
            } else if (action == MotionEvent.ACTION_MOVE) {
                if (Math.hypot(event.getX()-edgeDownX, event.getY()-edgeDownY) > 10f) edgeMoved = true;
            }
        }

        boolean handled = super.onTouchEvent(event);

        if (edgePickMode && action == MotionEvent.ACTION_UP && !edgeMoved && is3DOverview()) {
            if (pickNearestEdge(event.getX(), event.getY())) {
                edgePickMode = false;
                post(this::showEdgeQuickMenu);
            } else toast("لبه‌ای نزدیک محل لمس پیدا نشد؛ کمی نزدیک‌تر روی خط بزن");
            invalidate();
        }
        return handled;
    }

    private boolean pickNearestEdge(float sx, float sy) {
        Object body = selectedBody();
        SolidCSG csg = bodyCsg(body);
        if (body == null || csg == null) return false;
        float best = 36f;
        Geometry3D.Vec3 bestA = null, bestB = null;
        for (SolidCSG.Polygon poly : csg.polygons()) {
            int n = poly.vertices.size();
            for (int i=0;i<n;i++) {
                Geometry3D.Vec3 a = poly.vertices.get(i).pos;
                Geometry3D.Vec3 b = poly.vertices.get((i+1)%n).pos;
                PointF pa = project(a), pb = project(b);
                float d = distanceToSegment(sx,sy,pa.x,pa.y,pb.x,pb.y);
                if (d < best) { best=d; bestA=a; bestB=b; }
            }
        }
        if (bestA == null) return false;
        selectedEdgeBody = body;
        selectedEdgeA = bestA;
        selectedEdgeB = bestB;
        return true;
    }

    private void showEdgeQuickMenu() {
        Object body = selectedBody();
        if (body == null || selectedEdgeA == null) return;
        PrismData p = analyzePrism(bodyCsg(body));
        boolean editable = p != null && isVerticalEdge(p, selectedEdgeA, selectedEdgeB);
        String[] items = editable
                ? new String[]{"⌒ Fillet همین Edge", "◩ Chamfer همین Edge", "✓ فقط انتخاب بماند"}
                : new String[]{"✓ Edge انتخاب شد", "⌁ انتخاب Edge دیگر"};
        new AlertDialog.Builder(getContext())
                .setTitle("Edge انتخاب شد")
                .setMessage(editable
                        ? "این لبه در هسته فعلی قابل Fillet/Chamfer انتخابی است."
                        : "انتخاب Edge واقعی انجام شد؛ این نوع لبه برای Blend دقیق به B-Rep نهایی نیاز دارد.")
                .setItems(items, (d,w) -> {
                    if (editable && w == 0) askEdgeEdit(EditKind.EDGE_FILLET);
                    else if (editable && w == 1) askEdgeEdit(EditKind.EDGE_CHAMFER);
                    else if (!editable && w == 1) beginEdgePick();
                })
                .setNegativeButton("بستن", null)
                .show();
    }

    // ------------------------------------------------------------------
    // Prism recognition and face/edge selectors
    // ------------------------------------------------------------------

    private PrismData analyzePrism(SolidCSG csg) {
        if (csg == null || csg.polygons().size() < 3) return null;
        List<SolidCSG.Polygon> polys = csg.polygons();
        SolidCSG.Polygon bestA = null, bestB = null;
        float bestScore = 0f;
        for (int i=0;i<polys.size();i++) {
            SolidCSG.Polygon a = polys.get(i);
            if (a.vertices.size() < 3) continue;
            for (int j=i+1;j<polys.size();j++) {
                SolidCSG.Polygon b = polys.get(j);
                if (b.vertices.size() != a.vertices.size()) continue;
                float nd = a.plane.normal.dot(b.plane.normal);
                if (nd > -0.94f) continue;
                Geometry3D.Vec3 delta = b.centroid().sub(a.centroid());
                float h = delta.length();
                if (h < 1e-4f) continue;
                float align = Math.abs(delta.normalized().dot(a.plane.normal));
                if (align < 0.92f) continue;
                float score = Math.min(polygonArea(a), polygonArea(b)) * h;
                if (score > bestScore) { bestScore=score; bestA=a; bestB=b; }
            }
        }
        if (bestA == null || bestB == null) return null;

        Geometry3D.Vec3 ca = bestA.centroid(), cb = bestB.centroid();
        Geometry3D.Vec3 axisVec = cb.sub(ca);
        float h = axisVec.length();
        Geometry3D.Vec3 axis = axisVec.normalized();
        Geometry3D.Vec3 origin = bestA.vertices.get(0).pos;
        Geometry3D.Vec3 edge = bestA.vertices.get(1).pos.sub(origin);
        edge = edge.sub(axis.mul(edge.dot(axis)));
        if (edge.length() < 1e-5f) return null;
        Geometry3D.Vec3 u = edge.normalized();
        Geometry3D.Vec3 v = axis.cross(u).normalized();
        Geometry3D.Plane3D plane = new Geometry3D.Plane3D(origin,u,v,"Direct Edit Plane");
        List<PointF> profile = new ArrayList<>();
        for (SolidCSG.Vertex vertex : bestA.vertices) {
            Geometry3D.Vec3 d = vertex.pos.sub(origin);
            profile.add(new PointF(d.dot(plane.u), d.dot(plane.v)));
        }
        if (Math.abs(signedArea(profile)) < 1e-4f) return null;
        if (signedArea(profile) < 0f) {
            List<PointF> rev = new ArrayList<>();
            for (int i=profile.size()-1;i>=0;i--) rev.add(profile.get(i));
            profile = rev;
        }
        return new PrismData(profile,plane,axis,h,bestA,bestB);
    }

    private static float polygonArea(SolidCSG.Polygon p) {
        if (p.vertices.size() < 3) return 0f;
        Geometry3D.Vec3 o = p.vertices.get(0).pos;
        float area = 0f;
        for (int i=1;i<p.vertices.size()-1;i++) {
            Geometry3D.Vec3 a = p.vertices.get(i).pos.sub(o);
            Geometry3D.Vec3 b = p.vertices.get(i+1).pos.sub(o);
            area += a.cross(b).length() * 0.5f;
        }
        return area;
    }

    private boolean isVerticalEdge(PrismData p, Geometry3D.Vec3 a, Geometry3D.Vec3 b) {
        Geometry3D.Vec3 d = b.sub(a);
        if (d.length() < 1e-5f) return false;
        return Math.abs(d.normalized().dot(p.axis)) > 0.94f;
    }

    private int faceSelector(PrismData p, SolidCSG.Polygon face) {
        Geometry3D.Vec3 n = face.plane.normal.normalized();
        if (Math.abs(n.dot(p.axis)) > 0.90f) {
            float db = face.centroid().sub(p.baseCenter).length();
            float dt = face.centroid().sub(p.topCenter).length();
            return dt < db ? FACE_TOP : FACE_BOTTOM;
        }
        return FACE_SIDE;
    }

    private int nearestCorner(PrismData p, Geometry3D.Vec3 anchor) {
        if (anchor == null) return 0;
        Geometry3D.Vec3 d = anchor.sub(p.plane.origin);
        float x = d.dot(p.plane.u), y = d.dot(p.plane.v);
        int best = 0; float bd = Float.MAX_VALUE;
        for (int i=0;i<p.profile.size();i++) {
            PointF q = p.profile.get(i);
            float ds = (q.x-x)*(q.x-x)+(q.y-y)*(q.y-y);
            if (ds < bd) { bd=ds; best=i; }
        }
        return best;
    }

    private int nearestSide(PrismData p, Geometry3D.Vec3 anchor) {
        if (anchor == null) return 0;
        Geometry3D.Vec3 d = anchor.sub(p.plane.origin);
        float x = d.dot(p.plane.u), y = d.dot(p.plane.v);
        int best = 0; float bd = Float.MAX_VALUE;
        for (int i=0;i<p.profile.size();i++) {
            PointF a=p.profile.get(i), b=p.profile.get((i+1)%p.profile.size());
            float mx=(a.x+b.x)*0.5f, my=(a.y+b.y)*0.5f;
            float ds=(mx-x)*(mx-x)+(my-y)*(my-y);
            if(ds<bd){bd=ds;best=i;}
        }
        return best;
    }

    // ------------------------------------------------------------------
    // Geometry operations
    // ------------------------------------------------------------------

    private SolidCSG offsetFace(PrismData p, DirectOp op) {
        float d = op.valueMm;
        if (op.selector == FACE_TOP) {
            float h = p.heightMm + d;
            if (h <= 0.1f) return null;
            return SolidCSG.extrude(p.profile, p.plane, h);
        }
        if (op.selector == FACE_BOTTOM) {
            float h = p.heightMm + d;
            if (h <= 0.1f) return null;
            Geometry3D.Vec3 newOrigin = p.plane.origin.sub(p.axis.mul(d));
            Geometry3D.Plane3D plane = new Geometry3D.Plane3D(newOrigin,p.plane.u,p.plane.v,"Moved bottom");
            return SolidCSG.extrude(p.profile, plane, h);
        }
        int side = nearestSide(p, op.anchor);
        List<PointF> q = offsetOneEdge(p.profile, side, d);
        if (q == null) return null;
        return SolidCSG.extrude(q, p.plane, p.heightMm);
    }

    private static List<PointF> offsetOneEdge(List<PointF> src, int edgeIndex, float distance) {
        if (src == null || src.size() < 3) return null;
        List<PointF> p = copy(src);
        int n=p.size(), i=((edgeIndex%n)+n)%n, j=(i+1)%n;
        PointF a=p.get(i), b=p.get(j);
        float dx=b.x-a.x, dy=b.y-a.y, l=(float)Math.hypot(dx,dy);
        if(l<1e-6f)return null;
        // Profile is CCW: right-hand normal of an edge points outward.
        float nx=dy/l, ny=-dx/l;
        PointF sa=new PointF(a.x+nx*distance,a.y+ny*distance);
        PointF sb=new PointF(b.x+nx*distance,b.y+ny*distance);
        PointF prev=p.get((i-1+n)%n), next=p.get((j+1)%n);
        PointF ni=lineIntersection(prev,a,sa,sb);
        PointF nj=lineIntersection(sa,sb,b,next);
        if(ni==null||nj==null)return null;
        p.set(i,ni);p.set(j,nj);
        return validProfile(p)?p:null;
    }

    private static List<PointF> chamferOne(List<PointF> src,int index,float d) {
        if(src==null||src.size()<3||d<=0)return null;
        int n=src.size(),i=((index%n)+n)%n;
        PointF prev=src.get((i-1+n)%n),cur=src.get(i),next=src.get((i+1)%n);
        float l1=len(cur,prev),l2=len(cur,next);
        if(d>=Math.min(l1,l2)*0.48f)return null;
        if(!isConvex(src,i))return null;
        List<PointF> out=new ArrayList<>();
        for(int k=0;k<n;k++){
            if(k!=i)out.add(new PointF(src.get(k).x,src.get(k).y));
            else{
                out.add(moveToward(cur,prev,d));
                out.add(moveToward(cur,next,d));
            }
        }
        return validProfile(out)?out:null;
    }

    private static List<PointF> filletOne(List<PointF> src,int index,float radius) {
        if(src==null||src.size()<3||radius<=0)return null;
        int n=src.size(),i=((index%n)+n)%n;
        PointF prev=src.get((i-1+n)%n),cur=src.get(i),next=src.get((i+1)%n);
        if(!isConvex(src,i))return null;
        float l1=len(cur,prev),l2=len(cur,next);
        if(l1<1e-5f||l2<1e-5f)return null;
        float ax=(prev.x-cur.x)/l1, ay=(prev.y-cur.y)/l1;
        float bx=(next.x-cur.x)/l2, by=(next.y-cur.y)/l2;
        float dot=clamp(ax*bx+ay*by,-1f,1f);
        double theta=Math.acos(dot);
        if(theta<0.05||Math.PI-theta<0.02)return null;
        float t=(float)(radius/Math.tan(theta/2.0));
        if(t>=Math.min(l1,l2)*0.48f)return null;
        float sx=ax+bx,sy=ay+by,sl=(float)Math.hypot(sx,sy);
        if(sl<1e-5f)return null;
        sx/=sl;sy/=sl;
        float centerDist=(float)(radius/Math.sin(theta/2.0));
        PointF center=new PointF(cur.x+sx*centerDist,cur.y+sy*centerDist);
        PointF p1=new PointF(cur.x+ax*t,cur.y+ay*t);
        PointF p2=new PointF(cur.x+bx*t,cur.y+by*t);
        double a1=Math.atan2(p1.y-center.y,p1.x-center.x);
        double a2=Math.atan2(p2.y-center.y,p2.x-center.x);
        double delta=a2-a1;
        while(delta<0)delta+=Math.PI*2;
        if(delta>Math.PI)delta-=Math.PI*2;
        // For a CCW convex profile the inside fillet sweep is the positive short arc.
        if(delta<0)delta+=Math.PI*2;
        int seg=Math.max(4,(int)Math.ceil(Math.abs(delta)/(Math.PI/24.0)));
        List<PointF> out=new ArrayList<>();
        for(int k=0;k<n;k++){
            if(k!=i){out.add(new PointF(src.get(k).x,src.get(k).y));continue;}
            out.add(p1);
            for(int s=1;s<seg;s++){
                double a=a1+delta*s/seg;
                out.add(new PointF(center.x+(float)Math.cos(a)*radius,center.y+(float)Math.sin(a)*radius));
            }
            out.add(p2);
        }
        return validProfile(out)?out:null;
    }

    private static List<PointF> chamferAll(List<PointF> src,float d) {
        if(src==null||src.size()<3)return null;
        List<PointF> out=copy(src);
        // Build in one pass so every original corner uses original neighbors.
        out.clear();
        for(int i=0;i<src.size();i++){
            PointF prev=src.get((i-1+src.size())%src.size()),cur=src.get(i),next=src.get((i+1)%src.size());
            if(!isConvex(src,i)){out.add(new PointF(cur.x,cur.y));continue;}
            float t=Math.min(d,Math.min(len(cur,prev),len(cur,next))*0.45f);
            if(t<=1e-5f){out.add(new PointF(cur.x,cur.y));continue;}
            out.add(moveToward(cur,prev,t));out.add(moveToward(cur,next,t));
        }
        return validProfile(out)?out:null;
    }

    private static List<PointF> filletAll(List<PointF> src,float r) {
        if(src==null||src.size()<3)return null;
        List<PointF> result=copy(src);
        // Apply from the highest original index so earlier indices remain stable enough.
        for(int i=src.size()-1;i>=0;i--){
            List<PointF> q=filletOne(result,i,r);
            if(q==null)return null;
            result=q;
        }
        return validProfile(result)?result:null;
    }

    private static List<PointF> insetProfile(List<PointF> p,float d) {
        if(p==null||p.size()<3||d<=0)return null;
        int n=p.size();
        PointF[] a=new PointF[n],b=new PointF[n];
        for(int i=0;i<n;i++){
            PointF p0=p.get(i),p1=p.get((i+1)%n);
            float dx=p1.x-p0.x,dy=p1.y-p0.y,l=(float)Math.hypot(dx,dy);
            if(l<1e-5f)return null;
            // CCW profile: inward is left normal.
            float nx=-dy/l,ny=dx/l;
            a[i]=new PointF(p0.x+nx*d,p0.y+ny*d);
            b[i]=new PointF(p1.x+nx*d,p1.y+ny*d);
        }
        List<PointF> out=new ArrayList<>();
        for(int i=0;i<n;i++){
            int prev=(i-1+n)%n;
            PointF x=lineIntersection(a[prev],b[prev],a[i],b[i]);
            if(x==null)return null;
            out.add(x);
        }
        return validProfile(out)?out:null;
    }

    private static boolean isConvex(List<PointF> p,int i){
        int n=p.size();PointF a=p.get((i-1+n)%n),b=p.get(i),c=p.get((i+1)%n);
        return orient(a,b,c)>1e-5f;
    }

    private static PointF lineIntersection(PointF a,PointF b,PointF c,PointF d){
        float den=(a.x-b.x)*(c.y-d.y)-(a.y-b.y)*(c.x-d.x);
        if(Math.abs(den)<1e-6f)return null;
        float t=((a.x-c.x)*(c.y-d.y)-(a.y-c.y)*(c.x-d.x))/den;
        return new PointF(a.x+t*(b.x-a.x),a.y+t*(b.y-a.y));
    }

    private static boolean validProfile(List<PointF> p){
        return p!=null&&p.size()>=3&&signedArea(p)>1e-4f&&!selfIntersects(p);
    }

    private static boolean selfIntersects(List<PointF> p){
        int n=p.size();
        for(int i=0;i<n;i++){
            PointF a=p.get(i),b=p.get((i+1)%n);
            for(int j=i+1;j<n;j++){
                if(j==i||j==(i+1)%n||(i==0&&j==n-1))continue;
                PointF c=p.get(j),d=p.get((j+1)%n);
                if(segmentsIntersect(a,b,c,d))return true;
            }
        }
        return false;
    }

    private static boolean segmentsIntersect(PointF a,PointF b,PointF c,PointF d){
        float o1=orient(a,b,c),o2=orient(a,b,d),o3=orient(c,d,a),o4=orient(c,d,b);
        return o1*o2<-1e-6f&&o3*o4<-1e-6f;
    }

    private static float orient(PointF a,PointF b,PointF c){return(b.x-a.x)*(c.y-a.y)-(b.y-a.y)*(c.x-a.x);}
    private static float signedArea(List<PointF> p){float a=0;for(int i=0;i<p.size();i++){PointF q=p.get(i),r=p.get((i+1)%p.size());a+=q.x*r.y-r.x*q.y;}return a*0.5f;}
    private static float len(PointF a,PointF b){return(float)Math.hypot(b.x-a.x,b.y-a.y);}
    private static PointF moveToward(PointF from,PointF to,float d){float l=len(from,to);return l<1e-6f?new PointF(from.x,from.y):new PointF(from.x+(to.x-from.x)*d/l,from.y+(to.y-from.y)*d/l);}
    private static List<PointF> copy(List<PointF> p){List<PointF> o=new ArrayList<>();for(PointF q:p)o.add(new PointF(q.x,q.y));return o;}
    private static float clamp(float v,float a,float b){return Math.max(a,Math.min(b,v));}

    // ------------------------------------------------------------------
    // Reflection helpers
    // ------------------------------------------------------------------

    private Object selectedBody(){try{return selectedBodyField==null?null:selectedBodyField.get(this);}catch(Exception e){return null;}}
    private SolidCSG.Polygon selectedFace(){try{Object v=selectedFaceField==null?null:selectedFaceField.get(this);return v instanceof SolidCSG.Polygon?(SolidCSG.Polygon)v:null;}catch(Exception e){return null;}}
    private void setSelectedFace(Object face){try{if(selectedFaceField!=null)selectedFaceField.set(this,face);}catch(Exception ignored){}}

    private SolidCSG bodyCsg(Object body){
        try{if(body==null)return null;Field f=findField(body.getClass(),"csg");Object v=f==null?null:f.get(body);return v instanceof SolidCSG?(SolidCSG)v:null;}
        catch(Exception e){return null;}
    }
    private void setBodyCsg(Object body,SolidCSG csg){try{Field f=findField(body.getClass(),"csg");if(f!=null)f.set(body,csg);}catch(Exception ignored){}}
    private String bodyName(Object body){try{if(body==null)return"Body";Field f=findField(body.getClass(),"name");Object v=f==null?null:f.get(body);return v==null?"Body":String.valueOf(v);}catch(Exception e){return"Body";}}

    private PointF project(Geometry3D.Vec3 p){
        try{Object v=projectMethod==null?null:projectMethod.invoke(this,p);return v instanceof PointF?(PointF)v:new PointF();}
        catch(Exception e){return new PointF();}
    }

    private void ensure3D(){if(!is3DOverview())toggle3DOverview();}

    private static Field findField(Class<?> c,String name){Class<?> x=c;while(x!=null){try{Field f=x.getDeclaredField(name);f.setAccessible(true);return f;}catch(Exception e){x=x.getSuperclass();}}return null;}

    private static Geometry3D.Vec3 midpoint(Geometry3D.Vec3 a,Geometry3D.Vec3 b){return new Geometry3D.Vec3((a.x+b.x)*0.5f,(a.y+b.y)*0.5f,(a.z+b.z)*0.5f);}

    private static float distanceToSegment(float px,float py,float ax,float ay,float bx,float by){
        float dx=bx-ax,dy=by-ay,l2=dx*dx+dy*dy;
        if(l2<1e-8f)return(float)Math.hypot(px-ax,py-ay);
        float t=((px-ax)*dx+(py-ay)*dy)/l2;t=Math.max(0f,Math.min(1f,t));
        float x=ax+t*dx,y=ay+t*dy;return(float)Math.hypot(px-x,py-y);
    }

    private static String csgSignature(SolidCSG csg){
        if(csg==null)return"null";StringBuilder b=new StringBuilder().append(csg.polygons().size()).append('|');int c=0;
        for(SolidCSG.Polygon p:csg.polygons()){Geometry3D.Vec3 q=p.centroid();b.append(p.vertices.size()).append(':').append(Math.round(q.x*10)).append(',').append(Math.round(q.y*10)).append(',').append(Math.round(q.z*10)).append(';');if(++c>16)break;}return b.toString();
    }

    private static float parseLengthMm(String raw){
        String s=normalizeDigits(raw).trim().toLowerCase(Locale.US).replace(" ","");
        if(s.endsWith("mm"))return Float.parseFloat(s.substring(0,s.length()-2));
        if(s.endsWith("cm"))return Float.parseFloat(s.substring(0,s.length()-2))*10f;
        return Float.parseFloat(s);
    }
    private static String normalizeDigits(String s){if(s==null)return"";StringBuilder b=new StringBuilder();for(int i=0;i<s.length();i++){char c=s.charAt(i);if(c>='۰'&&c<='۹')b.append((char)('0'+c-'۰'));else if(c>='٠'&&c<='٩')b.append((char)('0'+c-'٠'));else b.append(c);}return b.toString();}
    private static String num(float v){String s=String.format(Locale.US,"%.2f",v);while(s.contains(".")&&(s.endsWith("0")||s.endsWith(".")))s=s.substring(0,s.length()-1);return s;}
    private static String dual(float mm){return num(mm)+" mm";}
    private void toast(String s){if(s!=null&&!s.trim().isEmpty())Toast.makeText(getContext(),s,Toast.LENGTH_SHORT).show();}

    @Override
    public void clearAll(){
        super.clearAll();
        directBaseByBody.clear();directOpsByBody.clear();
        selectedEdgeA=selectedEdgeB=null;selectedEdgeBody=null;edgePickMode=false;
    }
}

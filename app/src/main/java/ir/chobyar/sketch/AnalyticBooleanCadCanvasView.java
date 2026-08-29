package ir.chobyar.sketch;

import android.app.AlertDialog;
import android.content.Context;
import android.widget.Toast;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;

/**
 * Analytic Boolean provenance layer.
 *
 * The current clipping operation is still evaluated by SolidCSG, but Boolean
 * features no longer erase the mathematical identity of curved operands.
 * Every parametric Boolean output keeps references to its left/right source
 * bodies and operation.  Cylinder/Cone/Sphere masters are therefore carried
 * through the history tree and exposed as analytic boundary surfaces.
 *
 * This is an important B-Rep bridge: a cylindrical hole keeps an exact radius,
 * axis and source identity after Subtract even though its display/trimming mesh
 * is currently polygonal.  A future native exact Boolean kernel can consume the
 * same semantic tree instead of reconstructing design intent from triangles.
 */
public class AnalyticBooleanCadCanvasView extends AnalyticCadCanvasView {

    private static final class BooleanNode {
        final String operation;
        final Object leftBody;
        final Object rightBody;
        final Object outputBody;

        BooleanNode(String operation, Object leftBody, Object rightBody, Object outputBody) {
            this.operation = operation;
            this.leftBody = leftBody;
            this.rightBody = rightBody;
            this.outputBody = outputBody;
        }
    }

    private static final class CurvedSurface {
        final AnalyticSolidKernel.Primitive primitive;
        final String role;
        final String source;

        CurvedSurface(AnalyticSolidKernel.Primitive primitive, String role, String source) {
            this.primitive = primitive;
            this.role = role;
            this.source = source;
        }
    }

    private final IdentityHashMap<Object, BooleanNode> booleanByBody = new IdentityHashMap<>();

    private Field historyField;
    private Field analyticByBodyField;
    private Field bodiesField;
    private Field selectedBodyField;
    private Field selectedFaceField;
    private Method applyHistoryBooleanMethod;

    public AnalyticBooleanCadCanvasView(Context context) {
        super(context);
        initBooleanReflection();
        syncBooleanHistory();
    }

    private void initBooleanReflection() {
        try {
            historyField = field(ParametricHistorySolidCadCanvasView.class, "history");
            analyticByBodyField = field(AnalyticCadCanvasView.class, "analyticByBody");
            bodiesField = field(SolidCadCanvasView.class, "bodies");
            selectedBodyField = field(SolidCadCanvasView.class, "selectedBody");
            selectedFaceField = field(SolidCadCanvasView.class, "selectedFace");
            applyHistoryBooleanMethod = ParametricHistorySolidCadCanvasView.class.getDeclaredMethod(
                    "applyHistoryBoolean", String.class, Object.class, Object.class);
            applyHistoryBooleanMethod.setAccessible(true);
        } catch (Exception ignored) {}
    }

    private static Field field(Class<?> owner, String name) throws NoSuchFieldException {
        Field f = owner.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }

    // ------------------------------------------------------------------
    // Adaptive Solid entry
    // ------------------------------------------------------------------

    @Override
    public void showSolidManager() {
        syncBooleanHistory();
        Object body = selectedBody();
        BooleanNode node = booleanByBody.get(body);
        List<CurvedSurface> surfaces = body == null ? Collections.emptyList() : curvedSurfaces(body);
        String bodyLine = body == null ? "No body selected" : bodyName(body);
        if (node != null) bodyLine += " • " + friendlyOp(node.operation);
        if (!surfaces.isEmpty()) bodyLine += " • " + surfaces.size() + " Face text text";

        String[] items = {
                "▣ Solid / Primitivetext text / Toolstext text",
                "∪−∩ Boolean text / text Surface text",
                "∿ Analytic Boundary Inspector",
                "◎ text text text text text",
                "↻ text text History",
                is3DOverview() ? "□ text text Sketch 2D" : "◇ Show 3D"
        };

        new AlertDialog.Builder(getContext())
                .setTitle("Solid 3D • Analytic B-Rep")
                .setMessage(bodyLine
                        + " \n  \n text Boolean text, Geometry text text CSG text text text text text Cylinder/Cone/Sphere text History text text.")
                .setItems(items, (d,w) -> {
                    if (w == 0) AnalyticBooleanCadCanvasView.super.showSolidManager();
                    else if (w == 1) showAnalyticBooleanChooser();
                    else if (w == 2) showBoundaryInspector();
                    else if (w == 3) showCurvedSurfaceReport();
                    else if (w == 4) {
                        syncBooleanHistory();
                        toast("Analytic Boolean text History text text");
                    } else toast(toggle3DOverview());
                })
                .setNegativeButton("Close", null)
                .show();
    }

    private void showAnalyticBooleanChooser() {
        if (bodies().size() < 2) {
            toast("text Boolean text text Body text text");
            return;
        }
        String[] ops = {
                "∪ Union / text",
                "− Subtract / text text text Face text",
                "∩ Intersect / text"
        };
        new AlertDialog.Builder(getContext())
                .setTitle("Boolean text")
                .setMessage("text text Selection text. text Body text text text selected text text text text.")
                .setItems(ops, (d,w) -> choosePrimary(w == 0 ? "UNION" : w == 1 ? "SUBTRACT" : "INTERSECT"))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void choosePrimary(String operation) {
        Object selected = selectedBody();
        if (selected != null && bodies().contains(selected)) {
            chooseTool(operation, selected);
            return;
        }
        List<Object> bs = new ArrayList<>(bodies());
        String[] names = new String[bs.size()];
        for (int i=0;i<bs.size();i++) names[i] = bodyName(bs.get(i));
        new AlertDialog.Builder(getContext())
                .setTitle("Body text")
                .setItems(names, (d,w) -> chooseTool(operation, bs.get(w)))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void chooseTool(String operation, Object primary) {
        List<Object> options = new ArrayList<>();
        for (Object b : bodies()) if (b != primary) options.add(b);
        if (options.isEmpty()) {
            toast("Body text text text");
            return;
        }
        String[] names = new String[options.size()];
        for (int i=0;i<options.size();i++) names[i] = bodyName(options.get(i));
        new AlertDialog.Builder(getContext())
                .setTitle(friendlyOp(operation) + " — Body text")
                .setMessage("Body text: " + bodyName(primary)
                        + ("SUBTRACT".equals(operation)
                        ? " \n text Body text Cylinder/Cone/Sphere text, Surface text text text text text text."
                        : " \n text text text text text text text B-Rep text text text."))
                .setItems(names, (d,w) -> toast(applyAnalyticBoolean(operation, primary, options.get(w))))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private String applyAnalyticBoolean(String operation, Object left, Object right) {
        if (applyHistoryBooleanMethod == null) return "Boolean Parametric is not ready";
        try {
            setSelectedBody(left);
            String result = String.valueOf(applyHistoryBooleanMethod.invoke(this, operation, left, right));
            syncBooleanHistory();
            Object out = selectedBody();
            BooleanNode node = booleanByBody.get(out);
            clearFace();
            ensure3D();
            invalidate();
            if (node == null) return result;

            List<CurvedSurface> surfaces = curvedSurfaces(out);
            int cutters = 0;
            for (CurvedSurface s : surfaces) if (s.role.startsWith("CUT")) cutters++;
            String suffix = surfaces.isEmpty() ? "" : " • " + surfaces.size() + " Surface text text text";
            if (cutters > 0) suffix += " • " + cutters + " Face text text";
            return result + suffix;
        } catch (Exception e) {
            return "Boolean text Done text";
        }
    }

    // ------------------------------------------------------------------
    // History synchronization: works even if Boolean was created in an older menu
    // ------------------------------------------------------------------

    private void syncBooleanHistory() {
        List<Object> validOutputs = new ArrayList<>();
        for (Object feature : history()) {
            if (feature == null || !"BooleanFeature".equals(feature.getClass().getSimpleName())) continue;
            try {
                Field operationField = findField(feature.getClass(), "operation");
                Field leftField = findField(feature.getClass(), "leftBody");
                Field rightField = findField(feature.getClass(), "rightBody");
                Field outputField = findField(feature.getClass(), "outputBody");
                if (operationField == null || leftField == null || rightField == null || outputField == null) continue;
                String op = String.valueOf(operationField.get(feature));
                Object left = leftField.get(feature), right = rightField.get(feature), out = outputField.get(feature);
                if (out == null) continue;
                booleanByBody.put(out, new BooleanNode(op, left, right, out));
                validOutputs.add(out);
            } catch (Exception ignored) {}
        }
        for (Object body : new ArrayList<>(booleanByBody.keySet())) {
            if (!validOutputs.contains(body)) booleanByBody.remove(body);
        }
    }

    @Override
    public String rebuildHistory() {
        String result = super.rebuildHistory();
        syncBooleanHistory();
        return result + " • Analytic surfaces preserved";
    }

    @Override
    public String undoLastFeature() {
        String result = super.undoLastFeature();
        syncBooleanHistory();
        return result;
    }

    @Override
    public String selectedInfo() {
        syncBooleanHistory();
        String base = super.selectedInfo();
        Object body = selectedBody();
        if (body == null) return base;
        BooleanNode n = booleanByBody.get(body);
        if (n == null) return base;
        int curved = curvedSurfaces(body).size();
        return base + " | " + friendlyOp(n.operation) + (curved > 0 ? " | Analytic Surface " + curved : "");
    }

    @Override
    public void clearAll() {
        super.clearAll();
        booleanByBody.clear();
    }

    // ------------------------------------------------------------------
    // Exact curved-surface provenance
    // ------------------------------------------------------------------

    public void showBoundaryInspector() {
        syncBooleanHistory();
        Object body = selectedBody();
        if (body == null) {
            ensure3D();
            toast("Select a body first");
            return;
        }
        BooleanNode node = booleanByBody.get(body);
        if (node == null) {
            AnalyticSolidKernel.Primitive primitive = primitiveForBody(body);
            if (primitive != null) {
                new AlertDialog.Builder(getContext())
                        .setTitle("Analytic Boundary • " + bodyName(body))
                        .setMessage("Primitive text \n " + primitiveDetail(primitive)
                                + " \n  \n text Body text Boolean text text.")
                        .setPositiveButton("OK", null).show();
            } else {
                toast("text Body text Face text text text text");
            }
            return;
        }

        List<CurvedSurface> surfaces = curvedSurfaces(body);
        StringBuilder msg = new StringBuilder();
        msg.append("Operation: ").append(friendlyOp(node.operation));
        msg.append("\nLeft: ").append(bodyName(node.leftBody));
        msg.append("\nRight: ").append(bodyName(node.rightBody));
        msg.append("\nMesh Boolean changed geometry: ").append(booleanChangedGeometry(node) ? "YES" : "NO / negligible");
        msg.append("\n\nAnalytic curved boundaries: ").append(surfaces.size());
        for (int i=0;i<surfaces.size();i++) {
            CurvedSurface s = surfaces.get(i);
            msg.append("\n\n").append(i+1).append(". ").append(s.role)
                    .append(" • ").append(s.source).append("\n")
                    .append(primitiveDetail(s.primitive));
        }
        msg.append(" \n  \n text Radius/Axis text Model text text; Polygon text Trim/Preview text text Done text.");

        new AlertDialog.Builder(getContext())
                .setTitle("Analytic B-Rep • " + bodyName(body))
                .setMessage(msg.toString())
                .setPositiveButton("OK", null).show();
    }

    public void showCurvedSurfaceReport() {
        syncBooleanHistory();
        Object body = selectedBody();
        if (body == null) {
            ensure3D();
            toast("Select a body first");
            return;
        }
        List<CurvedSurface> surfaces = curvedSurfaces(body);
        if (surfaces.isEmpty()) {
            toast("Face Cylinder/Cone/Sphere text text text Body not registered");
            return;
        }
        StringBuilder msg = new StringBuilder();
        int holes = 0;
        for (CurvedSurface s : surfaces) {
            if (s.role.startsWith("CUT") && s.primitive instanceof AnalyticSolidKernel.Cylinder) holes++;
        }
        if (holes > 0) msg.append("text/text text text: ").append(holes).append("\n\n");
        for (int i=0;i<surfaces.size();i++) {
            CurvedSurface s = surfaces.get(i);
            msg.append(i+1).append(") ").append(s.role).append(" — ").append(typeName(s.primitive)).append("\n");
            msg.append(compactDimensions(s.primitive)).append("\n");
            msg.append("Source: ").append(s.source).append("\n\n");
        }
        new AlertDialog.Builder(getContext())
                .setTitle("Curved Surface Report")
                .setMessage(msg.toString().trim())
                .setPositiveButton("OK", null).show();
    }

    private List<CurvedSurface> curvedSurfaces(Object body) {
        List<CurvedSurface> out = new ArrayList<>();
        collectSurfaces(body, "OUTER", out, new IdentityHashMap<>());
        return out;
    }

    private void collectSurfaces(Object body, String inheritedRole, List<CurvedSurface> out,
                                 IdentityHashMap<Object, Boolean> visiting) {
        if (body == null || visiting.containsKey(body)) return;
        visiting.put(body, Boolean.TRUE);
        BooleanNode node = booleanByBody.get(body);
        if (node == null) {
            AnalyticSolidKernel.Primitive p = primitiveForBody(body);
            if (p != null) out.add(new CurvedSurface(p, inheritedRole, bodyName(body)));
            visiting.remove(body);
            return;
        }

        if ("SUBTRACT".equals(node.operation)) {
            collectSurfaces(node.leftBody, inheritedRole, out, visiting);
            String rightRole = "OUTER".equals(inheritedRole) ? "CUT" : inheritedRole + "/CUT";
            collectSurfaces(node.rightBody, rightRole, out, visiting);
        } else if ("UNION".equals(node.operation)) {
            String role = "OUTER".equals(inheritedRole) ? "MERGED" : inheritedRole + "/MERGED";
            collectSurfaces(node.leftBody, role, out, visiting);
            collectSurfaces(node.rightBody, role, out, visiting);
        } else {
            String role = "OUTER".equals(inheritedRole) ? "TRIM" : inheritedRole + "/TRIM";
            collectSurfaces(node.leftBody, role, out, visiting);
            collectSurfaces(node.rightBody, role, out, visiting);
        }
        visiting.remove(body);
    }

    private AnalyticSolidKernel.Primitive primitiveForBody(Object body) {
        if (body == null) return null;
        AnalyticSolidKernel.Primitive cached = analyticMap().get(body);
        AnalyticSolidKernel.Primitive recognized = AnalyticSolidKernel.recognize(bodyCsg(body));
        if (recognized != null) {
            analyticMap().put(body, recognized);
            return recognized;
        }
        return cached;
    }

    /**
     * The current CSG result is used to decide whether a Boolean materially
     * changed volume. This avoids calling a disjoint cylinder an active hole.
     */
    private boolean booleanChangedGeometry(BooleanNode node) {
        SolidCSG left = bodyCsg(node.leftBody), out = bodyCsg(node.outputBody);
        if (left == null || out == null) return false;
        double a = meshVolume(left), b = meshVolume(out);
        double tol = Math.max(0.01, Math.max(a,b) * 1e-5);
        return Math.abs(a-b) > tol;
    }

    private static double meshVolume(SolidCSG csg) {
        if (csg == null) return 0.0;
        double sum = 0.0;
        for (SolidCSG.Polygon p : csg.polygons()) {
            if (p.vertices.size() < 3) continue;
            Geometry3D.Vec3 a = p.vertices.get(0).pos;
            for (int i=1;i<p.vertices.size()-1;i++) {
                Geometry3D.Vec3 b = p.vertices.get(i).pos;
                Geometry3D.Vec3 c = p.vertices.get(i+1).pos;
                sum += a.dot(b.cross(c)) / 6.0;
            }
        }
        return Math.abs(sum);
    }

    private static String primitiveDetail(AnalyticSolidKernel.Primitive p) {
        if (p instanceof AnalyticSolidKernel.Cylinder) {
            AnalyticSolidKernel.Cylinder c = (AnalyticSolidKernel.Cylinder)p;
            return "Cylinder Surface"
                    + "\nDiameter: " + dual(c.radiusMm*2f)
                    + "\nRadius: " + dual(c.radiusMm)
                    + "\nHeight master: " + dual(c.heightMm)
                    + "\nAxis: " + vec(c.axis);
        }
        if (p instanceof AnalyticSolidKernel.Cone) {
            AnalyticSolidKernel.Cone c = (AnalyticSolidKernel.Cone)p;
            return "Cone Surface"
                    + "\nBase Ø: " + dual(c.baseRadiusMm*2f)
                    + "\nTop Ø: " + dual(c.topRadiusMm*2f)
                    + "\nHeight master: " + dual(c.heightMm)
                    + "\nAxis: " + vec(c.axis);
        }
        AnalyticSolidKernel.Sphere s = (AnalyticSolidKernel.Sphere)p;
        return "Sphere Surface"
                + "\nDiameter: " + dual(s.radiusMm*2f)
                + "\nRadius: " + dual(s.radiusMm)
                + "\nCenter: " + vec(s.center);
    }

    private static String compactDimensions(AnalyticSolidKernel.Primitive p) {
        if (p instanceof AnalyticSolidKernel.Cylinder) {
            AnalyticSolidKernel.Cylinder c=(AnalyticSolidKernel.Cylinder)p;
            return "Ø " + dual(c.radiusMm*2f) + " • H " + dual(c.heightMm);
        }
        if (p instanceof AnalyticSolidKernel.Cone) {
            AnalyticSolidKernel.Cone c=(AnalyticSolidKernel.Cone)p;
            return "Ø1 " + dual(c.baseRadiusMm*2f) + " • Ø2 " + dual(c.topRadiusMm*2f) + " • H " + dual(c.heightMm);
        }
        AnalyticSolidKernel.Sphere s=(AnalyticSolidKernel.Sphere)p;
        return "Ø " + dual(s.radiusMm*2f);
    }

    private static String typeName(AnalyticSolidKernel.Primitive p) {
        if (p instanceof AnalyticSolidKernel.Cylinder) return "Cylinder";
        if (p instanceof AnalyticSolidKernel.Cone) return "Cone";
        return "Sphere";
    }

    private static String friendlyOp(String op) {
        return "UNION".equals(op) ? "Union" : "SUBTRACT".equals(op) ? "Subtract" : "Intersect";
    }

    private static String vec(Geometry3D.Vec3 v) {
        if (v == null) return "—";
        return "(" + num(v.x) + ", " + num(v.y) + ", " + num(v.z) + ")";
    }

    // ------------------------------------------------------------------
    // Reflection helpers
    // ------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private List<Object> history() {
        try {
            Object v = historyField == null ? null : historyField.get(this);
            return v instanceof List ? (List<Object>)v : Collections.emptyList();
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    @SuppressWarnings("unchecked")
    private IdentityHashMap<Object, AnalyticSolidKernel.Primitive> analyticMap() {
        try {
            Object v = analyticByBodyField == null ? null : analyticByBodyField.get(this);
            return v instanceof IdentityHashMap
                    ? (IdentityHashMap<Object, AnalyticSolidKernel.Primitive>)v
                    : new IdentityHashMap<>();
        } catch (Exception e) {
            return new IdentityHashMap<>();
        }
    }

    @SuppressWarnings("unchecked")
    private List<Object> bodies() {
        try {
            Object v = bodiesField == null ? null : bodiesField.get(this);
            return v instanceof List ? (List<Object>)v : Collections.emptyList();
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    private Object selectedBody() {
        try { return selectedBodyField == null ? null : selectedBodyField.get(this); }
        catch (Exception e) { return null; }
    }

    private void setSelectedBody(Object body) {
        try { if (selectedBodyField != null) selectedBodyField.set(this, body); }
        catch (Exception ignored) {}
    }

    private SolidCSG bodyCsg(Object body) {
        if (body == null) return null;
        try {
            Field f = findField(body.getClass(), "csg");
            Object v = f == null ? null : f.get(body);
            return v instanceof SolidCSG ? (SolidCSG)v : null;
        } catch (Exception e) { return null; }
    }

    private String bodyName(Object body) {
        if (body == null) return "—";
        try {
            Field f = findField(body.getClass(), "name");
            Object v = f == null ? null : f.get(body);
            return v == null ? "Body" : String.valueOf(v);
        } catch (Exception e) { return "Body"; }
    }

    private static Field findField(Class<?> c, String name) {
        Class<?> x = c;
        while (x != null) {
            try {
                Field f = x.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (Exception e) { x = x.getSuperclass(); }
        }
        return null;
    }

    private void clearFace() {
        try { if (selectedFaceField != null) selectedFaceField.set(this, null); }
        catch (Exception ignored) {}
    }

    private void ensure3D() {
        if (!is3DOverview()) toggle3DOverview();
    }

    private static String num(float v) {
        String s = String.format(Locale.US, "%.4f", v);
        while (s.contains(".") && (s.endsWith("0") || s.endsWith("."))) s = s.substring(0, s.length()-1);
        return s;
    }

    private static String dual(float mm) {
        return num(mm) + " mm";
    }

    private void toast(String s) {
        if (s != null && !s.trim().isEmpty()) Toast.makeText(getContext(), s, Toast.LENGTH_SHORT).show();
    }
}

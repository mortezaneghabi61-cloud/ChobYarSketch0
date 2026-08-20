#!/usr/bin/env python3
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[2]
CAD = ROOT / "app/src/main/java/ir/chobyar/sketch/CadCanvasView.java"
SMART = ROOT / "app/src/main/java/ir/chobyar/sketch/SmartCadCanvasView.java"
CHOB = ROOT / "app/src/main/java/ir/chobyar/sketch/ChobYarShaprCanvasView.java"


def replace_once(text, old, new, label):
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: expected 1 occurrence, found {count}")
    return text.replace(old, new, 1)


def replace_all_checked(text, old, new, minimum, label):
    count = text.count(old)
    if count < minimum:
        raise RuntimeError(f"{label}: expected at least {minimum} occurrences, found {count}")
    return text.replace(old, new)


def patch_cad():
    text = CAD.read_text(encoding="utf-8")
    changes = [
        ("    private final List<Entity> entities = new ArrayList<>();", "    protected final List<Entity> entities = new ArrayList<>();", "entities access"),
        ("    private Entity selected;", "    protected Entity selected;", "selected access"),
        ("    private float viewScale = 1f;", "    protected float viewScale = 1f;", "viewScale access"),
        ("    private float offsetX = 120f;", "    protected float offsetX = 120f;", "offsetX access"),
        ("    private float offsetY = 160f;", "    protected float offsetY = 160f;", "offsetY access"),
        ("    private float startX, startY, endX, endY;", "    protected float startX, startY, endX, endY;", "gesture coordinates access"),
        ("    private boolean drawing = false;", "    protected boolean drawing = false;", "drawing access"),
        ("    private Entity findHit(float x,float y){", "    protected Entity findHit(float x,float y){", "findHit access"),
        ("    private void saveUndo(){", "    protected void saveUndo(){", "saveUndo access"),
        ("    private boolean isVisible(Entity e){", "    protected boolean isVisible(Entity e){", "isVisible access"),
        ("    private float screenToWorldX(float sx){", "    protected float screenToWorldX(float sx){", "screenToWorldX access"),
        ("    private float screenToWorldY(float sy){", "    protected float screenToWorldY(float sy){", "screenToWorldY access"),
        ("    private interface Entity{", "    protected interface Entity{", "Entity access"),
        ("    private static class LineEntity extends BaseEntity{", "    protected static class LineEntity extends BaseEntity{", "LineEntity access"),
        ("    private static class SnapPoint{", "    protected static class SnapPoint{", "SnapPoint access"),
        ("    private static class ControlPoint{", "    protected static class ControlPoint{", "ControlPoint access"),
    ]
    for old, new, label in changes:
        text = replace_once(text, old, new, label)
    CAD.write_text(text, encoding="utf-8")


def patch_smart():
    text = SMART.read_text(encoding="utf-8")
    text = text.replace("import java.lang.reflect.Field;\n", "")
    text = text.replace("import java.lang.reflect.Method;\n", "")
    text = replace_once(text,
        " * CadCanvasView intentionally keeps its geometry entities private. This class\n * uses reflection only at the interaction boundary so the existing geometry\n * and DXF code can stay untouched while the UI evolves quickly.\n",
        " * CadCanvasView exposes a protected sketch-core contract to subclasses. This\n * layer uses that contract directly so selection and viewport behavior cannot\n * silently fail because a private field or method was renamed.\n",
        "Smart class comment")
    text = replace_once(text,
        "    private final List<Object> selectedObjects = new ArrayList<>();",
        "    protected final List<Object> selectedObjects = new ArrayList<>();",
        "selectedObjects access")

    reflection_fields = '''    private Field entitiesField;\n    private Field selectedField;\n    private Field viewScaleField;\n    private Field offsetXField;\n    private Field offsetYField;\n    private Method findHitMethod;\n    private Method saveUndoMethod;\n    private Method isVisibleMethod;\n    private boolean reflectionReady = false;\n\n'''
    text = replace_once(text, reflection_fields, "", "Smart reflection fields")
    text = replace_once(text, "        initReflection();\n", "", "Smart initReflection call")

    pattern = re.compile(r"    private void initReflection\(\) \{.*?\n    \}\n\n(?=    public void setStatusListener)", re.S)
    text, n = pattern.subn("", text, count=1)
    if n != 1:
        raise RuntimeError(f"Smart initReflection method: expected 1 occurrence, found {n}")

    text = text.replace("        if (!reflectionReady) return;\n", "")
    text = text.replace("        if (!reflectionReady) return super.onTouchEvent(event);\n\n", "")
    text = text.replace("saveUndoReflective();", "saveUndo();")

    text = text.replace('Object c = call(e, "copy");', 'Object c = entityCopy(e);')
    text = text.replace('Object c = call(e, "offsetCopy", new Class<?>[]{float.class}, distance);', 'Object c = entityOffsetCopy(e, distance);')
    text = text.replace('''call(e, "rotate",\n                new Class<?>[]{float.class, float.class, float.class}, c.x, c.y, deg);''',
                        'entityRotate(e, c.x, c.y, deg);')
    text = text.replace('''call(e, "scale",\n                new Class<?>[]{float.class, float.class, float.class}, c.x, c.y, factor);''',
                        'entityScale(e, c.x, c.y, factor);')
    text = replace_once(text,
        '''        String method = acrossXAxis ? "mirrorHorizontal" : "mirrorVertical";\n        for (Object e : selectedObjects) call(e, method, new Class<?>[]{float.class}, axisValue);''',
        '''        for (Object e : selectedObjects) {\n            if (acrossXAxis) entityMirrorHorizontal(e, axisValue);\n            else entityMirrorVertical(e, axisValue);\n        }''',
        "Smart mirror direct dispatch")
    text = text.replace('call(e, "setLayer", new Class<?>[]{String.class}, name.trim());', 'entitySetLayer(e, name.trim());')
    text = text.replace('call(e, "setColor", new Class<?>[]{int.class}, color);', 'entitySetColor(e, color);')

    old_anchor = '''            Object snaps = call(e, "snapPoints");\n            if (snaps instanceof List) {\n                for (Object sp : (List<?>) snaps) {\n                    PointF p = pointFromObject(sp);\n                    if (p == null) continue;\n                    float d = distanceWorld(wx, wy, p.x, p.y);\n                    if (d < bestD) { bestD = d; best = p; }\n                }\n            }'''
    new_anchor = '''            for (PointF p : entitySnapPoints(e)) {\n                float d = distanceWorld(wx, wy, p.x, p.y);\n                if (d < bestD) { bestD = d; best = p; }\n            }'''
    text = replace_once(text, old_anchor, new_anchor, "Smart selected anchor snap points")

    old_external = '''            Object snaps = call(e, "snapPoints");\n            if (snaps instanceof List) {\n                for (Object sp : (List<?>) snaps) {\n                    PointF p = pointFromObject(sp);\n                    if (p == null) continue;\n                    float d = distanceWorld(wx, wy, p.x, p.y);\n                    if (d <= radius && d < bestD) {\n                        best = p;\n                        bestD = d;\n                    }\n                }\n            }\n            Object nearObj = call(e, "nearestPoint", new Class<?>[]{float.class, float.class}, wx, wy);\n            if (nearObj instanceof PointF) {\n                PointF p = (PointF) nearObj;'''
    new_external = '''            for (PointF p : entitySnapPoints(e)) {\n                float d = distanceWorld(wx, wy, p.x, p.y);\n                if (d <= radius && d < bestD) {\n                    best = p;\n                    bestD = d;\n                }\n            }\n            PointF p = entityNearestPoint(e, wx, wy);\n            if (p != null) {'''
    text = replace_once(text, old_external, new_external, "Smart external snap points")

    start = text.find('    @SuppressWarnings("unchecked")\n    private List<Object> entities() {')
    end = text.find('    private RectF worldRectToScreen(RectF r) {', start)
    if start < 0 or end < 0:
        raise RuntimeError("Smart helper section markers not found")
    helpers = '''    @SuppressWarnings({"unchecked", "rawtypes"})\n    private List<Object> entities() {\n        return (List<Object>) (List<?>) super.entities;\n    }\n\n    /** Snapshot for higher interaction layers; the owning list stays private to Smart. */\n    protected final List<Object> smartSelectionSnapshot() {\n        return new ArrayList<>(selectedObjects);\n    }\n\n    private Object baseSelected() { return selected; }\n\n    private void setBaseSelected(Object value) {\n        selected = value instanceof Entity ? (Entity) value : null;\n    }\n\n    private boolean entityVisible(Object e) {\n        return e instanceof Entity && isVisible((Entity) e);\n    }\n\n    private boolean entityConstruction(Object e) {\n        return e instanceof Entity && ((Entity) e).isConstruction();\n    }\n\n    private RectF entityBounds(Object e) {\n        return e instanceof Entity ? new RectF(((Entity) e).bounds()) : null;\n    }\n\n    private PointF entityCenter(Object e) {\n        if (!(e instanceof Entity)) return null;\n        PointF p = ((Entity) e).center();\n        return p == null ? null : new PointF(p.x, p.y);\n    }\n\n    private void translate(Object e, float dx, float dy) {\n        if (e instanceof Entity) ((Entity) e).translate(dx, dy);\n    }\n\n    private Object entityCopy(Object e) {\n        return e instanceof Entity ? ((Entity) e).copy() : null;\n    }\n\n    private Object entityOffsetCopy(Object e, float distance) {\n        return e instanceof Entity ? ((Entity) e).offsetCopy(distance) : null;\n    }\n\n    private void entityRotate(Object e, float cx, float cy, float deg) {\n        if (e instanceof Entity) ((Entity) e).rotate(cx, cy, deg);\n    }\n\n    private void entityScale(Object e, float cx, float cy, float factor) {\n        if (e instanceof Entity) ((Entity) e).scale(cx, cy, factor);\n    }\n\n    private void entityMirrorHorizontal(Object e, float axis) {\n        if (e instanceof Entity) ((Entity) e).mirrorHorizontal(axis);\n    }\n\n    private void entityMirrorVertical(Object e, float axis) {\n        if (e instanceof Entity) ((Entity) e).mirrorVertical(axis);\n    }\n\n    private void entitySetLayer(Object e, String layer) {\n        if (e instanceof Entity) ((Entity) e).setLayer(layer);\n    }\n\n    private void entitySetColor(Object e, int color) {\n        if (e instanceof Entity) ((Entity) e).setColor(color);\n    }\n\n    private List<PointF> entitySnapPoints(Object e) {\n        List<PointF> out = new ArrayList<>();\n        if (!(e instanceof Entity)) return out;\n        for (SnapPoint sp : ((Entity) e).snapPoints()) out.add(new PointF(sp.x, sp.y));\n        return out;\n    }\n\n    private PointF entityNearestPoint(Object e, float wx, float wy) {\n        if (!(e instanceof Entity)) return null;\n        PointF p = ((Entity) e).nearestPoint(wx, wy);\n        return p == null ? null : new PointF(p.x, p.y);\n    }\n\n    private float viewScale() { return super.viewScale; }\n    private float offsetX() { return super.offsetX; }\n    private float offsetY() { return super.offsetY; }\n\n    private float screenToWorldXLocal(float sx) { return screenToWorldX(sx); }\n    private float screenToWorldYLocal(float sy) { return screenToWorldY(sy); }\n\n    private PointF worldToScreen(float wx, float wy) {\n        float s = PX_PER_MM * viewScale();\n        return new PointF(offsetX() + wx * s, offsetY() + wy * s);\n    }\n\n'''
    text = text[:start] + helpers + text[end:]

    SMART.write_text(text, encoding="utf-8")


def patch_chob():
    text = CHOB.read_text(encoding="utf-8")
    text = text.replace("import java.lang.reflect.Field;\n", "")
    text = text.replace("import java.lang.reflect.Method;\n", "")

    fields = '''    private Field selectedField;\n    private Field entitiesField;\n    private Field selectedObjectsField;\n    private Field viewScaleField;\n    private Field offsetXField;\n    private Field offsetYField;\n    private Field startXField;\n    private Field startYField;\n    private Method saveUndoMethod;\n\n'''
    text = replace_once(text, fields, "", "Chob reflection fields")
    text = replace_once(text, "        initReflection();\n", "", "Chob initReflection call")

    pattern = re.compile(r"    private void initReflection\(\) \{.*?\n    \}\n\n    private static Field field\(Class<\?> owner, String name\) throws NoSuchFieldException \{.*?\n    \}\n\n", re.S)
    text, n = pattern.subn("", text, count=1)
    if n != 1:
        raise RuntimeError(f"Chob init reflection block: expected 1 occurrence, found {n}")

    text = replace_once(text,
        '''            if (startXField == null || startYField == null) return null;\n            float sx = startXField.getFloat(this);\n            float sy = startYField.getFloat(this);''',
        '''            float sx = startX;\n            float sy = startY;''',
        "Chob line gesture coordinates")

    start = text.find('    // Reflection / geometry helpers\n')
    end = text.find('    private static boolean containsIdentity(List<Object> list, Object value) {', start)
    if start < 0 or end < 0:
        raise RuntimeError("Chob helper section markers not found")

    helpers = '''    // Direct sketch-core helpers ------------------------------------------------\n\n    @SuppressWarnings({"unchecked", "rawtypes"})\n    private List<Object> entities() {\n        return (List<Object>) (List<?>) super.entities;\n    }\n\n    private List<Object> smartSelection() {\n        return smartSelectionSnapshot();\n    }\n\n    private List<Object> selectionObjects() {\n        List<Object> smart = smartSelection();\n        if (!smart.isEmpty()) return new ArrayList<>(smart);\n        Object base = selectedObject();\n        List<Object> one = new ArrayList<>();\n        if (base != null) one.add(base);\n        return one;\n    }\n\n    private List<Object> selectedLines() {\n        List<Object> out = new ArrayList<>();\n        for (Object e : selectionObjects()) if (isLine(e)) out.add(e);\n        return out;\n    }\n\n    private Object selectedObject() { return selected; }\n\n    private boolean isLine(Object e) { return e instanceof LineEntity; }\n\n    private float viewScale() { return super.viewScale; }\n    private float offsetX() { return super.offsetX; }\n    private float offsetY() { return super.offsetY; }\n\n    private PointF worldToScreen(float x, float y) {\n        float s = PX_PER_MM * viewScale();\n        return new PointF(offsetX() + x * s, offsetY() + y * s);\n    }\n\n    private PointF endpoint(Object line, int index) {\n        if (!(line instanceof LineEntity)) return null;\n        LineEntity l = (LineEntity) line;\n        return index == 0 ? new PointF(l.x1, l.y1) : new PointF(l.x2, l.y2);\n    }\n\n    private PointF lineMidpoint(Object line) {\n        PointF a = endpoint(line, 0), b = endpoint(line, 1);\n        return a == null || b == null ? null : new PointF((a.x + b.x) / 2f, (a.y + b.y) / 2f);\n    }\n\n    private float lineAngle(Object line) {\n        PointF a = endpoint(line, 0), b = endpoint(line, 1);\n        return a == null || b == null ? 0f : angleDeg(b.x - a.x, b.y - a.y);\n    }\n\n    private void setLineAngleAroundCenter(Object line, float angle) {\n        PointF a = endpoint(line, 0), b = endpoint(line, 1);\n        if (a == null || b == null) return;\n        float cx = (a.x + b.x) / 2f, cy = (a.y + b.y) / 2f;\n        float half = distWorld(a.x, a.y, b.x, b.y) / 2f;\n        double r = Math.toRadians(angle);\n        float vx = half * (float) Math.cos(r), vy = half * (float) Math.sin(r);\n        setLine(line, cx - vx, cy - vy, cx + vx, cy + vy);\n    }\n\n    private void setLine(Object line, float x1, float y1, float x2, float y2) {\n        if (!(line instanceof LineEntity)) return;\n        LineEntity l = (LineEntity) line;\n        l.x1 = x1; l.y1 = y1; l.x2 = x2; l.y2 = y2;\n    }\n\n    private void setEndpoint(Object line, int index, float x, float y) {\n        if (!(line instanceof LineEntity)) return;\n        LineEntity l = (LineEntity) line;\n        if (index == 0) { l.x1 = x; l.y1 = y; }\n        else { l.x2 = x; l.y2 = y; }\n    }\n\n    private PointF selectionCenter(List<Object> selection) {\n        RectF all = null;\n        for (Object e : selection) {\n            if (!(e instanceof Entity)) continue;\n            RectF r = new RectF(((Entity) e).bounds());\n            if (all == null) all = r; else all.union(r);\n        }\n        return all == null ? null : new PointF(all.centerX(), all.centerY());\n    }\n\n    private void translate(Object e, float dx, float dy) {\n        if (e instanceof Entity) ((Entity) e).translate(dx, dy);\n    }\n\n    private void rotate(Object e, float cx, float cy, float deg) {\n        if (e instanceof Entity) ((Entity) e).rotate(cx, cy, deg);\n    }\n\n    private float getFloat(Object o, String name) throws Exception {\n        if (!(o instanceof LineEntity)) throw new NoSuchFieldException(name);\n        LineEntity l = (LineEntity) o;\n        if ("x1".equals(name)) return l.x1;\n        if ("y1".equals(name)) return l.y1;\n        if ("x2".equals(name)) return l.x2;\n        if ("y2".equals(name)) return l.y2;\n        throw new NoSuchFieldException(name);\n    }\n\n    private float safeGet(Object o, String name) {\n        try { return getFloat(o, name); } catch (Exception e) { return 0f; }\n    }\n\n    private void setFloat(Object o, String name, float v) {\n        if (!(o instanceof LineEntity)) return;\n        LineEntity l = (LineEntity) o;\n        if ("x1".equals(name)) l.x1 = v;\n        else if ("y1".equals(name)) l.y1 = v;\n        else if ("x2".equals(name)) l.x2 = v;\n        else if ("y2".equals(name)) l.y2 = v;\n    }\n\n'''
    text = text[:start] + helpers + text[end:]
    CHOB.write_text(text, encoding="utf-8")


def validate():
    smart = SMART.read_text(encoding="utf-8")
    chob = CHOB.read_text(encoding="utf-8")
    cad = CAD.read_text(encoding="utf-8")
    forbidden = {
        "SmartCadCanvasView": ["java.lang.reflect", "reflectionReady", "getDeclaredField", "getDeclaredMethod"],
        "ChobYarShaprCanvasView": ["java.lang.reflect", "getDeclaredField", "getDeclaredMethod", "setAccessible(true)"],
    }
    for name, needles in forbidden.items():
        src = smart if name.startswith("Smart") else chob
        for needle in needles:
            if needle in src:
                raise RuntimeError(f"{name}: reflection residue remains: {needle}")
    for needle in ["protected final List<Entity> entities", "protected Entity selected", "protected void saveUndo()", "protected Entity findHit"]:
        if needle not in cad:
            raise RuntimeError(f"CadCanvasView contract missing: {needle}")
    print("Sketch core refactor applied and statically validated")


def main():
    patch_cad()
    patch_smart()
    patch_chob()
    validate()


if __name__ == "__main__":
    main()

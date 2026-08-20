#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SRC = ROOT / "app/src/main/java/ir/chobyar/sketch"
CAD = SRC / "CadCanvasView.java"
SMART = SRC / "SmartCadCanvasView.java"
CHOB = SRC / "ChobYarShaprCanvasView.java"


def replace_once(text, old, new, label):
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: expected 1 occurrence, found {count}")
    return text.replace(old, new, 1)


def patch_cad():
    text = CAD.read_text(encoding="utf-8")
    # Keep legacy implementation methods private so older subclasses that have
    # same-named private helpers do not become illegal weak-access overrides.
    text = replace_once(text, "    protected Entity findHit(float x,float y){", "    private Entity findHit(float x,float y){", "findHit visibility")
    text = replace_once(text, "    protected void saveUndo(){", "    private void saveUndo(){", "saveUndo visibility")
    text = replace_once(text, "    protected boolean isVisible(Entity e){", "    private boolean isVisible(Entity e){", "isVisible visibility")
    text = replace_once(text, "    protected float screenToWorldX(float sx){", "    private float screenToWorldX(float sx){", "screenToWorldX visibility")
    text = replace_once(text, "    protected float screenToWorldY(float sy){", "    private float screenToWorldY(float sy){", "screenToWorldY visibility")

    anchor = "    private float screenToWorldY(float sy){return(sy-offsetY)/(PX_PER_MM*viewScale);}\n"
    contract = anchor + '''\n    // Stable subclass-facing Sketch core contract. Unique names intentionally\n    // avoid collisions with legacy private helpers in intermediate subclasses.\n    protected final Entity coreFindHit(float x, float y) { return findHit(x, y); }\n    protected final void coreSaveUndo() { saveUndo(); }\n    protected final boolean coreIsVisible(Entity e) { return isVisible(e); }\n    protected final float coreScreenToWorldX(float sx) { return screenToWorldX(sx); }\n    protected final float coreScreenToWorldY(float sy) { return screenToWorldY(sy); }\n'''
    text = replace_once(text, anchor, contract, "Sketch core contract insertion")
    CAD.write_text(text, encoding="utf-8")


def patch_smart():
    text = SMART.read_text(encoding="utf-8")
    # Smart no longer reflects into CadCanvasView; use the collision-safe core API.
    text = text.replace("Object hit = findHit(wx, wy);", "Object hit = coreFindHit(wx, wy);")
    text = text.replace("saveUndo();", "coreSaveUndo();")
    text = text.replace("return e instanceof Entity && isVisible((Entity) e);", "return e instanceof Entity && coreIsVisible((Entity) e);")
    text = text.replace("return screenToWorldX(sx);", "return coreScreenToWorldX(sx);")
    text = text.replace("return screenToWorldY(sy);", "return coreScreenToWorldY(sy);")
    SMART.write_text(text, encoding="utf-8")


def patch_chob():
    text = CHOB.read_text(encoding="utf-8")
    text = text.replace("saveUndo();", "coreSaveUndo();")
    # Line inference/gizmo math should use the stable viewport conversion API.
    text = text.replace("screenToWorldX(source.getX())", "coreScreenToWorldX(source.getX())")
    text = text.replace("screenToWorldY(source.getY())", "coreScreenToWorldY(source.getY())")
    text = text.replace("screenToWorldX(event.getX())", "coreScreenToWorldX(event.getX())")
    text = text.replace("screenToWorldY(event.getY())", "coreScreenToWorldY(event.getY())")
    CHOB.write_text(text, encoding="utf-8")


def validate():
    cad = CAD.read_text(encoding="utf-8")
    smart = SMART.read_text(encoding="utf-8")
    chob = CHOB.read_text(encoding="utf-8")
    for token in [
        "protected final List<Entity> entities",
        "protected Entity selected",
        "protected float viewScale",
        "protected float offsetX",
        "protected float offsetY",
        "protected final Entity coreFindHit",
        "protected final void coreSaveUndo",
        "protected final boolean coreIsVisible",
        "protected final float coreScreenToWorldX",
        "protected final float coreScreenToWorldY",
    ]:
        if token not in cad:
            raise RuntimeError(f"missing core contract token: {token}")
    for forbidden in [
        "protected Entity findHit(float x,float y)",
        "protected void saveUndo()",
        "protected boolean isVisible(Entity e)",
        "protected float screenToWorldX(float sx)",
        "protected float screenToWorldY(float sy)",
    ]:
        if forbidden in cad:
            raise RuntimeError(f"legacy method still widened: {forbidden}")
    for name, src in [("SmartCadCanvasView", smart), ("ChobYarShaprCanvasView", chob)]:
        for token in ("java.lang.reflect.Field", "java.lang.reflect.Method", "getDeclaredField", "getDeclaredMethod"):
            if token in src:
                raise RuntimeError(f"{name} reflection residue: {token}")
    if "coreFindHit(wx, wy)" not in smart:
        raise RuntimeError("Smart selection is not routed through coreFindHit")
    if "coreSaveUndo();" not in smart or "coreSaveUndo();" not in chob:
        raise RuntimeError("Undo is not routed through coreSaveUndo")
    print("Collision-safe Sketch core contract applied and validated")


def main():
    patch_cad()
    patch_smart()
    patch_chob()
    validate()


if __name__ == "__main__":
    main()

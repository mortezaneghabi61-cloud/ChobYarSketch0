#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SRC = ROOT / "app/src/main/java/ir/chobyar/sketch"
CAD = SRC / "CadCanvasView.java"
SMART = SRC / "SmartCadCanvasView.java"
CHOB = SRC / "ChobYarShaprCanvasView.java"


def patch_remaining_calls():
    smart = SMART.read_text(encoding="utf-8")
    chob = CHOB.read_text(encoding="utf-8")

    smart = smart.replace(
        "pendingTapHit = findHit(wx, wy);",
        "pendingTapHit = coreFindHit(wx, wy);"
    )
    chob = chob.replace(
        "float wx = screenToWorldX(x), wy = screenToWorldY(y);",
        "float wx = coreScreenToWorldX(x), wy = coreScreenToWorldY(y);"
    )

    SMART.write_text(smart, encoding="utf-8")
    CHOB.write_text(chob, encoding="utf-8")


def validate():
    cad = CAD.read_text(encoding="utf-8")
    smart = SMART.read_text(encoding="utf-8")
    chob = CHOB.read_text(encoding="utf-8")

    required = [
        "protected final Entity coreFindHit",
        "protected final void coreSaveUndo",
        "protected final boolean coreIsVisible",
        "protected final float coreScreenToWorldX",
        "protected final float coreScreenToWorldY",
    ]
    for token in required:
        if token not in cad:
            raise RuntimeError(f"missing core contract token: {token}")

    forbidden_smart = [
        "pendingTapHit = findHit(wx, wy);",
        "java.lang.reflect.Field",
        "java.lang.reflect.Method",
        "getDeclaredField",
        "getDeclaredMethod",
    ]
    forbidden_chob = [
        "float wx = screenToWorldX(x), wy = screenToWorldY(y);",
        "java.lang.reflect.Field",
        "java.lang.reflect.Method",
        "getDeclaredField",
        "getDeclaredMethod",
    ]
    for token in forbidden_smart:
        if token in smart:
            raise RuntimeError(f"SmartCadCanvasView still contains: {token}")
    for token in forbidden_chob:
        if token in chob:
            raise RuntimeError(f"ChobYarShaprCanvasView still contains: {token}")

    if "pendingTapHit = coreFindHit(wx, wy);" not in smart:
        raise RuntimeError("pending selection hit-test is not routed through coreFindHit")
    if "coreScreenToWorldX(x)" not in chob or "coreScreenToWorldY(y)" not in chob:
        raise RuntimeError("gizmo viewport conversion is not routed through core API")

    print("Remaining Sketch core call sites patched and validated")


def main():
    patch_remaining_calls()
    validate()


if __name__ == "__main__":
    main()

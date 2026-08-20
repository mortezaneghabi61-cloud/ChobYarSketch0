#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SRC = ROOT / "app/src/main/java/ir/chobyar/sketch"
CAD = SRC / "CadCanvasView.java"
SMART = SRC / "SmartCadCanvasView.java"
CHOB = SRC / "ChobYarShaprCanvasView.java"
SHAPR = SRC / "ShaprStyleCadCanvasView.java"


def patch_remaining_calls():
    cad = CAD.read_text(encoding="utf-8")
    smart = SMART.read_text(encoding="utf-8")
    chob = CHOB.read_text(encoding="utf-8")
    shapr = SHAPR.read_text(encoding="utf-8")

    smart = smart.replace(
        "pendingTapHit = findHit(wx, wy);",
        "pendingTapHit = coreFindHit(wx, wy);"
    )
    chob = chob.replace(
        "float wx = screenToWorldX(x), wy = screenToWorldY(y);",
        "float wx = coreScreenToWorldX(x), wy = coreScreenToWorldY(y);"
    )

    # ShaprStyleCadCanvasView owns an interactive dimension label. It needs to
    # prime the production ScaleGestureDetector with the first finger without
    # invoking selection/drawing in CadCanvasView. Keep the detector private and
    # expose only this narrow type-safe observation hook to presentation layers.
    scale_hook = (
        "    protected final void coreObserveScaleGesture(MotionEvent event) {\n"
        "        scaleDetector.onTouchEvent(event);\n"
        "    }\n"
    )
    anchor = "    protected final float coreScreenToWorldY(float sy) { return screenToWorldY(sy); }\n"
    if "protected final void coreObserveScaleGesture" not in cad:
        if anchor not in cad:
            raise RuntimeError("could not locate Sketch core contract anchor")
        cad = cad.replace(anchor, anchor + scale_hook, 1)

    # When a single finger starts on the exact-dimension label, let the label
    # keep owning tap/drag behavior but feed that pointer stream to the scale
    # detector. If a second finger joins, ShaprStyle already cancels its label
    # gesture and forwards the real POINTER_DOWN/MOVE sequence to super.
    down_anchor = (
        "        if (action == MotionEvent.ACTION_DOWN\n"
        "                && !exactFieldRect.isEmpty()\n"
        "                && exactFieldRect.contains(event.getX(), event.getY())) {\n"
        "            exactFieldPressed = true;\n"
    )
    down_replacement = (
        "        if (action == MotionEvent.ACTION_DOWN\n"
        "                && !exactFieldRect.isEmpty()\n"
        "                && exactFieldRect.contains(event.getX(), event.getY())) {\n"
        "            coreObserveScaleGesture(event);\n"
        "            exactFieldPressed = true;\n"
    )
    if "coreObserveScaleGesture(event);\n            exactFieldPressed = true;" not in shapr:
        if down_anchor not in shapr:
            raise RuntimeError("could not locate dimension-label ACTION_DOWN")
        shapr = shapr.replace(down_anchor, down_replacement, 1)

    pressed_anchor = "        if (exactFieldPressed) {\n            if (action == MotionEvent.ACTION_MOVE) {\n"
    pressed_replacement = (
        "        if (exactFieldPressed) {\n"
        "            coreObserveScaleGesture(event);\n"
        "            if (action == MotionEvent.ACTION_MOVE) {\n"
    )
    if "if (exactFieldPressed) {\n            coreObserveScaleGesture(event);" not in shapr:
        if pressed_anchor not in shapr:
            raise RuntimeError("could not locate active dimension-label gesture")
        shapr = shapr.replace(pressed_anchor, pressed_replacement, 1)

    CAD.write_text(cad, encoding="utf-8")
    SMART.write_text(smart, encoding="utf-8")
    CHOB.write_text(chob, encoding="utf-8")
    SHAPR.write_text(shapr, encoding="utf-8")


def validate():
    cad = CAD.read_text(encoding="utf-8")
    smart = SMART.read_text(encoding="utf-8")
    chob = CHOB.read_text(encoding="utf-8")
    shapr = SHAPR.read_text(encoding="utf-8")

    required = [
        "protected final Entity coreFindHit",
        "protected final void coreSaveUndo",
        "protected final boolean coreIsVisible",
        "protected final float coreScreenToWorldX",
        "protected final float coreScreenToWorldY",
        "protected final void coreObserveScaleGesture",
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
    if shapr.count("coreObserveScaleGesture(event);") < 2:
        raise RuntimeError("dimension-label gesture does not seed and maintain the scale detector")
    if "event.getPointerCount() >= 2" not in shapr or "return super.onTouchEvent(event);" not in shapr:
        raise RuntimeError("multi-touch is not handed back to the CAD core")

    print("Remaining Sketch core call sites patched and validated")


def main():
    patch_remaining_calls()
    validate()


if __name__ == "__main__":
    main()

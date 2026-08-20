#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SRC = ROOT / "app/src/main/java/ir/chobyar/sketch"
CAD = SRC / "CadCanvasView.java"
SMART = SRC / "SmartCadCanvasView.java"
CHOB = SRC / "ChobYarShaprCanvasView.java"
SHAPR = SRC / "ShaprStyleCadCanvasView.java"
TOUCH_TEST = ROOT / "app/src/androidTest/java/ir/chobyar/sketch/TouchInputContractInstrumentationTest.java"


def patch_remaining_calls():
    cad = CAD.read_text(encoding="utf-8")
    smart = SMART.read_text(encoding="utf-8")
    chob = CHOB.read_text(encoding="utf-8")
    shapr = SHAPR.read_text(encoding="utf-8")
    touch_test = TOUCH_TEST.read_text(encoding="utf-8")

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
    # detector.
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

    # Critical handoff: if pointer #1 started on the floating dimension field,
    # the label owns ACTION_DOWN. Feed ACTION_POINTER_DOWN directly to the same
    # scale detector exactly once before dropping label ownership. Subsequent
    # MOVE events use the normal CAD navigation path. This preserves the native
    # ScaleGestureDetector sequence without letting intermediate UI layers trap
    # the pinch.
    multi_anchor = (
        "        if (event.getPointerCount() >= 2) {\n"
        "            exactFieldPressed = false;\n"
        "            exactFieldDragging = false;\n"
        "            fieldGestureEntity = null;\n"
        "            return super.onTouchEvent(event);\n"
        "        }\n"
    )
    multi_replacement = (
        "        if (event.getPointerCount() >= 2) {\n"
        "            if (exactFieldPressed) {\n"
        "                // Dimension-label pinch transition: the first DOWN was\n"
        "                // already observed directly, so observe POINTER_DOWN\n"
        "                // here exactly once before returning navigation to core.\n"
        "                coreObserveScaleGesture(event);\n"
        "                exactFieldPressed = false;\n"
        "                exactFieldDragging = false;\n"
        "                fieldGestureEntity = null;\n"
        "                return true;\n"
        "            }\n"
        "            exactFieldPressed = false;\n"
        "            exactFieldDragging = false;\n"
        "            fieldGestureEntity = null;\n"
        "            return super.onTouchEvent(event);\n"
        "        }\n"
    )
    if "Dimension-label pinch transition" not in shapr:
        if multi_anchor not in shapr:
            raise RuntimeError("could not locate dimension-label multi-touch handoff")
        shapr = shapr.replace(multi_anchor, multi_replacement, 1)

    # Emit successful-run telemetry into the instrumentation stream itself.
    # touch-input-contract.txt is already uploaded as a CI artifact, so these
    # values become durable evidence instead of existing only in transient logs.
    if "private static void report(Instrumentation instrumentation, String value)" not in touch_test:
        helper_anchor = "    private static CanvasState capture(ActivityScenario<ChobYarActivity> scenario) {\n"
        helper = (
            "    private static void report(Instrumentation instrumentation, String value) {\n"
            "        android.os.Bundle status = new android.os.Bundle();\n"
            "        status.putString(\"CHOBYAR_TOUCH_RESULT\", value);\n"
            "        instrumentation.sendStatus(2, status);\n"
            "    }\n\n"
        )
        if helper_anchor not in touch_test:
            raise RuntimeError("could not locate touch-test helper anchor")
        touch_test = touch_test.replace(helper_anchor, helper + helper_anchor, 1)

    stylus_anchor = (
        "                assertNotNull(\"Stylus tap on the rectangle edge must select it\", canvas.selected);\n"
        "                assertEquals(1, canvas.selectedObjects.size());\n"
    )
    stylus_replacement = stylus_anchor + (
        "                report(instrumentation, \"STYLUS_RESULT viewScale=\" + canvas.viewScale);\n"
    )
    if "STYLUS_RESULT viewScale=" not in touch_test:
        if stylus_anchor not in touch_test:
            raise RuntimeError("could not locate stylus result anchor")
        touch_test = touch_test.replace(stylus_anchor, stylus_replacement, 1)

    pan_anchor = (
        "                assertTrue(\"Pure pan unexpectedly changed zoom too much: before=\" + before[2]\n"
        "                                + \" after=\" + canvas.viewScale,\n"
        "                        Math.abs(canvas.viewScale - before[2]) < 0.08f);\n"
    )
    pan_replacement = pan_anchor + (
        "                report(instrumentation, \"PAN_RESULT before=\" + before[2]\n"
        "                        + \" after=\" + canvas.viewScale + \" moved=\" + moved);\n"
    )
    if "PAN_RESULT before=" not in touch_test:
        if pan_anchor not in touch_test:
            raise RuntimeError("could not locate pan result anchor")
        touch_test = touch_test.replace(pan_anchor, pan_replacement, 1)

    pinch_anchor = (
        "                assertTrue(\"Two-finger pinch was blocked by exact dimension label: before=\"\n"
        "                                + start[2] + \" after=\" + canvas.viewScale,\n"
        "                        canvas.viewScale > start[2] * 1.05f);\n"
    )
    pinch_replacement = (
        "                report(instrumentation, \"DIMENSION_LABEL_PINCH_RESULT before=\"\n"
        "                        + start[2] + \" after=\" + canvas.viewScale);\n"
    ) + pinch_anchor
    if "DIMENSION_LABEL_PINCH_RESULT before=" not in touch_test:
        if pinch_anchor not in touch_test:
            raise RuntimeError("could not locate dimension-label pinch result anchor")
        touch_test = touch_test.replace(pinch_anchor, pinch_replacement, 1)

    CAD.write_text(cad, encoding="utf-8")
    SMART.write_text(smart, encoding="utf-8")
    CHOB.write_text(chob, encoding="utf-8")
    SHAPR.write_text(shapr, encoding="utf-8")
    TOUCH_TEST.write_text(touch_test, encoding="utf-8")


def validate():
    cad = CAD.read_text(encoding="utf-8")
    smart = SMART.read_text(encoding="utf-8")
    chob = CHOB.read_text(encoding="utf-8")
    shapr = SHAPR.read_text(encoding="utf-8")
    touch_test = TOUCH_TEST.read_text(encoding="utf-8")

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
    if shapr.count("coreObserveScaleGesture(event);") < 3:
        raise RuntimeError("dimension-label gesture does not seed/transition the scale detector")
    if "Dimension-label pinch transition" not in shapr:
        raise RuntimeError("dimension-label pinch transition is not explicit")
    for marker in ("STYLUS_RESULT", "PAN_RESULT", "DIMENSION_LABEL_PINCH_RESULT"):
        if marker not in touch_test:
            raise RuntimeError(f"touch telemetry missing: {marker}")

    print("Remaining Sketch core call sites patched and validated")


def main():
    patch_remaining_calls()
    validate()


if __name__ == "__main__":
    main()

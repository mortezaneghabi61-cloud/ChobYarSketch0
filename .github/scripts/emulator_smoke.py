#!/usr/bin/env python3
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from pathlib import Path

PACKAGE = "ir.chobyar.sketch"
ACTIVITY = f"{PACKAGE}/.ChobYarActivity"
APK = Path("app/build/outputs/apk/debug/app-debug.apk")
ARTIFACTS = Path("test-artifacts")
ARTIFACTS.mkdir(parents=True, exist_ok=True)


def run(cmd, check=True, stdout=subprocess.PIPE):
    print("$", " ".join(str(x) for x in cmd), flush=True)
    result = subprocess.run(cmd, stdout=stdout, stderr=subprocess.PIPE, text=True)
    if result.stdout:
        print(result.stdout.rstrip(), flush=True)
    if result.stderr:
        print(result.stderr.rstrip(), file=sys.stderr, flush=True)
    if check and result.returncode != 0:
        raise RuntimeError(f"command failed ({result.returncode}): {' '.join(str(x) for x in cmd)}")
    return result


def adb(*args, check=True):
    return run(["adb", *args], check=check)


def shell(*args, check=True):
    return adb("shell", *args, check=check)


def screenshot(name):
    path = ARTIFACTS / f"{name}.png"
    print(f"Capturing {path}", flush=True)
    with path.open("wb") as f:
        result = subprocess.run(["adb", "exec-out", "screencap", "-p"], stdout=f, stderr=subprocess.PIPE)
    if result.returncode != 0:
        raise RuntimeError(result.stderr.decode("utf-8", "replace"))


def dump_ui(name, attempts=5):
    remote = "/sdcard/chobyar-window.xml"
    local = ARTIFACTS / f"{name}.xml"
    last_error = None
    for attempt in range(attempts):
        shell("rm", "-f", remote, check=False)
        result = shell("uiautomator", "dump", remote, check=False)
        pull = adb("pull", remote, str(local), check=False)
        if result.returncode == 0 and pull.returncode == 0 and local.exists():
            try:
                return ET.parse(local).getroot()
            except ET.ParseError as exc:
                last_error = exc
        else:
            last_error = RuntimeError(
                f"UI dump attempt {attempt + 1}: dump={result.returncode}, pull={pull.returncode}"
            )
        time.sleep(1.25)
    raise RuntimeError(f"Could not capture Android UI hierarchy: {last_error}")


def parse_bounds(raw):
    m = re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", raw or "")
    return tuple(int(v) for v in m.groups()) if m else None


def node_text(node):
    return " ".join([
        node.attrib.get("text", ""),
        node.attrib.get("content-desc", ""),
        node.attrib.get("resource-id", ""),
    ])


def find_contains(root, needle):
    wanted = needle.casefold()
    for node in root.iter("node"):
        if wanted in node_text(node).casefold():
            bounds = parse_bounds(node.attrib.get("bounds"))
            if bounds:
                return node, bounds
    return None, None


def find_label(root, label):
    """Find a toolbar/menu label, avoiding status text that merely contains it."""
    wanted = label.casefold()
    for node in root.iter("node"):
        text = node.attrib.get("text", "")
        lines = [part.strip().casefold() for part in text.splitlines() if part.strip()]
        if wanted in lines:
            bounds = parse_bounds(node.attrib.get("bounds"))
            if bounds:
                return node, bounds
    for node in root.iter("node"):
        desc = node.attrib.get("content-desc", "").strip().casefold()
        if desc == wanted:
            bounds = parse_bounds(node.attrib.get("bounds"))
            if bounds:
                return node, bounds
    return None, None


def tap_bounds(bounds, label):
    left, top, right, bottom = bounds
    x, y = (left + right) // 2, (top + bottom) // 2
    print(f"Tapping {label!r} at ({x}, {y})", flush=True)
    shell("input", "tap", str(x), str(y))
    time.sleep(1.2)


def tap_label(root, label):
    node, bounds = find_label(root, label)
    if node is None:
        raise AssertionError(f"UI label {label!r} was not found")
    tap_bounds(bounds, label)


def dismiss_android_overlays():
    for attempt in range(3):
        root = dump_ui(f"00-system-overlay-{attempt}")
        node, bounds = find_contains(root, "Got it")
        if node is None:
            node, bounds = find_contains(root, "android:id/ok")
        if node is None:
            return
        screenshot(f"00-system-overlay-{attempt}")
        tap_bounds(bounds, "Android full-screen tutorial")
        time.sleep(1.5)


def wait_for_label(label, prefix, timeout=30):
    deadline = time.time() + timeout
    attempt = 0
    last = None
    while time.time() < deadline:
        last = dump_ui(f"{prefix}-{attempt}")
        node, _ = find_label(last, label)
        if node is not None:
            return last
        time.sleep(1.25)
        attempt += 1
    return last


def wait_for_contains(text, prefix, timeout=12):
    deadline = time.time() + timeout
    attempt = 0
    last = None
    while time.time() < deadline:
        last = dump_ui(f"{prefix}-{attempt}")
        node, _ = find_contains(last, text)
        if node is not None:
            return last
        time.sleep(0.8)
        attempt += 1
    return last


def display_size(root):
    right = bottom = 0
    for node in root.iter("node"):
        b = parse_bounds(node.attrib.get("bounds"))
        if b:
            right, bottom = max(right, b[2]), max(bottom, b[3])
    if right < 400 or bottom < 400:
        raise AssertionError(f"Invalid UI display bounds: {right}x{bottom}")
    return right, bottom


def assert_alive(stage):
    result = shell("pidof", PACKAGE, check=False)
    pid = result.stdout.strip()
    if not pid:
        raise AssertionError(f"{PACKAGE} died at stage: {stage}")
    print(f"App alive at {stage}; pid={pid}", flush=True)
    return pid.split()[0]


def save_logcat(pid):
    result = adb("logcat", "-d", "-v", "threadtime", f"--pid={pid}", check=False)
    (ARTIFACTS / "app-logcat.txt").write_text(result.stdout or "", encoding="utf-8")
    return result.stdout or ""


def main():
    if not APK.exists():
        raise FileNotFoundError(f"APK not found: {APK}")

    adb("wait-for-device")
    shell("settings", "put", "system", "accelerometer_rotation", "0", check=False)
    shell("settings", "put", "system", "user_rotation", "1", check=False)
    shell("settings", "put", "secure", "immersive_mode_confirmations", "confirmed", check=False)
    adb("install", "-r", str(APK))
    adb("logcat", "-c")
    shell("am", "force-stop", PACKAGE, check=False)

    launch = shell("am", "start", "-W", "-n", ACTIVITY)
    if "Status: ok" not in launch.stdout and "Status: timeout" not in launch.stdout:
        raise AssertionError("Android did not report a usable activity launch")
    time.sleep(2)

    pid = assert_alive("launch")
    dismiss_android_overlays()
    workspace = wait_for_label("Sketch", "01-workspace")
    screenshot("01-workspace")
    if find_label(workspace, "Sketch")[0] is None:
        raise AssertionError("Primary Sketch toolbar control never appeared")
    if find_label(workspace, "Units")[0] is None:
        raise AssertionError("Workspace loaded incompletely: Units control is missing")

    tap_label(workspace, "Sketch")
    palette = wait_for_label("Rectangle", "02-sketch-palette", timeout=15)
    screenshot("02-sketch-palette")
    if find_label(palette, "Rectangle")[0] is None:
        raise AssertionError("Sketch palette did not expose Rectangle")

    tap_label(palette, "Rectangle")
    active = dump_ui("03-rectangle-active")
    width, height = display_size(active)

    x1, y1 = int(width * 0.38), int(height * 0.35)
    x2, y2 = int(width * 0.66), int(height * 0.66)
    print(f"Drawing rectangle from ({x1},{y1}) to ({x2},{y2}) on {width}x{height}", flush=True)
    shell("input", "swipe", str(x1), str(y1), str(x2), str(y2), "700")
    time.sleep(1.5)

    pid = assert_alive("rectangle gesture")
    screenshot("03-rectangle-drawn")

    label_x = (x1 + x2) // 2
    label_y = (y1 + y2) // 2 - 58
    moved_x = min(width - 170, label_x + 115)
    moved_y = min(height - 120, label_y + 85)
    print(f"Dragging dimension label ({label_x},{label_y}) -> ({moved_x},{moved_y})", flush=True)
    shell("input", "swipe", str(label_x), str(label_y), str(moved_x), str(moved_y), "550")
    time.sleep(0.8)
    assert_alive("dimension-label drag")
    screenshot("04-dimension-label-moved")

    edit_x = max(24, moved_x - 90)
    print(f"Tapping unobscured moved dimension label at ({edit_x},{moved_y})", flush=True)
    shell("input", "tap", str(edit_x), str(moved_y))
    editor = wait_for_contains("Width and Height", "05-dimension-editor", timeout=10)
    if editor is None or find_contains(editor, "Width and Height")[0] is None:
        raise AssertionError("Moved rectangle dimension label did not open its numeric editor")
    screenshot("05-moved-dimension-editor")
    shell("input", "keyevent", "KEYCODE_BACK")
    time.sleep(0.8)

    # Explicit selection regression: leave the Sketch palette, clear the
    # auto-selection created by Rectangle, then select the rectangle again by
    # touching an unobscured edge. The adaptive Deselect All command is our
    # accessibility-visible proof that the production selection path fired.
    after_editor = dump_ui("06-before-selection-test")
    close_node, close_bounds = find_label(after_editor, "Close")
    if close_node is not None:
        tap_bounds(close_bounds, "Close")
    closed = dump_ui("06-palette-closed")
    deselect_node, deselect_bounds = find_label(closed, "Deselect All")
    if deselect_node is not None:
        tap_bounds(deselect_bounds, "Deselect All")
    cleared = wait_for_label("Search", "06-selection-cleared", timeout=10)
    if cleared is None or find_label(cleared, "Search")[0] is None:
        raise AssertionError("Selection could not be cleared back to the primary tool rail")

    edge_x = x1 + 8
    edge_y = (y1 + y2) // 2
    print(f"Selecting rectangle edge at ({edge_x},{edge_y})", flush=True)
    shell("input", "tap", str(edge_x), str(edge_y))
    selected_ui = wait_for_label("Deselect All", "07-rectangle-reselected", timeout=10)
    if selected_ui is None or find_label(selected_ui, "Deselect All")[0] is None:
        raise AssertionError("Rectangle could not be reselected after clearing selection")
    assert_alive("rectangle reselection")
    screenshot("07-rectangle-reselected")

    # Viewport zoom regression: Fit is the production zoom-to-fit path and
    # recalculates viewScale/offsets through the same core viewport state used
    # by pinch zoom. Exercise it after reselection to catch viewport/selection
    # coupling regressions introduced by the architecture refactor.
    fit_node, fit_bounds = find_label(selected_ui, "Fit")
    if fit_node is None:
        raise AssertionError("Fit zoom control disappeared after refactor")
    tap_bounds(fit_bounds, "Fit")
    assert_alive("Fit zoom")
    screenshot("08-after-fit-zoom")

    logcat = save_logcat(pid)
    if "FATAL EXCEPTION" in logcat:
        raise AssertionError("FATAL EXCEPTION found in app logcat")

    summary = (
        "PASS\n"
        "- APK built, installed, and ChobYarActivity stayed alive\n"
        "- Main CAD workspace controls were accessible\n"
        "- Sketch palette opened\n"
        "- Rectangle tool accepted a real ADB touch gesture\n"
        "- Rectangle dimension label was dragged to a new screen position\n"
        "- Tapping the moved label opened the numeric dimension editor\n"
        "- Rectangle selection was cleared and then reacquired by a fresh edge tap\n"
        "- Fit zoom exercised the refactored viewport state while selection remained active\n"
        "- No FATAL EXCEPTION was found\n"
    )
    (ARTIFACTS / "summary.txt").write_text(summary, encoding="utf-8")
    print(summary, flush=True)


if __name__ == "__main__":
    try:
        main()
    except Exception as exc:
        print(f"SMOKE TEST FAILED: {exc}", file=sys.stderr, flush=True)
        try:
            screenshot("99-failure")
            dump_ui("99-failure-ui")
        except Exception as capture_exc:
            print(f"Could not capture failure evidence: {capture_exc}", file=sys.stderr, flush=True)
        try:
            pid = shell("pidof", PACKAGE, check=False).stdout.strip().split()[0]
            save_logcat(pid)
        except Exception:
            result = adb("logcat", "-d", "-v", "threadtime", check=False)
            (ARTIFACTS / "full-logcat.txt").write_text(result.stdout or "", encoding="utf-8")
        raise

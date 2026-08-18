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


def run(cmd, *, check=True, text=True, stdout=subprocess.PIPE):
    print("$", " ".join(str(x) for x in cmd), flush=True)
    result = subprocess.run(cmd, stdout=stdout, stderr=subprocess.PIPE, text=text)
    if text and result.stdout:
        print(result.stdout.rstrip(), flush=True)
    if result.stderr:
        err = result.stderr if text else result.stderr.decode("utf-8", "replace")
        print(err.rstrip(), file=sys.stderr, flush=True)
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
                f"uiautomator dump attempt {attempt + 1} failed: "
                f"dump={result.returncode}, pull={pull.returncode}"
            )
        time.sleep(1.25)
    raise RuntimeError(f"Could not capture Android UI hierarchy after {attempts} attempts: {last_error}")


def parse_bounds(raw):
    m = re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", raw or "")
    if not m:
        return None
    return tuple(int(v) for v in m.groups())


def find_node(root, needle):
    needle = needle.casefold()
    for node in root.iter("node"):
        haystack = " ".join([
            node.attrib.get("text", ""),
            node.attrib.get("content-desc", ""),
            node.attrib.get("resource-id", ""),
        ]).casefold()
        if needle in haystack:
            bounds = parse_bounds(node.attrib.get("bounds"))
            if bounds:
                return node, bounds
    return None, None


def click_contains(root, needle):
    node, bounds = find_node(root, needle)
    if node is None:
        raise AssertionError(f"UI item containing {needle!r} was not found")
    left, top, right, bottom = bounds
    x = (left + right) // 2
    y = (top + bottom) // 2
    print(f"Clicking {needle!r} at ({x}, {y}) text={node.attrib.get('text', '')!r}", flush=True)
    shell("input", "tap", str(x), str(y))
    time.sleep(1.0)


def dismiss_android_first_run_overlays():
    # A fresh emulator can show Android's own immersive-mode tutorial above
    # the app. We pre-confirm it through Settings, but retain this fallback for
    # images where that setting is ignored.
    for attempt in range(3):
        root = dump_ui(f"00-system-overlay-{attempt}")
        got_it, _ = find_node(root, "Got it")
        if got_it is None:
            got_it, _ = find_node(root, "android:id/ok")
        if got_it is None:
            return
        screenshot(f"00-system-overlay-{attempt}")
        click_contains(root, "Got it" if find_node(root, "Got it")[0] is not None else "android:id/ok")
        time.sleep(2.0)


def wait_for_workspace(timeout_seconds=30):
    deadline = time.time() + timeout_seconds
    last = None
    attempt = 0
    while time.time() < deadline:
        last = dump_ui(f"01-workspace-wait-{attempt}")
        title, _ = find_node(last, "چوب‌یار 3D")
        sketch, _ = find_node(last, "Sketch")
        if title is not None and sketch is not None:
            return last
        time.sleep(1.5)
        attempt += 1
    return last


def display_size_from_ui(root):
    max_right = 0
    max_bottom = 0
    for node in root.iter("node"):
        bounds = parse_bounds(node.attrib.get("bounds"))
        if bounds:
            max_right = max(max_right, bounds[2])
            max_bottom = max(max_bottom, bounds[3])
    if max_right < 400 or max_bottom < 400:
        raise AssertionError(f"Could not determine usable display size from UI dump: {max_right}x{max_bottom}")
    return max_right, max_bottom


def assert_alive(stage):
    result = shell("pidof", PACKAGE, check=False)
    pid = result.stdout.strip()
    if not pid:
        raise AssertionError(f"{PACKAGE} is not running at stage: {stage}")
    print(f"App alive at {stage}; pid={pid}", flush=True)
    return pid.split()[0]


def save_logcat(pid):
    result = adb("logcat", "-d", "-v", "threadtime", f"--pid={pid}", check=False)
    path = ARTIFACTS / "app-logcat.txt"
    path.write_text(result.stdout or "", encoding="utf-8")
    return result.stdout or ""


def main():
    if not APK.exists():
        raise FileNotFoundError(f"APK not found: {APK}")

    adb("wait-for-device")
    shell("settings", "put", "system", "accelerometer_rotation", "0", check=False)
    shell("settings", "put", "system", "user_rotation", "1", check=False)
    # Prevent Android's own one-time full-screen tutorial from obscuring the
    # application on a newly-created CI emulator.
    shell("settings", "put", "secure", "immersive_mode_confirmations", "confirmed", check=False)
    adb("install", "-r", str(APK))
    adb("logcat", "-c")
    shell("am", "force-stop", PACKAGE, check=False)

    launch = shell("am", "start", "-W", "-n", ACTIVITY)
    if "Status: ok" not in launch.stdout and "Status: timeout" not in launch.stdout:
        raise AssertionError("Android did not report a successful activity launch")
    time.sleep(2.0)

    pid = assert_alive("launch")
    dismiss_android_first_run_overlays()
    launch_ui = wait_for_workspace()
    screenshot("01-launch")

    title, _ = find_node(launch_ui, "چوب‌یار 3D")
    if title is None:
        raise AssertionError("Workspace title 'چوب‌یار 3D' is missing after Android overlays were dismissed")
    sketch, _ = find_node(launch_ui, "Sketch")
    if sketch is None:
        raise AssertionError("Primary Sketch tool is missing from the launch UI")

    click_contains(launch_ui, "Sketch")
    sketch_ui = dump_ui("02-sketch-palette-ui")
    screenshot("02-sketch-palette")
    rectangle, _ = find_node(sketch_ui, "Rectangle")
    if rectangle is None:
        raise AssertionError("Sketch palette opened, but Rectangle tool is missing")

    click_contains(sketch_ui, "Rectangle")
    active_ui = dump_ui("03-rectangle-active-ui")
    width, height = display_size_from_ui(active_ui)
    x1, y1 = int(width * 0.38), int(height * 0.34)
    x2, y2 = int(width * 0.66), int(height * 0.66)
    print(f"Drawing rectangle from ({x1},{y1}) to ({x2},{y2}) on {width}x{height}", flush=True)
    shell("input", "swipe", str(x1), str(y1), str(x2), str(y2), "700")
    time.sleep(1.5)

    pid = assert_alive("rectangle gesture")
    screenshot("03-rectangle-drawn")
    dump_ui("04-after-rectangle-ui")

    # Exercise a non-destructive view command after drawing. This catches a
    # common class of crashes where the sketch exists but workspace chrome and
    # canvas state get out of sync.
    after_ui = dump_ui("05-before-fit-ui")
    fit, _ = find_node(after_ui, "Fit")
    if fit is not None:
        click_contains(after_ui, "Fit")
        time.sleep(0.5)
        assert_alive("Fit command")
        screenshot("04-after-fit")

    logcat = save_logcat(pid)
    if "FATAL EXCEPTION" in logcat:
        raise AssertionError("App logcat contains FATAL EXCEPTION")

    summary = (
        "PASS\n"
        "- App installed and ChobYarActivity launched\n"
        "- Android first-run full-screen overlay was handled\n"
        "- Main workspace title and Sketch tool were visible\n"
        "- Sketch palette opened and Rectangle tool was visible\n"
        "- Rectangle gesture completed without process death\n"
        "- Fit command executed when available\n"
        "- No FATAL EXCEPTION found in app logcat\n"
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
            print(f"Could not capture failure artifacts: {capture_exc}", file=sys.stderr, flush=True)
        try:
            pid = shell("pidof", PACKAGE, check=False).stdout.strip().split()[0]
            save_logcat(pid)
        except Exception:
            result = adb("logcat", "-d", "-v", "threadtime", check=False)
            (ARTIFACTS / "full-logcat.txt").write_text(result.stdout or "", encoding="utf-8")
        raise

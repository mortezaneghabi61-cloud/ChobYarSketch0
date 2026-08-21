# Performance and Real-Device Acceptance

This stage turns a green emulator build into an installable Android release candidate and records a repeatable performance baseline. Passing CI is necessary but is **not** the final real-device acceptance.

## CI release-candidate gate

The Release Candidate Performance Gate must:

- build the signed `release` APK against Android 35 and the OCCT exact B-Rep backend;
- verify the APK signature and application id `ir.chobyar.sketch`;
- verify the universal APK carries `arm64-v8a` native libraries;
- keep the APK below the current 350 MiB guardrail;
- install the exact release APK on an API 35 emulator;
- cold-launch `ChobYarActivity` successfully and keep the emulator baseline below 30 seconds;
- verify the app process remains alive after launch;
- capture `dumpsys meminfo`, `dumpsys gfxinfo`, cold-launch timing and logcat evidence;
- fail on a launch-time fatal exception or app ANR;
- upload the exact APK and evidence used by the gate.

The broad startup and size thresholds are initial regression guardrails for the hosted emulator. They are not product UX targets and can be tightened after several stable baselines.

## Real-device acceptance

Use the exact signed release-candidate APK produced by CI on an arm64 Android device. Do not substitute a locally rebuilt APK.

Acceptance sequence:

1. Install or update the APK over the previous ChobYar build without losing the signing lineage.
2. Cold-launch the app three times and verify there is no crash, ANR, black canvas or permanently stalled renderer.
3. Create a Sketch with line, rectangle, circle and arc entities; pan and zoom while the Sketch remains responsive.
4. Create an exact 3D body with Extrude, then exercise Move/Rotate, Fillet or Chamfer, Shell, Boolean and one Revolve/Thread path where applicable.
5. Orbit, pan and zoom the 3D view continuously for at least 30 seconds; verify there is no visible model corruption or renderer loss.
6. Add and calibrate a Reference Image; orbit away and back; verify registration remains on the same Sketch plane.
7. Change Material/Appearance preset and roughness; verify exact model dimensions and History do not change.
8. Enable Section View, change axis/offset/flip, then disable it; verify the exact model is unchanged.
9. Save a `.chobyar` project, close/reopen it, and verify Sketch, exact 3D History, Reference Image, Material/Appearance and Section state restore correctly.
10. Export at least STEP and STL from the restored project and confirm export completes without changing the document.
11. Repeat the key modeling interactions with the stylus if the device supports a pressure-capable pen.

## Evidence to keep

Record the device model, Android version, APK version name/code, commit SHA, cold-launch observations, any visible jank or interaction delay, and every crash/ANR with the action that triggered it. A release candidate is accepted only when the full sequence above completes without a blocking defect.

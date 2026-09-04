#!/usr/bin/env bash
set -euo pipefail

mkdir -p test-artifacts
: > test-artifacts/production-cad-summary.txt

adb shell am force-stop com.google.android.apps.nexuslauncher || true
adb shell pm disable-user --user 0 com.google.android.apps.nexuslauncher || true
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk

focus_touch_target() {
  adb shell input keyevent KEYCODE_WAKEUP || true
  adb shell am force-stop ir.chobyar.sketch || true
  adb shell am start -W -n ir.chobyar.sketch/.ChobYarActivity \
    | tee test-artifacts/touch-target-launch.txt
  grep -Fq 'Status: ok' test-artifacts/touch-target-launch.txt
  sleep 1
}

run_contract() {
  local class="$1"
  local slug="$2"
  local expected="$3"
  local attempt=1
  local instrument_status=0

  while true; do
    if [[ "$class" == "TouchInputContractInstrumentationTest" ]]; then
      focus_touch_target
    fi

    adb logcat -c
    set +e
    adb shell am instrument -w -e class "ir.chobyar.sketch.${class}" \
      ir.chobyar.sketch.test/androidx.test.runner.AndroidJUnitRunner \
      | tee "test-artifacts/${slug}-instrumentation.txt"
    instrument_status=${PIPESTATUS[0]}
    set -e

    adb logcat -d -v brief > "test-artifacts/${slug}-logcat.txt" || true

    if [[ ${instrument_status} -eq 0 ]] && grep -Fq "$expected" "test-artifacts/${slug}-instrumentation.txt"; then
      break
    fi

    # API 35 can briefly give the system/launcher window focus immediately after
    # emulator boot. In that narrow case Instrumentation.sendPointerSync rejects
    # an otherwise valid stylus event before it reaches the app. Preserve the
    # failed evidence and retry the whole isolated contract exactly once. Do not
    # retry assertion failures or any other application/test failure.
    if [[ ${attempt} -eq 1 ]] && \
       grep -Fq 'Targeted input event injection' "test-artifacts/${slug}-instrumentation.txt"; then
      cp "test-artifacts/${slug}-instrumentation.txt" "test-artifacts/${slug}-instrumentation-attempt1.txt"
      cp "test-artifacts/${slug}-logcat.txt" "test-artifacts/${slug}-logcat-attempt1.txt" || true
      echo "CONTRACT_INFRA_RETRY class=${class} reason=api35_target_window_focus" \
        | tee -a test-artifacts/production-cad-summary.txt
      attempt=2
      instrumentation_package='ir.chobyar.sketch.test'
      adb shell am force-stop "$instrumentation_package" || true
      adb shell input keyevent KEYCODE_WAKEUP || true
      sleep 2
      continue
    fi

    if [[ ${instrument_status} -ne 0 ]]; then
      echo "CONTRACT_FAIL class=${class} reason=instrumentation_exit_${instrument_status}" | tee -a test-artifacts/production-cad-summary.txt
      return ${instrument_status}
    fi
    echo "CONTRACT_FAIL class=${class} reason=missing_expected expected=${expected}" | tee -a test-artifacts/production-cad-summary.txt
    return 1
  done

  printf '%s | %s\n' "$class" "$expected" | tee -a test-artifacts/production-cad-summary.txt
}

# Sketch interaction, primitives, selection, dimensions and constraints
run_contract TouchInputContractInstrumentationTest touch 'OK (3 tests)'
run_contract StylusDimensionLabelRoutingInstrumentationTest stylus-label-routing 'OK (1 test)'
run_contract DimensionLabelReleaseInstrumentationTest dimension-label-release 'OK (2 tests)'
run_contract K38PointLockInteractionInstrumentationTest point-lock-interaction 'OK (4 tests)'
run_contract K39PointFixedTransformInstrumentationTest point-fixed-transform 'OK (4 tests)'
run_contract K310DrivingDimensionAuthorityInstrumentationTest driving-dimension-authority 'OK (4 tests)'
run_contract K310DrivingDimensionActivityLifecycleInstrumentationTest driving-dimension-activity-lifecycle 'OK (1 test)'
run_contract K311EqualConstraintAuthorityInstrumentationTest equal-constraint-authority 'OK (4 tests)'
run_contract K312SingleLineAngleAuthorityInstrumentationTest single-line-angle-authority 'OK (5 tests)'
run_contract K313TangentAuthorityInstrumentationTest tangent-authority 'OK (6 tests)'
run_contract K314SymmetryAuthorityInstrumentationTest symmetry-authority 'OK (5 tests)'
run_contract K315ShaprLabRenderAuthorityInstrumentationTest shaprlab-render-authority 'OK (3 tests)'
run_contract K316ConcentricAuthorityInstrumentationTest concentric-authority 'OK (5 tests)'
run_contract K317DirectionalRenderAuthorityInstrumentationTest directional-render-authority 'OK (4 tests)'
run_contract K318DisconnectAuthorityInstrumentationTest disconnect-authority 'OK (4 tests)'
run_contract K319LinearArrayAuthorityInstrumentationTest linear-array-authority 'OK (4 tests)'
run_contract K320TrimAuthorityInstrumentationTest trim-authority 'OK (4 tests)'
run_contract K321ExtendAuthorityInstrumentationTest extend-authority 'OK (4 tests)'
# K3.22 must remain in Production/Consolidated to enforce model-owned stable-ID/history authority.
run_contract K322OffsetAuthorityInstrumentationTest offset-authority 'OK (4 tests)'
run_contract K36dMidpointAuthorityInstrumentationTest midpoint-authority 'OK (5 tests)'
run_contract PinchZoomInstrumentationTest pinch 'OK (1 test)'
run_contract SketchUndoRedoInstrumentationTest sketch-undo-redo 'OK (1 test)'
run_contract SketchPrimitivesSnapInstrumentationTest sketch-primitives 'OK (5 tests)'
run_contract SketchConstraintSolverInstrumentationTest sketch-constraints 'OK (5 tests)'
run_contract SmartCommandSelectionInstrumentationTest command-selection 'OK (1 test)'

# GPU renderer lifecycle
run_contract FilamentLifecycleInstrumentationTest filament-lifecycle 'OK (3 tests)'

# Solid / History / Boolean / Direct modeling + workspace + Revolve thread
run_contract SolidCommandInstrumentationTest solid 'OK (2 tests)'
run_contract BooleanCommandInstrumentationTest boolean 'OK (3 tests)'
run_contract BooleanKeepOriginalsInstrumentationTest boolean-keep 'OK (3 tests)'
run_contract DirectFinishInstrumentationTest direct-finish 'OK (2 tests)'
run_contract ShellPushPullInstrumentationTest shell-pushpull 'OK (2 tests)'
run_contract SweepCommandInstrumentationTest sweep 'OK (1 test)'
run_contract LoftCommandInstrumentationTest loft 'OK (1 test)'
run_contract WorkspaceSessionInstrumentationTest workspace-session 'OK (5 tests)'
run_contract RevolveThreadContractInstrumentationTest revolve-thread 'OK (4 tests)'

# Construction / exact Project / associative references / exact topology
run_contract ConstructionProjectInstrumentationTest construction-project 'OK (2 tests)'
run_contract Project3DExactInstrumentationTest project-exact 'OK (3 tests)'
run_contract Project3DSelectedBodyInstrumentationTest project-selected 'OK (3 tests)'
run_contract AssociativeProjectProvenanceInstrumentationTest project-associative 'OK (4 tests)'
run_contract ExactEdgeTopologyInstrumentationTest exact-edge-topology 'OK (3 tests)'
run_contract ExactFaceTopologyInstrumentationTest exact-face-topology 'OK (6 tests)'
run_contract ExactTopologyIndexInstrumentationTest exact-topology-index 'OK (2 tests)'

# The declared run_contract entries are the source of truth for suite membership.
declared_classes=$(sed -nE 's/^[[:space:]]*run_contract[[:space:]]+([A-Za-z0-9_]+).*/\1/p' "$0" | wc -l | tr -d '[:space:]')
passed_classes=$(grep -c ' | OK (' test-artifacts/production-cad-summary.txt || true)
if [[ "$passed_classes" -ne "$declared_classes" ]]; then
  echo "CONSOLIDATED_COUNT_FAIL passed_classes=${passed_classes} expected=${declared_classes}" | tee -a test-artifacts/production-cad-summary.txt
  exit 1
fi

echo "PRODUCTION_CAD_REGRESSION OK classes=${passed_classes}" | tee -a test-artifacts/production-cad-summary.txt

#!/usr/bin/env bash
set -euo pipefail

mkdir -p test-artifacts
: > test-artifacts/production-cad-summary.txt

adb shell am force-stop com.google.android.apps.nexuslauncher || true
adb shell pm disable-user --user 0 com.google.android.apps.nexuslauncher || true
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk

run_contract() {
  local class="$1"
  local slug="$2"
  local expected="$3"

  adb logcat -c
  set +e
  adb shell am instrument -w -e class "ir.chobyar.sketch.${class}" \
    ir.chobyar.sketch.test/androidx.test.runner.AndroidJUnitRunner \
    | tee "test-artifacts/${slug}-instrumentation.txt"
  local instrument_status=${PIPESTATUS[0]}
  set -e

  adb logcat -d -v brief > "test-artifacts/${slug}-logcat.txt" || true

  if [[ ${instrument_status} -ne 0 ]]; then
    echo "CONTRACT_FAIL class=${class} reason=instrumentation_exit_${instrument_status}" | tee -a test-artifacts/production-cad-summary.txt
    return ${instrument_status}
  fi
  if ! grep -Fq "$expected" "test-artifacts/${slug}-instrumentation.txt"; then
    echo "CONTRACT_FAIL class=${class} reason=missing_expected expected=${expected}" | tee -a test-artifacts/production-cad-summary.txt
    return 1
  fi

  printf '%s | %s\n' "$class" "$expected" | tee -a test-artifacts/production-cad-summary.txt
}

# Sketch interaction, primitives, selection, dimensions and constraints: 16 tests
run_contract TouchInputContractInstrumentationTest touch 'OK (3 tests)'
run_contract PinchZoomInstrumentationTest pinch 'OK (1 test)'
run_contract SketchUndoRedoInstrumentationTest sketch-undo-redo 'OK (1 test)'
run_contract SketchPrimitivesSnapInstrumentationTest sketch-primitives 'OK (5 tests)'
run_contract SketchConstraintSolverInstrumentationTest sketch-constraints 'OK (5 tests)'
run_contract SmartCommandSelectionInstrumentationTest command-selection 'OK (1 test)'

# Solid / History / Boolean / Direct modeling + workspace sessions: 19 tests
run_contract SolidCommandInstrumentationTest solid 'OK (2 tests)'
run_contract BooleanCommandInstrumentationTest boolean 'OK (3 tests)'
run_contract BooleanKeepOriginalsInstrumentationTest boolean-keep 'OK (3 tests)'
run_contract DirectFinishInstrumentationTest direct-finish 'OK (2 tests)'
run_contract ShellPushPullInstrumentationTest shell-pushpull 'OK (2 tests)'
run_contract SweepCommandInstrumentationTest sweep 'OK (1 test)'
run_contract LoftCommandInstrumentationTest loft 'OK (1 test)'
run_contract WorkspaceSessionInstrumentationTest workspace-session 'OK (5 tests)'

# Construction / exact Project / associative references / exact topology: 21 tests
run_contract ConstructionProjectInstrumentationTest construction-project 'OK (2 tests)'
run_contract Project3DExactInstrumentationTest project-exact 'OK (3 tests)'
run_contract Project3DSelectedBodyInstrumentationTest project-selected 'OK (3 tests)'
run_contract AssociativeProjectProvenanceInstrumentationTest project-associative 'OK (4 tests)'
run_contract ExactEdgeTopologyInstrumentationTest exact-edge-topology 'OK (3 tests)'
run_contract ExactFaceTopologyInstrumentationTest exact-face-topology 'OK (6 tests)'
run_contract ExactTopologyIndexInstrumentationTest exact-topology-index 'OK (2 tests)'

passed_classes=$(grep -c ' | OK (' test-artifacts/production-cad-summary.txt || true)
if [[ "$passed_classes" -ne 21 ]]; then
  echo "CONSOLIDATED_COUNT_FAIL passed_classes=${passed_classes} expected=21" | tee -a test-artifacts/production-cad-summary.txt
  exit 1
fi

echo 'PRODUCTION_CAD_REGRESSION OK classes=21 tests=58' | tee -a test-artifacts/production-cad-summary.txt

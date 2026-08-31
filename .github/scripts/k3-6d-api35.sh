#!/usr/bin/env bash
set -euo pipefail

ARTIFACT_DIR="${GITHUB_WORKSPACE}/test-artifacts"
mkdir -p "$ARTIFACT_DIR"
{
  echo "emulator_script_started=true"
  adb version
  adb devices -l
} | tee "${ARTIFACT_DIR}/emulator-entry.txt"

adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk

failures=0
run_class() {
  local cls="$1"
  local short="${cls##*.}"
  local out="${ARTIFACT_DIR}/${short}.txt"
  local tmp="${out}.instrument.tmp"

  # Every interaction contract gets a clean persisted/session surface.
  # This prevents a preceding lifecycle or relationship test from becoming
  # anonymous state authority for the next semantic snap test.
  adb shell pm clear ir.chobyar.sketch >/dev/null || true
  adb shell pm clear ir.chobyar.sketch.test >/dev/null || true

  echo "=== ${cls} ===" | tee "$out"
  set +e
  adb shell am instrument -w -r -e class "$cls" \
    ir.chobyar.sketch.test/androidx.test.runner.AndroidJUnitRunner \
    >"$tmp" 2>&1
  local instrument_status=$?
  set -e
  tee -a "$out" <"$tmp"
  rm -f "$tmp"

  if [[ "$instrument_status" -ne 0 ]] || ! grep -Eq '^OK \([0-9]+ tests?\)$' "$out"; then
    echo "K3.6d FAILURE: ${cls} (instrument_status=${instrument_status})" | tee -a "$out"
    failures=$((failures + 1))
  else
    echo "K3.6d PASS: ${cls}" | tee -a "$out"
  fi
}

run_class ir.chobyar.sketch.K36dAutoConnectionPolicyInstrumentationTest
run_class ir.chobyar.sketch.K36dMidpointAuthorityInstrumentationTest
run_class ir.chobyar.sketch.K36dModelConstraintBadgeProjectionInstrumentationTest
run_class ir.chobyar.sketch.K36dSnapFeedbackPriorityInstrumentationTest
run_class ir.chobyar.sketch.K36dSnapPriorityAndExtensionFeedbackInstrumentationTest
run_class ir.chobyar.sketch.K36dTouchStylusSelectionStabilityInstrumentationTest
run_class ir.chobyar.sketch.K36dProductionEndpointConstraintAuthorityInstrumentationTest
run_class ir.chobyar.sketch.K36dActivityLifecyclePersistenceInstrumentationTest

if [[ "$failures" -ne 0 ]]; then
  echo "K3.6d API35 regression failures: $failures"
  exit 1
fi

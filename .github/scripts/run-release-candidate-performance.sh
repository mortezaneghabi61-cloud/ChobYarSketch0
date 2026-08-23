#!/usr/bin/env bash
set -euo pipefail

mkdir -p test-artifacts
adb install -r "$APK"
adb logcat -c
adb shell am force-stop ir.chobyar.sketch
adb shell am start -W -S -n ir.chobyar.sketch/.ChobYarActivity | tee test-artifacts/cold-launch.txt

awk -F': ' '/TotalTime:/{v=$2} /WaitTime:/{if(v=="") v=$2} END{gsub(/\r/,"",v); if(v=="") exit 1; print "cold_start_observed_ms=" v; if ((v+0) >= 20000) exit 2}' \
  test-artifacts/cold-launch.txt > test-artifacts/performance-baseline.txt
cat test-artifacts/performance-baseline.txt

sleep 3
adb shell pidof ir.chobyar.sketch | tr -d '\r' > test-artifacts/pid.txt
test -s test-artifacts/pid.txt
adb shell dumpsys meminfo ir.chobyar.sketch > test-artifacts/meminfo.txt
PSS_KB=$(awk '/TOTAL PSS:/{print $3; exit}' test-artifacts/meminfo.txt)
test -n "$PSS_KB"
echo "total_pss_kb=$PSS_KB" | tee -a test-artifacts/performance-baseline.txt
test "$PSS_KB" -lt 400000

adb shell dumpsys gfxinfo ir.chobyar.sketch > test-artifacts/gfxinfo.txt
adb logcat -d > test-artifacts/logcat.txt
! grep -E 'FATAL EXCEPTION|ANR in ir\.chobyar\.sketch' test-artifacts/logcat.txt

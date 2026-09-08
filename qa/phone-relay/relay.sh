#!/data/data/com.termux/files/usr/bin/bash
set -u -o pipefail

REPO='mortezaneghabi61-cloud/ChobYarSketch0'
BRANCH='qa/phone-relay-20260908'
COMMAND_PATH='qa/phone-relay/command.json'
RESULT_PATH='qa/phone-relay/result.json'
ROOT="$HOME/.chobyar-phone-relay"
STATE="$ROOT/last_seq"
LOG="$ROOT/relay.log"
mkdir -p "$ROOT"

ts(){ date -u +%Y-%m-%dT%H:%M:%SZ; }
log(){ printf '[%s] %s\n' "$(ts)" "$*" | tee -a "$LOG" >&2; }
need(){ command -v "$1" >/dev/null 2>&1 || { log "missing command: $1"; exit 2; }; }
need gh; need jq; need adb; need base64

fetch_command(){
  gh api "repos/$REPO/contents/$COMMAND_PATH?ref=$BRANCH" --jq .content 2>/dev/null \
    | tr -d '\n' | base64 -d 2>/dev/null
}

upload_file(){
  local repo_path="$1" local_file="$2" message="$3" sha content
  content="$(base64 -w 0 "$local_file")" || return 1
  sha="$(gh api "repos/$REPO/contents/$repo_path?ref=$BRANCH" --jq .sha 2>/dev/null || true)"
  if [ -n "$sha" ]; then
    gh api --method PUT "repos/$REPO/contents/$repo_path" \
      -f message="$message" -f branch="$BRANCH" -f sha="$sha" -f content="$content" >/dev/null
  else
    gh api --method PUT "repos/$REPO/contents/$repo_path" \
      -f message="$message" -f branch="$BRANCH" -f content="$content" >/dev/null
  fi
}

report(){
  local seq="$1" action="$2" status="$3" code="$4" message="$5"
  local f="$ROOT/result.json"
  jq -n --argjson seq "$seq" --arg action "$action" --arg status "$status" \
    --argjson exit_code "$code" --arg at "$(ts)" --arg message "$message" \
    '{version:1,seq:$seq,action:$action,status:$status,exit_code:$exit_code,at:$at,message:$message}' > "$f"
  upload_file "$RESULT_PATH" "$f" "qa(phone): result seq $seq $action" || log "result upload failed"
}

serial(){ cat "$HOME/.chobyar-qa-bridge/state/adb_serial" 2>/dev/null || true; }
require_serial(){
  local s; s="$(serial)"
  [ -n "$s" ] || { printf 'NO_ADB_SERIAL\n'; return 3; }
  adb -s "$s" get-state 2>/dev/null | grep -qx device || { printf 'ADB_NOT_DEVICE serial=%s\n' "$s"; return 4; }
  printf '%s\n' "$s"
}

capture_latest(){
  local out dir
  out="$($HOME/.chobyar-qa-bridge/bin/chobyar-capture 2>&1)" || { printf '%s\n' "$out"; return 5; }
  dir="$(printf '%s\n' "$out" | sed -n 's/^EVIDENCE_DIR=//p' | tail -1)"
  [ -d "$dir" ] || { printf 'EVIDENCE_DIR_MISSING\n'; return 6; }
  upload_file 'qa/phone-relay/evidence/latest-screen.png' "$dir/screen.png" 'qa(phone): latest screen' || return 7
  [ -f "$dir/ui.xml" ] && upload_file 'qa/phone-relay/evidence/latest-ui.xml' "$dir/ui.xml" 'qa(phone): latest ui' || true
  [ -f "$dir/logcat.txt" ] && upload_file 'qa/phone-relay/evidence/latest-logcat.txt' "$dir/logcat.txt" 'qa(phone): latest logcat' || true
  printf '%s\n' "$out"
}

run_action(){
  local json="$1" action seq s out rc
  seq="$(jq -r '.seq // -1' <<<"$json")"
  action="$(jq -r '.action // ""' <<<"$json")"
  case "$action" in
    idle) return 0 ;;
    status)
      out="$(printf 'TIME=%s\n' "$(ts)"; adb version | head -1; adb devices -l; s="$(serial)"; printf 'SAVED_SERIAL=%s\n' "$s"; if [ -n "$s" ]; then adb -s "$s" shell 'printf "MODEL="; getprop ro.product.model; printf "ANDROID="; getprop ro.build.version.release; printf "SDK="; getprop ro.build.version.sdk' 2>&1; adb -s "$s" shell pm path ir.chobyar.sketch 2>&1 || true; fi)"; rc=$? ;;
    launch)
      out="$($HOME/.chobyar-qa-bridge/bin/chobyar-launch 2>&1)"; rc=$? ;;
    capture)
      out="$(capture_latest 2>&1)"; rc=$? ;;
    smoke)
      out="$($HOME/.chobyar-qa-bridge/bin/chobyar-smoke 2>&1)"; rc=$?; if [ "$rc" -eq 0 ]; then more="$(capture_latest 2>&1)"; out="$out\n$more"; fi ;;
    tap)
      s="$(require_serial)"; rc=$?; if [ "$rc" -eq 0 ]; then x="$(jq -r '.args.x' <<<"$json")"; y="$(jq -r '.args.y' <<<"$json")"; case "$x:$y" in (*[!0-9:]*|:*) out='INVALID_TAP_ARGS'; rc=2;; (*) out="$(adb -s "$s" shell input tap "$x" "$y" 2>&1)"; rc=$?;; esac; fi ;;
    swipe)
      s="$(require_serial)"; rc=$?; if [ "$rc" -eq 0 ]; then x1="$(jq -r '.args.x1' <<<"$json")"; y1="$(jq -r '.args.y1' <<<"$json")"; x2="$(jq -r '.args.x2' <<<"$json")"; y2="$(jq -r '.args.y2' <<<"$json")"; ms="$(jq -r '.args.ms // 400' <<<"$json")"; case "$x1:$y1:$x2:$y2:$ms" in (*[!0-9:]*|*::*|:*|*:) out='INVALID_SWIPE_ARGS'; rc=2;; (*) out="$(adb -s "$s" shell input swipe "$x1" "$y1" "$x2" "$y2" "$ms" 2>&1)"; rc=$?;; esac; fi ;;
    install_download)
      file="$(jq -r '.args.file // \"\"' <<<"$json")"
      case "$file" in
        */*|*'..'*|'') out='INVALID_APK_FILENAME'; rc=2 ;;
        *.apk) out="$($HOME/.chobyar-qa-bridge/bin/chobyar-install-apk "$HOME/storage/downloads/$file" 2>&1)"; rc=$? ;;
        *) out='INVALID_APK_FILENAME'; rc=2 ;;
      esac ;;
    stop)
      report "$seq" "$action" ok 0 'relay stopping'; printf '%s\n' "$seq" > "$STATE"; exit 0 ;;
    *) out="UNSUPPORTED_ACTION=$action"; rc=64 ;;
  esac
  [ "${rc:-1}" -eq 0 ] && report "$seq" "$action" ok 0 "${out:-OK}" || report "$seq" "$action" error "${rc:-1}" "${out:-FAILED}"
}

last="$(cat "$STATE" 2>/dev/null || printf '%s' -1)"
log "relay started branch=$BRANCH last_seq=$last"
while :; do
  json="$(fetch_command || true)"
  if jq -e '.version==1 and (.seq|type=="number") and (.action|type=="string")' >/dev/null 2>&1 <<<"$json"; then
    seq="$(jq -r .seq <<<"$json")"
    if [ "$seq" -gt "$last" ]; then
      log "processing seq=$seq action=$(jq -r .action <<<"$json")"
      run_action "$json"
      printf '%s\n' "$seq" > "$STATE"
      last="$seq"
    fi
  fi
  sleep 3
done

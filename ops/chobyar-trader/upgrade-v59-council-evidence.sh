#!/usr/bin/env bash
set -Eeuo pipefail

REPO_URL="https://github.com/mortezaneghabi61-cloud/ChobYarSketch0.git"
EXPECTED_SHA="${1:-}"
APP_DIR="/opt/chobyar-trader"
ENV_FILE="$APP_DIR/.env"
VENV_PYTHON="$APP_DIR/.venv/bin/python"
V5_DIR="$APP_DIR/app/v5"
SHADOW_UNIT="/etc/systemd/system/chobyar-v5-shadow.service"
TRADER_SERVICE="chobyar-trader.service"
STATUS_SERVICE="chobyar-status.service"
SHADOW_TIMER="chobyar-v5-shadow.timer"
SCORECARD_TIMER="chobyar-v5-scorecard.timer"
WRAPPER_TARGET="$V5_DIR/shadow_runner_v52.py"
EVIDENCE_TARGET="$V5_DIR/council_evidence.py"
JOURNAL="$APP_DIR/logs/v5_council_evidence.jsonl"
EXPECTED_OLD_WRAPPER_SHA256="f5515a8f3df67baa370db11cbeff8b7331454d52095eac578267164902f75384"

fail() {
  printf 'FAIL-CLOSED: %s\n' "$*" >&2
  exit 1
}

[[ $EUID -eq 0 ]] || fail "run as root"
[[ "$EXPECTED_SHA" =~ ^[0-9a-f]{40}$ ]] ||
  fail "exact 40-character commit SHA required"
[[ -x "$VENV_PYTHON" && -f "$ENV_FILE" && -f "$WRAPPER_TARGET" ]] ||
  fail "existing v5 installation is incomplete"
[[ ! -e "$EVIDENCE_TARGET" && ! -L "$EVIDENCE_TARGET" ]] ||
  fail "council_evidence.py already exists; investigate before overwrite"
[[ ! -e "$JOURNAL" && ! -L "$JOURNAL" ]] ||
  fail "evidence journal already exists; investigate before overwrite"

for lock in \
  'TRADING_MODE=paper' \
  'LIVE_TRADING_ENABLED=false' \
  'MAX_POSITION_PCT=0.25' \
  'STOP_LOSS_PCT=0.015' \
  'TAKE_PROFIT_PCT=0.03' \
  'MAX_DAILY_LOSS_PCT=0.03'; do
  grep -qxF "$lock" "$ENV_FILE" || fail "safety lock mismatch: $lock"
done

for unit in \
  "$TRADER_SERVICE" \
  "$STATUS_SERVICE" \
  "$SHADOW_TIMER" \
  "$SCORECARD_TIMER"; do
  systemctl is-active --quiet "$unit" || fail "inactive unit: $unit"
done

grep -Eq '^ExecStart=/opt/chobyar-trader/.venv/bin/python /opt/chobyar-trader/app/v5/shadow_runner_v52.py --app-dir /opt/chobyar-trader$' "$SHADOW_UNIT" ||
  fail "unexpected shadow ExecStart"
! grep -Eq '^(Environment|EnvironmentFile|PassEnvironment|LoadCredential)=' "$SHADOW_UNIT" ||
  fail "shadow service must not receive environment credentials"
[[ "$(systemctl show chobyar-v5-shadow.service -p ProtectSystem --value)" == "strict" ]] ||
  fail "shadow ProtectSystem is not strict"
[[ "$(systemctl show chobyar-v5-shadow.service -p NoNewPrivileges --value)" == "yes" ]] ||
  fail "shadow NoNewPrivileges is not enabled"
[[ "$(systemctl show chobyar-v5-shadow.service -p UMask --value)" == "0077" ]] ||
  fail "shadow UMask is not 0077"
read_write_paths="$(systemctl show chobyar-v5-shadow.service -p ReadWritePaths --value)"
[[ "$read_write_paths" == *"/opt/chobyar-trader/logs"* ]] ||
  fail "shadow service cannot write its evidence journal"

available_bytes="$(df -P -B1 "$APP_DIR" | awk 'NR == 2 {print $4}')"
[[ "$available_bytes" =~ ^[0-9]+$ && "$available_bytes" -ge 4294967296 ]] ||
  fail "less than 4 GiB filesystem capacity is available"

current_wrapper_sha256="$(sha256sum "$WRAPPER_TARGET" | awk '{print $1}')"
[[ "$current_wrapper_sha256" == "$EXPECTED_OLD_WRAPPER_SHA256" ]] ||
  fail "active shadow wrapper drifted"

trader_pid_before="$(systemctl show "$TRADER_SERVICE" -p MainPID --value)"
trader_started_before="$(systemctl show "$TRADER_SERVICE" -p ExecMainStartTimestampMonotonic --value)"
status_pid_before="$(systemctl show "$STATUS_SERVICE" -p MainPID --value)"
status_started_before="$(systemctl show "$STATUS_SERVICE" -p ExecMainStartTimestampMonotonic --value)"
[[ "$trader_pid_before" =~ ^[1-9][0-9]*$ ]] || fail "Trader PID is invalid"
[[ "$status_pid_before" =~ ^[1-9][0-9]*$ ]] || fail "Status PID is invalid"

umask 077
work="$(mktemp -d)"
backup="$APP_DIR/backups/v59-council-evidence-$(date -u +%Y%m%dT%H%M%SZ)"
changed=0

rollback() {
  if [[ "$changed" == 1 ]]; then
    cp -a "$backup/shadow_runner_v52.py" "$WRAPPER_TARGET" || true
    rm -f -- "$EVIDENCE_TARGET" "$JOURNAL" || true
  fi
}

cleanup() {
  rc=$?
  trap - EXIT
  if [[ $rc -ne 0 ]]; then
    rollback
  fi
  rm -rf "$work"
  exit "$rc"
}
trap cleanup EXIT

git init -q "$work/repo"
git -C "$work/repo" remote add origin "$REPO_URL"
git -C "$work/repo" fetch -q --depth=1 origin "$EXPECTED_SHA"
git -C "$work/repo" checkout -q --detach FETCH_HEAD
[[ "$(git -C "$work/repo" rev-parse HEAD)" == "$EXPECTED_SHA" ]] ||
  fail "downloaded commit mismatch"

src="$work/repo/ops/chobyar-trader"
candidate_dir="$src/v5"
candidate_wrapper="$candidate_dir/shadow_runner_v52.py"
candidate_evidence="$candidate_dir/council_evidence.py"
for file in \
  "$candidate_wrapper" \
  "$candidate_evidence" \
  "$candidate_dir/test_council_evidence.py" \
  "$candidate_dir/test_shadow_evidence_wiring.py" \
  "$src/upgrade-v59-council-evidence.sh"; do
  [[ -f "$file" ]] || fail "candidate file missing: $file"
done

for baseline in \
  shadow_runner.py \
  specialist_council.py \
  meta_intelligence.py \
  public_source_fallbacks.py; do
  cmp -s "$candidate_dir/$baseline" "$V5_DIR/$baseline" ||
    fail "candidate/deployed baseline mismatch: $baseline"
done

python_files=("$candidate_dir"/*.py)
"$VENV_PYTHON" -m py_compile "${python_files[@]}"
PYTHONPATH="$candidate_dir" "$VENV_PYTHON" -m unittest discover \
  -s "$candidate_dir" -p 'test_*.py' -v
bash -n "$src/upgrade-v59-council-evidence.sh"

if grep -RniE '\.(post|put|patch|delete)\(|submit_order|create_order|place_order|withdraw|enable_live|LIVE_TRADING_ENABLED=true|/api/v5/trade/|martingale|leverage|proxy|vpn' \
  "$candidate_evidence" "$candidate_wrapper" \
  "$candidate_dir/test_council_evidence.py" \
  "$candidate_dir/test_shadow_evidence_wiring.py"; then
  fail "execution, risk, or bypass surface detected"
fi
grep -q 'FULL_FIDELITY_BACKTEST_READY=NO' "$candidate_evidence" ||
  fail "truthful readiness lock is missing"
grep -q 'EXECUTION_AUTHORITY=NONE' "$candidate_evidence" ||
  fail "execution authority lock is missing"

mkdir -p "$backup"
chmod 700 "$backup"
cp -a "$WRAPPER_TARGET" "$backup/shadow_runner_v52.py"
changed=1

install -o root -g root -m 0700 "$candidate_evidence" "$EVIDENCE_TARGET"
install -o root -g root -m 0700 "$candidate_wrapper" "$WRAPPER_TARGET"
cmp -s "$candidate_evidence" "$EVIDENCE_TARGET" || fail "evidence module install mismatch"
cmp -s "$candidate_wrapper" "$WRAPPER_TARGET" || fail "wrapper install mismatch"

# Run only the independent observation-only shadow oneshot once.
systemctl start chobyar-v5-shadow.service
[[ "$(systemctl show chobyar-v5-shadow.service -p Result --value)" == "success" ]] ||
  fail "shadow evidence run failed"
[[ -f "$JOURNAL" && ! -L "$JOURNAL" ]] || fail "evidence journal was not created safely"
[[ "$(stat -c '%a %U:%G' "$JOURNAL")" == "600 root:root" ]] ||
  fail "evidence journal mode or owner mismatch"

PYTHONPATH="$V5_DIR" "$VENV_PYTHON" "$EVIDENCE_TARGET" "$JOURNAL"

"$VENV_PYTHON" - "$APP_DIR/state/v5_shadow_latest.json" <<'PY'
import json
import sys

report = json.load(open(sys.argv[1], encoding="utf-8"))
assert report.get("ok") is True
assert report.get("mode") == "shadow_observation_only"
assert report.get("execution_authority") is False
assert report.get("automatic_promotion_enabled") is False
assert report.get("automatic_reweighting_enabled") is False
assert report.get("foreign_execution_enabled") is False
assert report.get("geo_bypass_supported") is False
assert len(report.get("specialists") or []) == 5
meta = report.get("meta_intelligence") or {}
assert meta.get("execution_authority") is False
print("V59_SHADOW_REPORT=PASS")
print("SHADOW_ACTION=" + str((report.get("shadow_consensus") or {}).get("action")))
print("DATA_INTEGRITY=" + str((meta.get("data_integrity") or {}).get("score")))
PY

trader_pid_after="$(systemctl show "$TRADER_SERVICE" -p MainPID --value)"
trader_started_after="$(systemctl show "$TRADER_SERVICE" -p ExecMainStartTimestampMonotonic --value)"
status_pid_after="$(systemctl show "$STATUS_SERVICE" -p MainPID --value)"
status_started_after="$(systemctl show "$STATUS_SERVICE" -p ExecMainStartTimestampMonotonic --value)"
[[ "$trader_pid_after" == "$trader_pid_before" && "$trader_started_after" == "$trader_started_before" ]] ||
  fail "Trader process changed during v5.9 deployment"
[[ "$status_pid_after" == "$status_pid_before" && "$status_started_after" == "$status_started_before" ]] ||
  fail "Status process changed during v5.9 deployment"

for lock in \
  'TRADING_MODE=paper' \
  'LIVE_TRADING_ENABLED=false' \
  'MAX_POSITION_PCT=0.25' \
  'STOP_LOSS_PCT=0.015' \
  'TAKE_PROFIT_PCT=0.03' \
  'MAX_DAILY_LOSS_PCT=0.03'; do
  grep -qxF "$lock" "$ENV_FILE" || fail "post-install safety drift: $lock"
done

printf 'DEPLOYED_SHA=%s\n' "$EXPECTED_SHA"
printf 'OLD_WRAPPER_SHA256=%s\n' "$current_wrapper_sha256"
printf 'NEW_WRAPPER_SHA256=%s\n' "$(sha256sum "$WRAPPER_TARGET" | awk '{print $1}')"
printf 'EVIDENCE_MODULE_SHA256=%s\n' "$(sha256sum "$EVIDENCE_TARGET" | awk '{print $1}')"
printf 'EVIDENCE_JOURNAL_MODE=%s\n' "$(stat -c '%a %U:%G' "$JOURNAL")"
printf 'TRADER_PID_BEFORE=%s\nTRADER_PID_AFTER=%s\n' "$trader_pid_before" "$trader_pid_after"
printf 'STATUS_PID_BEFORE=%s\nSTATUS_PID_AFTER=%s\n' "$status_pid_before" "$status_pid_after"
printf 'PAPER_MODE=PASS\nLIVE_LOCKED=YES\nRISK_UNCHANGED=YES\n'
printf 'TRADER_RESTARTED=NO\nSTATUS_RESTARTED=NO\n'
printf 'FULL_FIDELITY_BACKTEST_READY=NO\nEXECUTION_AUTHORITY=NONE\n'
changed=0

#!/usr/bin/env bash
set -Eeuo pipefail

REPO_URL="https://github.com/mortezaneghabi61-cloud/ChobYarSketch0.git"
EXPECTED_SHA="${1:-}"
APP_DIR="/opt/chobyar-trader"
ENV_FILE="$APP_DIR/.env"
VENV_PYTHON="$APP_DIR/.venv/bin/python"
V5_DIR="$APP_DIR/app/v5"
JOURNAL="$APP_DIR/logs/v5_council_evidence.jsonl"
EPOCH_ROOT="$APP_DIR/evidence-epochs"
EVIDENCE_TARGET="$V5_DIR/council_evidence.py"
TRADER_SERVICE="chobyar-trader.service"
STATUS_SERVICE="chobyar-status.service"
SHADOW_SERVICE="chobyar-v5-shadow.service"
SHADOW_TIMER="chobyar-v5-shadow.timer"
SCORECARD_TIMER="chobyar-v5-scorecard.timer"
EXPECTED_OLD_EVIDENCE_SHA256="ff8fac2bd92f650f948e14d075d6d95b439d7cebf594de464224ec55451c1765"
ENGINE_FILES=(
  specialist_council.py
  meta_intelligence.py
  shadow_runner.py
  shadow_runner_v52.py
  public_source_fallbacks.py
  council_evidence.py
)

fail() {
  printf 'FAIL-CLOSED: %s\n' "$*" >&2
  exit 1
}

identity() {
  systemctl show "$1" -p MainPID -p ExecMainStartTimestampMonotonic -p InvocationID
}

check_locks() {
  local lock key count
  for lock in \
    TRADING_MODE=paper \
    LIVE_TRADING_ENABLED=false \
    MAX_POSITION_PCT=0.25 \
    STOP_LOSS_PCT=0.015 \
    TAKE_PROFIT_PCT=0.03 \
    MAX_DAILY_LOSS_PCT=0.03; do
    key="${lock%%=*}"
    count="$(grep -cE "^[[:space:]]*(export[[:space:]]+)?${key}[[:space:]]*=" "$ENV_FILE" || true)"
    [[ "$count" == 1 ]] || fail "ambiguous safety lock: $key"
    grep -qxF "$lock" "$ENV_FILE" || fail "safety lock mismatch: $key"
  done
}

wait_for_shadow_idle() {
  local attempt
  for attempt in $(seq 1 60); do
    if ! systemctl is-active --quiet "$SHADOW_SERVICE"; then
      return 0
    fi
    sleep 1
  done
  fail "shadow service did not become idle"
}

[[ $EUID -eq 0 ]] || fail "run as root"
[[ "$EXPECTED_SHA" =~ ^[0-9a-f]{40}$ ]] || fail "exact commit SHA required"
[[ -x "$VENV_PYTHON" && -f "$ENV_FILE" ]] || fail "existing installation is incomplete"
[[ -f "$JOURNAL" && ! -L "$JOURNAL" ]] || fail "active evidence journal is unsafe or missing"
[[ "$(stat -c '%a %U:%G' "$JOURNAL")" == "600 root:root" ]] || fail "journal mode or owner mismatch"
[[ ! -L "$EPOCH_ROOT" ]] || fail "evidence epoch root must not be a symlink"
if [[ -e "$EPOCH_ROOT" ]]; then
  [[ -d "$EPOCH_ROOT" && "$(stat -c '%a %U:%G' "$EPOCH_ROOT")" == "700 root:root" ]] ||
    fail "evidence epoch root is unsafe"
fi
[[ "$(sha256sum "$EVIDENCE_TARGET" | awk '{print $1}')" == "$EXPECTED_OLD_EVIDENCE_SHA256" ]] ||
  fail "active evidence engine is not the reviewed V5.9 epoch"
for name in "${ENGINE_FILES[@]}"; do
  [[ -f "$V5_DIR/$name" && ! -L "$V5_DIR/$name" ]] || fail "unsafe engine file: $name"
done
check_locks
for unit in "$TRADER_SERVICE" "$STATUS_SERVICE" "$SHADOW_TIMER" "$SCORECARD_TIMER"; do
  systemctl is-active --quiet "$unit" || fail "inactive unit: $unit"
done

trader_before="$(identity "$TRADER_SERVICE")"
status_before="$(identity "$STATUS_SERVICE")"
old_journal_sha256="$(sha256sum "$JOURNAL" | awk '{print $1}')"
old_record_count="$(wc -l < "$JOURNAL")"
[[ "$old_record_count" =~ ^[1-9][0-9]*$ ]] || fail "old evidence journal is empty"

umask 077
work="$(mktemp -d)"
timer_stopped=0
engine_installed=0
journal_archived=0
epoch_dir=""

rollback() {
  if [[ "$timer_stopped" == 1 ]]; then
    systemctl stop "$SHADOW_TIMER" >/dev/null 2>&1 || true
    systemctl stop "$SHADOW_SERVICE" >/dev/null 2>&1 || true
  fi
  if [[ "$engine_installed" == 1 && -n "$epoch_dir" && -f "$epoch_dir/council_evidence.py" ]]; then
    if [[ -e "$JOURNAL" || -L "$JOURNAL" ]]; then
      mv -- "$JOURNAL" "$epoch_dir/failed-new-epoch.jsonl" || true
    fi
    install -o root -g root -m 0700 "$epoch_dir/council_evidence.py" "$EVIDENCE_TARGET" || true
  fi
  if [[ "$journal_archived" == 1 && -n "$epoch_dir" && -f "$epoch_dir/v5_council_evidence.jsonl" && ! -e "$JOURNAL" ]]; then
    install -o root -g root -m 0600 "$epoch_dir/v5_council_evidence.jsonl" "$JOURNAL" || true
  fi
  if [[ "$timer_stopped" == 1 ]]; then
    systemctl start "$SHADOW_TIMER" >/dev/null 2>&1 || true
  fi
}

cleanup() {
  local rc=$?
  trap - EXIT
  if [[ $rc -ne 0 ]]; then
    rollback
  fi
  rm -rf -- "$work"
  exit "$rc"
}
trap cleanup EXIT

git init -q "$work/repo"
git -C "$work/repo" remote add origin "$REPO_URL"
git -C "$work/repo" fetch -q --depth=1 origin "$EXPECTED_SHA"
git -C "$work/repo" checkout -q --detach FETCH_HEAD
[[ "$(git -C "$work/repo" rev-parse HEAD)" == "$EXPECTED_SHA" ]] || fail "downloaded commit mismatch"

src="$work/repo/ops/chobyar-trader"
candidate="$src/v5"
for required in \
  "$candidate/council_evidence.py" \
  "$candidate/test_council_evidence.py" \
  "$candidate/evidence_replay_readiness.py" \
  "$src/upgrade-v61-evidence-timestamp-epoch.sh"; do
  [[ -f "$required" ]] || fail "candidate file missing"
done
[[ -x "$src/upgrade-v61-evidence-timestamp-epoch.sh" ]] || fail "candidate migration is not executable"
for name in "${ENGINE_FILES[@]}"; do
  if [[ "$name" != council_evidence.py ]]; then
    cmp -s "$candidate/$name" "$V5_DIR/$name" || fail "unexpected engine drift: $name"
  fi
done
[[ "$(sha256sum "$candidate/council_evidence.py" | awk '{print $1}')" != "$EXPECTED_OLD_EVIDENCE_SHA256" ]] ||
  fail "candidate does not contain the timestamp fix"

python_files=("$candidate"/*.py)
"$VENV_PYTHON" -B -m py_compile "${python_files[@]}"
PYTHONPATH="$candidate" "$VENV_PYTHON" -B -m unittest discover -s "$candidate" -p 'test_*.py' -v
bash -n "$src/upgrade-v61-evidence-timestamp-epoch.sh"
if grep -RniE 'submit_order|create_order|place_order|withdraw|LIVE_TRADING_ENABLED=true|/api/v5/trade/|martingale|leverage|proxy|vpn' \
  "$candidate/council_evidence.py" "$candidate/test_council_evidence.py"; then
  fail "execution, risk, or bypass surface detected"
fi

printf '\n=== VALIDATE_OLD_EPOCH ===\n'
PYTHONPATH="$V5_DIR" "$VENV_PYTHON" -B "$EVIDENCE_TARGET" "$JOURNAL"

systemctl stop "$SHADOW_TIMER"
timer_stopped=1
wait_for_shadow_idle

epoch_stamp="$(date -u +%Y%m%dT%H%M%SZ)"
epoch_dir="$EPOCH_ROOT/v59-$epoch_stamp-${EXPECTED_OLD_EVIDENCE_SHA256:0:12}"
[[ ! -e "$epoch_dir" && ! -L "$epoch_dir" ]] || fail "epoch archive path already exists"
install -d -o root -g root -m 0700 "$EPOCH_ROOT" "$epoch_dir"
for name in "${ENGINE_FILES[@]}"; do
  install -o root -g root -m 0700 "$V5_DIR/$name" "$epoch_dir/$name"
done
mv -- "$JOURNAL" "$epoch_dir/v5_council_evidence.jsonl"
journal_archived=1
[[ "$(sha256sum "$epoch_dir/v5_council_evidence.jsonl" | awk '{print $1}')" == "$old_journal_sha256" ]] ||
  fail "archived journal digest mismatch"
[[ "$(stat -c '%a %U:%G' "$epoch_dir/v5_council_evidence.jsonl")" == "600 root:root" ]] ||
  fail "archived journal mode or owner mismatch"
(
  cd "$epoch_dir"
  sha256sum "${ENGINE_FILES[@]}" v5_council_evidence.jsonl > MANIFEST.sha256
  chmod 0600 MANIFEST.sha256
)
sync -f "$epoch_dir"

printf '\n=== REPLAY_ARCHIVED_EPOCH ===\n'
PYTHONPATH="$epoch_dir" "$VENV_PYTHON" -B "$epoch_dir/council_evidence.py" \
  "$epoch_dir/v5_council_evidence.jsonl"

install -o root -g root -m 0700 "$candidate/council_evidence.py" "$EVIDENCE_TARGET"
engine_installed=1
cmp -s "$candidate/council_evidence.py" "$EVIDENCE_TARGET" || fail "new evidence engine install mismatch"

# Run only the observation-only shadow oneshot. Trader and Status are untouched.
systemctl start "$SHADOW_SERVICE"
[[ "$(systemctl show "$SHADOW_SERVICE" -p Result --value)" == success ]] || fail "new epoch shadow run failed"
[[ -f "$JOURNAL" && ! -L "$JOURNAL" ]] || fail "new epoch journal was not created"
[[ "$(stat -c '%a %U:%G' "$JOURNAL")" == "600 root:root" ]] || fail "new journal mode or owner mismatch"

printf '\n=== VALIDATE_NEW_EPOCH ===\n'
PYTHONPATH="$V5_DIR" "$VENV_PYTHON" -B "$EVIDENCE_TARGET" "$JOURNAL"
# The readiness auditor is a reviewed deployment verifier, not a runtime file.
# Run it from the exact candidate checkout with the exact candidate engine.
PYTHONPATH="$candidate" "$VENV_PYTHON" -B "$candidate/evidence_replay_readiness.py" "$JOURNAL"

check_locks
[[ "$(identity "$TRADER_SERVICE")" == "$trader_before" ]] || fail "Trader process changed"
[[ "$(identity "$STATUS_SERVICE")" == "$status_before" ]] || fail "Status process changed"
(
  cd "$epoch_dir"
  sha256sum -c MANIFEST.sha256
)
systemctl start "$SHADOW_TIMER"
[[ "$(systemctl is-active "$SHADOW_TIMER")" == active ]] || fail "shadow timer was not restored"
timer_stopped=0

printf '\nDEPLOYED_SHA=%s\n' "$EXPECTED_SHA"
printf 'OLD_EPOCH_ARCHIVE=%s\n' "$epoch_dir"
printf 'OLD_EPOCH_RECORDS=%s\n' "$old_record_count"
printf 'OLD_JOURNAL_SHA256=%s\n' "$old_journal_sha256"
printf 'NEW_EVIDENCE_SHA256=%s\n' "$(sha256sum "$EVIDENCE_TARGET" | awk '{print $1}')"
printf 'NEW_EPOCH_RECORDS=%s\n' "$(wc -l < "$JOURNAL")"
printf 'TIMESTAMP_PRECISION_FIX=PASS\nOLD_EPOCH_REPLAY=PASS\nNEW_EPOCH_REPLAY=PASS\n'
printf 'TRADER_RESTARTED=NO\nSTATUS_RESTARTED=NO\nPAPER_MODE=PASS\nRISK_UNCHANGED=YES\n'
printf 'FULL_FIDELITY_BACKTEST_READY=NO\nEXECUTION_AUTHORITY=NONE\n'
engine_installed=0
journal_archived=0

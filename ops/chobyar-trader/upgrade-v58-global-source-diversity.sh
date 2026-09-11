#!/usr/bin/env bash
set -Eeuo pipefail

REPO_URL="https://github.com/mortezaneghabi61-cloud/ChobYarSketch0.git"
EXPECTED_SHA="${1:-}"
APP_DIR="/opt/chobyar-trader"
ENV_FILE="$APP_DIR/.env"
VENV_PYTHON="$APP_DIR/.venv/bin/python"
TRADER_SERVICE="chobyar-trader.service"
STATUS_SERVICE="chobyar-status.service"
SHADOW_TIMER="chobyar-v5-shadow.timer"
SCORECARD_TIMER="chobyar-v5-scorecard.timer"
TARGET="$APP_DIR/app/v5/execution_safety/global_sources.py"
AUDIT="$APP_DIR/logs/audit.jsonl"
SOURCE_PATH="ops/chobyar-trader/v5/execution_safety"
EXPECTED_OLD_SHA256="2fe6ed07a583ee599b72a8feca49351f6ee571545fa15976daff791bfd69d00e"

fail() {
  printf 'FAIL-CLOSED: %s\n' "$*" >&2
  exit 1
}

[[ $EUID -eq 0 ]] || fail "run as root"
[[ "$EXPECTED_SHA" =~ ^[0-9a-f]{40}$ ]] ||
  fail "exact 40-character commit SHA required"
[[ -x "$VENV_PYTHON" && -f "$ENV_FILE" && -f "$TARGET" ]] ||
  fail "existing Trader runtime is incomplete"

for lock in \
  'TRADING_MODE=paper' \
  'LIVE_TRADING_ENABLED=false' \
  'MAX_POSITION_PCT=0.25' \
  'STOP_LOSS_PCT=0.015' \
  'TAKE_PROFIT_PCT=0.03' \
  'MAX_DAILY_LOSS_PCT=0.03'; do
  grep -qx "$lock" "$ENV_FILE" || fail "safety lock mismatch: $lock"
done

for unit in \
  "$TRADER_SERVICE" \
  "$STATUS_SERVICE" \
  "$SHADOW_TIMER" \
  "$SCORECARD_TIMER"; do
  systemctl is-active --quiet "$unit" || fail "inactive unit: $unit"
done

grep -Eq \
  '^ExecStart=/opt/chobyar-trader/.venv/bin/python /opt/chobyar-trader/app/v5/execution_safety/trader_entry.py$' \
  /etc/systemd/system/chobyar-trader.service ||
  fail "unexpected Trader entry point"

current_sha256="$(sha256sum "$TARGET" | awk '{print $1}')"
[[ "$current_sha256" == "$EXPECTED_OLD_SHA256" ]] ||
  fail "active global_sources.py drifted"

trader_pid_before="$(systemctl show "$TRADER_SERVICE" -p MainPID --value)"
trader_started_before="$(
  systemctl show "$TRADER_SERVICE" -p ActiveEnterTimestamp --value
)"
status_pid_before="$(systemctl show "$STATUS_SERVICE" -p MainPID --value)"
status_started_before="$(
  systemctl show "$STATUS_SERVICE" -p ActiveEnterTimestamp --value
)"
[[ "$trader_pid_before" =~ ^[1-9][0-9]*$ ]] ||
  fail "Trader PID is invalid"
[[ "$status_pid_before" =~ ^[1-9][0-9]*$ ]] ||
  fail "Status PID is invalid"

umask 077
work="$(mktemp -d)"
backup="$APP_DIR/backups/v58-global-source-diversity-$(date -u +%Y%m%dT%H%M%SZ)"
changed=0

rollback() {
  if [[ "$changed" == 1 && -f "$backup/global_sources.py" ]]; then
    cp -a "$backup/global_sources.py" "$TARGET" || true
    systemctl restart "$TRADER_SERVICE" || true
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

source_dir="$work/repo/$SOURCE_PATH"
candidate="$source_dir/global_sources.py"
test_file="$source_dir/test_global_sources.py"
[[ -f "$candidate" && -f "$test_file" ]] ||
  fail "candidate source or test missing"

"$VENV_PYTHON" -m py_compile "$candidate" "$test_file"
PYTHONPATH="$source_dir" "$VENV_PYTHON" "$test_file" -v
bash -n "$work/repo/ops/chobyar-trader/upgrade-v58-global-source-diversity.sh"

if grep -nEi \
  '\.(post|put|patch|delete)\(|submit_order|create_order|place_order|withdraw|proxy|vpn|LIVE_TRADING_ENABLED=true|leverage|martingale' \
  "$candidate" "$test_file"; then
  fail "mutable, bypass, live or risk surface detected"
fi

PYTHONPATH="$source_dir" "$VENV_PYTHON" - <<'PY'
import global_sources

price, change, sources, dispersion = global_sources.fetch_global_snapshot()
assert price is not None
assert change is not None
assert len(sources) >= 2
assert len(sources) == len(set(sources))
assert set(sources).issubset({name for name, _url, _params in global_sources.SOURCE_SPECS})
assert dispersion is not None and dispersion <= 0.02
print("PREFLIGHT_SOURCES=" + ",".join(sources))
print("PREFLIGHT_GLOBAL_DIVERSITY=PASS")
PY

mkdir -p "$backup"
chmod 700 "$backup"
cp -a "$TARGET" "$backup/global_sources.py"
audit_offset="$(stat -c %s "$AUDIT" 2>/dev/null || printf '0')"

install -o root -g root -m 0700 "$candidate" "$TARGET"
changed=1
cmp -s "$candidate" "$TARGET" || fail "installed source mismatch"

systemctl restart "$TRADER_SERVICE"
systemctl is-active --quiet "$TRADER_SERVICE" ||
  fail "Trader inactive after restart"

validated=0
for _attempt in {1..12}; do
  if "$VENV_PYTHON" - "$AUDIT" "$audit_offset" >"$work/post-cycle.txt" <<'PY'
import json
import math
import sys
from pathlib import Path

path = Path(sys.argv[1])
offset = int(sys.argv[2])
allowed = {"kucoin", "gateio", "mexc", "bitget", "bybit", "okx", "kraken", "coinbase"}
with path.open("rb") as stream:
    stream.seek(min(offset, path.stat().st_size))
    lines = stream.read().splitlines()

for raw in reversed(lines):
    try:
        row = json.loads(raw)
    except Exception:
        continue
    if not isinstance(row, dict) or row.get("event") != "cycle":
        continue
    sources = row.get("global_sources")
    if not isinstance(sources, list):
        continue
    if len(sources) < 2 or len(sources) != len(set(sources)):
        continue
    if not set(sources).issubset(allowed):
        continue
    values = (
        row.get("global_price"),
        row.get("global_change_24h"),
        row.get("global_dispersion_pct"),
    )
    try:
        numbers = [float(value) for value in values]
    except (TypeError, ValueError):
        continue
    if not all(math.isfinite(value) for value in numbers):
        continue
    if numbers[0] <= 0 or numbers[2] > 0.02:
        continue
    print("ACTIVE_CYCLE_SOURCES=" + ",".join(sources))
    print("ACTIVE_GLOBAL_DIVERSITY=PASS")
    raise SystemExit(0)
raise SystemExit(1)
PY
  then
    validated=1
    break
  fi
  sleep 5
done
[[ "$validated" == 1 ]] || fail "no valid diverse cycle after restart"
cat "$work/post-cycle.txt"

trader_pid_after="$(systemctl show "$TRADER_SERVICE" -p MainPID --value)"
status_pid_after="$(systemctl show "$STATUS_SERVICE" -p MainPID --value)"
status_started_after="$(
  systemctl show "$STATUS_SERVICE" -p ActiveEnterTimestamp --value
)"
[[ "$trader_pid_after" =~ ^[1-9][0-9]*$ ]] ||
  fail "new Trader PID is invalid"
[[ "$trader_pid_after" != "$trader_pid_before" ]] ||
  fail "Trader did not load the candidate in a new process"
[[ "$status_pid_after" == "$status_pid_before" ]] ||
  fail "Status service restarted unexpectedly"
[[ "$status_started_after" == "$status_started_before" ]] ||
  fail "Status start time changed unexpectedly"

for lock in \
  'TRADING_MODE=paper' \
  'LIVE_TRADING_ENABLED=false' \
  'MAX_POSITION_PCT=0.25' \
  'STOP_LOSS_PCT=0.015' \
  'TAKE_PROFIT_PCT=0.03' \
  'MAX_DAILY_LOSS_PCT=0.03'; do
  grep -qx "$lock" "$ENV_FILE" || fail "post-install safety drift: $lock"
done

printf 'DEPLOYED_SHA=%s\n' "$EXPECTED_SHA"
printf 'OLD_SOURCE_SHA256=%s\n' "$current_sha256"
printf 'NEW_SOURCE_SHA256=%s\n' "$(sha256sum "$TARGET" | awk '{print $1}')"
printf 'TRADER_PID_BEFORE=%s\nTRADER_PID_AFTER=%s\n' \
  "$trader_pid_before" "$trader_pid_after"
printf 'TRADER_STARTED_BEFORE=%s\n' "$trader_started_before"
printf 'STATUS_RESTARTED=NO\nPAPER_MODE=PASS\nLIVE_LOCKED=YES\nRISK_UNCHANGED=YES\n'
changed=0

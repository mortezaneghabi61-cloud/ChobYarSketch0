#!/usr/bin/env bash
set -Eeuo pipefail

REPO_URL="https://github.com/mortezaneghabi61-cloud/ChobYarSketch0.git"
EXPECTED_SHA="${1:-}"
APP_DIR="/opt/chobyar-trader"
ENV_FILE="$APP_DIR/.env"
VENV="$APP_DIR/.venv"
TRADER_UNIT="/etc/systemd/system/chobyar-trader.service"
SHADOW_UNIT="/etc/systemd/system/chobyar-v5-shadow.service"
V4_APP="$APP_DIR/app"

die() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }
[[ $EUID -eq 0 ]] || die "run as root"
[[ "$EXPECTED_SHA" =~ ^[0-9a-f]{40}$ ]] || die "exact 40-character commit SHA required"
[[ -f "$ENV_FILE" && -x "$VENV/bin/python" && -f "$TRADER_UNIT" && -f "$SHADOW_UNIT" ]] || die "existing trader/v5 installation incomplete"
[[ -f "$V4_APP/trader.py" && -f "$V4_APP/trader_entry.py" && -f "$V4_APP/global_sources.py" ]] || die "v4.2 runtime baseline missing"
[[ -f "$APP_DIR/state/v5_shadow_latest.json" ]] || die "v5 shadow report missing"

grep -qx 'TRADING_MODE=paper' "$ENV_FILE" || die "FAIL-CLOSED: TRADING_MODE must be paper"
grep -qx 'LIVE_TRADING_ENABLED=false' "$ENV_FILE" || die "FAIL-CLOSED: LIVE_TRADING_ENABLED must be false"
grep -qx 'MAX_POSITION_PCT=0.25' "$ENV_FILE" || die "FAIL-CLOSED: MAX_POSITION_PCT mismatch"
grep -qx 'STOP_LOSS_PCT=0.015' "$ENV_FILE" || die "FAIL-CLOSED: STOP_LOSS_PCT mismatch"
grep -qx 'TAKE_PROFIT_PCT=0.03' "$ENV_FILE" || die "FAIL-CLOSED: TAKE_PROFIT_PCT mismatch"
grep -qx 'MAX_DAILY_LOSS_PCT=0.03' "$ENV_FILE" || die "FAIL-CLOSED: MAX_DAILY_LOSS_PCT mismatch"

systemctl is-active --quiet chobyar-trader.service || die "trader inactive"
systemctl is-active --quiet chobyar-status.service || die "status inactive"
systemctl is-active --quiet chobyar-v5-shadow.timer || die "v5 shadow timer inactive"
systemctl is-active --quiet chobyar-v5-scorecard.timer || die "v5 scorecard timer inactive"
grep -q '/opt/chobyar-trader/app/trader_entry.py' "$TRADER_UNIT" || die "unexpected trader ExecStart"
grep -q '/opt/chobyar-trader/app/v5/shadow_runner_v52.py' "$SHADOW_UNIT" || die "unexpected v5 shadow ExecStart"
! grep -q 'EnvironmentFile' "$SHADOW_UNIT" || die "shadow service must not load .env"

status_pid_before="$(systemctl show chobyar-status.service -p MainPID --value)"
status_started_before="$(systemctl show chobyar-status.service -p ExecMainStartTimestampMonotonic --value)"
trader_exec_before="$(grep -E '^ExecStart=' "$TRADER_UNIT" || true)"
shadow_exec_before="$(grep -E '^ExecStart=' "$SHADOW_UNIT" || true)"
[[ "$status_pid_before" =~ ^[1-9][0-9]*$ ]] || die "status service is not running"
[[ -n "$trader_exec_before" && -n "$shadow_exec_before" ]] || die "ExecStart line missing"

umask 077
work="$(mktemp -d)"
backup="$APP_DIR/backups/v55-entry-gate-$(date -u +%Y%m%dT%H%M%SZ)"
changed=0
had_gate=0

rollback() {
  if [[ "$changed" == 1 ]]; then
    cp -a "$backup/trader_entry.py" "$V4_APP/trader_entry.py" || true
    if [[ "$had_gate" == 1 ]]; then
      cp -a "$backup/entry_gate_v55.py" "$V4_APP/entry_gate_v55.py" || true
    else
      rm -f "$V4_APP/entry_gate_v55.py"
    fi
    systemctl restart chobyar-trader.service || true
  fi
}
cleanup() {
  rc=$?
  if [[ $rc -ne 0 ]]; then rollback; fi
  rm -rf "$work"
  exit "$rc"
}
trap cleanup EXIT

git init -q "$work/repo"
git -C "$work/repo" remote add origin "$REPO_URL"
git -C "$work/repo" fetch -q --depth=1 origin "$EXPECTED_SHA"
git -C "$work/repo" checkout -q --detach FETCH_HEAD
[[ "$(git -C "$work/repo" rev-parse HEAD)" == "$EXPECTED_SHA" ]] || die "downloaded commit mismatch"
src="$work/repo/ops/chobyar-trader"
for f in v4/trader.py v4/trader_entry.py v4/global_sources.py v4/entry_gate_v55.py v4/test_entry_gate_v55.py v4/test_v4.py v4/test_global_sources.py upgrade-v55-paper-entry-gate.sh; do
  [[ -f "$src/$f" ]] || die "candidate missing $f"
done

"$VENV/bin/python" -m py_compile "$src/v4/trader.py" "$src/v4/trader_entry.py" "$src/v4/global_sources.py" "$src/v4/entry_gate_v55.py"
PYTHONPATH="$src/v4" "$VENV/bin/python" "$src/v4/test_entry_gate_v55.py" -v
PYTHONPATH="$src/v4" "$VENV/bin/python" "$src/v4/test_v4.py" -v
PYTHONPATH="$src/v4" "$VENV/bin/python" "$src/v4/test_global_sources.py" -v
bash -n "$src/upgrade-v55-paper-entry-gate.sh"

if grep -RniE 'submit_order|create_order|place_order|withdraw|martingale|leverage|enable_live|LIVE_TRADING_ENABLED=true' \
  "$src/v4/entry_gate_v55.py" "$src/v4/trader_entry.py"; then
  die "forbidden live/withdrawal/leverage marker in v5.5 production code"
fi
grep -q 'if action != "BUY"' "$src/v4/trader_entry.py" || die "exit-path bypass contract missing"
grep -q 'return action, score, reason' "$src/v4/trader_entry.py" || die "original non-BUY path not preserved"
grep -q 'v5_report_stale' "$src/v4/entry_gate_v55.py" || die "freshness fail-closed contract missing"
grep -q 'v4_tape_conflicts_with_buy' "$src/v4/entry_gate_v55.py" || die "Tape conflict gate missing"
grep -q 'v5_final_action_not_buy' "$src/v4/entry_gate_v55.py" || die "v5 BUY confirmation contract missing"

# Refresh the read-only shadow once so deployment validates a fresh v5 contract.
systemctl start chobyar-v5-shadow.service
"$VENV/bin/python" - "$APP_DIR/state/v5_shadow_latest.json" <<'PY'
import json, sys, time
from datetime import datetime
r = json.load(open(sys.argv[1], encoding='utf-8'))
assert r.get('ok') is True
assert r.get('mode') == 'shadow_observation_only'
assert r.get('execution_authority') is False
c = r.get('shadow_consensus') or {}
assert c.get('action') in {'BUY','SELL','WAIT'}
assert c.get('pre_meta_action') in {'BUY','SELL','WAIT'}
assert int(c.get('available_directional_specialists') or 0) >= 3
assert len(r.get('specialists') or []) == 5
m = r.get('meta_intelligence') or {}
assert isinstance(m.get('data_integrity'), dict)
ts = datetime.fromisoformat(str(r['generated_at_utc']).replace('Z','+00:00')).timestamp()
assert -60 <= time.time() - ts <= 660
print('V5_ENTRY_SOURCE_CONTRACT=PASS')
print('CURRENT_V5_ACTION=' + str(c.get('action')))
print('CURRENT_META_HOLD=' + str(bool(c.get('meta_hold'))).upper())
PY

mkdir -p "$backup"
chmod 700 "$backup"
cp -a "$V4_APP/trader_entry.py" "$backup/trader_entry.py"
if [[ -f "$V4_APP/entry_gate_v55.py" ]]; then
  had_gate=1
  cp -a "$V4_APP/entry_gate_v55.py" "$backup/entry_gate_v55.py"
fi
changed=1

install -m 700 "$src/v4/entry_gate_v55.py" "$V4_APP/entry_gate_v55.py"
install -m 700 "$src/v4/trader_entry.py" "$V4_APP/trader_entry.py"

# Intentional Trader-only restart: Paper state is persisted; Status and v5 units are untouched.
systemctl restart chobyar-trader.service
sleep 35
systemctl is-active --quiet chobyar-trader.service || die "trader inactive after v5.5"
systemctl is-active --quiet chobyar-status.service || die "status inactive after v5.5"
systemctl is-active --quiet chobyar-v5-shadow.timer || die "v5 shadow timer inactive after v5.5"
systemctl is-active --quiet chobyar-v5-scorecard.timer || die "v5 scorecard timer inactive after v5.5"

status_pid_after="$(systemctl show chobyar-status.service -p MainPID --value)"
status_started_after="$(systemctl show chobyar-status.service -p ExecMainStartTimestampMonotonic --value)"
trader_exec_after="$(grep -E '^ExecStart=' "$TRADER_UNIT" || true)"
shadow_exec_after="$(grep -E '^ExecStart=' "$SHADOW_UNIT" || true)"
[[ "$status_pid_after" == "$status_pid_before" && "$status_started_after" == "$status_started_before" ]] || die "FAIL-CLOSED: status restarted during v5.5"
[[ "$trader_exec_after" == "$trader_exec_before" ]] || die "FAIL-CLOSED: trader ExecStart changed"
[[ "$shadow_exec_after" == "$shadow_exec_before" ]] || die "FAIL-CLOSED: shadow ExecStart changed"

grep -qx 'TRADING_MODE=paper' "$ENV_FILE" || die "paper mode changed"
grep -qx 'LIVE_TRADING_ENABLED=false' "$ENV_FILE" || die "live lock changed"
grep -qx 'MAX_POSITION_PCT=0.25' "$ENV_FILE" || die "risk changed"
grep -qx 'STOP_LOSS_PCT=0.015' "$ENV_FILE" || die "risk changed"
grep -qx 'TAKE_PROFIT_PCT=0.03' "$ENV_FILE" || die "risk changed"
grep -qx 'MAX_DAILY_LOSS_PCT=0.03' "$ENV_FILE" || die "risk changed"

printf 'DEPLOYED_SHA=%s\nPAPER_STATUS=PASS\nLIVE_GATE=LOCKED\nV5_ENTRY_GATE=ACTIVE\nV5_CONFIRMATION_REQUIRED=YES\nTAPE_SELL_BLOCKS_NEW_BUY=YES\nEXITS_UNCHANGED=YES\nRISK=UNCHANGED\nSTATUS_RESTARTED=NO\nTRADER_RESTARTED=YES\nTRADER_EXECSTART=UNCHANGED\nSHADOW_EXECSTART=UNCHANGED\n' "$EXPECTED_SHA"
changed=0

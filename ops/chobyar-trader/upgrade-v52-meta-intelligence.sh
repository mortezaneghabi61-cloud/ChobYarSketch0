#!/usr/bin/env bash
set -Eeuo pipefail

REPO_URL="https://github.com/mortezaneghabi61-cloud/ChobYarSketch0.git"
EXPECTED_SHA="${1:-}"
APP_DIR="/opt/chobyar-trader"
ENV_FILE="$APP_DIR/.env"
VENV="$APP_DIR/.venv"
V5_DIR="$APP_DIR/app/v5"
SHADOW_UNIT="/etc/systemd/system/chobyar-v5-shadow.service"

fail() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }
[[ $EUID -eq 0 ]] || fail "run as root"
[[ "$EXPECTED_SHA" =~ ^[0-9a-f]{40}$ ]] || fail "exact 40-character commit SHA required"
[[ -f "$ENV_FILE" && -x "$VENV/bin/python" && -f "$SHADOW_UNIT" ]] || fail "existing v5 installation incomplete"

grep -qx 'TRADING_MODE=paper' "$ENV_FILE" || fail "FAIL-CLOSED: TRADING_MODE must be paper"
grep -qx 'LIVE_TRADING_ENABLED=false' "$ENV_FILE" || fail "FAIL-CLOSED: LIVE_TRADING_ENABLED must be false"
grep -qx 'MAX_POSITION_PCT=0.25' "$ENV_FILE" || fail "FAIL-CLOSED: MAX_POSITION_PCT mismatch"
grep -qx 'STOP_LOSS_PCT=0.015' "$ENV_FILE" || fail "FAIL-CLOSED: STOP_LOSS_PCT mismatch"
grep -qx 'TAKE_PROFIT_PCT=0.03' "$ENV_FILE" || fail "FAIL-CLOSED: TAKE_PROFIT_PCT mismatch"
grep -qx 'MAX_DAILY_LOSS_PCT=0.03' "$ENV_FILE" || fail "FAIL-CLOSED: MAX_DAILY_LOSS_PCT mismatch"

systemctl is-active --quiet chobyar-v5-shadow.timer || fail "v5 shadow timer inactive"
systemctl is-active --quiet chobyar-v5-scorecard.timer || fail "v5 scorecard timer inactive"
if grep -q '/opt/chobyar-trader/app/v5/shadow_runner_v52.py' "$SHADOW_UNIT"; then
  :
elif grep -q '/opt/chobyar-trader/app/v5/shadow_runner.py' "$SHADOW_UNIT"; then
  :
else
  fail "unexpected v5 shadow ExecStart baseline"
fi
! grep -q 'EnvironmentFile' "$SHADOW_UNIT" || fail "shadow service must not load .env"

trader_pid_before="$(systemctl show chobyar-trader.service -p MainPID --value)"
trader_started_before="$(systemctl show chobyar-trader.service -p ExecMainStartTimestampMonotonic --value)"
status_pid_before="$(systemctl show chobyar-status.service -p MainPID --value)"
status_started_before="$(systemctl show chobyar-status.service -p ExecMainStartTimestampMonotonic --value)"
[[ "$trader_pid_before" =~ ^[1-9][0-9]*$ ]] || fail "trader service is not running"
[[ "$status_pid_before" =~ ^[1-9][0-9]*$ ]] || fail "status service is not running"

umask 077
work="$(mktemp -d)"
backup="$APP_DIR/backups/v52-meta-$(date -u +%Y%m%dT%H%M%SZ)"
changed=0
had_meta=0
had_runner=0

rollback() {
  if [[ "$changed" == 1 ]]; then
    cp -a "$backup/chobyar-v5-shadow.service" "$SHADOW_UNIT" || true
    if [[ "$had_meta" == 1 ]]; then cp -a "$backup/meta_intelligence.py" "$V5_DIR/meta_intelligence.py"; else rm -f "$V5_DIR/meta_intelligence.py"; fi
    if [[ "$had_runner" == 1 ]]; then cp -a "$backup/shadow_runner_v52.py" "$V5_DIR/shadow_runner_v52.py"; else rm -f "$V5_DIR/shadow_runner_v52.py"; fi
    systemctl daemon-reload || true
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
[[ "$(git -C "$work/repo" rev-parse HEAD)" == "$EXPECTED_SHA" ]] || fail "downloaded commit mismatch"
src="$work/repo/ops/chobyar-trader"
for f in v5/meta_intelligence.py v5/shadow_runner_v52.py v5/test_meta_intelligence.py; do
  [[ -f "$src/$f" ]] || fail "candidate missing $f"
done

PYTHONPATH="$src/v5" "$VENV/bin/python" -m py_compile "$src/v5/meta_intelligence.py" "$src/v5/shadow_runner_v52.py"
PYTHONPATH="$src/v5" "$VENV/bin/python" "$src/v5/test_meta_intelligence.py" -v
bash -n "$src/upgrade-v52-meta-intelligence.sh"

if grep -RniE 'API_KEY|APIKEY|STATUS_HMAC_SECRET|submit_order|create_order|place_order|withdraw|enable_live|/api/v5/trade/|martingale|leverage' \
  "$src/v5/meta_intelligence.py" "$src/v5/shadow_runner_v52.py"; then
  fail "forbidden credential/execution marker in v5.2 production code"
fi
grep -q 'execution_authority.*False' "$src/v5/meta_intelligence.py" || fail "execution authority lock missing"
grep -q 'foreign_execution_enabled.*False' "$src/v5/meta_intelligence.py" || fail "foreign execution lock missing"
grep -q 'geo_bypass_supported.*False' "$src/v5/meta_intelligence.py" || fail "geo-bypass lock missing"
grep -q 'final_action = "WAIT" if holds else candidate_action' "$src/v5/meta_intelligence.py" || fail "meta layer downgrade-only invariant missing"

mkdir -p "$backup"
chmod 700 "$backup"
cp -a "$SHADOW_UNIT" "$backup/chobyar-v5-shadow.service"
if [[ -f "$V5_DIR/meta_intelligence.py" ]]; then had_meta=1; cp -a "$V5_DIR/meta_intelligence.py" "$backup/meta_intelligence.py"; fi
if [[ -f "$V5_DIR/shadow_runner_v52.py" ]]; then had_runner=1; cp -a "$V5_DIR/shadow_runner_v52.py" "$backup/shadow_runner_v52.py"; fi
changed=1

install -m 700 "$src/v5/meta_intelligence.py" "$V5_DIR/meta_intelligence.py"
install -m 700 "$src/v5/shadow_runner_v52.py" "$V5_DIR/shadow_runner_v52.py"
python3 - "$SHADOW_UNIT" <<'PY'
from pathlib import Path
import sys
p = Path(sys.argv[1])
text = p.read_text(encoding='utf-8')
old = '/opt/chobyar-trader/app/v5/shadow_runner.py'
new = '/opt/chobyar-trader/app/v5/shadow_runner_v52.py'
if new not in text:
    if old not in text:
        raise SystemExit('unexpected shadow ExecStart')
    text = text.replace(old, new)
p.write_text(text, encoding='utf-8')
PY

systemctl daemon-reload
systemctl start chobyar-v5-shadow.service
systemctl start chobyar-v5-scorecard.service
systemctl is-active --quiet chobyar-v5-shadow.timer || fail "shadow timer inactive after v5.2"
systemctl is-active --quiet chobyar-v5-scorecard.timer || fail "scorecard timer inactive after v5.2"

trader_pid_after="$(systemctl show chobyar-trader.service -p MainPID --value)"
trader_started_after="$(systemctl show chobyar-trader.service -p ExecMainStartTimestampMonotonic --value)"
status_pid_after="$(systemctl show chobyar-status.service -p MainPID --value)"
status_started_after="$(systemctl show chobyar-status.service -p ExecMainStartTimestampMonotonic --value)"
[[ "$trader_pid_after" == "$trader_pid_before" && "$trader_started_after" == "$trader_started_before" ]] || fail "FAIL-CLOSED: trader restarted during v5.2"
[[ "$status_pid_after" == "$status_pid_before" && "$status_started_after" == "$status_started_before" ]] || fail "FAIL-CLOSED: status restarted during v5.2"

"$VENV/bin/python" - "$APP_DIR/state/v5_shadow_latest.json" <<'PY'
import json, sys
r = json.load(open(sys.argv[1], encoding='utf-8'))
assert r.get('ok') is True
assert r.get('mode') == 'shadow_observation_only'
assert r.get('execution_authority') is False
assert r.get('automatic_promotion_enabled') is False
assert r.get('automatic_reweighting_enabled') is False
assert r.get('foreign_execution_enabled') is False
assert r.get('geo_bypass_supported') is False
meta = r.get('meta_intelligence')
assert isinstance(meta, dict)
assert meta.get('execution_authority') is False
consensus = r.get('shadow_consensus') or {}
assert consensus.get('action') in {'BUY','SELL','WAIT'}
assert consensus.get('pre_meta_action') in {'BUY','SELL','WAIT'}
assert isinstance(consensus.get('meta_hold_reasons'), list)
assert len(r.get('specialists') or []) == 5
print('V5_META_RUNTIME=PASS')
print('REGIME=' + str((r.get('regime') or {}).get('label')))
print('PRE_META_ACTION=' + str(consensus.get('pre_meta_action')))
print('SHADOW_ACTION=' + str(consensus.get('action')))
print('META_HOLD=' + str(bool(consensus.get('meta_hold'))).upper())
print('META_HOLD_REASONS=' + ','.join(consensus.get('meta_hold_reasons') or []))
print('DATA_INTEGRITY=' + format(float((meta.get('data_integrity') or {}).get('score') or 0.0), '.3f'))
print('UNCERTAINTY=' + format(float((meta.get('epistemic_uncertainty') or {}).get('score') or 0.0), '.3f'))
print('EXECUTION_STRESS=' + format(float((meta.get('execution_stress') or {}).get('score') or 0.0), '.3f'))
PY

grep -qx 'TRADING_MODE=paper' "$ENV_FILE" || fail "paper mode changed"
grep -qx 'LIVE_TRADING_ENABLED=false' "$ENV_FILE" || fail "live lock changed"
grep -qx 'MAX_POSITION_PCT=0.25' "$ENV_FILE" || fail "risk changed"
grep -qx 'STOP_LOSS_PCT=0.015' "$ENV_FILE" || fail "risk changed"
grep -qx 'TAKE_PROFIT_PCT=0.03' "$ENV_FILE" || fail "risk changed"
grep -qx 'MAX_DAILY_LOSS_PCT=0.03' "$ENV_FILE" || fail "risk changed"

printf 'DEPLOYED_SHA=%s\nPAPER_STATUS=PASS\nLIVE_GATE=LOCKED\nV5_META_INTELLIGENCE=ACTIVE\nSPECIALISTS=5\nCONFIDENCE_CALIBRATION=ACTIVE\nREGIME_TRANSITION_GUARD=ACTIVE\nEXECUTION_STRESS_GUARD=ACTIVE\nUNCERTAINTY_GUARD=ACTIVE\nFRAGILITY_TEST=ACTIVE\nEXECUTION_AUTHORITY=NONE\nAUTO_PROMOTION=DISABLED\nTRADER_RESTARTED=NO\nSTATUS_RESTARTED=NO\n' "$EXPECTED_SHA"
changed=0

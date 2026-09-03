#!/usr/bin/env bash
set -Eeuo pipefail

REPO_URL="https://github.com/mortezaneghabi61-cloud/ChobYarSketch0.git"
EXPECTED_SHA="${1:-}"
PUBLIC_IP="109.122.247.214"
APP_DIR="/opt/chobyar-trader"
ENV_FILE="$APP_DIR/.env"
VENV="$APP_DIR/.venv"
STATUS_UNIT="/etc/systemd/system/chobyar-status.service"
SHADOW_UNIT="/etc/systemd/system/chobyar-v5-shadow.service"
MONITOR_DIR="$APP_DIR/monitor"

fail() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }
[[ $EUID -eq 0 ]] || fail "run as root"
[[ "$EXPECTED_SHA" =~ ^[0-9a-f]{40}$ ]] || fail "exact 40-character commit SHA required"
[[ -f "$ENV_FILE" && -x "$VENV/bin/python" && -f "$STATUS_UNIT" && -f "$SHADOW_UNIT" ]] || fail "existing installation incomplete"
[[ -f "$APP_DIR/app/status_server_v51.py" ]] || fail "v5.1 status baseline missing"
grep -q '/opt/chobyar-trader/app/status_server_v51.py\|/opt/chobyar-trader/app/status_server_v53.py' "$STATUS_UNIT" || fail "status service is not on v5.1/v5.3 baseline"
grep -q '/opt/chobyar-trader/app/v5/shadow_runner_v52.py' "$SHADOW_UNIT" || fail "v5.2 shadow baseline missing"
! grep -q 'EnvironmentFile' "$SHADOW_UNIT" || fail "shadow service must not load .env"
systemctl is-active --quiet chobyar-v5-shadow.timer || fail "v5 shadow timer inactive"
systemctl is-active --quiet chobyar-v5-scorecard.timer || fail "v5 scorecard timer inactive"

for line in \
  'TRADING_MODE=paper' \
  'LIVE_TRADING_ENABLED=false' \
  'MAX_POSITION_PCT=0.25' \
  'STOP_LOSS_PCT=0.015' \
  'TAKE_PROFIT_PCT=0.03' \
  'MAX_DAILY_LOSS_PCT=0.03'; do
  grep -qx "$line" "$ENV_FILE" || fail "FAIL-CLOSED: expected $line"
done

trader_pid_before="$(systemctl show chobyar-trader.service -p MainPID --value)"
trader_started_before="$(systemctl show chobyar-trader.service -p ExecMainStartTimestampMonotonic --value)"
shadow_exec_before="$(grep '^ExecStart=' "$SHADOW_UNIT")"
[[ "$trader_pid_before" =~ ^[1-9][0-9]*$ ]] || fail "trader service is not running"

umask 077
work="$(mktemp -d)"
backup="$APP_DIR/backups/v53-meta-monitor-$(date -u +%Y%m%dT%H%M%SZ)"
changed=0
had_v53=0
had_meta_js=0
had_meta_css=0

rollback() {
  if [[ "$changed" == 1 ]]; then
    cp -a "$backup/chobyar-status.service" "$STATUS_UNIT" || true
    cp -a "$backup/sw.js" "$MONITOR_DIR/sw.js" || true
    if [[ "$had_v53" == 1 ]]; then cp -a "$backup/status_server_v53.py" "$APP_DIR/app/status_server_v53.py" || true; else rm -f "$APP_DIR/app/status_server_v53.py"; fi
    if [[ "$had_meta_js" == 1 ]]; then cp -a "$backup/meta_monitor.js" "$MONITOR_DIR/meta_monitor.js" || true; else rm -f "$MONITOR_DIR/meta_monitor.js"; fi
    if [[ "$had_meta_css" == 1 ]]; then cp -a "$backup/meta_monitor.css" "$MONITOR_DIR/meta_monitor.css" || true; else rm -f "$MONITOR_DIR/meta_monitor.css"; fi
    systemctl daemon-reload || true
    systemctl restart chobyar-status.service || true
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
for f in v3/status_server_v53.py v3/test_status_meta.py monitor/meta_monitor.js monitor/meta_monitor.css monitor/sw.js; do
  [[ -f "$src/$f" ]] || fail "candidate missing $f"
done

PYTHONPATH="$src/v3" "$VENV/bin/python" -m py_compile "$src/v3/status_server_v53.py"
PYTHONPATH="$src/v3" "$VENV/bin/python" "$src/v3/test_status_meta.py" -v
bash -n "$src/upgrade-v53-meta-monitor.sh"

if grep -RniE 'API_KEY|APIKEY|STATUS_HMAC_SECRET|submit_order|create_order|place_order|withdraw|enable_live|/api/v5/trade/' \
  "$src/v3/status_server_v53.py" "$src/monitor/meta_monitor.js" "$src/monitor/meta_monitor.css"; then
  fail "forbidden secret/execution marker in v5.3 production monitor"
fi
grep -q "execution_authority': False" "$src/v3/status_server_v53.py" || fail "read-only execution lock missing"
grep -q 'READ ONLY' "$src/monitor/meta_monitor.js" || fail "read-only UI marker missing"
grep -q 'Meta Hold' "$src/monitor/meta_monitor.js" || fail "meta hold UI missing"
grep -q 'chobyar-monitor-shell-v5' "$src/monitor/sw.js" || fail "monitor cache v5 missing"
grep -q 'cache: "no-store"' "$src/monitor/sw.js" || fail "public report network-only marker missing"

mkdir -p "$backup"
chmod 700 "$backup"
cp -a "$STATUS_UNIT" "$backup/chobyar-status.service"
cp -a "$MONITOR_DIR/sw.js" "$backup/sw.js"
if [[ -f "$APP_DIR/app/status_server_v53.py" ]]; then had_v53=1; cp -a "$APP_DIR/app/status_server_v53.py" "$backup/status_server_v53.py"; fi
if [[ -f "$MONITOR_DIR/meta_monitor.js" ]]; then had_meta_js=1; cp -a "$MONITOR_DIR/meta_monitor.js" "$backup/meta_monitor.js"; fi
if [[ -f "$MONITOR_DIR/meta_monitor.css" ]]; then had_meta_css=1; cp -a "$MONITOR_DIR/meta_monitor.css" "$backup/meta_monitor.css"; fi

install -m 700 "$src/v3/status_server_v53.py" "$APP_DIR/app/status_server_v53.py"
install -m 644 "$src/monitor/meta_monitor.js" "$MONITOR_DIR/meta_monitor.js"
install -m 644 "$src/monitor/meta_monitor.css" "$MONITOR_DIR/meta_monitor.css"
install -m 644 "$src/monitor/sw.js" "$MONITOR_DIR/sw.js"

python3 - "$STATUS_UNIT" <<'PY'
from pathlib import Path
import sys
p = Path(sys.argv[1])
text = p.read_text(encoding='utf-8')
v51 = '/opt/chobyar-trader/app/status_server_v51.py'
v53 = '/opt/chobyar-trader/app/status_server_v53.py'
if v53 not in text:
    if v51 not in text:
        raise SystemExit('unexpected status ExecStart')
    text = text.replace(v51, v53)
p.write_text(text, encoding='utf-8')
PY

changed=1
systemctl daemon-reload
systemctl restart chobyar-status.service
sleep 3
systemctl is-active --quiet chobyar-trader.service || fail "trader service inactive"
systemctl is-active --quiet chobyar-status.service || fail "status service inactive"
systemctl is-active --quiet chobyar-v5-shadow.timer || fail "v5 shadow timer inactive"
systemctl is-active --quiet chobyar-v5-scorecard.timer || fail "v5 scorecard timer inactive"

grep -q '/opt/chobyar-trader/app/v5/shadow_runner_v52.py' "$SHADOW_UNIT" || fail "shadow runner changed"
[[ "$(grep '^ExecStart=' "$SHADOW_UNIT")" == "$shadow_exec_before" ]] || fail "shadow ExecStart changed"
trader_pid_after="$(systemctl show chobyar-trader.service -p MainPID --value)"
trader_started_after="$(systemctl show chobyar-trader.service -p ExecMainStartTimestampMonotonic --value)"
[[ "$trader_pid_after" == "$trader_pid_before" && "$trader_started_after" == "$trader_started_before" ]] || fail "FAIL-CLOSED: trader restarted during v5.3 monitor upgrade"

port="$(awk -F= '$1=="STATUS_PORT" {print $2}' "$ENV_FILE" | tail -n1)"
port="${port:-8787}"
base_url="http://127.0.0.1:${port}"
[[ "$(curl -sS -o /dev/null -w '%{http_code}' "$base_url/health")" == 200 ]] || fail "health validation failed"
[[ "$(curl -sS -o /dev/null -w '%{http_code}' "$base_url/status")" == 401 ]] || fail "unauthenticated status was not rejected"
curl -fsS "$base_url/public-report" -o "$work/report.json"
curl -fsS "$base_url/monitor/app.js" -o "$work/app.js"
curl -fsS "$base_url/monitor/style.css" -o "$work/style.css"
curl -fsS "$base_url/monitor/sw.js" -o "$work/sw.js"

"$VENV/bin/python" - "$work/report.json" <<'PY'
import json, sys
r = json.load(open(sys.argv[1], encoding='utf-8'))
assert r.get('ok') is True and r.get('public_report') is True
assert r.get('report_version') == 6
assert r.get('mode') == 'paper' and r.get('live_locked') is True
assert not ({'paper','market','risk','security'} & set(r))
meta = r.get('v5_meta')
assert isinstance(meta, dict)
assert meta.get('mode') == 'shadow_observation_only'
assert meta.get('execution_authority') is False
assert meta.get('pre_meta_action') in {'BUY','SELL','WAIT'}
assert meta.get('final_action') in {'BUY','SELL','WAIT'}
assert isinstance(meta.get('meta_hold_reasons'), list)
for key in ('data_integrity','regime_transition','execution_stress','uncertainty','fragility'):
    assert isinstance(meta.get(key), dict), key
print('V5_META_MONITOR_REPORT=PASS')
print('PRE_META_ACTION=' + str(meta.get('pre_meta_action')))
print('FINAL_ACTION=' + str(meta.get('final_action')))
print('META_HOLD=' + str(bool(meta.get('meta_hold'))).upper())
print('META_HOLD_REASONS=' + ','.join(meta.get('meta_hold_reasons') or []))
print('DATA_INTEGRITY=' + str((meta.get('data_integrity') or {}).get('score')))
print('UNCERTAINTY=' + str((meta.get('uncertainty') or {}).get('score')))
print('EXECUTION_STRESS=' + str((meta.get('execution_stress') or {}).get('score')))
print('FRAGILE=' + str(bool((meta.get('fragility') or {}).get('fragile'))).upper())
PY

grep -q 'v5MetaPanel' "$work/app.js" || fail "served app missing meta panel"
grep -q 'v5-meta-panel' "$work/style.css" || fail "served style missing meta panel"
grep -q 'chobyar-monitor-shell-v5' "$work/sw.js" || fail "served service worker is not v5"

curl -fsS --connect-timeout 8 --max-time 20 "https://${PUBLIC_IP}/public-report" -o "$work/https-report.json"
curl -fsS --connect-timeout 8 --max-time 20 "https://${PUBLIC_IP}/monitor/app.js" -o "$work/https-app.js"
"$VENV/bin/python" - "$work/https-report.json" <<'PY'
import json, sys
r = json.load(open(sys.argv[1], encoding='utf-8'))
assert r.get('mode') == 'paper' and r.get('live_locked') is True and r.get('report_version') == 6
assert (r.get('v5_meta') or {}).get('execution_authority') is False
PY
grep -q 'v5MetaPanel' "$work/https-app.js" || fail "HTTPS app missing meta panel"

"$APP_DIR/bin/chobyar-status" >/dev/null || fail "authenticated status validation failed"
for line in \
  'TRADING_MODE=paper' \
  'LIVE_TRADING_ENABLED=false' \
  'MAX_POSITION_PCT=0.25' \
  'STOP_LOSS_PCT=0.015' \
  'TAKE_PROFIT_PCT=0.03' \
  'MAX_DAILY_LOSS_PCT=0.03'; do
  grep -qx "$line" "$ENV_FILE" || fail "FAIL-CLOSED: changed $line"
done

printf 'DEPLOYED_SHA=%s\nPAPER_STATUS=PASS\nLIVE_GATE=LOCKED\nV5_META_MONITOR=ACTIVE\nV5_META_INTELLIGENCE=ACTIVE\nEXECUTION_AUTHORITY=NONE\nTRADER_RESTARTED=NO\nSHADOW_RUNNER=V5.2_UNCHANGED\nSTATUS_RESTARTED=YES_READ_ONLY\nMONITOR_CACHE=V5\nMONITOR_URL=https://%s/monitor/\n' "$EXPECTED_SHA" "$PUBLIC_IP"
changed=0

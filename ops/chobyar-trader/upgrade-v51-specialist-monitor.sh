#!/usr/bin/env bash
set -Eeuo pipefail

REPO_URL="https://github.com/mortezaneghabi61-cloud/ChobYarSketch0.git"
EXPECTED_SHA="${1:-}"
PUBLIC_IP="109.122.247.214"
APP_DIR="/opt/chobyar-trader"
ENV_FILE="$APP_DIR/.env"
VENV="$APP_DIR/.venv"
STATUS_UNIT="/etc/systemd/system/chobyar-status.service"
MONITOR_DIR="$APP_DIR/monitor"

fail() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }
[[ $EUID -eq 0 ]] || fail "run as root"
[[ "$EXPECTED_SHA" =~ ^[0-9a-f]{40}$ ]] || fail "exact 40-character commit SHA required"
[[ -f "$ENV_FILE" && -x "$VENV/bin/python" && -f "$STATUS_UNIT" ]] || fail "existing installation incomplete"
[[ -f "$APP_DIR/app/status_server_v48.py" ]] || fail "v4.8 status baseline missing"
grep -q '/opt/chobyar-trader/app/status_server_v48.py' "$STATUS_UNIT" || fail "status service is not on v4.8 baseline"
systemctl is-active --quiet chobyar-v5-shadow.timer || fail "v5 shadow timer inactive"
systemctl is-active --quiet chobyar-v5-scorecard.timer || fail "v5 scorecard timer inactive"
[[ -f "$APP_DIR/state/v5_shadow_latest.json" && -f "$APP_DIR/state/v5_specialist_scorecard.json" ]] || fail "v5 shadow state missing"

grep -qx 'TRADING_MODE=paper' "$ENV_FILE" || fail "FAIL-CLOSED: TRADING_MODE must be paper"
grep -qx 'LIVE_TRADING_ENABLED=false' "$ENV_FILE" || fail "FAIL-CLOSED: LIVE_TRADING_ENABLED must be false"
grep -qx 'MAX_POSITION_PCT=0.25' "$ENV_FILE" || fail "FAIL-CLOSED: MAX_POSITION_PCT mismatch"
grep -qx 'STOP_LOSS_PCT=0.015' "$ENV_FILE" || fail "FAIL-CLOSED: STOP_LOSS_PCT mismatch"
grep -qx 'TAKE_PROFIT_PCT=0.03' "$ENV_FILE" || fail "FAIL-CLOSED: TAKE_PROFIT_PCT mismatch"
grep -qx 'MAX_DAILY_LOSS_PCT=0.03' "$ENV_FILE" || fail "FAIL-CLOSED: MAX_DAILY_LOSS_PCT mismatch"

trader_pid_before="$(systemctl show chobyar-trader.service -p MainPID --value)"
trader_started_before="$(systemctl show chobyar-trader.service -p ExecMainStartTimestampMonotonic --value)"
[[ "$trader_pid_before" =~ ^[1-9][0-9]*$ ]] || fail "trader service is not running"

umask 077
work="$(mktemp -d)"
backup="$APP_DIR/backups/v51-specialist-monitor-$(date -u +%Y%m%dT%H%M%SZ)"
changed=0
had_v51=0
had_js=0
had_css=0

rollback() {
  if [[ "$changed" == 1 ]]; then
    cp -a "$backup/chobyar-status.service" "$STATUS_UNIT" || true
    cp -a "$backup/sw.js" "$MONITOR_DIR/sw.js" || true
    if [[ "$had_v51" == 1 ]]; then cp -a "$backup/status_server_v51.py" "$APP_DIR/app/status_server_v51.py" || true; else rm -f "$APP_DIR/app/status_server_v51.py"; fi
    if [[ "$had_js" == 1 ]]; then cp -a "$backup/specialist_monitor.js" "$MONITOR_DIR/specialist_monitor.js" || true; else rm -f "$MONITOR_DIR/specialist_monitor.js"; fi
    if [[ "$had_css" == 1 ]]; then cp -a "$backup/specialist_monitor.css" "$MONITOR_DIR/specialist_monitor.css" || true; else rm -f "$MONITOR_DIR/specialist_monitor.css"; fi
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
for f in v3/status_server_v51.py v3/test_status_specialists.py monitor/specialist_monitor.js monitor/specialist_monitor.css monitor/sw.js; do
  [[ -f "$src/$f" ]] || fail "candidate missing $f"
done

PYTHONPATH="$src/v3" "$VENV/bin/python" -m py_compile "$src/v3/status_server_v51.py"
PYTHONPATH="$src/v3" "$VENV/bin/python" "$src/v3/test_status_specialists.py" -v
bash -n "$src/upgrade-v51-specialist-monitor.sh"

if grep -RniE 'API_KEY|APIKEY|submit_order|create_order|place_order|withdraw|enable_live|/api/v5/trade/' \
  "$src/v3/status_server_v51.py" "$src/v3/test_status_specialists.py" "$src/monitor/specialist_monitor.js" "$src/monitor/specialist_monitor.css"; then
  fail "forbidden secret/execution marker in specialist monitor"
fi
! grep -qE "['\"]btc_qty['\"][[:space:]]*:" "$src/v3/status_server_v51.py" || fail "position size exposed"
grep -q "execution_authority': False" "$src/v3/status_server_v51.py" || fail "shadow execution lock missing"
grep -q "automatic_promotion_enabled': False" "$src/v3/status_server_v51.py" || fail "auto-promotion lock missing"
grep -q 'SHADOW ONLY' "$src/monitor/specialist_monitor.js" || fail "shadow-only UI marker missing"
grep -q 'NO EXECUTION' "$src/monitor/specialist_monitor.js" || fail "no-execution UI marker missing"
grep -q 'chobyar-monitor-shell-v4' "$src/monitor/sw.js" || fail "monitor cache v4 missing"

mkdir -p "$backup"
chmod 700 "$backup"
cp -a "$STATUS_UNIT" "$backup/chobyar-status.service"
cp -a "$MONITOR_DIR/sw.js" "$backup/sw.js"
if [[ -f "$APP_DIR/app/status_server_v51.py" ]]; then had_v51=1; cp -a "$APP_DIR/app/status_server_v51.py" "$backup/status_server_v51.py"; fi
if [[ -f "$MONITOR_DIR/specialist_monitor.js" ]]; then had_js=1; cp -a "$MONITOR_DIR/specialist_monitor.js" "$backup/specialist_monitor.js"; fi
if [[ -f "$MONITOR_DIR/specialist_monitor.css" ]]; then had_css=1; cp -a "$MONITOR_DIR/specialist_monitor.css" "$backup/specialist_monitor.css"; fi

install -m 700 "$src/v3/status_server_v51.py" "$APP_DIR/app/status_server_v51.py"
install -m 644 "$src/monitor/specialist_monitor.js" "$MONITOR_DIR/specialist_monitor.js"
install -m 644 "$src/monitor/specialist_monitor.css" "$MONITOR_DIR/specialist_monitor.css"
install -m 644 "$src/monitor/sw.js" "$MONITOR_DIR/sw.js"

python3 - "$STATUS_UNIT" <<'PY'
from pathlib import Path
import sys
p = Path(sys.argv[1])
text = p.read_text(encoding='utf-8')
v48 = '/opt/chobyar-trader/app/status_server_v48.py'
v51 = '/opt/chobyar-trader/app/status_server_v51.py'
if v48 in text:
    text = text.replace(v48, v51)
elif v51 not in text:
    raise SystemExit('unexpected status ExecStart')
p.write_text(text, encoding='utf-8')
PY

changed=1
systemctl daemon-reload
systemctl restart chobyar-status.service
sleep 3
systemctl is-active --quiet chobyar-trader.service || fail "trader service inactive"
systemctl is-active --quiet chobyar-status.service || fail "status service inactive"
systemctl is-active --quiet chobyar-v5-shadow.timer || fail "v5 shadow timer inactive after monitor upgrade"
systemctl is-active --quiet chobyar-v5-scorecard.timer || fail "v5 scorecard timer inactive after monitor upgrade"

trader_pid_after="$(systemctl show chobyar-trader.service -p MainPID --value)"
trader_started_after="$(systemctl show chobyar-trader.service -p ExecMainStartTimestampMonotonic --value)"
[[ "$trader_pid_after" == "$trader_pid_before" && "$trader_started_after" == "$trader_started_before" ]] || fail "FAIL-CLOSED: trader restarted during v5.1 monitor upgrade"

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
assert r.get('report_version') == 5
assert r.get('mode') == 'paper' and r.get('live_locked') is True
assert not ({'paper','market','risk','security'} & set(r))
shadow = r.get('v5_shadow')
assert isinstance(shadow, dict)
assert shadow.get('mode') == 'shadow_observation_only'
assert shadow.get('execution_authority') is False
assert shadow.get('automatic_promotion_enabled') is False
assert shadow.get('foreign_execution_enabled') is False
assert shadow.get('geo_bypass_supported') is False
assert len(shadow.get('specialists') or []) == 5
score = r.get('v5_specialist_scorecard')
assert isinstance(score, dict)
assert score.get('execution_authority') is False
assert score.get('automatic_promotion_enabled') is False
assert int(score.get('minimum_directional_samples_per_regime') or 0) == 30
assert r.get('services', {}).get('v5_shadow_timer') == 'active'
assert r.get('services', {}).get('v5_scorecard_timer') == 'active'
print('V5_MONITOR_REPORT=PASS')
print('REGIME=' + str((shadow.get('regime') or {}).get('label')))
print('SHADOW_ACTION=' + str((shadow.get('shadow_consensus') or {}).get('action')))
print('RISK_VETO=' + str(bool((shadow.get('shadow_consensus') or {}).get('risk_veto'))).upper())
print('SPECIALISTS=' + str(len(shadow.get('specialists') or [])))
print('SCORECARD_SAMPLED_ROWS=' + str(score.get('sampled_rows')))
PY

grep -q 'v5SpecialistPanel' "$work/app.js" || fail "served app missing specialist monitor"
grep -q 'v5-specialist-panel' "$work/style.css" || fail "served style missing specialist monitor"
grep -q 'chobyar-monitor-shell-v4' "$work/sw.js" || fail "served service worker is not v4"

curl -fsS --connect-timeout 8 --max-time 20 "https://${PUBLIC_IP}/public-report" -o "$work/https-report.json"
curl -fsS --connect-timeout 8 --max-time 20 "https://${PUBLIC_IP}/monitor/app.js" -o "$work/https-app.js"
"$VENV/bin/python" - "$work/https-report.json" <<'PY'
import json, sys
r = json.load(open(sys.argv[1], encoding='utf-8'))
assert r.get('mode') == 'paper' and r.get('live_locked') is True and r.get('report_version') == 5
assert (r.get('v5_shadow') or {}).get('execution_authority') is False
PY
grep -q 'v5SpecialistPanel' "$work/https-app.js" || fail "HTTPS app missing specialist monitor"

"$APP_DIR/bin/chobyar-status" >/dev/null || fail "authenticated status validation failed"
grep -qx 'TRADING_MODE=paper' "$ENV_FILE" || fail "paper mode changed"
grep -qx 'LIVE_TRADING_ENABLED=false' "$ENV_FILE" || fail "live lock changed"
grep -qx 'MAX_POSITION_PCT=0.25' "$ENV_FILE" || fail "risk changed"
grep -qx 'STOP_LOSS_PCT=0.015' "$ENV_FILE" || fail "risk changed"
grep -qx 'TAKE_PROFIT_PCT=0.03' "$ENV_FILE" || fail "risk changed"
grep -qx 'MAX_DAILY_LOSS_PCT=0.03' "$ENV_FILE" || fail "risk changed"

printf 'DEPLOYED_SHA=%s\nPAPER_STATUS=PASS\nLIVE_GATE=LOCKED\nV5_SPECIALIST_MONITOR=ACTIVE\nV5_SHADOW=ACTIVE\nEXECUTION_AUTHORITY=NONE\nAUTO_PROMOTION=DISABLED\nTRADER_RESTARTED=NO\nSTATUS_RESTARTED=YES_READ_ONLY\nMONITOR_CACHE=V4\nMONITOR_URL=https://%s/monitor/\n' "$EXPECTED_SHA" "$PUBLIC_IP"
changed=0

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
[[ -f "$APP_DIR/app/status_server_v48.py" ]] || fail "v4.8 baseline missing"
grep -q '/opt/chobyar-trader/app/status_server_v48.py' "$STATUS_UNIT" || fail "status service is not on deployed v4.8 baseline"

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
backup="$APP_DIR/backups/v481-position-ui-$(date -u +%Y%m%dT%H%M%SZ)"
changed=0
had_v481=0
had_index=0

rollback() {
  if [[ "$changed" == 1 ]]; then
    cp -a "$backup/chobyar-status.service" "$STATUS_UNIT" || true
    cp -a "$backup/sw.js" "$MONITOR_DIR/sw.js" || true
    if [[ "$had_v481" == 1 ]]; then cp -a "$backup/status_server_v481.py" "$APP_DIR/app/status_server_v481.py" || true; else rm -f "$APP_DIR/app/status_server_v481.py"; fi
    if [[ "$had_index" == 1 ]]; then cp -a "$backup/index_v481.html" "$MONITOR_DIR/index_v481.html" || true; else rm -f "$MONITOR_DIR/index_v481.html"; fi
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
for f in v3/status_server_v481.py monitor/index_v481.html monitor/position_detail.js monitor/position_detail.css monitor/sw.js; do
  [[ -f "$src/$f" ]] || fail "candidate missing $f"
done

PYTHONPATH="$src/v3" "$VENV/bin/python" -m py_compile "$src/v3/status_server_v481.py"
bash -n "$src/upgrade-v481-position-ui-wire.sh"
grep -q '/monitor/position_detail.js' "$src/monitor/index_v481.html" || fail "detail JS not linked"
grep -q '/monitor/position_detail.css' "$src/monitor/index_v481.html" || fail "detail CSS not linked"
grep -q "'/monitor/position_detail.js'" "$src/v3/status_server_v481.py" || fail "detail JS route not allow-listed"
grep -q "'/monitor/position_detail.css'" "$src/v3/status_server_v481.py" || fail "detail CSS route not allow-listed"
grep -q 'chobyar-monitor-shell-v3' "$src/monitor/sw.js" || fail "cache v3 missing"

if grep -RniE 'STATUS_HMAC_SECRET|API_KEY|APIKEY|submit_order|create_order|place_order|enable_live' \
  "$src/v3/status_server_v481.py" "$src/monitor/index_v481.html" "$src/monitor/position_detail.js" "$src/monitor/position_detail.css"; then
  fail "forbidden secret/control marker detected"
fi

mkdir -p "$backup"
chmod 700 "$backup"
cp -a "$STATUS_UNIT" "$backup/chobyar-status.service"
cp -a "$MONITOR_DIR/sw.js" "$backup/sw.js"
if [[ -f "$APP_DIR/app/status_server_v481.py" ]]; then had_v481=1; cp -a "$APP_DIR/app/status_server_v481.py" "$backup/status_server_v481.py"; fi
if [[ -f "$MONITOR_DIR/index_v481.html" ]]; then had_index=1; cp -a "$MONITOR_DIR/index_v481.html" "$backup/index_v481.html"; fi

install -m 700 "$src/v3/status_server_v481.py" "$APP_DIR/app/status_server_v481.py"
install -m 644 "$src/monitor/index_v481.html" "$MONITOR_DIR/index_v481.html"
install -m 644 "$src/monitor/position_detail.js" "$MONITOR_DIR/position_detail.js"
install -m 644 "$src/monitor/position_detail.css" "$MONITOR_DIR/position_detail.css"
install -m 644 "$src/monitor/sw.js" "$MONITOR_DIR/sw.js"

python3 - "$STATUS_UNIT" <<'PY'
from pathlib import Path
import sys
p = Path(sys.argv[1])
text = p.read_text(encoding='utf-8')
v48 = '/opt/chobyar-trader/app/status_server_v48.py'
v481 = '/opt/chobyar-trader/app/status_server_v481.py'
if v48 in text:
    text = text.replace(v48, v481)
elif v481 not in text:
    raise SystemExit('unexpected status ExecStart')
p.write_text(text, encoding='utf-8')
PY

changed=1
systemctl daemon-reload
systemctl restart chobyar-status.service
sleep 3
systemctl is-active --quiet chobyar-trader.service || fail "trader service inactive"
systemctl is-active --quiet chobyar-status.service || fail "status service inactive"

trader_pid_after="$(systemctl show chobyar-trader.service -p MainPID --value)"
trader_started_after="$(systemctl show chobyar-trader.service -p ExecMainStartTimestampMonotonic --value)"
[[ "$trader_pid_after" == "$trader_pid_before" && "$trader_started_after" == "$trader_started_before" ]] || fail "FAIL-CLOSED: trader restarted during UI wiring"

port="$(awk -F= '$1=="STATUS_PORT" {print $2}' "$ENV_FILE" | tail -n1)"
port="${port:-8787}"
base="http://127.0.0.1:${port}"
[[ "$(curl -sS -o /dev/null -w '%{http_code}' "$base/health")" == 200 ]] || fail "health validation failed"
[[ "$(curl -sS -o /dev/null -w '%{http_code}' "$base/status")" == 401 ]] || fail "unauthenticated status was not rejected"
curl -fsS "$base/public-report" -o "$work/report.json"
curl -fsS "$base/monitor/" -o "$work/index.html"
curl -fsS "$base/monitor/position_detail.js" -o "$work/detail.js"
curl -fsS "$base/monitor/position_detail.css" -o "$work/detail.css"
curl -fsS "$base/monitor/sw.js" -o "$work/sw.js"

"$VENV/bin/python" - "$work/report.json" <<'PY'
import json, sys
r = json.load(open(sys.argv[1], encoding='utf-8'))
assert r.get('ok') is True and r.get('public_report') is True
assert r.get('report_version') == 4
assert r.get('mode') == 'paper' and r.get('live_locked') is True
p = r.get('position')
assert isinstance(p, dict) and 'targets_verified' in p and 'mark_price' in p
PY

grep -q '/monitor/position_detail.js' "$work/index.html" || fail "served shell does not load detail JS"
grep -q '/monitor/position_detail.css' "$work/index.html" || fail "served shell does not load detail CSS"
grep -q 'position-range' "$work/detail.js" || fail "served detail JS invalid"
grep -q 'position-progress' "$work/detail.css" || fail "served detail CSS invalid"
grep -q 'chobyar-monitor-shell-v3' "$work/sw.js" || fail "served service worker is not v3"

curl -fsS --connect-timeout 8 --max-time 20 "https://${PUBLIC_IP}/monitor/" -o "$work/https-index.html"
curl -fsS --connect-timeout 8 --max-time 20 "https://${PUBLIC_IP}/monitor/position_detail.js" -o "$work/https-detail.js"
grep -q '/monitor/position_detail.js' "$work/https-index.html" || fail "HTTPS shell does not load detail JS"
grep -q 'position-range' "$work/https-detail.js" || fail "HTTPS detail JS invalid"

"$APP_DIR/bin/chobyar-status" >/dev/null || fail "authenticated status validation failed"
grep -qx 'TRADING_MODE=paper' "$ENV_FILE" || fail "paper mode changed"
grep -qx 'LIVE_TRADING_ENABLED=false' "$ENV_FILE" || fail "live lock changed"
grep -qx 'MAX_POSITION_PCT=0.25' "$ENV_FILE" || fail "risk changed"
grep -qx 'STOP_LOSS_PCT=0.015' "$ENV_FILE" || fail "risk changed"
grep -qx 'TAKE_PROFIT_PCT=0.03' "$ENV_FILE" || fail "risk changed"
grep -qx 'MAX_DAILY_LOSS_PCT=0.03' "$ENV_FILE" || fail "risk changed"

printf 'DEPLOYED_SHA=%s\nPAPER_STATUS=PASS\nLIVE_GATE=LOCKED\nPOSITION_DETAIL_UI=ACTIVE\nTRADER_RESTARTED=NO\nMONITOR_CACHE=V3\nMONITOR_URL=https://%s/monitor/\n' "$EXPECTED_SHA" "$PUBLIC_IP"
changed=0

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
[[ -f "$ENV_FILE" && -x "$VENV/bin/python" && -f "$STATUS_UNIT" ]] || fail "existing trader/status installation incomplete"
[[ -f "$APP_DIR/app/status_server_v47.py" ]] || fail "v4.7 position telemetry baseline missing"
grep -q '/opt/chobyar-trader/app/status_server_v47.py' "$STATUS_UNIT" || fail "status service is not on v4.7 baseline"

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
backup="$APP_DIR/backups/v48-position-detail-$(date -u +%Y%m%dT%H%M%SZ)"
changed=0
had_v48=0
had_js=0
had_css=0

rollback() {
  if [[ "$changed" == 1 ]]; then
    cp -a "$backup/chobyar-status.service" "$STATUS_UNIT" || true
    cp -a "$backup/sw.js" "$MONITOR_DIR/sw.js" || true
    if [[ "$had_v48" == 1 ]]; then cp -a "$backup/status_server_v48.py" "$APP_DIR/app/status_server_v48.py" || true; else rm -f "$APP_DIR/app/status_server_v48.py"; fi
    if [[ "$had_js" == 1 ]]; then cp -a "$backup/position_detail.js" "$MONITOR_DIR/position_detail.js" || true; else rm -f "$MONITOR_DIR/position_detail.js"; fi
    if [[ "$had_css" == 1 ]]; then cp -a "$backup/position_detail.css" "$MONITOR_DIR/position_detail.css" || true; else rm -f "$MONITOR_DIR/position_detail.css"; fi
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
for f in v3/status_server_v48.py v3/test_status_position_detail.py monitor/position_detail.js monitor/position_detail.css monitor/sw.js; do
  [[ -f "$src/$f" ]] || fail "candidate missing $f"
done

PYTHONPATH="$src/v3" "$VENV/bin/python" -m py_compile "$src/v3/status_server_v48.py"
PYTHONPATH="$src/v3" "$VENV/bin/python" "$src/v3/test_status_position_detail.py" -v
bash -n "$src/upgrade-v48-position-detail.sh"

if grep -RniE 'STATUS_HMAC_SECRET|API_KEY|APIKEY|submit_order|create_order|place_order|enable_live|martingale|leverage' \
  "$src/v3/status_server_v48.py" "$src/v3/test_status_position_detail.py" "$src/monitor/position_detail.js" "$src/monitor/position_detail.css"; then
  fail "forbidden secret/control marker detected"
fi
! grep -qE "['\"]btc_qty['\"][[:space:]]*:" "$src/v3/status_server_v48.py" || fail "position size was exposed"
grep -q "'mark_price':" "$src/v3/status_server_v48.py" || fail "mark price projection missing"
grep -q "'stop_loss_price':" "$src/v3/status_server_v48.py" || fail "stop-loss projection missing"
grep -q "'take_profit_price':" "$src/v3/status_server_v48.py" || fail "take-profit projection missing"
grep -q "'targets_verified':" "$src/v3/status_server_v48.py" || fail "target verification missing"
grep -q 'position-range' "$src/monitor/position_detail.js" || fail "position range UI missing"
grep -q 'chobyar-monitor-shell-v2' "$src/monitor/sw.js" || fail "monitor cache version was not advanced"

mkdir -p "$backup"
chmod 700 "$backup"
cp -a "$STATUS_UNIT" "$backup/chobyar-status.service"
cp -a "$MONITOR_DIR/sw.js" "$backup/sw.js"
if [[ -f "$APP_DIR/app/status_server_v48.py" ]]; then had_v48=1; cp -a "$APP_DIR/app/status_server_v48.py" "$backup/status_server_v48.py"; fi
if [[ -f "$MONITOR_DIR/position_detail.js" ]]; then had_js=1; cp -a "$MONITOR_DIR/position_detail.js" "$backup/position_detail.js"; fi
if [[ -f "$MONITOR_DIR/position_detail.css" ]]; then had_css=1; cp -a "$MONITOR_DIR/position_detail.css" "$backup/position_detail.css"; fi

install -m 700 "$src/v3/status_server_v48.py" "$APP_DIR/app/status_server_v48.py"
install -m 644 "$src/monitor/position_detail.js" "$MONITOR_DIR/position_detail.js"
install -m 644 "$src/monitor/position_detail.css" "$MONITOR_DIR/position_detail.css"
install -m 644 "$src/monitor/sw.js" "$MONITOR_DIR/sw.js"

python3 - "$STATUS_UNIT" <<'PY'
from pathlib import Path
import sys
p = Path(sys.argv[1])
text = p.read_text(encoding='utf-8')
v47 = '/opt/chobyar-trader/app/status_server_v47.py'
v48 = '/opt/chobyar-trader/app/status_server_v48.py'
if v47 in text:
    text = text.replace(v47, v48)
elif v48 not in text:
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
[[ "$trader_pid_after" == "$trader_pid_before" && "$trader_started_after" == "$trader_started_before" ]] || fail "FAIL-CLOSED: trader restarted during position detail upgrade"

port="$(awk -F= '$1=="STATUS_PORT" {print $2}' "$ENV_FILE" | tail -n1)"
port="${port:-8787}"
base="http://127.0.0.1:${port}"
[[ "$(curl -sS -o /dev/null -w '%{http_code}' "$base/health")" == 200 ]] || fail "health validation failed"
[[ "$(curl -sS -o /dev/null -w '%{http_code}' "$base/status")" == 401 ]] || fail "unauthenticated status was not rejected"
curl -fsS "$base/public-report" -o "$work/report.json"
curl -fsS "$base/monitor/app.js" -o "$work/app.js"
curl -fsS "$base/monitor/style.css" -o "$work/style.css"
curl -fsS "$base/monitor/sw.js" -o "$work/sw.js"

"$VENV/bin/python" - "$work/report.json" <<'PY'
import json, math, sys
r = json.load(open(sys.argv[1], encoding='utf-8'))
assert r.get('ok') is True and r.get('public_report') is True
assert r.get('report_version') == 4
assert r.get('mode') == 'paper' and r.get('live_locked') is True
assert not ({'paper', 'market', 'risk', 'security'} & set(r))
p = r.get('position')
allowed = {'open','side','entry_price','unrealized_pnl','mark_price','unrealized_return_pct','targets_verified','stop_loss_price','take_profit_price','distance_to_stop_pct','distance_to_take_pct'}
assert isinstance(p, dict) and set(p) == allowed
assert 'btc_qty' not in p and 'cash_usdt' not in p
if p['open']:
    assert p['side'] == 'LONG'
    if p['mark_price'] is not None:
        assert isinstance(p['mark_price'], (int,float)) and math.isfinite(p['mark_price']) and p['mark_price'] > 0
    if p['targets_verified']:
        assert p['stop_loss_price'] < p['entry_price'] < p['take_profit_price']
else:
    assert p['side'] == 'FLAT'
print(f"POSITION_OPEN={str(p['open']).upper()}")
print(f"POSITION_SIDE={p['side']}")
print(f"MARK_PRICE={p['mark_price']}")
print(f"POSITION_RETURN_PCT={p['unrealized_return_pct']}")
print(f"STOP_LOSS_PRICE={p['stop_loss_price']}")
print(f"TAKE_PROFIT_PRICE={p['take_profit_price']}")
PY

grep -q 'position-range' "$work/app.js" || fail "served app is missing position detail"
grep -q 'position-progress' "$work/style.css" || fail "served style is missing position detail"
grep -q 'chobyar-monitor-shell-v2' "$work/sw.js" || fail "served service worker cache version mismatch"

curl -fsS --connect-timeout 8 --max-time 20 "https://${PUBLIC_IP}/public-report" -o "$work/https-report.json"
curl -fsS --connect-timeout 8 --max-time 20 "https://${PUBLIC_IP}/monitor/app.js" -o "$work/https-app.js"
"$VENV/bin/python" - "$work/https-report.json" <<'PY'
import json, sys
r = json.load(open(sys.argv[1], encoding='utf-8'))
assert r.get('mode') == 'paper' and r.get('live_locked') is True and r.get('report_version') == 4
assert isinstance(r.get('position'), dict) and 'targets_verified' in r['position']
PY
grep -q 'position-range' "$work/https-app.js" || fail "HTTPS monitor is missing position detail"

"$APP_DIR/bin/chobyar-status" >/dev/null || fail "authenticated status validation failed"
grep -qx 'TRADING_MODE=paper' "$ENV_FILE" || fail "paper mode changed"
grep -qx 'LIVE_TRADING_ENABLED=false' "$ENV_FILE" || fail "live lock changed"
grep -qx 'MAX_POSITION_PCT=0.25' "$ENV_FILE" || fail "risk changed"
grep -qx 'STOP_LOSS_PCT=0.015' "$ENV_FILE" || fail "risk changed"
grep -qx 'TAKE_PROFIT_PCT=0.03' "$ENV_FILE" || fail "risk changed"
grep -qx 'MAX_DAILY_LOSS_PCT=0.03' "$ENV_FILE" || fail "risk changed"

printf 'DEPLOYED_SHA=%s\nPAPER_STATUS=PASS\nLIVE_GATE=LOCKED\nPOSITION_DETAIL=ACTIVE\nTRADER_RESTARTED=NO\nMONITOR_CACHE=V2\nMONITOR_URL=https://%s/monitor/\n' "$EXPECTED_SHA" "$PUBLIC_IP"
changed=0

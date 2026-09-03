#!/usr/bin/env bash
set -Eeuo pipefail

REPO_URL="https://github.com/mortezaneghabi61-cloud/ChobYarSketch0.git"
EXPECTED_SHA="${1:-}"
PUBLIC_IP="109.122.247.214"
APP_DIR="/opt/chobyar-trader"
ENV_FILE="$APP_DIR/.env"
VENV="$APP_DIR/.venv"
STATUS_UNIT="/etc/systemd/system/chobyar-status.service"

fail() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }
[[ $EUID -eq 0 ]] || fail "run as root"
[[ "$EXPECTED_SHA" =~ ^[0-9a-f]{40}$ ]] || fail "exact 40-character commit SHA required"
[[ -f "$ENV_FILE" && -x "$VENV/bin/python" && -f "$STATUS_UNIT" ]] || fail "existing trader/status installation incomplete"
[[ -f "$APP_DIR/app/status_server_v45.py" ]] || fail "v4.5 monitor baseline missing"

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
backup="$APP_DIR/backups/v47-position-$(date -u +%Y%m%dT%H%M%SZ)"
changed=0
had_v47=0

rollback() {
  if [[ "$changed" == 1 ]]; then
    cp -a "$backup/chobyar-status.service" "$STATUS_UNIT" || true
    if [[ "$had_v47" == 1 ]]; then
      cp -a "$backup/status_server_v47.py" "$APP_DIR/app/status_server_v47.py" || true
    else
      rm -f "$APP_DIR/app/status_server_v47.py"
    fi
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
for f in v3/status_server_v47.py v3/test_status_position.py; do
  [[ -f "$src/$f" ]] || fail "candidate missing $f"
done

PYTHONPATH="$src/v3" "$VENV/bin/python" -m py_compile "$src/v3/status_server_v47.py"
PYTHONPATH="$src/v3" "$VENV/bin/python" "$src/v3/test_status_position.py" -v

if grep -RniE 'STATUS_HMAC_SECRET|API_KEY|APIKEY|submit_order|create_order|place_order|enable_live|martingale|leverage' \
  "$src/v3/status_server_v47.py" "$src/v3/test_status_position.py"; then
  fail "forbidden secret/control marker detected"
fi

grep -q "'open': True" "$src/v3/status_server_v47.py" || fail "open position projection missing"
grep -q "'side': 'LONG'" "$src/v3/status_server_v47.py" || fail "LONG projection missing"
grep -q "'side': 'FLAT'" "$src/v3/status_server_v47.py" || fail "FLAT projection missing"
grep -q "'entry_price':" "$src/v3/status_server_v47.py" || fail "entry projection missing"
grep -q "'unrealized_pnl':" "$src/v3/status_server_v47.py" || fail "unrealized PnL projection missing"
! grep -qE "['\"]btc_qty['\"][[:space:]]*:" "$src/v3/status_server_v47.py" || fail "position size was exposed"

mkdir -p "$backup"
chmod 700 "$backup"
cp -a "$STATUS_UNIT" "$backup/chobyar-status.service"
if [[ -f "$APP_DIR/app/status_server_v47.py" ]]; then
  had_v47=1
  cp -a "$APP_DIR/app/status_server_v47.py" "$backup/status_server_v47.py"
fi

install -m 700 "$src/v3/status_server_v47.py" "$APP_DIR/app/status_server_v47.py"

python3 - "$STATUS_UNIT" <<'PY'
from pathlib import Path
import sys
p = Path(sys.argv[1])
text = p.read_text(encoding='utf-8')
v45 = '/opt/chobyar-trader/app/status_server_v45.py'
v47 = '/opt/chobyar-trader/app/status_server_v47.py'
if v45 in text:
    text = text.replace(v45, v47)
elif v47 not in text:
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
[[ "$trader_pid_after" == "$trader_pid_before" && "$trader_started_after" == "$trader_started_before" ]] || fail "FAIL-CLOSED: trader restarted during position telemetry upgrade"

port="$(awk -F= '$1=="STATUS_PORT" {print $2}' "$ENV_FILE" | tail -n1)"
port="${port:-8787}"
base="http://127.0.0.1:${port}"
[[ "$(curl -sS -o /dev/null -w '%{http_code}' "$base/health")" == 200 ]] || fail "health validation failed"
[[ "$(curl -sS -o /dev/null -w '%{http_code}' "$base/status")" == 401 ]] || fail "unauthenticated status was not rejected"
curl -fsS "$base/public-report" -o "$work/report.json"

"$VENV/bin/python" - "$work/report.json" <<'PY'
import json, math, sys
r = json.load(open(sys.argv[1], encoding='utf-8'))
assert r.get('ok') is True and r.get('public_report') is True
assert r.get('report_version') == 3
assert r.get('mode') == 'paper' and r.get('live_locked') is True
assert not ({'paper', 'market', 'risk', 'security'} & set(r))
p = r.get('position')
assert isinstance(p, dict)
assert set(p) == {'open', 'side', 'entry_price', 'unrealized_pnl'}
assert isinstance(p.get('open'), bool)
assert p.get('side') in {'LONG', 'FLAT'}
if p['open']:
    assert p['side'] == 'LONG'
    assert isinstance(p['entry_price'], (int, float)) and math.isfinite(p['entry_price']) and p['entry_price'] > 0
    assert p['unrealized_pnl'] is None or (isinstance(p['unrealized_pnl'], (int, float)) and math.isfinite(p['unrealized_pnl']))
else:
    assert p['side'] == 'FLAT'
    assert p['entry_price'] is None
    assert p['unrealized_pnl'] == 0.0
assert 'btc_qty' not in p and 'cash_usdt' not in p
print(f"POSITION_OPEN={str(p['open']).upper()}")
print(f"POSITION_SIDE={p['side']}")
print(f"UNREALIZED_PNL={p['unrealized_pnl']}")
PY

curl -fsS --connect-timeout 8 --max-time 20 "https://${PUBLIC_IP}/public-report" -o "$work/https-report.json"
curl -fsS --connect-timeout 8 --max-time 20 "https://${PUBLIC_IP}/monitor/" -o "$work/monitor.html"
"$VENV/bin/python" - "$work/https-report.json" <<'PY'
import json, sys
r = json.load(open(sys.argv[1], encoding='utf-8'))
assert r.get('mode') == 'paper' and r.get('live_locked') is True
p = r.get('position')
assert isinstance(p, dict) and set(p) == {'open', 'side', 'entry_price', 'unrealized_pnl'}
PY
grep -q 'ChobYar Trader Monitor' "$work/monitor.html" || fail "HTTPS monitor validation failed"

"$APP_DIR/bin/chobyar-status" >/dev/null || fail "authenticated status validation failed"
grep -qx 'TRADING_MODE=paper' "$ENV_FILE" || fail "paper mode changed"
grep -qx 'LIVE_TRADING_ENABLED=false' "$ENV_FILE" || fail "live lock changed"
grep -qx 'MAX_POSITION_PCT=0.25' "$ENV_FILE" || fail "risk changed"
grep -qx 'STOP_LOSS_PCT=0.015' "$ENV_FILE" || fail "risk changed"
grep -qx 'TAKE_PROFIT_PCT=0.03' "$ENV_FILE" || fail "risk changed"
grep -qx 'MAX_DAILY_LOSS_PCT=0.03' "$ENV_FILE" || fail "risk changed"

printf 'DEPLOYED_SHA=%s\nPAPER_STATUS=PASS\nLIVE_GATE=LOCKED\nPOSITION_TELEMETRY=ACTIVE\nTRADER_RESTARTED=NO\nMONITOR_URL=https://%s/monitor/\n' "$EXPECTED_SHA" "$PUBLIC_IP"
changed=0

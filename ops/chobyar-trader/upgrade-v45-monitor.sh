#!/usr/bin/env bash
set -Eeuo pipefail

REPO_URL="https://github.com/mortezaneghabi61-cloud/ChobYarSketch0.git"
EXPECTED_SHA="${1:-}"
APP_DIR="/opt/chobyar-trader"
ENV_FILE="$APP_DIR/.env"
VENV="$APP_DIR/.venv"
STATUS_UNIT="/etc/systemd/system/chobyar-status.service"
MONITOR_DIR="$APP_DIR/monitor"

die() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }
[[ $EUID -eq 0 ]] || die "run as root"
[[ "$EXPECTED_SHA" =~ ^[0-9a-f]{40}$ ]] || die "exact 40-character commit SHA required"
[[ -f "$ENV_FILE" && -x "$VENV/bin/python" && -f "$STATUS_UNIT" ]] || die "existing trader/status installation incomplete"
[[ -f "$APP_DIR/app/status_server_v44.py" ]] || die "v4.4 status baseline missing"

grep -qx 'TRADING_MODE=paper' "$ENV_FILE" || die "FAIL-CLOSED: TRADING_MODE must be paper"
grep -qx 'LIVE_TRADING_ENABLED=false' "$ENV_FILE" || die "FAIL-CLOSED: LIVE_TRADING_ENABLED must be false"
grep -qx 'MAX_POSITION_PCT=0.25' "$ENV_FILE" || die "FAIL-CLOSED: MAX_POSITION_PCT mismatch"
grep -qx 'STOP_LOSS_PCT=0.015' "$ENV_FILE" || die "FAIL-CLOSED: STOP_LOSS_PCT mismatch"
grep -qx 'TAKE_PROFIT_PCT=0.03' "$ENV_FILE" || die "FAIL-CLOSED: TAKE_PROFIT_PCT mismatch"
grep -qx 'MAX_DAILY_LOSS_PCT=0.03' "$ENV_FILE" || die "FAIL-CLOSED: MAX_DAILY_LOSS_PCT mismatch"

trader_pid_before="$(systemctl show chobyar-trader.service -p MainPID --value)"
trader_started_before="$(systemctl show chobyar-trader.service -p ExecMainStartTimestampMonotonic --value)"
[[ "$trader_pid_before" =~ ^[1-9][0-9]*$ ]] || die "trader service is not running"

umask 077
work="$(mktemp -d)"
backup="$APP_DIR/backups/v45-monitor-$(date -u +%Y%m%dT%H%M%SZ)"
changed=0
had_v45=0
had_monitor=0

rollback() {
  if [[ "$changed" == 1 ]]; then
    cp -a "$backup/chobyar-status.service" "$STATUS_UNIT" || true
    if [[ "$had_v45" == 1 ]]; then
      cp -a "$backup/status_server_v45.py" "$APP_DIR/app/status_server_v45.py" || true
    else
      rm -f "$APP_DIR/app/status_server_v45.py"
    fi
    rm -rf "$MONITOR_DIR"
    if [[ "$had_monitor" == 1 ]]; then cp -a "$backup/monitor" "$MONITOR_DIR" || true; fi
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
[[ "$(git -C "$work/repo" rev-parse HEAD)" == "$EXPECTED_SHA" ]] || die "downloaded commit mismatch"
src="$work/repo/ops/chobyar-trader"

for f in \
  v3/status_server_v45.py \
  v3/test_status_monitor.py \
  monitor/index.html \
  monitor/style.css \
  monitor/app.js \
  monitor/manifest.webmanifest \
  monitor/icon.svg \
  monitor/sw.js; do
  [[ -f "$src/$f" ]] || die "candidate missing $f"
done

PYTHONPATH="$src/v3" "$VENV/bin/python" -m py_compile "$src/v3/status_server_v45.py"
PYTHONPATH="$src/v3" "$VENV/bin/python" "$src/v3/test_status_monitor.py" -v

# Defense in depth: client assets must not contain any execution/control surface.
if grep -RniE 'STATUS_HMAC_SECRET|API_KEY|APIKEY|submit_order|create_order|place_order|enable_live|martingale|leverage' "$src/monitor"; then
  die "forbidden control/secret marker in monitor client"
fi
if grep -RniE 'https?://109\.122\.247\.214|fetch\([[:space:]]*["'"']?/status' "$src/monitor"; then
  die "monitor attempted direct VPS/authenticated status access"
fi
grep -q 'fetch("/public-report"' "$src/monitor/app.js" || die "public-report fetch missing"
grep -q 'report.mode !== "paper"' "$src/monitor/app.js" || die "paper fail-closed check missing"
grep -q 'report.live_locked !== true' "$src/monitor/app.js" || die "live-lock fail-closed check missing"

mkdir -p "$backup"
chmod 700 "$backup"
cp -a "$STATUS_UNIT" "$backup/chobyar-status.service"
if [[ -f "$APP_DIR/app/status_server_v45.py" ]]; then had_v45=1; cp -a "$APP_DIR/app/status_server_v45.py" "$backup/status_server_v45.py"; fi
if [[ -d "$MONITOR_DIR" ]]; then had_monitor=1; cp -a "$MONITOR_DIR" "$backup/monitor"; fi

install -m 700 "$src/v3/status_server_v45.py" "$APP_DIR/app/status_server_v45.py"
rm -rf "$MONITOR_DIR"
install -d -m 755 "$MONITOR_DIR"
for f in index.html style.css app.js manifest.webmanifest icon.svg sw.js; do
  install -m 644 "$src/monitor/$f" "$MONITOR_DIR/$f"
done

python3 - "$STATUS_UNIT" <<'PY'
from pathlib import Path
import sys
p = Path(sys.argv[1])
text = p.read_text(encoding='utf-8')
v44 = '/opt/chobyar-trader/app/status_server_v44.py'
v45 = '/opt/chobyar-trader/app/status_server_v45.py'
if v44 in text:
    text = text.replace(v44, v45)
elif v45 not in text:
    raise SystemExit('unexpected status ExecStart')
p.write_text(text, encoding='utf-8')
PY

changed=1
systemctl daemon-reload
systemctl restart chobyar-status.service
sleep 3
systemctl is-active --quiet chobyar-trader.service || die "trader service inactive"
systemctl is-active --quiet chobyar-status.service || die "status service inactive"

trader_pid_after="$(systemctl show chobyar-trader.service -p MainPID --value)"
trader_started_after="$(systemctl show chobyar-trader.service -p ExecMainStartTimestampMonotonic --value)"
[[ "$trader_pid_after" == "$trader_pid_before" && "$trader_started_after" == "$trader_started_before" ]] || die "FAIL-CLOSED: trader restarted during monitor upgrade"

port="$(awk -F= '$1=="STATUS_PORT" {print $2}' "$ENV_FILE" | tail -n1)"; port="${port:-8787}"
base="http://127.0.0.1:${port}"
[[ "$(curl -sS -o /dev/null -w '%{http_code}' "$base/health")" == 200 ]] || die "health validation failed"
[[ "$(curl -sS -o /dev/null -w '%{http_code}' "$base/status")" == 401 ]] || die "unauthenticated status was not rejected"
curl -fsS "$base/public-report" -o "$work/report.json"
"$VENV/bin/python" - "$work/report.json" <<'PY'
import json, sys
r = json.load(open(sys.argv[1], encoding='utf-8'))
assert r.get('ok') is True and r.get('public_report') is True
assert r.get('mode') == 'paper'
assert r.get('live_locked') is True
assert not ({'paper', 'market', 'risk', 'security'} & set(r))
PY

[[ "$(curl -sS -o /dev/null -w '%{http_code}' "$base/monitor")" == 302 ]] || die "monitor redirect validation failed"
curl -fsSL "$base/monitor/" -o "$work/monitor.html"
curl -fsS "$base/monitor/app.js" -o "$work/app.js"
grep -q 'ChobYar Trader Monitor' "$work/monitor.html" || die "monitor HTML validation failed"
grep -q 'fetch("/public-report"' "$work/app.js" || die "served monitor app validation failed"
! grep -qiE 'STATUS_HMAC_SECRET|API_KEY|submit_order|create_order|place_order' "$work/monitor.html" "$work/app.js" || die "served monitor contains forbidden marker"

"$APP_DIR/bin/chobyar-status" >/dev/null || die "authenticated status validation failed"
grep -qx 'TRADING_MODE=paper' "$ENV_FILE" && grep -qx 'LIVE_TRADING_ENABLED=false' "$ENV_FILE" || die "paper lock changed"
grep -qx 'MAX_POSITION_PCT=0.25' "$ENV_FILE" && \
  grep -qx 'STOP_LOSS_PCT=0.015' "$ENV_FILE" && \
  grep -qx 'TAKE_PROFIT_PCT=0.03' "$ENV_FILE" && \
  grep -qx 'MAX_DAILY_LOSS_PCT=0.03' "$ENV_FILE" || die "risk changed"

printf 'DEPLOYED_SHA=%s\nPAPER_STATUS=PASS\nLIVE_GATE=LOCKED\nMONITOR=ACTIVE\nTRADER_RESTARTED=NO\nMONITOR_PATH=/monitor/\n' "$EXPECTED_SHA"
changed=0

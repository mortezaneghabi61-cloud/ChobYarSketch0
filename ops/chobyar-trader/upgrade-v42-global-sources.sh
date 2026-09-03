#!/usr/bin/env bash
set -Eeuo pipefail

REPO_URL="https://github.com/mortezaneghabi61-cloud/ChobYarSketch0.git"
EXPECTED_SHA="${1:-}"
APP_DIR="/opt/chobyar-trader"
ENV_FILE="$APP_DIR/.env"
VENV="$APP_DIR/.venv"
TRADER_UNIT="/etc/systemd/system/chobyar-trader.service"

die() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }
[[ $EUID -eq 0 ]] || die "run as root"
[[ "$EXPECTED_SHA" =~ ^[0-9a-f]{40}$ ]] || die "exact 40-character commit SHA required"
[[ -f "$ENV_FILE" && -x "$VENV/bin/python" && -f "$TRADER_UNIT" ]] || die "existing trader installation is incomplete"

grep -qx 'TRADING_MODE=paper' "$ENV_FILE" || die "FAIL-CLOSED: TRADING_MODE must be paper"
grep -qx 'LIVE_TRADING_ENABLED=false' "$ENV_FILE" || die "FAIL-CLOSED: LIVE_TRADING_ENABLED must be false"
grep -qx 'MAX_POSITION_PCT=0.25' "$ENV_FILE" || die "FAIL-CLOSED: MAX_POSITION_PCT mismatch"
grep -qx 'STOP_LOSS_PCT=0.015' "$ENV_FILE" || die "FAIL-CLOSED: STOP_LOSS_PCT mismatch"
grep -qx 'TAKE_PROFIT_PCT=0.03' "$ENV_FILE" || die "FAIL-CLOSED: TAKE_PROFIT_PCT mismatch"
grep -qx 'MAX_DAILY_LOSS_PCT=0.03' "$ENV_FILE" || die "FAIL-CLOSED: MAX_DAILY_LOSS_PCT mismatch"

umask 077
work="$(mktemp -d)"
backup="$APP_DIR/backups/v42-global-$(date -u +%Y%m%dT%H%M%SZ)"
changed=0
had_entry=0
had_sources=0

rollback() {
  if [[ "$changed" == 1 ]]; then
    cp -a "$backup/chobyar-trader.service" "$TRADER_UNIT" || true
    cp -a "$backup/trader.py" "$APP_DIR/app/trader.py" || true
    if [[ "$had_entry" == 1 ]]; then cp -a "$backup/trader_entry.py" "$APP_DIR/app/trader_entry.py" || true; else rm -f "$APP_DIR/app/trader_entry.py"; fi
    if [[ "$had_sources" == 1 ]]; then cp -a "$backup/global_sources.py" "$APP_DIR/app/global_sources.py" || true; else rm -f "$APP_DIR/app/global_sources.py"; fi
    systemctl daemon-reload || true
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
for f in v4/trader.py v4/trader_entry.py v4/global_sources.py v4/test_v4.py v4/test_global_sources.py; do
  [[ -f "$src/$f" ]] || die "candidate missing $f"
done

"$VENV/bin/python" -m py_compile "$src/v4/trader.py" "$src/v4/trader_entry.py" "$src/v4/global_sources.py"
PYTHONPATH="$src/v4" "$VENV/bin/python" "$src/v4/test_v4.py" -v
PYTHONPATH="$src/v4" "$VENV/bin/python" "$src/v4/test_global_sources.py" -v
if grep -RniE '(/order|submit_order|create_order|place_order|leverage|martingale)' "$src/v4"; then
  die "forbidden live-order or leverage surface detected"
fi

mkdir -p "$backup"
chmod 700 "$backup"
cp -a "$TRADER_UNIT" "$backup/chobyar-trader.service"
cp -a "$APP_DIR/app/trader.py" "$backup/trader.py"
if [[ -f "$APP_DIR/app/trader_entry.py" ]]; then had_entry=1; cp -a "$APP_DIR/app/trader_entry.py" "$backup/trader_entry.py"; fi
if [[ -f "$APP_DIR/app/global_sources.py" ]]; then had_sources=1; cp -a "$APP_DIR/app/global_sources.py" "$backup/global_sources.py"; fi

install -m 700 "$src/v4/trader.py" "$APP_DIR/app/trader.py"
install -m 700 "$src/v4/trader_entry.py" "$APP_DIR/app/trader_entry.py"
install -m 700 "$src/v4/global_sources.py" "$APP_DIR/app/global_sources.py"
python3 - "$TRADER_UNIT" <<'PY'
from pathlib import Path
import sys
p = Path(sys.argv[1])
text = p.read_text(encoding='utf-8')
old = '/opt/chobyar-trader/app/trader.py'
new = '/opt/chobyar-trader/app/trader_entry.py'
if old in text:
    text = text.replace(old, new)
elif new not in text:
    raise SystemExit('unexpected trader ExecStart')
p.write_text(text, encoding='utf-8')
PY
changed=1
systemctl daemon-reload
systemctl restart chobyar-trader.service
sleep 35
systemctl is-active --quiet chobyar-trader.service || die "trader service inactive after v4.2 upgrade"
systemctl is-active --quiet chobyar-status.service || die "status service inactive after v4.2 upgrade"

port="$(awk -F= '$1=="STATUS_PORT" {print $2}' "$ENV_FILE" | tail -n1)"; port="${port:-8787}"
[[ "$(curl -sS -o /dev/null -w '%{http_code}' "http://127.0.0.1:${port}/health")" == 200 ]] || die "health validation failed"
[[ "$(curl -sS -o /dev/null -w '%{http_code}' "http://127.0.0.1:${port}/status")" == 401 ]] || die "unauthenticated status was not rejected"
curl -fsS "http://127.0.0.1:${port}/public-report" -o "$work/report.json"
"$VENV/bin/python" - "$work/report.json" <<'PY'
import json, sys
r = json.load(open(sys.argv[1], encoding='utf-8'))
assert r.get('ok') is True
assert r.get('public_report') is True
assert r.get('mode') == 'paper'
assert r.get('live_locked') is True
summary = ((r.get('decision') or {}).get('agent_summary') or {})
unavailable = int(summary.get('unavailable') or 0)
print(f'GLOBAL_AGENT_UNAVAILABLE={unavailable}')
PY
"$APP_DIR/bin/chobyar-status" >/dev/null || die "authenticated status validation failed"
grep -qx 'TRADING_MODE=paper' "$ENV_FILE" && grep -qx 'LIVE_TRADING_ENABLED=false' "$ENV_FILE" || die "paper lock changed"

printf 'DEPLOYED_SHA=%s\nPAPER_STATUS=PASS\nLIVE_GATE=LOCKED\nGLOBAL_SOURCE_FALLBACKS=ACTIVE\n' "$EXPECTED_SHA"
changed=0

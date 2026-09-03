#!/usr/bin/env bash
set -Eeuo pipefail

REPO_URL="https://github.com/mortezaneghabi61-cloud/ChobYarSketch0.git"
EXPECTED_SHA="${1:-}"
APP_DIR="/opt/chobyar-trader"
ENV_FILE="$APP_DIR/.env"
VENV="$APP_DIR/.venv"
STATUS_UNIT="/etc/systemd/system/chobyar-status.service"
SCORE_UNIT="/etc/systemd/system/chobyar-agent-scorecard.service"
SCORE_TIMER="/etc/systemd/system/chobyar-agent-scorecard.timer"

die() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }
[[ $EUID -eq 0 ]] || die "run as root"
[[ "$EXPECTED_SHA" =~ ^[0-9a-f]{40}$ ]] || die "exact 40-character commit SHA required"
[[ -f "$ENV_FILE" && -x "$VENV/bin/python" && -f "$STATUS_UNIT" ]] || die "existing trader/status installation incomplete"

grep -qx 'TRADING_MODE=paper' "$ENV_FILE" || die "FAIL-CLOSED: TRADING_MODE must be paper"
grep -qx 'LIVE_TRADING_ENABLED=false' "$ENV_FILE" || die "FAIL-CLOSED: LIVE_TRADING_ENABLED must be false"
grep -qx 'MAX_POSITION_PCT=0.25' "$ENV_FILE" || die "FAIL-CLOSED: MAX_POSITION_PCT mismatch"
grep -qx 'STOP_LOSS_PCT=0.015' "$ENV_FILE" || die "FAIL-CLOSED: STOP_LOSS_PCT mismatch"
grep -qx 'TAKE_PROFIT_PCT=0.03' "$ENV_FILE" || die "FAIL-CLOSED: TAKE_PROFIT_PCT mismatch"
grep -qx 'MAX_DAILY_LOSS_PCT=0.03' "$ENV_FILE" || die "FAIL-CLOSED: MAX_DAILY_LOSS_PCT mismatch"

umask 077
work="$(mktemp -d)"
backup="$APP_DIR/backups/v44-scorecard-$(date -u +%Y%m%dT%H%M%SZ)"
changed=0
had_wrapper=0
had_score=0
had_score_unit=0
had_score_timer=0

rollback() {
  if [[ "$changed" == 1 ]]; then
    cp -a "$backup/chobyar-status.service" "$STATUS_UNIT" || true
    if [[ "$had_wrapper" == 1 ]]; then cp -a "$backup/status_server_v44.py" "$APP_DIR/app/status_server_v44.py" || true; else rm -f "$APP_DIR/app/status_server_v44.py"; fi
    if [[ "$had_score" == 1 ]]; then cp -a "$backup/agent_scorecard.py" "$APP_DIR/app/agent_scorecard.py" || true; else rm -f "$APP_DIR/app/agent_scorecard.py"; fi
    if [[ "$had_score_unit" == 1 ]]; then cp -a "$backup/chobyar-agent-scorecard.service" "$SCORE_UNIT" || true; else rm -f "$SCORE_UNIT"; fi
    if [[ "$had_score_timer" == 1 ]]; then cp -a "$backup/chobyar-agent-scorecard.timer" "$SCORE_TIMER" || true; else rm -f "$SCORE_TIMER"; fi
    systemctl daemon-reload || true
    if [[ "$had_score_timer" == 1 ]]; then systemctl enable --now chobyar-agent-scorecard.timer >/dev/null 2>&1 || true; else systemctl disable --now chobyar-agent-scorecard.timer >/dev/null 2>&1 || true; fi
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
for f in v4/agent_scorecard.py v4/test_agent_scorecard.py v3/status_server_v44.py v3/test_status_scorecard.py; do
  [[ -f "$src/$f" ]] || die "candidate missing $f"
done

"$VENV/bin/python" -m py_compile "$src/v4/agent_scorecard.py" "$src/v3/status_server_v44.py"
PYTHONPATH="$src/v4" "$VENV/bin/python" "$src/v4/test_agent_scorecard.py" -v
PYTHONPATH="$src/v3" /usr/bin/python3 "$src/v3/test_status_scorecard.py" -v
if grep -RniE '(/order|submit_order|create_order|place_order|leverage|martingale|automatic_reweighting_enabled.: true)' "$src/v4/agent_scorecard.py" "$src/v3/status_server_v44.py"; then
  die "forbidden execution or automatic-reweighting surface detected"
fi

mkdir -p "$backup"
chmod 700 "$backup"
cp -a "$STATUS_UNIT" "$backup/chobyar-status.service"
if [[ -f "$APP_DIR/app/status_server_v44.py" ]]; then had_wrapper=1; cp -a "$APP_DIR/app/status_server_v44.py" "$backup/status_server_v44.py"; fi
if [[ -f "$APP_DIR/app/agent_scorecard.py" ]]; then had_score=1; cp -a "$APP_DIR/app/agent_scorecard.py" "$backup/agent_scorecard.py"; fi
if [[ -f "$SCORE_UNIT" ]]; then had_score_unit=1; cp -a "$SCORE_UNIT" "$backup/chobyar-agent-scorecard.service"; fi
if [[ -f "$SCORE_TIMER" ]]; then had_score_timer=1; cp -a "$SCORE_TIMER" "$backup/chobyar-agent-scorecard.timer"; fi

install -m 700 "$src/v4/agent_scorecard.py" "$APP_DIR/app/agent_scorecard.py"
install -m 700 "$src/v3/status_server_v44.py" "$APP_DIR/app/status_server_v44.py"

install -m 644 /dev/stdin "$SCORE_UNIT" <<'UNIT'
[Unit]
Description=ChobYar Paper Agent Forward Scorecard
After=chobyar-trader.service
[Service]
Type=oneshot
WorkingDirectory=/opt/chobyar-trader/app
ExecStart=/opt/chobyar-trader/.venv/bin/python /opt/chobyar-trader/app/agent_scorecard.py --audit /opt/chobyar-trader/logs/audit.jsonl --output /opt/chobyar-trader/state/agent_scorecard.json
User=root
NoNewPrivileges=true
PrivateTmp=true
ProtectHome=true
ProtectSystem=strict
ReadOnlyPaths=/opt/chobyar-trader/logs
ReadWritePaths=/opt/chobyar-trader/state
Environment=PYTHONDONTWRITEBYTECODE=1
UNIT

install -m 644 /dev/stdin "$SCORE_TIMER" <<'UNIT'
[Unit]
Description=Refresh ChobYar agent scorecard hourly
[Timer]
OnBootSec=5min
OnUnitActiveSec=1h
RandomizedDelaySec=3min
Persistent=true
Unit=chobyar-agent-scorecard.service
[Install]
WantedBy=timers.target
UNIT

python3 - "$STATUS_UNIT" <<'PY'
from pathlib import Path
import sys
p = Path(sys.argv[1])
text = p.read_text(encoding='utf-8')
old = '/opt/chobyar-trader/app/status_server.py'
new = '/opt/chobyar-trader/app/status_server_v44.py'
if old in text:
    text = text.replace(old, new)
elif new not in text:
    raise SystemExit('unexpected status ExecStart')
p.write_text(text, encoding='utf-8')
PY

changed=1
systemctl daemon-reload
systemctl start chobyar-agent-scorecard.service
systemctl enable --now chobyar-agent-scorecard.timer >/dev/null
systemctl restart chobyar-status.service
sleep 3
systemctl is-active --quiet chobyar-trader.service || die "trader service inactive"
systemctl is-active --quiet chobyar-status.service || die "status service inactive"
systemctl is-active --quiet chobyar-agent-scorecard.timer || die "scorecard timer inactive"
[[ -s "$APP_DIR/state/agent_scorecard.json" ]] || die "scorecard output missing"

port="$(awk -F= '$1=="STATUS_PORT" {print $2}' "$ENV_FILE" | tail -n1)"; port="${port:-8787}"
[[ "$(curl -sS -o /dev/null -w '%{http_code}' "http://127.0.0.1:${port}/health")" == 200 ]] || die "health validation failed"
[[ "$(curl -sS -o /dev/null -w '%{http_code}' "http://127.0.0.1:${port}/status")" == 401 ]] || die "unauthenticated status was not rejected"
curl -fsS "http://127.0.0.1:${port}/public-report" -o "$work/report.json"
"$VENV/bin/python" - "$work/report.json" <<'PY'
import json, sys
r = json.load(open(sys.argv[1], encoding='utf-8'))
assert r.get('ok') is True and r.get('public_report') is True
assert r.get('mode') == 'paper' and r.get('live_locked') is True
assert not ({'paper','market','risk','security'} & set(r))
sc = r.get('agent_scorecard') or {}
assert sc.get('weights_changed') is False
assert sc.get('automatic_reweighting_enabled') is False
print(f"SCORECARD_SAMPLED_ROWS={int(sc.get('sampled_rows') or 0)}")
print(f"SCORECARD_REVIEWABLE_AGENTS={len(sc.get('reviewable_agents') or [])}")
PY
"$APP_DIR/bin/chobyar-status" >/dev/null || die "authenticated status validation failed"
grep -qx 'TRADING_MODE=paper' "$ENV_FILE" && grep -qx 'LIVE_TRADING_ENABLED=false' "$ENV_FILE" || die "paper lock changed"

printf 'DEPLOYED_SHA=%s\nPAPER_STATUS=PASS\nLIVE_GATE=LOCKED\nAGENT_SCORECARD=ACTIVE\nAUTO_REWEIGHTING=DISABLED\n' "$EXPECTED_SHA"
changed=0

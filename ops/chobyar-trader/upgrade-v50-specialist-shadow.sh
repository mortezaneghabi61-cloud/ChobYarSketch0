#!/usr/bin/env bash
set -Eeuo pipefail

REPO_URL="https://github.com/mortezaneghabi61-cloud/ChobYarSketch0.git"
EXPECTED_SHA="${1:-}"
APP_DIR="/opt/chobyar-trader"
ENV_FILE="$APP_DIR/.env"
VENV="$APP_DIR/.venv"
V5_DIR="$APP_DIR/app/v5"
SHADOW_SERVICE="/etc/systemd/system/chobyar-v5-shadow.service"
SHADOW_TIMER="/etc/systemd/system/chobyar-v5-shadow.timer"
SCORE_SERVICE="/etc/systemd/system/chobyar-v5-scorecard.service"
SCORE_TIMER="/etc/systemd/system/chobyar-v5-scorecard.timer"

fail() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }
[[ $EUID -eq 0 ]] || fail "run as root"
[[ "$EXPECTED_SHA" =~ ^[0-9a-f]{40}$ ]] || fail "exact 40-character commit SHA required"
[[ -f "$ENV_FILE" && -x "$VENV/bin/python" ]] || fail "existing trader installation incomplete"

grep -qx 'TRADING_MODE=paper' "$ENV_FILE" || fail "FAIL-CLOSED: TRADING_MODE must be paper"
grep -qx 'LIVE_TRADING_ENABLED=false' "$ENV_FILE" || fail "FAIL-CLOSED: LIVE_TRADING_ENABLED must be false"
grep -qx 'MAX_POSITION_PCT=0.25' "$ENV_FILE" || fail "FAIL-CLOSED: MAX_POSITION_PCT mismatch"
grep -qx 'STOP_LOSS_PCT=0.015' "$ENV_FILE" || fail "FAIL-CLOSED: STOP_LOSS_PCT mismatch"
grep -qx 'TAKE_PROFIT_PCT=0.03' "$ENV_FILE" || fail "FAIL-CLOSED: TAKE_PROFIT_PCT mismatch"
grep -qx 'MAX_DAILY_LOSS_PCT=0.03' "$ENV_FILE" || fail "FAIL-CLOSED: MAX_DAILY_LOSS_PCT mismatch"

trader_pid_before="$(systemctl show chobyar-trader.service -p MainPID --value)"
trader_started_before="$(systemctl show chobyar-trader.service -p ExecMainStartTimestampMonotonic --value)"
[[ "$trader_pid_before" =~ ^[1-9][0-9]*$ ]] || fail "trader service is not running"
status_pid_before="$(systemctl show chobyar-status.service -p MainPID --value 2>/dev/null || true)"

umask 077
work="$(mktemp -d)"
backup="$APP_DIR/backups/v50-shadow-$(date -u +%Y%m%dT%H%M%SZ)"
changed=0

rollback() {
  if [[ "$changed" == 1 ]]; then
    systemctl disable --now chobyar-v5-shadow.timer chobyar-v5-scorecard.timer >/dev/null 2>&1 || true
    for name in chobyar-v5-shadow.service chobyar-v5-shadow.timer chobyar-v5-scorecard.service chobyar-v5-scorecard.timer; do
      if [[ -f "$backup/$name" ]]; then cp -a "$backup/$name" "/etc/systemd/system/$name"; else rm -f "/etc/systemd/system/$name"; fi
    done
    if [[ -d "$backup/v5" ]]; then rm -rf "$V5_DIR"; cp -a "$backup/v5" "$V5_DIR"; else rm -rf "$V5_DIR"; fi
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
for f in v5/specialist_council.py v5/shadow_runner.py v5/specialist_scorecard.py v5/test_specialist_council.py v5/test_specialist_scorecard.py; do
  [[ -f "$src/$f" ]] || fail "candidate missing $f"
done

PYTHONPATH="$src/v5" "$VENV/bin/python" -m py_compile "$src/v5/specialist_council.py" "$src/v5/shadow_runner.py" "$src/v5/specialist_scorecard.py"
PYTHONPATH="$src/v5" "$VENV/bin/python" "$src/v5/test_specialist_council.py" -v
PYTHONPATH="$src/v5" "$VENV/bin/python" "$src/v5/test_specialist_scorecard.py" -v

if grep -RniE 'API_KEY|APIKEY|STATUS_HMAC_SECRET|submit_order|create_order|place_order|/api/v5/trade/|enable_live|withdraw|martingale|leverage' "$src/v5" --include='*.py'; then
  fail "forbidden credential/execution marker in v5 shadow code"
fi
grep -q 'execution_authority.*False' "$src/v5/specialist_council.py" || fail "execution authority lock missing"
grep -q 'foreign_execution_enabled.*False' "$src/v5/specialist_council.py" || fail "foreign execution lock missing"
grep -q 'geo_bypass_supported.*False' "$src/v5/specialist_council.py" || fail "geo-bypass refusal marker missing"
grep -q 'automatic_promotion_enabled.*False' "$src/v5/specialist_council.py" || fail "automatic promotion lock missing"

mkdir -p "$backup"
chmod 700 "$backup"
[[ -d "$V5_DIR" ]] && cp -a "$V5_DIR" "$backup/v5"
for unit in "$SHADOW_SERVICE" "$SHADOW_TIMER" "$SCORE_SERVICE" "$SCORE_TIMER"; do
  [[ -f "$unit" ]] && cp -a "$unit" "$backup/$(basename "$unit")"
done
changed=1

mkdir -p "$V5_DIR"
chmod 700 "$V5_DIR"
install -m 700 "$src/v5/specialist_council.py" "$V5_DIR/specialist_council.py"
install -m 700 "$src/v5/shadow_runner.py" "$V5_DIR/shadow_runner.py"
install -m 700 "$src/v5/specialist_scorecard.py" "$V5_DIR/specialist_scorecard.py"

cat >"$SHADOW_SERVICE" <<'UNIT'
[Unit]
Description=ChobYar Trader v5 Shadow Specialist Council
After=network-online.target chobyar-trader.service
Wants=network-online.target

[Service]
Type=oneshot
ExecStart=/opt/chobyar-trader/.venv/bin/python /opt/chobyar-trader/app/v5/shadow_runner.py --app-dir /opt/chobyar-trader
NoNewPrivileges=true
PrivateTmp=true
ProtectHome=true
ProtectSystem=strict
ReadWritePaths=/opt/chobyar-trader/state /opt/chobyar-trader/logs
UMask=0077
Nice=10
MemoryMax=256M
CPUQuota=50%
UNIT

cat >"$SHADOW_TIMER" <<'UNIT'
[Unit]
Description=Run ChobYar v5 Shadow Specialist Council every 5 minutes

[Timer]
OnBootSec=2min
OnUnitActiveSec=5min
RandomizedDelaySec=20s
Persistent=true
Unit=chobyar-v5-shadow.service

[Install]
WantedBy=timers.target
UNIT

cat >"$SCORE_SERVICE" <<'UNIT'
[Unit]
Description=ChobYar Trader v5 Specialist Scorecard
After=chobyar-v5-shadow.service

[Service]
Type=oneshot
ExecStart=/opt/chobyar-trader/.venv/bin/python /opt/chobyar-trader/app/v5/specialist_scorecard.py --app-dir /opt/chobyar-trader
NoNewPrivileges=true
PrivateTmp=true
ProtectHome=true
ProtectSystem=strict
ReadWritePaths=/opt/chobyar-trader/state
UMask=0077
Nice=15
MemoryMax=192M
CPUQuota=30%
UNIT

cat >"$SCORE_TIMER" <<'UNIT'
[Unit]
Description=Run ChobYar v5 Specialist Scorecard hourly

[Timer]
OnBootSec=10min
OnUnitActiveSec=1h
RandomizedDelaySec=2min
Persistent=true
Unit=chobyar-v5-scorecard.service

[Install]
WantedBy=timers.target
UNIT

! grep -Rni 'EnvironmentFile' "$SHADOW_SERVICE" "$SCORE_SERVICE" || fail "v5 shadow services must not load .env"
systemctl daemon-reload
systemctl start chobyar-v5-shadow.service
systemctl start chobyar-v5-scorecard.service
systemctl enable --now chobyar-v5-shadow.timer chobyar-v5-scorecard.timer >/dev/null
systemctl is-active --quiet chobyar-v5-shadow.timer || fail "v5 shadow timer inactive"
systemctl is-active --quiet chobyar-v5-scorecard.timer || fail "v5 scorecard timer inactive"

trader_pid_after="$(systemctl show chobyar-trader.service -p MainPID --value)"
trader_started_after="$(systemctl show chobyar-trader.service -p ExecMainStartTimestampMonotonic --value)"
[[ "$trader_pid_after" == "$trader_pid_before" && "$trader_started_after" == "$trader_started_before" ]] || fail "FAIL-CLOSED: trader restarted during v5 shadow install"
status_pid_after="$(systemctl show chobyar-status.service -p MainPID --value 2>/dev/null || true)"
[[ "$status_pid_after" == "$status_pid_before" ]] || fail "FAIL-CLOSED: status service restarted during v5 shadow install"

"$VENV/bin/python" - "$APP_DIR/state/v5_shadow_latest.json" "$APP_DIR/state/v5_specialist_scorecard.json" <<'PY'
import json, sys
shadow = json.load(open(sys.argv[1], encoding='utf-8'))
score = json.load(open(sys.argv[2], encoding='utf-8'))
assert shadow.get('ok') is True
assert shadow.get('mode') == 'shadow_observation_only'
assert shadow.get('execution_authority') is False
assert shadow.get('foreign_execution_enabled') is False
assert shadow.get('geo_bypass_supported') is False
assert shadow.get('automatic_promotion_enabled') is False
assert len(shadow.get('specialists') or []) == 5
assert score.get('mode') == 'shadow_observation_only'
assert score.get('execution_authority') is False
assert score.get('automatic_promotion_enabled') is False
print('V5_RUNTIME_VALIDATION=PASS')
print('REGIME=' + str((shadow.get('regime') or {}).get('label')))
print('SHADOW_ACTION=' + str((shadow.get('shadow_consensus') or {}).get('action')))
print('RISK_VETO=' + str(bool((shadow.get('shadow_consensus') or {}).get('risk_veto'))).upper())
print('SPECIALISTS=' + str(len(shadow.get('specialists') or [])))
print('SCORECARD_SAMPLED_ROWS=' + str(score.get('sampled_rows')))
PY

grep -qx 'TRADING_MODE=paper' "$ENV_FILE" || fail "paper mode changed"
grep -qx 'LIVE_TRADING_ENABLED=false' "$ENV_FILE" || fail "live lock changed"
grep -qx 'MAX_POSITION_PCT=0.25' "$ENV_FILE" || fail "risk changed"
grep -qx 'STOP_LOSS_PCT=0.015' "$ENV_FILE" || fail "risk changed"
grep -qx 'TAKE_PROFIT_PCT=0.03' "$ENV_FILE" || fail "risk changed"
grep -qx 'MAX_DAILY_LOSS_PCT=0.03' "$ENV_FILE" || fail "risk changed"

printf 'DEPLOYED_SHA=%s\nPAPER_STATUS=PASS\nLIVE_GATE=LOCKED\nV5_SHADOW=ACTIVE\nSPECIALIST_SCORECARD=ACTIVE\nEXECUTION_AUTHORITY=NONE\nFOREIGN_EXECUTION=DISABLED\nGEO_BYPASS=UNSUPPORTED\nAUTO_PROMOTION=DISABLED\nTRADER_RESTARTED=NO\nSTATUS_RESTARTED=NO\n' "$EXPECTED_SHA"
changed=0

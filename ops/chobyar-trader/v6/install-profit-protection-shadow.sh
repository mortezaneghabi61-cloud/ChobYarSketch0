#!/usr/bin/env bash
set -Eeuo pipefail

REPO_URL="https://github.com/mortezaneghabi61-cloud/ChobYarSketch0.git"
EXPECTED_SHA="${1:-}"
APP_DIR="/opt/chobyar-trader"
ENV_FILE="$APP_DIR/.env"
VENV="$APP_DIR/.venv"
TARGET_DIR="$APP_DIR/app/v6"
TARGET_FILE="$TARGET_DIR/profit_protection_shadow.py"
UNIT_FILE="/etc/systemd/system/chobyar-profit-protection-shadow.service"
SERVICE="chobyar-profit-protection-shadow.service"
TRADER_SERVICE="chobyar-trader.service"

die() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }
[[ $EUID -eq 0 ]] || die "run as root"
[[ "$EXPECTED_SHA" =~ ^[0-9a-f]{40}$ ]] || die "exact 40-character commit SHA required"
[[ -f "$ENV_FILE" && -x "$VENV/bin/python" ]] || die "existing Paper installation is incomplete"
grep -qx 'TRADING_MODE=paper' "$ENV_FILE" || die "FAIL-CLOSED: TRADING_MODE must be paper"
grep -qx 'LIVE_TRADING_ENABLED=false' "$ENV_FILE" || die "FAIL-CLOSED: LIVE_TRADING_ENABLED must be false"
systemctl is-active --quiet "$TRADER_SERVICE" || die "active Paper trader is required"
trader_pid_before="$(systemctl show -p MainPID --value "$TRADER_SERVICE")"
[[ "$trader_pid_before" =~ ^[1-9][0-9]*$ ]] || die "invalid active trader PID"
"$VENV/bin/python" -c 'import httpx' || die "runtime dependency httpx is unavailable"

umask 077
work="$(mktemp -d)"
backup="$APP_DIR/backups/profit-protection-shadow-$(date -u +%Y%m%dT%H%M%SZ)"
had_target=false
had_unit=false
was_active=false
was_enabled=false
committed=false

cleanup() { rm -rf "$work"; }
rollback() {
  if [[ "$committed" == true ]]; then return; fi
  systemctl stop "$SERVICE" >/dev/null 2>&1 || true
  if [[ "$had_target" == true ]]; then
    install -m 700 "$backup/profit_protection_shadow.py" "$TARGET_FILE"
  else
    rm -f "$TARGET_FILE"
  fi
  if [[ "$had_unit" == true ]]; then
    install -m 644 "$backup/chobyar-profit-protection-shadow.service" "$UNIT_FILE"
  else
    systemctl disable "$SERVICE" >/dev/null 2>&1 || true
    rm -f "$UNIT_FILE"
  fi
  systemctl daemon-reload >/dev/null 2>&1 || true
  if [[ "$was_enabled" == true ]]; then systemctl enable "$SERVICE" >/dev/null 2>&1 || true; fi
  if [[ "$was_active" == true ]]; then systemctl start "$SERVICE" >/dev/null 2>&1 || true; fi
}
trap 'rollback; cleanup' EXIT

git init -q "$work/repo"
git -C "$work/repo" remote add origin "$REPO_URL"
git -C "$work/repo" fetch -q --depth=1 origin "$EXPECTED_SHA"
git -C "$work/repo" checkout -q --detach FETCH_HEAD
[[ "$(git -C "$work/repo" rev-parse HEAD)" == "$EXPECTED_SHA" ]] || die "downloaded commit mismatch"
src="$work/repo/ops/chobyar-trader/v6"
[[ -f "$src/profit_protection_shadow.py" && -f "$src/test_profit_protection_shadow.py" ]] || die "commit lacks V6.8 shadow files"

TRADING_MODE=paper LIVE_TRADING_ENABLED=false PYTHONPATH="$src" \
  "$VENV/bin/python" -m unittest discover -v -s "$src" -p 'test_profit_protection_shadow.py'
TRADING_MODE=paper LIVE_TRADING_ENABLED=false \
  "$VENV/bin/python" -m py_compile "$src/profit_protection_shadow.py"
if grep -niE '\.(post|put|patch|delete)\(|submit_order|create_order|place_order|cancel_order|withdraw|api[_-]?key|authorization|LIVE_TRADING_ENABLED=true|subprocess' \
  "$src/profit_protection_shadow.py"; then
  die "mutable, authenticated, live, or process surface detected"
fi

mkdir -p "$backup" "$TARGET_DIR" "$APP_DIR/logs"
chmod 700 "$backup" "$TARGET_DIR" "$APP_DIR/logs"
if [[ -f "$TARGET_FILE" ]]; then cp -a "$TARGET_FILE" "$backup/"; had_target=true; fi
if [[ -f "$UNIT_FILE" ]]; then cp -a "$UNIT_FILE" "$backup/"; had_unit=true; fi
if systemctl is-active --quiet "$SERVICE"; then was_active=true; fi
if systemctl is-enabled --quiet "$SERVICE"; then was_enabled=true; fi
install -m 700 "$src/profit_protection_shadow.py" "$TARGET_FILE"

install -m 644 /dev/stdin "$UNIT_FILE" <<'UNIT'
[Unit]
Description=ChobYar Profit Protection Shadow Observer
After=network-online.target chobyar-trader.service
Wants=network-online.target
Requires=chobyar-trader.service

[Service]
Type=simple
User=root
WorkingDirectory=/opt/chobyar-trader/app/v6
EnvironmentFile=/opt/chobyar-trader/.env
Environment=SHADOW_SAMPLE_SECONDS=5
ExecStart=/opt/chobyar-trader/.venv/bin/python /opt/chobyar-trader/app/v6/profit_protection_shadow.py
Restart=on-failure
RestartSec=5
UMask=0077
NoNewPrivileges=true
PrivateTmp=true
ProtectHome=true
ProtectSystem=strict
ReadOnlyPaths=/opt/chobyar-trader/.env /opt/chobyar-trader/state /opt/chobyar-trader/app
ReadWritePaths=/opt/chobyar-trader/logs
RestrictAddressFamilies=AF_INET AF_INET6

[Install]
WantedBy=multi-user.target
UNIT

systemctl daemon-reload
systemctl enable --now "$SERVICE" >/dev/null
sleep 8
systemctl is-active --quiet "$SERVICE" || die "shadow service inactive"
trader_pid_after="$(systemctl show -p MainPID --value "$TRADER_SERVICE")"
[[ "$trader_pid_after" == "$trader_pid_before" ]] || die "Paper trader PID changed unexpectedly"
grep -qx 'TRADING_MODE=paper' "$ENV_FILE" || die "Paper mode changed"
grep -qx 'LIVE_TRADING_ENABLED=false' "$ENV_FILE" || die "live gate changed"
[[ -s "$APP_DIR/logs/profit_protection_shadow.jsonl" ]] || die "shadow evidence stream missing"
tail -n 1 "$APP_DIR/logs/profit_protection_shadow.jsonl" | "$VENV/bin/python" -c \
  'import json,sys; d=json.load(sys.stdin); assert d["mode"]=="shadow_observation_only"; assert d["execution_authority"] is False; assert d["automatic_promotion"] is False'

committed=true
printf 'DEPLOYED_SHA=%s\nSHADOW_STATUS=PASS\nTRADER_PID_UNCHANGED=%s\nEXECUTION_AUTHORITY=NONE\n' \
  "$EXPECTED_SHA" "$trader_pid_after"

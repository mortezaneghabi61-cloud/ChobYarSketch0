#!/usr/bin/env bash
set -Eeuo pipefail

REPO_URL="https://github.com/mortezaneghabi61-cloud/ChobYarSketch0.git"
EXPECTED_SHA="${1:-}"
APP_DIR="/opt/chobyar-trader"
ENV_FILE="$APP_DIR/.env"
VENV="$APP_DIR/.venv"
TARGET_DIR="$APP_DIR/app/v6"
TARGET_FILE="$TARGET_DIR/paper_exploration.py"
UNIT_FILE="/etc/systemd/system/chobyar-paper-exploration.service"
SERVICE="chobyar-paper-exploration.service"
TRADER_SERVICE="chobyar-trader.service"
SHADOW_SERVICE="chobyar-profit-protection-shadow.service"

die() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }
[[ $EUID -eq 0 ]] || die "run as root"
[[ "$EXPECTED_SHA" =~ ^[0-9a-f]{40}$ ]] || die "exact 40-character commit SHA required"
[[ -f "$ENV_FILE" && -x "$VENV/bin/python" ]] || die "existing Paper installation is incomplete"
grep -qx 'TRADING_MODE=paper' "$ENV_FILE" || die "FAIL-CLOSED: TRADING_MODE must be paper"
grep -qx 'LIVE_TRADING_ENABLED=false' "$ENV_FILE" || die "FAIL-CLOSED: LIVE_TRADING_ENABLED must be false"
systemctl is-active --quiet "$TRADER_SERVICE" || die "active Paper trader is required"
systemctl is-active --quiet "$SHADOW_SERVICE" || die "active profit-protection Shadow is required"
trader_pid_before="$(systemctl show -p MainPID --value "$TRADER_SERVICE")"
shadow_pid_before="$(systemctl show -p MainPID --value "$SHADOW_SERVICE")"
[[ "$trader_pid_before" =~ ^[1-9][0-9]*$ && "$shadow_pid_before" =~ ^[1-9][0-9]*$ ]] || die "invalid protected service PID"

umask 077
work="$(mktemp -d)"
backup="$APP_DIR/backups/paper-exploration-$(date -u +%Y%m%dT%H%M%SZ)"
had_target=false
had_unit=false
was_active=false
was_enabled=false
committed=false
mutation_started=false

cleanup() { rm -rf "$work"; }
rollback() {
  if [[ "$committed" == true ]]; then return; fi
  if [[ "$mutation_started" != true ]]; then return; fi
  systemctl stop "$SERVICE" >/dev/null 2>&1 || true
  if [[ "$had_target" == true ]]; then
    install -m 700 "$backup/paper_exploration.py" "$TARGET_FILE"
  else
    rm -f "$TARGET_FILE"
  fi
  if [[ "$had_unit" == true ]]; then
    install -m 644 "$backup/chobyar-paper-exploration.service" "$UNIT_FILE"
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
[[ -f "$src/paper_exploration.py" && -f "$src/test_paper_exploration.py" ]] || die "commit lacks V6.11 exploration files"

TRADING_MODE=paper LIVE_TRADING_ENABLED=false PYTHONPATH="$src" \
  "$VENV/bin/python" -m unittest discover -v -s "$src" -p 'test_paper_exploration.py'
TRADING_MODE=paper LIVE_TRADING_ENABLED=false "$VENV/bin/python" -m py_compile "$src/paper_exploration.py"
if grep -niE '\.(post|put|patch|delete)\(|submit_order|create_order|place_order|cancel_order|withdraw|api[_-]?key|authorization|subprocess|httpx|requests' "$src/paper_exploration.py"; then
  die "execution, authenticated, process, or network surface detected"
fi

mutation_started=true
mkdir -p "$TARGET_DIR" "$APP_DIR/backups" "$APP_DIR/logs" "$APP_DIR/state" "$backup"
if [[ -f "$TARGET_FILE" ]]; then cp -a "$TARGET_FILE" "$backup/paper_exploration.py"; had_target=true; fi
if [[ -f "$UNIT_FILE" ]]; then cp -a "$UNIT_FILE" "$backup/chobyar-paper-exploration.service"; had_unit=true; fi
if systemctl is-active --quiet "$SERVICE"; then was_active=true; fi
if systemctl is-enabled --quiet "$SERVICE"; then was_enabled=true; fi
install -m 700 "$src/paper_exploration.py" "$TARGET_FILE"

cat >"$UNIT_FILE" <<'UNIT'
[Unit]
Description=ChobYar Isolated Paper Exploration
After=chobyar-trader.service chobyar-profit-protection-shadow.service
Requires=chobyar-trader.service chobyar-profit-protection-shadow.service

[Service]
Type=simple
User=root
Environment=TRADING_MODE=paper
Environment=LIVE_TRADING_ENABLED=false
Environment=CHOBYAR_APP_DIR=/opt/chobyar-trader
ExecStart=/opt/chobyar-trader/.venv/bin/python /opt/chobyar-trader/app/v6/paper_exploration.py
Restart=on-failure
RestartSec=5
NoNewPrivileges=true
PrivateTmp=true
PrivateNetwork=true
ProtectHome=true
ProtectSystem=strict
ReadOnlyPaths=/opt/chobyar-trader/.env /opt/chobyar-trader/logs/audit.jsonl /opt/chobyar-trader/app
ReadWritePaths=/opt/chobyar-trader/logs /opt/chobyar-trader/state

[Install]
WantedBy=multi-user.target
UNIT
chmod 644 "$UNIT_FILE"
systemctl daemon-reload
systemctl enable --now "$SERVICE"
sleep 8
systemctl is-active --quiet "$SERVICE" || die "Paper Exploration service is not active"
[[ "$(systemctl show -p MainPID --value "$TRADER_SERVICE")" == "$trader_pid_before" ]] || die "Trader PID changed"
[[ "$(systemctl show -p MainPID --value "$SHADOW_SERVICE")" == "$shadow_pid_before" ]] || die "Shadow PID changed"
grep -qx 'TRADING_MODE=paper' "$ENV_FILE" || die "Paper mode changed"
grep -qx 'LIVE_TRADING_ENABLED=false' "$ENV_FILE" || die "live gate changed"
[[ -s "$APP_DIR/state/paper_exploration_state.json" ]] || die "exploration state missing"

committed=true
printf 'DEPLOYED_SHA=%s\nEXPLORATION_STATUS=PASS\nTRADER_PID_UNCHANGED=%s\nSHADOW_PID_UNCHANGED=%s\nEXECUTION_AUTHORITY=NONE\n' \
  "$EXPECTED_SHA" "$trader_pid_before" "$shadow_pid_before"

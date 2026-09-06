#!/usr/bin/env bash
set -euo pipefail

SHA="${1:-}"
REPO="https://github.com/mortezaneghabi61-cloud/ChobYarSketch0.git"
APP="/opt/chobyar-trader"
SRC_REL="ops/chobyar-trader/v5/execution_safety/live_observer_stage21.py"
DST_DIR="$APP/app/v5/execution_safety"
DST="$DST_DIR/live_observer_stage21.py"
PY="$APP/.venv/bin/python"
SERVICE="/etc/systemd/system/chobyar-live-observer.service"
TIMER="/etc/systemd/system/chobyar-live-observer.timer"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

fail() { echo "FAIL-CLOSED: $*" >&2; exit 1; }

[[ $EUID -eq 0 ]] || fail "run as root"
[[ "$SHA" =~ ^[0-9a-f]{40}$ ]] || fail "exact 40-char commit SHA required"
[[ -f "$APP/.env" ]] || fail ".env missing"
[[ -x "$PY" ]] || fail "venv python missing"

# Existing production Paper trader must remain safely locked during Stage-21 deployment.
mode="$(grep -E '^TRADING_MODE=' "$APP/.env" | tail -n1 | cut -d= -f2- | tr -d '[:space:]')"
live="$(grep -E '^LIVE_TRADING_ENABLED=' "$APP/.env" | tail -n1 | cut -d= -f2- | tr -d '[:space:]')"
[[ "$mode" == "paper" ]] || fail "production TRADING_MODE must remain paper"
[[ "$live" == "false" ]] || fail "production LIVE_TRADING_ENABLED must remain false"

for pair in \
  'MAX_POSITION_PCT=0.25' \
  'STOP_LOSS_PCT=0.015' \
  'TAKE_PROFIT_PCT=0.03' \
  'MAX_DAILY_LOSS_PCT=0.03'; do
  grep -qx "$pair" "$APP/.env" || fail "approved risk value missing: $pair"
done

git -c advice.detachedHead=false clone -q --filter=blob:none --no-checkout "$REPO" "$TMP/repo"
cd "$TMP/repo"
git fetch -q --depth=1 origin "$SHA"
git checkout -q --detach "$SHA"
[[ "$(git rev-parse HEAD)" == "$SHA" ]] || fail "exact SHA checkout mismatch"
[[ -f "$SRC_REL" ]] || fail "Stage-21 observer missing from exact SHA"

"$PY" -m py_compile "$SRC_REL"
PYTHONPATH="ops/chobyar-trader/v5/execution_safety" "$PY" "ops/chobyar-trader/v5/execution_safety/test_live_observer_stage21.py" -q

# Static no-mutation fence.
if grep -nEi '\.post\(|\.put\(|\.patch\(|\.delete\(|/v1/account/orders|cancel_order|submit_order|create_order|place_order|withdraw\(' "$SRC_REL"; then
  fail "mutable exchange surface detected in Stage-21 observer"
fi

grep -q 'execution_authority": False' "$SRC_REL" || fail "execution authority false fence missing"
grep -q 'order_submission_authority": False' "$SRC_REL" || fail "submission authority false fence missing"

install -d -m 0755 "$DST_DIR"
install -m 0644 "$SRC_REL" "$DST"

cat > "$SERVICE" <<EOF
[Unit]
Description=ChobYar Trader v5 GET-only Live Observer
After=network-online.target
Wants=network-online.target

[Service]
Type=oneshot
WorkingDirectory=$DST_DIR
Environment=CHOBYAR_APP_DIR=$APP
Environment=TRADING_MODE=live
Environment=LIVE_TRADING_ENABLED=true
Environment=LIVE_EXECUTION_ARMED=false
Environment=SYMBOL=BTCUSDT
Environment=LIVE_MAX_ORDER_USDT=10
Environment=SPOT_ONLY=true
Environment=WITHDRAWALS_ENABLED=false
Environment=LEVERAGE_ENABLED=false
ExecStart=$PY $DST
NoNewPrivileges=true
PrivateTmp=true
ProtectSystem=strict
ProtectHome=true
ReadWritePaths=$APP/logs $APP/state

[Install]
WantedBy=multi-user.target
EOF

cat > "$TIMER" <<'EOF'
[Unit]
Description=Run ChobYar GET-only Live Observer periodically

[Timer]
OnBootSec=2min
OnUnitActiveSec=5min
Persistent=false
Unit=chobyar-live-observer.service

[Install]
WantedBy=timers.target
EOF

systemctl daemon-reload
# Install only. Deliberately do not enable/start the live observer timer in Stage-21.
systemctl disable --now chobyar-live-observer.timer >/dev/null 2>&1 || true

echo "STAGE21_INSTALL=PASS"
echo "EXACT_SHA=$SHA"
echo "PRODUCTION_MODE=PAPER"
echo "PRODUCTION_LIVE_GATE=FALSE"
echo "LIVE_OBSERVER_INSTALLED=YES"
echo "LIVE_OBSERVER_TIMER=DISABLED"
echo "LIVE_MAX_ORDER_USDT=10"
echo "EXECUTION_ARMED=FALSE"
echo "EXECUTION_AUTHORITY=NONE"
echo "ORDER_SUBMISSION=NOT_USED"

#!/usr/bin/env bash
set -euo pipefail

SHA="${1:-}"
ENTRY_ID="${2:-}"
REPO="https://github.com/mortezaneghabi61-cloud/ChobYarSketch0.git"
APP="/opt/chobyar-trader"
PY="$APP/.venv/bin/python"
SRC_DIR_REL="ops/chobyar-trader/v5/execution_safety"
DST_DIR="$APP/app/v5/execution_safety"
CONF_DIR="/etc/chobyar-trader"
STAGE22_CONF="$CONF_DIR/live-stage22.env"
STAGE23_CONF="$CONF_DIR/live-stage23.env"
SERVICE="/etc/systemd/system/chobyar-live-guardian.service"
TIMER="/etc/systemd/system/chobyar-live-guardian.timer"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

fail() { echo "FAIL-CLOSED: $*" >&2; exit 1; }

[[ $EUID -eq 0 ]] || fail "run as root"
[[ "$SHA" =~ ^[0-9a-f]{40}$ ]] || fail "exact 40-char commit SHA required"
[[ "$ENTRY_ID" =~ ^chobyar-[a-z0-9][a-z0-9-]{7,63}$ ]] || fail "valid entry client id required"
[[ -f "$APP/.env" ]] || fail ".env missing"
[[ -x "$PY" ]] || fail "venv python missing"
[[ -f "$STAGE22_CONF" ]] || fail "Stage-22 live env missing"

grep -qx 'TRADING_MODE=paper' "$APP/.env" || fail "production trader must remain paper"
grep -qx 'LIVE_TRADING_ENABLED=false' "$APP/.env" || fail "production live gate must remain false"
for pair in 'MAX_POSITION_PCT=0.25' 'STOP_LOSS_PCT=0.015' 'TAKE_PROFIT_PCT=0.03' 'MAX_DAILY_LOSS_PCT=0.03'; do
  grep -qx "$pair" "$APP/.env" || fail "approved risk value missing: $pair"
done
for pair in 'TRADING_MODE=live' 'LIVE_TRADING_ENABLED=true' 'LIVE_EXECUTION_ARMED=true' 'SYMBOL=BTCUSDT' 'LIVE_MAX_ORDER_USDT=10' 'SPOT_ONLY=true' 'WITHDRAWALS_ENABLED=false' 'LEVERAGE_ENABLED=false' 'MARGIN_ENABLED=false' 'FUTURES_ENABLED=false' 'OTC_ENABLED=false'; do
  grep -qx "$pair" "$STAGE22_CONF" || fail "Stage-22 safety gate mismatch: $pair"
done

git -c advice.detachedHead=false clone -q --filter=blob:none --no-checkout "$REPO" "$TMP/repo"
cd "$TMP/repo"
git fetch -q --depth=1 origin "$SHA"
git checkout -q --detach "$SHA"
[[ "$(git rev-parse HEAD)" == "$SHA" ]] || fail "exact SHA checkout mismatch"
for f in live_executor_stage22.py live_position_guardian_stage23.py test_live_executor_stage22.py test_live_position_guardian_stage23.py; do
  [[ -f "$SRC_DIR_REL/$f" ]] || fail "missing $f"
done

"$PY" -m py_compile "$SRC_DIR_REL/live_executor_stage22.py" "$SRC_DIR_REL/live_position_guardian_stage23.py" "$SRC_DIR_REL/test_live_executor_stage22.py" "$SRC_DIR_REL/test_live_position_guardian_stage23.py"
PYTHONPATH="$SRC_DIR_REL" "$PY" "$SRC_DIR_REL/test_live_executor_stage22.py" -q
PYTHONPATH="$SRC_DIR_REL" "$PY" "$SRC_DIR_REL/test_live_position_guardian_stage23.py" -q

g="$SRC_DIR_REL/live_position_guardian_stage23.py"
[[ "$(grep -Ec 'client\.post\(ORDER_PATH' "$g" || true)" == "1" ]] || fail "expected one close-only POST surface"
! grep -nE '\.(delete|put|patch)\(' "$g" >/dev/null || fail "forbidden mutable HTTP surface"
grep -q '"side": "SELL"' "$g" || fail "SELL-only close contract missing"
! grep -q '"side": "BUY"' "$g" || fail "BUY surface forbidden in Stage-23"
grep -q 'entry_exceeded_approved_10_usdt_cap' "$g" || fail "entry-cap provenance proof missing"
grep -q 'btc_balance_exceeds_entry_quantity' "$g" || fail "position-bound quantity proof missing"
grep -q 'STOP_LOSS_PCT = Decimal("0.015")' "$g" || fail "approved stop loss missing"
grep -q 'TAKE_PROFIT_PCT = Decimal("0.03")' "$g" || fail "approved take profit missing"

install -d -m 0755 "$DST_DIR"
install -m 0644 "$SRC_DIR_REL/live_executor_stage22.py" "$DST_DIR/live_executor_stage22.py"
install -m 0644 "$g" "$DST_DIR/live_position_guardian_stage23.py"
install -d -m 0700 "$CONF_DIR"
printf 'LIVE_ENTRY_CLIENT_ID=%s\n' "$ENTRY_ID" > "$STAGE23_CONF"
chmod 0600 "$STAGE23_CONF"

cat > "$SERVICE" <<EOF
[Unit]
Description=ChobYar Stage-23 BTCUSDT Spot Risk Guardian
After=network-online.target
Wants=network-online.target

[Service]
Type=oneshot
WorkingDirectory=$APP
EnvironmentFile=$STAGE22_CONF
EnvironmentFile=$STAGE23_CONF
ExecStart=$PY $DST_DIR/live_position_guardian_stage23.py
User=root
Group=root
NoNewPrivileges=true
PrivateTmp=true
ProtectSystem=strict
ProtectHome=true
ReadWritePaths=$APP /var/tmp
EOF

cat > "$TIMER" <<'EOF'
[Unit]
Description=Run ChobYar Stage-23 Spot Risk Guardian every minute

[Timer]
OnBootSec=30s
OnUnitActiveSec=60s
AccuracySec=5s
Persistent=true
Unit=chobyar-live-guardian.service

[Install]
WantedBy=timers.target
EOF

systemctl daemon-reload
systemctl enable --now chobyar-live-guardian.timer
systemctl start chobyar-live-guardian.service

echo "STAGE23_INSTALL=PASS"
echo "EXACT_SHA=$SHA"
echo "ENTRY_CLIENT_ID=$ENTRY_ID"
echo "SYMBOL=BTCUSDT"
echo "STOP_LOSS_PCT=0.015"
echo "TAKE_PROFIT_PCT=0.03"
echo "CHECK_INTERVAL_SECONDS=60"
echo "EXIT_EXECUTION=POSITION_BOUND_LIMIT_SELL_ONLY"
echo "WITHDRAWALS=DISABLED"
echo "LEVERAGE=DISABLED"
echo "MARGIN=DISABLED"
echo "FUTURES=DISABLED"
echo "OTC=DISABLED"
echo "PRODUCTION_TRADER=PAPER_UNCHANGED"

#!/usr/bin/env bash
set -euo pipefail

SHA="${1:-}"
REPO="https://github.com/mortezaneghabi61-cloud/ChobYarSketch0.git"
APP="/opt/chobyar-trader"
PY="$APP/.venv/bin/python"
SRC_DIR_REL="ops/chobyar-trader/v5/execution_safety"
DST_DIR="$APP/app/v5/execution_safety"
CONF_DIR="/etc/chobyar-trader"
STAGE22_CONF="$CONF_DIR/live-stage22.env"
SERVICE="/etc/systemd/system/chobyar-live-risk-baseline.service"
TIMER="/etc/systemd/system/chobyar-live-risk-baseline.timer"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

fail() { echo "FAIL-CLOSED: $*" >&2; exit 1; }

[[ $EUID -eq 0 ]] || fail "run as root"
[[ "$SHA" =~ ^[0-9a-f]{40}$ ]] || fail "exact 40-char commit SHA required"
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

for f in live_entry_risk_stage25.py live_executor_stage22.py test_live_entry_risk_stage25.py test_live_executor_stage22.py; do
  [[ -f "$SRC_DIR_REL/$f" ]] || fail "missing $f"
done

"$PY" -m py_compile \
  "$SRC_DIR_REL/live_entry_risk_stage25.py" \
  "$SRC_DIR_REL/live_executor_stage22.py" \
  "$SRC_DIR_REL/test_live_entry_risk_stage25.py" \
  "$SRC_DIR_REL/test_live_executor_stage22.py"
PYTHONPATH="$SRC_DIR_REL" "$PY" "$SRC_DIR_REL/test_live_entry_risk_stage25.py" -q
PYTHONPATH="$SRC_DIR_REL" "$PY" "$SRC_DIR_REL/test_live_executor_stage22.py" -q

risk="$SRC_DIR_REL/live_entry_risk_stage25.py"
exec22="$SRC_DIR_REL/live_executor_stage22.py"
! grep -nE '\.(post|delete|put|patch)\(' "$risk" >/dev/null || fail "Stage-25 must remain exchange-read-only"
grep -q 'MAX_POSITION_PCT = Decimal("0.25")' "$risk" || fail "25 percent equity sizing missing"
grep -q 'MAX_DAILY_LOSS_PCT = Decimal("0.03")' "$risk" || fail "3 percent daily loss breaker missing"
grep -q 'daily_baseline_stale' "$risk" || fail "stale-baseline fail-closed guard missing"
grep -q 'buy_exceeds_equity_sized_budget' "$risk" || fail "equity sizing block missing"
grep -q 'enforce_buy_risk' "$exec22" || fail "Stage-22 BUY path is not wired to Stage-25"
test "$(grep -Ec 'client\.post\(ORDER_PATH' "$exec22")" = 1 || fail "Stage-22 mutable surface changed"

install -d -m 0755 "$DST_DIR"
install -m 0644 "$risk" "$DST_DIR/live_entry_risk_stage25.py"
install -m 0644 "$exec22" "$DST_DIR/live_executor_stage22.py"
install -d -m 0700 "$APP/state"

cat > "$SERVICE" <<EOF
[Unit]
Description=ChobYar Stage-25 BTCUSDT Daily Equity Baseline
After=network-online.target
Wants=network-online.target

[Service]
Type=oneshot
WorkingDirectory=$APP
EnvironmentFile=$STAGE22_CONF
ExecStart=$PY $DST_DIR/live_entry_risk_stage25.py --init-baseline
User=root
Group=root
NoNewPrivileges=true
PrivateTmp=true
ProtectSystem=strict
ProtectHome=true
ReadWritePaths=$APP/state
EOF

cat > "$TIMER" <<'EOF'
[Unit]
Description=Initialize ChobYar Stage-25 risk baseline at UTC day start

[Timer]
OnCalendar=*-*-* 00:00:05 UTC
AccuracySec=2s
Persistent=false
Unit=chobyar-live-risk-baseline.service

[Install]
WantedBy=timers.target
EOF

systemctl daemon-reload
systemctl enable --now chobyar-live-risk-baseline.timer
# Initialize the current UTC day's baseline now. This performs GETs and a local state write only.
systemctl start chobyar-live-risk-baseline.service

echo "STAGE25_INSTALL=PASS"
echo "EXACT_SHA=$SHA"
echo "EQUITY_SCOPE=BTCUSDT_BOOK_ONLY"
echo "MAX_POSITION_PCT=0.25"
echo "MAX_DAILY_LOSS_PCT=0.03"
echo "MAX_ORDER_USDT=10"
echo "DAILY_BASELINE_UTC=00:00:05"
echo "MISSED_BASELINE_POLICY=FAIL_CLOSED"
echo "EXCHANGE_MUTATION=NONE_DURING_INSTALL"
echo "FUTURE_BUY_PATH=STAGE22_PLUS_STAGE25"
echo "WITHDRAWALS=DISABLED"
echo "LEVERAGE=DISABLED"
echo "MARGIN=DISABLED"
echo "FUTURES=DISABLED"
echo "OTC=DISABLED"
echo "PRODUCTION_TRADER=PAPER_UNCHANGED"

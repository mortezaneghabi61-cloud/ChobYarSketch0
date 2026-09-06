#!/usr/bin/env bash
set -euo pipefail

SHA="${1:-}"
REPO="https://github.com/mortezaneghabi61-cloud/ChobYarSketch0.git"
APP="/opt/chobyar-trader"
PY="$APP/.venv/bin/python"
SRC_REL="ops/chobyar-trader/v5/execution_safety/live_executor_stage22.py"
TEST_REL="ops/chobyar-trader/v5/execution_safety/test_live_executor_stage22.py"
DST_DIR="$APP/app/v5/execution_safety"
DST="$DST_DIR/live_executor_stage22.py"
CONF_DIR="/etc/chobyar-trader"
CONF="$CONF_DIR/live-stage22.env"
WRAPPER="/usr/local/bin/chobyar-live-order"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

fail() { echo "FAIL-CLOSED: $*" >&2; exit 1; }

[[ $EUID -eq 0 ]] || fail "run as root"
[[ "$SHA" =~ ^[0-9a-f]{40}$ ]] || fail "exact 40-char commit SHA required"
[[ -f "$APP/.env" ]] || fail ".env missing"
[[ -x "$PY" ]] || fail "venv python missing"

# Production paper runtime stays untouched; Stage-22 is an isolated explicitly invoked executor.
prod_mode="$(grep -E '^TRADING_MODE=' "$APP/.env" | tail -n1 | cut -d= -f2- | tr -d '[:space:]')"
prod_live="$(grep -E '^LIVE_TRADING_ENABLED=' "$APP/.env" | tail -n1 | cut -d= -f2- | tr -d '[:space:]')"
[[ "$prod_mode" == "paper" ]] || fail "production TRADING_MODE must remain paper during Stage-22 install"
[[ "$prod_live" == "false" ]] || fail "production LIVE_TRADING_ENABLED must remain false during Stage-22 install"
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
[[ -f "$SRC_REL" && -f "$TEST_REL" ]] || fail "Stage-22 files missing"

"$PY" -m py_compile "$SRC_REL" "$TEST_REL"
PYTHONPATH="ops/chobyar-trader/v5/execution_safety" "$PY" "$TEST_REL" -q

# Stage-22 must have exactly one exchange mutation surface: POST /v1/account/orders.
post_count="$(grep -Ec 'client\.post\(ORDER_PATH' "$SRC_REL" || true)"
[[ "$post_count" == "1" ]] || fail "expected exactly one order POST surface"

# Reject actual mutable/non-spot execution surfaces only. Do not reject the explicit
# *_ENABLED=false safety gates themselves (for example FUTURES_ENABLED=false).
! grep -nEi '\.delete\(|\.put\(|\.patch\(|withdraw\(|["'"']/(margin|otc|futures)(/|["'"'])|margin_order|otc_order|futures_order' "$SRC_REL" >/dev/null \
  || fail "forbidden mutable/non-spot surface detected"

grep -q 'APPROVED_MAX_ORDER_USDT = Decimal("10")' "$SRC_REL" || fail "hard 10 USDT cap missing"
grep -q 'WITHDRAWALS_ENABLED' "$SRC_REL" || fail "withdrawal-off gate missing"
grep -q 'LEVERAGE_ENABLED' "$SRC_REL" || fail "leverage-off gate missing"
grep -q 'MARGIN_ENABLED' "$SRC_REL" || fail "margin-off gate missing"
grep -q 'FUTURES_ENABLED' "$SRC_REL" || fail "futures-off gate missing"
grep -q 'OTC_ENABLED' "$SRC_REL" || fail "otc-off gate missing"

install -d -m 0755 "$DST_DIR"
install -m 0644 "$SRC_REL" "$DST"
install -d -m 0700 "$CONF_DIR"
cat > "$CONF" <<'EOF'
TRADING_MODE=live
LIVE_TRADING_ENABLED=true
LIVE_EXECUTION_ARMED=true
SYMBOL=BTCUSDT
LIVE_MAX_ORDER_USDT=10
SPOT_ONLY=true
WITHDRAWALS_ENABLED=false
LEVERAGE_ENABLED=false
MARGIN_ENABLED=false
FUTURES_ENABLED=false
OTC_ENABLED=false
EOF
chmod 0600 "$CONF"

cat > "$WRAPPER" <<EOF
#!/usr/bin/env bash
set -euo pipefail
set -a
source "$CONF"
set +a
exec "$PY" "$DST" "\$@"
EOF
chmod 0750 "$WRAPPER"

# Keep the continuous production trader in Paper until a separately reviewed autonomous live broker exists.
# The Stage-22 executor is LIVE+ARMED, but it only acts when the operator invokes chobyar-live-order
# with an explicit side, quantity, price and unique client id.

echo "STAGE22_INSTALL=PASS"
echo "EXACT_SHA=$SHA"
echo "LIVE_EXECUTOR=ARMED"
echo "SYMBOL=BTCUSDT"
echo "LIVE_MAX_ORDER_USDT=10"
echo "WITHDRAWALS=DISABLED"
echo "LEVERAGE=DISABLED"
echo "MARGIN=DISABLED"
echo "FUTURES=DISABLED"
echo "OTC=DISABLED"
echo "PRODUCTION_TRADER=PAPER_UNCHANGED"
echo "ORDER_SUBMISSION=EXPLICIT_INVOCATION_ONLY"

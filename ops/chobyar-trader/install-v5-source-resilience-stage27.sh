#!/usr/bin/env bash
set -euo pipefail

SHA="${1:-}"
REPO="https://github.com/mortezaneghabi61-cloud/ChobYarSketch0.git"
APP="/opt/chobyar-trader"
PY="$APP/.venv/bin/python"
SRC="ops/chobyar-trader/v5/execution_safety"
DST="$APP/app/v5/execution_safety"
STAGE22_CONF="/etc/chobyar-trader/live-stage22.env"
SERVICE="chobyar-live-adversarial-veto.service"
TIMER="chobyar-live-adversarial-veto.timer"
STATE="$APP/state/live_adversarial_stage26.json"
HISTORY="$APP/state/live_adversarial_stage26_history.json"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

fail() { echo "FAIL-CLOSED: $*" >&2; exit 1; }

[[ $EUID -eq 0 ]] || fail "run as root"
[[ "$SHA" =~ ^[0-9a-f]{40}$ ]] || fail "exact 40-char commit SHA required"
[[ -x "$PY" ]] || fail "venv python missing"
[[ -f "$APP/.env" ]] || fail ".env missing"
[[ -f "$STAGE22_CONF" ]] || fail "Stage22 live env missing"
grep -qx 'TRADING_MODE=paper' "$APP/.env" || fail "production trader must remain paper"
grep -qx 'LIVE_TRADING_ENABLED=false' "$APP/.env" || fail "production live gate must remain false"
for pair in \
  'MAX_POSITION_PCT=0.25' \
  'STOP_LOSS_PCT=0.015' \
  'TAKE_PROFIT_PCT=0.03' \
  'MAX_DAILY_LOSS_PCT=0.03'; do
  grep -qx "$pair" "$APP/.env" || fail "production risk mismatch: $pair"
done
for pair in \
  'TRADING_MODE=live' \
  'LIVE_TRADING_ENABLED=true' \
  'LIVE_EXECUTION_ARMED=true' \
  'SYMBOL=BTCUSDT' \
  'LIVE_MAX_ORDER_USDT=10' \
  'SPOT_ONLY=true' \
  'WITHDRAWALS_ENABLED=false' \
  'LEVERAGE_ENABLED=false' \
  'MARGIN_ENABLED=false' \
  'FUTURES_ENABLED=false' \
  'OTC_ENABLED=false'; do
  grep -qx "$pair" "$STAGE22_CONF" || fail "Stage22 safety gate mismatch: $pair"
done

git -c advice.detachedHead=false clone -q --filter=blob:none --no-checkout "$REPO" "$TMP/repo"
cd "$TMP/repo"
git fetch -q --depth=1 origin "$SHA"
git checkout -q --detach "$SHA"
[[ "$(git rev-parse HEAD)" == "$SHA" ]] || fail "exact SHA checkout mismatch"

for f in \
  adversarial_market_defense_stage24.py \
  live_adversarial_veto_stage26.py \
  live_entry_risk_stage25.py \
  live_executor_stage22.py \
  test_adversarial_market_defense_stage24.py \
  test_live_adversarial_veto_stage26.py \
  test_live_entry_risk_stage25.py \
  test_live_executor_stage22.py; do
  [[ -f "$SRC/$f" ]] || fail "missing $f"
done

PYTHONPATH="$SRC" "$PY" -m py_compile \
  "$SRC/adversarial_market_defense_stage24.py" \
  "$SRC/live_adversarial_veto_stage26.py" \
  "$SRC/live_entry_risk_stage25.py" \
  "$SRC/live_executor_stage22.py"
PYTHONPATH="$SRC" "$PY" "$SRC/test_adversarial_market_defense_stage24.py" -q
PYTHONPATH="$SRC" "$PY" "$SRC/test_live_adversarial_veto_stage26.py" -q
PYTHONPATH="$SRC" "$PY" "$SRC/test_live_entry_risk_stage25.py" -q
PYTHONPATH="$SRC" "$PY" "$SRC/test_live_executor_stage22.py" -q

! grep -nE '\.(post|delete|put|patch)\(' \
  "$SRC/adversarial_market_defense_stage24.py" \
  "$SRC/live_adversarial_veto_stage26.py" >/dev/null \
  || fail "Stage24/26/27 must remain exchange-read-only"
test "$(grep -Ec 'client\.post\(ORDER_PATH' "$SRC/live_executor_stage22.py")" = 1 \
  || fail "Stage22 mutable surface changed"
grep -q 'MIN_REFERENCE_AGGREGATORS = 3' "$SRC/live_adversarial_veto_stage26.py" \
  || fail "Stage27 reference quorum missing"
grep -q 'STAGE27_REFERENCE_RESILIENCE' "$SRC/live_adversarial_veto_stage26.py" \
  || fail "Stage27 source policy missing"
grep -q 'reference_aggregator' "$SRC/live_adversarial_veto_stage26.py" \
  || fail "source-class labeling missing"

install -d -m 0755 "$DST"
for f in adversarial_market_defense_stage24.py live_adversarial_veto_stage26.py live_entry_risk_stage25.py live_executor_stage22.py; do
  install -m 0644 "$SRC/$f" "$DST/$f"
done
install -d -m 0700 "$APP/state"

# Prevent any pre-Stage27 state from authorizing a BUY. History is also reset so
# the local order-book must warm again under the new source policy.
rm -f "$STATE" "$HISTORY"
systemctl daemon-reload
systemctl restart "$TIMER"
systemctl start "$SERVICE"
systemctl is-enabled --quiet "$TIMER" || fail "Stage26/27 timer not enabled"
systemctl is-active --quiet "$TIMER" || fail "Stage26/27 timer not active"
[[ -s "$STATE" ]] || fail "Stage27 state missing after initial collection"

"$PY" - "$STATE" <<'PY'
import json, sys
from pathlib import Path
p = json.loads(Path(sys.argv[1]).read_text())
assert p.get("version") == 2
assert p.get("source_policy") == "STAGE27_REFERENCE_RESILIENCE"
assert p.get("scope") == "BTCUSDT_STAGE26_ADVERSARIAL_VETO"
assert p.get("symbol") == "BTCUSDT"
assert p.get("allowed") is False, "initial Stage27 sample must remain fail-closed during history warmup"
assert p.get("reason") == "history_warmup" or "collector_error" in p.get("flags", [])
assert isinstance(p.get("reference_source_ids"), list)
assert isinstance(p.get("evidence_classes"), list)
PY

echo "STAGE27_INSTALL=PASS"
echo "EXACT_SHA=$SHA"
echo "SOURCE_POLICY=STAGE27_REFERENCE_RESILIENCE"
echo "REFERENCE_AGGREGATORS=coinlore,coinpaprika,coingecko"
echo "OPTIONAL_DIRECT_EXCHANGE=bybit"
echo "MIN_REFERENCE_AGGREGATORS=3"
echo "REQUIRED_EVIDENCE_CLASSES=local_execution_venue,reference_aggregator"
echo "ORDERBOOK_HISTORY_MIN_SAMPLES=3"
echo "INITIAL_POLICY=FAIL_CLOSED_UNTIL_HISTORY_WARM"
echo "LIVE_BUY_PATH=STAGE27_SOURCE_POLICY_THEN_STAGE24_26_THEN_STAGE25_THEN_STAGE22"
echo "EXCHANGE_MUTATION=NONE_IN_STAGE24_STAGE26_STAGE27"
echo "PRODUCTION_TRADER=PAPER_UNCHANGED"
echo "WITHDRAWALS=DISABLED"
echo "LEVERAGE=DISABLED"
echo "MARGIN=DISABLED"
echo "FUTURES=DISABLED"
echo "OTC=DISABLED"

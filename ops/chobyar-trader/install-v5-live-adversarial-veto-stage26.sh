#!/usr/bin/env bash
set -euo pipefail

SHA="${1:-}"
REPO="https://github.com/mortezaneghabi61-cloud/ChobYarSketch0.git"
APP="/opt/chobyar-trader"
PY="$APP/.venv/bin/python"
SRC="ops/chobyar-trader/v5/execution_safety"
DST="$APP/app/v5/execution_safety"
STAGE22_CONF="/etc/chobyar-trader/live-stage22.env"
SERVICE="/etc/systemd/system/chobyar-live-adversarial-veto.service"
TIMER="/etc/systemd/system/chobyar-live-adversarial-veto.timer"
STATE="$APP/state/live_adversarial_stage26.json"
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
  grep -qx "$pair" "$APP/.env" || fail "approved production risk mismatch: $pair"
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
  || fail "Stage24/26 must remain exchange-read-only"
test "$(grep -Ec 'client\.post\(ORDER_PATH' "$SRC/live_executor_stage22.py")" = 1 \
  || fail "Stage22 mutable surface changed"
grep -q 'enforce_live_buy_veto()' "$SRC/live_executor_stage22.py" \
  || fail "Stage26 live BUY veto not wired"
grep -q 'enforce_buy_risk' "$SRC/live_executor_stage22.py" \
  || fail "Stage25 equity risk gate missing"
grep -q 'MIN_UNIQUE_SOURCES = 3' "$SRC/live_adversarial_veto_stage26.py" \
  || fail "three-source quorum missing"
grep -q 'MIN_HISTORY_SAMPLES = 3' "$SRC/live_adversarial_veto_stage26.py" \
  || fail "history warmup missing"
grep -q 'stage26_quorum_source_mismatch' "$SRC/live_adversarial_veto_stage26.py" \
  || fail "source/quorum integrity check missing"

install -d -m 0755 "$DST"
for f in \
  adversarial_market_defense_stage24.py \
  live_adversarial_veto_stage26.py \
  live_entry_risk_stage25.py \
  live_executor_stage22.py; do
  install -m 0644 "$SRC/$f" "$DST/$f"
done
install -d -m 0700 "$APP/state"

cat > "$SERVICE" <<EOF
[Unit]
Description=ChobYar Stage26 read-only adversarial market veto collector
After=network-online.target
Wants=network-online.target

[Service]
Type=oneshot
WorkingDirectory=$APP
ExecStart=$PY $DST/live_adversarial_veto_stage26.py --collect
User=root
Group=root
UMask=0077
NoNewPrivileges=true
PrivateTmp=true
PrivateDevices=true
ProtectSystem=strict
ProtectHome=true
ProtectKernelTunables=true
ProtectKernelModules=true
ProtectControlGroups=true
RestrictAddressFamilies=AF_UNIX AF_INET AF_INET6
ReadWritePaths=$APP/state
EOF

cat > "$TIMER" <<'EOF'
[Unit]
Description=Refresh ChobYar Stage26 adversarial veto snapshot

[Timer]
OnBootSec=3s
OnUnitActiveSec=20s
AccuracySec=2s
Persistent=false
Unit=chobyar-live-adversarial-veto.service

[Install]
WantedBy=timers.target
EOF

systemctl daemon-reload
systemctl enable --now chobyar-live-adversarial-veto.timer
systemctl start chobyar-live-adversarial-veto.service
systemctl is-enabled --quiet chobyar-live-adversarial-veto.timer || fail "Stage26 timer not enabled"
systemctl is-active --quiet chobyar-live-adversarial-veto.timer || fail "Stage26 timer not active"
[[ -s "$STATE" ]] || fail "Stage26 state missing after first read-only collection"
"$PY" - "$STATE" <<'PY'
import json, sys
from pathlib import Path
p = json.loads(Path(sys.argv[1]).read_text())
assert p.get("version") == 1
assert p.get("scope") == "BTCUSDT_STAGE26_ADVERSARIAL_VETO"
assert p.get("symbol") == "BTCUSDT"
assert isinstance(p.get("allowed"), bool)
assert isinstance(p.get("source_ids"), list)
PY

echo "STAGE26_INSTALL=PASS"
echo "EXACT_SHA=$SHA"
echo "LIVE_BUY_PATH=STAGE26_THEN_STAGE25_THEN_STAGE22"
echo "UNIQUE_GLOBAL_SOURCE_QUORUM=3"
echo "CONFIGURED_GLOBAL_SOURCES=4"
echo "ORDERBOOK_HISTORY_MIN_SAMPLES=3"
echo "SNAPSHOT_MAX_AGE_SECONDS=60"
echo "COLLECT_INTERVAL_SECONDS=20"
echo "INITIAL_POLICY=FAIL_CLOSED_UNTIL_HISTORY_WARM"
echo "EXCHANGE_MUTATION=NONE_IN_STAGE24_STAGE26"
echo "PRODUCTION_TRADER=PAPER_UNCHANGED"
echo "WITHDRAWALS=DISABLED"
echo "LEVERAGE=DISABLED"
echo "MARGIN=DISABLED"
echo "FUTURES=DISABLED"
echo "OTC=DISABLED"

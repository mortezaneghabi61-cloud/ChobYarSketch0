#!/usr/bin/env bash
set -Eeuo pipefail

REPO_URL="https://github.com/mortezaneghabi61-cloud/ChobYarSketch0.git"
EXPECTED_SHA="${1:-}"
APP_DIR="/opt/chobyar-trader"
ENV_FILE="$APP_DIR/.env"
VENV="$APP_DIR/.venv"
TRADER_UNIT="/etc/systemd/system/chobyar-trader.service"
SHADOW_UNIT="/etc/systemd/system/chobyar-v5-shadow.service"
V5_RUNTIME="$APP_DIR/app/v5/execution_safety"

fail() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }
[[ $EUID -eq 0 ]] || fail "run as root"
[[ "$EXPECTED_SHA" =~ ^[0-9a-f]{40}$ ]] || fail "exact 40-character commit SHA required"
[[ -f "$ENV_FILE" && -x "$VENV/bin/python" && -f "$TRADER_UNIT" && -f "$SHADOW_UNIT" ]] || fail "existing trader/v5 installation incomplete"

# Stage-9 is a paper-only runtime path cutover. It never changes .env.
grep -qx 'TRADING_MODE=paper' "$ENV_FILE" || fail "FAIL-CLOSED: TRADING_MODE must be paper"
grep -qx 'LIVE_TRADING_ENABLED=false' "$ENV_FILE" || fail "FAIL-CLOSED: LIVE_TRADING_ENABLED must be false"
grep -qx 'MAX_POSITION_PCT=0.25' "$ENV_FILE" || fail "FAIL-CLOSED: MAX_POSITION_PCT mismatch"
grep -qx 'STOP_LOSS_PCT=0.015' "$ENV_FILE" || fail "FAIL-CLOSED: STOP_LOSS_PCT mismatch"
grep -qx 'TAKE_PROFIT_PCT=0.03' "$ENV_FILE" || fail "FAIL-CLOSED: TAKE_PROFIT_PCT mismatch"
grep -qx 'MAX_DAILY_LOSS_PCT=0.03' "$ENV_FILE" || fail "FAIL-CLOSED: MAX_DAILY_LOSS_PCT mismatch"

systemctl is-active --quiet chobyar-trader.service || fail "trader inactive"
systemctl is-active --quiet chobyar-status.service || fail "status inactive"
systemctl is-active --quiet chobyar-v5-shadow.timer || fail "v5 shadow timer inactive"
systemctl is-active --quiet chobyar-v5-scorecard.timer || fail "v5 scorecard timer inactive"

grep -Eq '^ExecStart=/opt/chobyar-trader/.venv/bin/python /opt/chobyar-trader/app/(trader_entry.py|v5/execution_safety/trader_entry.py)$' "$TRADER_UNIT" || fail "unexpected trader ExecStart before cutover"
grep -q '/opt/chobyar-trader/app/v5/shadow_runner_v52.py' "$SHADOW_UNIT" || fail "unexpected v5 shadow ExecStart"
! grep -q 'EnvironmentFile' "$SHADOW_UNIT" || fail "shadow service must not load .env"

status_pid_before="$(systemctl show chobyar-status.service -p MainPID --value)"
status_started_before="$(systemctl show chobyar-status.service -p ExecMainStartTimestampMonotonic --value)"
shadow_exec_before="$(grep -E '^ExecStart=' "$SHADOW_UNIT" || true)"
[[ "$status_pid_before" =~ ^[1-9][0-9]*$ ]] || fail "status service is not running"
[[ -n "$shadow_exec_before" ]] || fail "shadow ExecStart missing"

umask 077
work="$(mktemp -d)"
backup="$APP_DIR/backups/v5-runtime-stage9-$(date -u +%Y%m%dT%H%M%SZ)"
changed=0
had_runtime=0

rollback() {
  if [[ "$changed" == 1 ]]; then
    cp -a "$backup/chobyar-trader.service" "$TRADER_UNIT" || true
    rm -rf "$V5_RUNTIME"
    if [[ "$had_runtime" == 1 && -d "$backup/execution_safety" ]]; then
      mkdir -p "$(dirname "$V5_RUNTIME")"
      cp -a "$backup/execution_safety" "$V5_RUNTIME" || true
    fi
    systemctl daemon-reload || true
    systemctl restart chobyar-trader.service || true
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
runtime_src="$src/v5/execution_safety"

production_files=(
  common.py
  trader.py
  global_sources.py
  trader_entry.py
  entry_gate_v55.py
  live_safety.py
  wallex_readonly.py
  market_metadata.py
  auth_preflight.py
  open_orders_readonly.py
  order_dryrun.py
  quote_cap.py
)
for f in "${production_files[@]}"; do
  [[ -f "$runtime_src/$f" ]] || fail "candidate missing v5 runtime file: $f"
done
for f in test_entry_gate_v55.py test_live_safety.py test_wallex_readonly.py test_market_metadata.py test_auth_preflight.py test_open_orders_readonly.py test_order_dryrun.py test_quote_cap.py; do
  [[ -f "$runtime_src/$f" ]] || fail "candidate missing v5 runtime test: $f"
done
[[ -f "$src/v4/test_global_sources.py" ]] || fail "legacy global-source regression test missing"
[[ -f "$src/upgrade-v56-v5-runtime-cutover.sh" ]] || fail "candidate installer missing"

# Core paper behavior is intentionally identical during path cutover.
cmp -s "$src/v4/common.py" "$runtime_src/common.py" || fail "common.py parity mismatch"
cmp -s "$src/v4/trader.py" "$runtime_src/trader.py" || fail "trader.py parity mismatch"
cmp -s "$src/v4/global_sources.py" "$runtime_src/global_sources.py" || fail "global_sources.py parity mismatch"

"$VENV/bin/python" -m py_compile "${production_files[@]/#/$runtime_src/}"
PYTHONPATH="$runtime_src" "$VENV/bin/python" "$runtime_src/test_entry_gate_v55.py" -v
PYTHONPATH="$runtime_src" "$VENV/bin/python" "$runtime_src/test_live_safety.py" -v
PYTHONPATH="$runtime_src" "$VENV/bin/python" "$runtime_src/test_wallex_readonly.py" -v
PYTHONPATH="$runtime_src" "$VENV/bin/python" "$runtime_src/test_order_dryrun.py" -v
PYTHONPATH="$runtime_src" "$VENV/bin/python" "$runtime_src/test_market_metadata.py" -v
PYTHONPATH="$runtime_src" "$VENV/bin/python" "$runtime_src/test_auth_preflight.py" -v
PYTHONPATH="$runtime_src" "$VENV/bin/python" "$runtime_src/test_open_orders_readonly.py" -v
PYTHONPATH="$runtime_src" "$VENV/bin/python" "$runtime_src/test_quote_cap.py" -v
PYTHONPATH="$runtime_src" "$VENV/bin/python" "$src/v4/test_global_sources.py" -v
bash -n "$src/upgrade-v56-v5-runtime-cutover.sh"

# Prove the entry process resolves its core modules from the v5 runtime directory.
TRADING_MODE=paper \
LIVE_TRADING_ENABLED=false \
MAX_POSITION_PCT=0.25 \
STOP_LOSS_PCT=0.015 \
TAKE_PROFIT_PCT=0.03 \
MAX_DAILY_LOSS_PCT=0.03 \
CHOBYAR_APP_DIR="$work/app-probe" \
PYTHONPATH="$runtime_src" \
"$VENV/bin/python" - "$runtime_src" <<'PY'
import importlib
import os
import sys
import types
from pathlib import Path

class DummyClient:
    def __init__(self, *args, **kwargs):
        pass

httpx = types.ModuleType("httpx")
httpx.Client = DummyClient
sys.modules["httpx"] = httpx
root = Path(sys.argv[1]).resolve()
entry = importlib.import_module("trader_entry")
for name in ("trader", "global_sources", "entry_gate_v55"):
    module = importlib.import_module(name)
    assert Path(module.__file__).resolve().parent == root, (name, module.__file__, root)
assert Path(entry.__file__).resolve().parent == root
print("V5_SELF_CONTAINED_IMPORT=PASS")
PY

if grep -RniE 'X-API-Key|/v1/account/orders|\.post\(|\.put\(|\.patch\(|\.delete\(|margin-trade|futures' \
  "$runtime_src/trader.py" "$runtime_src/trader_entry.py" "$runtime_src/entry_gate_v55.py" "$runtime_src/global_sources.py"; then
  fail "mutable/authenticated exchange surface detected in v5 paper runtime core"
fi
! grep -Rni 'LIVE_TRADING_ENABLED=true' "$runtime_src" --include='*.py' || fail "live-enable marker detected"

grep -q 'if MODE != "paper" or LIVE != "false"' "$runtime_src/trader.py" || fail "paper/live fail-closed import gate missing"
grep -q 'if action != "BUY"' "$runtime_src/trader_entry.py" || fail "legacy exit path preservation missing"
grep -q 'execution_authority.*False' "$runtime_src/entry_gate_v55.py" || fail "v5 confirmation execution-authority lock missing"

mkdir -p "$backup"
chmod 700 "$backup"
cp -a "$TRADER_UNIT" "$backup/chobyar-trader.service"
if [[ -d "$V5_RUNTIME" ]]; then
  had_runtime=1
  cp -a "$V5_RUNTIME" "$backup/execution_safety"
fi
changed=1

rm -rf "$V5_RUNTIME"
mkdir -p "$V5_RUNTIME"
chmod 700 "$V5_RUNTIME"
for f in "${production_files[@]}"; do
  install -m 700 "$runtime_src/$f" "$V5_RUNTIME/$f"
done

cat >"$TRADER_UNIT" <<'UNIT'
[Unit]
Description=ChobYar Trader v5 Paper-Only Runtime
After=network-online.target
Wants=network-online.target
[Service]
Type=simple
WorkingDirectory=/opt/chobyar-trader/app/v5/execution_safety
EnvironmentFile=/opt/chobyar-trader/.env
Environment=PYTHONDONTWRITEBYTECODE=1
ExecStart=/opt/chobyar-trader/.venv/bin/python /opt/chobyar-trader/app/v5/execution_safety/trader_entry.py
Restart=on-failure
RestartSec=10
User=root
NoNewPrivileges=true
PrivateTmp=true
ProtectHome=true
ProtectSystem=strict
ReadWritePaths=/opt/chobyar-trader/state /opt/chobyar-trader/logs
[Install]
WantedBy=multi-user.target
UNIT

systemctl daemon-reload
systemctl restart chobyar-trader.service
sleep 35
systemctl is-active --quiet chobyar-trader.service || fail "trader inactive after v5 runtime cutover"
systemctl is-active --quiet chobyar-status.service || fail "status inactive after v5 runtime cutover"
systemctl is-active --quiet chobyar-v5-shadow.timer || fail "v5 shadow timer inactive after cutover"
systemctl is-active --quiet chobyar-v5-scorecard.timer || fail "v5 scorecard timer inactive after cutover"

grep -qx 'WorkingDirectory=/opt/chobyar-trader/app/v5/execution_safety' "$TRADER_UNIT" || fail "v5 working directory not active"
grep -qx 'ExecStart=/opt/chobyar-trader/.venv/bin/python /opt/chobyar-trader/app/v5/execution_safety/trader_entry.py' "$TRADER_UNIT" || fail "v5 ExecStart not active"
! grep -Eq '^ExecStart=.* /opt/chobyar-trader/app/trader_entry.py$' "$TRADER_UNIT" || fail "legacy direct ExecStart still active"
for f in "${production_files[@]}"; do
  cmp -s "$runtime_src/$f" "$V5_RUNTIME/$f" || fail "installed v5 runtime mismatch: $f"
done

status_pid_after="$(systemctl show chobyar-status.service -p MainPID --value)"
status_started_after="$(systemctl show chobyar-status.service -p ExecMainStartTimestampMonotonic --value)"
shadow_exec_after="$(grep -E '^ExecStart=' "$SHADOW_UNIT" || true)"
[[ "$status_pid_after" == "$status_pid_before" && "$status_started_after" == "$status_started_before" ]] || fail "FAIL-CLOSED: status restarted during v5 runtime cutover"
[[ "$shadow_exec_after" == "$shadow_exec_before" ]] || fail "FAIL-CLOSED: v5 shadow ExecStart changed"

grep -qx 'TRADING_MODE=paper' "$ENV_FILE" || fail "paper mode changed"
grep -qx 'LIVE_TRADING_ENABLED=false' "$ENV_FILE" || fail "live lock changed"
grep -qx 'MAX_POSITION_PCT=0.25' "$ENV_FILE" || fail "risk changed"
grep -qx 'STOP_LOSS_PCT=0.015' "$ENV_FILE" || fail "risk changed"
grep -qx 'TAKE_PROFIT_PCT=0.03' "$ENV_FILE" || fail "risk changed"
grep -qx 'MAX_DAILY_LOSS_PCT=0.03' "$ENV_FILE" || fail "risk changed"

printf 'DEPLOYED_SHA=%s\nPAPER_STATUS=PASS\nLIVE_GATE=LOCKED\nV5_RUNTIME_EXECSTART=ACTIVE\nV5_RUNTIME_SELF_CONTAINED=YES\nV4_DIRECT_EXECSTART=RETIRED\nPAPER_ENGINE_BEHAVIOR=UNCHANGED\nRISK=UNCHANGED\nSTATUS_RESTARTED=NO\nSHADOW_EXECSTART=UNCHANGED\n' "$EXPECTED_SHA"
changed=0

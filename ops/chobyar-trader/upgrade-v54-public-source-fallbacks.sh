#!/usr/bin/env bash
set -Eeuo pipefail

REPO_URL="https://github.com/mortezaneghabi61-cloud/ChobYarSketch0.git"
EXPECTED_SHA="${1:-}"
APP_DIR="/opt/chobyar-trader"
ENV_FILE="$APP_DIR/.env"
VENV="$APP_DIR/.venv"
V5_DIR="$APP_DIR/app/v5"
SHADOW_UNIT="/etc/systemd/system/chobyar-v5-shadow.service"

fail() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }
[[ $EUID -eq 0 ]] || fail "run as root"
[[ "$EXPECTED_SHA" =~ ^[0-9a-f]{40}$ ]] || fail "exact 40-character commit SHA required"
[[ -f "$ENV_FILE" && -x "$VENV/bin/python" && -f "$SHADOW_UNIT" ]] || fail "existing v5 installation incomplete"
[[ -f "$V5_DIR/shadow_runner_v52.py" && -f "$V5_DIR/meta_intelligence.py" ]] || fail "v5.2 shadow baseline missing"

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
grep -q '/opt/chobyar-trader/app/v5/shadow_runner_v52.py' "$SHADOW_UNIT" || fail "unexpected v5 shadow ExecStart"
! grep -q 'EnvironmentFile' "$SHADOW_UNIT" || fail "shadow service must not load .env"

trader_pid_before="$(systemctl show chobyar-trader.service -p MainPID --value)"
trader_started_before="$(systemctl show chobyar-trader.service -p ExecMainStartTimestampMonotonic --value)"
status_pid_before="$(systemctl show chobyar-status.service -p MainPID --value)"
status_started_before="$(systemctl show chobyar-status.service -p ExecMainStartTimestampMonotonic --value)"
shadow_exec_before="$(systemctl show chobyar-v5-shadow.service -p ExecStart --value)"
[[ "$trader_pid_before" =~ ^[1-9][0-9]*$ ]] || fail "trader service is not running"
[[ "$status_pid_before" =~ ^[1-9][0-9]*$ ]] || fail "status service is not running"

umask 077
work="$(mktemp -d)"
backup="$APP_DIR/backups/v54-sources-$(date -u +%Y%m%dT%H%M%SZ)"
changed=0
had_fallback=0

rollback() {
  if [[ "$changed" == 1 ]]; then
    cp -a "$backup/shadow_runner_v52.py" "$V5_DIR/shadow_runner_v52.py" || true
    if [[ "$had_fallback" == 1 ]]; then
      cp -a "$backup/public_source_fallbacks.py" "$V5_DIR/public_source_fallbacks.py" || true
    else
      rm -f "$V5_DIR/public_source_fallbacks.py"
    fi
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
for f in v5/public_source_fallbacks.py v5/shadow_runner_v52.py v5/test_public_source_fallbacks.py upgrade-v54-public-source-fallbacks.sh; do
  [[ -f "$src/$f" ]] || fail "candidate missing $f"
done

PYTHONPATH="$src/v5" "$VENV/bin/python" -m py_compile \
  "$src/v5/public_source_fallbacks.py" "$src/v5/shadow_runner_v52.py"
PYTHONPATH="$src/v5" "$VENV/bin/python" "$src/v5/test_public_source_fallbacks.py" -v
PYTHONPATH="$src/v5" "$VENV/bin/python" "$src/v5/test_specialist_council.py" -v
PYTHONPATH="$src/v5" "$VENV/bin/python" "$src/v5/test_meta_intelligence.py" -v
bash -n "$src/upgrade-v54-public-source-fallbacks.sh"

if grep -RniE 'API_KEY|APIKEY|STATUS_HMAC_SECRET|submit_order|create_order|place_order|withdraw|enable_live|/api/v5/trade/|martingale|leverage' \
  "$src/v5/public_source_fallbacks.py" "$src/v5/shadow_runner_v52.py"; then
  fail "forbidden credential/execution marker in v5.4 production code"
fi
grep -q 'import public_source_fallbacks' "$src/v5/shadow_runner_v52.py" || fail "fallback wiring missing"
grep -q 'EXECUTION_AUTHORITY=NONE' "$src/v5/shadow_runner_v52.py" || fail "execution lock output missing"

# Pre-write VPS reachability proof for KuCoin public market data. No credentials.
PYTHONPATH="$src/v5" "$VENV/bin/python" - <<'PY'
import httpx
import public_source_fallbacks as s
with httpx.Client(timeout=8.0, headers={"User-Agent": "ChobYar-Trader/5.4-public-source-probe"}) as client:
    breadth = s.fetch_kucoin_breadth(client)
    funding, funding_z, funding_samples = s.fetch_kucoin_funding(client)
    oi, oi_change = s.fetch_kucoin_open_interest(client)
assert set(s.REQUIRED_BREADTH).issubset(breadth)
assert funding is not None and funding_z is not None and funding_samples >= s.MIN_FUNDING_SAMPLES
assert oi is not None and oi > 0
assert oi_change is not None
print("V5_FALLBACK_VPS_PROBE=PASS")
print("KUCOIN_BREADTH_SYMBOLS=" + str(len(breadth)))
print("KUCOIN_FUNDING_SAMPLES=" + str(funding_samples))
print("KUCOIN_OPEN_INTEREST=AVAILABLE")
print("KUCOIN_OI_CHANGE=AVAILABLE")
PY

mkdir -p "$backup"
chmod 700 "$backup"
cp -a "$V5_DIR/shadow_runner_v52.py" "$backup/shadow_runner_v52.py"
if [[ -f "$V5_DIR/public_source_fallbacks.py" ]]; then
  had_fallback=1
  cp -a "$V5_DIR/public_source_fallbacks.py" "$backup/public_source_fallbacks.py"
fi
changed=1

install -m 700 "$src/v5/public_source_fallbacks.py" "$V5_DIR/public_source_fallbacks.py"
install -m 700 "$src/v5/shadow_runner_v52.py" "$V5_DIR/shadow_runner_v52.py"

# Run only the read-only shadow service. Trader and Status are intentionally untouched.
systemctl start chobyar-v5-shadow.service
systemctl is-active --quiet chobyar-v5-shadow.timer || fail "shadow timer inactive after v5.4"
systemctl is-active --quiet chobyar-v5-scorecard.timer || fail "scorecard timer inactive after v5.4"

trader_pid_after="$(systemctl show chobyar-trader.service -p MainPID --value)"
trader_started_after="$(systemctl show chobyar-trader.service -p ExecMainStartTimestampMonotonic --value)"
status_pid_after="$(systemctl show chobyar-status.service -p MainPID --value)"
status_started_after="$(systemctl show chobyar-status.service -p ExecMainStartTimestampMonotonic --value)"
shadow_exec_after="$(systemctl show chobyar-v5-shadow.service -p ExecStart --value)"
[[ "$trader_pid_after" == "$trader_pid_before" && "$trader_started_after" == "$trader_started_before" ]] || fail "FAIL-CLOSED: trader restarted during v5.4"
[[ "$status_pid_after" == "$status_pid_before" && "$status_started_after" == "$status_started_before" ]] || fail "FAIL-CLOSED: status restarted during v5.4"
[[ "$shadow_exec_after" == "$shadow_exec_before" ]] || fail "FAIL-CLOSED: shadow ExecStart changed"

"$VENV/bin/python" - "$APP_DIR/state/v5_shadow_latest.json" <<'PY'
import json, sys
r = json.load(open(sys.argv[1], encoding='utf-8'))
assert r.get('ok') is True
assert r.get('mode') == 'shadow_observation_only'
assert r.get('execution_authority') is False
assert r.get('automatic_promotion_enabled') is False
assert r.get('automatic_reweighting_enabled') is False
assert r.get('foreign_execution_enabled') is False
assert r.get('geo_bypass_supported') is False
assert len(r.get('specialists') or []) == 5
health = r.get('source_health') or {}
assert health.get('breadth_source') in {'okx', 'kucoin'}
assert health.get('funding_source') in {'okx', 'kucoin'}
assert health.get('open_interest_source') in {'okx', 'kucoin'}
assert set(health.get('resolved_breadth_symbols') or []) >= {'BTC-USDT','ETH-USDT','SOL-USDT'}
assert int(health.get('resolved_funding_samples') or 0) >= 5
assert health.get('oi_change_available') is True
specialists = {x.get('agent'): x for x in (r.get('specialists') or []) if isinstance(x, dict)}
assert specialists.get('cross_market_breadth', {}).get('available') is True
assert specialists.get('derivatives_positioning', {}).get('available') is True
consensus = r.get('shadow_consensus') or {}
assert int(consensus.get('available_directional_specialists') or 0) >= 3
assert consensus.get('action') in {'BUY','SELL','WAIT'}
print('V5_SOURCE_RUNTIME=PASS')
print('BREADTH_SOURCE=' + str(health.get('breadth_source')))
print('FUNDING_SOURCE=' + str(health.get('funding_source')))
print('OPEN_INTEREST_SOURCE=' + str(health.get('open_interest_source')))
print('OI_CHANGE_AVAILABLE=' + str(bool(health.get('oi_change_available'))).upper())
print('DERIVATIVES_AVAILABLE=' + str(bool(specialists.get('derivatives_positioning', {}).get('available'))).upper())
print('BREADTH_AVAILABLE=' + str(bool(specialists.get('cross_market_breadth', {}).get('available'))).upper())
print('DIRECTIONAL_SPECIALISTS_AVAILABLE=' + str(consensus.get('available_directional_specialists')))
print('SHADOW_ACTION=' + str(consensus.get('action')))
PY

grep -qx 'TRADING_MODE=paper' "$ENV_FILE" || fail "paper mode changed"
grep -qx 'LIVE_TRADING_ENABLED=false' "$ENV_FILE" || fail "live lock changed"
grep -qx 'MAX_POSITION_PCT=0.25' "$ENV_FILE" || fail "risk changed"
grep -qx 'STOP_LOSS_PCT=0.015' "$ENV_FILE" || fail "risk changed"
grep -qx 'TAKE_PROFIT_PCT=0.03' "$ENV_FILE" || fail "risk changed"
grep -qx 'MAX_DAILY_LOSS_PCT=0.03' "$ENV_FILE" || fail "risk changed"

printf 'DEPLOYED_SHA=%s\nPAPER_STATUS=PASS\nLIVE_GATE=LOCKED\nV5_PUBLIC_SOURCE_FALLBACKS=ACTIVE\nOKX_PRIMARY=YES\nKUCOIN_FALLBACK=YES\nCOUNCIL_THRESHOLDS=UNCHANGED\nRISK=UNCHANGED\nEXECUTION_AUTHORITY=NONE\nAUTO_PROMOTION=DISABLED\nTRADER_RESTARTED=NO\nSTATUS_RESTARTED=NO\nSHADOW_EXECSTART=UNCHANGED\n' "$EXPECTED_SHA"
changed=0

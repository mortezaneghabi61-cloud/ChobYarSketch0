#!/usr/bin/env bash
set -Eeuo pipefail

REPO_URL="https://github.com/mortezaneghabi61-cloud/ChobYarSketch0.git"
EXPECTED_SHA="${1:-}"
APP_DIR="/opt/chobyar-trader"
ENV_FILE="$APP_DIR/.env"
VENV="$APP_DIR/.venv"
V3_DIR="$APP_DIR/app"
STATUS_UNIT="/etc/systemd/system/chobyar-status.service"
EXPECTED_V51_SHA256="d70e04dd783515c2528db011647bd1fd0e26db450fa95e324e5f96b522d76a59"
EXPECTED_V53_SHA256="df7972b817f2e8bb34abbc12c95c65aaf49992b5b4618a80ea918ea6f81da081"

fail() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }
[[ $EUID -eq 0 ]] || fail "run as root"
[[ "$EXPECTED_SHA" =~ ^[0-9a-f]{40}$ ]] || fail "exact commit SHA required"
[[ -x "$VENV/bin/python" && -f "$ENV_FILE" && -f "$STATUS_UNIT" ]] || fail "runtime incomplete"
grep -q '/opt/chobyar-trader/app/status_server_v53.py' "$STATUS_UNIT" || fail "unexpected status authority"
[[ "$(sha256sum "$V3_DIR/status_server_v51.py" | awk '{print $1}')" == "$EXPECTED_V51_SHA256" ]] || fail "status v51 baseline mismatch"
[[ "$(sha256sum "$V3_DIR/status_server_v53.py" | awk '{print $1}')" == "$EXPECTED_V53_SHA256" ]] || fail "status v53 baseline mismatch"
for line in TRADING_MODE=paper LIVE_TRADING_ENABLED=false MAX_POSITION_PCT=0.25 STOP_LOSS_PCT=0.015 TAKE_PROFIT_PCT=0.03 MAX_DAILY_LOSS_PCT=0.03; do
  grep -qx "$line" "$ENV_FILE" || fail "safety lock mismatch: $line"
done
systemctl is-active --quiet chobyar-trader.service || fail "trader inactive"
systemctl is-active --quiet chobyar-status.service || fail "status inactive"
systemctl is-active --quiet chobyar-v5-shadow.timer || fail "shadow timer inactive"
systemctl is-active --quiet chobyar-v5-scorecard.timer || fail "scorecard timer inactive"

trader_pid_before="$(systemctl show chobyar-trader.service -p MainPID --value)"
trader_start_before="$(systemctl show chobyar-trader.service -p ActiveEnterTimestamp --value)"
status_pid_before="$(systemctl show chobyar-status.service -p MainPID --value)"
shadow_exec_before="$(systemctl show chobyar-v5-shadow.service -p ExecStart --value)"

umask 077
work="$(mktemp -d)"
backup="$APP_DIR/backups/v57-public-source-health-$(date -u +%Y%m%dT%H%M%SZ)"
changed=0
rollback() {
  if [[ "$changed" == 1 ]]; then
    cp -a "$backup/status_server_v51.py" "$V3_DIR/status_server_v51.py" || true
    if [[ -f "$backup/public_source_health.py" ]]; then cp -a "$backup/public_source_health.py" "$V3_DIR/public_source_health.py"; else rm -f "$V3_DIR/public_source_health.py"; fi
    systemctl restart chobyar-status.service || true
  fi
}
cleanup() { rc=$?; [[ $rc -eq 0 ]] || rollback; rm -rf "$work"; exit "$rc"; }
trap cleanup EXIT

git init -q "$work/repo"
git -C "$work/repo" remote add origin "$REPO_URL"
git -C "$work/repo" fetch -q --depth=1 origin "$EXPECTED_SHA"
git -C "$work/repo" checkout -q --detach FETCH_HEAD
[[ "$(git -C "$work/repo" rev-parse HEAD)" == "$EXPECTED_SHA" ]] || fail "downloaded SHA mismatch"
src="$work/repo/ops/chobyar-trader"
for f in v3/public_source_health.py v3/status_server_v51.py v3/test_public_source_health.py upgrade-v57-public-source-health.sh; do [[ -f "$src/$f" ]] || fail "candidate missing $f"; done
cd "$src/v3"
"$VENV/bin/python" -m py_compile public_source_health.py status_server_v51.py test_public_source_health.py
PYTHONPATH=. "$VENV/bin/python" test_public_source_health.py -v
if grep -RniE 'submit_order|create_order|place_order|withdraw|LIVE_TRADING_ENABLED=true|/api/v5/trade/|martingale|leverage|proxy|vpn' public_source_health.py status_server_v51.py; then fail "forbidden surface"; fi

mkdir -p "$backup"
chmod 700 "$backup"
cp -a "$V3_DIR/status_server_v51.py" "$backup/status_server_v51.py"
[[ ! -f "$V3_DIR/public_source_health.py" ]] || cp -a "$V3_DIR/public_source_health.py" "$backup/public_source_health.py"
changed=1
install -m 700 public_source_health.py "$V3_DIR/public_source_health.py"
install -m 700 status_server_v51.py "$V3_DIR/status_server_v51.py"
systemctl restart chobyar-status.service
sleep 3
systemctl is-active --quiet chobyar-status.service || fail "status failed after restart"

[[ "$(systemctl show chobyar-trader.service -p MainPID --value)" == "$trader_pid_before" ]] || fail "trader PID changed"
[[ "$(systemctl show chobyar-trader.service -p ActiveEnterTimestamp --value)" == "$trader_start_before" ]] || fail "trader start time changed"
[[ "$(systemctl show chobyar-v5-shadow.service -p ExecStart --value)" == "$shadow_exec_before" ]] || fail "shadow ExecStart changed"

curl -kfsS --connect-timeout 8 --max-time 15 https://127.0.0.1/public-report -o "$work/report.json"
"$VENV/bin/python" - "$work/report.json" <<'PY'
import json,sys
r=json.load(open(sys.argv[1],encoding="utf-8"))
assert r.get("mode")=="paper" and r.get("live_locked") is True
v=r.get("v5_shadow") or {}
assert v.get("execution_authority") is False
assert v.get("automatic_promotion_enabled") is False
assert v.get("automatic_reweighting_enabled") is False
assert v.get("geo_bypass_supported") is False
h=v.get("source_health") or {}
assert h.get("breadth_source") in {"okx","kucoin"}
assert h.get("funding_source") in {"okx","kucoin"}
assert h.get("open_interest_source") in {"okx","kucoin"}
assert isinstance(h.get("resolved_breadth_symbols"),list) and len(h["resolved_breadth_symbols"]) <= 6
assert isinstance(h.get("resolved_funding_samples"),int) and h["resolved_funding_samples"] >= 0
assert isinstance(h.get("oi_change_available"),bool)
forbidden=("api_key","secret","token","cookie","authorization","private_exchange_credentials")
def walk(x):
    if isinstance(x,dict):
        for k,v in x.items():
            assert not any(marker in str(k).lower() for marker in forbidden), k
            walk(v)
    elif isinstance(x,list):
        for v in x: walk(v)
walk(r)
print("PUBLIC_SOURCE_HEALTH=PASS")
print("PUBLIC_REPORT_SECRET_LEAK_TEST=PASS")
for k in ("breadth_source","funding_source","open_interest_source","resolved_breadth_symbols","resolved_funding_samples","oi_change_available"):
    print(k.upper()+"="+str(h.get(k)))
PY
status_pid_after="$(systemctl show chobyar-status.service -p MainPID --value)"
printf 'DEPLOYED_SHA=%s\nTRADER_PID_BEFORE=%s\nTRADER_PID_AFTER=%s\nTRADER_RESTARTED=NO\nSTATUS_PID_BEFORE=%s\nSTATUS_PID_AFTER=%s\nSTATUS_RESTARTED=YES\nPAPER_MODE=paper\nLIVE_LOCKED=YES\nRISK_UNCHANGED=YES\n' "$EXPECTED_SHA" "$trader_pid_before" "$trader_pid_before" "$status_pid_before" "$status_pid_after"
changed=0

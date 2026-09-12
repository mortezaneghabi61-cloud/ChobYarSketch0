#!/usr/bin/env bash
# On-demand read-only VPS audit. No deployment, timer mutation, or promotion.
set -Eeuo pipefail
export PYTHONDONTWRITEBYTECODE=1
expected_sha="${1:-}"
app="/opt/chobyar-trader"
v5="$app/app/v5"
venv_python="$app/.venv/bin/python"
journal="$app/logs/v5_council_evidence.jsonl"
fail() { printf 'FAIL-CLOSED: %s\n' "$*" >&2; exit 1; }
[[ $EUID -eq 0 ]] || fail 'run as root'
[[ "$expected_sha" =~ ^[0-9a-f]{40}$ ]] || fail 'exact commit SHA required'
[[ -x "$venv_python" && -f "$journal" && ! -L "$journal" ]] || fail 'V5.9 evidence installation required'

check_locks() {
  for lock in TRADING_MODE=paper LIVE_TRADING_ENABLED=false MAX_POSITION_PCT=0.25 \
    STOP_LOSS_PCT=0.015 TAKE_PROFIT_PCT=0.03 MAX_DAILY_LOSS_PCT=0.03; do
    # Reject duplicate assignments, including a contradictory later assignment.
    key="${lock%%=*}"
    [[ "$(grep -cE "^[[:space:]]*(export[[:space:]]+)?${key}[[:space:]]*=" "$app/.env")" == 1 ]] || fail "ambiguous lock: $key"
    grep -qxF "$lock" "$app/.env" || fail "safety lock mismatch: $key"
  done
  for unit in chobyar-trader.service chobyar-status.service chobyar-v5-shadow.timer chobyar-v5-scorecard.timer; do
    systemctl is-active --quiet "$unit" || fail "inactive unit: $unit"
  done
}
identity() {
  systemctl show "$1" -p MainPID -p ExecMainStartTimestampMonotonic -p InvocationID
}
check_locks
trader_before="$(identity chobyar-trader.service)"
status_before="$(identity chobyar-status.service)"

umask 077
work="$(mktemp -d)"
cleanup() {
  result=$?
  trap - EXIT
  rm -rf -- "$work"
  exit "$result"
}
trap cleanup EXIT
git init -q "$work/repo"
git -C "$work/repo" remote add origin https://github.com/mortezaneghabi61-cloud/ChobYarSketch0.git
git -C "$work/repo" fetch -q --depth=1 origin "$expected_sha"
git -C "$work/repo" checkout -q --detach FETCH_HEAD
[[ "$(git -C "$work/repo" rev-parse HEAD)" == "$expected_sha" ]] || fail 'fetched SHA mismatch'
candidate="$work/repo/ops/chobyar-trader/v5"
check_engine() {
  for name in council_evidence.py shadow_runner.py shadow_runner_v52.py \
    specialist_council.py meta_intelligence.py public_source_fallbacks.py; do
    [[ -f "$v5/$name" && ! -L "$v5/$name" ]] || fail "unsafe engine file: $name"
    cmp -s "$candidate/$name" "$v5/$name" || fail "engine drift: $name"
  done
}
check_engine
printf '\n=== V60_LOCAL_CONTRACT_TESTS ===\n'
PYTHONPATH="$candidate" "$venv_python" -B -m unittest discover -s "$candidate" -p 'test_*.py' -v
printf '\n=== V60_EVIDENCE_READINESS ===\n'
PYTHONPATH="$candidate" "$venv_python" -B "$candidate/evidence_replay_readiness.py" "$journal" > "$work/result.json"
# Print only the audited aggregate, never raw evidence or environment content.
"$venv_python" -B - "$work/result.json" <<'PY'
import json, sys
with open(sys.argv[1], encoding="utf-8") as stream:
    result = json.load(stream)
assert result["ok"] is True
for key in ("full_fidelity_multiagent", "execution_authority_granted", "live_authority_granted", "automatic_promotion"):
    assert result[key] is False
print(json.dumps(result, sort_keys=True))
PY
check_engine
check_locks
[[ "$(identity chobyar-trader.service)" == "$trader_before" ]] || fail 'Trader process changed'
[[ "$(identity chobyar-status.service)" == "$status_before" ]] || fail 'Status process changed'
printf '\nVERIFIED_SHA=%s\n' "$expected_sha"
printf 'V60_READ_ONLY_AUDIT=PASS\nTRADER_RESTARTED=NO\nSTATUS_RESTARTED=NO\n'
printf 'PAPER_MODE=PASS\nRISK_UNCHANGED=YES\nFULL_FIDELITY_BACKTEST_READY=NO\nEXECUTION_AUTHORITY=NONE\n'

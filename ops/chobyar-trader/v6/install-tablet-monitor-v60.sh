#!/usr/bin/env bash
set -Eeuo pipefail

REPO_URL="https://github.com/mortezaneghabi61-cloud/ChobYarSketch0.git"
EXPECTED_SHA="${1:-}"
APP_DIR="/opt/chobyar-trader"
SRC_DIR="$APP_DIR/app"
MONITOR_DIR="$APP_DIR/monitor"
SERVER_FILE="$SRC_DIR/status_server_v60.py"
ASSET_FILE="$MONITOR_DIR/paper_exploration_monitor.js"
INDEX_FILE="$MONITOR_DIR/index.html"
UNIT_FILE="/etc/systemd/system/chobyar-status.service"
STATUS_SERVICE="chobyar-status.service"
PROTECTED_SERVICES=(chobyar-trader.service chobyar-profit-protection-shadow.service chobyar-paper-exploration.service)

die() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }
[[ $EUID -eq 0 ]] || die "run as root"
[[ "$EXPECTED_SHA" =~ ^[0-9a-f]{40}$ ]] || die "exact 40-character commit SHA required"
[[ -f "$SRC_DIR/status_server_v53.py" && -f "$INDEX_FILE" && -f "$UNIT_FILE" ]] || die "active v53 monitor baseline is incomplete"
grep -Fqx 'ExecStart=/usr/bin/python3 /opt/chobyar-trader/app/status_server_v53.py' "$UNIT_FILE" || die "unexpected status ExecStart; refusing blind replacement"

declare -A protected_pids
for service in "${PROTECTED_SERVICES[@]}"; do
  systemctl is-active --quiet "$service" || die "$service must be active"
  pid="$(systemctl show -p MainPID --value "$service")"
  [[ "$pid" =~ ^[1-9][0-9]*$ ]] || die "invalid PID for $service"
  protected_pids["$service"]="$pid"
done

umask 077
work="$(mktemp -d)"
backup="$APP_DIR/backups/tablet-monitor-v60-$(date -u +%Y%m%dT%H%M%SZ)"
committed=false
mutation_started=false

cleanup() { rm -rf "$work"; }
rollback() {
  [[ "$mutation_started" == true && "$committed" != true ]] || return
  install -m 700 "$backup/status_server_v60.py" "$SERVER_FILE" 2>/dev/null || rm -f "$SERVER_FILE"
  install -m 644 "$backup/paper_exploration_monitor.js" "$ASSET_FILE" 2>/dev/null || rm -f "$ASSET_FILE"
  install -m 644 "$backup/index.html" "$INDEX_FILE"
  install -m 644 "$backup/chobyar-status.service" "$UNIT_FILE"
  systemctl daemon-reload >/dev/null 2>&1 || true
  systemctl restart "$STATUS_SERVICE" >/dev/null 2>&1 || true
}
trap 'rollback; cleanup' EXIT

git init -q "$work/repo"
git -C "$work/repo" remote add origin "$REPO_URL"
git -C "$work/repo" fetch -q --depth=1 origin "$EXPECTED_SHA"
git -C "$work/repo" checkout -q --detach FETCH_HEAD
[[ "$(git -C "$work/repo" rev-parse HEAD)" == "$EXPECTED_SHA" ]] || die "downloaded commit mismatch"
src="$work/repo/ops/chobyar-trader/v6"
for file in status_server_v60.py paper_exploration_monitor.js test_status_server_v60.py; do
  [[ -f "$src/$file" ]] || die "commit lacks $file"
done

PYTHONPATH="$src" python3 -m unittest discover -v -s "$src" -p 'test_status_server_v60.py'
python3 -m py_compile "$src/status_server_v60.py"
if grep -niE 'api[_-]?key|authorization|STATUS_HMAC_SECRET|submit_order|create_order|place_order|cancel_order|withdraw|\.(post|put|patch|delete)\(' "$src/status_server_v60.py" "$src/paper_exploration_monitor.js"; then
  die "secret or execution surface detected"
fi

mkdir -p "$backup"
cp -a "$INDEX_FILE" "$backup/index.html"
cp -a "$UNIT_FILE" "$backup/chobyar-status.service"
[[ ! -f "$SERVER_FILE" ]] || cp -a "$SERVER_FILE" "$backup/status_server_v60.py"
[[ ! -f "$ASSET_FILE" ]] || cp -a "$ASSET_FILE" "$backup/paper_exploration_monitor.js"
mutation_started=true
install -m 700 "$src/status_server_v60.py" "$SERVER_FILE"
install -m 644 "$src/paper_exploration_monitor.js" "$ASSET_FILE"

python3 - "$INDEX_FILE" <<'PY'
from pathlib import Path
import sys

path = Path(sys.argv[1])
text = path.read_text(encoding="utf-8")
tag = '<script src="/monitor/paper_exploration_monitor.js" defer></script>'
if tag not in text:
    if "</body>" not in text:
        raise SystemExit("index.html has no closing body tag")
    text = text.replace("</body>", f"  {tag}\n</body>", 1)
    path.write_text(text, encoding="utf-8")
PY
chmod 644 "$INDEX_FILE"
sed -i 's#^ExecStart=/usr/bin/python3 /opt/chobyar-trader/app/status_server_v53.py$#ExecStart=/usr/bin/python3 /opt/chobyar-trader/app/status_server_v60.py#' "$UNIT_FILE"
chmod 644 "$UNIT_FILE"

systemctl daemon-reload
systemctl restart "$STATUS_SERVICE"
sleep 3
systemctl is-active --quiet "$STATUS_SERVICE" || die "status service is not active after upgrade"
for service in "${PROTECTED_SERVICES[@]}"; do
  [[ "$(systemctl show -p MainPID --value "$service")" == "${protected_pids[$service]}" ]] || die "$service PID changed"
done
report="$(curl -fsS --max-time 4 http://127.0.0.1:8787/public-report)"
python3 -c 'import json,sys; d=json.load(sys.stdin); x=d["paper_exploration"]; assert d["report_version"] == 7; assert x["execution_authority"] is False; assert set(x["lanes"]) == {"wide","balanced","selective"}' <<<"$report" || die "v60 public report contract failed"
curl -fsS --max-time 4 http://127.0.0.1:8787/monitor/paper_exploration_monitor.js >/dev/null || die "monitor asset unavailable"

committed=true
printf 'DEPLOYED_SHA=%s\nMONITOR_STATUS=PASS\nSTATUS_VERSION=7\nTRADER_PID_UNCHANGED=%s\nSHADOW_PID_UNCHANGED=%s\nEXPLORATION_PID_UNCHANGED=%s\n' \
  "$EXPECTED_SHA" "${protected_pids[chobyar-trader.service]}" "${protected_pids[chobyar-profit-protection-shadow.service]}" "${protected_pids[chobyar-paper-exploration.service]}"

#!/usr/bin/env bash
set -Eeuo pipefail

REPO_URL="https://github.com/mortezaneghabi61-cloud/ChobYarSketch0.git"
EXPECTED_SHA="${1:-}"
APP_DIR="/opt/chobyar-trader"
ENV_FILE="$APP_DIR/.env"
VENV="$APP_DIR/.venv"
TRADER_UNIT="/etc/systemd/system/chobyar-trader.service"
STATUS_UNIT="/etc/systemd/system/chobyar-status.service"

die() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }
[[ $EUID -eq 0 ]] || die "run as root"
[[ "$EXPECTED_SHA" =~ ^[0-9a-f]{40}$ ]] || die "exact 40-character commit SHA required"
[[ -f "$ENV_FILE" && -x "$VENV/bin/python" ]] || die "existing trader installation is incomplete"
grep -qx 'TRADING_MODE=paper' "$ENV_FILE" || die "FAIL-CLOSED: TRADING_MODE must be paper"
grep -qx 'LIVE_TRADING_ENABLED=false' "$ENV_FILE" || die "FAIL-CLOSED: LIVE_TRADING_ENABLED must be false"
grep -qx 'MAX_POSITION_PCT=0.25' "$ENV_FILE" || die "FAIL-CLOSED: MAX_POSITION_PCT mismatch"
grep -qx 'STOP_LOSS_PCT=0.015' "$ENV_FILE" || die "FAIL-CLOSED: STOP_LOSS_PCT mismatch"
grep -qx 'TAKE_PROFIT_PCT=0.03' "$ENV_FILE" || die "FAIL-CLOSED: TAKE_PROFIT_PCT mismatch"
grep -qx 'MAX_DAILY_LOSS_PCT=0.03' "$ENV_FILE" || die "FAIL-CLOSED: MAX_DAILY_LOSS_PCT mismatch"

umask 077
work="$(mktemp -d)"
backup="$APP_DIR/backups/v4-$(date -u +%Y%m%dT%H%M%SZ)"
cleanup() { rm -rf "$work"; }
trap cleanup EXIT

git init -q "$work/repo"
git -C "$work/repo" remote add origin "$REPO_URL"
git -C "$work/repo" fetch -q --depth=1 origin "$EXPECTED_SHA"
git -C "$work/repo" checkout -q --detach FETCH_HEAD
[[ "$(git -C "$work/repo" rev-parse HEAD)" == "$EXPECTED_SHA" ]] || die "downloaded commit mismatch"
src="$work/repo/ops/chobyar-trader"
[[ -f "$src/v4/trader.py" && -f "$src/v4/backtest.py" && -f "$src/v3/status_server.py" ]] || die "commit lacks v4 files"

"$VENV/bin/python" -m py_compile "$src/v4/common.py" "$src/v4/trader.py" "$src/v4/backtest.py" "$src/v3/status_server.py" "$src/v3/status_client.py"
PYTHONPATH="$src/v4" "$VENV/bin/python" -m unittest -v "$src/v4/test_v4.py"
PYTHONPATH="$src/v4" "$VENV/bin/python" "$src/v4/backtest.py" --output "$work/backtest.json"
"$VENV/bin/python" -c 'import json,sys; d=json.load(open(sys.argv[1])); assert d["ok"] and d["candles"] >= 80 and d["lookahead_policy"] == "close[i-1] signal; open[i] execution"' "$work/backtest.json"

mkdir -p "$backup" "$APP_DIR/app" "$APP_DIR/bin" "$APP_DIR/state" "$APP_DIR/logs"
chmod 700 "$APP_DIR" "$backup" "$APP_DIR/bin"
# Deliberately exclude .env: secrets are neither copied nor printed.
for file in "$APP_DIR/app/trader.py" "$APP_DIR/app/common.py" "$APP_DIR/app/backtest.py" "$APP_DIR/app/status_server.py" "$APP_DIR/bin/status_client.py" "$TRADER_UNIT" "$STATUS_UNIT"; do
  [[ -f "$file" ]] && cp -a "$file" "$backup/$(basename "$file")"
done

install -m 700 "$src/v4/common.py" "$APP_DIR/app/common.py"
install -m 700 "$src/v4/trader.py" "$APP_DIR/app/trader.py"
install -m 700 "$src/v4/backtest.py" "$APP_DIR/app/backtest.py"
install -m 700 "$src/v3/status_server.py" "$APP_DIR/app/status_server.py"
install -m 700 "$src/v3/status_client.py" "$APP_DIR/bin/status_client.py"
install -m 600 "$work/backtest.json" "$APP_DIR/state/backtest_latest.json"

if ! grep -Eq '^STATUS_HMAC_SECRET=.{32,}$' "$ENV_FILE"; then
  secret="$($VENV/bin/python -c 'import secrets; print(secrets.token_hex(32))')"
  printf 'STATUS_HMAC_SECRET=%s\n' "$secret" >> "$ENV_FILE"
  unset secret
fi
if grep -q '^STATUS_REQUIRE_AUTH=' "$ENV_FILE"; then
  sed -i 's/^STATUS_REQUIRE_AUTH=.*/STATUS_REQUIRE_AUTH=true/' "$ENV_FILE"
else
  printf 'STATUS_REQUIRE_AUTH=true\n' >> "$ENV_FILE"
fi
grep -q '^STATUS_PORT=' "$ENV_FILE" || printf 'STATUS_PORT=8787\n' >> "$ENV_FILE"
chmod 600 "$ENV_FILE"

install -m 700 /dev/stdin "$APP_DIR/bin/chobyar-status" <<'WRAP'
#!/usr/bin/env bash
exec /opt/chobyar-trader/.venv/bin/python /opt/chobyar-trader/bin/status_client.py "$@"
WRAP

install -m 644 /dev/stdin "$TRADER_UNIT" <<'UNIT'
[Unit]
Description=ChobYar Trader v4 Paper-Only Engine
After=network-online.target
Wants=network-online.target
[Service]
Type=simple
WorkingDirectory=/opt/chobyar-trader/app
EnvironmentFile=/opt/chobyar-trader/.env
ExecStart=/opt/chobyar-trader/.venv/bin/python /opt/chobyar-trader/app/trader.py
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

install -m 644 /dev/stdin "$STATUS_UNIT" <<'UNIT'
[Unit]
Description=ChobYar Authenticated Read-Only Status
After=network-online.target chobyar-trader.service
[Service]
Type=simple
WorkingDirectory=/opt/chobyar-trader/app
ExecStart=/usr/bin/python3 /opt/chobyar-trader/app/status_server.py
Restart=on-failure
RestartSec=5
User=root
NoNewPrivileges=true
PrivateTmp=true
ProtectHome=true
ProtectSystem=strict
ReadOnlyPaths=/opt/chobyar-trader
[Install]
WantedBy=multi-user.target
UNIT

systemctl daemon-reload
systemctl enable chobyar-trader.service chobyar-status.service >/dev/null
systemctl restart chobyar-trader.service
systemctl restart chobyar-status.service
sleep 3
systemctl is-active --quiet chobyar-trader.service || die "trader service inactive"
systemctl is-active --quiet chobyar-status.service || die "status service inactive"
port="$(awk -F= '$1=="STATUS_PORT" {print $2}' "$ENV_FILE" | tail -n1)"; port="${port:-8787}"
[[ "$(curl -sS -o /dev/null -w '%{http_code}' "http://127.0.0.1:${port}/health")" == 200 ]] || die "health validation failed"
[[ "$(curl -sS -o /dev/null -w '%{http_code}' "http://127.0.0.1:${port}/status")" == 401 ]] || die "unauthenticated status was not rejected"
"$APP_DIR/bin/chobyar-status" >/dev/null || die "authenticated signed status validation failed"
grep -qx 'TRADING_MODE=paper' "$ENV_FILE" && grep -qx 'LIVE_TRADING_ENABLED=false' "$ENV_FILE" || die "paper lock changed"
printf 'DEPLOYED_SHA=%s\nPAPER_STATUS=PASS\nLIVE_GATE=LOCKED\nSTATUS_AUTH=PASS\n' "$EXPECTED_SHA"

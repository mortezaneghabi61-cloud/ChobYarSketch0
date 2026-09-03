#!/usr/bin/env bash
set -Eeuo pipefail

REPO="mortezaneghabi61-cloud/ChobYarSketch0"
SOURCE_REF="${CHOBYAR_SOURCE_REF:-}"
APP_DIR="/opt/chobyar-trader"
ENV_FILE="$APP_DIR/.env"
VENV="$APP_DIR/.venv"
APP_SUBDIR="$APP_DIR/app"
BIN_DIR="$APP_DIR/bin"
STATUS_SERVICE="/etc/systemd/system/chobyar-status.service"

log() { printf '\n[chobyar-status-v3] %s\n' "$*"; }
die() { echo "ERROR: $*" >&2; exit 1; }

[[ $EUID -eq 0 ]] || die "Run as root."
[[ "$SOURCE_REF" =~ ^[0-9a-f]{40}$ ]] || die "CHOBYAR_SOURCE_REF must be an exact 40-character commit SHA."
[[ -f "$ENV_FILE" ]] || die "Missing $ENV_FILE."
[[ -x "$VENV/bin/python" ]] || die "Missing $VENV/bin/python."
grep -q '^TRADING_MODE=paper$' "$ENV_FILE" || die "FAIL-CLOSED: TRADING_MODE is not paper."
grep -q '^LIVE_TRADING_ENABLED=false$' "$ENV_FILE" || die "FAIL-CLOSED: LIVE_TRADING_ENABLED is not false."

umask 077
mkdir -p "$APP_SUBDIR" "$BIN_DIR" "$APP_DIR/backups"
chmod 700 "$APP_DIR" "$BIN_DIR"
chmod 600 "$ENV_FILE"

set_env() {
  local key="$1" value="$2"
  if grep -q "^${key}=" "$ENV_FILE"; then
    sed -i "s|^${key}=.*|${key}=${value}|" "$ENV_FILE"
  else
    printf '%s=%s\n' "$key" "$value" >> "$ENV_FILE"
  fi
}

set_env STATUS_REQUIRE_AUTH true
if ! grep -Eq '^STATUS_HMAC_SECRET=.{32,}$' "$ENV_FILE"; then
  status_secret="$($VENV/bin/python -c 'import secrets; print(secrets.token_hex(32))')"
  set_env STATUS_HMAC_SECRET "$status_secret"
  unset status_secret
fi
if ! grep -q '^STATUS_PORT=' "$ENV_FILE"; then
  printf 'STATUS_PORT=8787\n' >> "$ENV_FILE"
fi
chmod 600 "$ENV_FILE"

TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT
RAW="https://raw.githubusercontent.com/${REPO}/${SOURCE_REF}/ops/chobyar-trader/v3"

log "Downloading authenticated status files from exact commit"
curl -fsSL --retry 3 --connect-timeout 10 "$RAW/status_server.py" -o "$TMP_DIR/status_server.py"
curl -fsSL --retry 3 --connect-timeout 10 "$RAW/status_client.py" -o "$TMP_DIR/status_client.py"
printf '%s  %s\n' \
  'e22b0c63d6c0d73ad937d58112daa2eadcec99946fbb02f828499602ec736d30' status_server.py \
  '2b76ec3b5f82e566799f219c3188eb6c986d45f971fa755deb77a571892fe219' status_client.py \
  > "$TMP_DIR/SHA256SUMS"
(cd "$TMP_DIR" && sha256sum -c SHA256SUMS)
"$VENV/bin/python" -m py_compile "$TMP_DIR/status_server.py" "$TMP_DIR/status_client.py"

stamp="$(date +%Y%m%d%H%M%S)"
backup="$APP_DIR/backups/status-v3-$stamp"
mkdir -p "$backup"
chmod 700 "$backup"
[[ -f "$APP_SUBDIR/status_server.py" ]] && cp -a "$APP_SUBDIR/status_server.py" "$backup/status_server.py"
[[ -f "$STATUS_SERVICE" ]] && cp -a "$STATUS_SERVICE" "$backup/chobyar-status.service"
cp -a "$ENV_FILE" "$backup/.env.after-auth"
chmod 600 "$backup/.env.after-auth"

install -m 755 "$TMP_DIR/status_server.py" "$APP_SUBDIR/status_server.py"
install -m 700 "$TMP_DIR/status_client.py" "$BIN_DIR/status_client.py"
cat > "$BIN_DIR/chobyar-status" <<'WRAPPER'
#!/usr/bin/env bash
exec /opt/chobyar-trader/.venv/bin/python /opt/chobyar-trader/bin/status_client.py "$@"
WRAPPER
chmod 700 "$BIN_DIR/chobyar-status"

cat > "$STATUS_SERVICE" <<'UNIT'
[Unit]
Description=ChobYar Trader Authenticated Read-Only Status
After=network-online.target chobyar-trader.service
Wants=network-online.target

[Service]
Type=simple
ExecStart=/usr/bin/python3 /opt/chobyar-trader/app/status_server.py
Restart=always
RestartSec=5
User=root
NoNewPrivileges=true
PrivateTmp=true
ProtectHome=true
ProtectSystem=strict
Environment=PYTHONDONTWRITEBYTECODE=1

[Install]
WantedBy=multi-user.target
UNIT

log "Restarting read-only status service"
systemctl daemon-reload
systemctl enable chobyar-status.service >/dev/null
systemctl restart chobyar-status.service
sleep 2
systemctl is-active --quiet chobyar-trader.service || die "Trader service is not active after status-only upgrade."
systemctl is-active --quiet chobyar-status.service || die "Status service failed to start."
grep -q '^TRADING_MODE=paper$' "$ENV_FILE" || die "Paper lock changed unexpectedly."
grep -q '^LIVE_TRADING_ENABLED=false$' "$ENV_FILE" || die "Live lock changed unexpectedly."

PORT="$(awk -F= '$1=="STATUS_PORT" {print $2}' "$ENV_FILE" | tail -n1)"
PORT="${PORT:-8787}"
UNAUTH_CODE="$(curl -sS -o /dev/null -w '%{http_code}' "http://127.0.0.1:${PORT}/status" || true)"
[[ "$UNAUTH_CODE" == "401" ]] || die "Unauthenticated /status did not fail closed (HTTP $UNAUTH_CODE)."
HEALTH_CODE="$(curl -sS -o /dev/null -w '%{http_code}' "http://127.0.0.1:${PORT}/health" || true)"
[[ "$HEALTH_CODE" == "200" ]] || die "/health failed (HTTP $HEALTH_CODE)."
"$BIN_DIR/chobyar-status" >/dev/null || die "Authenticated local status verification failed."

log "Status v3 installed"
printf 'SOURCE_REF=%s\n' "$SOURCE_REF"
printf 'TRADER_SERVICE=active\nSTATUS_SERVICE=active\n'
printf 'MODE=paper\nLIVE_TRADING_ENABLED=false\n'
printf 'UNAUTHENTICATED_STATUS=HTTP_401\nAUTHENTICATED_STATUS=PASS\n'
printf 'STATUS_SECRET=local-env-only-not-printed\n'
printf 'LOCAL_STATUS_COMMAND=sudo %s/chobyar-status\n' "$BIN_DIR"
printf 'LIVE_GATE=LOCKED\n'

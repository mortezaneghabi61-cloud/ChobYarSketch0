#!/usr/bin/env bash
set -Eeuo pipefail

REPO_URL="https://github.com/mortezaneghabi61-cloud/ChobYarSketch0.git"
EXPECTED_SHA="${1:-}"
PUBLIC_IP="109.122.247.214"
APP_DIR="/opt/chobyar-trader"
ENV_FILE="$APP_DIR/.env"
VENV="$APP_DIR/.venv"
STATUS_PORT_DEFAULT="8787"
NGINX_SITE="/etc/nginx/sites-available/chobyar-monitor"
NGINX_ENABLED="/etc/nginx/sites-enabled/chobyar-monitor"
CERT_LIVE="/etc/letsencrypt/live/${PUBLIC_IP}"
CERT_RENEW_SERVICE="/etc/systemd/system/chobyar-cert-renew.service"
CERT_RENEW_TIMER="/etc/systemd/system/chobyar-cert-renew.timer"
WEBROOT="/var/www/chobyar-certbot"

die() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }

[[ $EUID -eq 0 ]] || die "run as root"
[[ "$EXPECTED_SHA" =~ ^[0-9a-f]{40}$ ]] || die "exact 40-character commit SHA required"
[[ -f "$ENV_FILE" && -x "$VENV/bin/python" ]] || die "existing trader installation incomplete"

grep -qx 'TRADING_MODE=paper' "$ENV_FILE" || die "FAIL-CLOSED: TRADING_MODE must be paper"
grep -qx 'LIVE_TRADING_ENABLED=false' "$ENV_FILE" || die "FAIL-CLOSED: LIVE_TRADING_ENABLED must be false"
grep -qx 'MAX_POSITION_PCT=0.25' "$ENV_FILE" || die "FAIL-CLOSED: MAX_POSITION_PCT mismatch"
grep -qx 'STOP_LOSS_PCT=0.015' "$ENV_FILE" || die "FAIL-CLOSED: STOP_LOSS_PCT mismatch"
grep -qx 'TAKE_PROFIT_PCT=0.03' "$ENV_FILE" || die "FAIL-CLOSED: TAKE_PROFIT_PCT mismatch"
grep -qx 'MAX_DAILY_LOSS_PCT=0.03' "$ENV_FILE" || die "FAIL-CLOSED: MAX_DAILY_LOSS_PCT mismatch"

trader_pid_before="$(systemctl show chobyar-trader.service -p MainPID --value)"
trader_started_before="$(systemctl show chobyar-trader.service -p ExecMainStartTimestampMonotonic --value)"
[[ "$trader_pid_before" =~ ^[1-9][0-9]*$ ]] || die "trader service is not running"

umask 077
work="$(mktemp -d)"
cleanup() {
  rc=$?
  rm -rf "$work"
  exit "$rc"
}
trap cleanup EXIT

git init -q "$work/repo"
git -C "$work/repo" remote add origin "$REPO_URL"
git -C "$work/repo" fetch -q --depth=1 origin "$EXPECTED_SHA"
git -C "$work/repo" checkout -q --detach FETCH_HEAD
[[ "$(git -C "$work/repo" rev-parse HEAD)" == "$EXPECTED_SHA" ]] || die "downloaded commit mismatch"
src="$work/repo/ops/chobyar-trader"
[[ -f "$src/upgrade-v45-monitor.sh" ]] || die "candidate missing monitor upgrader"

port="$(awk -F= '$1=="STATUS_PORT" {print $2}' "$ENV_FILE" | tail -n1)"
port="${port:-$STATUS_PORT_DEFAULT}"

if [[ "$(curl -sS -o /dev/null -w '%{http_code}' "http://127.0.0.1:${port}/monitor/" || true)" != "200" ]]; then
  bash "$src/upgrade-v45-monitor.sh" "$EXPECTED_SHA"
fi

[[ "$(curl -sS -o /dev/null -w '%{http_code}' "http://127.0.0.1:${port}/monitor/" || true)" == "200" ]] || die "monitor is not available locally"
[[ "$(curl -sS -o /dev/null -w '%{http_code}' "http://127.0.0.1:${port}/status" || true)" == "401" ]] || die "unauthenticated status was not rejected"

listeners="$(ss -ltnp 2>/dev/null | awk '$4 ~ /:80$|:443$/ {print}' || true)"
if [[ -n "$listeners" ]] && ! grep -qi 'nginx' <<<"$listeners"; then
  printf '%s\n' "$listeners" >&2
  die "ports 80/443 are already owned by a non-nginx service"
fi

export DEBIAN_FRONTEND=noninteractive
apt-get update -qq
apt-get install -y -qq nginx snapd ca-certificates curl >/dev/null

systemctl enable --now snapd.socket >/dev/null 2>&1 || true

CERTBOT_BIN="$(command -v certbot || true)"
certbot_ok=0
if [[ -n "$CERTBOT_BIN" && -x "$CERTBOT_BIN" ]]; then
  certbot_version="$("$CERTBOT_BIN" --version 2>&1 | awk '{print $2}' | head -n1)"
  if "$VENV/bin/python" - "$certbot_version" <<'PY'
import re, sys
parts = [int(x) for x in re.findall(r'\d+', sys.argv[1])[:2]]
while len(parts) < 2:
    parts.append(0)
raise SystemExit(0 if tuple(parts) >= (5, 4) else 1)
PY
  then
    certbot_ok=1
  fi
fi

if [[ "$certbot_ok" != "1" ]]; then
  snap list core >/dev/null 2>&1 || snap install core >/dev/null
  snap refresh core >/dev/null || true
  snap list certbot >/dev/null 2>&1 || snap install --classic certbot >/dev/null
  CERTBOT_BIN="/snap/bin/certbot"
fi

[[ -x "$CERTBOT_BIN" ]] || die "Certbot installation failed"
certbot_version="$("$CERTBOT_BIN" --version 2>&1 | awk '{print $2}' | head -n1)"
"$VENV/bin/python" - "$certbot_version" <<'PY'
import re, sys
parts = [int(x) for x in re.findall(r'\d+', sys.argv[1])[:2]]
while len(parts) < 2:
    parts.append(0)
if tuple(parts) < (5, 4):
    raise SystemExit(f"Certbot >=5.4 required for IP webroot certificates; found {sys.argv[1]}")
PY

install -d -m 755 "$WEBROOT/.well-known/acme-challenge"
rm -f /etc/nginx/sites-enabled/default

cat >"$NGINX_SITE" <<NGINX_HTTP
server {
    listen 80 default_server;
    listen [::]:80 default_server;
    server_name ${PUBLIC_IP};

    location ^~ /.well-known/acme-challenge/ {
        root ${WEBROOT};
        default_type text/plain;
        try_files \$uri =404;
    }

    location / {
        return 404;
    }
}
NGINX_HTTP
ln -sfn "$NGINX_SITE" "$NGINX_ENABLED"
nginx -t
systemctl enable --now nginx >/dev/null
systemctl reload nginx

if [[ ! -s "$CERT_LIVE/fullchain.pem" || ! -s "$CERT_LIVE/privkey.pem" ]]; then
  "$CERTBOT_BIN" certonly \
    --non-interactive \
    --agree-tos \
    --register-unsafely-without-email \
    --preferred-profile shortlived \
    --webroot \
    --webroot-path "$WEBROOT" \
    --ip-address "$PUBLIC_IP"
fi

[[ -s "$CERT_LIVE/fullchain.pem" && -s "$CERT_LIVE/privkey.pem" ]] || die "trusted IP certificate was not issued"

cat >"$NGINX_SITE" <<NGINX_TLS
server {
    listen 80 default_server;
    listen [::]:80 default_server;
    server_name ${PUBLIC_IP};

    location ^~ /.well-known/acme-challenge/ {
        root ${WEBROOT};
        default_type text/plain;
        try_files \$uri =404;
    }

    location / {
        return 308 https://\$host\$request_uri;
    }
}

server {
    listen 443 ssl default_server;
    listen [::]:443 ssl default_server;
    server_name ${PUBLIC_IP};

    ssl_certificate ${CERT_LIVE}/fullchain.pem;
    ssl_certificate_key ${CERT_LIVE}/privkey.pem;
    ssl_protocols TLSv1.2 TLSv1.3;
    ssl_session_cache shared:SSL:10m;
    ssl_session_timeout 1d;
    add_header Strict-Transport-Security "max-age=86400" always;

    location = / {
        return 302 /monitor/;
    }

    location = /monitor {
        return 302 /monitor/;
    }

    location ^~ /monitor/ {
        proxy_pass http://127.0.0.1:${port};
        proxy_http_version 1.1;
        proxy_set_header Host \$host;
        proxy_set_header X-Forwarded-Proto https;
        proxy_set_header Connection "";
    }

    location = /public-report {
        proxy_pass http://127.0.0.1:${port};
        proxy_http_version 1.1;
        proxy_set_header Host \$host;
        proxy_set_header X-Forwarded-Proto https;
        proxy_set_header Connection "";
        proxy_hide_header Access-Control-Allow-Origin;
    }

    location = /health {
        proxy_pass http://127.0.0.1:${port};
        proxy_http_version 1.1;
        proxy_set_header Host \$host;
        proxy_set_header X-Forwarded-Proto https;
        proxy_set_header Connection "";
    }

    location = /status {
        return 404;
    }

    location / {
        return 404;
    }
}
NGINX_TLS

nginx -t
systemctl reload nginx

cat >"$CERT_RENEW_SERVICE" <<UNIT
[Unit]
Description=Renew ChobYar short-lived IP TLS certificate
After=network-online.target nginx.service
Wants=network-online.target

[Service]
Type=oneshot
ExecStart=${CERTBOT_BIN} renew --quiet
ExecStartPost=/usr/bin/systemctl reload nginx
NoNewPrivileges=true
PrivateTmp=true
ProtectHome=true
UNIT

cat >"$CERT_RENEW_TIMER" <<'UNIT'
[Unit]
Description=Refresh ChobYar TLS certificate twice daily

[Timer]
OnBootSec=15min
OnUnitActiveSec=12h
RandomizedDelaySec=20min
Persistent=true
Unit=chobyar-cert-renew.service

[Install]
WantedBy=timers.target
UNIT

systemctl daemon-reload
systemctl enable --now chobyar-cert-renew.timer >/dev/null

curl -fsS --connect-timeout 8 --max-time 20 "https://${PUBLIC_IP}/health" -o "$work/health.json"
curl -fsS --connect-timeout 8 --max-time 20 "https://${PUBLIC_IP}/public-report" -o "$work/report.json"
curl -fsS --connect-timeout 8 --max-time 20 "https://${PUBLIC_IP}/monitor/" -o "$work/monitor.html"
status_code="$(curl -sS -o /dev/null -w '%{http_code}' --connect-timeout 8 --max-time 20 "https://${PUBLIC_IP}/status")"
[[ "$status_code" == "404" ]] || die "HTTPS proxy exposed /status"

"$VENV/bin/python" - "$work/report.json" <<'PY'
import json, sys
r = json.load(open(sys.argv[1], encoding='utf-8'))
assert r.get('ok') is True
assert r.get('public_report') is True
assert r.get('mode') == 'paper'
assert r.get('live_locked') is True
assert not ({'paper', 'market', 'risk', 'security'} & set(r))
PY
grep -q 'ChobYar Trader Monitor' "$work/monitor.html" || die "HTTPS monitor validation failed"

systemctl is-active --quiet nginx || die "nginx inactive"
systemctl is-active --quiet chobyar-status.service || die "status service inactive"
systemctl is-active --quiet chobyar-trader.service || die "trader service inactive"
systemctl is-active --quiet chobyar-cert-renew.timer || die "certificate renewal timer inactive"

trader_pid_after="$(systemctl show chobyar-trader.service -p MainPID --value)"
trader_started_after="$(systemctl show chobyar-trader.service -p ExecMainStartTimestampMonotonic --value)"
[[ "$trader_pid_after" == "$trader_pid_before" && "$trader_started_after" == "$trader_started_before" ]] || die "FAIL-CLOSED: trader restarted during HTTPS upgrade"

grep -qx 'TRADING_MODE=paper' "$ENV_FILE" || die "paper mode changed"
grep -qx 'LIVE_TRADING_ENABLED=false' "$ENV_FILE" || die "live lock changed"
grep -qx 'MAX_POSITION_PCT=0.25' "$ENV_FILE" || die "risk changed"
grep -qx 'STOP_LOSS_PCT=0.015' "$ENV_FILE" || die "risk changed"
grep -qx 'TAKE_PROFIT_PCT=0.03' "$ENV_FILE" || die "risk changed"
grep -qx 'MAX_DAILY_LOSS_PCT=0.03' "$ENV_FILE" || die "risk changed"

printf 'DEPLOYED_SHA=%s\nPAPER_STATUS=PASS\nLIVE_GATE=LOCKED\nMONITOR_HTTPS=ACTIVE\nTRADER_RESTARTED=NO\nTLS_RENEWAL_TIMER=ACTIVE\nMONITOR_URL=https://%s/monitor/\n' "$EXPECTED_SHA" "$PUBLIC_IP"

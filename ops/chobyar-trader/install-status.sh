#!/usr/bin/env bash
set -Eeuo pipefail

APP_DIR="/opt/chobyar-trader"
STATUS_APP="$APP_DIR/app/status_server.py"
SERVICE="/etc/systemd/system/chobyar-status.service"
PORT="8787"

if [[ $EUID -ne 0 ]]; then
  echo "Run as root" >&2
  exit 1
fi

mkdir -p "$APP_DIR/app"

cat > "$STATUS_APP" <<'PY'
from __future__ import annotations

import json
import os
import subprocess
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlparse

APP_DIR = Path('/opt/chobyar-trader')
STATE_FILE = APP_DIR / 'state' / 'paper_state.json'
AUDIT_FILE = APP_DIR / 'logs' / 'audit.jsonl'
ENV_FILE = APP_DIR / '.env'
PORT = int(os.getenv('STATUS_PORT', '8787'))

SAFE_ENV_KEYS = {
    'TRADING_MODE',
    'LIVE_TRADING_ENABLED',
    'SYMBOL',
    'PAPER_START_USDT',
    'MAX_POSITION_PCT',
    'STOP_LOSS_PCT',
    'TAKE_PROFIT_PCT',
    'MAX_DAILY_LOSS_PCT',
}


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat()


def read_safe_env() -> dict:
    out = {}
    try:
        for raw in ENV_FILE.read_text(encoding='utf-8').splitlines():
            if '=' not in raw or raw.lstrip().startswith('#'):
                continue
            k, v = raw.split('=', 1)
            k = k.strip()
            if k in SAFE_ENV_KEYS:
                out[k] = v.strip()
    except Exception:
        pass
    return out


def read_state() -> dict:
    try:
        return json.loads(STATE_FILE.read_text(encoding='utf-8'))
    except Exception:
        return {}


def read_last_cycle() -> dict:
    try:
        lines = AUDIT_FILE.read_text(encoding='utf-8').splitlines()
    except Exception:
        return {}
    for raw in reversed(lines[-300:]):
        try:
            obj = json.loads(raw)
        except Exception:
            continue
        if obj.get('event') == 'cycle':
            safe = {
                'ts': obj.get('ts'),
                'symbol': obj.get('symbol'),
                'mid': obj.get('mid'),
                'signal': obj.get('signal'),
                'action': obj.get('action'),
                'risk_reason': obj.get('risk_reason'),
                'equity': obj.get('equity'),
                'realized_pnl': obj.get('realized_pnl'),
                'trades': obj.get('trades'),
            }
            return safe
    return {}


def trader_service_status() -> str:
    try:
        p = subprocess.run(
            ['systemctl', 'is-active', 'chobyar-trader'],
            capture_output=True,
            text=True,
            timeout=2,
            check=False,
        )
        return (p.stdout or p.stderr).strip() or 'unknown'
    except Exception:
        return 'unknown'


def payload() -> dict:
    env = read_safe_env()
    state = read_state()
    last = read_last_cycle()
    return {
        'ok': True,
        'server_time_utc': utc_now(),
        'service': trader_service_status(),
        'mode': env.get('TRADING_MODE', 'unknown'),
        'live_trading_enabled': env.get('LIVE_TRADING_ENABLED', 'unknown'),
        'symbol': env.get('SYMBOL', 'unknown'),
        'risk': {
            'max_position_pct': env.get('MAX_POSITION_PCT'),
            'stop_loss_pct': env.get('STOP_LOSS_PCT'),
            'take_profit_pct': env.get('TAKE_PROFIT_PCT'),
            'max_daily_loss_pct': env.get('MAX_DAILY_LOSS_PCT'),
        },
        'paper': {
            'cash_usdt': state.get('cash_usdt'),
            'btc_qty': state.get('btc_qty'),
            'entry_price': state.get('entry_price'),
            'realized_pnl': state.get('realized_pnl'),
            'trades': state.get('trades'),
            'day_start_equity': state.get('day_start_equity'),
        },
        'last_cycle': last,
    }


class Handler(BaseHTTPRequestHandler):
    def _send(self, code: int, body: bytes, ctype: str = 'application/json; charset=utf-8') -> None:
        self.send_response(code)
        self.send_header('Content-Type', ctype)
        self.send_header('Cache-Control', 'no-store')
        self.send_header('X-Content-Type-Options', 'nosniff')
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self) -> None:
        path = urlparse(self.path).path
        if path in ('/', '/status', '/health'):
            body = json.dumps(payload(), ensure_ascii=False, separators=(',', ':')).encode('utf-8')
            self._send(200, body)
        else:
            self._send(404, b'{"ok":false,"error":"not_found"}')

    def log_message(self, fmt: str, *args) -> None:
        return


if __name__ == '__main__':
    ThreadingHTTPServer(('0.0.0.0', PORT), Handler).serve_forever()
PY

chmod 755 "$STATUS_APP"

cat > "$SERVICE" <<'UNIT'
[Unit]
Description=ChobYar Trader Read-Only Status
After=network-online.target chobyar-trader.service
Wants=network-online.target

[Service]
Type=simple
ExecStart=/usr/bin/python3 /opt/chobyar-trader/app/status_server.py
Restart=always
RestartSec=5
NoNewPrivileges=true
PrivateTmp=true
ProtectHome=true
ProtectSystem=strict
ReadWritePaths=/opt/chobyar-trader

[Install]
WantedBy=multi-user.target
UNIT

systemctl daemon-reload
systemctl enable --now chobyar-status

if command -v ufw >/dev/null 2>&1; then
  if ufw status | grep -q '^Status: active'; then
    ufw allow ${PORT}/tcp >/dev/null
  fi
fi

sleep 1
systemctl --no-pager --full status chobyar-status | sed -n '1,12p'
echo
printf 'STATUS_URL=http://%s:%s/status\n' "$(curl -4 -fsS https://api.ipify.org)" "$PORT"

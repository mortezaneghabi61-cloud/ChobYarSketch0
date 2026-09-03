from __future__ import annotations

import hashlib
import hmac
import json
import os
import subprocess
import time
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlparse

APP_DIR = Path('/opt/chobyar-trader')
STATE_FILE = APP_DIR / 'state' / 'paper_state.json'
BACKTEST_FILE = APP_DIR / 'state' / 'backtest_latest.json'
AUDIT_FILE = APP_DIR / 'logs' / 'audit.jsonl'
ENV_FILE = APP_DIR / '.env'

SAFE_ENV_KEYS = {
    'TRADING_MODE', 'LIVE_TRADING_ENABLED', 'RISK_PROFILE', 'SYMBOL', 'PAPER_START_USDT',
    'MAX_POSITION_PCT', 'STOP_LOSS_PCT', 'TAKE_PROFIT_PCT', 'MAX_DAILY_LOSS_PCT',
    'MAX_SPREAD_PCT', 'ENTRY_SCORE_THRESHOLD', 'EXIT_SCORE_THRESHOLD', 'MIN_AGENT_QUORUM',
    'MAX_ENTRIES_PER_DAY', 'COOLDOWN_SECONDS', 'STATUS_PORT', 'STATUS_REQUIRE_AUTH',
}
NONCE_TTL = 120
MAX_CLOCK_SKEW = 60
seen_nonces: dict[str, float] = {}


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat()


def read_env_all() -> dict[str, str]:
    out: dict[str, str] = {}
    try:
        for raw in ENV_FILE.read_text(encoding='utf-8').splitlines():
            if '=' not in raw or raw.lstrip().startswith('#'):
                continue
            key, value = raw.split('=', 1)
            out[key.strip()] = value.strip()
    except Exception:
        pass
    return out


def read_safe_env() -> dict[str, str]:
    env = read_env_all()
    return {k: env[k] for k in SAFE_ENV_KEYS if k in env}


def read_json(path: Path) -> dict:
    try:
        obj = json.loads(path.read_text(encoding='utf-8'))
        return obj if isinstance(obj, dict) else {}
    except Exception:
        return {}


def read_last_cycle() -> dict:
    try:
        lines = AUDIT_FILE.read_text(encoding='utf-8').splitlines()
    except Exception:
        return {}
    for raw in reversed(lines[-600:]):
        try:
            obj = json.loads(raw)
        except Exception:
            continue
        if obj.get('event') == 'cycle':
            return obj
    return {}


def service_active(name: str) -> str:
    try:
        p = subprocess.run(['systemctl', 'is-active', name], capture_output=True, text=True, timeout=2, check=False)
        return (p.stdout or p.stderr).strip() or 'unknown'
    except Exception:
        return 'unknown'


def clean_float(value):
    try:
        x = float(value)
        if x == float('inf'):
            return 'inf'
        if x != x:
            return None
        return x
    except Exception:
        return value


def read_recent_audit(limit: int = 5000) -> list[dict]:
    try:
        lines = AUDIT_FILE.read_text(encoding='utf-8').splitlines()[-limit:]
    except Exception:
        return []
    out = []
    for raw in lines:
        try:
            obj = json.loads(raw)
            if isinstance(obj, dict):
                out.append(obj)
        except Exception:
            continue
    return out


def performance_from_state(state: dict, last: dict, audit_rows: list[dict]) -> dict:
    closed = int(state.get('closed_trades') or 0)
    wins = int(state.get('wins') or 0)
    losses = int(state.get('losses') or 0)
    gross_profit = float(state.get('gross_profit') or 0.0)
    gross_loss = float(state.get('gross_loss') or 0.0)
    realized_from_audit = 0.0
    audit_closed = audit_wins = audit_losses = 0
    peak = None
    audit_max_dd = 0.0
    first_equity = None
    last_equity = None
    for row in audit_rows:
        if row.get('event') == 'paper_sell':
            try:
                pnl = float(row.get('pnl') or 0.0)
            except Exception:
                pnl = 0.0
            realized_from_audit += pnl
            audit_closed += 1
            if pnl > 0:
                audit_wins += 1
            elif pnl < 0:
                audit_losses += 1
        if row.get('event') == 'cycle':
            try:
                eq = float((row.get('performance') or {}).get('equity', row.get('equity')))
            except Exception:
                continue
            if not (eq == eq and abs(eq) != float('inf')):
                continue
            if first_equity is None:
                first_equity = eq
            last_equity = eq
            peak = eq if peak is None else max(peak, eq)
            if peak and peak > 0:
                audit_max_dd = max(audit_max_dd, (peak - eq) / peak)
    if closed == 0 and audit_closed:
        closed, wins, losses = audit_closed, audit_wins, audit_losses
        gross_profit = sum(max(float(r.get('pnl') or 0.0), 0.0) for r in audit_rows if r.get('event') == 'paper_sell')
        gross_loss = sum(max(-float(r.get('pnl') or 0.0), 0.0) for r in audit_rows if r.get('event') == 'paper_sell')
    equity = clean_float((last.get('performance') or {}).get('equity', last.get('equity', last_equity)))
    starting = float(state.get('starting_equity') or state.get('day_start_equity') or first_equity or 0.0)
    realized = state.get('realized_pnl')
    if realized is None and audit_closed:
        realized = realized_from_audit
    state_dd = clean_float(state.get('max_drawdown_pct'))
    max_dd = state_dd if isinstance(state_dd, (int, float)) else (audit_max_dd if audit_rows else None)
    return {
        'equity': equity,
        'realized_pnl': clean_float(realized),
        'return_pct': ((float(equity) - starting) / starting) if isinstance(equity, (int, float)) and starting > 0 else None,
        'orders_executed': state.get('trades'),
        'closed_trades': closed,
        'wins': wins,
        'losses': losses,
        'win_rate': wins / closed if closed else None,
        'max_drawdown_pct': max_dd,
        'profit_factor': (gross_profit / gross_loss) if gross_loss > 0 else (None if closed == 0 else 'inf'),
        'fees_paid': clean_float(state.get('fees_paid')),
        'daily_entries': state.get('daily_entries'),
        'metrics_version': state.get('metrics_version') or 'audit-derived-v1',
        'audit_window_events': len(audit_rows),
    }


def live_gate(perf: dict, backtest: dict, env: dict) -> dict:
    reasons = ['paper_only_build', 'no_live_order_adapter']
    if env.get('TRADING_MODE') != 'paper' or str(env.get('LIVE_TRADING_ENABLED')).lower() != 'false':
        reasons.append('fail_closed_config_mismatch')
    closed = int(perf.get('closed_trades') or 0)
    if closed < 30:
        reasons.append('forward_closed_trades_below_30')
    dd = perf.get('max_drawdown_pct')
    if isinstance(dd, (int, float)) and dd > 0.05:
        reasons.append('forward_drawdown_above_5pct')
    if not backtest.get('ok'):
        reasons.append('backtest_not_green')
    else:
        if int(backtest.get('closed_trades') or 0) < 20:
            reasons.append('backtest_closed_trades_below_20')
        if float(backtest.get('return_pct') or 0.0) <= 0:
            reasons.append('backtest_return_not_positive')
        if float(backtest.get('max_drawdown_pct') or 0.0) > 0.08:
            reasons.append('backtest_drawdown_above_8pct')
    return {
        'ready': False,
        'live_orders_possible': False,
        'reasons': reasons,
        'required_before_live': ['explicit_live_adapter_review', 'risk_gate', 'backtest_gate', 'forward_test_gate'],
    }


def payload() -> dict:
    env = read_safe_env()
    state = read_json(STATE_FILE)
    backtest = read_json(BACKTEST_FILE)
    last = read_last_cycle()
    audit_rows = read_recent_audit()
    perf = performance_from_state(state, last, audit_rows)
    agents = []
    for row in (last.get('agents') or last.get('votes') or []):
        if isinstance(row, dict):
            agents.append({
                'agent': row.get('agent'), 'vote': row.get('vote'), 'weight': row.get('weight'),
                'contribution': row.get('contribution'), 'reason': row.get('reason'), 'available': row.get('available'),
            })
    return {
        'ok': True,
        'version': 3,
        'server_time_utc': utc_now(),
        'services': {
            'trader': service_active('chobyar-trader'),
            'status': service_active('chobyar-status'),
            'backtest_timer': service_active('chobyar-backtest.timer'),
        },
        'mode': env.get('TRADING_MODE', 'unknown'),
        'live_trading_enabled': env.get('LIVE_TRADING_ENABLED', 'unknown'),
        'risk_profile': env.get('RISK_PROFILE', 'unknown'),
        'symbol': env.get('SYMBOL', 'unknown'),
        'risk': {
            'max_position_pct': env.get('MAX_POSITION_PCT'),
            'stop_loss_pct': env.get('STOP_LOSS_PCT'),
            'take_profit_pct': env.get('TAKE_PROFIT_PCT'),
            'max_daily_loss_pct': env.get('MAX_DAILY_LOSS_PCT'),
            'max_spread_pct': env.get('MAX_SPREAD_PCT'),
            'max_entries_per_day': env.get('MAX_ENTRIES_PER_DAY'),
            'cooldown_seconds': env.get('COOLDOWN_SECONDS'),
        },
        'market': {
            'local_mid': last.get('local_mid', last.get('mid')),
            'best_bid': last.get('best_bid'),
            'best_ask': last.get('best_ask'),
            'spread_pct': last.get('spread_pct'),
            'orderbook_imbalance': last.get('orderbook_imbalance'),
            'tape_buy_ratio': last.get('tape_buy_ratio'),
            'global_price': last.get('global_price'),
            'global_change_24h': last.get('global_change_24h'),
            'global_sources': last.get('global_sources'),
            'global_dispersion_pct': last.get('global_dispersion_pct'),
        },
        'decision': {
            'ts': last.get('ts'),
            'signal': last.get('signal'),
            'score': last.get('score'),
            'action': last.get('action'),
            'executed': last.get('executed'),
            'risk_reason': last.get('risk_reason', last.get('reason')),
            'agents': agents,
        },
        'paper': {
            'cash_usdt': state.get('cash_usdt'),
            'btc_qty': state.get('btc_qty'),
            'entry_price': state.get('entry_price'),
        },
        'performance': perf,
        'backtest': backtest,
        'live_gate': live_gate(perf, backtest, env),
        'security': {
            'status_auth': 'hmac-sha256',
            'secret_exposed': False,
            'secret_source': 'local_env_only',
            'tls': False,
            'note': 'Request secret is never transmitted. Status payload contains no secret; TLS is still required before any future live-trading control plane.',
        },
    }


def prune_nonces() -> None:
    cutoff = time.time() - NONCE_TTL
    for nonce, seen_at in list(seen_nonces.items()):
        if seen_at < cutoff:
            seen_nonces.pop(nonce, None)


def auth_ok(handler: BaseHTTPRequestHandler, path: str) -> bool:
    env = read_env_all()
    if env.get('STATUS_REQUIRE_AUTH', 'true').lower() != 'true':
        return False
    secret = env.get('STATUS_HMAC_SECRET', '')
    if len(secret) < 32:
        return False
    ts_raw = handler.headers.get('X-ChobYar-Timestamp', '')
    nonce = handler.headers.get('X-ChobYar-Nonce', '')
    signature = handler.headers.get('X-ChobYar-Signature', '')
    if not ts_raw or not nonce or not signature or len(nonce) < 16:
        return False
    try:
        ts = int(ts_raw)
    except Exception:
        return False
    if abs(int(time.time()) - ts) > MAX_CLOCK_SKEW:
        return False
    prune_nonces()
    if nonce in seen_nonces:
        return False
    message = f'{ts_raw}\n{nonce}\nGET\n{path}'.encode('utf-8')
    expected = hmac.new(secret.encode('utf-8'), message, hashlib.sha256).hexdigest()
    if not hmac.compare_digest(expected, signature.lower()):
        return False
    seen_nonces[nonce] = time.time()
    return True


class Handler(BaseHTTPRequestHandler):
    def send_json(self, code: int, obj: dict, response_secret: str | None = None) -> None:
        body = json.dumps(obj, ensure_ascii=False, separators=(',', ':')).encode('utf-8')
        self.send_response(code)
        self.send_header('Content-Type', 'application/json; charset=utf-8')
        self.send_header('Cache-Control', 'no-store')
        self.send_header('X-Content-Type-Options', 'nosniff')
        self.send_header('Referrer-Policy', 'no-referrer')
        self.send_header('Content-Security-Policy', "default-src 'none'")
        if response_secret:
            digest = hmac.new(response_secret.encode('utf-8'), body, hashlib.sha256).hexdigest()
            self.send_header('X-ChobYar-Response-Signature', digest)
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self) -> None:
        path = urlparse(self.path).path
        if path in ('/', '/health'):
            env = read_safe_env()
            self.send_json(200, {
                'ok': True,
                'service': service_active('chobyar-trader'),
                'mode': env.get('TRADING_MODE', 'unknown'),
                'live_trading_enabled': env.get('LIVE_TRADING_ENABLED', 'unknown'),
                'status_auth_required': True,
            })
            return
        if path != '/status':
            self.send_json(404, {'ok': False, 'error': 'not_found'})
            return
        if not auth_ok(self, path):
            self.send_json(401, {'ok': False, 'error': 'authentication_required'})
            return
        secret = read_env_all().get('STATUS_HMAC_SECRET', '')
        self.send_json(200, payload(), response_secret=secret)

    def log_message(self, fmt: str, *args) -> None:
        return


if __name__ == '__main__':
    env = read_env_all()
    port = int(env.get('STATUS_PORT', '8787'))
    ThreadingHTTPServer(('0.0.0.0', port), Handler).serve_forever()

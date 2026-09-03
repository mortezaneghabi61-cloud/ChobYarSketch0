from __future__ import annotations

import math

import status_server_v47 as v47

base = v47.base
v45 = v47.v45
_original_public_report = base.public_report_payload
_original_send_monitor_asset = v45.send_monitor_asset

EXPECTED_STOP = 0.015
EXPECTED_TAKE = 0.03
DETAIL_JS = v45.MONITOR_DIR / 'position_detail.js'
DETAIL_CSS = v45.MONITOR_DIR / 'position_detail.css'


def _positive_finite(value) -> float | None:
    try:
        number = float(value)
    except Exception:
        return None
    if not math.isfinite(number) or number <= 0:
        return None
    return number


def _approved_targets(entry: float, mark: float) -> dict:
    env = base.read_safe_env()
    try:
        stop_pct = float(env.get('STOP_LOSS_PCT', 'nan'))
        take_pct = float(env.get('TAKE_PROFIT_PCT', 'nan'))
    except Exception:
        stop_pct = take_pct = float('nan')

    if stop_pct != EXPECTED_STOP or take_pct != EXPECTED_TAKE:
        return {
            'targets_verified': False,
            'stop_loss_price': None,
            'take_profit_price': None,
            'distance_to_stop_pct': None,
            'distance_to_take_pct': None,
        }

    stop_price = entry * (1.0 - stop_pct)
    take_price = entry * (1.0 + take_pct)
    return {
        'targets_verified': True,
        'stop_loss_price': stop_price,
        'take_profit_price': take_price,
        'distance_to_stop_pct': (mark - stop_price) / mark,
        'distance_to_take_pct': (take_price - mark) / mark,
    }


def detailed_public_position() -> dict:
    position = dict(v47.public_position())
    if position.get('open') is not True:
        position.update({
            'mark_price': None,
            'unrealized_return_pct': None,
            'targets_verified': False,
            'stop_loss_price': None,
            'take_profit_price': None,
            'distance_to_stop_pct': None,
            'distance_to_take_pct': None,
        })
        return position

    entry = _positive_finite(position.get('entry_price'))
    last = base.read_last_cycle()
    mark = _positive_finite(last.get('local_mid', last.get('mid')))
    if entry is None or mark is None:
        position.update({
            'mark_price': mark,
            'unrealized_return_pct': None,
            'targets_verified': False,
            'stop_loss_price': None,
            'take_profit_price': None,
            'distance_to_stop_pct': None,
            'distance_to_take_pct': None,
        })
        return position

    position['mark_price'] = mark
    position['unrealized_return_pct'] = (mark - entry) / entry
    position.update(_approved_targets(entry, mark))
    return position


def public_report_payload() -> dict:
    report = _original_public_report()
    report['report_version'] = 4
    report['position'] = detailed_public_position()
    return report


def _read_optional(path):
    try:
        return path.read_bytes()
    except Exception:
        return b''


def send_monitor_asset(handler, asset, content_type: str) -> None:
    """Serve the proven monitor shell with small, local-only v4.8 appendices."""
    try:
        body = asset.read_bytes()
    except Exception:
        handler.send_json(503, {'ok': False, 'error': 'monitor_asset_unavailable'})
        return

    if asset.name == 'app.js':
        body += b'\n' + _read_optional(DETAIL_JS)
    elif asset.name == 'style.css':
        body += b'\n' + _read_optional(DETAIL_CSS)

    handler.send_response(200)
    handler.send_header('Content-Type', content_type)
    handler.send_header('Content-Length', str(len(body)))
    handler.send_header('Cache-Control', 'no-store')
    handler.send_header('X-Content-Type-Options', 'nosniff')
    handler.send_header('Referrer-Policy', 'no-referrer')
    handler.send_header('Content-Security-Policy', v45.CSP)
    handler.send_header('Permissions-Policy', 'camera=(), microphone=(), geolocation=(), payment=()')
    if asset.name == 'sw.js':
        handler.send_header('Service-Worker-Allowed', '/monitor/')
    handler.end_headers()
    handler.wfile.write(body)


base.public_report_payload = public_report_payload
v45.send_monitor_asset = send_monitor_asset


if __name__ == '__main__':
    env = base.read_auth_env()
    port = int(env.get('STATUS_PORT', '8787'))
    base.ThreadingHTTPServer(('0.0.0.0', port), base.Handler).serve_forever()

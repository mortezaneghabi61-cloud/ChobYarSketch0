from __future__ import annotations

from pathlib import Path

import status_server_v51 as v51

base = v51.base
v45 = v51.v45
V5_SHADOW_FILE = v51.V5_SHADOW_FILE
META_JS = v45.MONITOR_DIR / 'meta_monitor.js'
META_CSS = v45.MONITOR_DIR / 'meta_monitor.css'
_original_report = v51.public_report_payload
_original_send = v51.send_monitor_asset


def _safe_float(value):
    return v51._finite(value)


def _safe_text(value, limit: int = 120):
    return v51._clean_text(value, limit)


def _safe_flags(value, limit: int = 8):
    if not isinstance(value, list):
        return []
    return [_safe_text(item, 64) for item in value[:limit]]


def public_meta_projection() -> dict:
    raw = base.read_json(V5_SHADOW_FILE)
    meta = raw.get('meta_intelligence') if isinstance(raw.get('meta_intelligence'), dict) else {}
    consensus = raw.get('shadow_consensus') if isinstance(raw.get('shadow_consensus'), dict) else {}
    integrity = meta.get('data_integrity') if isinstance(meta.get('data_integrity'), dict) else {}
    transition = meta.get('regime_transition') if isinstance(meta.get('regime_transition'), dict) else {}
    execution = meta.get('execution_stress') if isinstance(meta.get('execution_stress'), dict) else {}
    uncertainty = meta.get('epistemic_uncertainty') if isinstance(meta.get('epistemic_uncertainty'), dict) else {}
    fragility = meta.get('decision_fragility') if isinstance(meta.get('decision_fragility'), dict) else {}
    return {
        'mode': 'shadow_observation_only',
        'execution_authority': False,
        'pre_meta_action': _safe_text(consensus.get('pre_meta_action'), 12),
        'final_action': _safe_text(consensus.get('action'), 12),
        'meta_hold': bool(consensus.get('meta_hold')),
        'meta_hold_reasons': _safe_flags(consensus.get('meta_hold_reasons'), 8),
        'data_integrity': {
            'score': _safe_float(integrity.get('score')),
            'healthy': bool(integrity.get('healthy')),
            'flags': _safe_flags(integrity.get('flags'), 8),
            'hourly_candles': int(integrity.get('hourly_candles') or 0),
            'large_gap_count': int(integrity.get('large_gap_count') or 0),
            'latest_candle_age_seconds': _safe_float(integrity.get('latest_candle_age_seconds')),
            'breadth_symbols': int(integrity.get('breadth_symbols') or 0),
        },
        'regime_transition': {
            'score': _safe_float(transition.get('score')),
            'flags': _safe_flags(transition.get('flags'), 8),
        },
        'execution_stress': {
            'score': _safe_float(execution.get('score')),
            'flags': _safe_flags(execution.get('flags'), 8),
            'model': _safe_text(execution.get('model'), 64),
        },
        'uncertainty': {
            'score': _safe_float(uncertainty.get('score')),
            'directional_coverage': _safe_float(uncertainty.get('directional_coverage')),
            'directional_margin': _safe_float(uncertainty.get('directional_margin')),
        },
        'fragility': {
            'fragile': bool(fragility.get('fragile')),
            'flip_count': int(fragility.get('flip_count') or 0),
            'trials': int(fragility.get('trials') or 0),
            'actions': [_safe_text(x, 12) for x in (fragility.get('actions') or [])[:8]],
        },
    }


def public_report_payload() -> dict:
    report = _original_report()
    report['report_version'] = 6
    report['v5_meta'] = public_meta_projection()
    return report


def _read_optional(path: Path) -> bytes:
    try:
        return path.read_bytes()
    except Exception:
        return b''


def send_monitor_asset(handler, asset, content_type: str) -> None:
    if asset.name not in {'app.js', 'style.css'}:
        return _original_send(handler, asset, content_type)
    try:
        body = asset.read_bytes()
    except Exception:
        handler.send_json(503, {'ok': False, 'error': 'monitor_asset_unavailable'})
        return
    if asset.name == 'app.js':
        body += b'\n' + v51._read_optional(v51.v48.DETAIL_JS) + b'\n' + v51._read_optional(v51.V5_JS) + b'\n' + _read_optional(META_JS)
    else:
        body += b'\n' + v51._read_optional(v51.v48.DETAIL_CSS) + b'\n' + v51._read_optional(v51.V5_CSS) + b'\n' + _read_optional(META_CSS)
    handler.send_response(200)
    handler.send_header('Content-Type', content_type)
    handler.send_header('Content-Length', str(len(body)))
    handler.send_header('Cache-Control', 'no-store')
    handler.send_header('X-Content-Type-Options', 'nosniff')
    handler.send_header('Referrer-Policy', 'no-referrer')
    handler.send_header('Content-Security-Policy', v45.CSP)
    handler.send_header('Permissions-Policy', 'camera=(), microphone=(), geolocation=(), payment=()')
    handler.end_headers()
    handler.wfile.write(body)


base.public_report_payload = public_report_payload
v45.send_monitor_asset = send_monitor_asset


if __name__ == '__main__':
    env = base.read_auth_env()
    port = int(env.get('STATUS_PORT', '8787'))
    base.ThreadingHTTPServer(('0.0.0.0', port), base.Handler).serve_forever()

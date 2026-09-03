from __future__ import annotations

import math
from pathlib import Path

import status_server_v481 as v481

v48 = v481.v48
base = v48.base
v45 = v481.v45
_original_public_report = base.public_report_payload

V5_SHADOW_FILE = base.APP_DIR / 'state' / 'v5_shadow_latest.json'
V5_SCORECARD_FILE = base.APP_DIR / 'state' / 'v5_specialist_scorecard.json'
V5_JS = v45.MONITOR_DIR / 'specialist_monitor.js'
V5_CSS = v45.MONITOR_DIR / 'specialist_monitor.css'


def _finite(value):
    try:
        number = float(value)
    except Exception:
        return None
    return number if math.isfinite(number) else None


def _clean_text(value, limit: int = 180) -> str:
    return str(value or '')[:limit]


def public_v5_shadow() -> dict:
    raw = base.read_json(V5_SHADOW_FILE)
    specialists = []
    for item in raw.get('specialists') or []:
        if not isinstance(item, dict):
            continue
        try:
            vote = int(item.get('vote'))
        except Exception:
            vote = 0
        if vote not in (-1, 0, 1):
            vote = 0
        specialists.append({
            'agent': _clean_text(item.get('agent'), 80),
            'vote': vote,
            'confidence': _finite(item.get('confidence')),
            'available': bool(item.get('available')),
            'veto': bool(item.get('veto')),
            'reason': _clean_text(item.get('reason')),
        })
    regime = raw.get('regime') if isinstance(raw.get('regime'), dict) else {}
    consensus = raw.get('shadow_consensus') if isinstance(raw.get('shadow_consensus'), dict) else {}
    source_health = raw.get('source_health') if isinstance(raw.get('source_health'), dict) else {}
    return {
        'mode': 'shadow_observation_only',
        'execution_authority': False,
        'automatic_promotion_enabled': False,
        'automatic_reweighting_enabled': False,
        'foreign_execution_enabled': False,
        'geo_bypass_supported': False,
        'generated_at_utc': _clean_text(raw.get('generated_at_utc'), 64),
        'symbol': _clean_text(raw.get('symbol'), 24),
        'regime': {
            'label': _clean_text(regime.get('label'), 32),
            'confidence': _finite(regime.get('confidence')),
        },
        'shadow_consensus': {
            'action': _clean_text(consensus.get('action'), 12),
            'score': _finite(consensus.get('score')),
            'available_directional_specialists': int(consensus.get('available_directional_specialists') or 0),
            'risk_veto': bool(consensus.get('risk_veto')),
        },
        'specialists': specialists[:5],
        'source_health': {
            'wallex_hourly_candles': int(source_health.get('wallex_hourly_candles') or 0),
            'okx_breadth_symbols': [_clean_text(x, 24) for x in (source_health.get('okx_breadth_symbols') or [])[:6]],
            'okx_funding_samples': int(source_health.get('okx_funding_samples') or 0),
            'okx_open_interest_available': bool(source_health.get('okx_open_interest_available')),
            'errors': [_clean_text(x, 80) for x in (source_health.get('errors') or [])[:8]],
        },
    }


def _horizon_summary(value) -> dict:
    value = value if isinstance(value, dict) else {}
    return {
        'samples': int(value.get('samples') or 0),
        'sufficient': bool(value.get('sufficient')),
        'hit_rate': _finite(value.get('hit_rate')),
        'average_signed_return': _finite(value.get('average_signed_return')),
    }


def public_v5_scorecard() -> dict:
    raw = base.read_json(V5_SCORECARD_FILE)
    method = raw.get('method') if isinstance(raw.get('method'), dict) else {}
    specialists = {}
    for name, value in (raw.get('specialists') or {}).items():
        if not isinstance(value, dict):
            continue
        regimes = value.get('regimes') if isinstance(value.get('regimes'), dict) else {}
        all_regime = regimes.get('ALL') if isinstance(regimes.get('ALL'), dict) else {}
        horizons = all_regime.get('horizons') if isinstance(all_regime.get('horizons'), dict) else {}
        specialists[_clean_text(name, 80)] = {
            'eligible_regimes': [_clean_text(x, 32) for x in (value.get('eligible_regimes') or [])[:8]],
            'eligible_for_manual_promotion_review': bool(value.get('eligible_for_manual_promotion_review')),
            'horizons': {key: _horizon_summary(horizons.get(key)) for key in ('1h', '4h', '12h')},
        }
    return {
        'mode': 'shadow_observation_only',
        'execution_authority': False,
        'automatic_promotion_enabled': False,
        'automatic_reweighting_enabled': False,
        'generated_at_utc': _clean_text(raw.get('generated_at_utc'), 64),
        'sampled_rows': int(raw.get('sampled_rows') or 0),
        'minimum_directional_samples_per_regime': int(method.get('minimum_directional_samples_per_regime') or 30),
        'reviewable_specialists': [_clean_text(x, 80) for x in (raw.get('reviewable_specialists') or [])[:10]],
        'ready_for_manual_promotion_review': bool(raw.get('ready_for_manual_promotion_review')),
        'specialists': specialists,
    }


def public_report_payload() -> dict:
    report = _original_public_report()
    report['report_version'] = 5
    report['v5_shadow'] = public_v5_shadow()
    report['v5_specialist_scorecard'] = public_v5_scorecard()
    report.setdefault('services', {})['v5_shadow_timer'] = base.service_active('chobyar-v5-shadow.timer')
    report.setdefault('services', {})['v5_scorecard_timer'] = base.service_active('chobyar-v5-scorecard.timer')
    return report


def _read_optional(path: Path) -> bytes:
    try:
        return path.read_bytes()
    except Exception:
        return b''


def send_monitor_asset(handler, asset, content_type: str) -> None:
    try:
        body = asset.read_bytes()
    except Exception:
        handler.send_json(503, {'ok': False, 'error': 'monitor_asset_unavailable'})
        return
    if asset.name == 'app.js':
        body += b'\n' + _read_optional(v48.DETAIL_JS) + b'\n' + _read_optional(V5_JS)
    elif asset.name == 'style.css':
        body += b'\n' + _read_optional(v48.DETAIL_CSS) + b'\n' + _read_optional(V5_CSS)
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

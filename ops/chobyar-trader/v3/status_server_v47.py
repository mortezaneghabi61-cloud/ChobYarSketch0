from __future__ import annotations

import math

import status_server_v45 as v45

base = v45.base
_original_public_report = base.public_report_payload


def _positive_finite(value) -> float | None:
    try:
        number = float(value)
    except Exception:
        return None
    if not math.isfinite(number) or number <= 0:
        return None
    return number


def public_position() -> dict:
    """Project only the minimum safe Paper-position fields needed by the monitor."""
    state = base.read_json(base.STATE_FILE)
    last = base.read_last_cycle()

    qty = _positive_finite(state.get('btc_qty'))
    entry = _positive_finite(state.get('entry_price'))
    is_open = qty is not None and entry is not None

    if not is_open:
        return {
            'open': False,
            'side': 'FLAT',
            'entry_price': None,
            'unrealized_pnl': 0.0,
        }

    mark = _positive_finite(last.get('local_mid', last.get('mid')))
    unrealized = None if mark is None else (mark - entry) * qty
    if unrealized is not None and not math.isfinite(unrealized):
        unrealized = None

    return {
        'open': True,
        'side': 'LONG',
        'entry_price': entry,
        'unrealized_pnl': unrealized,
    }


def public_report_payload() -> dict:
    report = _original_public_report()
    report['report_version'] = 3
    report['position'] = public_position()
    return report


# Existing Handler resolves this global function at request time. Replace only
# the sanitized public projection; /health, HMAC /status, and monitor routing
# remain exactly as provided by the proven v4.5 stack.
base.public_report_payload = public_report_payload


if __name__ == '__main__':
    env = base.read_auth_env()
    port = int(env.get('STATUS_PORT', '8787'))
    base.ThreadingHTTPServer(('0.0.0.0', port), base.Handler).serve_forever()

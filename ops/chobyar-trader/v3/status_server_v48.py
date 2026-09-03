from __future__ import annotations

import math

import status_server_v47 as v47

base = v47.base
_original_public_report = base.public_report_payload

EXPECTED_STOP = 0.015
EXPECTED_TAKE = 0.03


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


# Extend only the sanitized public projection. Existing /health, HMAC /status,
# monitor routing, and HTTPS reverse proxy remain unchanged.
base.public_report_payload = public_report_payload


if __name__ == '__main__':
    env = base.read_auth_env()
    port = int(env.get('STATUS_PORT', '8787'))
    base.ThreadingHTTPServer(('0.0.0.0', port), base.Handler).serve_forever()

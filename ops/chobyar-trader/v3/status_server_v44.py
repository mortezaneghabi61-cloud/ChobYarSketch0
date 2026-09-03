from __future__ import annotations

from pathlib import Path

import status_server as base

SCORECARD_FILE = Path('/opt/chobyar-trader/state/agent_scorecard.json')
_original_public_report = base.public_report_payload


def public_scorecard(scorecard: dict) -> dict:
    agents_out: dict[str, dict] = {}
    for name, raw in (scorecard.get('agents') or {}).items():
        if not isinstance(raw, dict):
            continue
        horizons_out: dict[str, dict] = {}
        for horizon, metrics in (raw.get('horizons') or {}).items():
            if not isinstance(metrics, dict):
                continue
            horizons_out[str(horizon)] = {
                'samples': metrics.get('samples'),
                'sufficient': metrics.get('sufficient'),
                'hit_rate': metrics.get('hit_rate'),
                'average_signed_return': metrics.get('average_signed_return'),
                'median_signed_return': metrics.get('median_signed_return'),
            }
        agents_out[str(name)] = {
            'eligible_for_weight_review': bool(raw.get('eligible_for_weight_review')),
            'horizons': horizons_out,
        }
    return {
        'ok': bool(scorecard.get('ok')),
        'generated_at_utc': scorecard.get('generated_at_utc'),
        'mode': scorecard.get('mode'),
        'sampled_rows': scorecard.get('sampled_rows'),
        'minimum_directional_samples': (scorecard.get('method') or {}).get('minimum_directional_samples'),
        'reviewable_agents': scorecard.get('reviewable_agents') or [],
        'ready_for_manual_weight_review': bool(scorecard.get('ready_for_manual_weight_review')),
        'weights_changed': False,
        'automatic_reweighting_enabled': False,
        'agents': agents_out,
    }


def public_report_payload() -> dict:
    report = _original_public_report()
    report['report_version'] = 2
    services = dict(report.get('services') or {})
    services['agent_scorecard_timer'] = base.service_active('chobyar-agent-scorecard.timer')
    report['services'] = services
    report['agent_scorecard'] = public_scorecard(base.read_json(SCORECARD_FILE))
    return report


# Existing Handler resolves the global function at request time; replacing it
# preserves the original HMAC /status and all existing endpoint behavior.
base.public_report_payload = public_report_payload


if __name__ == '__main__':
    env = base.read_auth_env()
    port = int(env.get('STATUS_PORT', '8787'))
    base.ThreadingHTTPServer(('0.0.0.0', port), base.Handler).serve_forever()

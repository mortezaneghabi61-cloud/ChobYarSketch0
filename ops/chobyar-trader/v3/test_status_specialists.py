from __future__ import annotations

import json
import unittest

import status_server_v51 as v51


class SpecialistProjectionTests(unittest.TestCase):
    def setUp(self):
        self.original_read_json = v51.base.read_json

    def tearDown(self):
        v51.base.read_json = self.original_read_json

    def test_shadow_projection_is_sanitized_and_execution_disabled(self):
        raw = {
            'generated_at_utc': '2026-09-03T17:00:00+00:00',
            'symbol': 'BTCUSDT',
            'execution_authority': True,
            'api_key': 'SHOULD_NOT_LEAK',
            'regime': {'label': 'TREND_UP', 'confidence': .81, 'atr14_pct': .01},
            'shadow_consensus': {'action': 'BUY', 'score': 1.5, 'available_directional_specialists': 4, 'risk_veto': False},
            'specialists': [
                {'agent': 'regime_structure', 'vote': 1, 'confidence': .8, 'available': True, 'veto': False, 'reason': 'trend', 'features': {'secret': 'x'}},
                {'agent': 'adversarial_risk', 'vote': 0, 'confidence': .7, 'available': True, 'veto': False, 'reason': 'clear'},
            ],
            'source_health': {'wallex_hourly_candles': 168, 'okx_breadth_symbols': ['BTC-USDT','ETH-USDT','SOL-USDT'], 'okx_funding_samples': 30, 'okx_open_interest_available': True, 'errors': []},
        }
        v51.base.read_json = lambda path: raw
        out = v51.public_v5_shadow()
        self.assertEqual(out['mode'], 'shadow_observation_only')
        self.assertFalse(out['execution_authority'])
        self.assertFalse(out['automatic_promotion_enabled'])
        self.assertFalse(out['foreign_execution_enabled'])
        self.assertFalse(out['geo_bypass_supported'])
        self.assertEqual(out['regime'], {'label': 'TREND_UP', 'confidence': .81})
        self.assertEqual(len(out['specialists']), 2)
        encoded = json.dumps(out)
        self.assertNotIn('SHOULD_NOT_LEAK', encoded)
        self.assertNotIn('api_key', encoded.lower())
        self.assertNotIn('features', encoded)

    def test_scorecard_projects_only_safe_evidence(self):
        raw = {
            'sampled_rows': 41,
            'ready_for_manual_promotion_review': True,
            'reviewable_specialists': ['regime_structure'],
            'method': {'minimum_directional_samples_per_regime': 30},
            'specialists': {
                'regime_structure': {
                    'eligible_regimes': ['TREND_UP'],
                    'eligible_for_manual_promotion_review': True,
                    'regimes': {'ALL': {'horizons': {
                        '1h': {'samples': 20, 'sufficient': False, 'hit_rate': .6, 'average_signed_return': .001, 'median_signed_return': .001},
                        '4h': {'samples': 31, 'sufficient': True, 'hit_rate': .58, 'average_signed_return': .003},
                        '12h': {'samples': 10, 'sufficient': False, 'hit_rate': .5, 'average_signed_return': 0},
                    }}},
                }
            },
        }
        v51.base.read_json = lambda path: raw
        out = v51.public_v5_scorecard()
        self.assertFalse(out['execution_authority'])
        self.assertFalse(out['automatic_promotion_enabled'])
        self.assertEqual(out['sampled_rows'], 41)
        self.assertEqual(out['minimum_directional_samples_per_regime'], 30)
        self.assertEqual(out['reviewable_specialists'], ['regime_structure'])
        four = out['specialists']['regime_structure']['horizons']['4h']
        self.assertEqual(four['samples'], 31)
        self.assertTrue(four['sufficient'])
        self.assertAlmostEqual(four['hit_rate'], .58)


if __name__ == '__main__':
    unittest.main()

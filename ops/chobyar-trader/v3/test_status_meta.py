from __future__ import annotations

import json
import unittest

import status_server_v53 as v53


class MetaProjectionTests(unittest.TestCase):
    def setUp(self):
        self.original_read_json = v53.base.read_json

    def tearDown(self):
        v53.base.read_json = self.original_read_json

    def test_projection_is_sanitized_and_read_only(self):
        raw = {
            'shadow_consensus': {
                'pre_meta_action': 'BUY',
                'action': 'WAIT',
                'meta_hold': True,
                'meta_hold_reasons': ['decision_fragility', 'execution_stress'],
                'secret': 'DO_NOT_LEAK',
            },
            'meta_intelligence': {
                'execution_authority': True,
                'api_key': 'SHOULD_NOT_LEAK',
                'data_integrity': {
                    'score': .7,
                    'healthy': True,
                    'flags': ['cross_market_breadth_incomplete'],
                    'hourly_candles': 168,
                    'large_gap_count': 0,
                    'latest_candle_age_seconds': 700,
                    'breadth_symbols': 2,
                },
                'regime_transition': {'score': .2, 'flags': ['volatility_rising'], 'ma_separation_now': .01},
                'execution_stress': {'score': .81, 'flags': ['spread_stress'], 'model': 'read_only_execution_stress_proxy', 'spread_component': .9},
                'epistemic_uncertainty': {'score': .44, 'directional_coverage': .75, 'directional_margin': .6, 'positive_weight': 1.0},
                'decision_fragility': {'fragile': True, 'flip_count': 2, 'trials': 4, 'actions': ['WAIT','BUY','WAIT','BUY']},
            },
        }
        v53.base.read_json = lambda path: raw
        out = v53.public_meta_projection()
        self.assertFalse(out['execution_authority'])
        self.assertEqual(out['pre_meta_action'], 'BUY')
        self.assertEqual(out['final_action'], 'WAIT')
        self.assertTrue(out['meta_hold'])
        self.assertEqual(out['meta_hold_reasons'], ['decision_fragility', 'execution_stress'])
        self.assertAlmostEqual(out['data_integrity']['score'], .7)
        self.assertAlmostEqual(out['execution_stress']['score'], .81)
        self.assertTrue(out['fragility']['fragile'])
        encoded = json.dumps(out).lower()
        self.assertNotIn('api_key', encoded)
        self.assertNotIn('should_not_leak', encoded)
        self.assertNotIn('secret', encoded)
        self.assertNotIn('spread_component', encoded)
        self.assertNotIn('positive_weight', encoded)

    def test_missing_meta_fails_closed_to_neutral_projection(self):
        v53.base.read_json = lambda path: {}
        out = v53.public_meta_projection()
        self.assertFalse(out['execution_authority'])
        self.assertFalse(out['meta_hold'])
        self.assertEqual(out['meta_hold_reasons'], [])
        self.assertIsNone(out['data_integrity']['score'])
        self.assertIsNone(out['uncertainty']['score'])
        self.assertFalse(out['fragility']['fragile'])


if __name__ == '__main__':
    unittest.main()

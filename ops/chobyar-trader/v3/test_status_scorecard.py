from __future__ import annotations

import unittest

from status_server_v44 import public_scorecard


class StatusScorecardTests(unittest.TestCase):
    def test_public_projection_keeps_metrics_but_never_enables_reweighting(self) -> None:
        raw = {
            'ok': True,
            'generated_at_utc': '2026-01-01T00:00:00+00:00',
            'mode': 'paper_observation_only',
            'sampled_rows': 44,
            'method': {'minimum_directional_samples': 30},
            'reviewable_agents': ['momentum'],
            'ready_for_manual_weight_review': True,
            'weights_changed': True,
            'automatic_reweighting_enabled': True,
            'agents': {
                'momentum': {
                    'eligible_for_weight_review': True,
                    'horizons': {
                        '4h': {
                            'samples': 31,
                            'sufficient': True,
                            'hit_rate': 0.61,
                            'average_signed_return': 0.002,
                            'median_signed_return': 0.001,
                            'unexpected_private_field': 'drop-me',
                        }
                    },
                    'unexpected_private_field': 'drop-me',
                }
            },
            'unexpected_private_field': 'drop-me',
        }
        out = public_scorecard(raw)
        self.assertTrue(out['ready_for_manual_weight_review'])
        self.assertFalse(out['weights_changed'])
        self.assertFalse(out['automatic_reweighting_enabled'])
        self.assertNotIn('unexpected_private_field', out)
        self.assertNotIn('unexpected_private_field', out['agents']['momentum'])
        self.assertEqual(out['agents']['momentum']['horizons']['4h']['samples'], 31)


if __name__ == '__main__':
    unittest.main()

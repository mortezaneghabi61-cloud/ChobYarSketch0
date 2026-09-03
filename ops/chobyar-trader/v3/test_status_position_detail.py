from __future__ import annotations

import unittest

import status_server_v48 as detail


class PositionDetailTests(unittest.TestCase):
    def setUp(self) -> None:
        self.read_json = detail.base.read_json
        self.read_last_cycle = detail.base.read_last_cycle
        self.read_safe_env = detail.base.read_safe_env

    def tearDown(self) -> None:
        detail.base.read_json = self.read_json
        detail.base.read_last_cycle = self.read_last_cycle
        detail.base.read_safe_env = self.read_safe_env

    def patch(self, state: dict, last: dict, env: dict | None = None) -> None:
        detail.base.read_json = lambda _path: dict(state)
        detail.base.read_last_cycle = lambda: dict(last)
        detail.base.read_safe_env = lambda: dict(env or {
            'STOP_LOSS_PCT': '0.015',
            'TAKE_PROFIT_PCT': '0.03',
        })

    def test_open_long_has_mark_return_and_verified_targets_without_size(self) -> None:
        self.patch({'btc_qty': 0.001, 'entry_price': 100.0}, {'local_mid': 101.0})
        out = detail.detailed_public_position()
        self.assertTrue(out['open'])
        self.assertEqual(out['side'], 'LONG')
        self.assertEqual(out['entry_price'], 100.0)
        self.assertEqual(out['mark_price'], 101.0)
        self.assertAlmostEqual(out['unrealized_pnl'], 0.001)
        self.assertAlmostEqual(out['unrealized_return_pct'], 0.01)
        self.assertTrue(out['targets_verified'])
        self.assertAlmostEqual(out['stop_loss_price'], 98.5)
        self.assertAlmostEqual(out['take_profit_price'], 103.0)
        self.assertAlmostEqual(out['distance_to_stop_pct'], (101.0 - 98.5) / 101.0)
        self.assertAlmostEqual(out['distance_to_take_pct'], (103.0 - 101.0) / 101.0)
        for forbidden in ('btc_qty', 'qty', 'size', 'cash_usdt', 'entry_fee', 'fees_paid'):
            self.assertNotIn(forbidden, out)

    def test_risk_mismatch_hides_targets_fail_closed(self) -> None:
        self.patch(
            {'btc_qty': 0.001, 'entry_price': 100.0},
            {'local_mid': 101.0},
            {'STOP_LOSS_PCT': '0.02', 'TAKE_PROFIT_PCT': '0.03'},
        )
        out = detail.detailed_public_position()
        self.assertTrue(out['open'])
        self.assertFalse(out['targets_verified'])
        self.assertIsNone(out['stop_loss_price'])
        self.assertIsNone(out['take_profit_price'])
        self.assertIsNone(out['distance_to_stop_pct'])
        self.assertIsNone(out['distance_to_take_pct'])

    def test_flat_position_has_no_market_or_targets(self) -> None:
        self.patch({'btc_qty': 0.0, 'entry_price': None}, {'local_mid': 101.0})
        out = detail.detailed_public_position()
        self.assertFalse(out['open'])
        self.assertEqual(out['side'], 'FLAT')
        self.assertIsNone(out['mark_price'])
        self.assertIsNone(out['stop_loss_price'])
        self.assertIsNone(out['take_profit_price'])

    def test_missing_fresh_mark_does_not_fabricate_targets(self) -> None:
        self.patch({'btc_qty': 0.001, 'entry_price': 100.0}, {})
        out = detail.detailed_public_position()
        self.assertTrue(out['open'])
        self.assertIsNone(out['mark_price'])
        self.assertIsNone(out['unrealized_return_pct'])
        self.assertFalse(out['targets_verified'])
        self.assertIsNone(out['stop_loss_price'])
        self.assertIsNone(out['take_profit_price'])


if __name__ == '__main__':
    unittest.main()

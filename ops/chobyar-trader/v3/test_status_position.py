from __future__ import annotations

import unittest
from unittest.mock import patch

import status_server_v47 as position_status


class PositionProjectionTests(unittest.TestCase):
    def test_open_long_position_is_projected_without_size(self) -> None:
        with patch.object(position_status.base, 'read_json', return_value={
            'btc_qty': 0.001,
            'entry_price': 100.0,
            'cash_usdt': 9.0,
        }), patch.object(position_status.base, 'read_last_cycle', return_value={
            'local_mid': 105.0,
            'best_bid': 104.9,
            'best_ask': 105.1,
        }):
            out = position_status.public_position()

        self.assertEqual(set(out), {'open', 'side', 'entry_price', 'unrealized_pnl'})
        self.assertTrue(out['open'])
        self.assertEqual(out['side'], 'LONG')
        self.assertEqual(out['entry_price'], 100.0)
        self.assertAlmostEqual(out['unrealized_pnl'], 0.005)
        self.assertNotIn('btc_qty', out)
        self.assertNotIn('cash_usdt', out)

    def test_flat_position_is_explicit_and_does_not_infer_from_equity(self) -> None:
        with patch.object(position_status.base, 'read_json', return_value={
            'btc_qty': 0.0,
            'entry_price': None,
        }), patch.object(position_status.base, 'read_last_cycle', return_value={
            'local_mid': 50000.0,
            'performance': {'equity': 9.99},
        }):
            out = position_status.public_position()

        self.assertEqual(out, {
            'open': False,
            'side': 'FLAT',
            'entry_price': None,
            'unrealized_pnl': 0.0,
        })

    def test_open_position_without_fresh_mark_does_not_fabricate_pnl(self) -> None:
        with patch.object(position_status.base, 'read_json', return_value={
            'btc_qty': 0.001,
            'entry_price': 100.0,
        }), patch.object(position_status.base, 'read_last_cycle', return_value={}):
            out = position_status.public_position()

        self.assertTrue(out['open'])
        self.assertEqual(out['side'], 'LONG')
        self.assertEqual(out['entry_price'], 100.0)
        self.assertIsNone(out['unrealized_pnl'])

    def test_invalid_state_fails_closed_to_flat(self) -> None:
        for state in (
            {'btc_qty': -1, 'entry_price': 100},
            {'btc_qty': 1, 'entry_price': -100},
            {'btc_qty': 'nan', 'entry_price': 100},
        ):
            with self.subTest(state=state), \
                 patch.object(position_status.base, 'read_json', return_value=state), \
                 patch.object(position_status.base, 'read_last_cycle', return_value={'local_mid': 105}):
                out = position_status.public_position()
                self.assertFalse(out['open'])
                self.assertEqual(out['side'], 'FLAT')


if __name__ == '__main__':
    unittest.main()

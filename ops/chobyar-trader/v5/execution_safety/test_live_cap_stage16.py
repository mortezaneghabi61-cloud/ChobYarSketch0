from __future__ import annotations

import unittest

from live_cap_stage16 import LiveCapStage16Request, evaluate_live_cap_stage16


class LiveCapStage16Tests(unittest.TestCase):
    def good(self, **overrides):
        data = dict(
            symbol="BTCUSDT",
            quote_asset="USDT",
            requested_max_order_quote="10",
            stage15_allowed=True,
            trade_permission_enabled=False,
            withdrawal_permission_enabled=False,
            live_trading_enabled=False,
            exact_ip_allowlist=True,
            leverage_enabled=False,
            margin_enabled=False,
            futures_enabled=False,
            otc_enabled=False,
        )
        data.update(overrides)
        return LiveCapStage16Request(**data)

    def test_exact_10_usdt_is_approved_but_still_non_executing(self):
        d = evaluate_live_cap_stage16(self.good())
        self.assertTrue(d.allowed)
        self.assertEqual(str(d.approved_max_order_usdt), "10")
        self.assertTrue(d.ready_for_manual_trade_permission_change)
        self.assertFalse(d.ready_to_change_live_env)
        self.assertFalse(d.live_ready)
        self.assertFalse(d.execution_authority)

    def test_any_other_cap_is_rejected(self):
        for value in ("9.99", "10.01", "100", "0", "-1", "NaN", "Infinity", ""):
            with self.subTest(value=value):
                self.assertFalse(evaluate_live_cap_stage16(self.good(requested_max_order_quote=value)).allowed)

    def test_stage15_required(self):
        self.assertFalse(evaluate_live_cap_stage16(self.good(stage15_allowed=False)).allowed)

    def test_trade_and_live_must_remain_off(self):
        self.assertFalse(evaluate_live_cap_stage16(self.good(trade_permission_enabled=True)).allowed)
        self.assertFalse(evaluate_live_cap_stage16(self.good(live_trading_enabled=bool(1))).allowed)

    def test_withdrawal_and_nonspot_authority_rejected(self):
        self.assertFalse(evaluate_live_cap_stage16(self.good(withdrawal_permission_enabled=True)).allowed)
        for field in ("leverage_enabled", "margin_enabled", "futures_enabled", "otc_enabled"):
            with self.subTest(field=field):
                self.assertFalse(evaluate_live_cap_stage16(self.good(**{field: True})).allowed)

    def test_exact_ip_and_btcusdt_usdt_required(self):
        self.assertFalse(evaluate_live_cap_stage16(self.good(exact_ip_allowlist=False)).allowed)
        self.assertFalse(evaluate_live_cap_stage16(self.good(symbol="ETHUSDT")).allowed)
        self.assertFalse(evaluate_live_cap_stage16(self.good(quote_asset="TMN")).allowed)


if __name__ == "__main__":
    unittest.main()

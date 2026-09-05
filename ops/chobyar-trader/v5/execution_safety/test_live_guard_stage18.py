from __future__ import annotations

import unittest

from live_guard_stage18 import LiveGuardStage18Request, evaluate_live_guard_stage18


class LiveGuardStage18Tests(unittest.TestCase):
    def good(self, **overrides):
        data = dict(
            symbol="BTCUSDT",
            quote_asset="USDT",
            order_notional_quote="10",
            stage17_preflight_passed=True,
            read_permission_enabled=True,
            trade_permission_enabled=True,
            withdrawal_permission_enabled=False,
            exact_ip_allowlist=True,
            leverage_enabled=False,
            margin_enabled=False,
            futures_enabled=False,
            otc_enabled=False,
            max_position_pct="0.25",
            stop_loss_pct="0.015",
            take_profit_pct="0.03",
            max_daily_loss_pct="0.03",
        )
        data.update(overrides)
        return LiveGuardStage18Request(**data)

    def test_approved_boundary_passes_without_execution_authority(self):
        d = evaluate_live_guard_stage18(self.good())
        self.assertTrue(d.allowed)
        self.assertTrue(d.pre_submit_guard_passed)
        self.assertTrue(d.ready_for_separate_manual_live_activation_stage)
        self.assertFalse(d.live_ready)
        self.assertFalse(d.execution_authority)
        self.assertEqual(str(d.approved_max_order_usdt), "10")

    def test_stage17_is_required(self):
        self.assertFalse(evaluate_live_guard_stage18(self.good(stage17_preflight_passed=False)).allowed)

    def test_only_btcusdt_usdt(self):
        self.assertFalse(evaluate_live_guard_stage18(self.good(symbol="ETHUSDT")).allowed)
        self.assertFalse(evaluate_live_guard_stage18(self.good(quote_asset="TMN")).allowed)

    def test_read_and_trade_permissions_required(self):
        self.assertFalse(evaluate_live_guard_stage18(self.good(read_permission_enabled=False)).allowed)
        self.assertFalse(evaluate_live_guard_stage18(self.good(trade_permission_enabled=False)).allowed)

    def test_withdrawal_and_nonspot_authority_are_blocked(self):
        self.assertFalse(evaluate_live_guard_stage18(self.good(withdrawal_permission_enabled=True)).allowed)
        for field in ("leverage_enabled", "margin_enabled", "futures_enabled", "otc_enabled"):
            with self.subTest(field=field):
                self.assertFalse(evaluate_live_guard_stage18(self.good(**{field: True})).allowed)

    def test_exact_ip_allowlist_is_required(self):
        self.assertFalse(evaluate_live_guard_stage18(self.good(exact_ip_allowlist=False)).allowed)

    def test_order_notional_must_be_positive_and_at_most_10_usdt(self):
        for value in ("0", "-1", "10.01", "100", "NaN", "Infinity", ""):
            with self.subTest(value=value):
                self.assertFalse(evaluate_live_guard_stage18(self.good(order_notional_quote=value)).allowed)
        self.assertTrue(evaluate_live_guard_stage18(self.good(order_notional_quote="0.01")).allowed)
        self.assertTrue(evaluate_live_guard_stage18(self.good(order_notional_quote="9.99")).allowed)

    def test_risk_profile_is_exact(self):
        fields = {
            "max_position_pct": "0.24",
            "stop_loss_pct": "0.014",
            "take_profit_pct": "0.031",
            "max_daily_loss_pct": "0.029",
        }
        for field, value in fields.items():
            with self.subTest(field=field):
                self.assertFalse(evaluate_live_guard_stage18(self.good(**{field: value})).allowed)


if __name__ == "__main__":
    unittest.main()

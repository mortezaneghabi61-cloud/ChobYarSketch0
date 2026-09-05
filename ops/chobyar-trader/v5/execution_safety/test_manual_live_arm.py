from __future__ import annotations

import unittest

from manual_live_arm import ManualLiveArmRequest, evaluate_manual_live_arm_request


class ManualLiveArmTests(unittest.TestCase):
    def good(self, **overrides):
        values = dict(
            symbol="BTCUSDT",
            quote_asset="USDT",
            approved_max_order_quote="10",
            explicit_confirmation=True,
            final_readiness_passed=True,
            read_permission_enabled=True,
            trade_permission_enabled=False,
            withdrawal_permission_enabled=False,
            exact_ip_allowlist=True,
            leverage_enabled=False,
            margin_enabled=False,
            futures_enabled=False,
            otc_enabled=False,
        )
        values.update(overrides)
        return ManualLiveArmRequest(**values)

    def test_success_is_still_non_executing(self):
        d = evaluate_manual_live_arm_request(self.good())
        self.assertTrue(d.allowed)
        self.assertTrue(d.ready_to_request_manual_trade_permission)
        self.assertFalse(d.ready_to_change_live_env)
        self.assertFalse(d.live_ready)
        self.assertFalse(d.execution_authority)
        self.assertEqual(str(d.approved_max_order_quote), "10")

    def test_requires_explicit_confirmation(self):
        d = evaluate_manual_live_arm_request(self.good(explicit_confirmation=False))
        self.assertFalse(d.allowed)

    def test_trade_permission_must_still_be_off(self):
        d = evaluate_manual_live_arm_request(self.good(trade_permission_enabled=True))
        self.assertFalse(d.allowed)

    def test_withdrawal_and_nonspot_authority_rejected(self):
        for field in ("withdrawal_permission_enabled", "leverage_enabled", "margin_enabled", "futures_enabled", "otc_enabled"):
            with self.subTest(field=field):
                d = evaluate_manual_live_arm_request(self.good(**{field: True}))
                self.assertFalse(d.allowed)

    def test_exact_ip_required(self):
        self.assertFalse(evaluate_manual_live_arm_request(self.good(exact_ip_allowlist=False)).allowed)

    def test_cap_must_be_positive_finite(self):
        for value in ("", "0", "-1", "NaN", "Infinity"):
            with self.subTest(value=value):
                self.assertFalse(evaluate_manual_live_arm_request(self.good(approved_max_order_quote=value)).allowed)

    def test_only_btcusdt_usdt(self):
        self.assertFalse(evaluate_manual_live_arm_request(self.good(symbol="ETHUSDT")).allowed)
        self.assertFalse(evaluate_manual_live_arm_request(self.good(quote_asset="TMN")).allowed)


if __name__ == "__main__":
    unittest.main()

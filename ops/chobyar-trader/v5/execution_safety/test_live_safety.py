from __future__ import annotations

import unittest

from live_safety import evaluate_live_safety


BASE = {
    "TRADING_MODE": "paper",
    "LIVE_TRADING_ENABLED": "false",
    "LIVE_DRY_RUN": "true",
    "SPOT_ONLY": "true",
    "WITHDRAWALS_ENABLED": "false",
    "LEVERAGE_ENABLED": "false",
    "LIVE_MAX_ORDER_TMN": "100000",
    "MAX_POSITION_PCT": "0.25",
    "STOP_LOSS_PCT": "0.015",
    "TAKE_PROFIT_PCT": "0.03",
    "MAX_DAILY_LOSS_PCT": "0.03",
}


class LiveSafetyTests(unittest.TestCase):
    def test_stage1_baseline_is_allowed_for_read_and_dry_run_only(self) -> None:
        decision = evaluate_live_safety(BASE)
        self.assertTrue(decision.allowed)
        self.assertEqual(decision.reason, "stage1_read_and_dry_run_only")

    def test_live_execution_flag_true_is_blocked(self) -> None:
        env = {**BASE, "TRADING_MODE": "live", "LIVE_TRADING_ENABLED": "true"}
        decision = evaluate_live_safety(env)
        self.assertFalse(decision.allowed)
        self.assertEqual(decision.reason, "stage1_requires_live_trading_disabled")

    def test_dry_run_false_is_blocked(self) -> None:
        decision = evaluate_live_safety({**BASE, "LIVE_DRY_RUN": "false"})
        self.assertFalse(decision.allowed)

    def test_withdrawals_must_remain_disabled(self) -> None:
        decision = evaluate_live_safety({**BASE, "WITHDRAWALS_ENABLED": "true"})
        self.assertFalse(decision.allowed)
        self.assertEqual(decision.reason, "withdrawals_must_be_disabled")

    def test_leverage_must_remain_disabled(self) -> None:
        decision = evaluate_live_safety({**BASE, "LEVERAGE_ENABLED": "true"})
        self.assertFalse(decision.allowed)
        self.assertEqual(decision.reason, "leverage_must_be_disabled")

    def test_spot_only_is_required(self) -> None:
        decision = evaluate_live_safety({**BASE, "SPOT_ONLY": "false"})
        self.assertFalse(decision.allowed)
        self.assertEqual(decision.reason, "spot_only_required")

    def test_stage1_order_cap_is_enforced(self) -> None:
        decision = evaluate_live_safety({**BASE, "LIVE_MAX_ORDER_TMN": "100001"})
        self.assertFalse(decision.allowed)
        self.assertEqual(decision.reason, "live_max_order_tmn_exceeds_stage1_cap")

    def test_missing_order_cap_is_blocked(self) -> None:
        env = dict(BASE)
        env.pop("LIVE_MAX_ORDER_TMN")
        decision = evaluate_live_safety(env)
        self.assertFalse(decision.allowed)

    def test_each_approved_risk_value_is_exact(self) -> None:
        for key in ("MAX_POSITION_PCT", "STOP_LOSS_PCT", "TAKE_PROFIT_PCT", "MAX_DAILY_LOSS_PCT"):
            with self.subTest(key=key):
                decision = evaluate_live_safety({**BASE, key: "0.999"})
                self.assertFalse(decision.allowed)
                self.assertEqual(decision.reason, f"risk_mismatch:{key}")


if __name__ == "__main__":
    unittest.main()

import unittest
from dataclasses import replace

from live_runtime_stage20 import (
    LiveRuntimeStage20Request,
    evaluate_live_runtime_stage20,
)


def valid_request() -> LiveRuntimeStage20Request:
    return LiveRuntimeStage20Request(
        stage19_transition_passed=True,
        explicit_operator_confirmation=True,
        symbol="BTCUSDT",
        quote_asset="USDT",
        max_order_usdt="10",
        trading_mode="live",
        live_trading_enabled="true",
        live_execution_armed="false",
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
        open_orders_count=0,
    )


class Stage20Tests(unittest.TestCase):
    def test_valid_live_observation_stays_execution_disarmed(self):
        result = evaluate_live_runtime_stage20(valid_request())
        self.assertTrue(result.allowed)
        self.assertTrue(result.live_observation_mode)
        self.assertFalse(result.execution_authority)
        self.assertFalse(result.order_submission_authority)
        self.assertEqual(result.approved_max_order_usdt, 10)

    def test_requires_stage19_and_confirmation(self):
        self.assertFalse(evaluate_live_runtime_stage20(
            replace(valid_request(), stage19_transition_passed=False)
        ).allowed)
        self.assertFalse(evaluate_live_runtime_stage20(
            replace(valid_request(), explicit_operator_confirmation=False)
        ).allowed)

    def test_rejects_wrong_symbol_or_cap(self):
        self.assertFalse(evaluate_live_runtime_stage20(
            replace(valid_request(), symbol="ETHUSDT")
        ).allowed)
        self.assertFalse(evaluate_live_runtime_stage20(
            replace(valid_request(), max_order_usdt="10.01")
        ).allowed)

    def test_requires_live_mode_but_execution_arm_false(self):
        self.assertEqual(evaluate_live_runtime_stage20(
            replace(valid_request(), trading_mode="paper")
        ).reason, "live_observation_requires_trading_mode_live")
        self.assertEqual(evaluate_live_runtime_stage20(
            replace(valid_request(), live_trading_enabled="false")
        ).reason, "live_observation_requires_live_gate_true")
        self.assertEqual(evaluate_live_runtime_stage20(
            replace(valid_request(), live_execution_armed="true")
        ).reason, "stage20_requires_execution_arm_false")

    def test_withdrawal_and_non_spot_authority_are_rejected(self):
        self.assertFalse(evaluate_live_runtime_stage20(
            replace(valid_request(), withdrawal_permission_enabled=True)
        ).allowed)
        for field in ("leverage_enabled", "margin_enabled", "futures_enabled", "otc_enabled"):
            self.assertFalse(evaluate_live_runtime_stage20(
                replace(valid_request(), **{field: True})
            ).allowed)

    def test_risk_ip_and_open_orders_are_fail_closed(self):
        self.assertFalse(evaluate_live_runtime_stage20(
            replace(valid_request(), exact_ip_allowlist=False)
        ).allowed)
        self.assertFalse(evaluate_live_runtime_stage20(
            replace(valid_request(), max_position_pct="0.251")
        ).allowed)
        self.assertEqual(evaluate_live_runtime_stage20(
            replace(valid_request(), open_orders_count=1)
        ).reason, "open_orders_must_be_zero")
        self.assertEqual(evaluate_live_runtime_stage20(
            replace(valid_request(), open_orders_count=False)
        ).reason, "open_orders_count_invalid")


if __name__ == "__main__":
    unittest.main()

import unittest
from dataclasses import replace

from live_activation_stage19 import (
    LiveActivationStage19Request,
    evaluate_live_activation_stage19,
)


def valid_request() -> LiveActivationStage19Request:
    return LiveActivationStage19Request(
        stage18_guard_passed=True,
        symbol="BTCUSDT",
        quote_asset="USDT",
        approved_max_order_usdt="10",
        explicit_operator_confirmation=True,
        current_trading_mode="paper",
        current_live_trading_enabled="false",
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
        trader_service_active=True,
        status_service_active=True,
        shadow_timer_active=True,
        open_orders_count=0,
    )


class LiveActivationStage19Tests(unittest.TestCase):
    def test_valid_boundary_never_grants_execution_authority(self):
        result = evaluate_live_activation_stage19(valid_request())
        self.assertTrue(result.allowed)
        self.assertTrue(result.ready_for_operator_live_env_transition)
        self.assertEqual(result.approved_max_order_usdt, 10)
        self.assertFalse(result.live_ready)
        self.assertFalse(result.execution_authority)
        self.assertFalse(result.order_submission_authority)

    def test_requires_stage18_guard_and_explicit_confirmation(self):
        self.assertFalse(evaluate_live_activation_stage19(
            replace(valid_request(), stage18_guard_passed=False)
        ).allowed)
        self.assertFalse(evaluate_live_activation_stage19(
            replace(valid_request(), explicit_operator_confirmation=False)
        ).allowed)

    def test_requires_current_runtime_to_remain_paper_and_live_false(self):
        self.assertEqual(evaluate_live_activation_stage19(
            replace(valid_request(), current_trading_mode="live")
        ).reason, "current_runtime_must_still_be_paper")
        self.assertEqual(evaluate_live_activation_stage19(
            replace(valid_request(), current_live_trading_enabled="true")
        ).reason, "current_live_gate_must_still_be_false")

    def test_requires_exact_10_usdt_cap(self):
        self.assertFalse(evaluate_live_activation_stage19(
            replace(valid_request(), approved_max_order_usdt="10.01")
        ).allowed)
        self.assertFalse(evaluate_live_activation_stage19(
            replace(valid_request(), approved_max_order_usdt="NaN")
        ).allowed)

    def test_withdrawal_and_non_spot_authority_are_rejected(self):
        self.assertFalse(evaluate_live_activation_stage19(
            replace(valid_request(), withdrawal_permission_enabled=True)
        ).allowed)
        for field in ("leverage_enabled", "margin_enabled", "futures_enabled", "otc_enabled"):
            self.assertFalse(evaluate_live_activation_stage19(
                replace(valid_request(), **{field: True})
            ).allowed)

    def test_requires_exact_risk_profile_and_ip_posture(self):
        self.assertFalse(evaluate_live_activation_stage19(
            replace(valid_request(), max_daily_loss_pct="0.031")
        ).allowed)
        self.assertFalse(evaluate_live_activation_stage19(
            replace(valid_request(), exact_ip_allowlist=False)
        ).allowed)

    def test_requires_healthy_services_and_zero_open_orders(self):
        self.assertFalse(evaluate_live_activation_stage19(
            replace(valid_request(), trader_service_active=False)
        ).allowed)
        self.assertFalse(evaluate_live_activation_stage19(
            replace(valid_request(), status_service_active=False)
        ).allowed)
        self.assertFalse(evaluate_live_activation_stage19(
            replace(valid_request(), shadow_timer_active=False)
        ).allowed)
        self.assertEqual(evaluate_live_activation_stage19(
            replace(valid_request(), open_orders_count=1)
        ).reason, "open_orders_must_be_zero_before_transition")

    def test_boolean_open_order_count_is_rejected(self):
        self.assertEqual(evaluate_live_activation_stage19(
            replace(valid_request(), open_orders_count=False)
        ).reason, "open_orders_count_invalid")


if __name__ == "__main__":
    unittest.main()

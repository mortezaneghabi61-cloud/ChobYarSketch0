from __future__ import annotations

import unittest

from final_readiness import evaluate_final_readiness
from permission_readiness import ApiPermissionPosture


BASE_ENV = {
    "TRADING_MODE": "paper",
    "LIVE_DRY_RUN": "true",
    "SPOT_ONLY": "true",
    "WITHDRAWALS_ENABLED": "false",
    "LEVERAGE_ENABLED": "false",
    "LIVE_TRADING_ENABLED": "false",
    "MAX_POSITION_PCT": "0.25",
    "STOP_LOSS_PCT": "0.015",
    "TAKE_PROFIT_PCT": "0.03",
    "MAX_DAILY_LOSS_PCT": "0.03",
}

POSTURE = ApiPermissionPosture(
    read_enabled=True,
    trade_enabled=False,
    withdrawal_enabled=False,
    allowed_ips=("109.122.247.214",),
)


class FinalReadinessTests(unittest.TestCase):
    def test_passes_only_as_non_executing_manual_decision_readiness(self):
        result = evaluate_final_readiness(
            env=BASE_ENV,
            posture=POSTURE,
            expected_server_ip="109.122.247.214",
            readonly_probe_passed=True,
            trader_service_active=True,
            status_service_active=True,
            shadow_timer_active=True,
        )
        self.assertTrue(result.allowed)
        self.assertTrue(result.ready_for_manual_live_decision)
        self.assertFalse(result.live_ready)

    def test_trade_permission_on_is_rejected(self):
        result = evaluate_final_readiness(
            env=BASE_ENV,
            posture=ApiPermissionPosture(True, True, False, ("109.122.247.214",)),
            expected_server_ip="109.122.247.214",
            readonly_probe_passed=True,
            trader_service_active=True,
            status_service_active=True,
            shadow_timer_active=True,
        )
        self.assertFalse(result.allowed)
        self.assertFalse(result.live_ready)

    def test_live_enabled_is_rejected(self):
        result = evaluate_final_readiness(
            env={**BASE_ENV, "LIVE_TRADING_ENABLED": "true"},
            posture=POSTURE,
            expected_server_ip="109.122.247.214",
            readonly_probe_passed=True,
            trader_service_active=True,
            status_service_active=True,
            shadow_timer_active=True,
        )
        self.assertFalse(result.allowed)
        self.assertFalse(result.live_ready)

    def test_missing_readonly_probe_is_rejected(self):
        result = evaluate_final_readiness(
            env=BASE_ENV,
            posture=POSTURE,
            expected_server_ip="109.122.247.214",
            readonly_probe_passed=False,
            trader_service_active=True,
            status_service_active=True,
            shadow_timer_active=True,
        )
        self.assertEqual(result.reason, "readonly_probe_required")
        self.assertFalse(result.allowed)

    def test_unhealthy_runtime_component_is_rejected(self):
        for field in ("trader", "status", "shadow"):
            kwargs = dict(
                trader_service_active=True,
                status_service_active=True,
                shadow_timer_active=True,
            )
            if field == "trader":
                kwargs["trader_service_active"] = False
            elif field == "status":
                kwargs["status_service_active"] = False
            else:
                kwargs["shadow_timer_active"] = False
            result = evaluate_final_readiness(
                env=BASE_ENV,
                posture=POSTURE,
                expected_server_ip="109.122.247.214",
                readonly_probe_passed=True,
                **kwargs,
            )
            self.assertFalse(result.allowed)
            self.assertFalse(result.live_ready)


if __name__ == "__main__":
    unittest.main()

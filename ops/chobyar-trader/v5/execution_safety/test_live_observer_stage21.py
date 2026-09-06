from __future__ import annotations

import unittest
from dataclasses import dataclass

from live_observer_stage21 import run_live_observer_once, validate_live_observer_env


def valid_env() -> dict[str, str]:
    return {
        "TRADING_MODE": "live",
        "LIVE_TRADING_ENABLED": "true",
        "LIVE_EXECUTION_ARMED": "false",
        "SYMBOL": "BTCUSDT",
        "LIVE_MAX_ORDER_USDT": "10",
        "SPOT_ONLY": "true",
        "WITHDRAWALS_ENABLED": "false",
        "LEVERAGE_ENABLED": "false",
        "MAX_POSITION_PCT": "0.25",
        "STOP_LOSS_PCT": "0.015",
        "TAKE_PROFIT_PCT": "0.03",
        "MAX_DAILY_LOSS_PCT": "0.03",
        "WALLEX_API_KEY": "test-key-never-printed",
    }


@dataclass
class FakeResponse:
    status_code: int
    payload: object

    def json(self):
        return self.payload


class FakeClient:
    def __init__(self, open_orders=None):
        self.calls: list[tuple[str, str]] = []
        self.open_orders = [] if open_orders is None else open_orders

    def get(self, path, **kwargs):
        self.calls.append(("GET", path))
        if path == "/v1/account/balances":
            return FakeResponse(200, {"success": True, "result": {"balances": {}}})
        if path == "/v1/account/openOrders":
            return FakeResponse(200, {"success": True, "result": {"orders": self.open_orders}})
        if path == "/hector/web/v1/markets":
            return FakeResponse(200, {"result": {"symbols": []}})
        raise AssertionError(f"unexpected path: {path}")


class Stage21Tests(unittest.TestCase):
    def test_valid_observer_is_get_only_and_disarmed(self):
        client = FakeClient()
        result = run_live_observer_once(env=valid_env(), client=client)
        self.assertEqual(result["live_observer"], "PASS")
        self.assertFalse(result["execution_armed"])
        self.assertFalse(result["execution_authority"])
        self.assertFalse(result["order_submission_authority"])
        self.assertEqual(client.calls, [
            ("GET", "/v1/account/balances"),
            ("GET", "/v1/account/openOrders"),
            ("GET", "/hector/web/v1/markets"),
        ])

    def test_execution_arm_true_is_rejected_before_network(self):
        env = valid_env()
        env["LIVE_EXECUTION_ARMED"] = "true"
        client = FakeClient()
        with self.assertRaisesRegex(RuntimeError, "stage21_requires_execution_arm_false"):
            run_live_observer_once(env=env, client=client)
        self.assertEqual(client.calls, [])

    def test_cap_above_or_below_10_is_rejected(self):
        for cap in ("9.99", "10.01", "100"):
            env = valid_env()
            env["LIVE_MAX_ORDER_USDT"] = cap
            with self.assertRaisesRegex(RuntimeError, "stage21_cap_must_equal_10_usdt"):
                validate_live_observer_env(env)

    def test_risk_drift_is_rejected(self):
        env = valid_env()
        env["MAX_DAILY_LOSS_PCT"] = "0.031"
        with self.assertRaisesRegex(RuntimeError, "stage21_risk_profile_mismatch"):
            validate_live_observer_env(env)

    def test_non_spot_or_withdrawal_or_leverage_is_rejected(self):
        for key, value in (
            ("SPOT_ONLY", "false"),
            ("WITHDRAWALS_ENABLED", "true"),
            ("LEVERAGE_ENABLED", "true"),
        ):
            env = valid_env()
            env[key] = value
            with self.assertRaises(RuntimeError):
                validate_live_observer_env(env)

    def test_existing_open_order_blocks_observer(self):
        client = FakeClient(open_orders=[{"symbol": "BTCUSDT"}])
        with self.assertRaisesRegex(RuntimeError, "stage21_requires_zero_open_orders"):
            run_live_observer_once(env=valid_env(), client=client)

    def test_missing_key_is_rejected_without_secret_output(self):
        env = valid_env()
        env.pop("WALLEX_API_KEY")
        client = FakeClient()
        with self.assertRaisesRegex(RuntimeError, "wallex_api_key_missing"):
            run_live_observer_once(env=env, client=client)
        self.assertEqual(client.calls, [])


if __name__ == "__main__":
    unittest.main()

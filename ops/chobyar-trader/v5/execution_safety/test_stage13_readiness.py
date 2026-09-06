from __future__ import annotations

import unittest
from decimal import Decimal

from permission_readiness import ApiPermissionPosture, evaluate_api_permission_readiness
from quote_order_preflight import QuoteOrderIntent
from stage13_readiness import run_stage13_readiness


BASE_ENV = {
    "TRADING_MODE": "paper",
    "LIVE_DRY_RUN": "true",
    "SPOT_ONLY": "true",
    "WITHDRAWALS_ENABLED": "false",
    "LEVERAGE_ENABLED": "false",
    "LIVE_TRADING_ENABLED": "false",
    "LIVE_MAX_ORDER_USDT": "25",
    "SYMBOL": "BTCUSDT",
    "MAX_POSITION_PCT": "0.25",
    "STOP_LOSS_PCT": "0.015",
    "TAKE_PROFIT_PCT": "0.03",
    "MAX_DAILY_LOSS_PCT": "0.03",
}

EXPECTED_IP = "203.0.113.10"
SAFE_POSTURE = ApiPermissionPosture(
    read_enabled=True,
    trade_enabled=False,
    withdrawal_enabled=False,
    allowed_ips=(EXPECTED_IP,),
)


class Response:
    def __init__(self, payload, *, status_code=200):
        self.payload = payload
        self.status_code = status_code

    def raise_for_status(self):
        if self.status_code != 200:
            raise RuntimeError("http_failure")

    def json(self):
        return self.payload


class Client:
    def __init__(self):
        self.calls = []

    def get(self, path, *, headers=None):
        self.calls.append(("GET", path, dict(headers or {})))
        if path == "/v1/account/balances":
            return Response({
                "success": True,
                "result": {"balances": {"USDT": {"value": "25", "locked": "0"}}},
            })
        if path == "/v1/markets":
            return Response({
                "success": True,
                "result": {"symbols": {"BTCUSDT": {
                    "symbol": "BTCUSDT",
                    "stepSize": 6,
                    "tickSize": 2,
                    "minNotional": "10",
                    "maxNotional": "100000",
                }}},
            })
        if path == "/hector/web/v1/markets":
            return Response({"result": [{"symbol": "BTCUSDT", "is_spot": True}]})
        raise AssertionError(path)


class Stage13Tests(unittest.TestCase):
    def test_safe_least_privilege_posture_passes_but_never_live_ready(self):
        decision = evaluate_api_permission_readiness(
            posture=SAFE_POSTURE,
            expected_server_ip=EXPECTED_IP,
        )
        self.assertTrue(decision.allowed)
        self.assertTrue(decision.exact_ip_allowlist)
        self.assertFalse(decision.live_ready)

    def test_trade_permission_enabled_is_rejected(self):
        posture = ApiPermissionPosture(True, True, False, (EXPECTED_IP,))
        decision = evaluate_api_permission_readiness(posture=posture, expected_server_ip=EXPECTED_IP)
        self.assertFalse(decision.allowed)
        self.assertEqual(decision.reason, "api_trade_permission_must_remain_disabled_stage13")

    def test_withdrawal_permission_enabled_is_rejected(self):
        posture = ApiPermissionPosture(True, False, True, (EXPECTED_IP,))
        decision = evaluate_api_permission_readiness(posture=posture, expected_server_ip=EXPECTED_IP)
        self.assertFalse(decision.allowed)
        self.assertEqual(decision.reason, "api_withdrawal_permission_must_remain_disabled")

    def test_empty_or_extra_ip_allowlist_is_rejected(self):
        for ips in ((), (EXPECTED_IP, "203.0.113.11")):
            with self.subTest(ips=ips):
                posture = ApiPermissionPosture(True, False, False, ips)
                decision = evaluate_api_permission_readiness(posture=posture, expected_server_ip=EXPECTED_IP)
                self.assertFalse(decision.allowed)
                self.assertEqual(decision.reason, "api_ip_allowlist_must_match_single_expected_server")

    def test_integrated_readiness_is_get_only_and_not_live_ready(self):
        client = Client()
        report = run_stage13_readiness(
            env=BASE_ENV,
            api_key="secret-value",
            client=client,
            intent=QuoteOrderIntent("BTCUSDT", "BUY", Decimal("0.0002"), Decimal("50000.00")),
            posture=SAFE_POSTURE,
            expected_server_ip=EXPECTED_IP,
        )
        self.assertTrue(report.allowed)
        self.assertFalse(report.live_ready)
        self.assertFalse(report.authenticated.live_ready)
        self.assertEqual([method for method, _, _ in client.calls], ["GET", "GET", "GET"])
        self.assertEqual([path for _, path, _ in client.calls], [
            "/v1/account/balances",
            "/v1/markets",
            "/hector/web/v1/markets",
        ])
        self.assertNotIn("secret-value", repr(report))

    def test_live_enabled_remains_fail_closed(self):
        client = Client()
        env = dict(BASE_ENV)
        env["LIVE_TRADING_ENABLED"] = "true"
        with self.assertRaisesRegex(RuntimeError, "v5_safety_blocked"):
            run_stage13_readiness(
                env=env,
                api_key="secret-value",
                client=client,
                intent=QuoteOrderIntent("BTCUSDT", "BUY", Decimal("0.0002"), Decimal("50000.00")),
                posture=SAFE_POSTURE,
                expected_server_ip=EXPECTED_IP,
            )
        self.assertEqual(client.calls, [])


if __name__ == "__main__":
    unittest.main()

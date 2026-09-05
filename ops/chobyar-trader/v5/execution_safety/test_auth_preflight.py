from __future__ import annotations

import unittest

from auth_preflight import run_authenticated_preflight


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


class Response:
    def __init__(self, payload, status_code=200):
        self._payload = payload
        self.status_code = status_code

    def json(self):
        return self._payload

    def raise_for_status(self):
        if self.status_code >= 400:
            raise RuntimeError("http_error")


class FakeClient:
    def __init__(self):
        self.calls = []

    def get(self, path, headers=None):
        self.calls.append((path, headers))
        if path == "/v1/account/balances":
            return Response({
                "success": True,
                "result": {
                    "balances": {
                        "TMN": {"value": "999900", "locked": "0"},
                        "BTC": {"value": "0", "locked": "0"},
                    }
                },
            })
        if path == "/v1/markets":
            return Response({
                "success": True,
                "result": {
                    "symbols": {
                        "BTCUSDT": {
                            "symbol": "BTCUSDT",
                            "stepSize": 6,
                            "tickSize": 1,
                            "minNotional": "10",
                            "maxNotional": "100000",
                        }
                    }
                },
            })
        return Response({}, status_code=404)


class AuthPreflightTests(unittest.TestCase):
    def test_success_is_readonly_and_never_live_ready(self):
        client = FakeClient()
        result = run_authenticated_preflight(
            env=BASE, api_key="secret-not-returned", client=client, symbol="BTCUSDT"
        )
        self.assertTrue(result.allowed)
        self.assertFalse(result.live_ready)
        self.assertEqual(result.reason, "stage5_authenticated_readonly_preflight_only")
        self.assertEqual(result.symbol, "BTCUSDT")
        self.assertEqual([call[0] for call in client.calls], [
            "/v1/account/balances",
            "/v1/markets",
        ])
        self.assertNotIn("secret-not-returned", repr(result))

    def test_stage1_block_prevents_network(self):
        client = FakeClient()
        with self.assertRaisesRegex(RuntimeError, "stage1_blocked"):
            run_authenticated_preflight(
                env={**BASE, "WITHDRAWALS_ENABLED": "true"},
                api_key="x",
                client=client,
                symbol="BTCUSDT",
            )
        self.assertEqual(client.calls, [])

    def test_missing_key_prevents_network(self):
        client = FakeClient()
        with self.assertRaisesRegex(RuntimeError, "wallex_api_key_missing"):
            run_authenticated_preflight(env=BASE, api_key="", client=client, symbol="BTCUSDT")
        self.assertEqual(client.calls, [])

    def test_live_flag_blocks_pre_network(self):
        client = FakeClient()
        with self.assertRaises(RuntimeError):
            run_authenticated_preflight(
                env={**BASE, "LIVE_TRADING_ENABLED": "true"},
                api_key="x",
                client=client,
                symbol="BTCUSDT",
            )
        self.assertEqual(client.calls, [])

    def test_missing_symbol_prevents_network(self):
        client = FakeClient()
        with self.assertRaisesRegex(RuntimeError, "preflight_symbol_missing"):
            run_authenticated_preflight(env=BASE, api_key="x", client=client, symbol="")
        self.assertEqual(client.calls, [])

    def test_only_get_surface_is_used(self):
        client = FakeClient()
        run_authenticated_preflight(env=BASE, api_key="x", client=client, symbol="BTCUSDT")
        self.assertEqual(len(client.calls), 2)
        self.assertTrue(all(call[0].startswith("/v1/") for call in client.calls))


if __name__ == "__main__":
    unittest.main()

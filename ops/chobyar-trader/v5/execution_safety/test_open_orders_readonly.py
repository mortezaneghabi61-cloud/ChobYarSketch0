from __future__ import annotations

import unittest

from open_orders_readonly import fetch_open_orders_readonly

BASE_ENV = {
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


class FakeResponse:
    def __init__(self, payload, status_code=200):
        self._payload = payload
        self.status_code = status_code

    def raise_for_status(self):
        if self.status_code >= 400:
            raise RuntimeError("http_error")

    def json(self):
        return self._payload


class RecordingClient:
    def __init__(self, response):
        self.response = response
        self.calls = []

    def get(self, path, *, params=None, headers=None):
        self.calls.append((path, params, headers))
        return self.response


VALID_PAYLOAD = {
    "success": True,
    "result": {
        "orders": [
            {
                "symbol": "BTCUSDT",
                "type": "LIMIT",
                "side": "BUY",
                "price": "50000",
                "origQty": "0.001",
                "executedQty": "0.0002",
                "status": "NEW",
                "active": True,
                "clientOrderId": "LIMIT-test-1",
            }
        ]
    },
}


class Stage6OpenOrdersTests(unittest.TestCase):
    def test_safe_path_uses_exact_get_surface(self):
        client = RecordingClient(FakeResponse(VALID_PAYLOAD))
        orders = fetch_open_orders_readonly(
            env=BASE_ENV,
            api_key="secret-value",
            client=client,
            symbol="btcusdt",
        )
        self.assertEqual(len(orders), 1)
        self.assertEqual(orders[0].symbol, "BTCUSDT")
        self.assertEqual(orders[0].client_order_id, "LIMIT-test-1")
        self.assertEqual(client.calls[0][0], "/v1/account/openOrders")
        self.assertEqual(client.calls[0][1], {"symbol": "BTCUSDT"})
        self.assertEqual(client.calls[0][2]["X-API-Key"], "secret-value")

    def test_stage1_block_happens_before_network(self):
        env = dict(BASE_ENV, WITHDRAWALS_ENABLED="true")
        client = RecordingClient(FakeResponse(VALID_PAYLOAD))
        with self.assertRaisesRegex(RuntimeError, "stage1_blocked"):
            fetch_open_orders_readonly(env=env, api_key="x", client=client, symbol="BTCUSDT")
        self.assertEqual(client.calls, [])

    def test_missing_key_blocks_before_network(self):
        client = RecordingClient(FakeResponse(VALID_PAYLOAD))
        with self.assertRaisesRegex(RuntimeError, "wallex_api_key_missing"):
            fetch_open_orders_readonly(env=BASE_ENV, api_key=" ", client=client, symbol="BTCUSDT")
        self.assertEqual(client.calls, [])

    def test_missing_symbol_blocks_before_network(self):
        client = RecordingClient(FakeResponse(VALID_PAYLOAD))
        with self.assertRaisesRegex(RuntimeError, "open_orders_symbol_missing"):
            fetch_open_orders_readonly(env=BASE_ENV, api_key="x", client=client, symbol=" ")
        self.assertEqual(client.calls, [])

    def test_symbol_mismatch_fails_closed(self):
        payload = {"success": True, "result": {"orders": [dict(VALID_PAYLOAD["result"]["orders"][0], symbol="ETHUSDT")]}}
        client = RecordingClient(FakeResponse(payload))
        with self.assertRaisesRegex(RuntimeError, "open_orders_symbol_mismatch"):
            fetch_open_orders_readonly(env=BASE_ENV, api_key="x", client=client, symbol="BTCUSDT")

    def test_inactive_order_fails_closed(self):
        payload = {"success": True, "result": {"orders": [dict(VALID_PAYLOAD["result"]["orders"][0], active=False)]}}
        client = RecordingClient(FakeResponse(payload))
        with self.assertRaisesRegex(RuntimeError, "open_orders_state_invalid"):
            fetch_open_orders_readonly(env=BASE_ENV, api_key="x", client=client, symbol="BTCUSDT")

    def test_invalid_side_fails_closed(self):
        payload = {"success": True, "result": {"orders": [dict(VALID_PAYLOAD["result"]["orders"][0], side="HOLD")]}}
        client = RecordingClient(FakeResponse(payload))
        with self.assertRaisesRegex(RuntimeError, "open_orders_side_invalid"):
            fetch_open_orders_readonly(env=BASE_ENV, api_key="x", client=client, symbol="BTCUSDT")

    def test_invalid_number_fails_closed(self):
        payload = {"success": True, "result": {"orders": [dict(VALID_PAYLOAD["result"]["orders"][0], origQty="nan")]}}
        client = RecordingClient(FakeResponse(payload))
        with self.assertRaisesRegex(RuntimeError, "open_orders_invalid_number"):
            fetch_open_orders_readonly(env=BASE_ENV, api_key="x", client=client, symbol="BTCUSDT")


if __name__ == "__main__":
    unittest.main()

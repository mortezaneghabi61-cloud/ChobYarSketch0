from __future__ import annotations

import unittest

from wallex_readonly import BALANCES_PATH, fetch_balances_readonly

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


class FakeResponse:
    def __init__(self, payload, *, status_ok=True):
        self.payload = payload
        self.status_ok = status_ok

    def raise_for_status(self):
        if not self.status_ok:
            raise RuntimeError("http_failure")

    def json(self):
        return self.payload


class FakeClient:
    def __init__(self, response):
        self.response = response
        self.calls = []

    def get(self, path, *, headers):
        self.calls.append(("GET", path, dict(headers)))
        return self.response


class WallexReadOnlyTests(unittest.TestCase):
    def test_reads_balances_with_get_only(self):
        client = FakeClient(FakeResponse({
            "success": True,
            "result": {"balances": {"TMN": {"value": "123", "locked": "0"}}},
        }))
        rows = fetch_balances_readonly(env=BASE, api_key="secret-value", client=client)
        self.assertEqual(rows[0].asset, "TMN")
        self.assertEqual(rows[0].value, 123.0)
        self.assertEqual(client.calls[0][0], "GET")
        self.assertEqual(client.calls[0][1], BALANCES_PATH)
        self.assertEqual(client.calls[0][2]["X-API-Key"], "secret-value")
        self.assertNotIn("secret-value", repr(rows))

    def test_stage1_block_prevents_network_call(self):
        client = FakeClient(FakeResponse({"success": True, "result": {"balances": {}}}))
        with self.assertRaisesRegex(RuntimeError, "stage1_blocked"):
            fetch_balances_readonly(
                env={**BASE, "LIVE_TRADING_ENABLED": "true"},
                api_key="secret-value",
                client=client,
            )
        self.assertEqual(client.calls, [])

    def test_missing_key_prevents_network_call(self):
        client = FakeClient(FakeResponse({"success": True, "result": {"balances": {}}}))
        with self.assertRaisesRegex(RuntimeError, "wallex_api_key_missing"):
            fetch_balances_readonly(env=BASE, api_key="", client=client)
        self.assertEqual(client.calls, [])

    def test_rejects_failed_api_contract(self):
        client = FakeClient(FakeResponse({"success": False}))
        with self.assertRaisesRegex(RuntimeError, "wallex_balance_request_rejected"):
            fetch_balances_readonly(env=BASE, api_key="secret-value", client=client)

    def test_rejects_malformed_schema(self):
        client = FakeClient(FakeResponse({"success": True, "result": {"balances": []}}))
        with self.assertRaisesRegex(RuntimeError, "wallex_balance_schema_invalid"):
            fetch_balances_readonly(env=BASE, api_key="secret-value", client=client)

    def test_rejects_negative_balance(self):
        client = FakeClient(FakeResponse({
            "success": True,
            "result": {"balances": {"BTC": {"value": "-1", "locked": "0"}}},
        }))
        with self.assertRaisesRegex(RuntimeError, "wallex_balance_negative"):
            fetch_balances_readonly(env=BASE, api_key="secret-value", client=client)


if __name__ == "__main__":
    unittest.main()

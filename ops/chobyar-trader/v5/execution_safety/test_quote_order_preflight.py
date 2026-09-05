from __future__ import annotations

import unittest
from decimal import Decimal

from quote_order_preflight import QuoteOrderIntent, parse_quote_market_rules, run_quote_order_preflight
from spot_eligibility import parse_spot_eligibility


BASE_ENV = {
    "TRADING_MODE": "paper",
    "LIVE_DRY_RUN": "true",
    "SPOT_ONLY": "true",
    "WITHDRAWALS_ENABLED": "false",
    "LEVERAGE_ENABLED": "false",
    "LIVE_TRADING_ENABLED": "false",
    "LIVE_MAX_ORDER_TMN": "100000",
    "LIVE_MAX_ORDER_USDT": "25",
    "SYMBOL": "BTCUSDT",
    "MAX_POSITION_PCT": "0.25",
    "STOP_LOSS_PCT": "0.015",
    "TAKE_PROFIT_PCT": "0.03",
    "MAX_DAILY_LOSS_PCT": "0.03",
}


class Response:
    def __init__(self, payload, status_code=200):
        self.payload = payload
        self.status_code = status_code

    def json(self):
        return self.payload


class Client:
    def __init__(self):
        self.paths = []

    def get(self, path):
        self.paths.append(path)
        if path == "/v1/markets":
            return Response({"success": True, "result": {"symbols": {"BTCUSDT": {
                "symbol": "BTCUSDT", "stepSize": 6, "tickSize": 2,
                "minNotional": "10", "maxNotional": "100000",
            }}}})
        if path == "/hector/web/v1/markets":
            return Response({"result": [{"symbol": "BTCUSDT", "is_spot": True}]})
        raise AssertionError(path)


class Stage11Tests(unittest.TestCase):
    def test_metadata_notional_is_quote_asset(self):
        rules = parse_quote_market_rules({"success": True, "result": {"symbols": {"BTCUSDT": {
            "symbol": "BTCUSDT", "stepSize": 6, "tickSize": 2,
            "minNotional": "10", "maxNotional": "100",
        }}}}, "BTCUSDT")
        self.assertEqual(rules.quote_asset, "USDT")
        self.assertEqual(rules.min_notional_quote, Decimal("10"))

    def test_spot_false_is_denied(self):
        result = parse_spot_eligibility({"result": [{"symbol": "BTCUSDT", "is_spot": False}]}, "BTCUSDT")
        self.assertFalse(result.allowed)
        self.assertEqual(result.reason, "market_not_spot")

    def test_quote_cap_uses_usdt_not_tmn(self):
        client = Client()
        intent = QuoteOrderIntent("BTCUSDT", "BUY", Decimal("0.0002"), Decimal("50000.00"))
        result = run_quote_order_preflight(env=BASE_ENV, client=client, intent=intent)
        self.assertTrue(result.allowed)
        self.assertFalse(result.live_ready)
        self.assertEqual(result.notional_quote, Decimal("10.000000"))
        self.assertEqual(result.quote_cap.quote_asset, "USDT")
        self.assertEqual(client.paths, ["/v1/markets", "/hector/web/v1/markets"])

    def test_above_usdt_cap_is_denied_even_when_legacy_tmn_cap_is_large(self):
        client = Client()
        intent = QuoteOrderIntent("BTCUSDT", "BUY", Decimal("0.0006"), Decimal("50000.00"))
        with self.assertRaisesRegex(RuntimeError, "above_quote_asset_cap"):
            run_quote_order_preflight(env=BASE_ENV, client=client, intent=intent)

    def test_live_enabled_is_fail_closed(self):
        env = dict(BASE_ENV)
        env["LIVE_TRADING_ENABLED"] = "true"
        with self.assertRaisesRegex(RuntimeError, "v5_safety_blocked"):
            run_quote_order_preflight(
                env=env,
                client=Client(),
                intent=QuoteOrderIntent("BTCUSDT", "BUY", Decimal("0.0002"), Decimal("50000.00")),
            )


if __name__ == "__main__":
    unittest.main()

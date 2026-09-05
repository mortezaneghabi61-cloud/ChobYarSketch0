from __future__ import annotations

import unittest
from decimal import Decimal

from authenticated_quote_preflight import run_authenticated_quote_preflight
from quote_order_preflight import QuoteOrderIntent


class Response:
    def __init__(self, payload, status_code=200):
        self._payload = payload
        self.status_code = status_code

    def json(self):
        return self._payload

    def raise_for_status(self):
        if self.status_code >= 400:
            raise RuntimeError(f"http_{self.status_code}")


class Client:
    def __init__(self):
        self.calls = []

    def get(self, path, headers=None):
        self.calls.append((path, headers))
        if path == "/v1/account/balances":
            return Response({"success": True, "result": {"balances": {"USDT": {"value": "25", "locked": "0"}}}})
        if path == "/v1/markets":
            return Response({"success": True, "result": {"symbols": {"BTCUSDT": {
                "symbol": "BTCUSDT", "stepSize": 6, "tickSize": 1,
                "minNotional": "10", "maxNotional": "100000"
            }}}})
        if path == "/hector/web/v1/markets":
            return Response({"result": [{"symbol": "BTCUSDT", "is_spot": True}]})
        raise AssertionError(path)


def env():
    # Deliberately NO LIVE_MAX_ORDER_TMN. Stage-12 BTCUSDT authority is USDT.
    return {
        "TRADING_MODE": "paper",
        "LIVE_TRADING_ENABLED": "false",
        "LIVE_DRY_RUN": "true",
        "SPOT_ONLY": "true",
        "WITHDRAWALS_ENABLED": "false",
        "LEVERAGE_ENABLED": "false",
        "SYMBOL": "BTCUSDT",
        "LIVE_MAX_ORDER_USDT": "20",
        "MAX_POSITION_PCT": "0.25",
        "STOP_LOSS_PCT": "0.015",
        "TAKE_PROFIT_PCT": "0.03",
        "MAX_DAILY_LOSS_PCT": "0.03",
    }


class Stage12Tests(unittest.TestCase):
    def test_authenticated_quote_spot_preflight_without_legacy_tmn_cap(self):
        client = Client()
        result = run_authenticated_quote_preflight(
            env=env(), api_key="secret-not-logged", client=client,
            intent=QuoteOrderIntent("BTCUSDT", "BUY", Decimal("0.0002"), Decimal("60000.0")),
        )
        self.assertTrue(result.allowed)
        self.assertFalse(result.live_ready)
        self.assertEqual(result.order.rules.quote_asset, "USDT")
        self.assertEqual(result.order.notional_quote, Decimal("12.00000"))
        self.assertEqual(result.order.quote_cap.cap_quote, Decimal("20"))
        self.assertEqual(result.balances[0].asset, "USDT")

    def test_usdt_cap_is_authoritative_even_if_legacy_tmn_is_huge(self):
        e = env()
        e["LIVE_MAX_ORDER_TMN"] = "999999999"
        e["LIVE_MAX_ORDER_USDT"] = "11"
        with self.assertRaisesRegex(RuntimeError, "above_quote_asset_cap"):
            run_authenticated_quote_preflight(
                env=e, api_key="secret-not-logged", client=Client(),
                intent=QuoteOrderIntent("BTCUSDT", "BUY", Decimal("0.0002"), Decimal("60000.0")),
            )

    def test_live_arm_is_rejected(self):
        e = env()
        e["LIVE_TRADING_ENABLED"] = "true"
        with self.assertRaisesRegex(RuntimeError, "v5_requires_live_trading_disabled"):
            run_authenticated_quote_preflight(
                env=e, api_key="secret-not-logged", client=Client(),
                intent=QuoteOrderIntent("BTCUSDT", "BUY", Decimal("0.0002"), Decimal("60000.0")),
            )


if __name__ == "__main__":
    unittest.main()

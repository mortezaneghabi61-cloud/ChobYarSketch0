from __future__ import annotations

import unittest
from decimal import Decimal

from live_executor_stage22 import Stage22Error
from live_position_guardian_stage23 import Stage23Error, guard_once, read_guard_decision

GOOD_ENV = {
    "TRADING_MODE": "live",
    "LIVE_TRADING_ENABLED": "true",
    "LIVE_EXECUTION_ARMED": "true",
    "SYMBOL": "BTCUSDT",
    "LIVE_MAX_ORDER_USDT": "10",
    "SPOT_ONLY": "true",
    "WITHDRAWALS_ENABLED": "false",
    "LEVERAGE_ENABLED": "false",
    "MARGIN_ENABLED": "false",
    "FUTURES_ENABLED": "false",
    "OTC_ENABLED": "false",
    "MAX_POSITION_PCT": "0.25",
    "STOP_LOSS_PCT": "0.015",
    "TAKE_PROFIT_PCT": "0.03",
    "MAX_DAILY_LOSS_PCT": "0.03",
    "WALLEX_API_KEY": "secret-test-key",
}


class Resp:
    def __init__(self, status_code, payload):
        self.status_code = status_code
        self._payload = payload
    def json(self):
        return self._payload


class FakeClient:
    def __init__(self, *, last="100.00", bid="99.90", btc="0.099", open_orders=None, entry_status="FILLED"):
        self.last = last
        self.bid = bid
        self.btc = btc
        self.open_orders = [] if open_orders is None else open_orders
        self.entry_status = entry_status
        self.posts = []

    def get(self, path, **kwargs):
        if path == "/hector/web/v1/markets":
            return Resp(200, {"success": True, "result": {"markets": [{"symbol": "BTCUSDT", "quote_asset": "USDT", "is_spot": True}]}})
        if path.startswith("/v1/account/orders/"):
            return Resp(200, {"success": True, "result": {
                "symbol": "BTCUSDT", "side": "BUY", "status": self.entry_status,
                "executedPrice": "100.00", "executedQty": "0.10000000",
            }})
        if path == "/v1/account/openOrders":
            return Resp(200, {"success": True, "result": {"orders": self.open_orders}})
        if path == "/v1/account/balances":
            return Resp(200, {"success": True, "result": {"balances": {
                "BTC": {"value": self.btc, "locked": "0"},
                "USDT": {"value": "1", "locked": "0"},
            }}})
        if path == "/v1/markets":
            return Resp(200, {"success": True, "result": {"symbols": {"BTCUSDT": {
                "symbol": "BTCUSDT", "stepSize": 8, "tickSize": 2,
                "minNotional": "1", "maxNotional": "100000",
                "stats": {"lastPrice": self.last, "bidPrice": self.bid},
            }}}})
        raise AssertionError(path)

    def post(self, path, **kwargs):
        self.posts.append((path, kwargs))
        body = kwargs["json"]
        return Resp(201, {"success": True, "result": {
            "symbol": "BTCUSDT", "type": "LIMIT", "side": body["side"],
            "clientOrderId": body["client_id"],
        }})


class Stage23Tests(unittest.TestCase):
    def test_hold_does_not_submit(self):
        c = FakeClient(last="100.00", bid="99.90")
        out = guard_once(env=GOOD_ENV, entry_client_id="chobyar-entry-0001", client=c)
        self.assertEqual(out["state"], "HOLD")
        self.assertFalse(out["submitted"])
        self.assertEqual(c.posts, [])

    def test_stop_trigger_submits_one_sell(self):
        c = FakeClient(last="98.40", bid="98.35")
        out = guard_once(env=GOOD_ENV, entry_client_id="chobyar-entry-0001", client=c)
        self.assertEqual(out["state"], "STOP_TRIGGER")
        self.assertTrue(out["submitted"])
        self.assertEqual(len(c.posts), 1)
        body = c.posts[0][1]["json"]
        self.assertEqual(body["side"], "SELL")
        self.assertLessEqual(Decimal(body["price"]) * Decimal(body["quantity"]), Decimal("10"))

    def test_take_trigger_submits_one_sell(self):
        c = FakeClient(last="103.10", bid="103.00")
        out = guard_once(env=GOOD_ENV, entry_client_id="chobyar-entry-0001", client=c)
        self.assertEqual(out["state"], "TAKE_TRIGGER")
        self.assertTrue(out["submitted"])
        self.assertEqual(len(c.posts), 1)

    def test_open_order_blocks_new_exit(self):
        c = FakeClient(open_orders=[{"clientOrderId": "existing"}])
        out = guard_once(env=GOOD_ENV, entry_client_id="chobyar-entry-0001", client=c)
        self.assertEqual(out["state"], "EXIT_PENDING")
        self.assertFalse(out["submitted"])
        self.assertEqual(c.posts, [])

    def test_unfilled_entry_fails_closed(self):
        c = FakeClient(entry_status="NEW")
        with self.assertRaisesRegex(Stage23Error, "entry_must_be_filled"):
            read_guard_decision(env=GOOD_ENV, entry_client_id="chobyar-entry-0001", client=c)

    def test_nonspot_or_withdrawal_authority_rejected(self):
        for key in ("WITHDRAWALS_ENABLED", "MARGIN_ENABLED", "FUTURES_ENABLED", "OTC_ENABLED"):
            c = FakeClient()
            env = dict(GOOD_ENV, **{key: "true"})
            with self.assertRaises(Stage23Error):
                read_guard_decision(env=env, entry_client_id="chobyar-entry-0001", client=c)


if __name__ == "__main__":
    unittest.main()

from __future__ import annotations

import unittest
from decimal import Decimal

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
ENTRY_ID = "chobyar-entry-0001"


class Resp:
    def __init__(self, status_code, payload):
        self.status_code = status_code
        self._payload = payload
    def json(self):
        return self._payload


class FakeClient:
    def __init__(self, *, last="100.00", bid="99.90", btc="0.099", open_orders=None, entry_status="FILLED", entry_sum="9.90"):
        self.last = last
        self.bid = bid
        self.btc = btc
        self.open_orders = [] if open_orders is None else open_orders
        self.entry_status = entry_status
        self.entry_sum = entry_sum
        self.posts = []

    def get(self, path, **kwargs):
        if path == "/hector/web/v1/markets":
            return Resp(200, {"success": True, "result": {"markets": [{"symbol": "BTCUSDT", "quote_asset": "USDT", "is_spot": True}]}})
        if path == f"/v1/account/orders/{ENTRY_ID}":
            return Resp(200, {"success": True, "result": {
                "symbol": "BTCUSDT", "side": "BUY", "status": self.entry_status,
                "executedPrice": "100.00", "executedQty": "0.10000000", "executedSum": self.entry_sum,
            }})
        if path.startswith("/v1/account/orders/"):
            return Resp(404, {"success": False})
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
        out = guard_once(env=GOOD_ENV, entry_client_id=ENTRY_ID, client=c)
        self.assertEqual(out["state"], "HOLD")
        self.assertFalse(out["submitted"])
        self.assertEqual(c.posts, [])

    def test_stop_trigger_submits_one_sell(self):
        c = FakeClient(last="98.40", bid="98.35")
        out = guard_once(env=GOOD_ENV, entry_client_id=ENTRY_ID, client=c)
        self.assertEqual(out["state"], "STOP_TRIGGER")
        self.assertTrue(out["submitted"])
        self.assertEqual(len(c.posts), 1)
        self.assertEqual(c.posts[0][1]["json"]["side"], "SELL")

    def test_take_trigger_closes_full_position_even_if_exit_value_exceeds_10(self):
        c = FakeClient(last="103.10", bid="103.00", btc="0.099")
        out = guard_once(env=GOOD_ENV, entry_client_id=ENTRY_ID, client=c)
        self.assertEqual(out["state"], "TAKE_TRIGGER")
        self.assertTrue(out["submitted"])
        self.assertEqual(len(c.posts), 1)
        body = c.posts[0][1]["json"]
        self.assertEqual(body["side"], "SELL")
        self.assertEqual(Decimal(body["quantity"]), Decimal("0.09900000"))
        self.assertGreater(Decimal(body["price"]) * Decimal(body["quantity"]), Decimal("10"))

    def test_open_order_blocks_new_exit(self):
        c = FakeClient(open_orders=[{"clientOrderId": "existing"}])
        out = guard_once(env=GOOD_ENV, entry_client_id=ENTRY_ID, client=c)
        self.assertEqual(out["state"], "EXIT_PENDING")
        self.assertFalse(out["submitted"])
        self.assertEqual(c.posts, [])

    def test_unfilled_entry_fails_closed(self):
        c = FakeClient(entry_status="NEW")
        with self.assertRaisesRegex(Stage23Error, "entry_must_be_filled"):
            read_guard_decision(env=GOOD_ENV, entry_client_id=ENTRY_ID, client=c)

    def test_entry_above_10_usdt_is_not_eligible(self):
        c = FakeClient(entry_sum="10.01")
        with self.assertRaisesRegex(Stage23Error, "entry_exceeded_approved_10_usdt_cap"):
            read_guard_decision(env=GOOD_ENV, entry_client_id=ENTRY_ID, client=c)

    def test_unrelated_btc_cannot_be_sold(self):
        c = FakeClient(btc="0.10000001")
        with self.assertRaisesRegex(Stage23Error, "btc_balance_exceeds_entry_quantity"):
            read_guard_decision(env=GOOD_ENV, entry_client_id=ENTRY_ID, client=c)

    def test_nonspot_or_withdrawal_authority_rejected(self):
        for key in ("WITHDRAWALS_ENABLED", "MARGIN_ENABLED", "FUTURES_ENABLED", "OTC_ENABLED"):
            c = FakeClient()
            env = dict(GOOD_ENV, **{key: "true"})
            with self.assertRaises(Stage23Error):
                read_guard_decision(env=env, entry_client_id=ENTRY_ID, client=c)


if __name__ == "__main__":
    unittest.main()

from __future__ import annotations

import unittest
from decimal import Decimal

from live_executor_stage22 import LiveOrderIntent, Stage22Error, submit_one_live_limit_order, validate_live_env


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
    def __init__(self, existing=False, post_status=201):
        self.existing = existing
        self.post_status = post_status
        self.posts = []

    def get(self, path, **kwargs):
        if path == "/hector/web/v1/markets":
            return Resp(200, {"success": True, "result": {"markets": [{"symbol": "BTCUSDT", "quote_asset": "USDT", "is_spot": True}]}})
        if path == "/v1/markets":
            return Resp(200, {"success": True, "result": {"symbols": {"BTCUSDT": {"symbol": "BTCUSDT", "stepSize": 6, "tickSize": 2, "minNotional": "5", "maxNotional": "100000"}}}})
        if path == "/v1/account/openOrders":
            return Resp(200, {"success": True, "result": {"orders": ([{"x": 1}] if self.existing else [])}})
        if path.startswith("/v1/account/orders/"):
            return Resp(404, {"success": False})
        raise AssertionError(path)

    def post(self, path, **kwargs):
        self.posts.append((path, kwargs))
        body = kwargs["json"]
        return Resp(self.post_status, {
            "success": self.post_status == 201,
            "result": {
                "symbol": "BTCUSDT",
                "type": "LIMIT",
                "side": body["side"],
                "clientOrderId": "LIMIT-server-id",
            },
        })


class Stage22Tests(unittest.TestCase):
    def intent(self, notional10=True):
        return LiveOrderIntent("BUY", Decimal("0.000100"), Decimal("100000.00" if notional10 else "100001.00"), "chobyar-live-test-0001")

    def test_good_env(self):
        validate_live_env(GOOD_ENV)

    def test_requires_execution_arm(self):
        env = dict(GOOD_ENV, LIVE_EXECUTION_ARMED="false")
        with self.assertRaisesRegex(Stage22Error, "live_execution_arm_true_required"):
            validate_live_env(env)

    def test_withdrawal_and_nonspot_blocked(self):
        for key in ("WITHDRAWALS_ENABLED", "LEVERAGE_ENABLED", "MARGIN_ENABLED", "FUTURES_ENABLED", "OTC_ENABLED"):
            env = dict(GOOD_ENV, **{key: "true"})
            with self.assertRaises(Stage22Error):
                validate_live_env(env)

    def test_happy_path_posts_once(self):
        c = FakeClient()
        result = submit_one_live_limit_order(env=GOOD_ENV, intent=self.intent(), client=c)
        self.assertTrue(result["submitted"])
        self.assertEqual(len(c.posts), 1)
        self.assertEqual(c.posts[0][0], "/v1/account/orders")
        self.assertEqual(c.posts[0][1]["json"]["type"], "LIMIT")

    def test_hard_cap_blocks(self):
        c = FakeClient()
        with self.assertRaisesRegex(Stage22Error, "hard_cap_10_usdt_exceeded"):
            submit_one_live_limit_order(env=GOOD_ENV, intent=self.intent(False), client=c)
        self.assertEqual(c.posts, [])

    def test_existing_open_order_blocks(self):
        c = FakeClient(existing=True)
        with self.assertRaisesRegex(Stage22Error, "existing_open_order_blocks_submission"):
            submit_one_live_limit_order(env=GOOD_ENV, intent=self.intent(), client=c)
        self.assertEqual(c.posts, [])

    def test_post_must_return_201(self):
        c = FakeClient(post_status=200)
        with self.assertRaisesRegex(Stage22Error, "order_submit_http_200"):
            submit_one_live_limit_order(env=GOOD_ENV, intent=self.intent(), client=c)


if __name__ == "__main__":
    unittest.main()

from __future__ import annotations

import json
import tempfile
import unittest
from datetime import datetime, timezone
from decimal import Decimal
from pathlib import Path

import live_entry_risk_stage25
import live_executor_stage22
from live_adversarial_veto_stage26 import Stage26Error
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
    def __init__(self, existing=False, post_status=201, post_payload=None, *, usdt="90", btc="0"):
        self.existing = existing
        self.post_status = post_status
        self.post_payload = post_payload
        self.usdt = usdt
        self.btc = btc
        self.posts = []

    def get(self, path, **kwargs):
        if path == "/hector/web/v1/markets":
            return Resp(200, {"success": True, "result": {"markets": [{
                "symbol": "BTCUSDT", "quote_asset": "USDT", "is_spot": True
            }]}})
        if path == "/v1/markets":
            return Resp(200, {"success": True, "result": {"symbols": {"BTCUSDT": {
                "symbol": "BTCUSDT",
                "stepSize": 6,
                "tickSize": 2,
                "minNotional": "5",
                "maxNotional": "100000",
                "stats": {"bidPrice": "100000.00", "lastPrice": "100000.00"},
            }}}})
        if path == "/v1/account/balances":
            return Resp(200, {"success": True, "result": {"balances": {
                "USDT": {"value": self.usdt, "locked": "0"},
                "BTC": {"value": self.btc, "locked": "0"},
            }}})
        if path == "/v1/account/openOrders":
            return Resp(200, {"success": True, "result": {
                "orders": ([{"x": 1}] if self.existing else [])
            }})
        if path.startswith("/v1/account/orders/"):
            return Resp(404, {"success": False})
        raise AssertionError(path)

    def post(self, path, **kwargs):
        self.posts.append((path, kwargs))
        body = kwargs["json"]
        if self.post_payload is not None:
            return Resp(self.post_status, self.post_payload)
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
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.old_state = live_entry_risk_stage25.DEFAULT_STATE_PATH
        self.old_veto = live_executor_stage22.enforce_live_buy_veto
        self.old_risk = live_executor_stage22.enforce_buy_risk
        state = Path(self.tmp.name) / "risk.json"
        state.write_text(json.dumps({
            "version": 1,
            "utc_day": datetime.now(timezone.utc).date().isoformat(),
            "start_equity_usdt": "90",
        }))
        live_entry_risk_stage25.DEFAULT_STATE_PATH = state
        live_executor_stage22.enforce_live_buy_veto = lambda: {
            "allowed": True,
            "price_quorum": 3,
            "source_ids": ["coinbase", "kraken", "okx"],
        }

    def tearDown(self):
        live_entry_risk_stage25.DEFAULT_STATE_PATH = self.old_state
        live_executor_stage22.enforce_live_buy_veto = self.old_veto
        live_executor_stage22.enforce_buy_risk = self.old_risk
        self.tmp.cleanup()

    def intent(self, notional10=True, side="BUY"):
        return LiveOrderIntent(
            side,
            Decimal("0.000100"),
            Decimal("100000.00" if notional10 else "100001.00"),
            "chobyar-live-test-0001",
        )

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

    def test_happy_path_posts_once_after_stage26_and_stage25(self):
        c = FakeClient()
        result = submit_one_live_limit_order(env=GOOD_ENV, intent=self.intent(), client=c)
        self.assertTrue(result["submitted"])
        self.assertEqual(result["stage26_price_quorum"], 3)
        self.assertEqual(result["stage26_sources"], "coinbase,kraken,okx")
        self.assertEqual(result["max_new_buy_usdt"], "10")
        self.assertEqual(len(c.posts), 1)
        self.assertEqual(c.posts[0][0], "/v1/account/orders")
        self.assertEqual(c.posts[0][1]["json"]["type"], "LIMIT")

    def test_stage26_veto_blocks_before_stage25_and_before_post(self):
        def blocked():
            raise Stage26Error("stage26_blocked_local_global_divergence")

        def risk_must_not_run(**kwargs):
            raise AssertionError("Stage25 must not run after Stage26 veto")

        live_executor_stage22.enforce_live_buy_veto = blocked
        live_executor_stage22.enforce_buy_risk = risk_must_not_run
        c = FakeClient()
        with self.assertRaisesRegex(Stage22Error, "stage26_stage26_blocked_local_global_divergence"):
            submit_one_live_limit_order(env=GOOD_ENV, intent=self.intent(), client=c)
        self.assertEqual(c.posts, [])

    def test_stage26_missing_snapshot_blocks_buy(self):
        def blocked():
            raise Stage26Error("stage26_snapshot_missing")

        live_executor_stage22.enforce_live_buy_veto = blocked
        c = FakeClient()
        with self.assertRaisesRegex(Stage22Error, "stage26_stage26_snapshot_missing"):
            submit_one_live_limit_order(env=GOOD_ENV, intent=self.intent(), client=c)
        self.assertEqual(c.posts, [])

    def test_sell_bypasses_stage26_and_stage25_to_preserve_exit(self):
        def veto_must_not_run():
            raise AssertionError("Stage26 must not block SELL")

        def risk_must_not_run(**kwargs):
            raise AssertionError("Stage25 must not block SELL")

        live_executor_stage22.enforce_live_buy_veto = veto_must_not_run
        live_executor_stage22.enforce_buy_risk = risk_must_not_run
        c = FakeClient()
        result = submit_one_live_limit_order(env=GOOD_ENV, intent=self.intent(side="SELL"), client=c)
        self.assertTrue(result["submitted"])
        self.assertEqual(result["side"], "SELL")
        self.assertEqual(len(c.posts), 1)

    def test_stage25_blocks_buy_when_existing_position_is_over_25pct(self):
        c = FakeClient(usdt="60", btc="0.00030000")
        with self.assertRaisesRegex(Stage22Error, "stage25_buy_exceeds_equity_sized_budget"):
            submit_one_live_limit_order(env=GOOD_ENV, intent=self.intent(), client=c)
        self.assertEqual(c.posts, [])

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

    def test_422_exposes_only_whitelisted_validation_detail(self):
        c = FakeClient(post_status=422, post_payload={
            "success": False,
            "message": "validation failed",
            "errors": {"quantity": ["invalid precision"]},
            "secret": "must-not-appear",
        })
        with self.assertRaises(Stage22Error) as ctx:
            submit_one_live_limit_order(env=GOOD_ENV, intent=self.intent(), client=c)
        text = str(ctx.exception)
        self.assertIn("order_submit_http_422", text)
        self.assertIn("message=validation failed", text)
        self.assertIn("quantity", text)
        self.assertNotIn("must-not-appear", text)
        self.assertNotIn("secret-test-key", text)


if __name__ == "__main__":
    unittest.main()

from __future__ import annotations

import unittest
from decimal import Decimal

from quote_cap import STAGE7_MAX_QUOTE_CAP, evaluate_quote_cap


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
    "SYMBOL": "BTCUSDT",
    "LIVE_MAX_ORDER_USDT": "10",
}


class QuoteCapTests(unittest.TestCase):
    def test_btcusdt_uses_usdt_cap(self):
        result = evaluate_quote_cap(env=BASE, symbol="BTCUSDT", notional_quote=Decimal("9.5"))
        self.assertTrue(result.allowed)
        self.assertEqual(result.quote_asset, "USDT")
        self.assertEqual(result.cap_quote, Decimal("10"))

    def test_legacy_tmn_cap_cannot_authorize_usdt(self):
        env = dict(BASE)
        env.pop("LIVE_MAX_ORDER_USDT")
        env["LIVE_MAX_ORDER_TMN"] = "1"
        result = evaluate_quote_cap(env=env, symbol="BTCUSDT", notional_quote="0.5")
        self.assertFalse(result.allowed)
        self.assertEqual(result.reason, "quote_cap_required:LIVE_MAX_ORDER_USDT")

    def test_above_usdt_cap_denied(self):
        result = evaluate_quote_cap(env=BASE, symbol="BTCUSDT", notional_quote="10.00000001")
        self.assertFalse(result.allowed)
        self.assertEqual(result.reason, "above_quote_asset_cap")

    def test_unknown_symbol_fails_closed(self):
        env = dict(BASE)
        env["SYMBOL"] = "ETHUSDT"
        result = evaluate_quote_cap(env=env, symbol="ETHUSDT", notional_quote="1")
        self.assertFalse(result.allowed)
        self.assertEqual(result.reason, "symbol_quote_not_approved")

    def test_symbol_env_mismatch_fails_closed(self):
        env = dict(BASE)
        env["SYMBOL"] = "ETHUSDT"
        result = evaluate_quote_cap(env=env, symbol="BTCUSDT", notional_quote="1")
        self.assertFalse(result.allowed)
        self.assertEqual(result.reason, "symbol_env_mismatch")

    def test_quote_cap_has_stage_ceiling(self):
        env = dict(BASE)
        env["LIVE_MAX_ORDER_USDT"] = str(STAGE7_MAX_QUOTE_CAP + Decimal("0.01"))
        result = evaluate_quote_cap(env=env, symbol="BTCUSDT", notional_quote="1")
        self.assertFalse(result.allowed)
        self.assertEqual(result.reason, "quote_cap_exceeds_stage7_ceiling")

    def test_stage1_failure_blocks(self):
        env = dict(BASE)
        env["WITHDRAWALS_ENABLED"] = "true"
        result = evaluate_quote_cap(env=env, symbol="BTCUSDT", notional_quote="1")
        self.assertFalse(result.allowed)
        self.assertTrue(result.reason.startswith("stage1_blocked:"))

    def test_live_execution_remains_disabled(self):
        env = dict(BASE)
        env["LIVE_TRADING_ENABLED"] = "true"
        result = evaluate_quote_cap(env=env, symbol="BTCUSDT", notional_quote="1")
        self.assertFalse(result.allowed)

    def test_non_positive_notional_denied(self):
        result = evaluate_quote_cap(env=BASE, symbol="BTCUSDT", notional_quote="0")
        self.assertFalse(result.allowed)
        self.assertEqual(result.reason, "notional_quote_must_be_positive")


if __name__ == "__main__":
    unittest.main()

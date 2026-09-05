from __future__ import annotations

import unittest
from decimal import Decimal

from order_dryrun import MarketRules, OrderIntent, evaluate_order_dry_run


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

RULES = MarketRules(
    symbol="BTCUSDT",
    quantity_step=Decimal("0.000001"),
    price_tick=Decimal("1"),
    min_notional_tmn=Decimal("10"),
    max_notional_tmn=Decimal("1000000"),
)


def intent(**changes):
    data = {
        "symbol": "BTCUSDT",
        "side": "BUY",
        "quantity": Decimal("0.001000"),
        "limit_price_tmn": Decimal("50000"),
    }
    data.update(changes)
    return OrderIntent(**data)


class OrderDryRunTests(unittest.TestCase):
    def test_valid_dry_run_never_submits(self):
        result = evaluate_order_dry_run(env=BASE, intent=intent(), rules=RULES)
        self.assertTrue(result.allowed)
        self.assertEqual(result.reason, "stage3_dry_run_validated_no_submission")
        self.assertEqual(result.notional_tmn, Decimal("50.000000"))

    def test_stage1_failure_blocks(self):
        result = evaluate_order_dry_run(
            env={**BASE, "WITHDRAWALS_ENABLED": "true"}, intent=intent(), rules=RULES
        )
        self.assertFalse(result.allowed)
        self.assertIn("stage1_blocked", result.reason)

    def test_live_execution_flag_blocks(self):
        result = evaluate_order_dry_run(
            env={**BASE, "LIVE_TRADING_ENABLED": "true"}, intent=intent(), rules=RULES
        )
        self.assertFalse(result.allowed)

    def test_wrong_symbol_blocks(self):
        result = evaluate_order_dry_run(env=BASE, intent=intent(symbol="ETHUSDT"), rules=RULES)
        self.assertEqual(result.reason, "symbol_not_approved")

    def test_invalid_side_blocks(self):
        result = evaluate_order_dry_run(env=BASE, intent=intent(side="SHORT"), rules=RULES)
        self.assertEqual(result.reason, "invalid_side")

    def test_quantity_step_mismatch_blocks(self):
        result = evaluate_order_dry_run(
            env=BASE, intent=intent(quantity=Decimal("0.0010005")), rules=RULES
        )
        self.assertEqual(result.reason, "quantity_step_mismatch")

    def test_price_tick_mismatch_blocks(self):
        result = evaluate_order_dry_run(
            env=BASE, intent=intent(limit_price_tmn=Decimal("50000.5")), rules=RULES
        )
        self.assertEqual(result.reason, "price_tick_mismatch")

    def test_below_min_notional_blocks(self):
        result = evaluate_order_dry_run(
            env=BASE,
            intent=intent(quantity=Decimal("0.000001"), limit_price_tmn=Decimal("50000")),
            rules=RULES,
        )
        self.assertEqual(result.reason, "below_market_min_notional")

    def test_above_stage_cap_blocks(self):
        result = evaluate_order_dry_run(
            env=BASE,
            intent=intent(quantity=Decimal("3.000000"), limit_price_tmn=Decimal("50000")),
            rules=RULES,
        )
        self.assertEqual(result.reason, "above_stage_cap")

    def test_invalid_market_rules_fail_closed(self):
        bad = MarketRules(
            symbol="BTCUSDT",
            quantity_step=Decimal("0"),
            price_tick=Decimal("1"),
            min_notional_tmn=Decimal("10"),
            max_notional_tmn=Decimal("1000000"),
        )
        result = evaluate_order_dry_run(env=BASE, intent=intent(), rules=bad)
        self.assertEqual(result.reason, "market_rules_invalid")


if __name__ == "__main__":
    unittest.main()

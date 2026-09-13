from __future__ import annotations

import unittest

from trade_cohort_analysis import extract_trades, summarize


def cycle(ts: str, action: str, tape: int, score: float = 2.5):
    return {"ts": ts, "event": "cycle", "action": action, "executed": True, "score": score,
            "local_mid": 101, "spread_pct": 0.002, "global_change_24h": 0.04, "global_sources": ["kucoin", "gateio"],
            "agents": [{"agent": "momentum", "vote": 1}, {"agent": "order_book", "vote": 1},
                       {"agent": "tape_order_flow", "vote": tape}, {"agent": "global_trend", "vote": 1}]}


def priced_cycle(ts: str, price: float):
    row = cycle(ts, "HOLD", 1)
    row["local_mid"] = price
    return row


class TradeCohortAnalysisTests(unittest.TestCase):
    def test_pairs_events_with_immediately_following_executed_cycles(self):
        rows = [
            {"ts": "2026-09-03T15:51:43+00:00", "event": "paper_buy", "price": 100},
            cycle("2026-09-03T15:51:44+00:00", "BUY", -1),
            {"ts": "2026-09-03T20:20:43+00:00", "event": "paper_sell", "price": 102, "pnl": 0.012, "reason": "consensus exit"},
            cycle("2026-09-03T20:20:44+00:00", "SELL", 0, -2.1),
        ]
        trades, stats = extract_trades(rows)
        self.assertEqual(stats, {"unmatched_buys": 0, "unmatched_sells": 0, "cycle_misses": 0})
        self.assertEqual(len(trades), 1)
        self.assertTrue(trades[0].current_tape_gate_would_block)
        self.assertEqual(trades[0].exit_score, -2.1)
        self.assertAlmostEqual(trades[0].max_favorable_excursion_pct, 0.01)
        report = summarize(trades, stats)
        self.assertEqual(report["wins"]["trades"], 1)
        self.assertEqual(report["wins"]["vote_positive_counts"]["global_trend"], 1)
        self.assertFalse(report["execution_authority"])
        self.assertFalse(report["counterfactual_pnl_claim"])

    def test_fixed_protection_rules_report_observed_triggers_only(self):
        entry_cycle = cycle("2026-09-03T00:00:01Z", "BUY", 1)
        entry_cycle["local_mid"] = 100
        rows = [
            {"ts": "2026-09-03T00:00:00Z", "event": "paper_buy", "price": 100},
            entry_cycle,
            priced_cycle("2026-09-03T00:01:00Z", 100.6),
            priced_cycle("2026-09-03T00:02:00Z", 100.2),
            priced_cycle("2026-09-03T00:03:00Z", 99.9),
            {"ts": "2026-09-03T00:04:00Z", "event": "paper_sell", "price": 99.8, "pnl": -0.01},
            cycle("2026-09-03T00:04:01Z", "SELL", -1, -2.0),
        ]
        trades, stats = extract_trades(rows)
        observed = trades[0].protection_observations
        self.assertTrue(observed["trail_0_3pct_after_0_5pct"]["triggered"])
        self.assertAlmostEqual(observed["trail_0_3pct_after_0_5pct"]["observed_trigger_return_pct"], 0.002)
        self.assertTrue(observed["breakeven_after_0_5pct"]["triggered"])
        self.assertAlmostEqual(observed["breakeven_after_0_5pct"]["observed_trigger_return_pct"], -0.001)
        report = summarize(trades, stats)
        self.assertEqual(report["all"]["protection_trigger_counts"]["breakeven_after_0_5pct"], 1)

    def test_protection_never_arms_without_observed_half_percent_gain(self):
        entry_cycle = cycle("2026-09-03T00:00:01Z", "BUY", 1)
        entry_cycle["local_mid"] = 100
        rows = [
            {"ts": "2026-09-03T00:00:00Z", "event": "paper_buy", "price": 100},
            entry_cycle,
            priced_cycle("2026-09-03T00:01:00Z", 100.4),
            {"ts": "2026-09-03T00:02:00Z", "event": "paper_sell", "price": 99.8, "pnl": -0.01},
            cycle("2026-09-03T00:02:01Z", "SELL", -1, -2.0),
        ]
        trades, _ = extract_trades(rows)
        for observation in trades[0].protection_observations.values():
            self.assertFalse(observation["armed"])
            self.assertFalse(observation["triggered"])

    def test_mid_position_sell_is_excluded_and_reported(self):
        rows = [
            {"ts": "2026-09-03T13:51:42+00:00", "event": "paper_sell", "price": 99, "pnl": -0.01, "reason": "stop loss"},
            cycle("2026-09-03T13:51:43+00:00", "SELL", 0, -2.0),
        ]
        trades, stats = extract_trades(rows)
        self.assertEqual(trades, [])
        self.assertEqual(stats["unmatched_sells"], 1)

    def test_non_monotonic_input_fails_closed(self):
        with self.assertRaisesRegex(ValueError, "monotonic"):
            extract_trades([{"ts": "2026-09-03T00:00:02Z"}, {"ts": "2026-09-03T00:00:01Z"}])

    def test_stale_cycle_is_not_attached_to_buy(self):
        rows = [
            {"ts": "2026-09-03T00:00:00Z", "event": "paper_buy", "price": 100},
            cycle("2026-09-03T00:02:01Z", "BUY", 1),
        ]
        trades, stats = extract_trades(rows)
        self.assertEqual(trades, [])
        self.assertEqual(stats["cycle_misses"], 1)

    def test_open_position_is_reported_not_invented_as_closed(self):
        rows = [
            {"ts": "2026-09-03T00:00:00Z", "event": "paper_buy", "price": 100},
            cycle("2026-09-03T00:00:01Z", "BUY", 1),
        ]
        trades, stats = extract_trades(rows)
        self.assertEqual(trades, [])
        self.assertEqual(stats["unmatched_buys"], 1)


if __name__ == "__main__":
    unittest.main()

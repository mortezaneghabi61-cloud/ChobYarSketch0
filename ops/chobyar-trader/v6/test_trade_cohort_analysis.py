from __future__ import annotations

import unittest

from trade_cohort_analysis import extract_trades, summarize


def cycle(ts: str, action: str, tape: int, score: float = 2.5):
    return {"ts": ts, "event": "cycle", "action": action, "executed": True, "score": score,
            "spread_pct": 0.002, "global_change_24h": 0.04, "global_sources": ["kucoin", "gateio"],
            "agents": [{"agent": "momentum", "vote": 1}, {"agent": "order_book", "vote": 1},
                       {"agent": "tape_order_flow", "vote": tape}, {"agent": "global_trend", "vote": 1}]}


class TradeCohortAnalysisTests(unittest.TestCase):
    def test_pairs_events_with_immediately_following_executed_cycles(self):
        rows = [
            {"ts": "2026-09-03T15:51:43+00:00", "event": "paper_buy", "price": 100},
            cycle("2026-09-03T15:51:44+00:00", "BUY", -1),
            {"ts": "2026-09-03T20:20:43+00:00", "event": "paper_sell", "pnl": 0.012, "reason": "consensus exit"},
            cycle("2026-09-03T20:20:44+00:00", "SELL", 0, -2.1),
        ]
        trades, stats = extract_trades(rows)
        self.assertEqual(stats, {"unmatched_buys": 0, "unmatched_sells": 0, "cycle_misses": 0})
        self.assertEqual(len(trades), 1)
        self.assertTrue(trades[0].current_tape_gate_would_block)
        report = summarize(trades, stats)
        self.assertEqual(report["wins"]["trades"], 1)
        self.assertEqual(report["wins"]["vote_positive_counts"]["global_trend"], 1)
        self.assertFalse(report["execution_authority"])

    def test_mid_position_sell_is_excluded_and_reported(self):
        rows = [
            {"ts": "2026-09-03T13:51:42+00:00", "event": "paper_sell", "pnl": -0.01, "reason": "stop loss"},
            cycle("2026-09-03T13:51:43+00:00", "SELL", 0, -2.0),
        ]
        trades, stats = extract_trades(rows)
        self.assertEqual(trades, [])
        self.assertEqual(stats["unmatched_sells"], 1)

    def test_non_monotonic_input_fails_closed(self):
        with self.assertRaisesRegex(ValueError, "monotonic"):
            extract_trades([{"ts": "2026-09-03T00:00:02Z"}, {"ts": "2026-09-03T00:00:01Z"}])


if __name__ == "__main__":
    unittest.main()

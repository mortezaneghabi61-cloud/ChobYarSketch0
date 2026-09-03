from __future__ import annotations

import unittest
from datetime import datetime, timedelta, timezone

from agent_scorecard import compute_scorecard


class AgentScorecardTests(unittest.TestCase):
    def test_directional_accuracy_uses_future_price_without_wait_votes(self) -> None:
        start = datetime(2026, 1, 1, tzinfo=timezone.utc)
        rows = []
        # 60 samples at 15-minute spacing gives enough 1h/4h evidence.
        for i in range(60):
            ts = start + timedelta(minutes=15 * i)
            px = 100.0 + i
            rows.append({
                "ts": ts.isoformat(),
                "event": "cycle",
                "local_mid": px,
                "action": "WAIT",
                "agents": [
                    {"agent": "always_buy", "vote": 1, "available": True},
                    {"agent": "always_sell", "vote": -1, "available": True},
                    {"agent": "waiter", "vote": 0, "available": True},
                    {"agent": "offline", "vote": 1, "available": False},
                ],
            })
        score = compute_scorecard(rows)
        buy = score["agents"]["always_buy"]["horizons"]["1h"]
        sell = score["agents"]["always_sell"]["horizons"]["1h"]
        self.assertGreaterEqual(buy["samples"], 30)
        self.assertEqual(buy["hit_rate"], 1.0)
        self.assertEqual(sell["hit_rate"], 0.0)
        self.assertNotIn("waiter", score["agents"])
        self.assertNotIn("offline", score["agents"])
        self.assertFalse(score["weights_changed"])
        self.assertFalse(score["automatic_reweighting_enabled"])

    def test_duplicate_cycles_are_reduced_to_bucket_samples(self) -> None:
        start = datetime(2026, 1, 1, tzinfo=timezone.utc)
        rows = []
        for minute in (0, 1, 2, 15, 16, 30):
            rows.append({
                "ts": (start + timedelta(minutes=minute)).isoformat(),
                "event": "cycle",
                "local_mid": 100 + minute,
                "agents": [],
            })
        score = compute_scorecard(rows)
        self.assertEqual(score["sampled_rows"], 3)


if __name__ == "__main__":
    unittest.main()

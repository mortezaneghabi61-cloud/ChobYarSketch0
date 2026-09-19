import json
import os
import tempfile
import unittest
from pathlib import Path

os.environ.setdefault("TRADING_MODE", "paper")
os.environ.setdefault("LIVE_TRADING_ENABLED", "false")

from paper_exploration import (
    EXPLORATION_STRATEGY_VERSION,
    LOSS_COOLDOWN_SECONDS,
    LOSS_STREAK_COOLDOWN_SECONDS,
    append_events,
    initial_state,
    process_cycle,
)


def cycle(ts, score, mid=100.0, spread=0.0):
    return {"event": "cycle", "ts_epoch": ts, "score": score, "local_mid": mid, "spread_pct": spread}


class PaperExplorationTests(unittest.TestCase):
    def test_three_lanes_create_virtual_entries_without_execution_authority(self):
        state = initial_state()
        events = process_cycle(state, cycle(1, 0.3))
        self.assertEqual([e["lane"] for e in events], ["wide", "balanced", "selective"])
        self.assertTrue(all(e["event"] == "exploration_buy" for e in events))

    def test_wide_lane_explores_score_seen_in_wait_output(self):
        state = initial_state()
        events = process_cycle(state, cycle(1, -0.7))
        self.assertEqual([(e["event"], e["lane"]) for e in events], [("exploration_buy", "wide")])

    def test_stop_loss_closes_and_includes_both_fees(self):
        state = initial_state()
        process_cycle(state, cycle(1, 0.3))
        events = process_cycle(state, cycle(2, -0.5, mid=99.5))
        sells = [e for e in events if e["event"] == "exploration_sell"]
        self.assertEqual(len(sells), 3)
        self.assertTrue(all(e["reason"] == "stop_loss" and e["pnl"] < 0 for e in sells))

    def test_take_profit_and_max_hold_are_deterministic(self):
        state = initial_state()
        process_cycle(state, cycle(1, 0.3))
        self.assertTrue(all(e["reason"] == "take_profit" for e in process_cycle(state, cycle(2, 0.2, mid=100.7))))
        state = initial_state()
        process_cycle(state, cycle(1, 0.3))
        self.assertTrue(all(e["reason"] == "max_hold" for e in process_cycle(state, cycle(1801, 0.2))))

    def test_reentry_requires_a_fresh_threshold_crossing_after_max_hold(self):
        state = initial_state()
        process_cycle(state, cycle(1, 0.3))
        exits = process_cycle(state, cycle(1801, 0.3))
        self.assertEqual(
            [(event["event"], event["reason"]) for event in exits],
            [("exploration_sell", "max_hold")] * 3,
        )
        self.assertEqual(process_cycle(state, cycle(1802, 0.3)), [])
        self.assertEqual(process_cycle(state, cycle(1803, -1.0)), [])
        self.assertEqual(process_cycle(state, cycle(1804, 0.3)), [])
        self.assertEqual(process_cycle(state, cycle(1801 + LOSS_COOLDOWN_SECONDS, -1.0)), [])
        reentries = process_cycle(state, cycle(1802 + LOSS_COOLDOWN_SECONDS, 0.3))
        self.assertEqual(
            [(event["event"], event["lane"]) for event in reentries],
            [
                ("exploration_buy", "wide"),
                ("exploration_buy", "balanced"),
                ("exploration_buy", "selective"),
            ],
        )

    def test_loss_exit_starts_cooldown_before_next_entry(self):
        state = initial_state()
        process_cycle(state, cycle(1, 0.3))
        exits = process_cycle(state, cycle(2, -0.5, mid=99.5))

        self.assertEqual([event["reason"] for event in exits], ["stop_loss", "stop_loss", "stop_loss"])
        self.assertTrue(all(event["cooldown_until"] > 2 for event in exits))
        self.assertEqual(process_cycle(state, cycle(3, -1.0)), [])
        self.assertEqual(process_cycle(state, cycle(4, 0.3)), [])

    def test_repeated_losses_extend_the_cooldown(self):
        state = initial_state()
        process_cycle(state, cycle(1, 0.3))
        process_cycle(state, cycle(2, -0.5, mid=99.5))
        process_cycle(state, cycle(3603, -1.0))
        process_cycle(state, cycle(3604, 0.3))
        exits = process_cycle(state, cycle(3605, -0.5, mid=99.0))

        self.assertEqual([event["loss_streak"] for event in exits], [2, 2, 2])
        self.assertTrue(all(event["cooldown_until"] == 3605 + LOSS_STREAK_COOLDOWN_SECONDS for event in exits))
        self.assertEqual(process_cycle(state, cycle(3606, -1.0)), [])
        self.assertEqual(process_cycle(state, cycle(3607, 0.3)), [])

    def test_appended_events_include_strategy_version(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "events.jsonl"
            append_events(path, [{"event": "exploration_buy", "lane": "wide"}])

            record = json.loads(path.read_text(encoding="utf-8"))
        self.assertEqual(record["strategy_version"], EXPLORATION_STRATEGY_VERSION)
        self.assertFalse(record["execution_authority"])
        self.assertFalse(record["automatic_promotion"])

    def test_legacy_state_without_last_score_waits_for_fresh_history(self):
        state = initial_state()
        state.pop("last_score")
        state["last_ts"] = 100

        self.assertEqual(process_cycle(state, cycle(101, 0.3)), [])
        self.assertEqual(process_cycle(state, cycle(102, -1.0)), [])
        reentries = process_cycle(state, cycle(103, 0.3))
        self.assertEqual(
            [(event["event"], event["lane"]) for event in reentries],
            [
                ("exploration_buy", "wide"),
                ("exploration_buy", "balanced"),
                ("exploration_buy", "selective"),
            ],
        )

    def test_duplicate_or_invalid_cycles_do_not_trade(self):
        state = initial_state()
        process_cycle(state, cycle(2, 0.3))
        self.assertEqual(process_cycle(state, cycle(2, 10)), [])
        self.assertEqual(process_cycle(state, {"event": "cycle", "ts_epoch": 3, "score": 10}), [])


if __name__ == "__main__":
    unittest.main()

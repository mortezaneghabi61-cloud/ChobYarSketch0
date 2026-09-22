import importlib
import json
import os
import sys
import tempfile
import types
import unittest
from pathlib import Path
from unittest.mock import patch


class ParentHandler:
    def do_GET(self):
        self.parent_called = True


class StatusV60Tests(unittest.TestCase):
    def setUp(self):
        base = types.SimpleNamespace(
            Handler=ParentHandler,
            public_report_payload=lambda: {"existing": True},
            read_auth_env=lambda: {"STATUS_PORT": "8787"},
            ThreadingHTTPServer=object,
        )
        parent = types.ModuleType("status_server_v53")
        parent.base = base
        parent.public_report_payload = base.public_report_payload
        sys.modules["status_server_v53"] = parent
        sys.modules.pop("status_server_v60", None)
        self.module = importlib.import_module("status_server_v60")

    def tearDown(self):
        sys.modules.pop("status_server_v53", None)
        sys.modules.pop("status_server_v60", None)

    def test_projection_separates_three_lanes_and_counts_completed_sells(self):
        with tempfile.TemporaryDirectory() as tmp:
            state = Path(tmp) / "state.json"
            log = Path(tmp) / "events.jsonl"
            state.write_text(json.dumps({"last_ts": 1000, "lanes": {
                "wide": {"threshold": -.75, "cash": 9.8, "quantity": 0, "cooldown_until": 1100, "loss_streak": 1, "last_exit_reason": "stop_loss"},
                "balanced": {"threshold": 0, "cash": 10.1, "quantity": .01},
                "selective": {"threshold": .25, "cash": 10, "quantity": 0},
            }}))
            log.write_text("\n".join([
                json.dumps({"event":"exploration_sell","lane":"wide","pnl":-.1,"strategy_version":"v624-final-paper-candidate"}),
                json.dumps({"event":"exploration_sell","lane":"wide","pnl":.2}),
                json.dumps({"event":"exploration_buy","lane":"balanced"}),
            ]))
            self.module.STATE_FILE, self.module.LOG_FILE = state, log
            with patch.object(self.module.time, "time", return_value=1050), patch.object(self.module, "_service_active", return_value=True), patch.object(self.module, "_latest_mark_price", return_value=100.0):
                result = self.module.public_exploration_projection()
            self.assertTrue(result["ok"])
            self.assertFalse(result["stale"])
            self.assertEqual(result["total_completed_trades"], 2)
            self.assertEqual(result["current_strategy_completed_trades"], 1)
            self.assertEqual(result["lanes"]["wide"]["wins"], 1)
            self.assertEqual(result["lanes"]["wide"]["losses"], 1)
            self.assertEqual(result["lanes"]["wide"]["current_wins"], 0)
            self.assertEqual(result["lanes"]["wide"]["current_losses"], 1)
            self.assertEqual(result["lanes"]["wide"]["cooldown_remaining_seconds"], 50)
            self.assertEqual(result["lanes"]["wide"]["loss_streak"], 1)
            self.assertEqual(result["lanes"]["wide"]["last_exit_reason"], "stop_loss")
            self.assertTrue(result["lanes"]["balanced"]["position_open"])
            self.assertAlmostEqual(result["lanes"]["balanced"]["equity"], 11.1)
            self.assertAlmostEqual(result["lanes"]["balanced"]["return_pct"], 11.0)
            self.assertFalse(result["live_readiness"]["ready"])
            self.assertEqual(result["live_readiness"]["trades"], 1)
            self.assertFalse(result["live_readiness"]["checks"]["minimum_sample"])

    def test_open_position_without_mark_price_does_not_report_cash_as_return(self):
        with tempfile.TemporaryDirectory() as tmp:
            state = Path(tmp) / "state.json"
            log = Path(tmp) / "events.jsonl"
            state.write_text(json.dumps({"last_ts": 1000, "lanes": {
                "wide": {"threshold": -.75, "cash": 7.5, "quantity": .025},
                "balanced": {"threshold": 0, "cash": 10, "quantity": 0},
                "selective": {"threshold": .25, "cash": 10, "quantity": 0},
            }}))
            log.write_text("")
            self.module.STATE_FILE, self.module.LOG_FILE = state, log
            with patch.object(self.module, "_latest_mark_price", return_value=None), patch.object(self.module, "_service_active", return_value=True):
                result = self.module.public_exploration_projection()
        self.assertIsNone(result["lanes"]["wide"]["equity"])
        self.assertIsNone(result["lanes"]["wide"]["return_pct"])

    def test_missing_or_malformed_state_fails_closed_without_leaking(self):
        with tempfile.TemporaryDirectory() as tmp:
            self.module.STATE_FILE = Path(tmp) / "missing"
            self.module.LOG_FILE = Path(tmp) / "missing-log"
            with patch.object(self.module, "_service_active", return_value=False):
                result = self.module.public_exploration_projection()
        self.assertFalse(result["ok"])
        self.assertFalse(result["execution_authority"])
        self.assertNotIn("error", result)

    def test_existing_report_is_preserved_and_versioned(self):
        with patch.object(self.module, "public_exploration_projection", return_value={"ok": True}):
            result = self.module.public_report_payload()
        self.assertTrue(result["existing"])
        self.assertEqual(result["report_version"], 8)
        self.assertEqual(result["paper_exploration"], {"ok": True})

    def test_server_rendered_monitor_contains_three_lanes_without_script(self):
        data = {"ok": True, "service_active": True, "stale": False, "strategy_version": "v624-final-paper-candidate", "total_completed_trades": 3, "current_strategy_completed_trades": 1, "lanes": {
            "wide": {"return_pct": -1, "equity": 9.9, "cash": 7.4, "completed_trades": 1, "wins": 0, "losses": 1, "current_wins": 0, "current_losses": 1, "cooldown_remaining_seconds": 10, "position_open": True},
            "balanced": {"return_pct": 1, "equity": 10.1, "cash": 10.1, "completed_trades": 1, "wins": 1, "losses": 0, "current_wins": 0, "current_losses": 0, "cooldown_remaining_seconds": 0, "position_open": False},
            "selective": {"return_pct": None, "equity": None, "cash": 7.5, "completed_trades": 1, "wins": 0, "losses": 1, "current_wins": 0, "current_losses": 0, "cooldown_remaining_seconds": 0, "position_open": True},
        }}
        with patch.object(self.module, "public_exploration_projection", return_value=data):
            page = self.module.exploration_html().decode()
        self.assertIn("معاملات آزمایشی سریع", page)
        self.assertIn("گسترده", page)
        self.assertIn("متعادل", page)
        self.assertIn("انتخابی", page)
        self.assertIn("v624-final-paper-candidate", page)
        self.assertIn("ترمز ضرر", page)
        self.assertIn("نسخه جدید", page)
        self.assertNotIn("<script", page)


if __name__ == "__main__":
    unittest.main()

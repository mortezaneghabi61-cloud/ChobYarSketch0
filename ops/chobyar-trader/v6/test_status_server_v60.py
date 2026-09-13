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
                "wide": {"threshold": -.75, "cash": 9.8, "quantity": 0},
                "balanced": {"threshold": 0, "cash": 10.1, "quantity": .01},
                "selective": {"threshold": .25, "cash": 10, "quantity": 0},
            }}))
            log.write_text("\n".join([
                json.dumps({"event":"exploration_sell","lane":"wide","pnl":-.1}),
                json.dumps({"event":"exploration_sell","lane":"wide","pnl":.2}),
                json.dumps({"event":"exploration_buy","lane":"balanced"}),
            ]))
            self.module.STATE_FILE, self.module.LOG_FILE = state, log
            with patch.object(self.module.time, "time", return_value=1050), patch.object(self.module, "_service_active", return_value=True):
                result = self.module.public_exploration_projection()
            self.assertTrue(result["ok"])
            self.assertFalse(result["stale"])
            self.assertEqual(result["total_completed_trades"], 2)
            self.assertEqual(result["lanes"]["wide"]["wins"], 1)
            self.assertEqual(result["lanes"]["wide"]["losses"], 1)
            self.assertTrue(result["lanes"]["balanced"]["position_open"])

    def test_missing_or_malformed_state_fails_closed_without_leaking(self):
        with tempfile.TemporaryDirectory() as tmp:
            self.module.STATE_FILE = Path(tmp) / "missing"
            self.module.LOG_FILE = Path(tmp) / "missing-log"
            result = self.module.public_exploration_projection()
        self.assertFalse(result["ok"])
        self.assertFalse(result["execution_authority"])
        self.assertNotIn("error", result)

    def test_existing_report_is_preserved_and_versioned(self):
        with patch.object(self.module, "public_exploration_projection", return_value={"ok": True}):
            result = self.module.public_report_payload()
        self.assertTrue(result["existing"])
        self.assertEqual(result["report_version"], 7)
        self.assertEqual(result["paper_exploration"], {"ok": True})


if __name__ == "__main__":
    unittest.main()

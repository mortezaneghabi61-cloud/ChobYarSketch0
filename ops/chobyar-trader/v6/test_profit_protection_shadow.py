from __future__ import annotations

import json
import os
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

os.environ.setdefault("TRADING_MODE", "paper")
os.environ.setdefault("LIVE_TRADING_ENABLED", "false")

from profit_protection_shadow import ShadowTracker, fetch_public_mid, read_position


class Response:
    def raise_for_status(self):
        return None

    def json(self):
        return {"success": True, "result": {"bid": [["100", "1"]], "ask": [["102", "1"]]}}


class GetOnlyClient:
    def get(self, path, *, params):
        if path != "/v1/depth" or params != {"symbol": "BTCUSDT"}:
            raise AssertionError("unexpected public request")
        return Response()


class ProfitProtectionShadowTests(unittest.TestCase):
    def test_reads_position_without_modifying_state(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "state.json"
            original = json.dumps({"btc_qty": 0.01, "entry_price": 100})
            path.write_text(original, encoding="utf-8")
            self.assertEqual(read_position(path), (100.0, 0.01))
            self.assertEqual(path.read_text(encoding="utf-8"), original)

    def test_invalid_open_position_fails_closed(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "state.json"
            path.write_text('{"btc_qty":1,"entry_price":null}', encoding="utf-8")
            with self.assertRaises(ValueError):
                read_position(path)

    def test_public_market_client_uses_get_only(self):
        self.assertEqual(fetch_public_mid(GetOnlyClient()), (100.0, 102.0, 101.0))

    def test_tracker_observes_breakeven_and_trailing_without_execution(self):
        tracker = ShadowTracker()
        armed = tracker.observe((100.0, 0.01), (100.6, 100.8, 100.7))
        crossed = tracker.observe((100.0, 0.01), (100.2, 100.4, 100.3))
        self.assertTrue(armed.breakeven_armed)
        self.assertTrue(crossed.trailing_crossed)
        self.assertFalse(crossed.breakeven_crossed)
        self.assertFalse(crossed.execution_authority)
        self.assertFalse(crossed.automatic_promotion)

    def test_tracker_resets_when_position_closes_or_entry_changes(self):
        tracker = ShadowTracker()
        tracker.observe((100.0, 0.01), (101.0, 101.2, 101.1))
        self.assertFalse(tracker.observe(None, None).position_open)
        changed = tracker.observe((200.0, 0.02), (200.2, 200.4, 200.3))
        self.assertAlmostEqual(changed.peak_return_pct, 0.001)


if __name__ == "__main__":
    unittest.main()

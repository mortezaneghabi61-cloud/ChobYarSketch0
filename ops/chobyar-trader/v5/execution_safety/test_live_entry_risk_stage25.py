from __future__ import annotations

import json
import tempfile
import unittest
from datetime import datetime, timezone
from decimal import Decimal
from pathlib import Path

from live_entry_risk_stage25 import Stage25Error, evaluate_buy_risk, initialize_daily_baseline

GOOD_ENV = {
    "MAX_POSITION_PCT": "0.25",
    "MAX_DAILY_LOSS_PCT": "0.03",
}
NOW = datetime(2026, 9, 6, 10, 0, tzinfo=timezone.utc)


class Resp:
    def __init__(self, status_code, payload):
        self.status_code = status_code
        self._payload = payload

    def json(self):
        return self._payload


class FakeClient:
    def __init__(self, *, usdt="80", usdt_locked="0", btc="0.001", btc_locked="0", bid="10000"):
        self.usdt = usdt
        self.usdt_locked = usdt_locked
        self.btc = btc
        self.btc_locked = btc_locked
        self.bid = bid

    def get(self, path, **kwargs):
        if path == "/v1/account/balances":
            return Resp(200, {"success": True, "result": {"balances": {
                "USDT": {"value": self.usdt, "locked": self.usdt_locked},
                "BTC": {"value": self.btc, "locked": self.btc_locked},
            }}})
        if path == "/v1/markets":
            return Resp(200, {"success": True, "result": {"symbols": {"BTCUSDT": {
                "stats": {"bidPrice": self.bid}
            }}}})
        raise AssertionError(path)


def write_state(path: Path, start="100", day="2026-09-06"):
    path.write_text(json.dumps({"version": 1, "utc_day": day, "start_equity_usdt": start}))


class Stage25Tests(unittest.TestCase):
    def test_equity_sizing_allows_within_25pct_total_position(self):
        with tempfile.TemporaryDirectory() as td:
            state = Path(td) / "risk.json"
            write_state(state, start="100")
            # Equity = 90, position = 10, max position = 22.5, remaining = 12.5.
            out = evaluate_buy_risk(
                env=GOOD_ENV,
                intended_notional=Decimal("10"),
                client=FakeClient(),
                headers={"X-API-Key": "test"},
                state_path=state,
                now=NOW,
            )
            self.assertEqual(out.equity_usdt, Decimal("90"))
            self.assertEqual(out.max_position_usdt, Decimal("22.50"))
            self.assertEqual(out.max_new_buy_usdt, Decimal("10"))

    def test_existing_oversized_position_blocks_new_buy(self):
        with tempfile.TemporaryDirectory() as td:
            state = Path(td) / "risk.json"
            write_state(state, start="100")
            # Equity = 100; BTC position = 30 > 25% equity.
            c = FakeClient(usdt="70", btc="0.003", bid="10000")
            with self.assertRaisesRegex(Stage25Error, "buy_exceeds_equity_sized_budget"):
                evaluate_buy_risk(env=GOOD_ENV, intended_notional=Decimal("1"), client=c, headers={}, state_path=state, now=NOW)

    def test_daily_drawdown_at_three_percent_blocks(self):
        with tempfile.TemporaryDirectory() as td:
            state = Path(td) / "risk.json"
            write_state(state, start="100")
            # Equity = 97 exactly => 3% drawdown.
            c = FakeClient(usdt="87", btc="0.001", bid="10000")
            with self.assertRaisesRegex(Stage25Error, "daily_loss_limit_reached"):
                evaluate_buy_risk(env=GOOD_ENV, intended_notional=Decimal("1"), client=c, headers={}, state_path=state, now=NOW)

    def test_missing_baseline_fails_closed(self):
        with tempfile.TemporaryDirectory() as td:
            state = Path(td) / "missing.json"
            with self.assertRaisesRegex(Stage25Error, "daily_baseline_required"):
                evaluate_buy_risk(env=GOOD_ENV, intended_notional=Decimal("1"), client=FakeClient(), headers={}, state_path=state, now=NOW)

    def test_stale_baseline_fails_closed(self):
        with tempfile.TemporaryDirectory() as td:
            state = Path(td) / "risk.json"
            write_state(state, start="100", day="2026-09-05")
            with self.assertRaisesRegex(Stage25Error, "daily_baseline_stale"):
                evaluate_buy_risk(env=GOOD_ENV, intended_notional=Decimal("1"), client=FakeClient(), headers={}, state_path=state, now=NOW)

    def test_available_usdt_is_also_a_hard_budget(self):
        with tempfile.TemporaryDirectory() as td:
            state = Path(td) / "risk.json"
            write_state(state, start="10")
            c = FakeClient(usdt="1", btc="0", bid="10000")
            with self.assertRaisesRegex(Stage25Error, "buy_exceeds_equity_sized_budget"):
                evaluate_buy_risk(env=GOOD_ENV, intended_notional=Decimal("1.01"), client=c, headers={}, state_path=state, now=NOW)

    def test_baseline_initialization_is_local_state_only(self):
        with tempfile.TemporaryDirectory() as td:
            state = Path(td) / "risk.json"
            out = initialize_daily_baseline(client=FakeClient(), headers={}, state_path=state, now=NOW)
            self.assertEqual(out["utc_day"], "2026-09-06")
            self.assertTrue(state.exists())
            self.assertEqual(state.stat().st_mode & 0o777, 0o600)
            saved = json.loads(state.read_text())
            self.assertEqual(saved["scope"], "BTCUSDT_BOOK_ONLY")


if __name__ == "__main__":
    unittest.main()

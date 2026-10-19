"""Regression fences for historical PAPER ledger accounting. Zero exchange access."""
from __future__ import annotations

import unittest
from paper_ledger_audit import audit_rows


def row(ts, event, **fields):
    return {"ts": f"2026-09-03T{ts}Z", "event": event, **fields}


class PaperLedgerAuditTests(unittest.TestCase):
    def test_both_fees_subtracted_exactly_once(self):
        result = audit_rows([
            row("12:00:00", "paper_buy", price=100, qty=1, fee=0.2),
            row("12:10:00", "paper_sell", price=101, qty=1, fee=0.202, pnl=0.598),
        ])
        self.assertTrue(result["ok"])
        self.assertAlmostEqual(result["reconstructed_net_pnl"], .598)
        self.assertAlmostEqual(result["both_side_fees"], .402)
        self.assertEqual(result["pnl_mismatches"], [])
        self.assertEqual(result["reconstructed_wins"], 1)

    def test_missing_entry_fee_in_legacy_report_detected(self):
        result = audit_rows([
            row("12:00:00", "paper_buy", price=100, qty=1, fee=.2),
            row("12:10:00", "paper_sell", price=101, qty=1, fee=.202, pnl=.798),
        ])
        self.assertFalse(result["ok"])
        self.assertAlmostEqual(result["pnl_mismatches"][0]["delta"], .2)
        self.assertAlmostEqual(result["reconstructed_net_pnl"], .598)

    def test_regression_first_real_paper_trade_sign_changes(self):
        result = audit_rows([
            row("12:32:34", "paper_buy", price=78049.99, qty=3.203075362341494e-05, fee=.005),
            row("13:04:34", "paper_sell", price=78311.18, qty=3.203075362341494e-05,
                fee=.005016732225077799, pnl=.0033493803138213707),
        ])
        self.assertFalse(result["ok"])
        self.assertEqual(result["reported_wins"], 1)
        self.assertEqual(result["reconstructed_wins"], 0)
        self.assertAlmostEqual(result["reconstructed_net_pnl"], -.0016506196861786293)
        self.assertAlmostEqual(result["pnl_mismatches"][0]["delta"], .005)

    def test_reset_after_startup_is_reported_not_merged(self):
        result = audit_rows([
            row("12:00:00", "startup"),
            row("12:01:00", "cycle", realized_pnl=0, equity=10),
            row("12:02:00", "paper_buy", price=100, qty=1, fee=.2),
            row("12:03:00", "paper_sell", price=101, qty=1, fee=.202, pnl=.598),
            row("12:03:01", "cycle", realized_pnl=.598, equity=10.598),
            row("13:51:00", "startup"),
            row("13:52:00", "cycle", realized_pnl=0, equity=10),
        ])
        self.assertEqual(len(result["restart_state_discontinuities"]), 1)
        self.assertFalse(result["ok"])
        self.assertEqual(result["latest_cycle"]["reported_realized_pnl"], 0.0)

    def test_preserved_cycle_state_after_restart_no_fake_reset(self):
        result = audit_rows([
            row("12:00:00", "cycle", realized_pnl=-.1, equity=9.9),
            row("13:00:00", "startup"),
            row("13:01:00", "cycle", realized_pnl=-.1, equity=9.9),
        ])
        self.assertEqual(result["restart_state_discontinuities"], [])

    def test_quantity_mismatch_fails_closed(self):
        with self.assertRaisesRegex(ValueError, "quantity differs"):
            audit_rows([row("12:00:00", "paper_buy", price=100, qty=1, fee=.2),
                       row("12:01:00", "paper_sell", price=101, qty=.5, fee=.1, pnl=.3)])

    def test_negative_or_missing_fee_fails_closed(self):
        for missing in (None, -1, "not-a-number", float("nan")):
            with self.subTest(fee=missing):
                with self.assertRaisesRegex(ValueError, "fee"):
                    audit_rows([row("12:00:00", "paper_buy", price=100, qty=1, fee=missing)])

    def test_unmatched_sell_fails_closed(self):
        with self.assertRaisesRegex(ValueError, "without matching"):
            audit_rows([row("12:00:00", "paper_sell", price=100, qty=1, fee=.2, pnl=0)])

    def test_multiple_buys_fails_closed(self):
        with self.assertRaisesRegex(ValueError, "overlapping"):
            audit_rows([row("12:00:00", "paper_buy", price=100, qty=1, fee=.2),
                        row("12:01:00", "paper_buy", price=100, qty=1, fee=.2)])

    def test_open_position_not_declared_accounting_ok(self):
        r = audit_rows([row("12:00:00", "paper_buy", price=100, qty=1, fee=.2)])
        self.assertFalse(r["ok"])
        self.assertTrue(r["open_position_unverified"])

    def test_reject_nonmonotonic_or_naive_times(self):
        with self.assertRaisesRegex(ValueError, "non-monotonic"):
            audit_rows([row("12:01:00", "cycle"), row("12:00:00", "cycle")])
        with self.assertRaisesRegex(ValueError, "timezone-naive"):
            audit_rows([{"ts": "2026-09-03T12:00:00", "event": "cycle"}])

    def test_trade_without_cycle_confirmation_is_still_transaction(self):
        r = audit_rows([row("12:00:00", "paper_buy", price=100, qty=1, fee=.2),
                        row("12:01:00", "paper_sell", price=100, qty=1, fee=.2, pnl=-.4)])
        self.assertTrue(r["ok"])
        self.assertEqual(r["closed_trades"], 1)
        self.assertAlmostEqual(r["reconstructed_net_pnl"], -.4)


if __name__ == "__main__":
    unittest.main()

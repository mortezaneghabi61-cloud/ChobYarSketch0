from __future__ import annotations

import unittest
from pathlib import Path
import sys
import types
from unittest import mock

import council_evidence

sys.modules.setdefault("httpx", types.SimpleNamespace(Client=object))

import shadow_runner_v52 as wrapper
from specialist_council import CouncilContext

from test_council_evidence import candles, scorecard, source_cycle


def context() -> CouncilContext:
    rows = candles()
    return CouncilContext(
        candles=rows,
        local_mid=float(rows[-1][4]),
        spread_pct=0.0005,
        book_imbalance=0.22,
        tape_buy_ratio=0.62,
        global_change_24h=0.01,
        global_dispersion_pct=0.001,
        global_source_count=2,
        funding_rate=0.0001,
        funding_z=0.2,
        oi_change_pct=0.006,
        breadth_24h={"BTC-USDT": 0.02, "ETH-USDT": 0.018, "SOL-USDT": 0.025},
    )


class ShadowEvidenceWiringTests(unittest.TestCase):
    def test_exact_selected_cycle_and_context_are_recorded_before_return(self) -> None:
        app_dir = Path("/tmp/chobyar-test")
        ctx = context()
        cycle = source_cycle(ctx)
        card = scorecard()
        evaluated_at = float(ctx.candles[-1][0]) + 60.0
        original_council = wrapper.base.run_council
        original_read_cycle = wrapper.base.read_last_cycle
        restore_sources = mock.Mock()

        def fake_base_run(_app_dir: Path):
            selected = wrapper.base.read_last_cycle(Path("unused"))
            self.assertIs(selected, cycle)
            return wrapper.base.run_council(ctx)

        with (
            mock.patch.object(wrapper, "read_scorecard", return_value=card),
            mock.patch.object(wrapper.public_source_fallbacks, "install", return_value=restore_sources),
            mock.patch.object(wrapper.base, "read_last_cycle", return_value=cycle) as read_cycle,
            mock.patch.object(wrapper.base, "run", side_effect=fake_base_run),
            mock.patch.object(wrapper.time, "time", return_value=evaluated_at),
            mock.patch.object(wrapper.council_evidence, "record_evaluation") as record,
        ):
            result = wrapper.run(app_dir)
            self.assertIs(wrapper.base.run_council, original_council)
            self.assertIs(wrapper.base.read_last_cycle, read_cycle)

        restore_sources.assert_called_once_with()
        record.assert_called_once()
        args = record.call_args.args
        self.assertEqual(args[0], app_dir / "logs" / council_evidence.DEFAULT_JOURNAL_NAME)
        self.assertIs(args[1], ctx)
        self.assertEqual(args[2], cycle)
        self.assertEqual(args[3], card)
        self.assertEqual(args[4], evaluated_at)
        self.assertIs(args[5], result)
        self.assertIs(wrapper.base.read_last_cycle, original_read_cycle)

    def test_evidence_failure_propagates_and_restores_all_hooks(self) -> None:
        app_dir = Path("/tmp/chobyar-test")
        ctx = context()
        cycle = source_cycle(ctx)
        original_council = wrapper.base.run_council
        original_read_cycle = wrapper.base.read_last_cycle
        restore_sources = mock.Mock()

        def fake_base_run(_app_dir: Path):
            wrapper.base.read_last_cycle(Path("unused"))
            return wrapper.base.run_council(ctx)

        with (
            mock.patch.object(wrapper, "read_scorecard", return_value=scorecard()),
            mock.patch.object(wrapper.public_source_fallbacks, "install", return_value=restore_sources),
            mock.patch.object(wrapper.base, "read_last_cycle", return_value=cycle),
            mock.patch.object(wrapper.base, "run", side_effect=fake_base_run),
            mock.patch.object(
                wrapper.council_evidence,
                "record_evaluation",
                side_effect=council_evidence.EvidenceError("write rejected"),
            ),
        ):
            with self.assertRaises(council_evidence.EvidenceError):
                wrapper.run(app_dir)
            self.assertIs(wrapper.base.run_council, original_council)

        restore_sources.assert_called_once_with()
        self.assertIs(wrapper.base.read_last_cycle, original_read_cycle)


if __name__ == "__main__":
    unittest.main()

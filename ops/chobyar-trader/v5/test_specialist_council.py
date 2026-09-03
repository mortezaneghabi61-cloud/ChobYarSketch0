from __future__ import annotations

import math
import unittest

from specialist_council import CouncilContext, run_council


def candles(kind: str, n: int = 100) -> list[list[float]]:
    rows: list[list[float]] = []
    previous = 100.0
    for i in range(n):
        if kind == "up":
            close = 100.0 * (1.0015 ** i)
        elif kind == "down":
            close = 100.0 * (0.9985 ** i)
        else:
            close = 100.0 + math.sin(i * 0.9) * 0.35
        opened = previous
        high = max(opened, close) * 1.001
        low = min(opened, close) * 0.999
        rows.append([1_700_000_000 + i * 3600, opened, high, low, close, 10.0 + i])
        previous = close
    if kind == "shock":
        rows = candles("flat", n)
        opened = rows[-2][4]
        close = opened * 1.04
        rows[-1] = [rows[-1][0], opened, close * 1.001, opened * 0.999, close, rows[-1][5]]
    return rows


def context(kind: str = "up", **kwargs) -> CouncilContext:
    data = {
        "candles": candles(kind),
        "local_mid": 100.0,
        "spread_pct": 0.0005,
        "book_imbalance": 0.18,
        "tape_buy_ratio": 0.62,
        "global_change_24h": 0.02,
        "global_dispersion_pct": 0.002,
        "global_source_count": 2,
        "funding_rate": 0.0001,
        "funding_z": 0.2,
        "oi_change_pct": 0.01,
        "breadth_24h": {"BTC-USDT": 0.02, "ETH-USDT": 0.018, "SOL-USDT": 0.025},
    }
    data.update(kwargs)
    return CouncilContext(**data)


class SpecialistCouncilTests(unittest.TestCase):
    def test_trend_council_is_shadow_only(self) -> None:
        report = run_council(context("up"))
        self.assertEqual(report["mode"], "shadow_observation_only")
        self.assertFalse(report["execution_authority"])
        self.assertFalse(report["foreign_execution_enabled"])
        self.assertFalse(report["geo_bypass_supported"])
        self.assertFalse(report["automatic_promotion_enabled"])
        self.assertEqual(len(report["specialists"]), 5)
        by_name = {row["agent"]: row for row in report["specialists"]}
        self.assertEqual(by_name["regime_structure"]["vote"], 1)
        self.assertEqual(by_name["derivatives_positioning"]["vote"], 1)

    def test_microstructure_conflict_abstains(self) -> None:
        report = run_council(context("up", book_imbalance=0.22, tape_buy_ratio=0.35))
        micro = next(row for row in report["specialists"] if row["agent"] == "microstructure_liquidity")
        self.assertEqual(micro["vote"], 0)
        self.assertTrue(micro["features"]["conflict"])

    def test_missing_derivatives_data_is_unavailable_not_guessed(self) -> None:
        report = run_council(context("up", funding_rate=None, funding_z=None, oi_change_pct=None))
        derivative = next(row for row in report["specialists"] if row["agent"] == "derivatives_positioning")
        self.assertFalse(derivative["available"])
        self.assertEqual(derivative["vote"], 0)

    def test_shock_regime_triggers_risk_veto(self) -> None:
        report = run_council(context("shock"))
        self.assertEqual(report["regime"]["label"], "SHOCK")
        risk = next(row for row in report["specialists"] if row["agent"] == "adversarial_risk")
        self.assertTrue(risk["veto"])
        self.assertEqual(report["shadow_consensus"]["action"], "WAIT")

    def test_extreme_funding_without_clean_trend_is_contrarian_shadow_vote(self) -> None:
        report = run_council(context("flat", funding_rate=0.001, funding_z=3.1, oi_change_pct=0.0))
        derivative = next(row for row in report["specialists"] if row["agent"] == "derivatives_positioning")
        self.assertEqual(derivative["vote"], -1)


if __name__ == "__main__":
    unittest.main()

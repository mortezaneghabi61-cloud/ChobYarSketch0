from __future__ import annotations

import unittest
from datetime import datetime, timedelta, timezone

from specialist_scorecard import compute


class SpecialistScorecardTests(unittest.TestCase):
    def test_regime_specific_evidence_threshold(self) -> None:
        start = datetime(2026, 1, 1, tzinfo=timezone.utc)
        rows = []
        for i in range(60):
            rows.append({
                "mode": "shadow_observation_only",
                "generated_at_utc": (start + timedelta(minutes=15 * i)).isoformat(),
                "local_mid": 100.0 + i * 0.2,
                "regime": {"label": "TREND_UP"},
                "specialists": [
                    {"agent": "regime_structure", "vote": 1, "available": True},
                    {"agent": "adversarial_risk", "vote": 0, "available": True},
                ],
                "shadow_consensus": {"action": "BUY"},
            })
        report = compute(rows)
        self.assertFalse(report["automatic_promotion_enabled"])
        self.assertFalse(report["execution_authority"])
        self.assertIn("regime_structure", report["reviewable_specialists"])
        trend = report["specialists"]["regime_structure"]["regimes"]["TREND_UP"]
        self.assertTrue(trend["horizons"]["4h"]["sufficient"])
        self.assertEqual(trend["horizons"]["4h"]["hit_rate"], 1.0)

    def test_small_sample_stays_observation_only(self) -> None:
        start = datetime(2026, 1, 1, tzinfo=timezone.utc)
        rows = []
        for i in range(20):
            rows.append({
                "mode": "shadow_observation_only",
                "generated_at_utc": (start + timedelta(minutes=15 * i)).isoformat(),
                "local_mid": 100.0 + i * 0.1,
                "regime": {"label": "RANGE"},
                "specialists": [{"agent": "microstructure_liquidity", "vote": 1, "available": True}],
                "shadow_consensus": {"action": "WAIT"},
            })
        report = compute(rows)
        self.assertFalse(report["ready_for_manual_promotion_review"])
        self.assertEqual(report["reviewable_specialists"], [])


if __name__ == "__main__":
    unittest.main()

from __future__ import annotations

import unittest

from meta_intelligence import (
    calibrate_specialists,
    data_integrity,
    enhance_council,
    epistemic_uncertainty,
    execution_stress,
)
from specialist_council import CouncilContext


def candles(count: int = 100, start: float = 1_700_000_000.0) -> list[list[float]]:
    rows = []
    price = 80_000.0
    for i in range(count):
        opened = price
        close = opened * 1.001
        high = max(opened, close) * 1.001
        low = min(opened, close) * 0.999
        rows.append([start + i * 3600.0, opened, high, low, close, 100.0])
        price = close
    return rows


def context(**overrides) -> CouncilContext:
    rows = candles()
    values = dict(
        candles=rows,
        local_mid=float(rows[-1][4]),
        spread_pct=0.0005,
        book_imbalance=0.22,
        tape_buy_ratio=0.62,
        global_change_24h=0.01,
        global_dispersion_pct=0.001,
        global_source_count=3,
        funding_rate=0.0001,
        funding_z=0.2,
        oi_change_pct=0.006,
        breadth_24h={"BTC-USDT": 0.02, "ETH-USDT": 0.018, "SOL-USDT": 0.025},
    )
    values.update(overrides)
    return CouncilContext(**values)


def council(votes=(1, 1, 1, 1), confidences=(0.9, 0.9, 0.8, 0.7)):
    names = ("regime_structure", "microstructure_liquidity", "derivatives_positioning", "cross_market_breadth")
    specialists = [
        {"agent": name, "vote": vote, "confidence": conf, "available": True, "veto": False, "reason": "test", "features": {}}
        for name, vote, conf in zip(names, votes, confidences)
    ]
    specialists.append({"agent": "adversarial_risk", "vote": 0, "confidence": 0.2, "available": True, "veto": False, "reason": "clear", "features": {}})
    return {
        "ok": True,
        "mode": "shadow_observation_only",
        "execution_authority": False,
        "automatic_promotion_enabled": False,
        "automatic_reweighting_enabled": False,
        "foreign_execution_enabled": False,
        "geo_bypass_supported": False,
        "regime": {"label": "TREND_UP", "confidence": 0.9, "atr14_pct": 0.005},
        "specialists": specialists,
        "shadow_consensus": {"action": "BUY", "score": 3.3, "available_directional_specialists": 4, "risk_veto": False},
    }


def strong_scorecard():
    names = ("regime_structure", "microstructure_liquidity", "derivatives_positioning", "cross_market_breadth", "adversarial_risk")
    return {
        "specialists": {
            name: {"regimes": {"ALL": {"horizons": {"4h": {"samples": 30, "hit_rate": 1.0}}}}}
            for name in names
        }
    }


class MetaIntelligenceTests(unittest.TestCase):
    def test_confidence_is_never_inflated_without_evidence(self):
        raw = council()["specialists"]
        calibrated = calibrate_specialists(raw, {})
        for before, after in zip(raw, calibrated):
            self.assertLessEqual(after["confidence"], before["confidence"])
            self.assertAlmostEqual(after["confidence"], before["confidence"] * 0.5)

    def test_full_clean_evidence_can_preserve_raw_confidence(self):
        raw = council()["specialists"]
        calibrated = calibrate_specialists(raw, strong_scorecard())
        for before, after in zip(raw, calibrated):
            self.assertAlmostEqual(after["confidence"], before["confidence"])

    def test_balanced_disagreement_is_high_uncertainty(self):
        items = council(votes=(1, 1, -1, -1), confidences=(0.8, 0.8, 0.8, 0.8))["specialists"]
        calibrated = calibrate_specialists(items, strong_scorecard())
        uncertainty = epistemic_uncertainty(calibrated)
        self.assertGreaterEqual(uncertainty["score"], 0.65)

    def test_stale_market_data_fails_closed(self):
        ctx = context()
        stale_now = float(ctx.candles[-1][0]) + 10 * 3600
        integrity = data_integrity(ctx, now_ts=stale_now)
        self.assertFalse(integrity["healthy"])
        self.assertIn("hourly_candle_integrity", integrity["flags"])

    def test_execution_stress_detects_bad_market_conditions(self):
        ctx = context(spread_pct=0.01, global_dispersion_pct=0.03)
        stress = execution_stress(ctx, {"atr14_pct": 0.03})
        self.assertGreaterEqual(stress["score"], 0.8)

    def test_meta_layer_can_only_downgrade_to_wait(self):
        ctx = context(spread_pct=0.01, global_dispersion_pct=0.03)
        now = float(ctx.candles[-1][0]) + 1800
        out = enhance_council(ctx, council(), strong_scorecard(), now_ts=now)
        self.assertEqual(out["shadow_consensus"]["pre_meta_action"], "BUY")
        self.assertEqual(out["shadow_consensus"]["action"], "WAIT")
        self.assertTrue(out["shadow_consensus"]["meta_hold"])
        self.assertIn("execution_stress", out["shadow_consensus"]["meta_hold_reasons"])
        self.assertFalse(out["execution_authority"])
        self.assertFalse(out["automatic_promotion_enabled"])
        self.assertFalse(out["foreign_execution_enabled"])
        self.assertFalse(out["geo_bypass_supported"])


if __name__ == "__main__":
    unittest.main()

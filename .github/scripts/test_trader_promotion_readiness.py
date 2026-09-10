#!/usr/bin/env python3
from __future__ import annotations

import importlib.util
from pathlib import Path
import unittest

MODULE = Path(__file__).with_name("trader_promotion_readiness.py")
spec = importlib.util.spec_from_file_location("promotion", MODULE)
promotion = importlib.util.module_from_spec(spec)
assert spec and spec.loader
spec.loader.exec_module(promotion)


def ready_report():
    good_stat = {"samples": 60, "sufficient": True, "average_signed_return": 0.002, "hit_rate": 0.55}
    return {
        "ok": True,
        "public_report": True,
        "mode": "paper",
        "live_locked": True,
        "services": {"trader": "active", "status": "active", "v5_shadow_timer": "active", "v5_scorecard_timer": "active"},
        "forward_test": {"closed_trades": 40, "current_equity": 10.3, "realized_pnl": 0.3, "max_drawdown_pct": 0.02, "uptime_seconds": 15 * 86400},
        "backtest": {"ok": True, "full_fidelity_multiagent": True, "closed_trades": 100, "history_days": 120, "return_pct": 0.08, "max_drawdown_pct": 0.06},
        "v5_meta": {"data_integrity": {"healthy": True, "score": 0.95}},
        "v5_shadow": {
            "mode": "shadow_observation_only",
            "execution_authority": False,
            "automatic_promotion_enabled": False,
            "automatic_reweighting_enabled": False,
            "foreign_execution_enabled": False,
            "geo_bypass_supported": False,
            "source_health": {
                "breadth_source": "kucoin", "funding_source": "okx", "open_interest_source": "kucoin",
                "resolved_breadth_symbols": ["BTC-USDT", "ETH-USDT", "SOL-USDT"],
                "resolved_funding_samples": 12, "oi_change_available": True,
            },
        },
        "v5_specialist_scorecard": {
            "execution_authority": False,
            "specialists": {
                "a": {"horizons": {"4h": dict(good_stat), "12h": dict(good_stat)}},
                "b": {"horizons": {"4h": dict(good_stat), "12h": dict(good_stat)}},
                "specialist_council_shadow": {"horizons": {"4h": dict(good_stat), "12h": dict(good_stat)}},
            },
        },
    }


class PromotionReadinessTest(unittest.TestCase):
    def test_good_evidence_is_reviewable_but_never_grants_authority(self):
        result = promotion.evaluate(ready_report())
        self.assertTrue(result["ready_for_v5_paper_execution_review"])
        self.assertFalse(result["execution_authority_granted"])
        self.assertFalse(result["live_authority_granted"])
        self.assertFalse(result["automatic_promotion"])

    def test_current_style_weak_evidence_fails_closed(self):
        report = ready_report()
        report["forward_test"].update(closed_trades=6, realized_pnl=-0.099, uptime_seconds=7 * 86400)
        report["backtest"].update(full_fidelity_multiagent=False, closed_trades=44, history_days=89.95, return_pct=-0.031)
        report["v5_meta"]["data_integrity"]["score"] = 0.85
        report["v5_shadow"]["source_health"] = {"errors": ["okx_breadth:HTTPStatusError"]}
        result = promotion.evaluate(report)
        self.assertFalse(result["ready_for_v5_paper_execution_review"])
        self.assertIn("BACKTEST_NOT_FULL_FIDELITY_MULTIAGENT", result["reasons"])
        self.assertIn("BREADTH_FALLBACK_NOT_PROVEN_ACTIVE", result["reasons"])
        self.assertIn("FORWARD_EXPECTANCY_NOT_POSITIVE", result["reasons"])

    def test_live_or_geo_bypass_can_never_pass(self):
        report = ready_report()
        report["live_locked"] = False
        report["v5_shadow"]["geo_bypass_supported"] = True
        result = promotion.evaluate(report)
        self.assertFalse(result["ready_for_v5_paper_execution_review"])
        self.assertIn("LIVE_NOT_LOCKED", result["reasons"])
        self.assertIn("GEO_BYPASS_PRESENT", result["reasons"])


if __name__ == "__main__":
    unittest.main()

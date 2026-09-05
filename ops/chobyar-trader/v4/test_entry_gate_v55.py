from __future__ import annotations

import json
import tempfile
import unittest
from datetime import datetime, timezone
from pathlib import Path

from entry_gate_v55 import evaluate_entry_gate


NOW = 1_788_551_000.0


def iso(ts: float) -> str:
    return datetime.fromtimestamp(ts, tz=timezone.utc).isoformat()


def report(**overrides):
    value = {
        "ok": True,
        "mode": "shadow_observation_only",
        "execution_authority": False,
        "generated_at_utc": iso(NOW - 120),
        "regime": {"label": "TREND_UP", "atr14_pct": 0.012},
        "specialists": [
            {"agent": "regime_structure", "available": True},
            {"agent": "microstructure_liquidity", "available": True},
            {"agent": "derivatives_positioning", "available": True},
            {"agent": "cross_market_breadth", "available": True},
            {"agent": "adversarial_risk", "available": True},
        ],
        "shadow_consensus": {
            "action": "BUY",
            "pre_meta_action": "BUY",
            "score": 1.55,
            "available_directional_specialists": 4,
            "risk_veto": False,
            "meta_hold": False,
            "meta_hold_reasons": [],
        },
        "meta_intelligence": {
            "data_integrity": {"healthy": True, "score": 0.85},
            "execution_stress": {"score": 0.20},
            "epistemic_uncertainty": {"score": 0.25},
            "decision_fragility": {"fragile": False},
        },
    }
    for key, val in overrides.items():
        if key == "consensus":
            value["shadow_consensus"].update(val)
        elif key == "meta":
            for subkey, subval in val.items():
                if isinstance(subval, dict) and isinstance(value["meta_intelligence"].get(subkey), dict):
                    value["meta_intelligence"][subkey].update(subval)
                else:
                    value["meta_intelligence"][subkey] = subval
        else:
            value[key] = val
    return value


def votes(tape: int = 1):
    return [
        {"agent": "momentum", "vote": 1, "available": True},
        {"agent": "order_book", "vote": 1, "available": True},
        {"agent": "tape_order_flow", "vote": tape, "available": True},
    ]


class EntryGateV55Test(unittest.TestCase):
    def evaluate(self, payload, *, tape=1):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "v5_shadow_latest.json"
            if payload is not None:
                path.write_text(json.dumps(payload), encoding="utf-8")
            return evaluate_entry_gate(path, votes(tape), now_ts=NOW)

    def test_fresh_healthy_v5_buy_allows_entry(self):
        result = self.evaluate(report())
        self.assertTrue(result.allowed)
        self.assertEqual(result.reason, "v5_confirmed_buy")
        self.assertEqual(result.v5_action, "BUY")

    def test_v5_wait_pre_meta_blocks_entry(self):
        result = self.evaluate(report(consensus={"action": "WAIT", "pre_meta_action": "WAIT"}))
        self.assertFalse(result.allowed)
        self.assertEqual(result.reason, "v5_pre_meta_action_not_buy")

    def test_v5_final_wait_blocks_entry(self):
        result = self.evaluate(report(consensus={"action": "WAIT", "pre_meta_action": "BUY"}))
        self.assertFalse(result.allowed)
        self.assertEqual(result.reason, "v5_final_action_not_buy")

    def test_stale_report_blocks_entry(self):
        result = self.evaluate(report(generated_at_utc=iso(NOW - 900)))
        self.assertFalse(result.allowed)
        self.assertEqual(result.reason, "v5_report_stale")

    def test_meta_hold_blocks_entry(self):
        result = self.evaluate(report(consensus={"meta_hold": True, "meta_hold_reasons": ["execution_stress"]}))
        self.assertFalse(result.allowed)
        self.assertEqual(result.reason, "v5_meta_hold")

    def test_risk_veto_blocks_entry(self):
        result = self.evaluate(report(consensus={"risk_veto": True}))
        self.assertFalse(result.allowed)
        self.assertEqual(result.reason, "v5_risk_veto")

    def test_unhealthy_data_blocks_entry(self):
        result = self.evaluate(report(meta={"data_integrity": {"healthy": False, "score": 0.4}}))
        self.assertFalse(result.allowed)
        self.assertEqual(result.reason, "v5_data_integrity_unhealthy")

    def test_fragile_decision_blocks_entry(self):
        result = self.evaluate(report(meta={"decision_fragility": {"fragile": True}}))
        self.assertFalse(result.allowed)
        self.assertEqual(result.reason, "v5_decision_fragile")

    def test_explicit_tape_sell_blocks_entry(self):
        result = self.evaluate(report(), tape=-1)
        self.assertFalse(result.allowed)
        self.assertEqual(result.reason, "v4_tape_conflicts_with_buy")

    def test_missing_report_blocks_entry(self):
        result = self.evaluate(None)
        self.assertFalse(result.allowed)
        self.assertEqual(result.reason, "v5_report_unavailable")

    def test_execution_authority_must_remain_false(self):
        result = self.evaluate(report(execution_authority=True))
        self.assertFalse(result.allowed)
        self.assertEqual(result.reason, "v5_execution_authority_violation")


if __name__ == "__main__":
    unittest.main()

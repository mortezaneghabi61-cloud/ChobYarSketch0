from __future__ import annotations

import copy
import unittest
from datetime import datetime, timedelta, timezone

from trader_telemetry_contract import TelemetryContractError, validate_report


NOW = datetime(2026, 9, 12, 10, 30, tzinfo=timezone.utc)


def report() -> dict:
    return {
        "ok": True,
        "public_report": True,
        "report_version": 6,
        "mode": "paper",
        "live_locked": True,
        "decision": {"signal": "WAIT", "action": "WAIT", "executed": False},
        "services": {"v5_shadow_timer": "active"},
        "v5_shadow": {
            "mode": "shadow_observation_only",
            "execution_authority": False,
            "automatic_promotion_enabled": False,
            "automatic_reweighting_enabled": False,
            "foreign_execution_enabled": False,
            "geo_bypass_supported": False,
            "generated_at_utc": (NOW - timedelta(minutes=5)).isoformat(),
        },
    }


class TraderTelemetryContractTests(unittest.TestCase):
    def test_fresh_observation_only_heartbeat_passes(self) -> None:
        summary = validate_report(report(), now=NOW)
        self.assertEqual(summary["shadow_age_seconds"], 300.0)
        self.assertFalse(summary["execution_authority"])
        self.assertFalse(summary["automatic_promotion"])

    def test_shadow_heartbeat_over_ten_minutes_is_stale(self) -> None:
        value = report()
        value["v5_shadow"]["generated_at_utc"] = (
            NOW - timedelta(seconds=601)
        ).isoformat()
        with self.assertRaisesRegex(TelemetryContractError, "heartbeat is stale"):
            validate_report(value, now=NOW)

    def test_excessive_future_clock_skew_fails_closed(self) -> None:
        value = report()
        value["v5_shadow"]["generated_at_utc"] = (
            NOW + timedelta(seconds=61)
        ).isoformat()
        with self.assertRaisesRegex(TelemetryContractError, "too far in the future"):
            validate_report(value, now=NOW)

    def test_inactive_timer_fails_closed(self) -> None:
        value = report()
        value["services"]["v5_shadow_timer"] = "inactive"
        with self.assertRaisesRegex(TelemetryContractError, "timer is not active"):
            validate_report(value, now=NOW)

    def test_any_shadow_authority_fails_closed(self) -> None:
        for key in (
            "execution_authority",
            "automatic_promotion_enabled",
            "automatic_reweighting_enabled",
            "foreign_execution_enabled",
            "geo_bypass_supported",
        ):
            with self.subTest(key=key):
                value = report()
                value["v5_shadow"][key] = True
                with self.assertRaisesRegex(TelemetryContractError, "authority lock failed"):
                    validate_report(value, now=NOW)

    def test_sensitive_or_forbidden_public_sections_are_rejected(self) -> None:
        sensitive = report()
        sensitive["v5_shadow"]["nested"] = {"api_key": "must-not-appear"}
        with self.assertRaisesRegex(TelemetryContractError, "sensitive key"):
            validate_report(sensitive, now=NOW)

        forbidden = copy.deepcopy(report())
        forbidden["risk"] = {}
        with self.assertRaisesRegex(TelemetryContractError, "forbidden top-level"):
            validate_report(forbidden, now=NOW)


if __name__ == "__main__":
    unittest.main()

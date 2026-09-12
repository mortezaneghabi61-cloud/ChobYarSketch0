from __future__ import annotations

import argparse
import json
import math
import sys
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


MAX_SHADOW_AGE_SECONDS = 600.0
MAX_FUTURE_SKEW_SECONDS = 60.0
FORBIDDEN_TOP_LEVEL = {"paper", "market", "risk", "security"}
SENSITIVE_MARKERS = (
    "secret",
    "token",
    "password",
    "passwd",
    "api_key",
    "apikey",
    "authorization",
    "cookie",
)


class TelemetryContractError(ValueError):
    pass


def require(condition: bool, message: str) -> None:
    if not condition:
        raise TelemetryContractError(message)


def parse_utc(value: Any, field: str) -> datetime:
    require(isinstance(value, str) and 1 <= len(value) <= 64, f"{field} is malformed")
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError as exc:
        raise TelemetryContractError(f"{field} is malformed") from exc
    require(parsed.tzinfo is not None, f"{field} lacks timezone")
    return parsed.astimezone(timezone.utc)


def reject_sensitive_keys(value: Any, path: str = "$") -> None:
    if isinstance(value, dict):
        for key, child in value.items():
            lower = str(key).lower()
            require(
                not any(marker in lower for marker in SENSITIVE_MARKERS),
                f"sensitive key at {path}.{key}",
            )
            reject_sensitive_keys(child, f"{path}.{key}")
    elif isinstance(value, list):
        for index, child in enumerate(value):
            reject_sensitive_keys(child, f"{path}[{index}]")


def validate_report(report: Any, *, now: datetime | None = None) -> dict[str, Any]:
    require(isinstance(report, dict), "public report must be an object")
    reject_sensitive_keys(report)
    require(report.get("ok") is True, "public report is not healthy")
    require(report.get("public_report") is True, "public report marker is missing")
    require(report.get("mode") == "paper", "paper mode lock failed")
    require(report.get("live_locked") is True, "live lock failed")
    require(not (FORBIDDEN_TOP_LEVEL & set(report)), "forbidden top-level section exposed")

    shadow = report.get("v5_shadow")
    require(isinstance(shadow, dict), "v5 shadow report is missing")
    require(shadow.get("mode") == "shadow_observation_only", "shadow mode lock failed")
    for key in (
        "execution_authority",
        "automatic_promotion_enabled",
        "automatic_reweighting_enabled",
        "foreign_execution_enabled",
        "geo_bypass_supported",
    ):
        require(shadow.get(key) is False, f"shadow authority lock failed: {key}")

    services = report.get("services")
    require(isinstance(services, dict), "service state is missing")
    require(services.get("v5_shadow_timer") == "active", "shadow timer is not active")

    current = now or datetime.now(timezone.utc)
    require(current.tzinfo is not None, "validation clock lacks timezone")
    current = current.astimezone(timezone.utc)
    generated = parse_utc(shadow.get("generated_at_utc"), "v5_shadow.generated_at_utc")
    age = (current - generated).total_seconds()
    require(math.isfinite(age), "shadow age is not finite")
    require(age >= -MAX_FUTURE_SKEW_SECONDS, "shadow timestamp is too far in the future")
    require(age <= MAX_SHADOW_AGE_SECONDS, "shadow evidence heartbeat is stale")

    return {
        "mode": "public_observation_only_telemetry",
        "shadow_generated_at_utc": generated.isoformat(),
        "shadow_age_seconds": age,
        "shadow_timer": "active",
        "execution_authority": False,
        "automatic_promotion": False,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description="Validate sanitized ChobYar Trader telemetry")
    parser.add_argument("report", type=Path)
    args = parser.parse_args()
    try:
        report = json.loads(args.report.read_text(encoding="utf-8"))
        summary = validate_report(report)
    except (OSError, json.JSONDecodeError, TelemetryContractError) as exc:
        print(f"TELEMETRY_STATUS=FAIL:{exc}", file=sys.stderr)
        return 2
    print("TELEMETRY_STATUS=PASS")
    print(f"SHADOW_GENERATED_AT_UTC={summary['shadow_generated_at_utc']}")
    print(f"SHADOW_AGE_SECONDS={summary['shadow_age_seconds']:.3f}")
    print("SHADOW_TIMER=ACTIVE")
    print("EXECUTION_AUTHORITY=NONE")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

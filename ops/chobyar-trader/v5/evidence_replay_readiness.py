#!/usr/bin/env python3
from __future__ import annotations

import argparse
import fcntl
import json
import math
import os
import re
import stat
from collections.abc import Callable, Iterable, Mapping
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Any

import council_evidence


MIN_DATA_INTEGRITY_SCORE = 0.90
REQUIRED_BREADTH = frozenset(("BTC-USDT", "ETH-USDT", "SOL-USDT"))
SHA256_RE = re.compile(r"^[0-9a-f]{64}$")
ALLOWED_ACTIONS = frozenset(("BUY", "SELL", "WAIT"))


class ReadinessError(RuntimeError):
    pass


@dataclass(frozen=True)
class ReadinessPolicy:
    minimum_history_days: float = 90.0
    target_interval_seconds: float = 300.0
    interval_tolerance_seconds: float = 60.0
    maximum_gap_seconds: float = 600.0
    minimum_coverage_ratio: float = 0.95
    minimum_quality_ratio: float = 0.95

    def validate(self) -> None:
        values = asdict(self)
        if any(type(value) not in (int, float) or not math.isfinite(value) for value in values.values()):
            raise ReadinessError("readiness policy values must be finite numbers")
        if self.minimum_history_days < 0:
            raise ReadinessError("minimum history cannot be negative")
        if self.target_interval_seconds <= 0:
            raise ReadinessError("target interval must be positive")
        if not 0 <= self.interval_tolerance_seconds <= self.target_interval_seconds:
            raise ReadinessError("interval tolerance is invalid")
        if self.maximum_gap_seconds < self.target_interval_seconds + self.interval_tolerance_seconds:
            raise ReadinessError("maximum gap cannot be shorter than the target interval")
        if not 0 < self.minimum_coverage_ratio <= 1:
            raise ReadinessError("minimum coverage ratio is invalid")
        if not 0 < self.minimum_quality_ratio <= 1:
            raise ReadinessError("minimum quality ratio is invalid")


def _number(value: Any, label: str) -> float:
    if isinstance(value, bool):
        raise ReadinessError(f"{label} must be numeric")
    try:
        result = float(value)
    except (TypeError, ValueError) as exc:
        raise ReadinessError(f"{label} must be numeric") from exc
    if not math.isfinite(result):
        raise ReadinessError(f"{label} must be finite")
    return result


def _ratio(count: int, total: int) -> float:
    return count / total if total else 0.0


def _engine_epoch(record: Mapping[str, Any]) -> tuple[tuple[str, str], ...]:
    raw = record.get("engine_sha256")
    if not isinstance(raw, Mapping) or not raw:
        raise ReadinessError("engine digest map is missing")
    epoch: list[tuple[str, str]] = []
    for name, digest in raw.items():
        if not isinstance(name, str) or not isinstance(digest, str) or not SHA256_RE.fullmatch(digest):
            raise ReadinessError("engine digest map is malformed")
        epoch.append((name, digest))
    return tuple(sorted(epoch))


def _context_is_complete(record: Mapping[str, Any]) -> bool:
    context = record.get("context")
    if not isinstance(context, Mapping):
        return False
    required_numbers = (
        "global_change_24h",
        "global_dispersion_pct",
        "funding_rate",
        "funding_z",
        "oi_change_pct",
    )
    if any(context.get(key) is None for key in required_numbers):
        return False
    breadth = context.get("breadth_24h")
    return isinstance(breadth, Mapping) and set(breadth) == REQUIRED_BREADTH


def _assert_observation_only(output: Any) -> None:
    if not isinstance(output, Mapping) or output.get("mode") != "shadow_observation_only":
        raise ReadinessError("replayed result is not shadow observation-only")
    for key in (
        "execution_authority",
        "automatic_promotion_enabled",
        "automatic_reweighting_enabled",
        "foreign_execution_enabled",
        "geo_bypass_supported",
    ):
        if output.get(key) is not False:
            raise ReadinessError(f"replayed authority lock failed: {key}")
    meta = output.get("meta_intelligence")
    if not isinstance(meta, Mapping) or meta.get("execution_authority") is not False:
        raise ReadinessError("replayed meta authority lock failed")


def assess_records(
    records: Iterable[Mapping[str, Any]],
    *,
    replay_record: Callable[[Mapping[str, Any]], Mapping[str, Any]] | None = None,
    policy: ReadinessPolicy | None = None,
) -> dict[str, Any]:
    effective_policy = policy or ReadinessPolicy()
    effective_policy.validate()
    replay_record = replay_record or council_evidence.replay_record

    count = 0
    first_time: float | None = None
    previous_time: float | None = None
    last_time: float | None = None
    maximum_gap = 0.0
    excessive_gap_count = 0
    covered_seconds = 0.0
    integrity_count = 0
    diverse_source_count = 0
    complete_context_count = 0
    engine_epochs: set[tuple[tuple[str, str], ...]] = set()
    action_counts = {action: 0 for action in sorted(ALLOWED_ACTIONS)}

    for index, record in enumerate(records, start=1):
        if not isinstance(record, Mapping):
            raise ReadinessError(f"record {index} is not an object")
        evaluated_at = _number(record.get("evaluation_time_unix"), f"record {index} evaluation time")
        if evaluated_at <= 0:
            raise ReadinessError("evaluation time must be positive")
        if previous_time is not None:
            gap = evaluated_at - previous_time
            if gap <= 0:
                raise ReadinessError("evidence evaluation times must strictly increase")
            maximum_gap = max(maximum_gap, gap)
            covered_seconds += min(gap, effective_policy.target_interval_seconds + effective_policy.interval_tolerance_seconds)
            if gap > effective_policy.maximum_gap_seconds:
                excessive_gap_count += 1
        else:
            first_time = evaluated_at

        try:
            output = replay_record(record)
        except Exception as exc:
            raise ReadinessError(f"record {index} deterministic replay failed") from exc
        _assert_observation_only(output)

        meta = output.get("meta_intelligence") or {}
        integrity = meta.get("data_integrity") if isinstance(meta, Mapping) else {}
        if isinstance(integrity, Mapping):
            try:
                integrity_score = _number(integrity.get("score"), "data integrity score")
            except ReadinessError:
                integrity_score = -1.0
            if integrity.get("healthy") is True and integrity_score >= MIN_DATA_INTEGRITY_SCORE:
                integrity_count += 1

        context = record.get("context")
        if isinstance(context, Mapping):
            source_count = context.get("global_source_count")
            if isinstance(source_count, int) and not isinstance(source_count, bool) and source_count >= 2:
                diverse_source_count += 1
        if _context_is_complete(record):
            complete_context_count += 1

        consensus = output.get("shadow_consensus")
        action = consensus.get("action") if isinstance(consensus, Mapping) else None
        if not isinstance(action, str) or action not in ALLOWED_ACTIONS:
            raise ReadinessError(f"record {index} replayed an invalid shadow action")
        action_counts[str(action)] += 1
        engine_epochs.add(_engine_epoch(record))

        count += 1
        previous_time = evaluated_at
        last_time = evaluated_at

    if count == 0 or first_time is None or last_time is None:
        raise ReadinessError("evidence journal is empty")

    duration_seconds = last_time - first_time
    history_days = duration_seconds / 86400.0
    expected_records = math.floor(duration_seconds / effective_policy.target_interval_seconds) + 1
    coverage_ratio = min(1.0, _ratio(count, expected_records))
    time_coverage_ratio = covered_seconds / duration_seconds if duration_seconds > 0 else 0.0
    integrity_ratio = _ratio(integrity_count, count)
    source_ratio = _ratio(diverse_source_count, count)
    feature_ratio = _ratio(complete_context_count, count)

    reasons: list[str] = []
    if history_days < effective_policy.minimum_history_days:
        reasons.append("EVIDENCE_HISTORY_TOO_SHORT")
    if time_coverage_ratio < effective_policy.minimum_coverage_ratio:
        reasons.append("EVIDENCE_COVERAGE_TOO_LOW")
    if excessive_gap_count:
        reasons.append("EVIDENCE_GAP_TOO_LARGE")
    if integrity_ratio < effective_policy.minimum_quality_ratio:
        reasons.append("DATA_INTEGRITY_COVERAGE_TOO_LOW")
    if source_ratio < effective_policy.minimum_quality_ratio:
        reasons.append("GLOBAL_SOURCE_DIVERSITY_COVERAGE_TOO_LOW")
    if feature_ratio < effective_policy.minimum_quality_ratio:
        reasons.append("CONTEXT_FEATURE_COVERAGE_TOO_LOW")
    if len(engine_epochs) != 1:
        reasons.append("ENGINE_EPOCH_COUNT_NOT_ONE")

    return {
        "ok": True,
        "mode": "observation_only_evidence_audit",
        "ready_for_full_fidelity_backtest_review": not reasons,
        "full_fidelity_multiagent": False,
        "execution_authority_granted": False,
        "live_authority_granted": False,
        "automatic_promotion": False,
        "reasons": reasons,
        "policy": asdict(effective_policy),
        "metrics": {
            "valid_records": count,
            "history_days": history_days,
            "expected_records_at_target_cadence": expected_records,
            "record_coverage_ratio": coverage_ratio,
            "time_coverage_ratio": time_coverage_ratio,
            "covered_seconds": covered_seconds,
            "first_evaluation_unix": first_time,
            "last_evaluation_unix": last_time,
            "maximum_gap_seconds": maximum_gap,
            "excessive_gap_count": excessive_gap_count,
            "data_integrity_quality_ratio": integrity_ratio,
            "global_source_diversity_ratio": source_ratio,
            "complete_context_ratio": feature_ratio,
            "engine_epoch_count": len(engine_epochs),
            "shadow_action_counts": action_counts,
        },
    }


def audit_journal(
    path: Path,
    *,
    policy: ReadinessPolicy | None = None,
) -> dict[str, Any]:
    journal = Path(path)
    if journal.is_symlink():
        raise ReadinessError("evidence journal must not be a symlink")
    flags = os.O_RDONLY | os.O_CLOEXEC | os.O_NONBLOCK
    if hasattr(os, "O_NOFOLLOW"):
        flags |= os.O_NOFOLLOW
    try:
        fd = os.open(journal, flags)
    except OSError as exc:
        raise ReadinessError("cannot open evidence journal safely") from exc

    try:
        # Hold a non-blocking shared lock only to capture a complete prefix.
        # Replaying it can take minutes; never delay the independent writer.
        fcntl.flock(fd, fcntl.LOCK_SH | fcntl.LOCK_NB)
        metadata = os.fstat(fd)
        if not stat.S_ISREG(metadata.st_mode):
            raise ReadinessError("evidence journal must be a regular file")
        if metadata.st_uid != os.geteuid():
            raise ReadinessError("evidence journal owner mismatch")
        if stat.S_IMODE(metadata.st_mode) & 0o077:
            raise ReadinessError("evidence journal permissions are too broad")
        if metadata.st_size > council_evidence.MAX_JOURNAL_BYTES:
            raise ReadinessError("evidence journal exceeds maximum size")
        snapshot_size = metadata.st_size
        if snapshot_size and os.pread(fd, 1, snapshot_size - 1) != b"\n":
            raise ReadinessError("evidence snapshot has an incomplete trailing record")
        fcntl.flock(fd, fcntl.LOCK_UN)

        def parsed_records() -> Iterable[Mapping[str, Any]]:
            with os.fdopen(fd, "rb", closefd=False) as stream:
                line_number = 0
                remaining = snapshot_size
                while remaining:
                    raw = stream.readline(min(remaining, council_evidence.MAX_RECORD_BYTES + 1))
                    if not raw:
                        raise ReadinessError("evidence snapshot was truncated")
                    remaining -= len(raw)
                    line_number += 1
                    if len(raw) > council_evidence.MAX_RECORD_BYTES or not raw.endswith(b"\n"):
                        raise ReadinessError(
                            f"journal line {line_number} is oversized or incomplete"
                        )
                    try:
                        payload = json.loads(raw.decode("utf-8"), object_pairs_hook=_unique_object)
                    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
                        raise ReadinessError(f"journal line {line_number} is malformed") from exc
                    if not isinstance(payload, Mapping):
                        raise ReadinessError(f"journal line {line_number} is not an object")
                    yield payload

        result = assess_records(parsed_records(), policy=policy)
        after = journal.lstat()
        if (after.st_dev, after.st_ino) != (metadata.st_dev, metadata.st_ino) or after.st_size < snapshot_size:
            raise ReadinessError("evidence journal was replaced or truncated")
        result["metrics"]["snapshot_bytes"] = snapshot_size
        return result
    except OSError as exc:
        raise ReadinessError("evidence snapshot unavailable, busy, or unreadable; retry the audit") from exc
    finally:
        try:
            fcntl.flock(fd, fcntl.LOCK_UN)
        finally:
            os.close(fd)


def _unique_object(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise ReadinessError("duplicate JSON key in evidence snapshot")
        result[key] = value
    return result


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Read-only fail-closed ChobYar council evidence replay-readiness audit"
    )
    parser.add_argument("journal", type=Path)
    parser.add_argument(
        "--require-ready",
        action="store_true",
        help="exit 2 when the evidence window is not ready for a separate backtest review",
    )
    args = parser.parse_args()
    try:
        result = audit_journal(args.journal)
    except ReadinessError as exc:
        print(json.dumps({
            "ok": False,
            "mode": "observation_only_evidence_audit",
            "ready_for_full_fidelity_backtest_review": False,
            "full_fidelity_multiagent": False,
            "execution_authority_granted": False,
            "live_authority_granted": False,
            "automatic_promotion": False,
            "reasons": ["EVIDENCE_AUDIT_FAILED"],
            "error": str(exc),
        }, sort_keys=True))
        return 2
    print(json.dumps(result, sort_keys=True))
    if args.require_ready and not result["ready_for_full_fidelity_backtest_review"]:
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

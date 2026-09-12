from __future__ import annotations

import argparse
import fcntl
import hashlib
import json
import math
import os
import re
import stat
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Mapping

from meta_intelligence import enhance_council
from specialist_council import CouncilContext, clamp, finite, run_council

SCHEMA = "chobyar.v5.council-context-evidence"
SCHEMA_VERSION = 1
DEFAULT_JOURNAL_NAME = "v5_council_evidence.jsonl"
MAX_RECORD_BYTES = 512 * 1024
MAX_JOURNAL_BYTES = 2 * 1024 * 1024 * 1024
MAX_CANDLES = 512
MAX_SOURCE_CYCLE_LAG_SECONDS = 300.0
SPECIALIST_AGENTS = (
    "regime_structure",
    "microstructure_liquidity",
    "derivatives_positioning",
    "cross_market_breadth",
    "adversarial_risk",
)
ALLOWED_GLOBAL_SOURCES = {
    "kucoin",
    "gateio",
    "mexc",
    "bitget",
    "bybit",
    "okx",
    "kraken",
    "coinbase",
}
REQUIRED_BREADTH = ("BTC-USDT", "ETH-USDT", "SOL-USDT")
ENGINE_FILES = (
    "specialist_council.py",
    "meta_intelligence.py",
    "shadow_runner.py",
    "shadow_runner_v52.py",
    "public_source_fallbacks.py",
    "council_evidence.py",
)
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
SHA256_RE = re.compile(r"^[0-9a-f]{64}$")


class EvidenceError(RuntimeError):
    pass


def canonical_json(value: Any) -> str:
    try:
        return json.dumps(
            value,
            ensure_ascii=False,
            sort_keys=True,
            separators=(",", ":"),
            allow_nan=False,
        )
    except (TypeError, ValueError) as exc:
        raise EvidenceError("value is not canonical JSON") from exc


def canonical_sha256(value: Any) -> str:
    return hashlib.sha256(canonical_json(value).encode("utf-8")).hexdigest()


def _number(value: Any, label: str, *, positive: bool = False, nonnegative: bool = False) -> float:
    if isinstance(value, bool):
        raise EvidenceError(f"{label} must be numeric")
    try:
        number = float(value)
    except (TypeError, ValueError) as exc:
        raise EvidenceError(f"{label} must be numeric") from exc
    if not math.isfinite(number):
        raise EvidenceError(f"{label} must be finite")
    if positive and number <= 0:
        raise EvidenceError(f"{label} must be positive")
    if nonnegative and number < 0:
        raise EvidenceError(f"{label} must be nonnegative")
    return number


def _optional_number(value: Any, label: str, *, nonnegative: bool = False) -> float | None:
    if value is None:
        return None
    return _number(value, label, nonnegative=nonnegative)


def _parse_utc(value: Any, label: str) -> datetime:
    if not isinstance(value, str) or not value:
        raise EvidenceError(f"{label} must be an ISO timestamp")
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError as exc:
        raise EvidenceError(f"{label} must be an ISO timestamp") from exc
    if parsed.tzinfo is None or parsed.utcoffset() != timezone.utc.utcoffset(parsed):
        raise EvidenceError(f"{label} must be UTC")
    return parsed


def _normalise_candles(value: Any) -> list[list[float]]:
    if not isinstance(value, list) or not 80 <= len(value) <= MAX_CANDLES:
        raise EvidenceError("context.candles count is outside the supported range")
    rows: list[list[float]] = []
    previous_ts = -1.0
    for index, raw in enumerate(value):
        if not isinstance(raw, (list, tuple)) or len(raw) < 6:
            raise EvidenceError(f"context.candles[{index}] is malformed")
        row = [_number(raw[column], f"context.candles[{index}][{column}]") for column in range(6)]
        ts, opened, high, low, close, volume = row
        if ts <= previous_ts:
            raise EvidenceError("context candle timestamps must increase")
        if min(opened, high, low, close) <= 0:
            raise EvidenceError("context OHLC values must be positive")
        if high < max(opened, close) or low > min(opened, close) or volume < 0:
            raise EvidenceError("context candle OHLC/volume is invalid")
        previous_ts = ts
        rows.append(row)
    return rows


def _normalise_context(value: CouncilContext | Mapping[str, Any]) -> dict[str, Any]:
    raw: Mapping[str, Any]
    if isinstance(value, CouncilContext):
        raw = value.__dict__
    elif isinstance(value, Mapping):
        raw = value
    else:
        raise EvidenceError("context must be a CouncilContext or mapping")

    breadth_raw = raw.get("breadth_24h")
    if not isinstance(breadth_raw, Mapping):
        raise EvidenceError("context.breadth_24h must be an object")
    unexpected_breadth = set(breadth_raw) - set(REQUIRED_BREADTH)
    if unexpected_breadth:
        raise EvidenceError("context contains unknown breadth symbols")
    breadth = {
        symbol: _number(breadth_raw[symbol], f"context.breadth_24h.{symbol}")
        for symbol in REQUIRED_BREADTH
        if symbol in breadth_raw
    }

    source_count = raw.get("global_source_count")
    if isinstance(source_count, bool):
        raise EvidenceError("context.global_source_count must be an integer")
    try:
        source_count_int = int(source_count)
    except (TypeError, ValueError) as exc:
        raise EvidenceError("context.global_source_count must be an integer") from exc
    if source_count_int != source_count or not 0 <= source_count_int <= 32:
        raise EvidenceError("context.global_source_count is invalid")

    return {
        "candles": _normalise_candles(raw.get("candles")),
        "local_mid": _number(raw.get("local_mid"), "context.local_mid", positive=True),
        "spread_pct": _number(raw.get("spread_pct"), "context.spread_pct", nonnegative=True),
        "book_imbalance": _number(raw.get("book_imbalance"), "context.book_imbalance"),
        "tape_buy_ratio": _number(raw.get("tape_buy_ratio"), "context.tape_buy_ratio"),
        "global_change_24h": _optional_number(raw.get("global_change_24h"), "context.global_change_24h"),
        "global_dispersion_pct": _optional_number(
            raw.get("global_dispersion_pct"),
            "context.global_dispersion_pct",
            nonnegative=True,
        ),
        "global_source_count": source_count_int,
        "funding_rate": _optional_number(raw.get("funding_rate"), "context.funding_rate"),
        "funding_z": _optional_number(raw.get("funding_z"), "context.funding_z"),
        "oi_change_pct": _optional_number(raw.get("oi_change_pct"), "context.oi_change_pct"),
        "breadth_24h": breadth,
    }


def _context_from_payload(payload: Mapping[str, Any]) -> CouncilContext:
    normalised = _normalise_context(payload)
    return CouncilContext(**normalised)


def _project_cycle(cycle: Any) -> dict[str, Any]:
    if not isinstance(cycle, Mapping):
        raise EvidenceError("source cycle must be an object")
    if cycle.get("event") != "cycle":
        raise EvidenceError("source cycle event must be cycle")
    _parse_utc(cycle.get("ts"), "source_cycle.ts")
    if cycle.get("symbol") != "BTCUSDT":
        raise EvidenceError("source cycle symbol must be BTCUSDT")
    sources = cycle.get("global_sources")
    if not isinstance(sources, list) or any(not isinstance(item, str) for item in sources):
        raise EvidenceError("source cycle global_sources must be a string list")
    if len(sources) != len(set(sources)) or not set(sources).issubset(ALLOWED_GLOBAL_SOURCES):
        raise EvidenceError("source cycle global_sources is invalid")
    return {
        "ts": cycle["ts"],
        "event": "cycle",
        "symbol": "BTCUSDT",
        "local_mid": _number(cycle.get("local_mid"), "source_cycle.local_mid", positive=True),
        "spread_pct": _number(cycle.get("spread_pct"), "source_cycle.spread_pct", nonnegative=True),
        "orderbook_imbalance": _number(
            cycle.get("orderbook_imbalance"), "source_cycle.orderbook_imbalance"
        ),
        "tape_buy_ratio": _number(cycle.get("tape_buy_ratio"), "source_cycle.tape_buy_ratio"),
        "global_change_24h": _optional_number(
            cycle.get("global_change_24h"), "source_cycle.global_change_24h"
        ),
        "global_dispersion_pct": _optional_number(
            cycle.get("global_dispersion_pct"),
            "source_cycle.global_dispersion_pct",
            nonnegative=True,
        ),
        "global_sources": list(sources),
    }


def _assert_cycle_context_match(cycle: Mapping[str, Any], context: Mapping[str, Any]) -> None:
    pairs = (
        ("local_mid", "local_mid"),
        ("spread_pct", "spread_pct"),
        ("orderbook_imbalance", "book_imbalance"),
        ("tape_buy_ratio", "tape_buy_ratio"),
        ("global_change_24h", "global_change_24h"),
        ("global_dispersion_pct", "global_dispersion_pct"),
    )
    for cycle_key, context_key in pairs:
        if cycle.get(cycle_key) != context.get(context_key):
            raise EvidenceError(f"source cycle does not match context field {context_key}")
    if len(cycle["global_sources"]) != context["global_source_count"]:
        raise EvidenceError("source cycle count does not match context.global_source_count")


def _calibration_snapshot(scorecard: Any) -> dict[str, dict[str, float | int | None]]:
    card = scorecard if isinstance(scorecard, Mapping) else {}
    snapshot: dict[str, dict[str, float | int | None]] = {}
    for agent in SPECIALIST_AGENTS:
        try:
            horizon = (
                card.get("specialists", {})
                .get(agent, {})
                .get("regimes", {})
                .get("ALL", {})
                .get("horizons", {})
                .get("4h", {})
            )
            samples = max(0, int(horizon.get("samples") or 0))
            hit = finite(horizon.get("hit_rate"))
            hit = clamp(hit, 0.0, 1.0) if hit is not None else None
        except Exception:
            samples, hit = 0, None
        snapshot[agent] = {"samples_4h": samples, "hit_rate_4h": hit}
    return snapshot


def _validate_calibration(value: Any) -> dict[str, dict[str, float | int | None]]:
    if not isinstance(value, Mapping) or set(value) != set(SPECIALIST_AGENTS):
        raise EvidenceError("calibration input agents are invalid")
    result: dict[str, dict[str, float | int | None]] = {}
    for agent in SPECIALIST_AGENTS:
        row = value.get(agent)
        if not isinstance(row, Mapping) or set(row) != {"samples_4h", "hit_rate_4h"}:
            raise EvidenceError("calibration input row is malformed")
        samples = row.get("samples_4h")
        if isinstance(samples, bool):
            raise EvidenceError("calibration sample count is invalid")
        try:
            samples_int = int(samples)
        except (TypeError, ValueError) as exc:
            raise EvidenceError("calibration sample count is invalid") from exc
        if samples_int != samples or samples_int < 0:
            raise EvidenceError("calibration sample count is invalid")
        hit = _optional_number(row.get("hit_rate_4h"), "calibration.hit_rate_4h")
        if hit is not None and not 0.0 <= hit <= 1.0:
            raise EvidenceError("calibration hit rate is invalid")
        result[agent] = {"samples_4h": samples_int, "hit_rate_4h": hit}
    return result


def _scorecard_from_snapshot(snapshot: Mapping[str, Mapping[str, Any]]) -> dict[str, Any]:
    return {
        "specialists": {
            agent: {
                "regimes": {
                    "ALL": {
                        "horizons": {
                            "4h": {
                                "samples": row["samples_4h"],
                                "hit_rate": row["hit_rate_4h"],
                            }
                        }
                    }
                }
            }
            for agent, row in snapshot.items()
        }
    }


def _engine_hashes(engine_dir: Path | None = None) -> dict[str, str]:
    root = Path(engine_dir) if engine_dir is not None else Path(__file__).resolve().parent
    result: dict[str, str] = {}
    for name in ENGINE_FILES:
        path = root / name
        if path.is_symlink() or not path.is_file():
            raise EvidenceError(f"engine file missing or unsafe: {name}")
        result[name] = hashlib.sha256(path.read_bytes()).hexdigest()
    return result


def _assert_observation_only(output: Any) -> None:
    if not isinstance(output, Mapping):
        raise EvidenceError("enhanced output must be an object")
    if output.get("mode") != "shadow_observation_only":
        raise EvidenceError("enhanced output is not shadow observation-only")
    for key in (
        "execution_authority",
        "automatic_promotion_enabled",
        "automatic_reweighting_enabled",
        "foreign_execution_enabled",
        "geo_bypass_supported",
    ):
        if output.get(key) is not False:
            raise EvidenceError(f"enhanced output authority lock failed: {key}")
    meta = output.get("meta_intelligence")
    if not isinstance(meta, Mapping) or meta.get("execution_authority") is not False:
        raise EvidenceError("meta intelligence authority lock failed")


def _reject_sensitive_keys(value: Any, path: str = "$") -> None:
    if isinstance(value, Mapping):
        for key, child in value.items():
            lower = str(key).lower()
            if any(marker in lower for marker in SENSITIVE_MARKERS):
                raise EvidenceError(f"sensitive key rejected at {path}")
            _reject_sensitive_keys(child, f"{path}.{key}")
    elif isinstance(value, list):
        for index, child in enumerate(value):
            _reject_sensitive_keys(child, f"{path}[{index}]")


def build_record(
    ctx: CouncilContext,
    source_cycle: Mapping[str, Any],
    scorecard: Mapping[str, Any] | None,
    evaluated_at: float,
    enhanced_output: Mapping[str, Any],
    *,
    engine_dir: Path | None = None,
) -> dict[str, Any]:
    context_payload = _normalise_context(ctx)
    cycle_payload = _project_cycle(source_cycle)
    _assert_cycle_context_match(cycle_payload, context_payload)
    evaluated_at_number = _number(evaluated_at, "evaluated_at", positive=True)
    source_cycle_time = _parse_utc(cycle_payload["ts"], "source_cycle.ts").timestamp()
    source_cycle_lag = evaluated_at_number - source_cycle_time
    if not 0.0 <= source_cycle_lag <= MAX_SOURCE_CYCLE_LAG_SECONDS:
        raise EvidenceError("source cycle is future-dated or stale at evaluation")
    output_copy = json.loads(canonical_json(enhanced_output))
    _assert_observation_only(output_copy)
    calibration = _calibration_snapshot(scorecard)
    cycle_hash = canonical_sha256(cycle_payload)
    record: dict[str, Any] = {
        "schema": SCHEMA,
        "schema_version": SCHEMA_VERSION,
        "captured_at_utc": datetime.fromtimestamp(
            evaluated_at_number, tz=timezone.utc
        ).isoformat(),
        "evaluation_time_unix": evaluated_at_number,
        "source_cycle_ref": {
            "event": "cycle",
            "ts": cycle_payload["ts"],
            "sha256": cycle_hash,
        },
        "source_cycle": cycle_payload,
        "context": context_payload,
        "context_sha256": canonical_sha256(context_payload),
        "calibration_4h": calibration,
        "engine_sha256": _engine_hashes(engine_dir),
        "result_sha256": canonical_sha256(output_copy),
        "authority": {
            "execution_authority": False,
            "automatic_promotion": False,
            "automatic_reweighting": False,
            "foreign_execution": False,
            "geo_bypass": False,
        },
    }
    _reject_sensitive_keys(record)
    record["record_sha256"] = canonical_sha256(record)
    validate_record(record, engine_dir=engine_dir)
    return record


def validate_record(
    record: Any,
    *,
    engine_dir: Path | None = None,
    require_engine_match: bool = True,
) -> None:
    if not isinstance(record, Mapping):
        raise EvidenceError("evidence record must be an object")
    expected_keys = {
        "schema",
        "schema_version",
        "captured_at_utc",
        "evaluation_time_unix",
        "source_cycle_ref",
        "source_cycle",
        "context",
        "context_sha256",
        "calibration_4h",
        "engine_sha256",
        "result_sha256",
        "authority",
        "record_sha256",
    }
    if set(record) != expected_keys:
        raise EvidenceError("evidence record keys are invalid")
    if record.get("schema") != SCHEMA or record.get("schema_version") != SCHEMA_VERSION:
        raise EvidenceError("unsupported evidence schema")
    _reject_sensitive_keys(record)

    stored_record_hash = record.get("record_sha256")
    if not isinstance(stored_record_hash, str) or not SHA256_RE.fullmatch(stored_record_hash):
        raise EvidenceError("record digest is malformed")
    body = dict(record)
    body.pop("record_sha256")
    if canonical_sha256(body) != stored_record_hash:
        raise EvidenceError("record digest mismatch")

    evaluated_at = _number(record.get("evaluation_time_unix"), "evaluation_time_unix", positive=True)
    expected_capture = datetime.fromtimestamp(evaluated_at, tz=timezone.utc).isoformat()
    if record.get("captured_at_utc") != expected_capture:
        raise EvidenceError("capture timestamp does not match evaluation time")

    context_payload = _normalise_context(record.get("context"))
    if canonical_sha256(context_payload) != record.get("context_sha256"):
        raise EvidenceError("context digest mismatch")
    cycle_payload = _project_cycle(record.get("source_cycle"))
    source_cycle_lag = evaluated_at - _parse_utc(
        cycle_payload["ts"], "source_cycle.ts"
    ).timestamp()
    if not 0.0 <= source_cycle_lag <= MAX_SOURCE_CYCLE_LAG_SECONDS:
        raise EvidenceError("source cycle is future-dated or stale at evaluation")
    _assert_cycle_context_match(cycle_payload, context_payload)
    ref = record.get("source_cycle_ref")
    expected_ref = {
        "event": "cycle",
        "ts": cycle_payload["ts"],
        "sha256": canonical_sha256(cycle_payload),
    }
    if ref != expected_ref:
        raise EvidenceError("source cycle reference mismatch")
    _validate_calibration(record.get("calibration_4h"))

    engine_hashes = record.get("engine_sha256")
    if not isinstance(engine_hashes, Mapping) or set(engine_hashes) != set(ENGINE_FILES):
        raise EvidenceError("engine digest map is invalid")
    if any(not isinstance(value, str) or not SHA256_RE.fullmatch(value) for value in engine_hashes.values()):
        raise EvidenceError("engine digest is malformed")
    if require_engine_match and dict(engine_hashes) != _engine_hashes(engine_dir):
        raise EvidenceError("engine source digest mismatch")

    result_hash = record.get("result_sha256")
    if not isinstance(result_hash, str) or not SHA256_RE.fullmatch(result_hash):
        raise EvidenceError("result digest is malformed")
    expected_authority = {
        "execution_authority": False,
        "automatic_promotion": False,
        "automatic_reweighting": False,
        "foreign_execution": False,
        "geo_bypass": False,
    }
    if record.get("authority") != expected_authority:
        raise EvidenceError("evidence authority lock failed")


def replay_record(record: Mapping[str, Any], *, engine_dir: Path | None = None) -> dict[str, Any]:
    validate_record(record, engine_dir=engine_dir)
    context = _context_from_payload(record["context"])
    calibration = _validate_calibration(record["calibration_4h"])
    scorecard = _scorecard_from_snapshot(calibration)
    output = enhance_council(
        context,
        run_council(context),
        scorecard,
        now_ts=float(record["evaluation_time_unix"]),
    )
    _assert_observation_only(output)
    if canonical_sha256(output) != record["result_sha256"]:
        raise EvidenceError("deterministic replay result mismatch")
    return output


def _validate_existing_tail(fd: int, size: int) -> None:
    if size == 0:
        return
    if os.pread(fd, 1, size - 1) != b"\n":
        raise EvidenceError("evidence journal has an incomplete trailing record")
    read_size = min(size, MAX_RECORD_BYTES + 1)
    tail = os.pread(fd, read_size, size - read_size)
    body = tail[:-1]
    boundary = body.rfind(b"\n")
    if boundary < 0:
        if size > read_size:
            raise EvidenceError("existing trailing record exceeds maximum size")
        raw = body
    else:
        raw = body[boundary + 1 :]
    if not raw or len(raw) + 1 > MAX_RECORD_BYTES:
        raise EvidenceError("existing trailing record is empty or oversized")
    try:
        record = json.loads(raw.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise EvidenceError("existing trailing record is malformed") from exc
    replay_record(record)


def append_record(
    path: Path,
    record: Mapping[str, Any],
    *,
    max_journal_bytes: int = MAX_JOURNAL_BYTES,
) -> None:
    validate_record(record)
    if not isinstance(max_journal_bytes, int) or max_journal_bytes <= 0:
        raise EvidenceError("journal capacity is invalid")
    encoded = (canonical_json(record) + "\n").encode("utf-8")
    if len(encoded) > MAX_RECORD_BYTES:
        raise EvidenceError("evidence record exceeds maximum size")
    if len(encoded) > max_journal_bytes:
        raise EvidenceError("evidence record exceeds journal capacity")

    path = Path(path)
    try:
        parent_stat = path.parent.lstat()
    except OSError as exc:
        raise EvidenceError("evidence parent directory is unavailable") from exc
    if not stat.S_ISDIR(parent_stat.st_mode) or path.parent.is_symlink():
        raise EvidenceError("evidence parent directory is unsafe")
    if path.is_symlink():
        raise EvidenceError("evidence path must not be a symlink")

    flags = os.O_RDWR | os.O_APPEND | os.O_CREAT | os.O_CLOEXEC
    if hasattr(os, "O_NOFOLLOW"):
        flags |= os.O_NOFOLLOW
    try:
        fd = os.open(path, flags, 0o600)
    except OSError as exc:
        raise EvidenceError("cannot open evidence journal safely") from exc
    try:
        file_stat = os.fstat(fd)
        if not stat.S_ISREG(file_stat.st_mode):
            raise EvidenceError("evidence journal must be a regular file")
        if file_stat.st_uid != os.geteuid():
            raise EvidenceError("evidence journal owner mismatch")
        if stat.S_IMODE(file_stat.st_mode) & 0o077:
            raise EvidenceError("evidence journal permissions are too broad")
        fcntl.flock(fd, fcntl.LOCK_EX)
        locked_size = os.fstat(fd).st_size
        _validate_existing_tail(fd, locked_size)
        if locked_size + len(encoded) > max_journal_bytes:
            raise EvidenceError("evidence journal capacity reached")
        view = memoryview(encoded)
        while view:
            written = os.write(fd, view)
            if written <= 0:
                raise EvidenceError("evidence journal append failed")
            view = view[written:]
        os.fsync(fd)
    except OSError as exc:
        raise EvidenceError("evidence journal append failed") from exc
    finally:
        try:
            fcntl.flock(fd, fcntl.LOCK_UN)
        finally:
            os.close(fd)


def record_evaluation(
    path: Path,
    ctx: CouncilContext,
    source_cycle: Mapping[str, Any],
    scorecard: Mapping[str, Any] | None,
    evaluated_at: float,
    enhanced_output: Mapping[str, Any],
) -> dict[str, Any]:
    record = build_record(
        ctx,
        source_cycle,
        scorecard,
        evaluated_at,
        enhanced_output,
    )
    append_record(path, record)
    return record


def validate_journal(path: Path) -> dict[str, Any]:
    path = Path(path)
    if path.is_symlink():
        raise EvidenceError("evidence journal must not be a symlink")
    flags = os.O_RDONLY | os.O_CLOEXEC
    if hasattr(os, "O_NOFOLLOW"):
        flags |= os.O_NOFOLLOW
    try:
        fd = os.open(path, flags)
    except OSError as exc:
        raise EvidenceError("cannot open evidence journal safely") from exc
    times: list[float] = []
    try:
        file_stat = os.fstat(fd)
        if not stat.S_ISREG(file_stat.st_mode):
            raise EvidenceError("evidence journal must be a regular file")
        if file_stat.st_size > MAX_JOURNAL_BYTES:
            raise EvidenceError("evidence journal exceeds maximum size")
        if stat.S_IMODE(file_stat.st_mode) & 0o077:
            raise EvidenceError("evidence journal permissions are too broad")
        with os.fdopen(fd, "rb", closefd=False) as stream:
            line_number = 0
            while True:
                raw = stream.readline(MAX_RECORD_BYTES + 1)
                if not raw:
                    break
                line_number += 1
                if len(raw) > MAX_RECORD_BYTES or not raw.endswith(b"\n"):
                    raise EvidenceError(f"journal line {line_number} is oversized or incomplete")
                try:
                    record = json.loads(raw.decode("utf-8"))
                except (UnicodeDecodeError, json.JSONDecodeError) as exc:
                    raise EvidenceError(f"journal line {line_number} is malformed") from exc
                replay_record(record)
                times.append(float(record["evaluation_time_unix"]))
    finally:
        os.close(fd)
    if not times:
        raise EvidenceError("evidence journal is empty")
    return {
        "valid_records": len(times),
        "first_evaluation_utc": datetime.fromtimestamp(min(times), tz=timezone.utc).isoformat(),
        "last_evaluation_utc": datetime.fromtimestamp(max(times), tz=timezone.utc).isoformat(),
        "history_days": (max(times) - min(times)) / 86400.0,
        "full_fidelity_backtest_ready": False,
        "execution_authority_granted": False,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description="Fail-closed CouncilContext evidence replay validator")
    parser.add_argument("journal", type=Path)
    args = parser.parse_args()
    try:
        summary = validate_journal(args.journal)
    except EvidenceError as exc:
        print("EVIDENCE_REPLAY=FAIL_CLOSED")
        print("ERROR=" + str(exc))
        return 2
    print("EVIDENCE_REPLAY=PASS")
    print("VALID_RECORDS=" + str(summary["valid_records"]))
    print("HISTORY_DAYS=" + format(float(summary["history_days"]), ".6f"))
    print("FULL_FIDELITY_BACKTEST_READY=NO")
    print("EXECUTION_AUTHORITY=NONE")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

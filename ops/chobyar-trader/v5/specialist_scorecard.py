from __future__ import annotations

import argparse
import bisect
import json
import math
import statistics
from collections import defaultdict, deque
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

DEFAULT_APP_DIR = Path("/opt/chobyar-trader")
HORIZONS = {"1h": 3600, "4h": 4 * 3600, "12h": 12 * 3600}
SAMPLE_BUCKET_SECONDS = 15 * 60
MAX_FUTURE_SKEW_SECONDS = 15 * 60
MIN_DIRECTIONAL_SAMPLES = 30
MAX_ROWS = 100_000


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat()


def parse_ts(value: Any) -> float | None:
    if not isinstance(value, str) or not value:
        return None
    try:
        return datetime.fromisoformat(value.replace("Z", "+00:00")).timestamp()
    except ValueError:
        return None


def price(value: Any) -> float | None:
    try:
        number = float(value)
    except (TypeError, ValueError):
        return None
    return number if math.isfinite(number) and number > 0 else None


def atomic_json(path: Path, payload: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(path.suffix + ".tmp")
    tmp.write_text(json.dumps(payload, ensure_ascii=False, sort_keys=True, separators=(",", ":")) + "\n", encoding="utf-8")
    tmp.replace(path)


def read_rows(path: Path) -> list[dict[str, Any]]:
    if not path.is_file():
        return []
    rows: deque[dict[str, Any]] = deque(maxlen=MAX_ROWS)
    with path.open("r", encoding="utf-8") as stream:
        for line in stream:
            try:
                row = json.loads(line)
            except json.JSONDecodeError:
                continue
            if not isinstance(row, dict) or row.get("mode") != "shadow_observation_only":
                continue
            if parse_ts(row.get("generated_at_utc")) is None or price(row.get("local_mid")) is None:
                continue
            rows.append(row)
    return list(rows)


def bucket_rows(rows: list[dict[str, Any]]) -> list[dict[str, Any]]:
    buckets: dict[int, dict[str, Any]] = {}
    for row in rows:
        ts = parse_ts(row.get("generated_at_utc"))
        if ts is not None:
            buckets[int(ts // SAMPLE_BUCKET_SECONDS)] = row
    return [buckets[key] for key in sorted(buckets)]


def summarize(values: list[float]) -> dict[str, Any]:
    samples = len(values)
    return {
        "samples": samples,
        "sufficient": samples >= MIN_DIRECTIONAL_SAMPLES,
        "hit_rate": (sum(value > 0 for value in values) / samples) if samples else None,
        "average_signed_return": statistics.fmean(values) if values else None,
        "median_signed_return": statistics.median(values) if values else None,
    }


def directional(row: dict[str, Any]) -> list[tuple[str, int]]:
    result: list[tuple[str, int]] = []
    for item in row.get("specialists") or []:
        if not isinstance(item, dict) or item.get("available") is False:
            continue
        name = str(item.get("agent") or "").strip()
        try:
            vote = int(item.get("vote"))
        except (TypeError, ValueError):
            continue
        if name and vote in (-1, 1):
            result.append((name, vote))
    action = str((row.get("shadow_consensus") or {}).get("action") or "").upper()
    if action == "BUY":
        result.append(("specialist_council_shadow", 1))
    elif action == "SELL":
        result.append(("specialist_council_shadow", -1))
    return result


def compute(rows: list[dict[str, Any]]) -> dict[str, Any]:
    sampled = bucket_rows(rows)
    points: list[tuple[float, float, dict[str, Any]]] = []
    for row in sampled:
        ts = parse_ts(row.get("generated_at_utc"))
        px = price(row.get("local_mid"))
        if ts is not None and px is not None:
            points.append((ts, px, row))
    times = [p[0] for p in points]
    signed: dict[str, dict[str, dict[str, list[float]]]] = defaultdict(lambda: defaultdict(lambda: defaultdict(list)))

    for ts, px, row in points:
        regime = str((row.get("regime") or {}).get("label") or "UNKNOWN")
        votes = directional(row)
        if not votes:
            continue
        for horizon_name, seconds in HORIZONS.items():
            target = ts + seconds
            j = bisect.bisect_left(times, target)
            if j >= len(points):
                continue
            future_ts, future_px, _future = points[j]
            if future_ts - target > MAX_FUTURE_SKEW_SECONDS:
                continue
            raw_return = future_px / px - 1.0
            for name, direction in votes:
                signed[name][regime][horizon_name].append(direction * raw_return)
                signed[name]["ALL"][horizon_name].append(direction * raw_return)

    specialists: dict[str, Any] = {}
    reviewable: list[str] = []
    for name in sorted(signed):
        regimes: dict[str, Any] = {}
        eligible_regimes: list[str] = []
        for regime in sorted(signed[name]):
            horizons = {h: summarize(signed[name][regime].get(h, [])) for h in HORIZONS}
            eligible = bool(horizons["4h"]["sufficient"])
            regimes[regime] = {"horizons": horizons, "eligible_for_review": eligible}
            if regime != "ALL" and eligible:
                eligible_regimes.append(regime)
        specialists[name] = {"regimes": regimes, "eligible_regimes": eligible_regimes, "eligible_for_manual_promotion_review": bool(eligible_regimes)}
        if eligible_regimes:
            reviewable.append(name)

    return {
        "ok": True,
        "generated_at_utc": utc_now(),
        "mode": "shadow_observation_only",
        "automatic_promotion_enabled": False,
        "automatic_reweighting_enabled": False,
        "execution_authority": False,
        "method": {
            "sample_bucket_seconds": SAMPLE_BUCKET_SECONDS,
            "future_horizons_seconds": HORIZONS,
            "max_future_skew_seconds": MAX_FUTURE_SKEW_SECONDS,
            "minimum_directional_samples_per_regime": MIN_DIRECTIONAL_SAMPLES,
            "score": "signed future return grouped by specialist x market regime x horizon",
        },
        "raw_rows": len(rows),
        "sampled_rows": len(points),
        "reviewable_specialists": reviewable,
        "ready_for_manual_promotion_review": bool(reviewable),
        "specialists": specialists,
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--app-dir", type=Path, default=DEFAULT_APP_DIR)
    args = parser.parse_args()
    source = args.app_dir / "logs" / "v5_shadow.jsonl"
    output = args.app_dir / "state" / "v5_specialist_scorecard.json"
    report = compute(read_rows(source))
    atomic_json(output, report)
    print("V5_SPECIALIST_SCORECARD=PASS")
    print(f"SAMPLED_ROWS={report['sampled_rows']}")
    print(f"REVIEWABLE_SPECIALISTS={len(report['reviewable_specialists'])}")
    print("AUTO_PROMOTION=DISABLED")


if __name__ == "__main__":
    main()

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

from common import atomic_json, utc_now

APP_DIR = Path("/opt/chobyar-trader")
DEFAULT_AUDIT = APP_DIR / "logs" / "audit.jsonl"
DEFAULT_OUTPUT = APP_DIR / "state" / "agent_scorecard.json"
HORIZONS = {"1h": 3600, "4h": 4 * 3600, "12h": 12 * 3600}
SAMPLE_BUCKET_SECONDS = 15 * 60
MAX_FUTURE_SKEW_SECONDS = 15 * 60
MIN_DIRECTIONAL_SAMPLES = 30
MAX_CYCLE_ROWS = 200_000


def parse_ts(value: Any) -> float | None:
    if not isinstance(value, str) or not value:
        return None
    try:
        return datetime.fromisoformat(value.replace("Z", "+00:00")).timestamp()
    except ValueError:
        return None


def finite_price(value: Any) -> float | None:
    try:
        px = float(value)
    except (TypeError, ValueError):
        return None
    return px if px > 0 and math.isfinite(px) else None


def read_cycles(path: Path) -> list[dict[str, Any]]:
    rows: deque[dict[str, Any]] = deque(maxlen=MAX_CYCLE_ROWS)
    with path.open("r", encoding="utf-8") as stream:
        for line in stream:
            try:
                row = json.loads(line)
            except json.JSONDecodeError:
                continue
            if not isinstance(row, dict) or row.get("event") != "cycle":
                continue
            ts = parse_ts(row.get("ts"))
            px = finite_price(row.get("local_mid"))
            if ts is None or px is None:
                continue
            rows.append(row)
    return list(rows)


def bucket_cycles(rows: list[dict[str, Any]]) -> list[dict[str, Any]]:
    # Last cycle per 15-minute bucket reduces serially repeated 30-second votes.
    buckets: dict[int, dict[str, Any]] = {}
    for row in rows:
        ts = parse_ts(row.get("ts"))
        if ts is None:
            continue
        buckets[int(ts // SAMPLE_BUCKET_SECONDS)] = row
    return [buckets[key] for key in sorted(buckets)]


def directional_votes(row: dict[str, Any]) -> list[tuple[str, int]]:
    votes: list[tuple[str, int]] = []
    for agent in row.get("agents") or []:
        if not isinstance(agent, dict) or agent.get("available") is False:
            continue
        try:
            direction = int(agent.get("vote"))
        except (TypeError, ValueError):
            continue
        name = str(agent.get("agent") or "").strip()
        if name and direction in (-1, 1):
            votes.append((name, direction))
    action = str(row.get("action") or row.get("signal") or "").upper()
    if action == "BUY":
        votes.append(("supervisor_consensus_action", 1))
    elif action == "SELL":
        votes.append(("supervisor_consensus_action", -1))
    return votes


def summarize(values: list[float]) -> dict[str, Any]:
    samples = len(values)
    return {
        "samples": samples,
        "sufficient": samples >= MIN_DIRECTIONAL_SAMPLES,
        "hit_rate": (sum(value > 0 for value in values) / samples) if samples else None,
        "average_signed_return": statistics.fmean(values) if values else None,
        "median_signed_return": statistics.median(values) if values else None,
    }


def compute_scorecard(cycles: list[dict[str, Any]]) -> dict[str, Any]:
    sampled = bucket_cycles(cycles)
    points: list[tuple[float, float, dict[str, Any]]] = []
    for row in sampled:
        ts = parse_ts(row.get("ts"))
        px = finite_price(row.get("local_mid"))
        if ts is not None and px is not None:
            points.append((ts, px, row))
    times = [point[0] for point in points]
    signed: dict[str, dict[str, list[float]]] = defaultdict(lambda: defaultdict(list))

    for ts, px, row in points:
        votes = directional_votes(row)
        if not votes:
            continue
        for horizon_name, horizon_seconds in HORIZONS.items():
            target = ts + horizon_seconds
            j = bisect.bisect_left(times, target)
            if j >= len(points):
                continue
            future_ts, future_px, _future_row = points[j]
            if future_ts - target > MAX_FUTURE_SKEW_SECONDS:
                continue
            raw_return = future_px / px - 1.0
            for name, direction in votes:
                signed[name][horizon_name].append(direction * raw_return)

    agents: dict[str, Any] = {}
    for name in sorted(signed):
        horizons = {h: summarize(signed[name].get(h, [])) for h in HORIZONS}
        four_hour = horizons["4h"]
        agents[name] = {
            "horizons": horizons,
            "eligible_for_weight_review": bool(four_hour["sufficient"]),
        }

    reviewable = [name for name, value in agents.items() if value["eligible_for_weight_review"]]
    return {
        "ok": True,
        "generated_at_utc": utc_now(),
        "mode": "paper_observation_only",
        "weights_changed": False,
        "automatic_reweighting_enabled": False,
        "method": {
            "sample_bucket_seconds": SAMPLE_BUCKET_SECONDS,
            "future_horizons_seconds": HORIZONS,
            "max_future_skew_seconds": MAX_FUTURE_SKEW_SECONDS,
            "minimum_directional_samples": MIN_DIRECTIONAL_SAMPLES,
            "score": "signed future return; hit when vote direction matches future price direction",
        },
        "raw_cycle_rows": len(cycles),
        "sampled_rows": len(points),
        "reviewable_agents": reviewable,
        "ready_for_manual_weight_review": bool(reviewable),
        "agents": agents,
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--audit", type=Path, default=DEFAULT_AUDIT)
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    args = parser.parse_args()
    if not args.audit.is_file():
        raise SystemExit("FAIL-CLOSED: audit log not found")
    scorecard = compute_scorecard(read_cycles(args.audit))
    atomic_json(args.output, scorecard)
    print("AGENT_SCORECARD=PASS")
    print(f"SAMPLED_ROWS={scorecard['sampled_rows']}")
    print(f"REVIEWABLE_AGENTS={len(scorecard['reviewable_agents'])}")


if __name__ == "__main__":
    main()

from __future__ import annotations

import argparse
import json
import math
import statistics
from dataclasses import asdict, dataclass
from datetime import datetime
from pathlib import Path
from typing import Any, Iterable

MAX_CYCLE_LAG_SECONDS = 120.0
AGENTS = ("momentum", "order_book", "tape_order_flow", "global_trend")


def _finite(value: Any) -> float | None:
    try:
        number = float(value)
    except (TypeError, ValueError):
        return None
    return number if math.isfinite(number) else None


def _timestamp(value: Any) -> float | None:
    if not isinstance(value, str):
        return None
    try:
        return datetime.fromisoformat(value.replace("Z", "+00:00")).timestamp()
    except ValueError:
        return None


def _votes(cycle: dict[str, Any]) -> dict[str, int | None]:
    result = dict.fromkeys(AGENTS)
    for item in cycle.get("agents") or []:
        if not isinstance(item, dict) or item.get("agent") not in result:
            continue
        vote = _finite(item.get("vote"))
        result[str(item["agent"])] = int(vote) if vote in (-1, 0, 1) else None
    return result


@dataclass(frozen=True)
class Trade:
    entry_ts: float
    exit_ts: float
    pnl: float
    hold_seconds: float
    exit_reason: str
    entry_score: float | None
    spread_pct: float | None
    global_change_24h: float | None
    global_source_count: int
    votes: dict[str, int | None]
    current_tape_gate_would_block: bool


def read_jsonl(path: Path) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    with path.open(encoding="utf-8") as stream:
        for line_number, line in enumerate(stream, 1):
            try:
                row = json.loads(line)
            except json.JSONDecodeError as exc:
                raise ValueError(f"invalid JSON on line {line_number}") from exc
            if not isinstance(row, dict) or _timestamp(row.get("ts")) is None:
                raise ValueError(f"invalid audit row on line {line_number}")
            rows.append(row)
    return rows


def extract_trades(rows: Iterable[dict[str, Any]]) -> tuple[list[Trade], dict[str, int]]:
    ordered = list(rows)
    times = [_timestamp(row.get("ts")) for row in ordered]
    if any(value is None for value in times) or times != sorted(times):
        raise ValueError("audit timestamps must be valid and monotonic")
    open_entry: tuple[dict[str, Any], dict[str, Any]] | None = None
    trades: list[Trade] = []
    stats = {"unmatched_buys": 0, "unmatched_sells": 0, "cycle_misses": 0}

    def next_cycle(index: int, action: str) -> dict[str, Any] | None:
        event_ts = times[index]
        for later, later_ts in zip(ordered[index + 1 :], times[index + 1 :]):
            assert event_ts is not None and later_ts is not None
            if later_ts - event_ts > MAX_CYCLE_LAG_SECONDS:
                break
            if later.get("event") == "cycle" and later.get("action") == action and later.get("executed") is True:
                return later
        return None

    for index, row in enumerate(ordered):
        event = row.get("event")
        if event == "paper_buy":
            cycle = next_cycle(index, "BUY")
            if open_entry is not None:
                stats["unmatched_buys"] += 1
            open_entry = (row, cycle) if cycle is not None else None
            if cycle is None:
                stats["cycle_misses"] += 1
        elif event == "paper_sell":
            exit_cycle = next_cycle(index, "SELL")
            if open_entry is None:
                stats["unmatched_sells"] += 1
                continue
            buy, entry_cycle = open_entry
            open_entry = None
            if exit_cycle is None:
                stats["cycle_misses"] += 1
                continue
            entry_ts, exit_ts = _timestamp(buy["ts"]), _timestamp(row["ts"])
            pnl = _finite(row.get("pnl"))
            if entry_ts is None or exit_ts is None or pnl is None or exit_ts <= entry_ts:
                stats["unmatched_sells"] += 1
                continue
            votes = _votes(entry_cycle)
            sources = entry_cycle.get("global_sources")
            trades.append(Trade(
                entry_ts, exit_ts, pnl, exit_ts - entry_ts, str(row.get("reason") or "unknown"),
                _finite(entry_cycle.get("score")), _finite(entry_cycle.get("spread_pct")),
                _finite(entry_cycle.get("global_change_24h")), len(sources) if isinstance(sources, list) else 0,
                votes, votes["tape_order_flow"] == -1,
            ))
    if open_entry is not None:
        stats["unmatched_buys"] += 1
    return trades, stats


def summarize(trades: list[Trade], extraction: dict[str, int]) -> dict[str, Any]:
    def cohort(rows: list[Trade]) -> dict[str, Any]:
        return {
            "trades": len(rows),
            "total_pnl": sum(row.pnl for row in rows),
            "median_hold_seconds": statistics.median(row.hold_seconds for row in rows) if rows else None,
            "mean_entry_score": statistics.fmean(row.entry_score for row in rows if row.entry_score is not None) if any(row.entry_score is not None for row in rows) else None,
            "tape_conflict_count": sum(row.current_tape_gate_would_block for row in rows),
            "vote_positive_counts": {agent: sum(row.votes.get(agent) == 1 for row in rows) for agent in AGENTS},
            "exit_reason_counts": {reason: sum(row.exit_reason == reason for row in rows) for reason in sorted({row.exit_reason for row in rows})},
        }
    wins = [row for row in trades if row.pnl > 0]
    losses = [row for row in trades if row.pnl < 0]
    return {
        "ok": True,
        "mode": "offline_observation_only",
        "execution_authority": False,
        "automatic_promotion": False,
        "sample_warning": "descriptive_only_not_strategy_evidence" if len(wins) < 5 or len(losses) < 5 else None,
        "extraction": extraction,
        "all": cohort(trades), "wins": cohort(wins), "losses": cohort(losses),
        "trades": [asdict(row) for row in trades],
    }


def main() -> None:
    parser = argparse.ArgumentParser(description="Offline, read-only Paper trade cohort analysis")
    parser.add_argument("audit_jsonl", type=Path)
    args = parser.parse_args()
    print(json.dumps(summarize(*extract_trades(read_jsonl(args.audit_jsonl))), sort_keys=True))


if __name__ == "__main__":
    main()

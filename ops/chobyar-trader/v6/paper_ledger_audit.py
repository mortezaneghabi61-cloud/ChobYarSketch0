"""Read-only, fail-closed PAPER ledger reconciliation for historical audit events.

Reconstructs each completed trade from entry/exit price, quantity, and BOTH
recorded fees. Does not alter live state, historical logs, or any trading mode.
A reset of simulated account equity is NOT silently joined across epochs.
"""

from __future__ import annotations

import argparse
from datetime import datetime
from decimal import Decimal, InvalidOperation
import json
from pathlib import Path
from typing import Any, Iterable

TOLERANCE = Decimal("0.0000001")
ALLOWED = ("paper_buy", "paper_sell")


def _decimal(value: Any, field: str, *, positive: bool = False) -> Decimal:
    if isinstance(value, bool) or value is None:
        raise ValueError(f"FAIL_CLOSED: invalid {field}")
    try:
        out = Decimal(str(value))
    except (ValueError, TypeError, InvalidOperation) as exc:
        raise ValueError(f"FAIL_CLOSED: invalid {field}") from exc
    if not out.is_finite() or out < 0 or (positive and out <= 0):
        raise ValueError(f"FAIL_CLOSED: invalid {field}")
    return out


def _timestamp(row: dict[str, Any]) -> datetime:
    value = row.get("ts")
    try:
        dt = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except (AttributeError, TypeError, ValueError) as exc:
        raise ValueError("FAIL_CLOSED: invalid event timestamp") from exc
    if dt.utcoffset() is None:
        raise ValueError("FAIL_CLOSED: timezone-naive audit timestamp")
    return dt


def _event_trade_fields(row: dict[str, Any]) -> dict[str, Decimal]:
    return {
        "price": _decimal(row.get("price"), "price", positive=True),
        "qty": _decimal(row.get("qty"), "quantity", positive=True),
        "fee": _decimal(row.get("fee"), "fee"),
    }


def audit_rows(rows: Iterable[dict[str, Any]]) -> dict[str, Any]:
    """Return an independently reconstructed history with explicit ambiguities.

    Any non-monotonic timestamp, malformed execution or ambiguous position
    invalidates the report: never fabricate a balancing entry.
    """
    prior_time: datetime | None = None
    position: tuple[dict[str, Any], dict[str, Decimal]] | None = None
    last_cycle: dict[str, Any] | None = None
    startup_since_cycle = False
    trades = []
    rebases = []
    total_gross = Decimal(0)
    total_fees = Decimal(0)
    total_net = Decimal(0)
    total_logged = Decimal(0)
    mismatches = []
    buy_count = sell_count = 0
    event_count = 0

    for row in rows:
        event_count += 1
        if not isinstance(row, dict):
            raise ValueError("FAIL_CLOSED: non-object audit row")
        ts = _timestamp(row)
        if prior_time is not None and ts < prior_time:
            raise ValueError("FAIL_CLOSED: non-monotonic audit timestamps")
        prior_time = ts
        event = row.get("event")
        if event == "startup":
            startup_since_cycle = True
            continue
        if event == "cycle":
            current_realized = row.get("realized_pnl")
            if current_realized is not None:
                current_realized = _decimal_signed(current_realized, "cycle realized_pnl")
                if (startup_since_cycle and last_cycle is not None
                        and current_realized != last_cycle["realized_pnl"]):
                    rebases.append({
                        "after_startup_ts": row["ts"],
                        "previous_cycle_ts": last_cycle["ts"],
                        "previous_realized_pnl": float(last_cycle["realized_pnl"]),
                        "new_realized_pnl": float(current_realized),
                        "previous_equity": last_cycle.get("equity"),
                        "new_equity": float(_decimal_signed(row["equity"], "cycle equity"))
                        if row.get("equity") is not None else None,
                        "cause": "UNVERIFIED: state discontinuity after startup",
                    })
                last_cycle = {
                    "ts": row["ts"], "realized_pnl": current_realized,
                    "equity": float(_decimal_signed(row["equity"], "cycle equity"))
                    if row.get("equity") is not None else None,
                }
            startup_since_cycle = False
            continue
        if event not in ALLOWED:
            continue
        fields = _event_trade_fields(row)
        if event == "paper_buy":
            buy_count += 1
            if position is not None:
                raise ValueError("FAIL_CLOSED: overlapping paper buys")
            position = (row, fields)
            continue

        sell_count += 1
        if position is None:
            raise ValueError("FAIL_CLOSED: paper sell without matching buy")
        buy, entry = position
        position = None
        if fields["qty"] != entry["qty"]:
            raise ValueError("FAIL_CLOSED: buy/sell quantity differs")
        if _timestamp(row) <= _timestamp(buy):
            raise ValueError("FAIL_CLOSED: exit timestamp not later than entry")
        source_pnl = _decimal_signed(row.get("pnl"), "recorded sell pnl")
        gross = fields["qty"] * (fields["price"] - entry["price"])
        fees = entry["fee"] + fields["fee"]
        net = gross - fees
        delta = source_pnl - net
        trade_index = len(trades) + 1
        total_gross += gross
        total_fees += fees
        total_net += net
        total_logged += source_pnl
        if abs(delta) > TOLERANCE:
            mismatches.append({
                "trade_index": trade_index,
                "exit_ts": row["ts"],
                "reported_pnl": float(source_pnl),
                "reconstructed_pnl": float(net),
                "delta": float(delta),
                "entry_fee": float(entry["fee"]),
            })
        trades.append({
            "trade_index": trade_index,
            "entry_ts": buy["ts"], "exit_ts": row["ts"],
            "reconstructed_pnl": float(net),
            "reported_pnl": float(source_pnl),
            "gross_pnl": float(gross),
            "fees_both_sides": float(fees),
        })

    return {
        "mode": "offline_paper_audit_only",
        "execution_authority": False,
        "automatic_promotion": False,
        "ok": not mismatches and not rebases and position is None,
        "event_count": event_count,
        "buy_events": buy_count,
        "sell_events": sell_count,
        "closed_trades": len(trades),
        "open_position_unverified": position is not None,
        "reported_wins": sum(t["reported_pnl"] > 0 for t in trades),
        "reconstructed_wins": sum(t["reconstructed_pnl"] > 0 for t in trades),
        "gross_pnl": float(total_gross),
        "both_side_fees": float(total_fees),
        "reconstructed_net_pnl": float(total_net),
        "sum_logged_sell_pnl": float(total_logged),
        "reported_minus_reconstructed": float(total_logged - total_net),
        "pnl_mismatches": mismatches,
        "restart_state_discontinuities": rebases,
        "latest_cycle": {
            "ts": last_cycle["ts"],
            "reported_realized_pnl": float(last_cycle["realized_pnl"]),
            "reported_equity": last_cycle.get("equity"),
        } if last_cycle else None,
        "warning": "Historic sums may span independently reset paper sessions; "
                   "do not substitute cross-epoch PnL for current account equity",
        "trades": trades,
    }


def _decimal_signed(value: Any, field: str) -> Decimal:
    if isinstance(value, bool) or value is None:
        raise ValueError(f"FAIL_CLOSED: invalid {field}")
    try:
        out = Decimal(str(value))
    except (InvalidOperation, TypeError, ValueError) as exc:
        raise ValueError(f"FAIL_CLOSED: invalid {field}") from exc
    if not out.is_finite():
        raise ValueError(f"FAIL_CLOSED: invalid {field}")
    return out


def read_jsonl(path: Path) -> Iterable[dict[str, Any]]:
    with path.open(encoding="utf-8") as stream:
        for number, line in enumerate(stream, 1):
            try:
                yield json.loads(line)
            except json.JSONDecodeError as exc:
                raise ValueError(f"FAIL_CLOSED: malformed JSONL at line {number}") from exc


def main() -> None:
    parser = argparse.ArgumentParser(description="Read-only, fail-closed PAPER trading ledger audit")
    parser.add_argument("audit_jsonl", type=Path)
    parser.add_argument("--summary-only", action="store_true")
    args = parser.parse_args()
    report = audit_rows(read_jsonl(args.audit_jsonl))
    if args.summary_only:
        report.pop("trades")
    print(json.dumps(report, ensure_ascii=False, sort_keys=True))
    # Nonzero is intentional for any unresolved accounting or reset anomaly.
    raise SystemExit(0 if report["ok"] else 2)


if __name__ == "__main__":
    main()

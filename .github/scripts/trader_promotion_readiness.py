#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import math
from pathlib import Path
from typing import Any

MIN_FORWARD_TRADES = 30
MIN_FORWARD_UPTIME_DAYS = 14.0
MIN_BACKTEST_TRADES = 80
MIN_BACKTEST_DAYS = 90.0
MAX_FORWARD_DRAWDOWN = 0.05
MAX_BACKTEST_DRAWDOWN = 0.10
MIN_DATA_INTEGRITY_SCORE = 0.90
MIN_COUNCIL_SAMPLES = 30
MIN_SPECIALIST_QUALITY_COUNT = 2


def number(value: Any) -> float | None:
    try:
        result = float(value)
    except (TypeError, ValueError):
        return None
    return result if math.isfinite(result) else None


def evaluate(report: dict[str, Any]) -> dict[str, Any]:
    reasons: list[str] = []

    def require(condition: bool, code: str) -> None:
        if not condition:
            reasons.append(code)

    require(report.get("ok") is True, "REPORT_NOT_OK")
    require(report.get("public_report") is True, "NOT_SANITIZED_PUBLIC_REPORT")
    require(report.get("mode") == "paper", "MODE_NOT_PAPER")
    require(report.get("live_locked") is True, "LIVE_NOT_LOCKED")

    services = report.get("services") if isinstance(report.get("services"), dict) else {}
    for service in ("trader", "status", "v5_shadow_timer", "v5_scorecard_timer"):
        require(services.get(service) == "active", f"SERVICE_INACTIVE:{service}")

    shadow = report.get("v5_shadow") if isinstance(report.get("v5_shadow"), dict) else {}
    require(shadow.get("mode") == "shadow_observation_only", "V5_NOT_SHADOW_ONLY")
    require(shadow.get("execution_authority") is False, "V5_EXECUTION_AUTHORITY_PRESENT")
    require(shadow.get("automatic_promotion_enabled") is False, "AUTO_PROMOTION_ENABLED")
    require(shadow.get("automatic_reweighting_enabled") is False, "AUTO_REWEIGHTING_ENABLED")
    require(shadow.get("foreign_execution_enabled") is False, "FOREIGN_EXECUTION_ENABLED")
    require(shadow.get("geo_bypass_supported") is False, "GEO_BYPASS_PRESENT")

    forward = report.get("forward_test") if isinstance(report.get("forward_test"), dict) else {}
    f_trades = int(number(forward.get("closed_trades")) or 0)
    f_equity = number(forward.get("current_equity"))
    f_pnl = number(forward.get("realized_pnl"))
    f_dd = number(forward.get("max_drawdown_pct"))
    f_uptime_days = (number(forward.get("uptime_seconds")) or 0.0) / 86400.0
    require(f_trades >= MIN_FORWARD_TRADES, f"FORWARD_TRADES_TOO_LOW:{f_trades}<{MIN_FORWARD_TRADES}")
    require(f_uptime_days >= MIN_FORWARD_UPTIME_DAYS, f"FORWARD_UPTIME_TOO_LOW:{f_uptime_days:.2f}<{MIN_FORWARD_UPTIME_DAYS:.0f}d")
    require(f_equity is not None and f_pnl is not None and f_pnl > 0.0, "FORWARD_EXPECTANCY_NOT_POSITIVE")
    require(f_dd is not None and f_dd <= MAX_FORWARD_DRAWDOWN, "FORWARD_DRAWDOWN_TOO_HIGH_OR_UNKNOWN")

    backtest = report.get("backtest") if isinstance(report.get("backtest"), dict) else {}
    b_trades = int(number(backtest.get("closed_trades")) or 0)
    b_days = number(backtest.get("history_days")) or 0.0
    b_return = number(backtest.get("return_pct"))
    b_dd = number(backtest.get("max_drawdown_pct"))
    require(backtest.get("ok") is True, "BACKTEST_NOT_OK")
    require(backtest.get("full_fidelity_multiagent") is True, "BACKTEST_NOT_FULL_FIDELITY_MULTIAGENT")
    require(b_trades >= MIN_BACKTEST_TRADES, f"BACKTEST_TRADES_TOO_LOW:{b_trades}<{MIN_BACKTEST_TRADES}")
    require(b_days >= MIN_BACKTEST_DAYS, f"BACKTEST_HISTORY_TOO_SHORT:{b_days:.2f}<{MIN_BACKTEST_DAYS:.0f}d")
    require(b_return is not None and b_return > 0.0, "BACKTEST_RETURN_NOT_POSITIVE")
    require(b_dd is not None and b_dd <= MAX_BACKTEST_DRAWDOWN, "BACKTEST_DRAWDOWN_TOO_HIGH_OR_UNKNOWN")

    meta = report.get("v5_meta") if isinstance(report.get("v5_meta"), dict) else {}
    integrity = meta.get("data_integrity") if isinstance(meta.get("data_integrity"), dict) else {}
    integrity_score = number(integrity.get("score"))
    require(integrity.get("healthy") is True, "DATA_INTEGRITY_UNHEALTHY")
    require(integrity_score is not None and integrity_score >= MIN_DATA_INTEGRITY_SCORE,
            f"DATA_INTEGRITY_SCORE_TOO_LOW:{integrity_score}")

    health = shadow.get("source_health") if isinstance(shadow.get("source_health"), dict) else {}
    require(health.get("breadth_source") in {"okx", "kucoin"}, "BREADTH_FALLBACK_NOT_PROVEN_ACTIVE")
    require(health.get("funding_source") in {"okx", "kucoin"}, "FUNDING_FALLBACK_NOT_PROVEN_ACTIVE")
    require(health.get("open_interest_source") in {"okx", "kucoin"}, "OPEN_INTEREST_FALLBACK_NOT_PROVEN_ACTIVE")
    require(len(health.get("resolved_breadth_symbols") or []) >= 3, "BREADTH_COVERAGE_INSUFFICIENT")
    require(int(number(health.get("resolved_funding_samples")) or 0) >= 5, "FUNDING_SAMPLES_INSUFFICIENT")
    require(health.get("oi_change_available") is True, "OI_CHANGE_UNAVAILABLE")

    scorecard = report.get("v5_specialist_scorecard") if isinstance(report.get("v5_specialist_scorecard"), dict) else {}
    require(scorecard.get("execution_authority") is False, "SCORECARD_EXECUTION_AUTHORITY_PRESENT")
    specialists = scorecard.get("specialists") if isinstance(scorecard.get("specialists"), dict) else {}

    council = specialists.get("specialist_council_shadow") if isinstance(specialists.get("specialist_council_shadow"), dict) else {}
    council_h = council.get("horizons") if isinstance(council.get("horizons"), dict) else {}
    for horizon in ("4h", "12h"):
        stat = council_h.get(horizon) if isinstance(council_h.get(horizon), dict) else {}
        samples = int(number(stat.get("samples")) or 0)
        avg = number(stat.get("average_signed_return"))
        hit = number(stat.get("hit_rate"))
        require(samples >= MIN_COUNCIL_SAMPLES and stat.get("sufficient") is True,
                f"COUNCIL_{horizon}_SAMPLES_INSUFFICIENT:{samples}")
        require(avg is not None and avg > 0.0, f"COUNCIL_{horizon}_EXPECTANCY_NOT_POSITIVE")
        require(hit is not None and hit >= 0.50, f"COUNCIL_{horizon}_HIT_RATE_BELOW_50")

    quality = 0
    for name, specialist in specialists.items():
        if name == "specialist_council_shadow" or not isinstance(specialist, dict):
            continue
        horizons = specialist.get("horizons") if isinstance(specialist.get("horizons"), dict) else {}
        good = True
        for horizon in ("4h", "12h"):
            stat = horizons.get(horizon) if isinstance(horizons.get(horizon), dict) else {}
            avg = number(stat.get("average_signed_return"))
            hit = number(stat.get("hit_rate"))
            samples = int(number(stat.get("samples")) or 0)
            good = good and stat.get("sufficient") is True and samples >= 30 and avg is not None and avg > 0 and hit is not None and hit >= 0.50
        if good:
            quality += 1
    require(quality >= MIN_SPECIALIST_QUALITY_COUNT,
            f"POSITIVE_SPECIALISTS_TOO_FEW:{quality}<{MIN_SPECIALIST_QUALITY_COUNT}")

    ready = not reasons
    return {
        "ready_for_v5_paper_execution_review": ready,
        "execution_authority_granted": False,
        "live_authority_granted": False,
        "automatic_promotion": False,
        "reasons": reasons,
        "metrics": {
            "forward_closed_trades": f_trades,
            "forward_uptime_days": round(f_uptime_days, 3),
            "forward_realized_pnl": f_pnl,
            "forward_max_drawdown_pct": f_dd,
            "backtest_closed_trades": b_trades,
            "backtest_history_days": b_days,
            "backtest_return_pct": b_return,
            "backtest_max_drawdown_pct": b_dd,
            "data_integrity_score": integrity_score,
            "positive_specialists_4h_12h": quality,
        },
    }


def main() -> int:
    parser = argparse.ArgumentParser(description="Read-only ChobYar Trader V5 promotion-readiness gate")
    parser.add_argument("report", type=Path)
    parser.add_argument("--require-ready", action="store_true", help="exit 2 when readiness criteria are not satisfied")
    args = parser.parse_args()
    payload = json.loads(args.report.read_text(encoding="utf-8"))
    if not isinstance(payload, dict):
        raise SystemExit("report must be a JSON object")
    result = evaluate(payload)
    print(json.dumps(result, ensure_ascii=False, sort_keys=True))
    if args.require_ready and not result["ready_for_v5_paper_execution_review"]:
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

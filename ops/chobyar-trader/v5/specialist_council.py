from __future__ import annotations

import math
import statistics
from dataclasses import dataclass
from typing import Any


@dataclass(frozen=True)
class CouncilContext:
    candles: list[list[float]]
    local_mid: float
    spread_pct: float
    book_imbalance: float
    tape_buy_ratio: float
    global_change_24h: float | None
    global_dispersion_pct: float | None
    global_source_count: int
    funding_rate: float | None
    funding_z: float | None
    oi_change_pct: float | None
    breadth_24h: dict[str, float]


def finite(value: Any) -> float | None:
    try:
        number = float(value)
    except (TypeError, ValueError):
        return None
    return number if math.isfinite(number) else None


def clamp(value: float, low: float = -1.0, high: float = 1.0) -> float:
    return max(low, min(high, value))


def sign(value: float, deadband: float = 0.0) -> int:
    if value > deadband:
        return 1
    if value < -deadband:
        return -1
    return 0


def _validate_candles(candles: list[list[float]]) -> None:
    if len(candles) < 80:
        raise ValueError("specialist council requires at least 80 hourly candles")
    previous = -1.0
    for row in candles:
        if len(row) < 6:
            raise ValueError("malformed candle")
        ts, opened, high, low, close, _volume = (float(x) for x in row[:6])
        if ts <= previous or min(opened, high, low, close) <= 0 or not all(math.isfinite(x) for x in (ts, opened, high, low, close)):
            raise ValueError("invalid candle")
        if high < max(opened, close) or low > min(opened, close):
            raise ValueError("invalid OHLC")
        previous = ts


def _regime_features(candles: list[list[float]]) -> dict[str, float | str]:
    _validate_candles(candles)
    closes = [float(row[4]) for row in candles]
    highs = [float(row[2]) for row in candles]
    lows = [float(row[3]) for row in candles]
    returns = [closes[i] / closes[i - 1] - 1.0 for i in range(1, len(closes))]
    true_ranges: list[float] = []
    for i in range(1, len(closes)):
        prev = closes[i - 1]
        tr = max(highs[i] - lows[i], abs(highs[i] - prev), abs(lows[i] - prev)) / prev
        true_ranges.append(tr)

    ma20 = statistics.fmean(closes[-20:])
    ma50 = statistics.fmean(closes[-50:])
    separation = (ma20 - ma50) / ma50
    window = closes[-25:]
    path = sum(abs(window[i] - window[i - 1]) for i in range(1, len(window)))
    efficiency = abs(window[-1] - window[0]) / path if path > 0 else 0.0
    atr14 = statistics.fmean(true_ranges[-14:])
    median_tr = statistics.median(true_ranges[-60:])
    sigma48 = statistics.pstdev(returns[-48:]) if len(returns) >= 48 else statistics.pstdev(returns)
    last_return = returns[-1]
    shock = abs(last_return) > max(0.01, 3.0 * sigma48) or true_ranges[-1] > max(0.015, 3.0 * median_tr)
    volatile = atr14 > max(0.012, 1.8 * median_tr)

    if shock:
        label = "SHOCK"
    elif volatile and efficiency < 0.32:
        label = "VOLATILE"
    elif separation > 0.002 and efficiency >= 0.32:
        label = "TREND_UP"
    elif separation < -0.002 and efficiency >= 0.32:
        label = "TREND_DOWN"
    elif efficiency < 0.22 and abs(separation) < 0.003:
        label = "RANGE"
    else:
        label = "UNCERTAIN"

    confidence = clamp(0.45 * min(abs(separation) / 0.01, 1.0) + 0.45 * min(efficiency / 0.7, 1.0) + 0.10, 0.0, 1.0)
    if label in {"SHOCK", "VOLATILE"}:
        confidence = max(confidence, 0.75)
    return {
        "label": label,
        "confidence": confidence,
        "ma20_ma50_sep": separation,
        "efficiency_24h": efficiency,
        "atr14_pct": atr14,
        "last_return_1h": last_return,
        "sigma48": sigma48,
    }


def specialist(agent: str, vote: int, confidence: float, reason: str, *, available: bool = True, features: dict[str, Any] | None = None, veto: bool = False) -> dict[str, Any]:
    if vote not in (-1, 0, 1):
        raise ValueError("invalid specialist vote")
    return {
        "agent": agent,
        "vote": vote,
        "confidence": clamp(float(confidence), 0.0, 1.0),
        "available": bool(available),
        "veto": bool(veto),
        "reason": reason,
        "features": features or {},
    }


def regime_structure(ctx: CouncilContext, regime: dict[str, Any]) -> dict[str, Any]:
    label = str(regime["label"])
    direction = 1 if label == "TREND_UP" else -1 if label == "TREND_DOWN" else 0
    return specialist(
        "regime_structure",
        direction,
        float(regime["confidence"]),
        f"regime={label}; efficiency={float(regime['efficiency_24h']):.3f}; ma_sep={float(regime['ma20_ma50_sep']):+.3%}",
        features={"regime": label, "efficiency": regime["efficiency_24h"], "ma_separation": regime["ma20_ma50_sep"], "atr14_pct": regime["atr14_pct"]},
    )


def microstructure_liquidity(ctx: CouncilContext) -> dict[str, Any]:
    spread = max(0.0, float(ctx.spread_pct))
    book = clamp(float(ctx.book_imbalance) / 0.25)
    tape = clamp((float(ctx.tape_buy_ratio) - 0.5) / 0.15)
    raw = 0.55 * book + 0.45 * tape
    conflict = sign(book, 0.25) != 0 and sign(tape, 0.25) != 0 and sign(book) != sign(tape)
    if conflict:
        direction = 0
        reason = "book/tape conflict; abstain"
    else:
        direction = sign(raw, 0.35)
        reason = f"book={book:+.2f}; tape={tape:+.2f}; spread={spread:.3%}"
    spread_quality = clamp(1.0 - spread / 0.006, 0.0, 1.0)
    confidence = min(1.0, abs(raw)) * spread_quality
    return specialist(
        "microstructure_liquidity",
        direction,
        confidence,
        reason,
        features={"book_score": book, "tape_score": tape, "spread_pct": spread, "conflict": conflict},
    )


def derivatives_positioning(ctx: CouncilContext, regime: dict[str, Any]) -> dict[str, Any]:
    funding = finite(ctx.funding_rate)
    funding_z = finite(ctx.funding_z)
    oi_change = finite(ctx.oi_change_pct)
    if funding is None or funding_z is None or oi_change is None:
        return specialist("derivatives_positioning", 0, 0.0, "funding/OI history incomplete", available=False)

    label = str(regime["label"])
    trend_dir = 1 if label == "TREND_UP" else -1 if label == "TREND_DOWN" else 0
    overcrowded = abs(funding_z) >= 2.0
    rising_oi = oi_change > 0.002
    falling_oi = oi_change < -0.002

    if overcrowded and label in {"RANGE", "UNCERTAIN", "VOLATILE"}:
        direction = -sign(funding_z)
        reason = "extreme funding crowding without clean trend"
        confidence = min(1.0, abs(funding_z) / 3.5) * 0.8
    elif rising_oi and trend_dir:
        direction = trend_dir
        reason = "open interest expanding with established trend"
        confidence = min(1.0, abs(oi_change) / 0.02) * (0.8 if abs(funding_z) < 2.0 else 0.5)
    elif falling_oi:
        direction = 0
        reason = "open interest contracting; conviction reduced"
        confidence = 0.25
    else:
        direction = 0
        reason = "derivatives positioning inconclusive"
        confidence = 0.2

    return specialist(
        "derivatives_positioning",
        direction,
        confidence,
        reason,
        features={"funding_rate": funding, "funding_z": funding_z, "oi_change_pct": oi_change, "overcrowded": overcrowded},
    )


def cross_market_breadth(ctx: CouncilContext) -> dict[str, Any]:
    required = ("BTC-USDT", "ETH-USDT", "SOL-USDT")
    changes: list[float] = []
    for name in required:
        value = finite(ctx.breadth_24h.get(name))
        if value is None:
            return specialist("cross_market_breadth", 0, 0.0, f"breadth missing {name}", available=False)
        changes.append(value)
    positive = sum(value > 0.003 for value in changes)
    negative = sum(value < -0.003 for value in changes)
    if positive >= 2:
        direction = 1
    elif negative >= 2:
        direction = -1
    else:
        direction = 0
    mean_change = statistics.fmean(changes)
    confidence = min(1.0, abs(mean_change) / 0.04) * (1.0 if direction else 0.35)
    return specialist(
        "cross_market_breadth",
        direction,
        confidence,
        f"BTC/ETH/SOL breadth mean={mean_change:+.2%}; aligned={max(positive, negative)}/3",
        features={"changes_24h": dict(zip(required, changes)), "aligned": max(positive, negative)},
    )


def adversarial_risk(ctx: CouncilContext, regime: dict[str, Any], micro: dict[str, Any]) -> dict[str, Any]:
    flags: list[str] = []
    severe: list[str] = []
    if ctx.spread_pct > 0.004:
        flags.append("wide_spread")
    if ctx.spread_pct > 0.006:
        severe.append("spread_hard_limit")
    if ctx.global_source_count < 2:
        flags.append("weak_global_source_diversity")
    dispersion = finite(ctx.global_dispersion_pct)
    if dispersion is None:
        flags.append("global_dispersion_unknown")
    elif dispersion > 0.01:
        flags.append("global_price_dispersion")
    if dispersion is not None and dispersion > 0.02:
        severe.append("global_sources_disagree")
    if str(regime["label"]) == "SHOCK":
        severe.append("shock_regime")
    elif str(regime["label"]) == "VOLATILE":
        flags.append("volatile_regime")
    if bool((micro.get("features") or {}).get("conflict")):
        flags.append("book_tape_conflict")
    funding_z = finite(ctx.funding_z)
    if funding_z is not None and abs(funding_z) >= 3.0:
        flags.append("extreme_funding")

    veto = bool(severe)
    confidence = 1.0 if veto else min(0.9, 0.25 + 0.12 * len(flags))
    reason = "veto: " + ",".join(severe) if veto else ("caution: " + ",".join(flags) if flags else "no material adversarial risk flag")
    return specialist(
        "adversarial_risk",
        0,
        confidence,
        reason,
        features={"flags": flags, "severe_flags": severe},
        veto=veto,
    )


def run_council(ctx: CouncilContext) -> dict[str, Any]:
    regime = _regime_features(ctx.candles)
    r = regime_structure(ctx, regime)
    m = microstructure_liquidity(ctx)
    d = derivatives_positioning(ctx, regime)
    b = cross_market_breadth(ctx)
    a = adversarial_risk(ctx, regime, m)
    directional = [r, m, d, b]
    available = [item for item in directional if item["available"]]
    score = sum(int(item["vote"]) * float(item["confidence"]) for item in available)
    if a["veto"] or len(available) < 3:
        action = "WAIT"
    elif score >= 1.25:
        action = "BUY"
    elif score <= -1.25:
        action = "SELL"
    else:
        action = "WAIT"
    return {
        "ok": True,
        "mode": "shadow_observation_only",
        "execution_authority": False,
        "automatic_promotion_enabled": False,
        "automatic_reweighting_enabled": False,
        "foreign_execution_enabled": False,
        "geo_bypass_supported": False,
        "regime": regime,
        "specialists": directional + [a],
        "shadow_consensus": {
            "action": action,
            "score": score,
            "available_directional_specialists": len(available),
            "risk_veto": bool(a["veto"]),
        },
    }

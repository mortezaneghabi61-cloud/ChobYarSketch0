from __future__ import annotations

import math
import statistics
import time
from dataclasses import replace
from typing import Any

from specialist_council import CouncilContext, clamp, finite, run_council

MIN_CALIBRATION_SAMPLES = 30
DIRECTIONAL_AGENTS = (
    "regime_structure",
    "microstructure_liquidity",
    "derivatives_positioning",
    "cross_market_breadth",
)


def _scorecard_4h(scorecard: dict[str, Any], agent: str) -> tuple[int, float | None]:
    try:
        node = scorecard.get("specialists", {}).get(agent, {})
        all_regime = node.get("regimes", {}).get("ALL", {})
        horizon = all_regime.get("horizons", {}).get("4h", {})
        samples = max(0, int(horizon.get("samples") or 0))
        hit = finite(horizon.get("hit_rate"))
        if hit is not None:
            hit = clamp(hit, 0.0, 1.0)
        return samples, hit
    except Exception:
        return 0, None


def calibrate_specialists(items: list[dict[str, Any]], scorecard: dict[str, Any]) -> list[dict[str, Any]]:
    """Shrink confidence until forward evidence exists; never inflate raw confidence."""
    out: list[dict[str, Any]] = []
    for raw in items:
        item = dict(raw)
        raw_conf = clamp(float(item.get("confidence") or 0.0), 0.0, 1.0)
        samples, hit = _scorecard_4h(scorecard, str(item.get("agent") or ""))
        sample_reliability = min(1.0, samples / MIN_CALIBRATION_SAMPLES)
        skill_reliability = 0.5 if hit is None else 0.5 + 0.5 * hit
        reliability = sample_reliability * skill_reliability
        calibrated = raw_conf * (0.5 + 0.5 * reliability)
        item["raw_confidence"] = raw_conf
        item["confidence"] = min(raw_conf, calibrated)
        item["calibration"] = {
            "samples_4h": samples,
            "hit_rate_4h": hit,
            "minimum_samples": MIN_CALIBRATION_SAMPLES,
            "reliability": reliability,
        }
        out.append(item)
    return out


def data_integrity(ctx: CouncilContext, now_ts: float | None = None) -> dict[str, Any]:
    now_ts = time.time() if now_ts is None else float(now_ts)
    timestamps = [float(row[0]) for row in ctx.candles if len(row) >= 1]
    gaps = [timestamps[i] - timestamps[i - 1] for i in range(1, len(timestamps))]
    large_gaps = sum(gap > 2.1 * 3600 for gap in gaps)
    last_age = max(0.0, now_ts - timestamps[-1]) if timestamps else float("inf")
    candles_ok = len(timestamps) >= 80 and large_gaps == 0 and last_age <= 3.5 * 3600
    local_ok = all(
        value is not None and math.isfinite(float(value))
        for value in (ctx.local_mid, ctx.spread_pct, ctx.book_imbalance, ctx.tape_buy_ratio)
    )
    global_ok = int(ctx.global_source_count) >= 2
    breadth_count = sum(1 for key in ("BTC-USDT", "ETH-USDT", "SOL-USDT") if finite(ctx.breadth_24h.get(key)) is not None)
    breadth_score = breadth_count / 3.0
    score = 0.40 * float(local_ok) + 0.30 * float(candles_ok) + 0.15 * float(global_ok) + 0.15 * breadth_score
    flags: list[str] = []
    if not local_ok:
        flags.append("local_fields_invalid")
    if not candles_ok:
        flags.append("hourly_candle_integrity")
    if not global_ok:
        flags.append("global_source_diversity")
    if breadth_count < 2:
        flags.append("cross_market_breadth_incomplete")
    return {
        "score": score,
        "healthy": score >= 0.55 and local_ok and candles_ok,
        "flags": flags,
        "hourly_candles": len(timestamps),
        "large_gap_count": large_gaps,
        "latest_candle_age_seconds": last_age if math.isfinite(last_age) else None,
        "breadth_symbols": breadth_count,
    }


def regime_transition_risk(ctx: CouncilContext) -> dict[str, Any]:
    closes = [float(row[4]) for row in ctx.candles]
    if len(closes) < 60:
        return {"score": 1.0, "flags": ["insufficient_history"], "ma_separation_now": None, "ma_separation_6h_ago": None}

    def sep(end: int) -> float:
        chunk = closes[:end] if end else closes
        ma20 = statistics.fmean(chunk[-20:])
        ma50 = statistics.fmean(chunk[-50:])
        return (ma20 - ma50) / ma50

    now_sep = sep(0)
    prev_sep = sep(-6)
    returns = [closes[i] / closes[i - 1] - 1.0 for i in range(1, len(closes))]
    recent_abs = statistics.fmean(abs(x) for x in returns[-6:])
    prior_abs = statistics.fmean(abs(x) for x in returns[-30:-6]) if len(returns) >= 30 else statistics.fmean(abs(x) for x in returns[:-6])
    vol_ratio = recent_abs / prior_abs if prior_abs > 1e-12 else 1.0

    flags: list[str] = []
    score = 0.0
    if now_sep * prev_sep < 0:
        score += 0.70
        flags.append("ma_structure_crossing")
    elif abs(prev_sep) > 0.001 and abs(now_sep) < 0.65 * abs(prev_sep):
        score += 0.40
        flags.append("trend_structure_weakening")
    if vol_ratio >= 2.0:
        score += 0.35
        flags.append("short_horizon_volatility_jump")
    elif vol_ratio >= 1.5:
        score += 0.20
        flags.append("volatility_rising")
    if returns[-1] * statistics.fmean(returns[-6:]) < 0 and abs(returns[-1]) > 0.006:
        score += 0.15
        flags.append("large_countertrend_hour")
    return {
        "score": clamp(score, 0.0, 1.0),
        "flags": flags,
        "ma_separation_now": now_sep,
        "ma_separation_6h_ago": prev_sep,
        "recent_vs_prior_abs_return_ratio": vol_ratio,
    }


def execution_stress(ctx: CouncilContext, regime: dict[str, Any]) -> dict[str, Any]:
    spread = max(0.0, float(ctx.spread_pct))
    dispersion = finite(ctx.global_dispersion_pct)
    atr = finite(regime.get("atr14_pct")) or 0.0
    spread_component = clamp(spread / 0.004, 0.0, 1.0)
    dispersion_component = 0.5 if dispersion is None else clamp(abs(dispersion) / 0.01, 0.0, 1.0)
    volatility_component = clamp(atr / 0.018, 0.0, 1.0)
    score = 0.50 * spread_component + 0.25 * dispersion_component + 0.25 * volatility_component
    flags: list[str] = []
    if spread_component >= 0.75:
        flags.append("spread_stress")
    if dispersion_component >= 0.75:
        flags.append("venue_dispersion_stress")
    if volatility_component >= 0.85:
        flags.append("volatility_execution_stress")
    return {
        "score": score,
        "flags": flags,
        "spread_component": spread_component,
        "venue_dispersion_component": dispersion_component,
        "volatility_component": volatility_component,
        "model": "read_only_execution_stress_proxy",
    }


def epistemic_uncertainty(items: list[dict[str, Any]]) -> dict[str, Any]:
    directional = [item for item in items if item.get("agent") in DIRECTIONAL_AGENTS and item.get("available") is True]
    pos = sum(float(item.get("confidence") or 0.0) for item in directional if int(item.get("vote") or 0) > 0)
    neg = sum(float(item.get("confidence") or 0.0) for item in directional if int(item.get("vote") or 0) < 0)
    total = pos + neg
    coverage = len(directional) / len(DIRECTIONAL_AGENTS)
    if total <= 1e-12:
        entropy = 1.0
        margin = 0.0
    else:
        p = pos / total
        q = neg / total
        entropy = 0.0
        for x in (p, q):
            if x > 1e-12:
                entropy -= x * math.log(x, 2)
        margin = abs(pos - neg) / total
    score = clamp(0.50 * entropy + 0.30 * (1.0 - coverage) + 0.20 * (1.0 - margin), 0.0, 1.0)
    return {
        "score": score,
        "directional_coverage": coverage,
        "positive_weight": pos,
        "negative_weight": neg,
        "binary_entropy": entropy,
        "directional_margin": margin,
    }


def _raw_action(score: float, available_count: int, risk_veto: bool) -> str:
    if risk_veto or available_count < 3:
        return "WAIT"
    if score >= 1.25:
        return "BUY"
    if score <= -1.25:
        return "SELL"
    return "WAIT"


def decision_fragility(ctx: CouncilContext, candidate_action: str) -> dict[str, Any]:
    if candidate_action not in {"BUY", "SELL"}:
        return {"fragile": False, "flip_count": 0, "trials": 0, "actions": []}
    perturbations = [
        replace(ctx, spread_pct=max(0.0, ctx.spread_pct * 1.25)),
        replace(ctx, book_imbalance=ctx.book_imbalance * 0.90),
        replace(ctx, tape_buy_ratio=0.5 + (ctx.tape_buy_ratio - 0.5) * 0.90),
        replace(ctx, global_dispersion_pct=(ctx.global_dispersion_pct * 1.20 if ctx.global_dispersion_pct is not None else None)),
    ]
    actions = [str(run_council(p)["shadow_consensus"]["action"]) for p in perturbations]
    flip_count = sum(action != candidate_action for action in actions)
    return {"fragile": flip_count >= 2, "flip_count": flip_count, "trials": len(actions), "actions": actions}


def enhance_council(ctx: CouncilContext, council: dict[str, Any], scorecard: dict[str, Any] | None = None, *, now_ts: float | None = None) -> dict[str, Any]:
    """Add conservative meta-intelligence to shadow output only.

    This function cannot create execution authority. It may only keep a directional
    shadow decision or downgrade it to WAIT.
    """
    scorecard = scorecard if isinstance(scorecard, dict) else {}
    result = dict(council)
    specialists = calibrate_specialists(list(council.get("specialists") or []), scorecard)
    result["specialists"] = specialists

    available = [item for item in specialists if item.get("agent") in DIRECTIONAL_AGENTS and item.get("available") is True]
    calibrated_score = sum(int(item.get("vote") or 0) * float(item.get("confidence") or 0.0) for item in available)
    original_consensus = council.get("shadow_consensus") or {}
    risk_veto = bool(original_consensus.get("risk_veto"))
    candidate_action = _raw_action(calibrated_score, len(available), risk_veto)

    integrity = data_integrity(ctx, now_ts=now_ts)
    transition = regime_transition_risk(ctx)
    execution = execution_stress(ctx, council.get("regime") or {})
    uncertainty = epistemic_uncertainty(specialists)
    fragility = decision_fragility(ctx, candidate_action)

    holds: list[str] = []
    if not integrity["healthy"]:
        holds.append("data_integrity")
    if transition["score"] >= 0.70:
        holds.append("regime_transition")
    if execution["score"] >= 0.80:
        holds.append("execution_stress")
    if uncertainty["score"] >= 0.65:
        holds.append("epistemic_uncertainty")
    if fragility["fragile"]:
        holds.append("decision_fragility")

    final_action = "WAIT" if holds else candidate_action
    result["execution_authority"] = False
    result["automatic_promotion_enabled"] = False
    result["automatic_reweighting_enabled"] = False
    result["foreign_execution_enabled"] = False
    result["geo_bypass_supported"] = False
    result["shadow_consensus"] = {
        **original_consensus,
        "raw_score": finite(original_consensus.get("score")),
        "score": calibrated_score,
        "pre_meta_action": candidate_action,
        "action": final_action,
        "available_directional_specialists": len(available),
        "risk_veto": risk_veto,
        "meta_hold": bool(holds),
        "meta_hold_reasons": holds,
    }
    result["meta_intelligence"] = {
        "mode": "shadow_observation_only",
        "execution_authority": False,
        "confidence_calibration": {
            "minimum_4h_samples": MIN_CALIBRATION_SAMPLES,
            "never_inflates_raw_confidence": True,
        },
        "data_integrity": integrity,
        "regime_transition": transition,
        "execution_stress": execution,
        "epistemic_uncertainty": uncertainty,
        "decision_fragility": fragility,
    }
    return result

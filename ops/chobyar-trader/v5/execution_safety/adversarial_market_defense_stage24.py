from __future__ import annotations

import math
import re
import unicodedata
from dataclasses import dataclass
from statistics import median
from typing import Iterable, Sequence

# Stage-24 is deliberately observation/gating only. It has no exchange mutation surface.
# External text is treated as untrusted data, never as instructions.

MIN_PRICE_QUORUM = 3
MAX_GLOBAL_DISPERSION = 0.0075      # 0.75%
MAX_LOCAL_GLOBAL_DIVERGENCE = 0.0125 # 1.25%
MAX_SPREAD = 0.0075                 # 0.75%
MAX_SINGLE_STEP_MOVE = 0.0200       # 2.00%
MAX_CANCEL_RATIO = 0.90
MAX_DEPTH_FLIP_RATIO = 0.80
MIN_TRADE_TO_QUOTE_RATIO = 0.02

INJECTION_PATTERNS = tuple(
    re.compile(p, re.IGNORECASE)
    for p in (
        r"\bignore\s+(all\s+)?(previous|prior|system|developer)\b",
        r"\b(disregard|override|bypass)\s+(the\s+)?(previous|prior|system|developer|safety)\b",
        r"\b(system|developer)\s+prompt\b",
        r"\breveal\s+(your\s+)?(instructions|prompt|secrets?|api\s*key)\b",
        r"\bcall\s+(the\s+)?(tool|plugin|function)\b",
        r"\bexecute\s+(this|the)\s+(command|instruction)\b",
        r"\byou\s+are\s+now\b",
        r"\bdo\s+not\s+follow\s+(your|the)\s+(rules|instructions)\b",
    )
)

BIDI_CONTROL_CATEGORIES = {"Cf"}


@dataclass(frozen=True)
class ExternalContent:
    source_id: str
    text: str
    trusted_source: bool = False


@dataclass(frozen=True)
class MarketEvidence:
    local_bid: float
    local_ask: float
    local_last: float
    global_prices: Sequence[float]
    previous_local_last: float | None = None
    cancel_ratio: float | None = None
    depth_flip_ratio: float | None = None
    trade_to_quote_ratio: float | None = None


@dataclass(frozen=True)
class DefenseDecision:
    allowed: bool
    reason: str
    risk_score: float
    flags: tuple[str, ...]
    trusted_text_sources: int
    price_quorum: int


def _finite_positive(value: float) -> bool:
    return isinstance(value, (int, float)) and math.isfinite(float(value)) and float(value) > 0


def _ratio_distance(a: float, b: float) -> float:
    if not (_finite_positive(a) and _finite_positive(b)):
        return math.inf
    return abs(float(a) - float(b)) / ((float(a) + float(b)) / 2.0)


def _contains_unicode_controls(text: str) -> bool:
    for ch in text:
        if unicodedata.category(ch) in BIDI_CONTROL_CATEGORIES:
            return True
    return False


def external_content_flags(items: Iterable[ExternalContent]) -> tuple[set[str], int]:
    flags: set[str] = set()
    trusted_sources: set[str] = set()
    for item in items:
        source_id = (item.source_id or "").strip().lower()
        text = item.text or ""
        if item.trusted_source and source_id:
            trusted_sources.add(source_id)
        if _contains_unicode_controls(text):
            flags.add("hidden_unicode_control")
        if any(pattern.search(text) for pattern in INJECTION_PATTERNS):
            flags.add("prompt_injection_like_content")
        if len(text) > 250_000:
            flags.add("oversized_external_content")
    return flags, len(trusted_sources)


def market_flags(e: MarketEvidence) -> tuple[set[str], int]:
    flags: set[str] = set()
    if not all(_finite_positive(x) for x in (e.local_bid, e.local_ask, e.local_last)):
        return {"invalid_local_market_data"}, 0
    if e.local_ask < e.local_bid:
        flags.add("crossed_local_market")

    spread = (e.local_ask - e.local_bid) / ((e.local_ask + e.local_bid) / 2.0)
    if spread > MAX_SPREAD:
        flags.add("abnormal_spread")

    prices = [float(x) for x in e.global_prices if _finite_positive(x)]
    quorum = len(prices)
    if quorum < MIN_PRICE_QUORUM:
        flags.add("global_price_quorum_insufficient")
    else:
        center = median(prices)
        dispersion = (max(prices) - min(prices)) / center if center > 0 else math.inf
        if dispersion > MAX_GLOBAL_DISPERSION:
            flags.add("global_price_dispersion_high")
        if _ratio_distance(e.local_last, center) > MAX_LOCAL_GLOBAL_DIVERGENCE:
            flags.add("local_global_divergence")

    if e.previous_local_last is not None:
        if not _finite_positive(e.previous_local_last):
            flags.add("invalid_previous_market_data")
        elif _ratio_distance(e.local_last, e.previous_local_last) > MAX_SINGLE_STEP_MOVE:
            flags.add("single_step_price_shock")

    # These metrics require order-book history. We never infer spoofing from one snapshot.
    if e.cancel_ratio is not None:
        if not 0 <= e.cancel_ratio <= 1:
            flags.add("invalid_cancel_ratio")
        elif e.cancel_ratio >= MAX_CANCEL_RATIO:
            flags.add("extreme_order_cancellation")
    if e.depth_flip_ratio is not None:
        if not 0 <= e.depth_flip_ratio <= 1:
            flags.add("invalid_depth_flip_ratio")
        elif e.depth_flip_ratio >= MAX_DEPTH_FLIP_RATIO:
            flags.add("rapid_depth_flip")
    if e.trade_to_quote_ratio is not None:
        if not 0 <= e.trade_to_quote_ratio <= 1:
            flags.add("invalid_trade_to_quote_ratio")
        elif e.trade_to_quote_ratio <= MIN_TRADE_TO_QUOTE_RATIO:
            flags.add("quote_activity_without_matching_trades")

    return flags, quorum


def evaluate_adversarial_defense(
    *,
    market: MarketEvidence,
    external_content: Sequence[ExternalContent] = (),
) -> DefenseDecision:
    text_flags, trusted_text_sources = external_content_flags(external_content)
    mkt_flags, price_quorum = market_flags(market)
    flags = set(text_flags) | set(mkt_flags)

    # Prompt/tool injection is an immediate block: untrusted content can inform analysis,
    # but it can never alter policy, call tools, or create trading authority.
    hard_block = {
        "prompt_injection_like_content",
        "hidden_unicode_control",
        "invalid_local_market_data",
        "crossed_local_market",
        "global_price_quorum_insufficient",
        "global_price_dispersion_high",
        "local_global_divergence",
        "abnormal_spread",
        "single_step_price_shock",
        "extreme_order_cancellation",
        "rapid_depth_flip",
        "quote_activity_without_matching_trades",
        "invalid_cancel_ratio",
        "invalid_depth_flip_ratio",
        "invalid_trade_to_quote_ratio",
        "invalid_previous_market_data",
    }

    weighted = 0.0
    for flag in flags:
        if flag in {"prompt_injection_like_content", "hidden_unicode_control"}:
            weighted += 1.0
        elif flag in {"local_global_divergence", "single_step_price_shock", "global_price_dispersion_high"}:
            weighted += 0.8
        elif flag in {"extreme_order_cancellation", "rapid_depth_flip", "quote_activity_without_matching_trades"}:
            weighted += 0.7
        else:
            weighted += 0.5
    risk_score = min(1.0, weighted)

    blockers = sorted(flags & hard_block)
    if blockers:
        return DefenseDecision(
            allowed=False,
            reason=blockers[0],
            risk_score=risk_score,
            flags=tuple(sorted(flags)),
            trusted_text_sources=trusted_text_sources,
            price_quorum=price_quorum,
        )

    return DefenseDecision(
        allowed=True,
        reason="adversarial_checks_clear",
        risk_score=risk_score,
        flags=tuple(sorted(flags)),
        trusted_text_sources=trusted_text_sources,
        price_quorum=price_quorum,
    )

from __future__ import annotations

import re
from typing import Any

_ALLOWED_SOURCES = {"okx", "kucoin"}
_SAFE_SYMBOL = re.compile(r"^[A-Z0-9-]{1,24}$")
_MAX_SYMBOLS = 6


def sanitize_source_health(value: Any) -> dict[str, Any]:
    """Return only the approved public source-resolution telemetry fields."""
    if not isinstance(value, dict):
        return {}

    out: dict[str, Any] = {}
    for key in ("breadth_source", "funding_source", "open_interest_source"):
        source = value.get(key)
        if isinstance(source, str) and source in _ALLOWED_SOURCES:
            out[key] = source

    symbols = value.get("resolved_breadth_symbols")
    if isinstance(symbols, list):
        clean = [item for item in symbols[:_MAX_SYMBOLS] if isinstance(item, str) and _SAFE_SYMBOL.fullmatch(item)]
        if len(clean) == len(symbols[:_MAX_SYMBOLS]):
            out["resolved_breadth_symbols"] = clean

    samples = value.get("resolved_funding_samples")
    if isinstance(samples, int) and not isinstance(samples, bool) and samples >= 0:
        out["resolved_funding_samples"] = samples

    oi_available = value.get("oi_change_available")
    if isinstance(oi_available, bool):
        out["oi_change_available"] = oi_available

    return out

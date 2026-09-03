from __future__ import annotations

import math
import statistics
from typing import Any, Callable

import httpx


SOURCE_SPECS = (
    ("okx", "https://www.okx.com/api/v5/market/ticker", {"instId": "BTC-USDT"}),
    ("kucoin", "https://api.kucoin.com/api/v1/market/stats", {"symbol": "BTC-USDT"}),
    ("coinbase", "https://api.exchange.coinbase.com/products/BTC-USDT/ticker", None),
    ("kraken", "https://api.kraken.com/0/public/Ticker", {"pair": "XBTUSDT"}),
)


def parse_source(name: str, data: Any) -> tuple[float, float | None]:
    if not isinstance(data, dict):
        raise ValueError("malformed global ticker")
    if name == "okx":
        if str(data.get("code")) != "0":
            raise ValueError("OKX ticker rejected")
        rows = data.get("data") or []
        row = rows[0]
        px = float(row["last"])
        opened = float(row["open24h"])
        change = (px - opened) / opened if opened > 0 else None
    elif name == "kucoin":
        if str(data.get("code")) != "200000":
            raise ValueError("KuCoin ticker rejected")
        row = data.get("data") or {}
        px = float(row["last"])
        change = float(row["changeRate"]) if row.get("changeRate") not in (None, "") else None
    elif name == "coinbase":
        px = float(data["price"])
        change = None
    elif name == "kraken":
        if data.get("error"):
            raise ValueError("Kraken ticker rejected")
        row = next(iter((data.get("result") or {}).values()))
        px = float(row["c"][0])
        opened = float(row["o"])
        change = (px - opened) / opened if opened > 0 else None
    else:
        raise ValueError("unknown global source")
    if px <= 0 or not math.isfinite(px):
        raise ValueError("invalid global price")
    if change is not None and not math.isfinite(change):
        raise ValueError("invalid global change")
    return px, change


def fetch_global_snapshot(
    audit: Any | None = None,
    client: httpx.Client | None = None,
) -> tuple[float | None, float | None, list[str], float | None]:
    owned = client is None
    http = client or httpx.Client(timeout=4.0, headers={"User-Agent": "ChobYar-Trader/4-paper"})
    prices: list[float] = []
    changes: list[float] = []
    sources: list[str] = []
    try:
        for name, url, params in SOURCE_SPECS:
            try:
                response = http.get(url, params=params)
                response.raise_for_status()
                px, change = parse_source(name, response.json())
                prices.append(px)
                sources.append(name)
                if change is not None:
                    changes.append(change)
            except Exception as exc:
                if audit is not None:
                    try:
                        audit.write("market_source_error", source=name, error=type(exc).__name__)
                    except Exception:
                        pass
            # Two agreeing prices plus one 24h-change source are enough; avoid
            # needless latency against blocked providers on every 30s cycle.
            if len(prices) >= 2 and changes:
                break
    finally:
        if owned:
            http.close()
    if not prices:
        return None, None, [], None
    mean = statistics.fmean(prices)
    dispersion = (max(prices) - min(prices)) / mean if len(prices) > 1 else 0.0
    return mean, statistics.fmean(changes) if changes else None, sources, dispersion

from __future__ import annotations

import math
import statistics
from typing import Any


MIN_PRICE_SOURCES = 2
SOURCE_SPECS = (
    ("kucoin", "https://api.kucoin.com/api/v1/market/stats", {"symbol": "BTC-USDT"}),
    ("gateio", "https://api.gateio.ws/api/v4/spot/tickers", {"currency_pair": "BTC_USDT"}),
    ("mexc", "https://api.mexc.com/api/v3/ticker/24hr", {"symbol": "BTCUSDT"}),
    ("bitget", "https://api.bitget.com/api/v2/spot/market/tickers", {"symbol": "BTCUSDT"}),
    (
        "bybit",
        "https://api.bybit.com/v5/market/tickers",
        {"category": "spot", "symbol": "BTCUSDT"},
    ),
    ("okx", "https://www.okx.com/api/v5/market/ticker", {"instId": "BTC-USDT"}),
    ("kraken", "https://api.kraken.com/0/public/Ticker", {"pair": "XBTUSDT"}),
    ("coinbase", "https://api.exchange.coinbase.com/products/BTC-USDT/ticker", None),
)

if len({name for name, _url, _params in SOURCE_SPECS}) != len(SOURCE_SPECS):
    raise RuntimeError("duplicate global source identifier")


def _mapping(data: Any) -> dict[str, Any]:
    if not isinstance(data, dict):
        raise ValueError("malformed global ticker")
    return data


def parse_source(name: str, data: Any) -> tuple[float, float | None]:
    if name == "gateio":
        if not isinstance(data, list) or not data or not isinstance(data[0], dict):
            raise ValueError("Gate.io ticker rejected")
        row = data[0]
        if row.get("currency_pair") != "BTC_USDT":
            raise ValueError("Gate.io symbol mismatch")
        px = float(row["last"])
        change = float(row["change_percentage"]) / 100.0
    else:
        payload = _mapping(data)
        if name == "kucoin":
            if str(payload.get("code")) != "200000":
                raise ValueError("KuCoin ticker rejected")
            row = payload.get("data") or {}
            px = float(row["last"])
            change = (
                float(row["changeRate"])
                if row.get("changeRate") not in (None, "")
                else None
            )
        elif name == "mexc":
            if payload.get("symbol") != "BTCUSDT":
                raise ValueError("MEXC symbol mismatch")
            px = float(payload["lastPrice"])
            change = float(payload["priceChangePercent"]) / 100.0
        elif name == "bitget":
            if str(payload.get("code")) != "00000":
                raise ValueError("Bitget ticker rejected")
            rows = payload.get("data") or []
            row = rows[0]
            if row.get("symbol") != "BTCUSDT":
                raise ValueError("Bitget symbol mismatch")
            px = float(row["lastPr"])
            change = float(row["change24h"])
        elif name == "bybit":
            if int(payload.get("retCode", -1)) != 0:
                raise ValueError("Bybit ticker rejected")
            rows = (payload.get("result") or {}).get("list") or []
            row = rows[0]
            if row.get("symbol") != "BTCUSDT":
                raise ValueError("Bybit symbol mismatch")
            px = float(row["lastPrice"])
            change = float(row["price24hPcnt"])
        elif name == "okx":
            if str(payload.get("code")) != "0":
                raise ValueError("OKX ticker rejected")
            rows = payload.get("data") or []
            row = rows[0]
            px = float(row["last"])
            opened = float(row["open24h"])
            change = (px - opened) / opened if opened > 0 else None
        elif name == "kraken":
            if payload.get("error"):
                raise ValueError("Kraken ticker rejected")
            row = next(iter((payload.get("result") or {}).values()))
            px = float(row["c"][0])
            opened = float(row["o"])
            change = (px - opened) / opened if opened > 0 else None
        elif name == "coinbase":
            px = float(payload["price"])
            change = None
        else:
            raise ValueError("unknown global source")

    if px <= 0 or not math.isfinite(px):
        raise ValueError("invalid global price")
    if change is not None and not math.isfinite(change):
        raise ValueError("invalid global change")
    return px, change


def fetch_global_snapshot(
    audit: Any | None = None,
    client: Any | None = None,
) -> tuple[float | None, float | None, list[str], float | None]:
    owned = client is None
    if owned:
        import httpx

        http = httpx.Client(
            timeout=4.0,
            headers={"User-Agent": "ChobYar-Trader/4-paper"},
        )
    else:
        http = client

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
                        audit.write(
                            "market_source_error",
                            source=name,
                            error=type(exc).__name__,
                        )
                    except Exception:
                        pass
            # Two independently parsed prices plus a 24h-change value are
            # sufficient. Later providers remain available as fallbacks.
            if len(prices) >= MIN_PRICE_SOURCES and changes:
                break
    finally:
        if owned:
            http.close()

    if not prices:
        return None, None, [], None
    mean = statistics.fmean(prices)
    dispersion = (
        (max(prices) - min(prices)) / mean
        if len(prices) > 1
        else 0.0
    )
    return (
        mean,
        statistics.fmean(changes) if changes else None,
        sources,
        dispersion,
    )

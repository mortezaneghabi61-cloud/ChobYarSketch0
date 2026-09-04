from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path

import public_source_fallbacks as sources


class FakeResponse:
    def __init__(self, payload):
        self.payload = payload

    def raise_for_status(self):
        return None

    def json(self):
        return self.payload


class FakeClient:
    def __init__(self, payloads):
        self.payloads = payloads

    def get(self, url, params=None):
        key = (url, tuple(sorted((params or {}).items())))
        if key not in self.payloads:
            raise RuntimeError(f"unexpected request {key}")
        payload = self.payloads[key]
        if isinstance(payload, BaseException):
            raise payload
        return FakeResponse(payload)


class PublicSourceFallbackTest(unittest.TestCase):
    def test_bybit_breadth_requires_all_three_symbols(self):
        params = {"category": "spot"}
        payload = {
            "retCode": 0,
            "result": {
                "list": [
                    {"symbol": "BTCUSDT", "price24hPcnt": "0.010"},
                    {"symbol": "ETHUSDT", "price24hPcnt": "-0.020"},
                    {"symbol": "SOLUSDT", "price24hPcnt": "0.030"},
                ]
            },
        }
        client = FakeClient({(sources.BYBIT_BASE + "/v5/market/tickers", tuple(sorted(params.items()))): payload})
        result = sources.fetch_bybit_breadth(client)
        self.assertEqual(set(result), {"BTC-USDT", "ETH-USDT", "SOL-USDT"})
        self.assertAlmostEqual(result["BTC-USDT"], 0.01)
        self.assertAlmostEqual(result["ETH-USDT"], -0.02)

    def test_bybit_funding_uses_newest_timestamp_and_zscore(self):
        params = {"category": "linear", "symbol": "BTCUSDT", "limit": "30"}
        rows = [
            {"fundingRate": "0.0001", "fundingRateTimestamp": "1000"},
            {"fundingRate": "0.0002", "fundingRateTimestamp": "5000"},
            {"fundingRate": "0.0001", "fundingRateTimestamp": "2000"},
            {"fundingRate": "0.0001", "fundingRateTimestamp": "3000"},
            {"fundingRate": "0.0001", "fundingRateTimestamp": "4000"},
        ]
        payload = {"retCode": 0, "result": {"list": rows}}
        client = FakeClient({(sources.BYBIT_BASE + "/v5/market/funding/history", tuple(sorted(params.items()))): payload})
        current, z, samples = sources.fetch_bybit_funding(client)
        self.assertEqual(samples, 5)
        self.assertAlmostEqual(current, 0.0002)
        self.assertIsNotNone(z)
        self.assertGreater(z, 0.0)

    def test_source_aware_oi_never_compares_across_venues(self):
        with tempfile.TemporaryDirectory() as tmp:
            state = Path(tmp) / "oi.json"
            state.write_text(
                json.dumps({"oi_value": 100.0, "oi_timestamp": 1000.0, "oi_source": "okx"}),
                encoding="utf-8",
            )
            change, next_state = sources.source_aware_previous_oi_change(
                state, 200.0, 1300.0, "bybit", lambda: "now"
            )
            self.assertIsNone(change)
            self.assertEqual(next_state["oi_source"], "bybit")
            state.write_text(json.dumps(next_state), encoding="utf-8")
            change, _ = sources.source_aware_previous_oi_change(
                state, 202.0, 1600.0, "bybit", lambda: "later"
            )
            self.assertAlmostEqual(change, 0.01)

    def test_unavailable_current_oi_preserves_previous_state(self):
        with tempfile.TemporaryDirectory() as tmp:
            state = Path(tmp) / "oi.json"
            previous = {"oi_value": 123.0, "oi_timestamp": 1000.0, "oi_source": "okx"}
            state.write_text(json.dumps(previous), encoding="utf-8")
            change, next_state = sources.source_aware_previous_oi_change(
                state, None, 1300.0, "none", lambda: "now"
            )
            self.assertIsNone(change)
            self.assertEqual(next_state, previous)

    def test_bybit_open_interest_selects_latest_sample(self):
        params = {
            "category": "linear",
            "symbol": "BTCUSDT",
            "intervalTime": "5min",
            "limit": "5",
        }
        payload = {
            "retCode": 0,
            "result": {
                "list": [
                    {"openInterest": "100", "timestamp": "1000"},
                    {"openInterest": "102", "timestamp": "3000"},
                    {"openInterest": "101", "timestamp": "2000"},
                ]
            },
        }
        client = FakeClient({(sources.BYBIT_BASE + "/v5/market/open-interest", tuple(sorted(params.items()))): payload})
        self.assertEqual(sources.fetch_bybit_open_interest(client), 102.0)


if __name__ == "__main__":
    unittest.main()

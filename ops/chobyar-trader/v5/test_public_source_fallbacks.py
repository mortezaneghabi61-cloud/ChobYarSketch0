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
    def test_kucoin_breadth_requires_all_three_symbols(self):
        payload = {
            "code": "200000",
            "data": {
                "ticker": [
                    {"symbol": "BTC-USDT", "changeRate": "0.010"},
                    {"symbol": "ETH-USDT", "changeRate": "-0.020"},
                    {"symbol": "SOL-USDT", "changeRate": "0.030"},
                ]
            },
        }
        key = (sources.KUCOIN_SPOT_BASE + "/api/v1/market/allTickers", tuple())
        result = sources.fetch_kucoin_breadth(FakeClient({key: payload}))
        self.assertEqual(set(result), set(sources.REQUIRED_BREADTH))
        self.assertAlmostEqual(result["BTC-USDT"], 0.01)
        self.assertAlmostEqual(result["ETH-USDT"], -0.02)

    def test_kucoin_funding_uses_newest_timestamp_and_zscore(self):
        now_ms = 2_000_000_000_000
        start_ms = now_ms - 14 * 24 * 3600 * 1000
        params = {
            "symbol": sources.KUCOIN_LINEAR_SYMBOL,
            "from": str(start_ms),
            "to": str(now_ms),
        }
        rows = [
            {"fundingRate": "0.0001", "timepoint": 1000},
            {"fundingRate": "0.0002", "timepoint": 5000},
            {"fundingRate": "0.0001", "timepoint": 2000},
            {"fundingRate": "0.0001", "timepoint": 3000},
            {"fundingRate": "0.0001", "timepoint": 4000},
        ]
        payload = {"code": "200000", "data": rows}
        key = (
            sources.KUCOIN_FUTURES_BASE + "/api/v1/contract/funding-rates",
            tuple(sorted(params.items())),
        )
        current, z, samples = sources.fetch_kucoin_funding(FakeClient({key: payload}), now_ms=now_ms)
        self.assertEqual(samples, 5)
        self.assertAlmostEqual(current, 0.0002)
        self.assertIsNotNone(z)
        self.assertGreater(z, 0.0)

    def test_kucoin_open_interest_uses_same_source_history_for_change(self):
        now_ms = 2_000_000_000_000
        start_ms = now_ms - 30 * 60 * 1000
        params = {
            "symbol": sources.KUCOIN_LINEAR_SYMBOL,
            "interval": "5min",
            "startAt": str(start_ms),
            "endAt": str(now_ms),
            "pageSize": "10",
        }
        payload = {
            "code": "200000",
            "data": [
                {"openInterest": "100", "ts": 1000},
                {"openInterest": "102", "ts": 3000},
                {"openInterest": "101", "ts": 2000},
            ],
        }
        key = (
            sources.KUCOIN_SPOT_BASE + "/api/ua/v1/market/open-interest",
            tuple(sorted(params.items())),
        )
        current, change = sources.fetch_kucoin_open_interest(FakeClient({key: payload}), now_ms=now_ms)
        self.assertEqual(current, 102.0)
        self.assertAlmostEqual(change, 102.0 / 101.0 - 1.0)

    def test_source_aware_oi_never_compares_across_venues(self):
        with tempfile.TemporaryDirectory() as tmp:
            state = Path(tmp) / "oi.json"
            state.write_text(
                json.dumps({"oi_value": 100.0, "oi_timestamp": 1000.0, "oi_source": "okx"}),
                encoding="utf-8",
            )
            change, next_state = sources.source_aware_previous_oi_change(
                state, 200.0, 1300.0, "kucoin", lambda: "now"
            )
            self.assertIsNone(change)
            self.assertEqual(next_state["oi_source"], "kucoin")

    def test_direct_kucoin_oi_change_is_allowed_without_cross_venue_state(self):
        with tempfile.TemporaryDirectory() as tmp:
            state = Path(tmp) / "oi.json"
            state.write_text(
                json.dumps({"oi_value": 100.0, "oi_timestamp": 1000.0, "oi_source": "okx"}),
                encoding="utf-8",
            )
            change, next_state = sources.source_aware_previous_oi_change(
                state,
                202.0,
                1300.0,
                "kucoin",
                lambda: "now",
                direct_same_source_change=0.01,
            )
            self.assertAlmostEqual(change, 0.01)
            self.assertEqual(next_state["oi_source"], "kucoin")

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


if __name__ == "__main__":
    unittest.main()

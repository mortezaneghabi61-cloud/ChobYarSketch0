from __future__ import annotations

import math
import unittest

import global_sources as subject


PAYLOADS = {
    "kucoin": {
        "code": "200000",
        "data": {"last": "100", "changeRate": "0.01"},
    },
    "gateio": [
        {
            "currency_pair": "BTC_USDT",
            "last": "101",
            "change_percentage": "2",
        }
    ],
    "mexc": {
        "symbol": "BTCUSDT",
        "lastPrice": "102",
        "priceChangePercent": "3",
    },
    "bitget": {
        "code": "00000",
        "data": [
            {
                "symbol": "BTCUSDT",
                "lastPr": "103",
                "change24h": "0.04",
            }
        ],
    },
    "bybit": {
        "retCode": 0,
        "result": {
            "list": [
                {
                    "symbol": "BTCUSDT",
                    "lastPrice": "104",
                    "price24hPcnt": "0.05",
                }
            ]
        },
    },
    "okx": {
        "code": "0",
        "data": [{"last": "105", "open24h": "100"}],
    },
    "kraken": {
        "error": [],
        "result": {"XXBTZUSD": {"c": ["106"], "o": "100"}},
    },
    "coinbase": {"price": "107"},
}


class FakeResponse:
    def __init__(self, payload=None, error=None):
        self.payload = payload
        self.error = error

    def raise_for_status(self):
        if self.error is not None:
            raise self.error

    def json(self):
        return self.payload


class FakeClient:
    def __init__(self, outcomes):
        self.outcomes = list(outcomes)
        self.calls = []
        self.closed = False

    def get(self, url, params=None):
        self.calls.append((url, params))
        outcome = self.outcomes.pop(0)
        if isinstance(outcome, BaseException):
            raise outcome
        return FakeResponse(outcome)

    def close(self):
        self.closed = True


class FakeAudit:
    def __init__(self):
        self.rows = []

    def write(self, event, **fields):
        self.rows.append((event, fields))


class GlobalSourcesTest(unittest.TestCase):
    def test_preferred_sources_are_proven_vps_candidates(self):
        names = [name for name, _url, _params in subject.SOURCE_SPECS]
        self.assertEqual(
            names[:5],
            ["kucoin", "gateio", "mexc", "bitget", "bybit"],
        )
        self.assertEqual(len(names), len(set(names)))

    def test_every_supported_parser_returns_finite_positive_data(self):
        for name, payload in PAYLOADS.items():
            with self.subTest(source=name):
                price, change = subject.parse_source(name, payload)
                self.assertGreater(price, 0)
                self.assertTrue(math.isfinite(price))
                if change is not None:
                    self.assertTrue(math.isfinite(change))

    def test_malformed_and_symbol_mismatch_fail_closed(self):
        bad = (
            ("gateio", {}),
            ("gateio", [{"last": "1", "change_percentage": "1"}]),
            ("gateio", [{"currency_pair": "ETH_USDT", "last": "1",
                         "change_percentage": "1"}]),
            ("mexc", {"lastPrice": "1", "priceChangePercent": "1"}),
            ("mexc", {"symbol": "ETHUSDT", "lastPrice": "1",
                      "priceChangePercent": "1"}),
            ("bitget", {"code": "99999", "data": []}),
            ("bitget", {"code": "00000", "data": [{"lastPr": "1",
                                                       "change24h": "0"}]}),
            ("bybit", {"retCode": 1, "result": {"list": []}}),
            ("bybit", {"retCode": 0, "result": {"list": [{
                "lastPrice": "1", "price24hPcnt": "0"
            }]}}),
            ("kucoin", {"code": "bad", "data": {}}),
            ("coinbase", {"price": "nan"}),
            ("unknown", {}),
        )
        for name, payload in bad:
            with self.subTest(source=name):
                with self.assertRaises((ValueError, KeyError, IndexError)):
                    subject.parse_source(name, payload)

    def test_two_healthy_sources_stop_before_later_providers(self):
        client = FakeClient([PAYLOADS["kucoin"], PAYLOADS["gateio"]])
        price, change, sources, dispersion = subject.fetch_global_snapshot(
            client=client
        )
        self.assertEqual(sources, ["kucoin", "gateio"])
        self.assertEqual(len(client.calls), 2)
        self.assertAlmostEqual(price, 100.5)
        self.assertAlmostEqual(change, 0.015)
        self.assertAlmostEqual(dispersion, 1.0 / 100.5)
        self.assertFalse(client.closed)

    def test_failure_falls_through_and_audits_only_class(self):
        client = FakeClient(
            [
                TimeoutError("private diagnostic must not be recorded"),
                PAYLOADS["gateio"],
                PAYLOADS["mexc"],
            ]
        )
        audit = FakeAudit()
        _price, _change, sources, _dispersion = (
            subject.fetch_global_snapshot(audit=audit, client=client)
        )
        self.assertEqual(sources, ["gateio", "mexc"])
        self.assertEqual(len(client.calls), 3)
        self.assertEqual(
            audit.rows,
            [
                (
                    "market_source_error",
                    {"source": "kucoin", "error": "TimeoutError"},
                )
            ],
        )

    def test_all_failures_return_no_fabricated_market_data(self):
        client = FakeClient(
            [RuntimeError("unavailable") for _ in subject.SOURCE_SPECS]
        )
        result = subject.fetch_global_snapshot(client=client)
        self.assertEqual(result, (None, None, [], None))
        self.assertEqual(len(client.calls), len(subject.SOURCE_SPECS))

    def test_injected_client_is_never_closed(self):
        client = FakeClient([PAYLOADS["kucoin"], PAYLOADS["gateio"]])
        subject.fetch_global_snapshot(client=client)
        self.assertFalse(client.closed)


if __name__ == "__main__":
    unittest.main()

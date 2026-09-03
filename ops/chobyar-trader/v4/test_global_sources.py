from __future__ import annotations

import unittest

from global_sources import parse_source


class GlobalSourceParserTests(unittest.TestCase):
    def test_okx_parser(self) -> None:
        px, change = parse_source("okx", {
            "code": "0",
            "data": [{"last": "102", "open24h": "100"}],
        })
        self.assertEqual(px, 102.0)
        self.assertAlmostEqual(change or 0.0, 0.02)

    def test_kucoin_parser(self) -> None:
        px, change = parse_source("kucoin", {
            "code": "200000",
            "data": {"last": "99", "changeRate": "-0.01"},
        })
        self.assertEqual(px, 99.0)
        self.assertAlmostEqual(change or 0.0, -0.01)

    def test_coinbase_parser_price_only(self) -> None:
        px, change = parse_source("coinbase", {"price": "101.5"})
        self.assertEqual(px, 101.5)
        self.assertIsNone(change)

    def test_kraken_parser(self) -> None:
        px, change = parse_source("kraken", {
            "error": [],
            "result": {"XBTUSDT": {"c": ["103", "1"], "o": "100"}},
        })
        self.assertEqual(px, 103.0)
        self.assertAlmostEqual(change or 0.0, 0.03)

    def test_rejects_malformed_or_rejected_sources(self) -> None:
        with self.assertRaises(ValueError):
            parse_source("okx", {"code": "1", "data": []})
        with self.assertRaises(ValueError):
            parse_source("kucoin", {"code": "400000", "data": {}})
        with self.assertRaises(ValueError):
            parse_source("kraken", {"error": ["blocked"], "result": {}})
        with self.assertRaises(ValueError):
            parse_source("unknown", {})


if __name__ == "__main__":
    unittest.main()

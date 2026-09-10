from __future__ import annotations

import json
import unittest

from public_source_health import sanitize_source_health


class PublicSourceHealthTest(unittest.TestCase):
    def test_kucoin_fields_are_explicitly_projected(self):
        raw = {
            "breadth_source": "kucoin",
            "funding_source": "kucoin",
            "open_interest_source": "kucoin",
            "resolved_breadth_symbols": ["BTC-USDT", "ETH-USDT", "SOL-USDT"],
            "resolved_funding_samples": 42,
            "oi_change_available": True,
        }
        self.assertEqual(sanitize_source_health(raw), raw)

    def test_okx_fields_are_explicitly_projected(self):
        raw = {
            "breadth_source": "okx",
            "funding_source": "okx",
            "open_interest_source": "okx",
            "resolved_breadth_symbols": ["BTC-USDT", "ETH-USDT", "SOL-USDT"],
            "resolved_funding_samples": 5,
            "oi_change_available": False,
        }
        self.assertEqual(sanitize_source_health(raw), raw)

    def test_missing_health_does_not_fabricate_values(self):
        self.assertEqual(sanitize_source_health(None), {})
        self.assertEqual(sanitize_source_health({}), {})

    def test_malformed_values_fail_closed(self):
        out = sanitize_source_health({
            "breadth_source": "unknown",
            "funding_source": 3,
            "open_interest_source": "OKX",
            "resolved_breadth_symbols": ["BTC-USDT", "../secret"],
            "resolved_funding_samples": -1,
            "oi_change_available": "true",
        })
        self.assertEqual(out, {})

    def test_unknown_and_secret_like_fields_are_excluded(self):
        out = sanitize_source_health({
            "breadth_source": "kucoin",
            "api_key": "DO_NOT_LEAK",
            "secret": "DO_NOT_LEAK",
            "token": "DO_NOT_LEAK",
            "unknown_nested": {"authorization": "DO_NOT_LEAK"},
        })
        encoded = json.dumps(out).lower()
        self.assertEqual(out, {"breadth_source": "kucoin"})
        for marker in ("api_key", "secret", "token", "authorization", "do_not_leak"):
            self.assertNotIn(marker, encoded)

    def test_projection_is_bounded_and_does_not_grant_authority(self):
        out = sanitize_source_health({
            "resolved_breadth_symbols": ["BTC-USDT"] * 20,
            "execution_authority": True,
            "automatic_promotion_enabled": True,
            "automatic_reweighting_enabled": True,
            "geo_bypass_supported": True,
        })
        self.assertEqual(len(out["resolved_breadth_symbols"]), 6)
        for forbidden in ("execution_authority", "automatic_promotion_enabled", "automatic_reweighting_enabled", "geo_bypass_supported"):
            self.assertNotIn(forbidden, out)


if __name__ == "__main__":
    unittest.main()

from __future__ import annotations

import unittest
from decimal import Decimal

from market_metadata import MARKETS_PATH, fetch_market_metadata, parse_market_metadata, validate_with_live_metadata
from order_dryrun import OrderIntent


BASE = {
    "TRADING_MODE": "paper",
    "LIVE_TRADING_ENABLED": "false",
    "LIVE_DRY_RUN": "true",
    "SPOT_ONLY": "true",
    "WITHDRAWALS_ENABLED": "false",
    "LEVERAGE_ENABLED": "false",
    "LIVE_MAX_ORDER_TMN": "100000",
    "MAX_POSITION_PCT": "0.25",
    "STOP_LOSS_PCT": "0.015",
    "TAKE_PROFIT_PCT": "0.03",
    "MAX_DAILY_LOSS_PCT": "0.03",
}

PAYLOAD = {
    "success": True,
    "result": {
        "symbols": {
            "BTCUSDT": {
                "symbol": "BTCUSDT",
                "stepSize": 6,
                "tickSize": 2,
                "minNotional": "10",
            }
        }
    },
}


class Response:
    def __init__(self, payload=PAYLOAD, status_code=200):
        self._payload = payload
        self.status_code = status_code

    def json(self):
        return self._payload


class Client:
    def __init__(self, response=None):
        self.response = response or Response()
        self.calls = []

    def get(self, path):
        self.calls.append(path)
        return self.response


class MarketMetadataTests(unittest.TestCase):
    def test_parse_precision_fields_to_steps(self):
        meta = parse_market_metadata(PAYLOAD, "BTCUSDT")
        self.assertEqual(meta.quantity_step, Decimal("0.000001"))
        self.assertEqual(meta.price_tick, Decimal("0.01"))
        self.assertEqual(meta.min_notional_tmn, Decimal("10"))
        self.assertIsNone(meta.market_max_notional_tmn)

    def test_read_only_get_exact_path(self):
        client = Client()
        meta = fetch_market_metadata(env=BASE, client=client, symbol="BTCUSDT")
        self.assertEqual(meta.symbol, "BTCUSDT")
        self.assertEqual(client.calls, [MARKETS_PATH])

    def test_stage1_block_prevents_network(self):
        client = Client()
        with self.assertRaisesRegex(RuntimeError, "stage1_blocked"):
            fetch_market_metadata(
                env={**BASE, "LIVE_TRADING_ENABLED": "true"},
                client=client,
                symbol="BTCUSDT",
            )
        self.assertEqual(client.calls, [])

    def test_missing_symbol_fails_closed(self):
        with self.assertRaisesRegex(RuntimeError, "symbol_missing"):
            parse_market_metadata(PAYLOAD, "ETHUSDT")

    def test_bad_precision_fails_closed(self):
        payload = {
            "success": True,
            "result": {"symbols": {"BTCUSDT": {"symbol": "BTCUSDT", "stepSize": -1, "tickSize": 2, "minNotional": 10}}},
        }
        with self.assertRaisesRegex(RuntimeError, "invalid_step"):
            parse_market_metadata(payload, "BTCUSDT")

    def test_http_failure_fails_closed(self):
        client = Client(Response(status_code=503))
        with self.assertRaisesRegex(RuntimeError, "http_failed"):
            fetch_market_metadata(env=BASE, client=client, symbol="BTCUSDT")

    def test_live_metadata_drives_local_dry_run_only(self):
        client = Client()
        intent = OrderIntent(
            symbol="BTCUSDT",
            side="BUY",
            quantity=Decimal("0.001000"),
            limit_price_tmn=Decimal("50000.00"),
        )
        result = validate_with_live_metadata(env=BASE, client=client, intent=intent)
        self.assertTrue(result.dry_run.allowed)
        self.assertEqual(result.dry_run.reason, "stage3_dry_run_validated_no_submission")
        self.assertEqual(client.calls, [MARKETS_PATH])

    def test_market_max_tighter_than_local_cap_is_enforced(self):
        payload = {
            "success": True,
            "result": {
                "symbols": {
                    "BTCUSDT": {
                        "symbol": "BTCUSDT",
                        "stepSize": 6,
                        "tickSize": 2,
                        "minNotional": "10",
                        "maxNotional": "20",
                    }
                }
            },
        }
        client = Client(Response(payload=payload))
        intent = OrderIntent(
            symbol="BTCUSDT",
            side="BUY",
            quantity=Decimal("0.001000"),
            limit_price_tmn=Decimal("50000.00"),
        )
        result = validate_with_live_metadata(env=BASE, client=client, intent=intent)
        self.assertFalse(result.dry_run.allowed)
        self.assertEqual(result.dry_run.reason, "above_market_max_notional")


if __name__ == "__main__":
    unittest.main()

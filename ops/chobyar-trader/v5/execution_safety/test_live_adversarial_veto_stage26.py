from __future__ import annotations

import json
import tempfile
import unittest
from datetime import datetime, timedelta, timezone
from pathlib import Path

from live_adversarial_veto_stage26 import Stage26Error, collect_once, enforce_live_buy_veto

NOW = datetime(2026, 9, 6, 12, 0, tzinfo=timezone.utc)
TEST_KEY = "test-only-key"


class Resp:
    def __init__(self, status, payload):
        self.status_code = status
        self._payload = payload

    def json(self):
        return self._payload


class FakeHttp:
    def __init__(self, *, divergent=False, fail_sources=(), bybit_ok=False):
        self.divergent = divergent
        self.fail_sources = set(fail_sources)
        self.bybit_ok = bybit_ok
        self.wallex_headers = []

    def get(self, url, **kwargs):
        if "wallex.ir/v1/markets" in url:
            self.wallex_headers.append(kwargs.get("headers", {}))
            return Resp(200, {"success": True, "result": {"symbols": {"BTCUSDT": {"stats": {
                "bidPrice": "79900", "askPrice": "80000", "lastPrice": "79950"
            }}}}})
        if "wallex.ir/v1/depth" in url:
            self.wallex_headers.append(kwargs.get("headers", {}))
            return Resp(200, {"success": True, "result": {
                "bid": [{"price": "79900", "quantity": "0.1"}, {"price": "79890", "quantity": "0.1"}],
                "ask": [{"price": "80000", "quantity": "0.1"}, {"price": "80010", "quantity": "0.1"}],
            }})
        if "coinlore" in url:
            if "coinlore" in self.fail_sources:
                return Resp(503, {})
            return Resp(200, [{"symbol": "BTC", "price_usd": "79955.15"}])
        if "coinpaprika" in url:
            if "coinpaprika" in self.fail_sources:
                return Resp(503, {})
            price = 82000 if self.divergent else 79999.77
            return Resp(200, {"symbol": "BTC", "quotes": {"USD": {"price": price}}})
        if "coingecko" in url:
            if "coingecko" in self.fail_sources:
                return Resp(503, {})
            return Resp(200, {"bitcoin": {"usd": 79939}})
        if "bybit" in url:
            if self.bybit_ok and "bybit" not in self.fail_sources:
                return Resp(200, {"retCode": 0, "result": {"list": [{"lastPrice": "79952"}]}})
            return Resp(403, {})
        raise AssertionError(url)


def warm_three(client, state: Path, history: Path):
    first = collect_once(client, state_path=state, history_path=history, now=NOW, api_key=TEST_KEY)
    second = collect_once(client, state_path=state, history_path=history, now=NOW + timedelta(seconds=20), api_key=TEST_KEY)
    third = collect_once(client, state_path=state, history_path=history, now=NOW + timedelta(seconds=40), api_key=TEST_KEY)
    return first, second, third


class Stage27SourceResilienceTests(unittest.TestCase):
    def test_wallex_gets_are_authenticated_without_secret_in_state(self):
        with tempfile.TemporaryDirectory() as td:
            client = FakeHttp()
            state = Path(td) / "s.json"
            collect_once(client, state_path=state, history_path=Path(td) / "h.json", now=NOW, api_key=TEST_KEY)
            self.assertEqual(len(client.wallex_headers), 2)
            self.assertTrue(all(h.get("X-API-Key") == TEST_KEY for h in client.wallex_headers))
            self.assertNotIn(TEST_KEY, state.read_text())

    def test_history_warmup_blocks_first_two_samples(self):
        with tempfile.TemporaryDirectory() as td:
            state, history = Path(td) / "s.json", Path(td) / "h.json"
            first = collect_once(FakeHttp(), state_path=state, history_path=history, now=NOW, api_key=TEST_KEY)
            second = collect_once(FakeHttp(), state_path=state, history_path=history, now=NOW + timedelta(seconds=20), api_key=TEST_KEY)
            self.assertFalse(first["allowed"])
            self.assertFalse(second["allowed"])
            self.assertEqual(first["reason"], "history_warmup")
            self.assertEqual(second["reason"], "history_warmup")

    def test_three_verified_reference_aggregators_allow_after_warmup(self):
        with tempfile.TemporaryDirectory() as td:
            state, history = Path(td) / "s.json", Path(td) / "h.json"
            _, _, third = warm_three(FakeHttp(), state, history)
            self.assertTrue(third["allowed"])
            self.assertEqual(third["reference_quorum"], 3)
            self.assertEqual(third["reference_source_ids"], ["coingecko", "coinlore", "coinpaprika"])
            self.assertEqual(third["price_quorum"], 3)
            self.assertEqual(third["evidence_classes"], ["local_execution_venue", "reference_aggregator"])
            self.assertEqual(third["source_policy"], "STAGE27_REFERENCE_RESILIENCE")

    def test_optional_direct_exchange_is_labeled_not_required(self):
        with tempfile.TemporaryDirectory() as td:
            state, history = Path(td) / "s.json", Path(td) / "h.json"
            _, _, third = warm_three(FakeHttp(bybit_ok=True), state, history)
            self.assertTrue(third["allowed"])
            self.assertEqual(third["reference_quorum"], 3)
            self.assertEqual(third["price_quorum"], 4)
            self.assertEqual(third["direct_exchange_source_ids"], ["bybit"])
            self.assertIn("direct_exchange_optional", third["evidence_classes"])

    def test_one_reference_aggregator_failure_blocks_even_if_bybit_works(self):
        with tempfile.TemporaryDirectory() as td:
            state, history = Path(td) / "s.json", Path(td) / "h.json"
            _, _, third = warm_three(FakeHttp(fail_sources={"coinlore"}, bybit_ok=True), state, history)
            self.assertFalse(third["allowed"])
            self.assertEqual(third["reference_quorum"], 2)
            self.assertIn("reference_aggregator_quorum_insufficient", third["flags"])

    def test_divergent_reference_price_blocks(self):
        with tempfile.TemporaryDirectory() as td:
            state, history = Path(td) / "s.json", Path(td) / "h.json"
            _, _, third = warm_three(FakeHttp(divergent=True), state, history)
            self.assertFalse(third["allowed"])
            self.assertIn("global_price_dispersion_high", third["flags"])

    def test_fresh_warmed_allowed_snapshot_passes(self):
        with tempfile.TemporaryDirectory() as td:
            state, history = Path(td) / "s.json", Path(td) / "h.json"
            warm_three(FakeHttp(), state, history)
            out = enforce_live_buy_veto(state_path=state, now=NOW + timedelta(seconds=50))
            self.assertTrue(out["allowed"])
            self.assertEqual(out["reference_quorum"], 3)

    def test_stale_snapshot_blocks(self):
        with tempfile.TemporaryDirectory() as td:
            state, history = Path(td) / "s.json", Path(td) / "h.json"
            warm_three(FakeHttp(), state, history)
            with self.assertRaisesRegex(Stage26Error, "stage26_snapshot_stale"):
                enforce_live_buy_veto(state_path=state, now=NOW + timedelta(seconds=101))

    def test_old_stage26_state_version_cannot_pass_stage27(self):
        with tempfile.TemporaryDirectory() as td:
            state = Path(td) / "s.json"
            state.write_text(json.dumps({
                "version": 1,
                "scope": "BTCUSDT_STAGE26_ADVERSARIAL_VETO",
                "symbol": "BTCUSDT",
                "observed_at": NOW.isoformat(),
                "allowed": True,
                "price_quorum": 3,
                "source_ids": ["coinlore", "coinpaprika", "coingecko"],
                "history_samples": 3,
            }))
            with self.assertRaisesRegex(Stage26Error, "stage27_version_mismatch"):
                enforce_live_buy_veto(state_path=state, now=NOW)

    def test_unapproved_source_in_state_blocks(self):
        with tempfile.TemporaryDirectory() as td:
            state = Path(td) / "s.json"
            state.write_text(json.dumps({
                "version": 2,
                "source_policy": "STAGE27_REFERENCE_RESILIENCE",
                "scope": "BTCUSDT_STAGE26_ADVERSARIAL_VETO",
                "symbol": "BTCUSDT",
                "observed_at": NOW.isoformat(),
                "allowed": True,
                "price_quorum": 3,
                "source_ids": ["coinlore", "coinpaprika", "evil-source"],
                "reference_source_ids": ["coinlore", "coinpaprika", "evil-source"],
                "reference_quorum": 3,
                "evidence_classes": ["local_execution_venue", "reference_aggregator"],
                "history_samples": 3,
            }))
            with self.assertRaisesRegex(Stage26Error, "stage26_unapproved_source"):
                enforce_live_buy_veto(state_path=state, now=NOW)

    def test_reference_quorum_must_match_reference_ids(self):
        with tempfile.TemporaryDirectory() as td:
            state = Path(td) / "s.json"
            state.write_text(json.dumps({
                "version": 2,
                "source_policy": "STAGE27_REFERENCE_RESILIENCE",
                "scope": "BTCUSDT_STAGE26_ADVERSARIAL_VETO",
                "symbol": "BTCUSDT",
                "observed_at": NOW.isoformat(),
                "allowed": True,
                "price_quorum": 3,
                "source_ids": ["coinlore", "coinpaprika", "coingecko"],
                "reference_source_ids": ["coinlore", "coinpaprika", "coingecko"],
                "reference_quorum": 99,
                "evidence_classes": ["local_execution_venue", "reference_aggregator"],
                "history_samples": 3,
            }))
            with self.assertRaisesRegex(Stage26Error, "stage27_reference_quorum_mismatch"):
                enforce_live_buy_veto(state_path=state, now=NOW)

    def test_evidence_classes_are_required(self):
        with tempfile.TemporaryDirectory() as td:
            state = Path(td) / "s.json"
            state.write_text(json.dumps({
                "version": 2,
                "source_policy": "STAGE27_REFERENCE_RESILIENCE",
                "scope": "BTCUSDT_STAGE26_ADVERSARIAL_VETO",
                "symbol": "BTCUSDT",
                "observed_at": NOW.isoformat(),
                "allowed": True,
                "price_quorum": 3,
                "source_ids": ["coinlore", "coinpaprika", "coingecko"],
                "reference_source_ids": ["coinlore", "coinpaprika", "coingecko"],
                "reference_quorum": 3,
                "evidence_classes": ["reference_aggregator"],
                "history_samples": 3,
            }))
            with self.assertRaisesRegex(Stage26Error, "stage27_evidence_class_diversity_insufficient"):
                enforce_live_buy_veto(state_path=state, now=NOW)

    def test_history_gap_forces_rewarm(self):
        with tempfile.TemporaryDirectory() as td:
            state, history = Path(td) / "s.json", Path(td) / "h.json"
            collect_once(FakeHttp(), state_path=state, history_path=history, now=NOW, api_key=TEST_KEY)
            collect_once(FakeHttp(), state_path=state, history_path=history, now=NOW + timedelta(seconds=20), api_key=TEST_KEY)
            out = collect_once(FakeHttp(), state_path=state, history_path=history, now=NOW + timedelta(seconds=100), api_key=TEST_KEY)
            self.assertFalse(out["allowed"])
            self.assertEqual(out["reason"], "history_warmup")
            self.assertEqual(out["history_samples"], 1)

    def test_invalid_history_fails_closed(self):
        with tempfile.TemporaryDirectory() as td:
            state, history = Path(td) / "s.json", Path(td) / "h.json"
            history.write_text("not-json")
            out = collect_once(FakeHttp(), state_path=state, history_path=history, now=NOW, api_key=TEST_KEY)
            self.assertFalse(out["allowed"])
            self.assertEqual(out["reason"], "collector_error")


if __name__ == "__main__":
    unittest.main()

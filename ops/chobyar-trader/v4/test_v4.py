from __future__ import annotations

import hashlib
import hmac
import importlib
import importlib.util
import json
import os
import tempfile
import threading
import time
import unittest
import urllib.error
import urllib.request
from pathlib import Path

from backtest import merge_rows, parse_wallex, simulate
from common import SecretFreeAudit


def candles(count: int = 120, start_ts: int = 1_700_000_000) -> list[list[float]]:
    rows = []
    price = 100.0
    for i in range(count):
        opened = price
        close = opened * (1.002 if (i // 12) % 2 == 0 else .998)
        rows.append([
            float(start_ts + i * 3600),
            opened,
            max(opened, close) * 1.002,
            min(opened, close) * .998,
            close,
            10.0,
        ])
        price = close
    return rows


def load_status_module():
    status_path = Path(__file__).parents[1] / "v3" / "status_server.py"
    spec = importlib.util.spec_from_file_location("status_server_under_test", status_path)
    module = importlib.util.module_from_spec(spec)
    assert spec and spec.loader
    spec.loader.exec_module(module)
    return module


class BacktestTests(unittest.TestCase):
    def test_deterministic_and_complete(self) -> None:
        one, two = simulate(candles()), simulate(candles())
        self.assertEqual(one, two)
        for key in (
            "fees_paid", "closed_trades", "win_rate", "max_drawdown_pct", "pnl",
            "equity_curve", "benchmark_buy_hold_return_pct", "return_vs_buy_hold_pct",
            "history_days", "exposure_pct", "strategy_model", "full_fidelity_multiagent",
        ):
            self.assertIn(key, one)
        self.assertEqual(one["lookahead_policy"], "close[i-1] signal; open[i] execution")
        self.assertEqual(one["intrabar_policy"], "stop_first_if_stop_and_take_hit_same_candle")
        self.assertFalse(one["full_fidelity_multiagent"])

    def test_rejects_unsorted_candles(self) -> None:
        rows = candles()
        rows[2][0] = rows[1][0]
        with self.assertRaises(ValueError):
            simulate(rows)

    def test_wallex_udf_parser(self) -> None:
        rows = candles(100)
        payload = {
            "s": "ok",
            "t": [int(row[0]) for row in rows],
            "o": [str(row[1]) for row in rows],
            "h": [str(row[2]) for row in rows],
            "l": [str(row[3]) for row in rows],
            "c": [str(row[4]) for row in rows],
            "v": [str(row[5]) for row in rows],
        }
        parsed = parse_wallex(payload)
        self.assertEqual(len(parsed), 100)
        self.assertEqual(parsed[0][0], rows[0][0])
        self.assertEqual(parsed[-1][4], rows[-1][4])

    def test_chunk_merge_deduplicates_boundary_candles(self) -> None:
        rows = candles(200)
        merged = merge_rows([rows[:120], rows[100:]], 500)
        self.assertEqual(len(merged), 200)
        self.assertEqual(len({row[0] for row in merged}), 200)
        self.assertEqual(merged[0][0], rows[0][0])
        self.assertEqual(merged[-1][0], rows[-1][0])

    def test_90_day_sample_increases_observation_count_without_risk_change(self) -> None:
        result = simulate(candles(24 * 90))
        self.assertGreaterEqual(result["history_days"], 89.0)
        self.assertGreaterEqual(result["closed_trades"], 20)
        self.assertEqual(result["position_pct"], 0.25)
        self.assertEqual(result["stop_loss_pct"], 0.015)
        self.assertEqual(result["take_profit_pct"], 0.03)
        self.assertIsNotNone(result["closed_trades_per_30d"])


class AuditTests(unittest.TestCase):
    def test_redacts_secret_names_and_values(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            os.environ["WALLEX_API_KEY"] = "never-write-this-value"
            path = Path(directory) / "audit.jsonl"
            SecretFreeAudit(path).write("test", api_key="x", error="contains never-write-this-value")
            content = path.read_text(encoding="utf-8")
            self.assertNotIn("never-write-this-value", content)
            self.assertNotIn('"api_key":"x"', content)


class FailClosedImportTests(unittest.TestCase):
    def test_non_paper_import_exits(self) -> None:
        os.environ["CHOBYAR_APP_DIR"] = tempfile.mkdtemp()
        os.environ["TRADING_MODE"] = "live"
        os.environ["LIVE_TRADING_ENABLED"] = "false"
        with self.assertRaises(SystemExit):
            importlib.import_module("trader")


class StatusSecurityTests(unittest.TestCase):
    def test_hmac_replay_response_signature_and_minimal_health(self) -> None:
        module = load_status_module()
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            secret = "s" * 64
            module.ENV_FILE = root / ".env"
            module.ENV_FILE.write_text(
                f"STATUS_REQUIRE_AUTH=true\nSTATUS_HMAC_SECRET={secret}\nSTATUS_PORT=0\n",
                encoding="utf-8",
            )
            module.STATE_FILE = root / "state.json"
            module.BACKTEST_FILE = root / "backtest.json"
            module.FORWARD_FILE = root / "forward.json"
            module.AUDIT_FILE = root / "audit.jsonl"
            server = module.ThreadingHTTPServer(("127.0.0.1", 0), module.Handler)
            thread = threading.Thread(target=server.serve_forever, daemon=True)
            thread.start()
            base = f"http://127.0.0.1:{server.server_port}"
            try:
                with self.assertRaises(urllib.error.HTTPError) as denied:
                    urllib.request.urlopen(base + "/status")
                self.assertEqual(denied.exception.code, 401)
                health = json.loads(urllib.request.urlopen(base + "/health").read())
                self.assertEqual(health, {"ok": True, "service": "chobyar-status"})
                ts, nonce = str(int(time.time())), "n" * 32
                signature = hmac.new(
                    secret.encode(),
                    f"{ts}\n{nonce}\nGET\n/status".encode(),
                    hashlib.sha256,
                ).hexdigest()
                request = urllib.request.Request(
                    base + "/status",
                    headers={
                        "X-ChobYar-Timestamp": ts,
                        "X-ChobYar-Nonce": nonce,
                        "X-ChobYar-Signature": signature,
                    },
                )
                response = urllib.request.urlopen(request)
                body = response.read()
                expected = hmac.new(secret.encode(), body, hashlib.sha256).hexdigest()
                self.assertTrue(hmac.compare_digest(expected, response.headers["X-ChobYar-Response-Signature"]))
                with self.assertRaises(urllib.error.HTTPError) as replay:
                    urllib.request.urlopen(request)
                self.assertEqual(replay.exception.code, 401)
            finally:
                server.shutdown()
                server.server_close()
                thread.join(timeout=2)

    def test_live_gate_marks_price_only_backtest_as_not_full_fidelity(self) -> None:
        module = load_status_module()
        gate = module.live_gate(
            {"closed_trades": 30, "max_drawdown_pct": 0.01},
            {
                "ok": True,
                "closed_trades": 25,
                "return_pct": 0.02,
                "max_drawdown_pct": 0.02,
                "history_days": 90.0,
                "full_fidelity_multiagent": False,
            },
            {"TRADING_MODE": "paper", "LIVE_TRADING_ENABLED": "false"},
        )
        self.assertFalse(gate["ready"])
        self.assertFalse(gate["live_orders_possible"])
        self.assertIn("backtest_price_only_proxy", gate["reasons"])


if __name__ == "__main__":
    unittest.main()

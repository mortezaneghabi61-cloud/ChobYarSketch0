from __future__ import annotations

import importlib
import importlib.util
import hashlib
import hmac
import json
import os
import tempfile
import unittest
import threading
import time
import urllib.error
import urllib.request
from pathlib import Path

from backtest import simulate
from common import SecretFreeAudit


def candles(count: int = 120) -> list[list[float]]:
    rows = []
    price = 100.0
    for i in range(count):
        opened = price
        close = opened * (1.002 if (i // 12) % 2 == 0 else .998)
        rows.append([float(i + 1), opened, max(opened, close) * 1.002, min(opened, close) * .998, close, 10.0])
        price = close
    return rows


class BacktestTests(unittest.TestCase):
    def test_deterministic_and_complete(self) -> None:
        one, two = simulate(candles()), simulate(candles())
        self.assertEqual(one, two)
        for key in ("fees_paid", "closed_trades", "win_rate", "max_drawdown_pct", "pnl", "equity_curve", "benchmark_buy_hold_return_pct"):
            self.assertIn(key, one)
        self.assertEqual(one["lookahead_policy"], "close[i-1] signal; open[i] execution")

    def test_rejects_unsorted_candles(self) -> None:
        rows = candles(); rows[2][0] = rows[1][0]
        with self.assertRaises(ValueError): simulate(rows)


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
        with self.assertRaises(SystemExit): importlib.import_module("trader")


class StatusSecurityTests(unittest.TestCase):
    def test_hmac_replay_response_signature_and_minimal_health(self) -> None:
        status_path = Path(__file__).parents[1] / "v3" / "status_server.py"
        spec = importlib.util.spec_from_file_location("status_server_under_test", status_path)
        module = importlib.util.module_from_spec(spec); assert spec and spec.loader; spec.loader.exec_module(module)
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); secret = "s" * 64
            module.ENV_FILE = root / ".env"
            module.ENV_FILE.write_text(f"STATUS_REQUIRE_AUTH=true\nSTATUS_HMAC_SECRET={secret}\nSTATUS_PORT=0\n", encoding="utf-8")
            module.STATE_FILE = root / "state.json"; module.BACKTEST_FILE = root / "backtest.json"
            module.FORWARD_FILE = root / "forward.json"; module.AUDIT_FILE = root / "audit.jsonl"
            server = module.ThreadingHTTPServer(("127.0.0.1", 0), module.Handler)
            thread = threading.Thread(target=server.serve_forever, daemon=True); thread.start()
            base = f"http://127.0.0.1:{server.server_port}"
            try:
                with self.assertRaises(urllib.error.HTTPError) as denied:
                    urllib.request.urlopen(base + "/status")
                self.assertEqual(denied.exception.code, 401)
                health = json.loads(urllib.request.urlopen(base + "/health").read())
                self.assertEqual(health, {"ok": True, "service": "chobyar-status"})
                ts, nonce = str(int(time.time())), "n" * 32
                signature = hmac.new(secret.encode(), f"{ts}\n{nonce}\nGET\n/status".encode(), hashlib.sha256).hexdigest()
                request = urllib.request.Request(base + "/status", headers={"X-ChobYar-Timestamp": ts, "X-ChobYar-Nonce": nonce, "X-ChobYar-Signature": signature})
                response = urllib.request.urlopen(request); body = response.read()
                expected = hmac.new(secret.encode(), body, hashlib.sha256).hexdigest()
                self.assertTrue(hmac.compare_digest(expected, response.headers["X-ChobYar-Response-Signature"]))
                with self.assertRaises(urllib.error.HTTPError) as replay:
                    urllib.request.urlopen(request)
                self.assertEqual(replay.exception.code, 401)
            finally:
                server.shutdown(); server.server_close(); thread.join(timeout=2)


if __name__ == "__main__": unittest.main()

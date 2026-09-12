from __future__ import annotations

import hashlib
import json
import os
import runpy
import stat
import subprocess
import sys
import tempfile
import types
import unittest
from pathlib import Path
from unittest.mock import patch

from common import SecretFreeAudit, atomic_json


SOURCE_DIR = Path(__file__).resolve().parent
MANIFEST = SOURCE_DIR / "paper_runtime_manifest.json"
RUNTIME_FILES = (
    "common.py",
    "trader.py",
    "trader_entry.py",
    "entry_gate_v55.py",
    "global_sources.py",
)


def safe_environment(app_dir: Path) -> dict[str, str]:
    env = os.environ.copy()
    env.update(
        {
            "CHOBYAR_APP_DIR": str(app_dir),
            "PYTHONPATH": str(SOURCE_DIR),
            "PYTHONDONTWRITEBYTECODE": "1",
            "TRADING_MODE": "paper",
            "LIVE_TRADING_ENABLED": "false",
            "MAX_POSITION_PCT": "0.25",
            "STOP_LOSS_PCT": "0.015",
            "TAKE_PROFIT_PCT": "0.03",
            "MAX_DAILY_LOSS_PCT": "0.03",
        }
    )
    return env


class CanonicalPaperRuntimeTests(unittest.TestCase):
    def run_import(self, overrides: dict[str, str | None]) -> subprocess.CompletedProcess[str]:
        with tempfile.TemporaryDirectory() as tmp:
            env = safe_environment(Path(tmp))
            for key, value in overrides.items():
                if value is None:
                    env.pop(key, None)
                else:
                    env[key] = value
            return subprocess.run(
                [
                    sys.executable,
                    "-B",
                    "-c",
                    (
                        "import sys,types;"
                        "httpx=types.ModuleType('httpx');"
                        "httpx.Client=lambda *args,**kwargs: object();"
                        "httpx.Response=object;"
                        "sys.modules['httpx']=httpx;"
                        "import trader;"
                        "print(trader.MODE, trader.LIVE, trader.MAX_POSITION_PCT,"
                        "trader.STOP_LOSS_PCT, trader.TAKE_PROFIT_PCT,"
                        "trader.MAX_DAILY_LOSS_PCT)"
                    ),
                ],
                cwd=SOURCE_DIR,
                env=env,
                check=False,
                capture_output=True,
                text=True,
                timeout=20,
            )

    def test_runtime_files_match_canonical_manifest(self):
        manifest = json.loads(MANIFEST.read_text(encoding="utf-8"))
        self.assertEqual(tuple(manifest["files"]), RUNTIME_FILES)
        for name, expected in manifest["files"].items():
            with self.subTest(name=name):
                actual = hashlib.sha256((SOURCE_DIR / name).read_bytes()).hexdigest()
                self.assertEqual(actual, expected)

    def test_approved_paper_contract_imports(self):
        result = self.run_import({})
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout.strip(), "paper false 0.25 0.015 0.03 0.03")

    def test_missing_mode_or_live_lock_fails_closed(self):
        for overrides in (
            {"TRADING_MODE": None},
            {"LIVE_TRADING_ENABLED": None},
            {"TRADING_MODE": "live"},
            {"LIVE_TRADING_ENABLED": "true"},
        ):
            with self.subTest(overrides=overrides):
                result = self.run_import(overrides)
                self.assertNotEqual(result.returncode, 0)
                self.assertIn("requires TRADING_MODE=paper", result.stderr)

    def test_any_approved_risk_drift_fails_closed(self):
        cases = {
            "MAX_POSITION_PCT": "0.26",
            "STOP_LOSS_PCT": "0.02",
            "TAKE_PROFIT_PCT": "0.04",
            "MAX_DAILY_LOSS_PCT": "0.04",
        }
        for key, value in cases.items():
            with self.subTest(key=key):
                result = self.run_import({key: value})
                self.assertNotEqual(result.returncode, 0)
                self.assertIn("risk values differ", result.stderr)

    def test_supervisor_preserves_all_approved_risk_exits(self):
        script = """
import sys
import types
from types import SimpleNamespace
httpx = types.ModuleType("httpx")
httpx.Client = lambda *args, **kwargs: object()
httpx.Response = object
sys.modules["httpx"] = httpx
import trader

def market(mid=100.0, spread=0.001):
    return trader.Market(
        best_bid=mid * (1 - spread / 2),
        best_ask=mid * (1 + spread / 2),
        mid=mid,
        spread_pct=spread,
        imbalance=0.0,
        prices=[mid] * 20,
        buy_ratio=0.5,
        global_price=mid,
        global_change=0.0,
        global_sources=["kucoin", "gateio"],
        global_dispersion_pct=0.0,
    )

def broker(qty, entry, equity=10.0):
    return SimpleNamespace(
        state=SimpleNamespace(
            btc_qty=qty,
            entry_price=entry,
            day_start_equity=10.0,
        ),
        equity=lambda _price: equity,
    )

def votes(score):
    return [
        {"available": True, "contribution": score / 6.0}
        for _ in range(6)
    ]

assert trader.supervise(market(98.4), broker(0.1, 100.0), votes(0))[2] == "stop loss"
assert trader.supervise(market(103.1), broker(0.1, 100.0), votes(0))[2] == "take profit"
assert trader.supervise(market(), broker(0.1, 100.0), votes(-2.1))[2] == "consensus exit"
assert trader.supervise(market(), broker(0.0, None), votes(2.5))[0] == "BUY"
assert trader.supervise(market(spread=0.007), broker(0.0, None), votes(2.5))[2] == "market risk gate"
assert trader.supervise(market(), broker(0.0, None, equity=9.6), votes(2.5))[2] == "daily loss hard limit"
print("SUPERVISOR_RISK_PATHS=PASS")
"""
        with tempfile.TemporaryDirectory() as tmp:
            result = subprocess.run(
                [sys.executable, "-B", "-c", script],
                cwd=SOURCE_DIR,
                env=safe_environment(Path(tmp)),
                check=False,
                capture_output=True,
                text=True,
                timeout=20,
            )
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout.strip(), "SUPERVISOR_RISK_PATHS=PASS")

    def test_private_atomic_state_and_secret_free_audit(self):
        secret = "test-secret-value-12345"
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            state_path = root / "state" / "paper_state.json"
            atomic_json(state_path, {"ok": True})
            self.assertEqual(stat.S_IMODE(state_path.stat().st_mode), 0o600)
            with patch.dict(os.environ, {"CHOBYAR_TEST_TOKEN": secret}):
                audit = SecretFreeAudit(root / "logs" / "audit.jsonl")
            audit.write(
                "contract",
                api_key="must-not-appear",
                detail="prefix-" + secret + "-suffix",
            )
            audit_path = root / "logs" / "audit.jsonl"
            self.assertEqual(stat.S_IMODE(audit_path.stat().st_mode), 0o600)
            raw = audit_path.read_text(encoding="utf-8")
            self.assertNotIn(secret, raw)
            self.assertNotIn("must-not-appear", raw)
            row = json.loads(raw)
            self.assertEqual(row["api_key"], "[REDACTED]")
            self.assertEqual(row["detail"], "prefix-[REDACTED]-suffix")

    def test_entry_wrapper_never_intercepts_exits(self):
        calls: list[object] = []
        audit_rows: list[tuple[str, dict[str, object]]] = []
        fake_trader = types.ModuleType("trader")
        fake_trader.APP_DIR = Path("/nonexistent")
        fake_trader.global_snapshot = lambda: None
        fake_trader.main = lambda: None
        fake_trader.AUDIT = types.SimpleNamespace(
            write=lambda event, **fields: audit_rows.append((event, fields))
        )

        def original(_market, _broker, _votes):
            return "SELL", -2.1, "stop loss"

        fake_trader.supervise = original
        fake_gate = types.ModuleType("entry_gate_v55")
        fake_gate.evaluate_entry_gate = lambda *_args, **_kwargs: calls.append(object())
        fake_sources = types.ModuleType("global_sources")
        fake_sources.fetch_global_snapshot = lambda _audit: (None, None, [], None)
        with patch.dict(
            sys.modules,
            {
                "trader": fake_trader,
                "entry_gate_v55": fake_gate,
                "global_sources": fake_sources,
            },
        ):
            namespace = runpy.run_path(str(SOURCE_DIR / "trader_entry.py"))
        result = namespace["v55_supervise"](object(), object(), [])
        self.assertEqual(result, ("SELL", -2.1, "stop loss"))
        self.assertEqual(calls, [])
        self.assertEqual(audit_rows, [])

    def test_entry_wrapper_can_only_confirm_or_block_existing_buy(self):
        audit_rows: list[tuple[str, dict[str, object]]] = []
        fake_trader = types.ModuleType("trader")
        fake_trader.APP_DIR = Path("/paper")
        fake_trader.global_snapshot = lambda: None
        fake_trader.main = lambda: None
        fake_trader.AUDIT = types.SimpleNamespace(
            write=lambda event, **fields: audit_rows.append((event, fields))
        )
        fake_trader.supervise = lambda *_args: ("BUY", 2.5, "consensus entry")
        decision = types.SimpleNamespace(
            allowed=False,
            reason="v5_final_action_not_buy",
            audit_fields=lambda: {"allowed": False},
        )
        fake_gate = types.ModuleType("entry_gate_v55")
        fake_gate.evaluate_entry_gate = lambda *_args, **_kwargs: decision
        fake_sources = types.ModuleType("global_sources")
        fake_sources.fetch_global_snapshot = lambda _audit: (None, None, [], None)
        with patch.dict(
            sys.modules,
            {
                "trader": fake_trader,
                "entry_gate_v55": fake_gate,
                "global_sources": fake_sources,
            },
        ):
            namespace = runpy.run_path(str(SOURCE_DIR / "trader_entry.py"))
        result = namespace["v55_supervise"](object(), object(), [])
        self.assertEqual(
            result,
            ("WAIT", 2.5, "v5 entry gate: v5_final_action_not_buy"),
        )
        self.assertEqual(audit_rows[0][0], "v5_entry_gate")
        decision.allowed = True
        decision.reason = "v5_confirmed_buy"
        result = namespace["v55_supervise"](object(), object(), [])
        self.assertEqual(result, ("BUY", 2.5, "consensus entry + v5 confirmed"))


if __name__ == "__main__":
    unittest.main()

from __future__ import annotations

import hashlib
import json
import os
import runpy
import subprocess
import sys
import tempfile
import types
import unittest
from pathlib import Path
from unittest.mock import patch


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

from __future__ import annotations

import json
import os
import fcntl
import subprocess
import sys
import tempfile
import unittest
from datetime import datetime, timezone
from pathlib import Path
from unittest import mock

import evidence_replay_readiness as readiness
import council_evidence
from test_council_evidence import context, evaluation, scorecard, source_cycle


def record(
    evaluated_at: float,
    *,
    source_count: int = 2,
    complete: bool = True,
    engine_epoch: str = "a" * 64,
) -> dict:
    context = {
        "global_source_count": source_count,
        "global_change_24h": 0.01 if complete else None,
        "global_dispersion_pct": 0.001 if complete else None,
        "funding_rate": 0.0001 if complete else None,
        "funding_z": 0.2 if complete else None,
        "oi_change_pct": 0.003 if complete else None,
        "breadth_24h": {
            "BTC-USDT": 0.01,
            "ETH-USDT": 0.02,
            "SOL-USDT": 0.03,
        }
        if complete
        else {},
    }
    return {
        "evaluation_time_unix": evaluated_at,
        "context": context,
        "engine_sha256": {"engine.py": engine_epoch},
    }


def replay(
    payload: dict,
    *,
    integrity_score: float = 1.0,
    integrity_healthy: bool = True,
    action: str = "WAIT",
) -> dict:
    return {
        "mode": "shadow_observation_only",
        "execution_authority": False,
        "automatic_promotion_enabled": False,
        "automatic_reweighting_enabled": False,
        "foreign_execution_enabled": False,
        "geo_bypass_supported": False,
        "shadow_consensus": {"action": action},
        "meta_intelligence": {
            "execution_authority": False,
            "data_integrity": {
                "score": integrity_score,
                "healthy": integrity_healthy,
            },
        },
    }


class EvidenceReplayReadinessTests(unittest.TestCase):
    def policy(self, **overrides) -> readiness.ReadinessPolicy:
        values = {
            "minimum_history_days": 1.0 / 24.0,
            "target_interval_seconds": 300.0,
            "maximum_gap_seconds": 600.0,
            "minimum_coverage_ratio": 0.95,
            "minimum_quality_ratio": 0.95,
        }
        values.update(overrides)
        return readiness.ReadinessPolicy(**values)

    def test_short_history_is_explicitly_not_ready_and_never_grants_authority(self) -> None:
        result = readiness.assess_records(
            [record(1_700_000_000.0), record(1_700_000_300.0)],
            replay_record=replay,
            policy=self.policy(),
        )
        self.assertFalse(result["ready_for_full_fidelity_backtest_review"])
        self.assertIn("EVIDENCE_HISTORY_TOO_SHORT", result["reasons"])
        self.assertFalse(result["full_fidelity_multiagent"])
        self.assertFalse(result["execution_authority_granted"])
        self.assertFalse(result["live_authority_granted"])
        self.assertFalse(result["automatic_promotion"])

    def test_dense_complete_history_can_be_ready_for_review_only(self) -> None:
        rows = [record(1_700_000_000.0 + index * 300.0) for index in range(13)]
        result = readiness.assess_records(
            rows,
            replay_record=replay,
            policy=self.policy(),
        )
        self.assertTrue(result["ready_for_full_fidelity_backtest_review"])
        self.assertEqual(result["reasons"], [])
        self.assertEqual(result["metrics"]["valid_records"], 13)
        self.assertEqual(result["metrics"]["maximum_gap_seconds"], 300.0)
        self.assertEqual(result["metrics"]["record_coverage_ratio"], 1.0)
        self.assertFalse(result["full_fidelity_multiagent"])
        self.assertFalse(result["execution_authority_granted"])

    def test_gap_and_sparse_coverage_fail_closed(self) -> None:
        rows = [
            record(1_700_000_000.0),
            record(1_700_000_300.0),
            record(1_700_003_600.0),
        ]
        result = readiness.assess_records(
            rows,
            replay_record=replay,
            policy=self.policy(minimum_coverage_ratio=0.80),
        )
        self.assertFalse(result["ready_for_full_fidelity_backtest_review"])
        self.assertIn("EVIDENCE_GAP_TOO_LARGE", result["reasons"])
        self.assertIn("EVIDENCE_COVERAGE_TOO_LOW", result["reasons"])

    def test_quality_source_and_feature_coverage_fail_closed(self) -> None:
        rows = [record(1_700_000_000.0 + index * 300.0) for index in range(13)]
        rows[-1] = record(
            1_700_003_600.0,
            source_count=1,
            complete=False,
        )

        def weak_last(payload: dict) -> dict:
            if payload is rows[-1]:
                return replay(payload, integrity_score=0.85)
            return replay(payload)

        result = readiness.assess_records(
            rows,
            replay_record=weak_last,
            policy=self.policy(minimum_quality_ratio=1.0),
        )
        self.assertIn("DATA_INTEGRITY_COVERAGE_TOO_LOW", result["reasons"])
        self.assertIn("GLOBAL_SOURCE_DIVERSITY_COVERAGE_TOO_LOW", result["reasons"])
        self.assertIn("CONTEXT_FEATURE_COVERAGE_TOO_LOW", result["reasons"])

    def test_multiple_engine_epochs_and_non_monotonic_time_are_rejected(self) -> None:
        rows = [
            record(1_700_000_000.0),
            record(1_700_000_300.0, engine_epoch="b" * 64),
        ]
        result = readiness.assess_records(
            rows,
            replay_record=replay,
            policy=self.policy(minimum_history_days=0.0),
        )
        self.assertIn("ENGINE_EPOCH_COUNT_NOT_ONE", result["reasons"])

        with self.assertRaises(readiness.ReadinessError):
            readiness.assess_records(
                [record(1000.0), record(1000.0)],
                replay_record=replay,
                policy=self.policy(minimum_history_days=0.0),
            )

    def test_unsafe_or_malformed_journal_fails_closed(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            malformed = root / "malformed.jsonl"
            malformed.write_text("not-json\n", encoding="utf-8")
            os.chmod(malformed, 0o600)
            with self.assertRaises(readiness.ReadinessError):
                readiness.audit_journal(malformed, policy=self.policy())

            broad = root / "broad.jsonl"
            broad.write_text("{}\n", encoding="utf-8")
            os.chmod(broad, 0o644)
            with self.assertRaises(readiness.ReadinessError):
                readiness.audit_journal(broad, policy=self.policy())

            link = root / "link.jsonl"
            link.symlink_to(malformed)
            with self.assertRaises(readiness.ReadinessError):
                readiness.audit_journal(link, policy=self.policy())

    def test_real_evidence_records_are_deterministically_audited(self) -> None:
        ctx = context()
        card = scorecard()
        first_time = float(ctx.candles[-1][0]) + 60.0
        records = []
        for evaluated_at in (first_time, first_time + 300.0):
            cycle = source_cycle(ctx)
            cycle["ts"] = datetime.fromtimestamp(
                evaluated_at - 30.0,
                tz=timezone.utc,
            ).isoformat()
            result = evaluation(ctx, card, evaluated_at)
            records.append(
                council_evidence.build_record(
                    ctx,
                    cycle,
                    card,
                    evaluated_at,
                    result,
                )
            )

        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "evidence.jsonl"
            for payload in records:
                council_evidence.append_record(path, payload)
            result = readiness.audit_journal(path, policy=self.policy())

        self.assertEqual(result["metrics"]["valid_records"], 2)
        self.assertEqual(result["metrics"]["engine_epoch_count"], 1)
        self.assertFalse(result["execution_authority_granted"])

    def test_timer_jitter_is_allowed_but_bursts_cannot_hide_missing_time(self) -> None:
        rows = [record(1000.0 + i * 333.8868) for i in range(20)]
        result = readiness.assess_records(rows, replay_record=replay, policy=self.policy())
        self.assertTrue(result["ready_for_full_fidelity_backtest_review"])
        burst = [record(1000.0 + i) for i in range(40)]
        burst += [record(1600.0), record(2200.0), record(2800.0), record(3400.0), record(4600.0)]
        result = readiness.assess_records(burst, replay_record=replay, policy=self.policy())
        self.assertIn("EVIDENCE_COVERAGE_TOO_LOW", result["reasons"])

    def test_snapshot_reader_does_not_hold_writer_lock_during_replay(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "journal.jsonl"
            row = record(1000.0)
            path.write_text(json.dumps(row) + "\n", encoding="utf-8")
            path.chmod(0o600)
            def replay_while_appending(payload):
                with path.open("ab") as writer:
                    fcntl.flock(writer, fcntl.LOCK_EX | fcntl.LOCK_NB)
                    writer.write((json.dumps(record(1300.0)) + "\n").encode())
                return replay(payload)
            with mock.patch.object(readiness.council_evidence, "replay_record", side_effect=replay_while_appending):
                result = readiness.audit_journal(path)
            self.assertEqual(result["metrics"]["valid_records"], 1)
            self.assertEqual(len(path.read_text().splitlines()), 2)

    def test_cli_missing_journal_returns_structured_failure(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            result = subprocess.run(
                [sys.executable, str(Path(readiness.__file__)), str(Path(tmp) / "missing")],
                capture_output=True, text=True, check=False,
            )
        self.assertEqual(result.returncode, 2)
        self.assertFalse(json.loads(result.stdout)["ok"])
        self.assertNotIn("Traceback", result.stderr)

    def test_busy_fifo_partial_and_duplicate_json_are_rejected_without_writes(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "journal"
            for raw in ('{}', '{"x":1,"x":2}\n', '[]\n', '{}\n'):
                path.write_text(raw, encoding="utf-8")
                path.chmod(0o600)
                with self.assertRaises(readiness.ReadinessError):
                    readiness.audit_journal(path)
                self.assertEqual(path.read_text(), raw)
            with path.open("ab") as writer:
                fcntl.flock(writer, fcntl.LOCK_EX | fcntl.LOCK_NB)
                with self.assertRaisesRegex(readiness.ReadinessError, "busy"):
                    readiness.audit_journal(path)
            fifo = Path(tmp) / "fifo"
            os.mkfifo(fifo, 0o600)
            with self.assertRaises(readiness.ReadinessError):
                readiness.audit_journal(fifo)

    def test_policy_and_authority_malformed_inputs_fail_closed(self) -> None:
        for value in (float("nan"), -1, True, "90", None):
            with self.subTest(value=value), self.assertRaises(readiness.ReadinessError):
                readiness.assess_records([record(1000.0)], replay_record=replay,
                    policy=self.policy(minimum_history_days=value))
        def unsafe(payload):
            result = replay(payload)
            result["execution_authority"] = True
            return result
        with self.assertRaises(readiness.ReadinessError):
            readiness.assess_records([record(1000.0)], replay_record=unsafe)
        for rows in ([], [record(float("nan"))], [record(0)], [record(1000), record(900)]):
            with self.assertRaises(readiness.ReadinessError):
                readiness.assess_records(rows, replay_record=replay)

    def test_real_cli_exit_codes_and_tampering(self) -> None:
        ctx = context()
        card = scorecard()
        stamp = float(ctx.candles[-1][0]) + 60
        payload = council_evidence.build_record(ctx, source_cycle(ctx), card, stamp,
            evaluation(ctx, card, stamp))
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "journal"
            council_evidence.append_record(path, payload)
            before = path.read_bytes()
            command = [sys.executable, "-B", readiness.__file__, str(path)]
            for extra, status in (([], 0), (["--require-ready"], 2)):
                result = subprocess.run(command + extra, capture_output=True, text=True, timeout=10)
                self.assertEqual(result.returncode, status, result.stderr)
                report = json.loads(result.stdout)
                self.assertTrue(report["ok"])
                self.assertFalse(report["ready_for_full_fidelity_backtest_review"])
                self.assertFalse(report["full_fidelity_multiagent"])
                self.assertNotIn("context", report)
                self.assertEqual(path.read_bytes(), before)
            payload["context"]["local_mid"] += 1
            path.write_text(json.dumps(payload) + "\n", encoding="utf-8")
            result = subprocess.run(command, capture_output=True, text=True, timeout=10)
            self.assertEqual(result.returncode, 2)
            self.assertFalse(json.loads(result.stdout)["ok"])

    def test_cli_contract_keeps_current_evidence_unready(self) -> None:
        result = readiness.assess_records(
            [record(1_700_000_000.0), record(1_700_000_300.0)],
            replay_record=replay,
        )
        encoded = json.dumps(result, sort_keys=True)
        self.assertIn('"ready_for_full_fidelity_backtest_review": false', encoded)
        self.assertIn('"execution_authority_granted": false', encoded)


if __name__ == "__main__":
    unittest.main()

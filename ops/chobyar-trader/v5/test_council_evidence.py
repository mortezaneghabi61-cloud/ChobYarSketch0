from __future__ import annotations

import json
import math
import os
import stat
import tempfile
import unittest
from datetime import datetime, timezone
from pathlib import Path

import council_evidence as evidence
from meta_intelligence import enhance_council
from specialist_council import CouncilContext, run_council


def candles(count: int = 100, start: float = 1_700_000_000.0) -> list[list[float]]:
    rows: list[list[float]] = []
    price = 80_000.0
    for index in range(count):
        opened = price
        close = opened * 1.001
        high = close * 1.001
        low = opened * 0.999
        rows.append([start + index * 3600.0, opened, high, low, close, 100.0 + index])
        price = close
    return rows


def context() -> CouncilContext:
    rows = candles()
    return CouncilContext(
        candles=rows,
        local_mid=float(rows[-1][4]),
        spread_pct=0.0005,
        book_imbalance=0.22,
        tape_buy_ratio=0.62,
        global_change_24h=0.01,
        global_dispersion_pct=0.001,
        global_source_count=2,
        funding_rate=0.0001,
        funding_z=0.2,
        oi_change_pct=0.006,
        breadth_24h={"BTC-USDT": 0.02, "ETH-USDT": 0.018, "SOL-USDT": 0.025},
    )


def source_cycle(ctx: CouncilContext) -> dict:
    return {
        "ts": datetime.fromtimestamp(
            float(ctx.candles[-1][0]) + 30.0,
            tz=timezone.utc,
        ).isoformat(),
        "event": "cycle",
        "symbol": "BTCUSDT",
        "local_mid": ctx.local_mid,
        "spread_pct": ctx.spread_pct,
        "orderbook_imbalance": ctx.book_imbalance,
        "tape_buy_ratio": ctx.tape_buy_ratio,
        "global_change_24h": ctx.global_change_24h,
        "global_dispersion_pct": ctx.global_dispersion_pct,
        "global_sources": ["kucoin", "gateio"],
        "api_key": "must-never-be-copied",
    }


def scorecard() -> dict:
    agents = evidence.SPECIALIST_AGENTS
    return {
        "specialists": {
            agent: {
                "regimes": {
                    "ALL": {
                        "horizons": {
                            "4h": {"samples": 30 + index, "hit_rate": 0.55 + index * 0.01}
                        }
                    }
                }
            }
            for index, agent in enumerate(agents)
        }
    }


def evaluation(ctx: CouncilContext, card: dict, evaluated_at: float) -> dict:
    return enhance_council(
        ctx,
        run_council(ctx),
        card,
        now_ts=evaluated_at,
    )


class CouncilEvidenceTests(unittest.TestCase):
    def build(self) -> tuple[dict, dict]:
        ctx = context()
        card = scorecard()
        evaluated_at = float(ctx.candles[-1][0]) + 60.0
        output = evaluation(ctx, card, evaluated_at)
        record = evidence.build_record(
            ctx=ctx,
            source_cycle=source_cycle(ctx),
            scorecard=card,
            evaluated_at=evaluated_at,
            enhanced_output=output,
        )
        return record, output

    def test_record_has_explicit_source_reference_and_exact_raw_inputs(self) -> None:
        record, _output = self.build()
        ctx = context()
        self.assertEqual(record["schema"], "chobyar.v5.council-context-evidence")
        self.assertEqual(record["schema_version"], 1)
        self.assertEqual(record["source_cycle_ref"]["ts"], source_cycle(ctx)["ts"])
        self.assertEqual(record["source_cycle"]["global_sources"], ["kucoin", "gateio"])
        self.assertEqual(record["context"]["candles"], ctx.candles)
        self.assertEqual(record["context"]["breadth_24h"], ctx.breadth_24h)
        self.assertEqual(record["context"]["funding_z"], ctx.funding_z)
        self.assertNotIn("api_key", json.dumps(record).lower())
        self.assertEqual(set(record["engine_sha256"]), set(evidence.ENGINE_FILES))

    def test_replay_is_deterministic_and_preserves_observation_only_locks(self) -> None:
        record, expected = self.build()
        replayed = evidence.replay_record(record)
        self.assertEqual(evidence.canonical_sha256(replayed), evidence.canonical_sha256(expected))
        self.assertFalse(replayed["execution_authority"])
        self.assertFalse(replayed["automatic_promotion_enabled"])
        self.assertFalse(replayed["automatic_reweighting_enabled"])
        self.assertFalse(replayed["foreign_execution_enabled"])
        self.assertFalse(replayed["geo_bypass_supported"])

    def test_tampering_fails_closed(self) -> None:
        record, _output = self.build()
        record["context"]["local_mid"] += 1.0
        with self.assertRaises(evidence.EvidenceError):
            evidence.replay_record(record)

    def test_non_finite_or_unknown_sources_fail_closed(self) -> None:
        ctx = context()
        card = scorecard()
        evaluated_at = float(ctx.candles[-1][0]) + 60.0
        output = evaluation(ctx, card, evaluated_at)
        bad_cycle = source_cycle(ctx)
        bad_cycle["global_sources"] = ["kucoin", "unknown"]
        with self.assertRaises(evidence.EvidenceError):
            evidence.build_record(ctx, bad_cycle, card, evaluated_at, output)

        bad_ctx = CouncilContext(**{**ctx.__dict__, "funding_z": math.nan})
        with self.assertRaises(evidence.EvidenceError):
            evidence.build_record(bad_ctx, source_cycle(bad_ctx), card, evaluated_at, output)

    def test_future_or_stale_source_cycle_fails_closed(self) -> None:
        ctx = context()
        card = scorecard()
        evaluated_at = float(ctx.candles[-1][0]) + 60.0
        output = evaluation(ctx, card, evaluated_at)
        for offset in (-301.0, 1.0):
            bad_cycle = source_cycle(ctx)
            bad_cycle["ts"] = datetime.fromtimestamp(
                evaluated_at + offset,
                tz=timezone.utc,
            ).isoformat()
            with self.assertRaises(evidence.EvidenceError):
                evidence.build_record(ctx, bad_cycle, card, evaluated_at, output)

    def test_append_is_private_regular_and_replayable(self) -> None:
        record, _output = self.build()
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "evidence.jsonl"
            evidence.append_record(path, record)
            evidence.append_record(path, record)
            mode = stat.S_IMODE(path.stat().st_mode)
            self.assertEqual(mode, 0o600)
            summary = evidence.validate_journal(path)
            self.assertEqual(summary["valid_records"], 2)
            self.assertFalse(summary["full_fidelity_backtest_ready"])
            self.assertFalse(summary["execution_authority_granted"])

    def test_symlink_and_capacity_fail_before_append(self) -> None:
        record, _output = self.build()
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            target = root / "target.jsonl"
            target.write_text("sentinel\n", encoding="utf-8")
            os.chmod(target, 0o600)
            link = root / "evidence.jsonl"
            link.symlink_to(target)
            with self.assertRaises(evidence.EvidenceError):
                evidence.append_record(link, record)
            self.assertEqual(target.read_text(encoding="utf-8"), "sentinel\n")

            small = root / "small.jsonl"
            with self.assertRaises(evidence.EvidenceError):
                evidence.append_record(small, record, max_journal_bytes=16)
            self.assertFalse(small.exists())

    def test_malformed_journal_fails_closed(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "evidence.jsonl"
            path.write_text('{"schema":"broken"}\nnot-json\n', encoding="utf-8")
            os.chmod(path, 0o600)
            with self.assertRaises(evidence.EvidenceError):
                evidence.validate_journal(path)

    def test_incomplete_or_tampered_tail_blocks_append_without_mutation(self) -> None:
        record, _output = self.build()
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            partial = root / "partial.jsonl"
            partial.write_bytes(b'{"schema":"partial"')
            os.chmod(partial, 0o600)
            before = partial.read_bytes()
            with self.assertRaises(evidence.EvidenceError):
                evidence.append_record(partial, record)
            self.assertEqual(partial.read_bytes(), before)

            tampered = root / "tampered.jsonl"
            changed = json.loads(json.dumps(record))
            changed["context"]["local_mid"] += 1.0
            tampered.write_text(json.dumps(changed) + "\n", encoding="utf-8")
            os.chmod(tampered, 0o600)
            before = tampered.read_bytes()
            with self.assertRaises(evidence.EvidenceError):
                evidence.append_record(tampered, record)
            self.assertEqual(tampered.read_bytes(), before)


if __name__ == "__main__":
    unittest.main()

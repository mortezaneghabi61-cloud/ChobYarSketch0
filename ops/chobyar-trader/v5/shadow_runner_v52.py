from __future__ import annotations

import argparse
import json
import time
from pathlib import Path
from typing import Any

import public_source_fallbacks
import shadow_runner as base
from meta_intelligence import enhance_council

DEFAULT_APP_DIR = Path("/opt/chobyar-trader")


def read_scorecard(app_dir: Path) -> dict[str, Any]:
    path = app_dir / "state" / "v5_specialist_scorecard.json"
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
        return value if isinstance(value, dict) else {}
    except Exception:
        return {}


def run(app_dir: Path) -> dict[str, Any]:
    scorecard = read_scorecard(app_dir)
    original_council = base.run_council
    restore_sources = public_source_fallbacks.install(base)

    def enhanced(ctx):
        raw = original_council(ctx)
        return enhance_council(ctx, raw, scorecard, now_ts=time.time())

    base.run_council = enhanced
    try:
        return base.run(app_dir)
    finally:
        base.run_council = original_council
        restore_sources()


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--app-dir", type=Path, default=DEFAULT_APP_DIR)
    args = parser.parse_args()
    report = run(args.app_dir)
    meta = report.get("meta_intelligence") or {}
    consensus = report.get("shadow_consensus") or {}
    health = report.get("source_health") or {}
    print("V5_META_SHADOW=PASS")
    print(f"REGIME={report['regime']['label']}")
    print(f"SHADOW_ACTION={consensus.get('action')}")
    print(f"PRE_META_ACTION={consensus.get('pre_meta_action')}")
    print(f"META_HOLD={str(bool(consensus.get('meta_hold'))).upper()}")
    print("META_HOLD_REASONS=" + ",".join(consensus.get("meta_hold_reasons") or []))
    print(f"DATA_INTEGRITY={float((meta.get('data_integrity') or {}).get('score') or 0.0):.3f}")
    print(f"UNCERTAINTY={float((meta.get('epistemic_uncertainty') or {}).get('score') or 0.0):.3f}")
    print(f"EXECUTION_STRESS={float((meta.get('execution_stress') or {}).get('score') or 0.0):.3f}")
    print("BREADTH_SOURCE=" + str(health.get("breadth_source") or "none"))
    print("FUNDING_SOURCE=" + str(health.get("funding_source") or "none"))
    print("OPEN_INTEREST_SOURCE=" + str(health.get("open_interest_source") or "none"))
    print("OI_CHANGE_AVAILABLE=" + str(bool(health.get("oi_change_available"))).upper())
    print("EXECUTION_AUTHORITY=NONE")


if __name__ == "__main__":
    main()

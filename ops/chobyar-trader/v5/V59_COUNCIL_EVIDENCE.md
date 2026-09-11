# ChobYar Trader v5.9 Council evidence journal

## Purpose

This change starts collecting the exact observation-only inputs already consumed
by the v5 specialist council. It fixes an evidence-proven attribution gap: older
shadow reports contain derived specialist features and a generation timestamp,
but no explicit reference to the audit cycle selected by `shadow_runner.py`.
Post-hoc timestamp and price matching was unique for only 1,663 of 2,142 existing
shadow reports. This change does not rewrite or relabel those historical rows.

The journal is evidence collection for a future, separately reviewed replay
backtest. It is not a backtest result and it does not satisfy the current 90-day
full-fidelity promotion gate.

## Exact captured evidence

Each schema-version-1 JSONL record contains:

- the selected audit-cycle UTC `ts`, a canonical projection digest, and only the
  local/global fields actually used to construct `CouncilContext`;
- the exact hourly candle rows and local microstructure, global context,
  funding, funding z-score, open-interest change, and BTC/ETH/SOL breadth values
  consumed by the council;
- only the five specialists' effective 4-hour calibration inputs used by the
  current meta layer;
- the evaluation time, exact source-file SHA-256 map, context digest, enhanced
  council result digest, and record digest;
- explicit false authority/promotion/reweighting/foreign-execution/geo-bypass
  invariants.

Extra audit fields are not copied. Secret-like keys are rejected recursively.
No `.env`, credential, order, position-size, or authenticated endpoint data is
read by the evidence module.

## Fail-closed storage and replay

The existing shadow service writes
`/opt/chobyar-trader/logs/v5_council_evidence.jsonl`. The file is opened with
append, close-on-exec, and no-follow flags where supported; it must be a regular
file owned by the service user with no group/other permissions. Each append is
serialized with an exclusive file lock, bounded, flushed, and `fsync`ed.
Before every append, while holding that lock, the writer verifies that the
existing journal ends on a record boundary and deterministically replays its
last record. An interrupted or tampered tail therefore blocks the next shadow
publication without modifying the damaged journal.

Limits are 512 KiB per record and 2 GiB for the journal. At the current five-
minute timer cadence, a representative 168-candle record is about 18 KiB, or
about 460 MB for 90 days. The VPS audit measured more than 21 GB free. There is
no matching ChobYar logrotate rule, so this PR does not add rotation that could
discard the required 90-day window. Hitting a validation, permission, symlink,
record-size, or journal-capacity guard fails the shadow run before it publishes a
new derived report; it does not affect the independent Paper trader process.

Replay validates the schema, hashes, exact engine version, cycle/context
consistency, source-cycle age, authority locks, and deterministic enhanced result.
Malformed, incomplete, tampered, wrong-version, or wrong-engine records are
rejected. Its summary always reports:

```text
FULL_FIDELITY_BACKTEST_READY=NO
EXECUTION_AUTHORITY=NONE
```

## Deployment boundary

The installer requires an exact merged commit SHA and the exact active v5.8
baseline hashes. It installs only `council_evidence.py` and the evidence-wired
`shadow_runner_v52.py`, then starts the existing read-only shadow oneshot once.
It does not modify systemd units, `.env`, risk settings, Trader code, Status code,
or public telemetry. Trader and Status PID/start-time locks must remain unchanged.

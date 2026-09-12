# V6.0 evidence replay-readiness audit

This is a prerequisite audit for a separately reviewed backtest, **not a trading
backtest**. It cannot compute profit, losses, trades, fills, or execution fidelity.
It never changes the V5.6 promotion gate or replaces `backtest_latest.json`.

## Contract

`evidence_replay_readiness.py JOURNAL [--require-ready]` reads the private V5.9
JSONL and deterministically replays each record through `council_evidence`.
The auditor is not part of the evidence engine digest set. Evidence must replay
with its exact recorded engine epoch. V6.1 introduced a reviewed epoch boundary
for the timestamp-precision defect: the V5.9 journal and all six original engine
files are retained together, while the active journal starts from an empty new
epoch. Different hashes still fail replay; old hashes or records are never rewritten.

Exit 0 means the audit succeeded, not that evidence is sufficient. Exit 2 means
invalid/unreadable/busy evidence, or insufficient evidence with `--require-ready`.
Failure output contains no raw inputs. All outcomes keep execution/live authority
and automatic promotion false. `full_fidelity_multiagent` is always false.

## Proposed evidence-window review policy

These are new diagnostic review criteria, not changes to trading/risk thresholds.
No CLI option lowers them. Smaller policies in tests are synthetic fixtures only.

- At least 90 elapsed days of captured evaluations; embedded 168-hour candle
  windows do not count as 168 hours of multiagent evidence.
- Strictly increasing evaluation times; one engine epoch.
- No evaluation gap greater than 600 seconds.
- At least 95% time coverage. An interval contributes `min(gap, 360 seconds)`;
  360 = nominal 300-second cadence plus 60 seconds of scheduling/run tolerance.
  The observed 333.887-second automatic interval is accommodated explicitly.
  Dense bursts cannot compensate for missing elapsed time.
- At least 95% of records have healthy integrity with score >=0.90, at least two
  global sources, and complete global/funding/OI/BTC-ETH-SOL breadth inputs.

The nominal record-count ratio is diagnostic only; it is not the coverage gate.
Quality ratios are record-weighted, not estimates of independent market samples.
The audit covers the entire captured prefix of the active reviewed engine epoch,
not a selected winning interval. Epoch rotation is permitted only for a reviewed
engine change with the complete preceding journal and engine archived together.
The window is historical: first/last evaluation times are reported, and freshness
of current service output must be checked separately. No future performance claim
or automatic promotion follows even if `ready_for_full_fidelity_backtest_review`
eventually becomes true. A full backtest still needs separately reviewed execution,
fees, intrabar path, portfolio risk and historical calibration provenance policies.

## Storage and concurrent operation

The journal must be regular, owned by the auditor's effective user, and private.
Opening rejects symlinks and does not block on a FIFO. A non-blocking shared lock
captures the byte size and a complete newline boundary, then is released before
replay. A busy writer returns a retryable failure, never an unbounded wait.
Only that fixed prefix is read; concurrent appends are excluded from this audit.
Input limits match V5.9 (512 KiB/record, 2 GiB/journal). Duplicate JSON keys,
partial records, tampering, truncation and replacement fail closed.

Like V5.9, this relies on the trusted append-only writer and root-owned engine.
Unkeyed digests demonstrate internal consistency, not authenticity against root
rewriting both inputs and digests. No auditor can reconstruct omitted historical
events or prove that a user with full filesystem control never rewrote a file.

## VPS use

After exact-head CI and squash merge, run the SHA-pinned
`ops/chobyar-trader/verify-v60-evidence-readiness.sh COMMIT_SHA` as root.
It uses a private temporary checkout, compares every engine file to the deployed
copy, runs regressions and audits the existing journal. It installs nothing,
modifies no service/timer/production state, and does not start the shadow oneshot.
Trader/Status identity and allowlisted Paper/risk assignments are checked before
and after. A false readiness result is expected for the new V5.9 journal.

Tests cover real captured records and CLI exit codes, synthetic dense/sparse
windows, scheduling tolerance, burst masking, malformed/tampered/private-file
boundaries, concurrent append, authority rejection and all V5.9 regressions.

# V6.25 shadow agent decision integrity

Base: `13c834516306d2dd49ec6da49d6c205b105b40fe` (V6.24).

## Reproduced defects and changes

- Local market values were checked for presence in the collector but their
  physical ranges were not enforced. Negative spread was silently clamped to
  zero; direct council inputs containing NaN could acquire a directional vote
  through clamping. The collector and council now share a validator: positive
  finite price, nonnegative finite spread, book imbalance in [-1, 1], and tape
  buy ratio in [0, 1]. Booleans are not numeric market evidence. Invalid inputs
  raise before a council result is published.
- Candle volume was not validated. All six OHLCV fields must now be finite and
  volume must be nonnegative. Zero volume remains supported.
- A future local-cycle timestamp passed the freshness check, and a future
  candle timestamp was clamped to age zero. Future cycles are now rejected;
  future candles make meta data integrity unhealthy and force WAIT. The
  existing 180-second cycle and 3.5-hour candle age ceilings remain unchanged.
- Asymmetric confidence calibration could turn raw WAIT into BUY/SELL, despite
  the meta layer's downgrade-only contract. The final directional action now
  requires exact agreement with the original council action. Otherwise it is
  WAIT with `raw_consensus_not_confirmed` as an auditable hold reason.

The same five specialists, quorum of three, and score thresholds of +/-1.25
remain in place. No new order interface, risk setting, or execution authority is
introduced. Existing paper exploration and canonical paper execution files are
unchanged. This is a decision-integrity upgrade, not evidence of profitability.

## Verification

Before implementation the new regressions failed against the base engine.
After implementation:

```sh
python -m unittest discover -s ops/chobyar-trader/v5 -p 'test_*.py' -q
# 53 tests passed: council, meta, source fallback, evidence capture and replay
python -m unittest discover -s ops/chobyar-trader/v6 -p 'test_*.py' -q
# 37 tests passed
python -m unittest discover -s ops/chobyar-trader/v5/execution_safety -p 'test_*.py' -q
# 28 tests passed
```

The V5.9 and V6.0 workflow source hashes are advanced only for the three changed
engine files. Exact source verification and all existing safety gates remain.

## Deployment boundary

This candidate has not been deployed or verified against VPS runtime data.
Do not copy these files over a running evidence engine: the three changed
engine hashes require an evidence epoch migration. Preserve the old journal
and all six old engine files with their manifest, verify the old journal using
that engine, pause the shadow timer, then install the reviewed candidate and
start a separate journal. Replay the new journal with the candidate engine
before resuming the timer. On failure restore both the old engine and journal.
Keep the trader and status services, their state, and the approved risk tuple
unchanged. Existing historical installers are not an installer for V6.25.

Required before activation: exact-head CI, a fresh VPS paper/live-lock and
runtime identity check, and a reviewed SHA-pinned rollback-capable epoch
migration. Never rewrite historical evidence hashes or pool different engine
epochs to claim readiness. Main governance remains unchanged.

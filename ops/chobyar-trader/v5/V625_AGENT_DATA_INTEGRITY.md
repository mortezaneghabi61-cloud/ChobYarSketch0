# V6.25 candidate: specialist input integrity and meta authority

Base: `13c834516306d2dd49ec6da49d6c205b105b40fe` (V6.24).

## Observable changes

- One local-market validator is shared by the context builder, liquidity
  specialist, adversarial supervisor, and meta data-integrity check. Require a
  positive price, nonnegative spread, book imbalance in [-1, 1], and tape buy
  ratio in [0, 1]. Missing, boolean, nonnumeric, and non-finite values are invalid.
- The ingestion boundary rejects invalid local observations instead of repairing
  a negative spread to zero. Direct council callers receive an unavailable,
  zero-confidence liquidity vote and an adversarial veto, forcing WAIT.
- Meta integrity rejects future, duplicate, and reversed hourly timestamps.
- Calibration may preserve the original direction or force WAIT. It cannot turn
  a raw WAIT into BUY/SELL or reverse the raw direction, even if asymmetric
  confidence shrinkage changes the aggregate score. The hold reason is
  `raw_action_guard`.

Quorum stays three, score thresholds stay +/-1.25. No execution or automatic
promotion/reweighting authority is added. Paper execution code, V6 exploration,
risk parameters, services, credentials, and governance are unchanged.

## Verification

Before implementation: 43 existing V5 tests passed. Seven added test methods
exposed 28 assertion failures and five errors across their subcases.

After implementation: 50 V5 tests passed, covering invalid inputs, valid boundary
values, raw-action authority, fresh versus future/reversed timestamps, ingestion,
evidence recording, replay, and fail-closed readiness. Re-run with:

```sh
python -m unittest discover -s ops/chobyar-trader/v5 -p 'test_*.py' -v
python -m unittest discover -s ops/chobyar-trader/v5/execution_safety -p 'test_*.py' -v
python -m unittest discover -s ops/chobyar-trader/v6 -p 'test_*.py' -v
```

The existing CI source-digest checks are advanced to this candidate epoch; none
are removed. The wrapper, evidence schema, replay rules and readiness gates are
unchanged.

## Deployment boundary

This is a repository candidate, not a verified VPS installation. The connected
desktop was offline and the public monitor request timed out during this task.
No service was restarted and no runtime data was modified.

The engine hashes change. Existing evidence cannot be relabeled or appended to
under the new engine: the journal tail check intentionally rejects a different
epoch. Before a separately verified deployment, stop only the shadow producer,
archive the old journal and its exact engine together without overwriting either,
then start a new evidence journal for this epoch. Retain a rollback path to the
old engine and its matching journal. Do not mix epochs, weaken hash validation,
or claim old scorecard samples validate V6.25; begin a fresh specialist scorecard
evaluation cohort as well. VPS deployment and fresh forward-performance evidence
remain unverified. No profitability improvement is claimed by these integrity
tests.

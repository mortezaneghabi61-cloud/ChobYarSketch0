# Paper runtime: recent-trade validity and protective exits

The prior local tape parser discarded timestamps and coerced arbitrary side values
with `bool()`. A response with trades 646 seconds old was observed on the VPS.
An isolated regression reproduced a BUY with that age and sufficient votes.
This establishes a data-validity defect; it does not establish that a historical
loss was caused by this defect or that this patch creates a profitable strategy.

## Contract

- New entries need at least ten valid BTCUSDT trades within the last 300 seconds.
- Timestamps must be timezone-aware, prices positive and finite, sides boolean,
  and future timestamps at most five seconds ahead of the server clock.
- Trades are sorted newest first before the existing momentum calculation.
- Unavailable tape contributes neither a momentum nor a tape-flow vote.
- New BUY is explicitly blocked when tape is unavailable, even if other votes
  would be strong enough. Empty/stale tape can coexist with a usable quote.
- Protective stop-loss and take-profit decisions run before entry gates and
  remain usable with stale tape, missing quorum, or a wide spread when the quote
  itself is finite, positive and correctly ordered.
- Global data are requested before local data so global network delays do not
  age the local snapshot before its decision.
- Cycle logs expose tape age, recent row count and availability.

The five-minute window and five-second clock tolerance are conservative data
quality choices, not parameters selected for profitability. Low-activity periods
will produce more WAIT decisions. HTTP failure of the local market endpoints can
still prevent a cycle; this patch does not claim to solve all exit availability risks.
Risk limits, paper state, credentials, the V5 entry wrapper, and live modules are
not modified. The canonical source includes the earlier tested protective-exit
ordering fix that was already present on the target VPS.

## Installation

`install_tape_freshness.py` expects the pinned new runtime and its tests alongside
it. It accepts only the known previously patched trader SHA-256 and unchanged
dependencies, requires an active paper process, runs the nineteen runtime tests,
backs up source and manifest, writes atomically, and checks for a fresh cycle with
the new tape diagnostics. Failed health verification rolls back source and
manifest. No paper state or environment file is rewritten.

Run the full twenty-three regression checks with:

```sh
python3 -B -m unittest discover -s . -p 'test_*.py' -v
```

The stale-entry regression was observed to fail against the previous installed
source before the fix. Installer checks cover success, idempotence, rollback,
state preservation, unknown-source refusal, and live-environment refusal.

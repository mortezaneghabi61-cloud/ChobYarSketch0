# Fixed-protocol trend research

This is an isolated research tool, not a trader upgrade or a live execution system.
It never reads credentials, imports installed trading modules, sends orders, or changes services.
Its only network request is the public Wallex BTCUSDT hourly candle GET endpoint:
https://api-docs.wallex.ir/#candles (endpoint: `/v1/udf/history`).

## Run

Use Python 3.10+ and the standard library:

```sh
python3 trend_research.py --app-dir /opt/chobyar-trader --output-root /opt/chobyar-trader/research
```

To inspect one failed window without repeating the full download or changing existing files:

```sh
python3 trend_research.py --diagnose-run /path/to/existing/trend-study-run --chunk 26
```

Diagnostics reuse the frozen start/end timestamps and request only that public-data window.
They report array sizes and specific missing-hour/invalid-OHLC errors. A window that validates
now does not establish that the earlier response was valid or authorize a strategy.
New full runs also preserve each public response in `chunks/`, including a failed validation
window; older runs created before this improvement did not save partial downloads.

To rerun the exact frozen period and strategy, trying the known failing window first:

```sh
python3 trend_research.py --repeat-run /path/to/existing/trend-study-run --preflight-chunk 26
```

This creates a separate report directory and preserves the old run. The frozen strategy fields
and 180-day-plus-warmup duration must match exactly. The program processes the preflight window
first, then downloads remaining windows and restores chronological order before simulation.
The first historical run did not save its 25 downloaded windows, so those must be downloaded
again after preflight succeeds; the tool does not claim to resume missing cache files.

If a recent hour remains unavailable at both hourly and minute resolution, an explicitly
separate historical screen can end before that gap:

```sh
python3 trend_research.py --historical-end-utc 2026-09-22T13:00:00Z
```

This keeps all strategy and cost parameters fixed, downloads a complete earlier 180-day
period plus warm-up, and creates a new report. It does not complete the original blocked
period. The report records `evaluation_scope=separate_historical_window` and the timestamp
after which data were not evaluated. It cannot issue `FORWARD_PAPER_TEST_ONLY`; a favorable
numeric screen is labeled `HISTORICAL_SCREEN_PASSED_NO_PROMOTION`. Repeating this run retains
that restriction. No later interval is claimed as evaluated, and the shifted final period
must not be presented as validation on the original untouched holdout. Dates require a time
zone, a whole UTC hour, and an end earlier than the current completed-hour boundary.
The historical date, repeated run, and diagnostic run options are mutually exclusive.

For at most 12 missing hours per window, the program first requests a smaller three-hour
window from the same Wallex BTCUSDT endpoint. If the target hour is still absent, it requests
`resolution=1`, documented by Wallex, for that exact hour. Aggregation requires all 60 distinct,
aligned, valid minute candles: first open, maximum high, minimum low, last close, summed volume.
Even one missing minute blocks recovery. There is no interpolation, forward fill, skipped
test interval, or substitution from another exchange. Original responses, recovery responses,
and per-hour provenance are retained separately. Malformed OHLC data and conflicting duplicates
are never silently replaced by recovery. This is data retrieval repair, not strategy retuning.

The program freezes and saves its protocol before downloading 180 days plus warm-up.
It saves normalized candles, their SHA-256, code SHA-256, results, and individual simulated exits
to a new private output directory. Incomplete hourly coverage, conflicting candles, invalid prices,
and download failures cannot produce a strategy approval. No missing bars are fabricated.
The public data fetch must still be verified from the target VPS; the development environment
could not connect to external exchange endpoints. Unit tests use deterministic fixtures.

## What is compared

One predeclared candidate uses the previous completed hourly close above SMA20 above SMA50,
with SMA20 rising. Entries occur at the next hourly open. Exit conditions are trend failure,
1.5% stop, or 3% target. The simulated position notional is 25% of cash. The daily loss entry gate
is 3%; it never blocks exits. A losing exit blocks the following six bars from re-entry.
Signal parameters are fixed, not optimized against the reported periods.

The same candles, periods, starting equity, and per-side costs are used for candidate,
25%-notional buy-and-hold, and cash. Splits are chronological 90/45/45 days; each starts flat
with 10 USDT. Indicators may use earlier bars for warm-up, never future bars.
The final 45 days are historical held-out screening, not forward live evidence.
Rerunning after parameter changes contaminates this holdout and must be disclosed.

Base costs assume 0.2% fee plus 0.1% execution haircut per side. Stress doubles both.
These are explicit research assumptions, not a statement of the user's actual fee tier or fills.
Stops use adverse open on gaps and take priority over targets if both occur in one bar.
Positions remaining at period end are liquidated with costs and labeled `end_of_test`.
Profit factor uses net positive/negative trade P&L; fees are already deducted.
Drawdown is measured at hourly closes, not worst intrabar drawdown.

The current bot's actual audit totals are presented separately and reconciled with its state
when available. They are **not** a reconstructed same-period benchmark: current order-book,
tape, and V5 gate decisions cannot be recovered from hourly candles alone.
No claims of beating the current bot may be inferred from unmatched historical totals.

## Screening is not permission to trade

At least 30 natural exits are required in each later period under each cost scenario.
Positive net P&L, profit factor >=1.2 (or no losing trades), and hourly-close drawdown <=5%
are necessary screens. These thresholds are engineering choices, not a statistical confidence guarantee.
Results are `INSUFFICIENT_EVIDENCE`, `REJECTED`, or `FORWARD_PAPER_TEST_ONLY`.
Every report sets `live_ready=false` and `automatic_promotion=false`.

Historical candle fills cannot establish order-book liquidity, capacity, actual fees,
minimum order constraints, or safe real execution. A favorable screen still requires
fresh forward paper evidence, calibrated costs, ledger reconciliation, and separate review.

## Verification

```sh
python3 -B -m unittest discover -s . -p 'test_*.py' -v
```

Tests cover completed-bar causality, data gaps and malformed OHLC, chronological sorting,
cost arithmetic and ledger reconciliation, adverse stop fills, ambiguous intrabar order,
loss cooldown, small-sample rejection, network failure, and preserving application files.

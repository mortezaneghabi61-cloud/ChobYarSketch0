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

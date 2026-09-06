# Stage 26 — Live Adversarial Market Veto

Stage 26 adds a fail-closed, read-only pre-entry veto in front of live BTCUSDT BUY orders. It does not create a new exchange mutation surface.

## Authority chain

For a live BUY, the governed order is:

1. Stage 26 adversarial market veto.
2. Stage 25 equity sizing and daily-loss breaker.
3. Stage 22 single LIMIT-order submission surface.

SELL exits are not blocked by Stage 26 or Stage 25, so risk-reducing exits remain available. Stage 23 remains the position-bound live exit guardian. The production trader service remains paper-only.

## Unique source quorum

Stage 24 no longer accepts anonymous scalar `global_prices`. Every global price is a `PriceObservation(source_id, price)`. Duplicate, missing, invalid, or unapproved source identity fails closed. Stage 26 requires at least three unique approved sources and requires the stored quorum count to exactly equal the unique source count.

Configured independent public references are Coinbase, Kraken, OKX, and Bybit. One source may fail while retaining a 3-source quorum; two failures block new BUY entries.

## Local market and order-book history

Wallex BTCUSDT bid/ask/last and depth are collected using authenticated GET requests. The API key is read locally from `/opt/chobyar-trader/.env`; it is never written to Stage 26 state, logs, PRs, or output.

A single depth snapshot is never treated as spoofing evidence. At least three consecutive local snapshots are required before Stage 26 can allow a BUY. Missing history, invalid history, a gap greater than 45 seconds, stale state, or collector failure leaves the veto closed.

The current order-book disappearance metric is deliberately conservative. It is a veto signal, not a claim that a specific actor spoofed the market. A false positive blocks entry rather than creating risk.

## Freshness

The collector is scheduled every 20 seconds. A BUY requires a snapshot no older than 60 seconds. The collector state and history are local mode-0600 files under `/opt/chobyar-trader/state`.

## Live safety boundaries

- BTCUSDT Spot only.
- Existing absolute order cap remains 10 USDT.
- Existing 25% position cap and 3% daily-loss breaker remain enforced by Stage 25.
- Withdrawals disabled.
- Leverage disabled.
- Margin disabled.
- Futures disabled.
- OTC disabled.
- Stage 24 and Stage 26 contain no POST/PUT/PATCH/DELETE exchange calls.
- External text/news is not part of the Stage 26 live execution authority. Stage 24's prompt-injection checks remain a defensive parser boundary, but untrusted text cannot directly authorize a trade.

## Fail-closed installation behavior

Installation starts the read-only collector but does not require the first snapshot to be clear. The first two consecutive observations are intentionally `history_warmup` and therefore block BUYs. After sufficient fresh history exists, `live_adversarial_veto_stage26.py --check` can pass only if the unique-source and market-integrity checks are clear.

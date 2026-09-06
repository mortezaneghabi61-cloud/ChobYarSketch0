# Stage 25 — Live Entry Equity & Daily-Loss Risk

Stage 25 makes the previously configured risk values operational for future live BTCUSDT BUY entries.

## Enforced contract

- `MAX_POSITION_PCT=0.25` is enforced against the BTCUSDT trading book, defined conservatively as `USDT total + BTC total * current bid`.
- Existing BTC exposure is included. A new BUY is allowed only if the post-entry position stays within 25% of book equity.
- The existing absolute `10 USDT` order cap remains an additional ceiling, not a target size.
- Available free USDT is another hard ceiling.
- `MAX_DAILY_LOSS_PCT=0.03` is enforced as a current-equity drawdown from the UTC-day baseline. At or beyond 3%, new BUY entries fail closed.
- SELL exits are not blocked by Stage 25; risk reduction must remain possible.
- Missing, invalid, or stale daily baseline blocks new BUY entries.

## Equity scope

Stage 25 intentionally uses only the BTCUSDT trading book. TMN and unrelated assets are excluded from sizing. This is conservative for BTCUSDT entry sizing and prevents unrelated balances from inflating BUY authority.

## Baseline lifecycle

Installation initializes the current UTC-day baseline using authenticated GET requests and a local atomic state write. A systemd timer refreshes the baseline at `00:00:05 UTC` each day. The timer is intentionally `Persistent=false`: if the server is down at the rollover and misses the baseline event, the previous baseline becomes stale and future BUYs fail closed instead of silently starting a late baseline.

## Authority boundaries

`live_entry_risk_stage25.py` has no POST/PUT/PATCH/DELETE exchange surface. It only reads balances and market data and writes the local risk state. Stage 22 remains the single governed live BUY submission surface, and now invokes Stage 25 before any BUY POST. Production `chobyar-trader.service` remains paper-only. Stage 23 remains the position-bound live SELL risk guardian.

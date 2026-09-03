# ChobYar Trader v4 — paper only

This branch contains a bounded multi-agent paper engine, deterministic no-lookahead backtest, separate forward-test state, secret-free append-only audit writer, and authenticated read-only status plane. It deliberately contains no live-order adapter.

Approved risk values are hard locked to `0.25 / 0.015 / 0.03 / 0.03`. The process exits unless mode is exactly `paper` and live trading is exactly `false`.

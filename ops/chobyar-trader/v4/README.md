# ChobYar Trader v4 — frozen legacy reference

Runtime authority moved to `ops/chobyar-trader/v5/execution_safety/` and the production Paper Trader ExecStart was cut over to that v5 path in Stage-9.

This v4 tree is retained only for historical compatibility, backtest/reference parity, and rollback archaeology. It is **not** an execution-authority path. Do not add new trading, safety, authenticated-exchange, or runtime behavior here.

The approved historical risk baseline remains `0.25 / 0.015 / 0.03 / 0.03`. The authoritative v5 runtime remains fail-closed in Paper mode with live execution disabled. Withdrawal, leverage, margin, futures, and OTC authority are not granted here.

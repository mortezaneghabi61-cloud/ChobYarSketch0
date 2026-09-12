# Canonical V5 Paper Runtime

This directory contains the complete source dependency closure for the active
paper-trading entrypoint:

`/opt/chobyar-trader/app/v5/execution_safety/trader_entry.py`

The baseline deliberately contains no live executor, authenticated exchange
client, order-submission path, deployment script, or automatic promotion.
Merging it changes repository source-of-truth only; it does not deploy or
restart any VPS service.

## Provenance

- `common.py`, `trader.py`, `trader_entry.py`, `entry_gate_v55.py`, and
  the entry-gate regression tests are isolated from commit
  `e52b4d683d5f7215cb5284faa1915b5d530747ea`.
- `global_sources.py` and its tests remain the V5.8 implementation from
  commit `4d9ebe15342fa53730838cc16366b09fd99e2b16`.
- `paper_runtime_manifest.json` pins every executable source hash and the
  approved risk values.

The previously observed VPS hashes for `common.py`, `trader.py`,
`trader_entry.py`, and `global_sources.py` match this baseline exactly.
The entry-gate hash is pinned from the isolated source and must be checked
read-only on the VPS before any future deployment is considered.

## Safety contract

- Import fails closed unless `TRADING_MODE=paper` and
  `LIVE_TRADING_ENABLED=false`.
- Import fails closed if any approved risk value changes.
- The V5 gate sees only an already-proposed BUY and may confirm it or downgrade
  it to WAIT.
- SELL, stop-loss, take-profit, daily-loss, and spread decisions remain owned
  by the proven paper supervisor and bypass the V5 entry gate.
- Public global sources use GET-only, credential-free market data.

Run the deterministic contract locally:

```bash
source_dir=ops/chobyar-trader/v5/execution_safety
PYTHONPATH="$source_dir" python3 -m unittest -v \
  "$source_dir/test_global_sources.py" \
  "$source_dir/test_entry_gate_v55.py" \
  "$source_dir/test_paper_runtime.py"
```

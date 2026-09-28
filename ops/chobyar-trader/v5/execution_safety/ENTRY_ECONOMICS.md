# Paper entry economics

The paper broker pays a fee on both sides, but the entry supervisor previously
checked only consensus and market/risk gates. A valid fee setting of 2% per side
allowed a BUY even though the unchanged 3% take-profit threshold could not repay
the entry outlay. The focused regression reproduced `BUY` before this change.

## Behavior

Before a new consensus BUY, estimate the configured target and stop payoffs
after both paper fees and the observed relative spread. Reject entry if the
estimate is invalid or the target net return is at most `1e-12` (a numerical
zero tolerance). Protective stop/take exits and consensus exits retain their
existing priority. The normal 0.2%-per-side fee still allows eligible entries.

Each cycle records `entry_economics` with the fee, observed spread, net target
and stop returns, break-even mid-price move from entry ask, and the win fraction
needed to break even in a hypothetical target-or-stop-only sequence. This last
quantity is **not a forecast probability**. Consensus exits, variable fills,
gaps, slippage, changing spreads and exchange order filters are excluded.

Let `f` be the per-side fee and `r = current_bid/current_mid`. A new paper BUY
fills at ask. For a threshold move `x` from entry ask, the projected exit bid is
`entry_ask * (1+x) * r`. The return on cash paid, including the entry fee, is
`(1+x)*r*(1-f)/(1+f)-1`. This follows the existing broker's cash ledger and the
supervisor's mid-price exit triggers. It assumes the future bid/mid ratio stays
unchanged. Stop figures are scenarios, not guaranteed maximum losses.

At zero spread and a 0.2% fee per side, a 100-unit entry costs 100.20. The target
returns 102.794 and the stop returns 98.303. Net payoffs are +2.594 and -1.897;
the target-or-stop break-even win fraction is 1.897/4.491, about 42.24%.

The recorded 16-trade paper account had gross P&L before its recorded fees of
+0.0810840291 USDT, fees of 0.1597689826, and net P&L of -0.0786849535.
This bookkeeping result motivates explicit cost reporting. It does not prove
that high configured fees caused previous entries, that every prior exit
followed a target/stop rule, or that this patch makes the strategy profitable.
No fee assumption is reduced, historical account reset, or strategy approved.

## Verification and installation

`test_entry_economics.py` checks the high-fee regression, exact break-even,
normal entry, all exit paths, invalid input, fee/spread sensitivity, audit
output, and agreement with actual paper broker round trips at both thresholds.
Existing paper and freshness tests remain applicable.

`install_entry_economics.py` accepts only the installed freshness-patched trader
SHA-256 `25b7739b65a5d8e72ad59e32c51422dbab846d4fbdd68a15379d5733d2b2c061`
with unchanged dependencies and a verified active paper entrypoint. It runs
28 runtime tests from its exact bundle before touching the server, backs up
source and manifest, atomically installs, and verifies a new cycle containing
the cost model. Failed health verification rolls back source and manifest.
It does not edit environment settings, paper account state, live scripts,
credentials, timers, or risk limits.

Use the files from the same pinned commit: `trader.py`, `common.py`,
`trader_entry.py`, `entry_gate_v55.py`, `global_sources.py`,
`paper_runtime_manifest.json`, `test_paper_runtime.py`, `test_tape_freshness.py`,
`test_entry_economics.py`, and `install_entry_economics.py`. Then run:

```sh
sudo python3 install_entry_economics.py
```

The full execution-safety directory passes 54 checks, including new and
historical installer checks, the V5 entry gate and global source handling:
`python3 -B -m unittest discover -s . -p 'test_*.py' -v`.
The historical installer's mechanics tests use a self-contained candidate
fixture so later runtime changes do not invalidate their pinned bundle. The
historical installer itself still requires its original pinned commit.

Deployment is a separate verification step. Passing isolated tests does not
prove the candidate has been installed or that it will earn money.

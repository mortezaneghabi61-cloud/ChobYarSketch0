# ChobYar Trader v5 execution safety

This directory is the authoritative v5 execution-safety surface.

Stage-8 is a behavior-preserving migration from the previously active `v4` safety location. The migrated Python modules are intentionally byte-identical to their proven v4 counterparts in this stage so no trading semantics, risk values, exchange permissions, or network behavior change during the move.

`ops/chobyar-trader/v4` remains frozen as a compatibility source until a later runtime migration proves that installers and the VPS no longer depend on it. New execution-safety work must target this v5 directory.

Safety remains fail-closed: paper/dry-run only, live execution disabled, spot-only, withdrawals disabled, leverage disabled. This directory does not grant live-order authority.

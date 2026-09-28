# Preserve protective paper exits during a tape endpoint outage

The local snapshot previously required both `/v1/depth` and `/v1/trades` to
succeed. A failed trades request aborted the whole cycle even when a valid book
quote existed and an open paper position had crossed its stop threshold. A
deterministic network-failure regression reproduced this exception before the
change. It is not evidence that a specific historical loss came from an outage.

The trades endpoint is now optional for obtaining an executable quote. HTTP
failures, JSON decoding failures and rejected API responses mark the tape
unavailable and log only the exception class as `local_tape_error`. The existing
freshness gate still prevents a new BUY without valid recent trades. Stop-loss
and take-profit decisions remain available when a valid book quote exists.

The trades request runs before the order-book request, including on success,
so tape request latency does not age the book quote. Tape timestamps are still
evaluated after both requests. The book itself remains mandatory: missing,
invalid or crossed quotes abort the snapshot; no quote is fabricated or reused.
Cycles record `quote_policy=book_after_optional_tape_v1` for installation checks.

This remains a polled paper simulation. It does not guarantee a maximum loss,
remove every network delay, or place exchange-native stops. Account state,
environment, fees, risk limits, live modules and decision thresholds are not
changed. The previous freshness and fee-aware entry fixes remain included.

## Verification

Six new tests exercise failed tape/valid book stop and take-profit paths, new
entry rejection, JSON/API/HTTP failure classes, request ordering on success and
failure, invalid/unavailable books, and an actual paper broker close through
`run_once`. All 64 execution-safety tests pass locally:

```sh
python3 -B -m unittest discover -s . -p 'test_*.py' -v
```

The canonical workflow now discovers this entire directory. Historical
installer mechanics use their own candidate fixtures; pinned historical
installers must still be run with files from their original commits.

## Installation

`install_exit_data_availability.py` accepts only predecessor SHA-256
`a0dc8f44afbb63349c9327205fb71ae8965c8dee6a523259ac5a940ef9be3697`,
unchanged dependencies and the verified active paper entrypoint. It runs 34
runtime tests, backs up source and manifest, writes atomically, restarts only
the paper trader and requires a fresh cycle with the new quote policy. Failed
health verification restores source and manifest. Account state is not reset.

Fetch the current runtime's five Python files, its manifest, the four runtime
test modules (`test_paper_runtime`, `test_tape_freshness`,
`test_entry_economics`, `test_exit_data_availability`) and this installer from
one pinned commit, then run `sudo python3 install_exit_data_availability.py`.
Test success and deployment success are separate results; neither establishes
a profitable trading strategy.

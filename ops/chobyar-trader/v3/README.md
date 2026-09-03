# ChobYar-Trader v3 status/security slice

This slice upgrades only the read-only status plane. It adds HMAC-SHA256 request authentication with replay protection, response signatures, secret-safe reporting, audit-derived paper PnL/win-rate/drawdown metrics, and an explicit fail-closed Live Gate. It does not change the trader engine, risk parameters, paper positions, or add any real-order execution adapter.

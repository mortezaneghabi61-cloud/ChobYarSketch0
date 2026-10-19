# Bounded offline research result — 2026-10-16

This is research on **public BTCUSDT Wallex hourly data**, NOT production trading. Source data originally downloaded/read-only on the Windows laptop. Instrument is spot; do not use Vibe-Trading's perpetual-contract engine as a spot engine.

| Fixed no-optimization baseline | 216-hour out-of-sample holdout net return |
|---|---:|
| No trade | 0.00000% |
| 25% capital buy-and-hold (estimated exit costs) | -0.82196% |
| SMA20/50 at next hour open | -1.19162% |

Model inputs: initial virtual 10 USDT, maximum spot stake 25% per entry, 0.2% **each-side fee**, 0.05% each-side adverse slippage, no leverage. Signals for an entry depend only on the previous *closed* candle; model uses next-hour open. This is not the ChobYar-Trader strategy, and results do not reproduce its production PnL.

- Vibe-Trading pinned `596fcf3d63eb4d96e067f6dc94742b5f74a230ea`: actual upstream Wallex loader processed 718 stored real historical bars through a network-disabled fixture (1 internal missing hour). The loader was isolated from the full unreviewed app and registry.
- TradingAgents pinned `1394a3f72aa4393e1a98f51b382434c4b4c2d972`: upstream pure point-in-time date-grid and alpha parser verified offline. No model inference occurred; this is NOT a full TradingAgents backtest.
- 14 local Python unit tests passed on the Wallex parser and no-lookahead spot sample engine.
- Ruflo 2 scoped metadata agents registered; 0 paid agent executions. Memory initialized and research memo stored; no real-market execution.
- Costs not fully modeled: actual fee tier uncertain, realistic spread and fill probability absent; min order notional; intrabar stop/target sequence; broader regimes; gap recovery. Hence result has **NO-GO for live** and no generalized profit claim.
- Reproducible local outputs: `TraderLab/data/vendor_bridge_verified.json`, `TraderLab/data/baseline_research.json`, and exact scripts `TraderLab/tools/vendor_bridge.py`, `spot_backtest.py`, `test_spot_backtest.py`.

Next decision: do NOT choose SMA20/50 for deployment; first prove a strategy exceeds no-trade after verified costs on larger time-separated Wallex datasets.

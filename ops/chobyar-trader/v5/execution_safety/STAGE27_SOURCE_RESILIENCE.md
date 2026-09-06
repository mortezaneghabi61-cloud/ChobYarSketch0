# Stage27 Source Resilience

Stage27 repairs the production-data availability problem discovered on the VPS after Stage26 deployment without weakening fail-closed behavior.

## Proven VPS connectivity

The deployed server returned HTTP 200 from CoinLore, CoinPaprika, and CoinGecko. Several direct exchange endpoints were region/IP blocked or unavailable. Stage27 therefore uses those three reachable services only as `reference_aggregator` evidence. It does **not** describe them as three independent exchanges.

Bybit remains an optional `direct_exchange_optional` source when reachable. It can strengthen evidence but can never replace the required three reference aggregators.

## Live BUY contract

A BUY can pass the Stage26/27 veto only when all of the following are true:

- authenticated Wallex BTCUSDT market and depth GETs succeed;
- at least three consecutive local order-book samples are fresh and gap-safe;
- all three required reference aggregators are present with unique source IDs;
- reference prices satisfy the existing Stage24 dispersion and local/global divergence limits;
- evidence-class metadata contains both `local_execution_venue` and `reference_aggregator`;
- the snapshot is version 2 and carries `STAGE27_REFERENCE_RESILIENCE`;
- the snapshot is no older than 60 seconds;
- Stage24 adversarial checks are clear;
- downstream Stage25 equity sizing and daily-loss controls pass;
- Stage22's existing single BTCUSDT LIMIT mutation surface is the only exchange POST.

Any missing, malformed, stale, inconsistent, duplicated, unapproved, under-quorum, divergent, or old-version evidence fails closed.

## Security invariants

Stage24/26/27 have no POST/PUT/PATCH/DELETE exchange authority. Stage27 installer does not enable the production trader for live trading and does not modify `.env`. Withdrawals, leverage, margin, futures, and OTC remain disabled. The 10 USDT absolute live-order cap remains unchanged.

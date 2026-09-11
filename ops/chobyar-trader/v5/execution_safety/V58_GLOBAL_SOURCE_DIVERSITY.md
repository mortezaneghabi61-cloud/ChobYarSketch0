# V5.8 global source diversity

The active Paper Trader obtained its global market snapshot from one provider.
The configured providers were observed from the VPS as follows on 2026-09-11:

- KuCoin: HTTP 200 with valid price and 24-hour change.
- OKX: HTTP 403.
- Coinbase Exchange: connection failure or timeout.
- Kraken: HTTP 403.

The latest audited cycle consequently contained only `kucoin`. V5 data
integrity remained healthy but scored 0.85 because global source diversity
requires at least two independently parsed providers.

One safe probe from the same VPS verified Gate.io, MEXC, Bitget and Bybit with
HTTP 200 and valid price/change parsing. Binance timed out. The source order is
therefore KuCoin, Gate.io, MEXC, Bitget and Bybit, followed by the older
providers. Collection stops after two prices and one change value, so normal
cycles use KuCoin plus Gate.io without waiting for known unavailable endpoints.
Later sources remain independent fallbacks.

All endpoints are credential-free public ticker reads. Responses must match the
provider schema and contain a positive finite price and a finite change value
before that provider is counted. Failures record only provider name and
exception class. No exchange account, order, execution, risk, or authority
surface is added.

The upgrade script verifies the exact previous source hash, Paper and live
locks, all risk values, deterministic tests, and a live two-source preflight
before writing. Python imports the module at process start, so activation
requires one controlled Trader restart. The script does not restart Status,
nginx, shadow timers, or scorecard timers. It verifies a new diverse cycle and
rolls back the source if validation fails.

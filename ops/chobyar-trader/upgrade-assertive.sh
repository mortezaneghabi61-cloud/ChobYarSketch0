#!/usr/bin/env bash
set -Eeuo pipefail

APP_DIR="/opt/chobyar-trader"
ENV_FILE="$APP_DIR/.env"
TRADER="$APP_DIR/app/trader.py"
SERVICE="chobyar-trader.service"

if [[ $EUID -ne 0 ]]; then
  echo "Run as root." >&2
  exit 1
fi

mkdir -p "$APP_DIR/app" "$APP_DIR/state" "$APP_DIR/logs"
touch "$ENV_FILE"
chmod 600 "$ENV_FILE"

set_env() {
  local key="$1" value="$2"
  if grep -q "^${key}=" "$ENV_FILE"; then
    sed -i "s|^${key}=.*|${key}=${value}|" "$ENV_FILE"
  else
    printf '%s=%s\n' "$key" "$value" >> "$ENV_FILE"
  fi
}

# Assertive, but bounded. Hard-locked to paper trading.
set_env TRADING_MODE paper
set_env LIVE_TRADING_ENABLED false
set_env RISK_PROFILE assertive
set_env SYMBOL BTCUSDT
set_env LOOP_SECONDS 30
set_env MAX_POSITION_PCT 0.35
set_env STOP_LOSS_PCT 0.012
set_env TAKE_PROFIT_PCT 0.024
set_env MAX_DAILY_LOSS_PCT 0.04
set_env MAX_SPREAD_PCT 0.006
set_env SIGNAL_THRESHOLD 1.8
set_env SIMULATED_FEE_PCT 0.002

if [[ -f "$TRADER" ]]; then
  cp -a "$TRADER" "$TRADER.bak.$(date +%Y%m%d%H%M%S)"
fi

cat > "$TRADER" <<'PY'
from __future__ import annotations

import json
import math
import os
import statistics
import time
from dataclasses import asdict, dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

import httpx
from dotenv import load_dotenv

APP_DIR = Path('/opt/chobyar-trader')
ENV_FILE = APP_DIR / '.env'
STATE_FILE = APP_DIR / 'state' / 'paper_state.json'
AUDIT_FILE = APP_DIR / 'logs' / 'audit.jsonl'
load_dotenv(ENV_FILE)

WALLEX = 'https://api.wallex.ir'
SYMBOL = os.getenv('SYMBOL', 'BTCUSDT').strip().upper()
TRADING_MODE = os.getenv('TRADING_MODE', 'paper').strip().lower()
LIVE_ENABLED = os.getenv('LIVE_TRADING_ENABLED', 'false').strip().lower() == 'true'
LOOP_SECONDS = max(15, int(os.getenv('LOOP_SECONDS', '30')))
PAPER_START_USDT = float(os.getenv('PAPER_START_USDT', '10'))
MAX_POSITION_PCT = min(max(float(os.getenv('MAX_POSITION_PCT', '0.35')), 0.05), 0.40)
STOP_LOSS_PCT = min(max(float(os.getenv('STOP_LOSS_PCT', '0.012')), 0.004), 0.04)
TAKE_PROFIT_PCT = min(max(float(os.getenv('TAKE_PROFIT_PCT', '0.024')), 0.008), 0.08)
MAX_DAILY_LOSS_PCT = min(max(float(os.getenv('MAX_DAILY_LOSS_PCT', '0.04')), 0.01), 0.06)
MAX_SPREAD_PCT = min(max(float(os.getenv('MAX_SPREAD_PCT', '0.006')), 0.001), 0.02)
SIGNAL_THRESHOLD = min(max(float(os.getenv('SIGNAL_THRESHOLD', '1.8')), 1.2), 3.0)
SIM_FEE_PCT = min(max(float(os.getenv('SIMULATED_FEE_PCT', '0.002')), 0.0), 0.02)

# Safety invariant: this version contains no live order adapter.
if TRADING_MODE != 'paper' or LIVE_ENABLED:
    raise SystemExit('FAIL-CLOSED: paper-only build; live trading is disabled')

LOCAL = httpx.Client(base_url=WALLEX, timeout=12.0, headers={'User-Agent': 'ChobYar-Trader/2-paper'})
GLOBAL = httpx.Client(timeout=10.0, headers={'User-Agent': 'ChobYar-Trader/2-paper'})


def now() -> str:
    return datetime.now(timezone.utc).isoformat()


def audit(event: str, **fields: Any) -> None:
    AUDIT_FILE.parent.mkdir(parents=True, exist_ok=True)
    with AUDIT_FILE.open('a', encoding='utf-8') as f:
        f.write(json.dumps({'ts': now(), 'event': event, **fields}, ensure_ascii=False) + '\n')


def wallex_json(path: str, params: dict[str, Any] | None = None) -> dict[str, Any]:
    r = LOCAL.get(path, params=params)
    r.raise_for_status()
    data = r.json()
    if isinstance(data, dict) and data.get('success') is False:
        raise RuntimeError(f"Wallex error: {data.get('message')}")
    return data


@dataclass
class LocalSnapshot:
    best_bid: float
    best_ask: float
    mid: float
    spread_pct: float
    imbalance: float
    prices: list[float]
    buy_ratio: float


@dataclass
class GlobalSnapshot:
    price: float | None
    change_24h: float | None
    sources: list[str]


class IranMarketAgent:
    @staticmethod
    def snapshot() -> LocalSnapshot:
        depth = wallex_json('/v1/depth', {'symbol': SYMBOL})
        trades = wallex_json('/v1/trades', {'symbol': SYMBOL})
        result = depth.get('result', {}) or {}
        bids = result.get('bid') or result.get('bids') or []
        asks = result.get('ask') or result.get('asks') or []
        if not bids or not asks:
            raise RuntimeError('empty Wallex order book')

        def p(x: Any) -> float:
            return float(x.get('price', 0)) if isinstance(x, dict) else float(x[0])

        def q(x: Any) -> float:
            return float(x.get('quantity', x.get('qty', 0))) if isinstance(x, dict) else float(x[1])

        best_bid = max(p(x) for x in bids[:20])
        best_ask = min(p(x) for x in asks[:20])
        mid = (best_bid + best_ask) / 2.0
        spread = (best_ask - best_bid) / mid if mid else 1.0
        bq = sum(q(x) for x in bids[:10])
        aq = sum(q(x) for x in asks[:10])
        imbalance = (bq - aq) / (bq + aq) if (bq + aq) else 0.0

        latest = (trades.get('result', {}) or {}).get('latestTrades', [])
        prices, buys = [], []
        for t in latest[:80]:
            try:
                prices.append(float(t.get('price')))
                buys.append(bool(t.get('isBuyOrder')))
            except Exception:
                pass
        if len(prices) < 8:
            raise RuntimeError('not enough Wallex trades')
        return LocalSnapshot(best_bid, best_ask, mid, spread, imbalance, prices, sum(buys)/len(buys) if buys else 0.5)


class GlobalMarketAgent:
    @staticmethod
    def snapshot() -> GlobalSnapshot:
        prices: list[float] = []
        changes: list[float] = []
        sources: list[str] = []

        # Kraken public ticker: no account, no trading, market-data only.
        try:
            r = GLOBAL.get('https://api.kraken.com/0/public/Ticker', params={'pair': 'XBTUSDT'})
            r.raise_for_status()
            data = r.json()
            result = data.get('result', {}) or {}
            if result:
                ticker = next(iter(result.values()))
                last = float(ticker['c'][0])
                opened = float(ticker['o'])
                prices.append(last)
                if opened > 0:
                    changes.append((last - opened) / opened)
                sources.append('kraken')
        except Exception as exc:
            audit('global_source_error', source='kraken', error=str(exc))

        # CoinGecko public price as independent external confirmation.
        try:
            r = GLOBAL.get('https://api.coingecko.com/api/v3/simple/price', params={
                'ids': 'bitcoin', 'vs_currencies': 'usd', 'include_24hr_change': 'true'
            })
            r.raise_for_status()
            row = r.json().get('bitcoin', {})
            px = float(row['usd'])
            ch = float(row.get('usd_24h_change', 0.0)) / 100.0
            prices.append(px)
            changes.append(ch)
            sources.append('coingecko')
        except Exception as exc:
            audit('global_source_error', source='coingecko', error=str(exc))

        return GlobalSnapshot(
            statistics.fmean(prices) if prices else None,
            statistics.fmean(changes) if changes else None,
            sources,
        )


class MomentumAgent:
    @staticmethod
    def vote(s: LocalSnapshot) -> tuple[int, float, str]:
        n = max(4, len(s.prices)//2)
        recent = statistics.fmean(s.prices[:n])
        older = statistics.fmean(s.prices[n:]) if s.prices[n:] else recent
        d = (recent - older) / older if older else 0.0
        if d > 0.0010: return 1, 1.3, f'local momentum {d:+.3%}'
        if d < -0.0010: return -1, 1.3, f'local momentum {d:+.3%}'
        return 0, 1.3, f'local momentum neutral {d:+.3%}'


class OrderBookAgent:
    @staticmethod
    def vote(s: LocalSnapshot) -> tuple[int, float, str]:
        if s.imbalance > 0.12: return 1, 1.0, f'bid imbalance {s.imbalance:+.3f}'
        if s.imbalance < -0.12: return -1, 1.0, f'ask imbalance {s.imbalance:+.3f}'
        return 0, 1.0, f'book balanced {s.imbalance:+.3f}'


class TapeAgent:
    @staticmethod
    def vote(s: LocalSnapshot) -> tuple[int, float, str]:
        if s.buy_ratio > 0.57: return 1, 1.0, f'buy tape {s.buy_ratio:.2f}'
        if s.buy_ratio < 0.43: return -1, 1.0, f'sell tape {s.buy_ratio:.2f}'
        return 0, 1.0, f'tape neutral {s.buy_ratio:.2f}'


class GlobalTrendAgent:
    @staticmethod
    def vote(g: GlobalSnapshot) -> tuple[int, float, str]:
        if g.change_24h is None:
            return 0, 1.2, 'global unavailable'
        if g.change_24h > 0.0035: return 1, 1.2, f'global 24h {g.change_24h:+.2%}'
        if g.change_24h < -0.0035: return -1, 1.2, f'global 24h {g.change_24h:+.2%}'
        return 0, 1.2, f'global flat {g.change_24h:+.2%}'


class LocalGlobalGapAgent:
    @staticmethod
    def vote(local: LocalSnapshot, g: GlobalSnapshot) -> tuple[int, float, str]:
        if g.price is None or g.price <= 0:
            return 0, 0.7, 'global price unavailable'
        gap = (local.mid - g.price) / g.price
        if gap > 0.007: return -1, 0.7, f'local premium {gap:+.2%}'
        if gap < -0.007: return 1, 0.7, f'local discount {gap:+.2%}'
        return 0, 0.7, f'local/global gap {gap:+.2%}'


@dataclass
class PaperState:
    cash_usdt: float
    btc_qty: float
    entry_price: float | None
    day_start_equity: float
    day_key: str
    realized_pnl: float
    trades: int


class PaperBroker:
    def __init__(self) -> None:
        self.state = self._load()

    def _load(self) -> PaperState:
        today = datetime.now(timezone.utc).date().isoformat()
        if STATE_FILE.exists():
            try:
                raw = json.loads(STATE_FILE.read_text(encoding='utf-8'))
                state = PaperState(**raw)
                if state.day_key != today:
                    eq = self.equity(state.entry_price or 0.0, state)
                    state.day_key = today
                    state.day_start_equity = max(eq, state.cash_usdt)
                    self._save(state)
                return state
            except Exception as exc:
                audit('state_recover', error=str(exc))
        state = PaperState(PAPER_START_USDT, 0.0, None, PAPER_START_USDT, today, 0.0, 0)
        self._save(state)
        return state

    def _save(self, state: PaperState | None = None) -> None:
        if state is not None: self.state = state
        tmp = STATE_FILE.with_suffix('.tmp')
        tmp.write_text(json.dumps(asdict(self.state), indent=2), encoding='utf-8')
        tmp.replace(STATE_FILE)

    def equity(self, price: float, state: PaperState | None = None) -> float:
        s = state or self.state
        return s.cash_usdt + s.btc_qty * max(price, 0.0)

    def daily_loss_hit(self, price: float) -> bool:
        start = self.state.day_start_equity
        return start <= 0 or (start - self.equity(price))/start >= MAX_DAILY_LOSS_PCT

    def buy(self, price: float, reason: str) -> None:
        if self.state.btc_qty > 0 or self.state.cash_usdt <= 0: return
        notional = self.state.cash_usdt * MAX_POSITION_PCT
        fee = notional * SIM_FEE_PCT
        spend = min(self.state.cash_usdt, notional + fee)
        qty = max(0.0, spend - fee) / price
        self.state.cash_usdt -= spend
        self.state.btc_qty = qty
        self.state.entry_price = price
        self.state.trades += 1
        self._save()
        audit('paper_buy', price=price, qty=qty, reason=reason, equity=self.equity(price))

    def sell_all(self, price: float, reason: str) -> None:
        if self.state.btc_qty <= 0: return
        qty = self.state.btc_qty
        gross = qty * price
        fee = gross * SIM_FEE_PCT
        entry = self.state.entry_price or price
        pnl = gross - fee - qty * entry
        self.state.cash_usdt += gross - fee
        self.state.btc_qty = 0.0
        self.state.entry_price = None
        self.state.realized_pnl += pnl
        self.state.trades += 1
        self._save()
        audit('paper_sell', price=price, qty=qty, pnl=pnl, reason=reason, equity=self.equity(price))


class RiskManager:
    @staticmethod
    def decide(signal: str, local: LocalSnapshot, broker: PaperBroker) -> tuple[str, str]:
        if not math.isfinite(local.mid) or local.mid <= 0:
            return 'WAIT', 'invalid price'
        if local.spread_pct > MAX_SPREAD_PCT:
            return 'WAIT', f'spread {local.spread_pct:.3%} too wide'
        if broker.daily_loss_hit(local.mid):
            return 'WAIT', 'daily loss hard-stop'
        st = broker.state
        if st.btc_qty > 0 and st.entry_price:
            change = (local.mid - st.entry_price)/st.entry_price
            if change <= -STOP_LOSS_PCT: return 'SELL', f'stop-loss {change:.2%}'
            if change >= TAKE_PROFIT_PCT: return 'SELL', f'take-profit {change:.2%}'
        if signal == 'BUY' and st.btc_qty == 0: return 'BUY', 'assertive consensus approved'
        if signal == 'SELL' and st.btc_qty > 0: return 'SELL', 'assertive consensus exit'
        return 'WAIT', 'state/risk gate'


def strategy(local: LocalSnapshot, global_: GlobalSnapshot) -> tuple[str, float, list[dict[str, Any]]]:
    raw = [
        ('momentum', MomentumAgent.vote(local)),
        ('orderbook', OrderBookAgent.vote(local)),
        ('tape', TapeAgent.vote(local)),
        ('global_trend', GlobalTrendAgent.vote(global_)),
        ('local_global_gap', LocalGlobalGapAgent.vote(local, global_)),
    ]
    score = 0.0
    votes = []
    for name, (vote, weight, reason) in raw:
        score += vote * weight
        votes.append({'agent': name, 'vote': vote, 'weight': weight, 'reason': reason})
    threshold = SIGNAL_THRESHOLD if global_.sources else SIGNAL_THRESHOLD + 0.7
    signal = 'BUY' if score >= threshold else 'SELL' if score <= -threshold else 'WAIT'
    return signal, score, votes


def run_once(broker: PaperBroker) -> None:
    local = IranMarketAgent.snapshot()
    global_ = GlobalMarketAgent.snapshot()
    signal, score, votes = strategy(local, global_)
    action, reason = RiskManager.decide(signal, local, broker)
    if action == 'BUY': broker.buy(local.best_ask, reason)
    elif action == 'SELL': broker.sell_all(local.best_bid, reason)
    audit('cycle', symbol=SYMBOL, local_mid=local.mid, global_price=global_.price,
          global_sources=global_.sources, global_change_24h=global_.change_24h,
          signal=signal, score=score, action=action, reason=reason, votes=votes,
          cash_usdt=broker.state.cash_usdt, btc_qty=broker.state.btc_qty,
          equity=broker.equity(local.mid), realized_pnl=broker.state.realized_pnl,
          trades=broker.state.trades)
    print(f"{now()} local={local.mid:.2f} global={global_.price or 0:.2f} sources={','.join(global_.sources) or 'none'} score={score:+.2f} signal={signal} action={action} equity={broker.equity(local.mid):.4f}", flush=True)


def main() -> None:
    audit('startup', mode='paper', profile='assertive', symbol=SYMBOL,
          max_position_pct=MAX_POSITION_PCT, stop_loss_pct=STOP_LOSS_PCT,
          take_profit_pct=TAKE_PROFIT_PCT, daily_loss_pct=MAX_DAILY_LOSS_PCT)
    broker = PaperBroker()
    while True:
        try:
            run_once(broker)
        except Exception as exc:
            audit('cycle_error', error=repr(exc))
            print(f"{now()} ERROR {exc!r}", flush=True)
        time.sleep(LOOP_SECONDS)


if __name__ == '__main__':
    main()
PY

chmod 700 "$TRADER"

if systemctl list-unit-files | grep -q '^chobyar-trader.service'; then
  systemctl restart "$SERVICE"
  sleep 2
  systemctl --no-pager --full status "$SERVICE" | sed -n '1,18p'
else
  echo "Trader code updated. Service not found yet; finish bootstrap first."
fi

echo
printf 'PROFILE=assertive\nMODE=paper\nLOCAL=Wallex\nGLOBAL=Kraken+CoinGecko\n'
echo 'LIVE TRADING REMAINS HARD-DISABLED.'

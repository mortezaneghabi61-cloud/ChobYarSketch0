#!/usr/bin/env bash
set -Eeuo pipefail

APP_DIR="/opt/chobyar-trader"
VENV="$APP_DIR/.venv"
ENV_FILE="$APP_DIR/.env"
SERVICE="/etc/systemd/system/chobyar-trader.service"

log() { printf '\n[chobyar-bootstrap] %s\n' "$*"; }

if [[ $EUID -ne 0 ]]; then
  echo "Run as root." >&2
  exit 1
fi

log "Installing base packages"
apt-get update -y
DEBIAN_FRONTEND=noninteractive apt-get install -y python3 python3-venv python3-pip curl ca-certificates

log "Preparing directories"
mkdir -p "$APP_DIR/app" "$APP_DIR/state" "$APP_DIR/logs"
chmod 700 "$APP_DIR"

if [[ ! -d "$VENV" ]]; then
  python3 -m venv "$VENV"
fi

"$VENV/bin/pip" install --upgrade pip >/dev/null
"$VENV/bin/pip" install "httpx>=0.27,<1" "python-dotenv>=1,<2" >/dev/null

if [[ ! -f "$ENV_FILE" ]]; then
  touch "$ENV_FILE"
fi
chmod 600 "$ENV_FILE"

ensure_env() {
  local key="$1" value="$2"
  if ! grep -q "^${key}=" "$ENV_FILE"; then
    printf '%s=%s\n' "$key" "$value" >> "$ENV_FILE"
  fi
}

# Fail-closed defaults. Existing values are preserved except live trading is forced off below.
ensure_env TRADING_MODE paper
ensure_env LIVE_TRADING_ENABLED false
ensure_env SYMBOL BTCUSDT
ensure_env PAPER_START_USDT 10
ensure_env LOOP_SECONDS 60
ensure_env MAX_POSITION_PCT 0.25
ensure_env STOP_LOSS_PCT 0.015
ensure_env TAKE_PROFIT_PCT 0.03
ensure_env MAX_DAILY_LOSS_PCT 0.03
ensure_env SIMULATED_FEE_PCT 0.002

# Hard lock: this bootstrap intentionally supports PAPER only.
sed -i 's/^TRADING_MODE=.*/TRADING_MODE=paper/' "$ENV_FILE"
sed -i 's/^LIVE_TRADING_ENABLED=.*/LIVE_TRADING_ENABLED=false/' "$ENV_FILE"

cat > "$APP_DIR/app/trader.py" <<'PY'
from __future__ import annotations

import json
import math
import os
import statistics
import time
from dataclasses import dataclass, asdict
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

import httpx
from dotenv import load_dotenv

APP_DIR = Path("/opt/chobyar-trader")
ENV_FILE = APP_DIR / ".env"
STATE_FILE = APP_DIR / "state" / "paper_state.json"
AUDIT_FILE = APP_DIR / "logs" / "audit.jsonl"

load_dotenv(ENV_FILE)

BASE_URL = "https://api.wallex.ir"
SYMBOL = os.getenv("SYMBOL", "BTCUSDT").strip().upper()
TRADING_MODE = os.getenv("TRADING_MODE", "paper").strip().lower()
LIVE_ENABLED = os.getenv("LIVE_TRADING_ENABLED", "false").strip().lower() == "true"
LOOP_SECONDS = max(30, int(os.getenv("LOOP_SECONDS", "60")))
PAPER_START_USDT = float(os.getenv("PAPER_START_USDT", "10"))
MAX_POSITION_PCT = min(max(float(os.getenv("MAX_POSITION_PCT", "0.25")), 0.01), 0.50)
STOP_LOSS_PCT = min(max(float(os.getenv("STOP_LOSS_PCT", "0.015")), 0.002), 0.10)
TAKE_PROFIT_PCT = min(max(float(os.getenv("TAKE_PROFIT_PCT", "0.03")), 0.004), 0.20)
MAX_DAILY_LOSS_PCT = min(max(float(os.getenv("MAX_DAILY_LOSS_PCT", "0.03")), 0.005), 0.20)
SIM_FEE_PCT = min(max(float(os.getenv("SIMULATED_FEE_PCT", "0.002")), 0.0), 0.02)

# Hard safety invariant: there is deliberately no live order adapter in this version.
if TRADING_MODE != "paper" or LIVE_ENABLED:
    raise SystemExit("FAIL-CLOSED: this build only permits TRADING_MODE=paper and LIVE_TRADING_ENABLED=false")

CLIENT = httpx.Client(
    base_url=BASE_URL,
    timeout=httpx.Timeout(15.0),
    headers={"User-Agent": "ChobYar-Trader-Paper/1.0"},
)


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat()


def audit(event: str, **fields: Any) -> None:
    AUDIT_FILE.parent.mkdir(parents=True, exist_ok=True)
    record = {"ts": utc_now(), "event": event, **fields}
    with AUDIT_FILE.open("a", encoding="utf-8") as f:
        f.write(json.dumps(record, ensure_ascii=False) + "\n")


def get_json(path: str, params: dict[str, Any] | None = None) -> dict[str, Any]:
    r = CLIENT.get(path, params=params)
    r.raise_for_status()
    data = r.json()
    if isinstance(data, dict) and data.get("success") is False:
        raise RuntimeError(f"Wallex API error: {data.get('message')}")
    return data


@dataclass
class Snapshot:
    symbol: str
    best_bid: float
    best_ask: float
    mid: float
    spread_pct: float
    bid_qty: float
    ask_qty: float
    orderbook_imbalance: float
    trade_prices: list[float]
    buy_trade_ratio: float


class MarketAgent:
    """Fetches only public market data; never submits orders."""

    @staticmethod
    def snapshot() -> Snapshot:
        depth = get_json("/v1/depth", {"symbol": SYMBOL})
        trades = get_json("/v1/trades", {"symbol": SYMBOL})

        result = depth.get("result", {})
        bids = result.get("bid") or result.get("bids") or []
        asks = result.get("ask") or result.get("asks") or []
        if not bids or not asks:
            raise RuntimeError("Empty order book")

        def price_of(x: Any) -> float:
            if isinstance(x, dict):
                return float(x.get("price", 0))
            return float(x[0])

        def qty_of(x: Any) -> float:
            if isinstance(x, dict):
                return float(x.get("quantity", x.get("qty", 0)))
            return float(x[1])

        best_bid = max(price_of(x) for x in bids[:20])
        best_ask = min(price_of(x) for x in asks[:20])
        mid = (best_bid + best_ask) / 2.0
        spread_pct = (best_ask - best_bid) / mid if mid else 1.0

        bid_qty = sum(qty_of(x) for x in bids[:10])
        ask_qty = sum(qty_of(x) for x in asks[:10])
        denom = bid_qty + ask_qty
        imbalance = (bid_qty - ask_qty) / denom if denom else 0.0

        latest = (trades.get("result", {}) or {}).get("latestTrades", [])
        prices = []
        buy_flags = []
        for t in latest[:60]:
            try:
                prices.append(float(t.get("price")))
                buy_flags.append(bool(t.get("isBuyOrder")))
            except Exception:
                continue
        if len(prices) < 6:
            raise RuntimeError("Not enough recent trades")
        buy_ratio = sum(buy_flags) / len(buy_flags) if buy_flags else 0.5

        return Snapshot(
            symbol=SYMBOL,
            best_bid=best_bid,
            best_ask=best_ask,
            mid=mid,
            spread_pct=spread_pct,
            bid_qty=bid_qty,
            ask_qty=ask_qty,
            orderbook_imbalance=imbalance,
            trade_prices=prices,
            buy_trade_ratio=buy_ratio,
        )


class MomentumAgent:
    @staticmethod
    def vote(s: Snapshot) -> tuple[int, str]:
        p = s.trade_prices
        half = max(3, len(p) // 2)
        recent = statistics.fmean(p[:half])
        older = statistics.fmean(p[half:]) if len(p[half:]) else recent
        delta = (recent - older) / older if older else 0.0
        if delta > 0.0015:
            return 1, f"recent momentum +{delta:.4%}"
        if delta < -0.0015:
            return -1, f"recent momentum {delta:.4%}"
        return 0, f"momentum neutral {delta:.4%}"


class OrderBookAgent:
    @staticmethod
    def vote(s: Snapshot) -> tuple[int, str]:
        if s.orderbook_imbalance > 0.15:
            return 1, f"bid imbalance {s.orderbook_imbalance:.3f}"
        if s.orderbook_imbalance < -0.15:
            return -1, f"ask imbalance {s.orderbook_imbalance:.3f}"
        return 0, f"orderbook balanced {s.orderbook_imbalance:.3f}"


class TapeAgent:
    @staticmethod
    def vote(s: Snapshot) -> tuple[int, str]:
        if s.buy_trade_ratio > 0.60:
            return 1, f"buy-tape ratio {s.buy_trade_ratio:.2f}"
        if s.buy_trade_ratio < 0.40:
            return -1, f"sell-tape ratio {s.buy_trade_ratio:.2f}"
        return 0, f"tape neutral {s.buy_trade_ratio:.2f}"


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
                raw = json.loads(STATE_FILE.read_text(encoding="utf-8"))
                state = PaperState(**raw)
                if state.day_key != today:
                    eq = self.equity(last_price=state.entry_price or 0.0, state=state)
                    state.day_key = today
                    state.day_start_equity = max(eq, state.cash_usdt)
                    self._save(state)
                return state
            except Exception as exc:
                audit("state_recover", error=str(exc))
        state = PaperState(
            cash_usdt=PAPER_START_USDT,
            btc_qty=0.0,
            entry_price=None,
            day_start_equity=PAPER_START_USDT,
            day_key=today,
            realized_pnl=0.0,
            trades=0,
        )
        self._save(state)
        return state

    def _save(self, state: PaperState | None = None) -> None:
        if state is not None:
            self.state = state
        STATE_FILE.parent.mkdir(parents=True, exist_ok=True)
        tmp = STATE_FILE.with_suffix(".tmp")
        tmp.write_text(json.dumps(asdict(self.state), indent=2), encoding="utf-8")
        tmp.replace(STATE_FILE)

    def equity(self, last_price: float, state: PaperState | None = None) -> float:
        s = state or self.state
        return s.cash_usdt + s.btc_qty * max(last_price, 0.0)

    def daily_loss_limit_hit(self, price: float) -> bool:
        if self.state.day_start_equity <= 0:
            return True
        eq = self.equity(price)
        drawdown = (self.state.day_start_equity - eq) / self.state.day_start_equity
        return drawdown >= MAX_DAILY_LOSS_PCT

    def buy(self, price: float, reason: str) -> None:
        if self.state.btc_qty > 0 or self.state.cash_usdt <= 0:
            return
        notional = self.state.cash_usdt * MAX_POSITION_PCT
        if notional <= 0:
            return
        fee = notional * SIM_FEE_PCT
        spend = min(self.state.cash_usdt, notional + fee)
        effective_notional = max(0.0, spend - fee)
        qty = effective_notional / price
        self.state.cash_usdt -= spend
        self.state.btc_qty += qty
        self.state.entry_price = price
        self.state.trades += 1
        self._save()
        audit("paper_buy", price=price, qty=qty, fee=fee, reason=reason, equity=self.equity(price))

    def sell_all(self, price: float, reason: str) -> None:
        if self.state.btc_qty <= 0:
            return
        qty = self.state.btc_qty
        gross = qty * price
        fee = gross * SIM_FEE_PCT
        net = gross - fee
        entry = self.state.entry_price or price
        pnl = net - qty * entry
        self.state.cash_usdt += net
        self.state.btc_qty = 0.0
        self.state.entry_price = None
        self.state.realized_pnl += pnl
        self.state.trades += 1
        self._save()
        audit("paper_sell", price=price, qty=qty, fee=fee, pnl=pnl, reason=reason, equity=self.equity(price))


class RiskManager:
    @staticmethod
    def decide(signal: str, s: Snapshot, broker: PaperBroker) -> tuple[str, str]:
        if not math.isfinite(s.mid) or s.mid <= 0:
            return "WAIT", "invalid price"
        if s.spread_pct > 0.01:
            return "WAIT", f"spread too wide {s.spread_pct:.3%}"
        if broker.daily_loss_limit_hit(s.mid):
            return "WAIT", "daily loss limit reached"

        st = broker.state
        if st.btc_qty > 0 and st.entry_price:
            change = (s.mid - st.entry_price) / st.entry_price
            if change <= -STOP_LOSS_PCT:
                return "SELL", f"stop-loss {change:.3%}"
            if change >= TAKE_PROFIT_PCT:
                return "SELL", f"take-profit {change:.3%}"

        if signal == "BUY" and st.btc_qty == 0:
            return "BUY", "consensus buy approved"
        if signal == "SELL" and st.btc_qty > 0:
            return "SELL", "consensus sell approved"
        return "WAIT", "risk/state gate"


def strategy_vote(s: Snapshot) -> tuple[str, list[dict[str, Any]]]:
    votes = []
    score = 0
    for name, agent in (
        ("momentum", MomentumAgent),
        ("orderbook", OrderBookAgent),
        ("tape", TapeAgent),
    ):
        vote, reason = agent.vote(s)
        score += vote
        votes.append({"agent": name, "vote": vote, "reason": reason})
    if score >= 2:
        signal = "BUY"
    elif score <= -2:
        signal = "SELL"
    else:
        signal = "WAIT"
    return signal, votes


def run_once(broker: PaperBroker) -> None:
    s = MarketAgent.snapshot()
    signal, votes = strategy_vote(s)
    action, risk_reason = RiskManager.decide(signal, s, broker)

    if action == "BUY":
        broker.buy(s.best_ask, risk_reason)
    elif action == "SELL":
        broker.sell_all(s.best_bid, risk_reason)

    audit(
        "cycle",
        symbol=SYMBOL,
        mid=s.mid,
        spread_pct=s.spread_pct,
        signal=signal,
        action=action,
        risk_reason=risk_reason,
        votes=votes,
        cash_usdt=broker.state.cash_usdt,
        btc_qty=broker.state.btc_qty,
        equity=broker.equity(s.mid),
        realized_pnl=broker.state.realized_pnl,
        trades=broker.state.trades,
    )
    print(
        f"{utc_now()} {SYMBOL} mid={s.mid:.4f} signal={signal} action={action} "
        f"equity={broker.equity(s.mid):.4f} USDT trades={broker.state.trades}",
        flush=True,
    )


def main() -> None:
    audit(
        "startup",
        mode=TRADING_MODE,
        live_enabled=LIVE_ENABLED,
        symbol=SYMBOL,
        loop_seconds=LOOP_SECONDS,
    )
    broker = PaperBroker()
    while True:
        started = time.time()
        try:
            run_once(broker)
        except KeyboardInterrupt:
            raise
        except Exception as exc:
            audit("cycle_error", error=repr(exc))
            print(f"{utc_now()} ERROR {exc}", flush=True)
        elapsed = time.time() - started
        time.sleep(max(5.0, LOOP_SECONDS - elapsed))


if __name__ == "__main__":
    main()
PY

cat > "$SERVICE" <<'UNIT'
[Unit]
Description=ChobYar Trader Paper Engine
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
WorkingDirectory=/opt/chobyar-trader
EnvironmentFile=/opt/chobyar-trader/.env
ExecStart=/opt/chobyar-trader/.venv/bin/python /opt/chobyar-trader/app/trader.py
Restart=on-failure
RestartSec=10
User=root
NoNewPrivileges=true
PrivateTmp=true
ProtectSystem=full
ProtectHome=true
ReadWritePaths=/opt/chobyar-trader

[Install]
WantedBy=multi-user.target
UNIT

log "Validating fail-closed configuration"
grep -q '^TRADING_MODE=paper$' "$ENV_FILE"
grep -q '^LIVE_TRADING_ENABLED=false$' "$ENV_FILE"
"$VENV/bin/python" -m py_compile "$APP_DIR/app/trader.py"

log "Starting service"
systemctl daemon-reload
systemctl enable --now chobyar-trader.service
sleep 3

log "Service status"
systemctl --no-pager --full status chobyar-trader.service || true

echo
echo "READY: ChobYar-Trader is installed in PAPER mode."
echo "Logs: journalctl -u chobyar-trader -f"
echo "Audit: /opt/chobyar-trader/logs/audit.jsonl"
echo "State: /opt/chobyar-trader/state/paper_state.json"
echo "LIVE TRADING IS NOT IMPLEMENTED IN THIS BUILD."

from __future__ import annotations

import math
import os
import statistics
import time
from dataclasses import asdict, dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from common import SecretFreeAudit, atomic_json, finite, utc_now

APP_DIR = Path(os.getenv("CHOBYAR_APP_DIR", "/opt/chobyar-trader"))
try:
    from dotenv import load_dotenv
    load_dotenv(APP_DIR / ".env")
except ImportError:
    pass
STATE_FILE = APP_DIR / "state" / "paper_state.json"
FORWARD_FILE = APP_DIR / "state" / "forward_test.json"
AUDIT = SecretFreeAudit(APP_DIR / "logs" / "audit.jsonl")

MODE = os.getenv("TRADING_MODE", "").strip().lower()
LIVE = os.getenv("LIVE_TRADING_ENABLED", "").strip().lower()
if MODE != "paper" or LIVE != "false":
    raise SystemExit("FAIL-CLOSED: v4 requires TRADING_MODE=paper and LIVE_TRADING_ENABLED=false")

import httpx

SYMBOL = os.getenv("SYMBOL", "BTCUSDT").strip().upper()
START = finite(os.getenv("PAPER_START_USDT", "10"), 10.0)
LOOP_SECONDS = max(15, int(os.getenv("LOOP_SECONDS", "30")))
MAX_POSITION_PCT = finite(os.getenv("MAX_POSITION_PCT", "0.25"), 0.25)
STOP_LOSS_PCT = finite(os.getenv("STOP_LOSS_PCT", "0.015"), 0.015)
TAKE_PROFIT_PCT = finite(os.getenv("TAKE_PROFIT_PCT", "0.03"), 0.03)
MAX_DAILY_LOSS_PCT = finite(os.getenv("MAX_DAILY_LOSS_PCT", "0.03"), 0.03)
FEE_PCT = finite(os.getenv("SIMULATED_FEE_PCT", "0.002"), 0.002)
MAX_SPREAD_PCT = finite(os.getenv("MAX_SPREAD_PCT", "0.006"), 0.006)
ENTRY_THRESHOLD = finite(os.getenv("ENTRY_SCORE_THRESHOLD", "2.4"), 2.4)
EXIT_THRESHOLD = finite(os.getenv("EXIT_SCORE_THRESHOLD", "-2.0"), -2.0)
MIN_QUORUM = max(5, int(os.getenv("MIN_AGENT_QUORUM", "6")))
MAX_TAPE_AGE_SECONDS = 300.0
MAX_TAPE_FUTURE_SKEW_SECONDS = 5.0

EXPECTED_RISK = (0.25, 0.015, 0.03, 0.03)
if (MAX_POSITION_PCT, STOP_LOSS_PCT, TAKE_PROFIT_PCT, MAX_DAILY_LOSS_PCT) != EXPECTED_RISK:
    raise SystemExit("FAIL-CLOSED: risk values differ from approved 0.25/0.015/0.03/0.03")
if not (0 <= FEE_PCT <= 0.02):
    raise SystemExit("FAIL-CLOSED: invalid simulated fee")

LOCAL = httpx.Client(base_url="https://api.wallex.ir", timeout=12.0, headers={"User-Agent": "ChobYar-Trader/4-paper"})
GLOBAL = httpx.Client(timeout=10.0, headers={"User-Agent": "ChobYar-Trader/4-paper"})


@dataclass(frozen=True)
class Market:
    best_bid: float
    best_ask: float
    mid: float
    spread_pct: float
    imbalance: float
    prices: list[float]
    buy_ratio: float
    global_price: float | None
    global_change: float | None
    global_sources: list[str]
    global_dispersion_pct: float | None
    tape_age_seconds: float | None = None


@dataclass
class State:
    cash_usdt: float
    btc_qty: float
    entry_price: float | None
    entry_fee: float
    starting_equity: float
    day_start_equity: float
    day_key: str
    realized_pnl: float
    fees_paid: float
    orders: int
    closed_trades: int
    wins: int
    losses: int
    gross_profit: float
    gross_loss: float
    peak_equity: float
    max_drawdown_pct: float


def _json(response: httpx.Response) -> dict[str, Any]:
    response.raise_for_status()
    data = response.json()
    if not isinstance(data, dict) or data.get("success") is False:
        raise RuntimeError("public market-data response rejected")
    return data


def parse_recent_tape(trades: dict[str, Any], now: float) -> tuple[list[float], float, float | None]:
    """Use only recent, timestamped same-market trades; never invent a tape."""
    try:
        rows = trades.get("latestTrades")
        if not isinstance(rows, list) or not rows or not math.isfinite(now):
            return [], 0.5, None
        parsed = []
        for row in rows[:100]:
            if not isinstance(row, dict) or row.get("symbol") != SYMBOL or not isinstance(row.get("isBuyOrder"), bool):
                return [], 0.5, None
            stamp = datetime.fromisoformat(row["timestamp"].replace("Z", "+00:00"))
            if stamp.tzinfo is None or stamp.utcoffset() is None or isinstance(row["price"], bool):
                return [], 0.5, None
            epoch, px = stamp.timestamp(), float(row["price"])
            if not math.isfinite(px) or px <= 0 or epoch - now > MAX_TAPE_FUTURE_SKEW_SECONDS:
                return [], 0.5, None
            parsed.append((epoch, px, row["isBuyOrder"]))
        parsed.sort(key=lambda row: row[0], reverse=True)
        age = now - parsed[0][0]
        recent = [row for row in parsed if now - row[0] <= MAX_TAPE_AGE_SECONDS]
        prices = [row[1] for row in recent]
        ratio = sum(row[2] for row in recent) / len(recent) if recent else 0.5
        return prices, ratio, age
    except (KeyError, TypeError, ValueError, AttributeError, OverflowError):
        return [], 0.5, None


def tape_current(m: Market) -> bool:
    age = m.tape_age_seconds
    return (age is not None and math.isfinite(age)
            and -MAX_TAPE_FUTURE_SKEW_SECONDS <= age <= MAX_TAPE_AGE_SECONDS
            and len(m.prices) >= 10)


def local_snapshot() -> tuple[float, float, float, float, list[float], float, float | None]:
    depth = _json(LOCAL.get("/v1/depth", params={"symbol": SYMBOL})).get("result", {}) or {}
    trades = _json(LOCAL.get("/v1/trades", params={"symbol": SYMBOL})).get("result", {}) or {}
    bids, asks = depth.get("bid") or depth.get("bids") or [], depth.get("ask") or depth.get("asks") or []
    if not bids or not asks:
        raise RuntimeError("Wallex order book unavailable")
    price = lambda x: finite(x.get("price") if isinstance(x, dict) else x[0])
    qty = lambda x: finite(x.get("quantity", x.get("qty")) if isinstance(x, dict) else x[1])
    best_bid, best_ask = max(price(x) for x in bids[:20]), min(price(x) for x in asks[:20])
    mid = (best_bid + best_ask) / 2
    if best_bid <= 0 or best_ask <= best_bid or not math.isfinite(mid):
        raise RuntimeError("invalid Wallex market")
    bq, aq = sum(qty(x) for x in bids[:10]), sum(qty(x) for x in asks[:10])
    imbalance = (bq - aq) / (bq + aq) if bq + aq else 0.0
    prices, buy_ratio, tape_age = parse_recent_tape(trades, time.time())
    return best_bid, best_ask, imbalance, (best_ask - best_bid) / mid, prices, buy_ratio, tape_age


def global_snapshot() -> tuple[float | None, float | None, list[str], float | None]:
    prices, changes, sources = [], [], []
    endpoints = (
        ("kraken", "https://api.kraken.com/0/public/Ticker", {"pair": "XBTUSDT"}),
        ("coinbase", "https://api.exchange.coinbase.com/products/BTC-USDT/ticker", None),
    )
    for name, url, params in endpoints:
        try:
            data = GLOBAL.get(url, params=params).json()
            if name == "kraken":
                row = next(iter((data.get("result") or {}).values()))
                px, opened = float(row["c"][0]), float(row["o"])
                change = (px - opened) / opened
            else:
                px, change = float(data["price"]), None
            if px > 0 and math.isfinite(px):
                prices.append(px); sources.append(name)
                if change is not None: changes.append(change)
        except Exception as exc:
            AUDIT.write("market_source_error", source=name, error=type(exc).__name__)
    if not prices:
        return None, None, [], None
    mean = statistics.fmean(prices)
    dispersion = (max(prices) - min(prices)) / mean if len(prices) > 1 else 0.0
    return mean, statistics.fmean(changes) if changes else None, sources, dispersion


def snapshot() -> Market:
    gp, gc, sources, dispersion = global_snapshot()
    bid, ask, imbalance, spread, prices, buy_ratio, tape_age = local_snapshot()
    return Market(bid, ask, (bid + ask) / 2, spread, imbalance, prices, buy_ratio, gp, gc, sources, dispersion, tape_age)


def vote(name: str, direction: int, weight: float, reason: str, available: bool = True) -> dict[str, Any]:
    return {"agent": name, "vote": direction, "weight": weight, "contribution": direction * weight, "reason": reason, "available": available}


def agent_votes(m: Market) -> list[dict[str, Any]]:
    tape_ok = tape_current(m)
    half = len(m.prices) // 2
    delta = ((statistics.fmean(m.prices[:half]) - statistics.fmean(m.prices[half:])) / statistics.fmean(m.prices[half:])) if tape_ok else 0.0
    momentum = 1 if delta > .001 else -1 if delta < -.001 else 0
    book = 1 if m.imbalance > .12 else -1 if m.imbalance < -.12 else 0
    tape = (1 if m.buy_ratio > .57 else -1 if m.buy_ratio < .43 else 0) if tape_ok else 0
    trend = 0 if m.global_change is None else 1 if m.global_change > .0035 else -1 if m.global_change < -.0035 else 0
    gap = None if not m.global_price else (m.mid - m.global_price) / m.global_price
    gap_vote = 0 if gap is None else -1 if gap > .007 else 1 if gap < -.007 else 0
    global_ok = m.global_price is not None and len(m.global_sources) >= 1 and (m.global_dispersion_pct or 0) <= .02
    return [
        vote("iran_wallex_market", 0, .4, "local public market valid"),
        vote("momentum", momentum, 1.3, f"momentum={delta:+.4%}" if tape_ok else "recent tape unavailable", tape_ok),
        vote("order_book", book, 1.0, f"imbalance={m.imbalance:+.3f}"),
        vote("tape_order_flow", tape, 1.0, f"buy_ratio={m.buy_ratio:.3f}" if tape_ok else "recent tape unavailable", tape_ok),
        vote("global_market", 0, .5, f"sources={','.join(m.global_sources) or 'none'}", global_ok),
        vote("global_trend", trend, 1.2, "global trend" if m.global_change is not None else "unavailable", m.global_change is not None),
        vote("iran_global_gap", gap_vote, .7, "gap unavailable" if gap is None else f"gap={gap:+.3%}", gap is not None),
    ]


def utc_day_key() -> str:
    return datetime.now(timezone.utc).date().isoformat()


class Broker:
    def __init__(self) -> None:
        today = utc_day_key()
        try:
            raw = __import__("json").loads(STATE_FILE.read_text(encoding="utf-8"))
            defaults = asdict(State(START, 0, None, 0, START, START, today, 0, 0, 0, 0, 0, 0, 0, 0, START, 0))
            self.state = State(**{**defaults, **raw})
        except Exception:
            self.state = State(START, 0, None, 0, START, START, today, 0, 0, 0, 0, 0, 0, 0, 0, START, 0)
        if self.state.day_key != today:
            self.state.day_key, self.state.day_start_equity = today, self.equity(self.state.entry_price or 0)
        self.started = time.time()
        try:
            forward = __import__("json").loads(FORWARD_FILE.read_text(encoding="utf-8"))
            self.forward_start = str(forward["start_timestamp"])
            self.cycles = int(forward.get("cycles", 0))
            started_dt = datetime.fromisoformat(self.forward_start.replace("Z", "+00:00"))
            self.forward_started_epoch = started_dt.timestamp()
        except Exception:
            self.forward_start = utc_now()
            self.forward_started_epoch = self.started
            self.cycles = 0
        self.save(self.state.entry_price or 0)

    def equity(self, px: float) -> float:
        return self.state.cash_usdt + self.state.btc_qty * max(px, 0)

    def roll_day_if_needed(self, px: float) -> bool:
        today = utc_day_key()
        if self.state.day_key == today:
            return False
        if not math.isfinite(px) or px <= 0:
            raise RuntimeError("invalid market price for daily risk rollover")
        equity = self.equity(px)
        if not math.isfinite(equity) or equity <= 0:
            raise RuntimeError("invalid equity for daily risk rollover")
        previous_key = self.state.day_key
        previous_equity = self.state.day_start_equity
        self.state.day_key = today
        self.state.day_start_equity = equity
        try:
            atomic_json(STATE_FILE, self.state)
        except Exception:
            self.state.day_key = previous_key
            self.state.day_start_equity = previous_equity
            raise
        AUDIT.write("paper_day_rollover", day_key=today, day_start_equity=equity)
        return True

    def save(self, px: float) -> None:
        equity = self.equity(px)
        self.state.peak_equity = max(self.state.peak_equity, equity)
        dd = (self.state.peak_equity - equity) / self.state.peak_equity if self.state.peak_equity > 0 else 0
        self.state.max_drawdown_pct = max(self.state.max_drawdown_pct, dd)
        atomic_json(STATE_FILE, self.state)
        atomic_json(FORWARD_FILE, {
            "start_timestamp": self.forward_start,
            "starting_equity": self.state.starting_equity, "current_equity": equity,
            "peak_equity": self.state.peak_equity, "current_drawdown_pct": dd,
            "max_drawdown_pct": self.state.max_drawdown_pct, "closed_trades": self.state.closed_trades,
            "wins": self.state.wins, "losses": self.state.losses, "realized_pnl": self.state.realized_pnl,
            "fees_paid": self.state.fees_paid, "uptime_seconds": max(0, int(time.time() - self.forward_started_epoch)), "cycles": self.cycles,
        })

    def buy(self, px: float) -> bool:
        if self.state.btc_qty > 0: return False
        notional = self.state.cash_usdt * MAX_POSITION_PCT
        fee = notional * FEE_PCT
        if notional <= 0 or notional + fee > self.state.cash_usdt: return False
        self.state.cash_usdt -= notional + fee; self.state.btc_qty = notional / px
        self.state.entry_price = px; self.state.entry_fee = fee; self.state.fees_paid += fee; self.state.orders += 1
        self.save(px); AUDIT.write("paper_buy", price=px, qty=self.state.btc_qty, fee=fee); return True

    def sell(self, px: float, reason: str) -> bool:
        if self.state.btc_qty <= 0: return False
        qty, entry = self.state.btc_qty, self.state.entry_price or px
        gross, fee = qty * px, qty * px * FEE_PCT
        pnl = gross - fee - qty * entry - self.state.entry_fee
        self.state.cash_usdt += gross - fee; self.state.btc_qty = 0; self.state.entry_price = None; self.state.entry_fee = 0
        self.state.realized_pnl += pnl; self.state.fees_paid += fee; self.state.orders += 1; self.state.closed_trades += 1
        if pnl > 0: self.state.wins += 1; self.state.gross_profit += pnl
        elif pnl < 0: self.state.losses += 1; self.state.gross_loss += -pnl
        self.save(px); AUDIT.write("paper_sell", price=px, qty=qty, fee=fee, pnl=pnl, reason=reason); return True


def entry_economics(m: Market) -> dict[str, Any]:
    """Estimate a new paper entry's payoffs, not its probability of winning.

    Both fees are included. Entry fills at ask; each exit threshold uses mid
    relative to entry ask, then fills at bid, as in supervise/Broker. The future
    bid/mid ratio is assumed equal to the observed ratio. Slippage, gaps and
    exchange order filters are not modeled; these estimates are not fill promises.
    Returns use the entry's full cash outlay, including its fee, as denominator.
    """
    result: dict[str, Any] = {
        "model": "paper_fee_and_unchanged_relative_spread_v1", "valid": False,
        "fee_per_side": None, "observed_spread_pct": None,
        "target_net_return": None, "stop_net_return": None,
        "break_even_mid_move_from_entry": None,
        "target_stop_break_even_win_rate": None,
    }
    values = (m.best_bid, m.mid, m.best_ask, FEE_PCT)
    if any(isinstance(x, bool) or not isinstance(x, (int, float)) or not math.isfinite(x) for x in values):
        return result
    if not (0 < m.best_bid <= m.mid <= m.best_ask) or not (0 <= FEE_PCT <= .02):
        return result
    exit_bid_to_mid = m.best_bid / m.mid
    received_per_outlay = exit_bid_to_mid * (1 - FEE_PCT) / (1 + FEE_PCT)
    if received_per_outlay <= 0 or not math.isfinite(1 / received_per_outlay):
        return result
    target = (1 + TAKE_PROFIT_PCT) * received_per_outlay - 1
    stop = (1 - STOP_LOSS_PCT) * received_per_outlay - 1
    result.update(
        valid=True, fee_per_side=FEE_PCT,
        observed_spread_pct=(m.best_ask - m.best_bid) / m.mid,
        target_net_return=target, stop_net_return=stop,
        break_even_mid_move_from_entry=1 / received_per_outlay - 1,
        target_stop_break_even_win_rate=(-stop / (target - stop)) if target > 0 and stop < 0 else None,
    )
    return result


def supervise(m: Market, broker: Broker, votes: list[dict[str, Any]]) -> tuple[str, float, str]:
    available = [v for v in votes if v["available"]]
    score = sum(float(v["contribution"]) for v in available)
    if not all(math.isfinite(x) and x > 0 for x in (m.mid, m.best_bid, m.best_ask)) or not (m.best_bid <= m.mid <= m.best_ask):
        return "WAIT", score, "invalid executable quote"
    if broker.state.btc_qty > 0 and broker.state.entry_price:
        change = (m.mid - broker.state.entry_price) / broker.state.entry_price
        if change <= -STOP_LOSS_PCT: return "SELL", score, "stop loss"
        if change >= TAKE_PROFIT_PCT: return "SELL", score, "take profit"
    if broker.state.btc_qty == 0 and not tape_current(m):
        return "WAIT", score, "recent trade data unavailable"
    if len(available) < MIN_QUORUM: return "WAIT", score, "agent quorum unavailable"
    if not math.isfinite(m.mid) or m.spread_pct > MAX_SPREAD_PCT: return "WAIT", score, "market risk gate"
    equity = broker.equity(m.mid)
    if broker.state.day_start_equity <= 0 or (broker.state.day_start_equity - equity) / broker.state.day_start_equity >= MAX_DAILY_LOSS_PCT:
        return "WAIT", score, "daily loss hard limit"
    if broker.state.btc_qty > 0 and broker.state.entry_price:
        if score <= EXIT_THRESHOLD: return "SELL", score, "consensus exit"
    if broker.state.btc_qty == 0 and score >= ENTRY_THRESHOLD:
        costs = entry_economics(m)
        if not costs["valid"]:
            return "WAIT", score, "entry cost estimate unavailable"
        if costs["target_net_return"] <= 1e-12:
            return "WAIT", score, "target does not cover modeled trading costs"
        return "BUY", score, "consensus entry"
    return "WAIT", score, "bounded consensus"


def run_once(broker: Broker) -> None:
    market = snapshot(); broker.roll_day_if_needed(market.mid)
    votes = agent_votes(market); action, score, reason = supervise(market, broker, votes)
    executed = broker.buy(market.best_ask) if action == "BUY" else broker.sell(market.best_bid, reason) if action == "SELL" else False
    broker.cycles += 1; broker.save(market.mid)
    perf_vote = vote("performance_audit", 0, .4, f"closed={broker.state.closed_trades};dd={broker.state.max_drawdown_pct:.3%}")
    supervisor = vote("supervisor_consensus", 0, .4, f"score={score:+.2f};action={action}")
    AUDIT.write("cycle", symbol=SYMBOL, local_mid=market.mid, best_bid=market.best_bid, best_ask=market.best_ask,
        spread_pct=market.spread_pct, orderbook_imbalance=market.imbalance, tape_buy_ratio=market.buy_ratio,
        tape_age_seconds=market.tape_age_seconds, tape_recent_rows=len(market.prices), tape_available=tape_current(market),
        entry_economics=entry_economics(market),
        global_price=market.global_price, global_change_24h=market.global_change, global_sources=market.global_sources,
        global_dispersion_pct=market.global_dispersion_pct, signal=action, score=score, action=action, executed=executed,
        risk_reason=reason, agents=votes + [perf_vote, supervisor], equity=broker.equity(market.mid),
        realized_pnl=broker.state.realized_pnl, unrealized_pnl=(market.mid - (broker.state.entry_price or market.mid)) * broker.state.btc_qty)


def main() -> None:
    broker = Broker(); AUDIT.write("startup", mode="paper", live_orders_possible=False, risk_profile="assertive_bounded")
    while True:
        started = time.monotonic()
        try: run_once(broker)
        except Exception as exc: AUDIT.write("cycle_error", error=type(exc).__name__)
        time.sleep(max(1, LOOP_SECONDS - (time.monotonic() - started)))


if __name__ == "__main__": main()

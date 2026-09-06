from __future__ import annotations

import json
import os
import tempfile
from dataclasses import dataclass
from datetime import datetime, timezone
from decimal import Decimal, InvalidOperation
from pathlib import Path
from typing import Any, Mapping

APP_DIR = Path(os.getenv("CHOBYAR_APP_DIR", "/opt/chobyar-trader"))
DEFAULT_STATE_PATH = APP_DIR / "state" / "live_risk_stage25.json"
BALANCES_PATH = "/v1/account/balances"
MARKETS_PATH = "/v1/markets"
APPROVED_SYMBOL = "BTCUSDT"
APPROVED_MAX_ORDER_USDT = Decimal("10")
MAX_POSITION_PCT = Decimal("0.25")
MAX_DAILY_LOSS_PCT = Decimal("0.03")


class Stage25Error(RuntimeError):
    pass


@dataclass(frozen=True)
class RiskSnapshot:
    equity_usdt: Decimal
    position_usdt: Decimal
    available_usdt: Decimal
    bid_price: Decimal
    day_start_equity_usdt: Decimal
    daily_drawdown_pct: Decimal
    max_position_usdt: Decimal
    remaining_position_budget_usdt: Decimal
    max_new_buy_usdt: Decimal


def _dec(value: object) -> Decimal | None:
    try:
        out = Decimal(str(value).strip())
    except (InvalidOperation, TypeError, ValueError):
        return None
    return out if out.is_finite() else None


def _result(payload: object, reason: str) -> Mapping[str, Any]:
    if not isinstance(payload, Mapping) or payload.get("success") is not True:
        raise Stage25Error(f"{reason}_rejected")
    result = payload.get("result")
    if not isinstance(result, Mapping):
        raise Stage25Error(f"{reason}_schema_invalid")
    return result


def _json_ok(response: Any, reason: str) -> Mapping[str, Any]:
    if getattr(response, "status_code", None) != 200:
        raise Stage25Error(f"{reason}_http_{getattr(response, 'status_code', None)}")
    try:
        payload = response.json()
    except Exception as exc:
        raise Stage25Error(f"{reason}_json_invalid") from exc
    if not isinstance(payload, Mapping):
        raise Stage25Error(f"{reason}_schema_invalid")
    return payload


def _utc_day(now: datetime | None = None) -> str:
    current = now or datetime.now(timezone.utc)
    if current.tzinfo is None:
        current = current.replace(tzinfo=timezone.utc)
    return current.astimezone(timezone.utc).date().isoformat()


def _read_market_bid(client: Any) -> Decimal:
    payload = _json_ok(client.get(MARKETS_PATH, headers={"Accept": "application/json"}), "markets")
    symbols = _result(payload, "markets").get("symbols")
    row = symbols.get(APPROVED_SYMBOL) if isinstance(symbols, Mapping) else None
    if not isinstance(row, Mapping):
        raise Stage25Error("btcusdt_market_missing")
    stats = row.get("stats")
    if not isinstance(stats, Mapping):
        raise Stage25Error("btcusdt_stats_missing")
    bid = _dec(stats.get("bidPrice"))
    if bid is None or bid <= 0:
        raise Stage25Error("btcusdt_bid_invalid")
    return bid


def _read_balances(client: Any, headers: Mapping[str, str]) -> tuple[Decimal, Decimal, Decimal, Decimal]:
    payload = _json_ok(client.get(BALANCES_PATH, headers=headers), "balances")
    balances = _result(payload, "balances").get("balances")
    if not isinstance(balances, Mapping):
        raise Stage25Error("balances_schema_invalid")

    def asset(name: str) -> tuple[Decimal, Decimal]:
        raw = balances.get(name)
        if raw is None:
            return Decimal("0"), Decimal("0")
        if not isinstance(raw, Mapping):
            raise Stage25Error(f"{name.lower()}_balance_schema_invalid")
        free = _dec(raw.get("value"))
        locked = _dec(raw.get("locked"))
        if free is None or free < 0 or locked is None or locked < 0:
            raise Stage25Error(f"{name.lower()}_balance_invalid")
        return free, locked

    usdt_free, usdt_locked = asset("USDT")
    btc_free, btc_locked = asset("BTC")
    return usdt_free, usdt_locked, btc_free, btc_locked


def current_book_equity(*, client: Any, headers: Mapping[str, str]) -> tuple[Decimal, Decimal, Decimal, Decimal]:
    """Conservative BTCUSDT trading-book equity: USDT plus BTC marked at executable bid."""
    bid = _read_market_bid(client)
    usdt_free, usdt_locked, btc_free, btc_locked = _read_balances(client, headers)
    btc_total = btc_free + btc_locked
    usdt_total = usdt_free + usdt_locked
    position = btc_total * bid
    equity = usdt_total + position
    if equity <= 0:
        raise Stage25Error("book_equity_nonpositive")
    return equity, position, usdt_free, bid


def _load_state(path: Path, *, now: datetime | None = None) -> Decimal:
    try:
        raw = json.loads(path.read_text())
    except FileNotFoundError as exc:
        raise Stage25Error("daily_baseline_required") from exc
    except Exception as exc:
        raise Stage25Error("daily_baseline_invalid") from exc
    if not isinstance(raw, Mapping):
        raise Stage25Error("daily_baseline_invalid")
    if str(raw.get("utc_day") or "") != _utc_day(now):
        raise Stage25Error("daily_baseline_stale")
    start = _dec(raw.get("start_equity_usdt"))
    if start is None or start <= 0:
        raise Stage25Error("daily_baseline_invalid")
    return start


def initialize_daily_baseline(*, client: Any, headers: Mapping[str, str], state_path: Path | None = None, now: datetime | None = None) -> dict[str, str]:
    path = state_path or DEFAULT_STATE_PATH
    equity, position, available_usdt, bid = current_book_equity(client=client, headers=headers)
    day = _utc_day(now)
    path.parent.mkdir(parents=True, exist_ok=True)
    payload = {
        "version": 1,
        "utc_day": day,
        "start_equity_usdt": format(equity, "f"),
        "initialized_at": datetime.now(timezone.utc).isoformat(),
        "scope": "BTCUSDT_BOOK_ONLY",
    }
    fd, tmp_name = tempfile.mkstemp(prefix=".stage25-", dir=str(path.parent), text=True)
    try:
        with os.fdopen(fd, "w") as handle:
            json.dump(payload, handle, sort_keys=True, separators=(",", ":"))
            handle.flush()
            os.fsync(handle.fileno())
        os.chmod(tmp_name, 0o600)
        os.replace(tmp_name, path)
        os.chmod(path, 0o600)
    finally:
        if os.path.exists(tmp_name):
            os.unlink(tmp_name)
    return {
        "utc_day": day,
        "start_equity_usdt": format(equity, "f"),
        "position_usdt": format(position, "f"),
        "available_usdt": format(available_usdt, "f"),
        "bid_price": format(bid, "f"),
    }


def evaluate_buy_risk(*, env: Mapping[str, str], intended_notional: Decimal, client: Any, headers: Mapping[str, str], state_path: Path | None = None, now: datetime | None = None) -> RiskSnapshot:
    if _dec(env.get("MAX_POSITION_PCT")) != MAX_POSITION_PCT:
        raise Stage25Error("max_position_pct_mismatch")
    if _dec(env.get("MAX_DAILY_LOSS_PCT")) != MAX_DAILY_LOSS_PCT:
        raise Stage25Error("max_daily_loss_pct_mismatch")
    if intended_notional <= 0:
        raise Stage25Error("buy_notional_invalid")
    if intended_notional > APPROVED_MAX_ORDER_USDT:
        raise Stage25Error("hard_cap_10_usdt_exceeded")

    equity, position, available_usdt, bid = current_book_equity(client=client, headers=headers)
    start_equity = _load_state(state_path or DEFAULT_STATE_PATH, now=now)
    drawdown = (start_equity - equity) / start_equity if equity < start_equity else Decimal("0")
    if drawdown >= MAX_DAILY_LOSS_PCT:
        raise Stage25Error("daily_loss_limit_reached")

    max_position = equity * MAX_POSITION_PCT
    remaining = max_position - position
    if remaining < 0:
        remaining = Decimal("0")
    max_new_buy = min(APPROVED_MAX_ORDER_USDT, available_usdt, remaining)
    if intended_notional > max_new_buy:
        raise Stage25Error("buy_exceeds_equity_sized_budget")

    return RiskSnapshot(
        equity_usdt=equity,
        position_usdt=position,
        available_usdt=available_usdt,
        bid_price=bid,
        day_start_equity_usdt=start_equity,
        daily_drawdown_pct=drawdown,
        max_position_usdt=max_position,
        remaining_position_budget_usdt=remaining,
        max_new_buy_usdt=max_new_buy,
    )


def enforce_buy_risk(*, env: Mapping[str, str], intended_notional: Decimal, client: Any, headers: Mapping[str, str], state_path: Path | None = None, now: datetime | None = None) -> RiskSnapshot:
    return evaluate_buy_risk(env=env, intended_notional=intended_notional, client=client, headers=headers, state_path=state_path, now=now)

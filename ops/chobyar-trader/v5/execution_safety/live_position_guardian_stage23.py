from __future__ import annotations

import os
import time
from dataclasses import dataclass
from decimal import Decimal, ROUND_DOWN
from typing import Any, Mapping

from live_executor_stage22 import (
    APPROVED_MAX_ORDER_USDT,
    APPROVED_SYMBOL,
    LiveOrderIntent,
    Stage22Error,
    _dec,
    _json_ok,
    api_key,
    merged_env,
    parse_market_rules,
    submit_one_live_limit_order,
    validate_live_env,
)

BASE_URL = "https://api.wallex.ir"
ORDER_PATH = "/v1/account/orders"
OPEN_ORDERS_PATH = "/v1/account/openOrders"
BALANCES_PATH = "/v1/account/balances"
MARKETS_PATH = "/v1/markets"
STOP_LOSS_PCT = Decimal("0.015")
TAKE_PROFIT_PCT = Decimal("0.03")
EXIT_CROSS_PCT = Decimal("0.002")


class Stage23Error(RuntimeError):
    pass


@dataclass(frozen=True)
class GuardDecision:
    state: str
    entry_price: Decimal
    last_price: Decimal
    bid_price: Decimal
    stop_price: Decimal
    take_price: Decimal
    available_btc: Decimal


def _map_result(payload: Mapping[str, Any], reason: str) -> Mapping[str, Any]:
    result = payload.get("result")
    if not isinstance(result, Mapping):
        raise Stage23Error(f"{reason}_schema_invalid")
    return result


def _positive_decimal(value: object, reason: str) -> Decimal:
    out = _dec(value)
    if out is None or out <= 0:
        raise Stage23Error(reason)
    return out


def read_guard_decision(*, env: Mapping[str, str], entry_client_id: str, client: Any) -> GuardDecision:
    try:
        validate_live_env(env)
        key = api_key(env)
    except Stage22Error as exc:
        raise Stage23Error(str(exc)) from exc
    headers = {"X-API-Key": key, "Accept": "application/json", "Content-Type": "application/json"}

    entry_payload = _json_ok(client.get(f"{ORDER_PATH}/{entry_client_id}", headers=headers), {200}, "entry_order")
    entry = _map_result(entry_payload, "entry_order")
    if str(entry.get("symbol") or "").upper() != APPROVED_SYMBOL:
        raise Stage23Error("entry_symbol_mismatch")
    if str(entry.get("side") or "").upper() != "BUY":
        raise Stage23Error("entry_side_must_be_buy")
    if str(entry.get("status") or "").upper() != "FILLED":
        raise Stage23Error("entry_must_be_filled")
    entry_price = _positive_decimal(entry.get("executedPrice"), "entry_executed_price_invalid")

    open_payload = _json_ok(client.get(OPEN_ORDERS_PATH, params={"symbol": APPROVED_SYMBOL}, headers=headers), {200}, "open_orders")
    open_result = _map_result(open_payload, "open_orders")
    orders = open_result.get("orders")
    if not isinstance(orders, list):
        raise Stage23Error("open_orders_schema_invalid")
    if orders:
        return GuardDecision(
            state="EXIT_PENDING",
            entry_price=entry_price,
            last_price=Decimal("0"),
            bid_price=Decimal("0"),
            stop_price=entry_price * (Decimal("1") - STOP_LOSS_PCT),
            take_price=entry_price * (Decimal("1") + TAKE_PROFIT_PCT),
            available_btc=Decimal("0"),
        )

    balance_payload = _json_ok(client.get(BALANCES_PATH, headers=headers), {200}, "balances")
    balances = _map_result(balance_payload, "balances").get("balances")
    if not isinstance(balances, Mapping):
        raise Stage23Error("balances_schema_invalid")
    btc = balances.get("BTC")
    if not isinstance(btc, Mapping):
        raise Stage23Error("btc_balance_missing")
    available_btc = _dec(btc.get("value"))
    locked_btc = _dec(btc.get("locked"))
    if available_btc is None or available_btc < 0 or locked_btc is None or locked_btc < 0:
        raise Stage23Error("btc_balance_invalid")
    if locked_btc != 0:
        raise Stage23Error("btc_locked_without_open_order")
    if available_btc == 0:
        return GuardDecision(
            state="FLAT",
            entry_price=entry_price,
            last_price=Decimal("0"),
            bid_price=Decimal("0"),
            stop_price=entry_price * (Decimal("1") - STOP_LOSS_PCT),
            take_price=entry_price * (Decimal("1") + TAKE_PROFIT_PCT),
            available_btc=available_btc,
        )

    market_payload = _json_ok(client.get(MARKETS_PATH, headers={"Accept": "application/json"}), {200}, "markets")
    symbols = _map_result(market_payload, "markets").get("symbols")
    raw = symbols.get(APPROVED_SYMBOL) if isinstance(symbols, Mapping) else None
    if not isinstance(raw, Mapping):
        raise Stage23Error("btcusdt_market_missing")
    stats = raw.get("stats")
    if not isinstance(stats, Mapping):
        raise Stage23Error("btcusdt_stats_missing")
    last_price = _positive_decimal(stats.get("lastPrice"), "last_price_invalid")
    bid_price = _positive_decimal(stats.get("bidPrice"), "bid_price_invalid")

    stop_price = entry_price * (Decimal("1") - STOP_LOSS_PCT)
    take_price = entry_price * (Decimal("1") + TAKE_PROFIT_PCT)
    state = "HOLD"
    if last_price <= stop_price:
        state = "STOP_TRIGGER"
    elif last_price >= take_price:
        state = "TAKE_TRIGGER"
    return GuardDecision(state, entry_price, last_price, bid_price, stop_price, take_price, available_btc)


def guard_once(*, env: Mapping[str, str], entry_client_id: str, client: Any) -> dict[str, object]:
    decision = read_guard_decision(env=env, entry_client_id=entry_client_id, client=client)
    if decision.state not in {"STOP_TRIGGER", "TAKE_TRIGGER"}:
        return {"submitted": False, "state": decision.state, "decision": decision}

    market_payload = _json_ok(client.get(MARKETS_PATH, headers={"Accept": "application/json"}), {200}, "market_rules")
    rules = parse_market_rules(market_payload)
    quantity = decision.available_btc.quantize(rules.quantity_step, rounding=ROUND_DOWN)
    price = (decision.bid_price * (Decimal("1") - EXIT_CROSS_PCT)).quantize(rules.price_tick, rounding=ROUND_DOWN)
    if quantity <= 0 or price <= 0:
        raise Stage23Error("exit_order_invalid")
    notional = quantity * price
    if notional > APPROVED_MAX_ORDER_USDT:
        raise Stage23Error("exit_hard_cap_exceeded")
    if notional < rules.min_notional:
        raise Stage23Error("exit_below_min_notional")

    state_slug = decision.state.lower().replace("_", "-")
    client_id = f"chobyar-stage23-{state_slug}-{int(time.time())}"
    try:
        result = submit_one_live_limit_order(
            env=env,
            intent=LiveOrderIntent("SELL", quantity, price, client_id),
            client=client,
        )
    except Stage22Error as exc:
        raise Stage23Error(str(exc)) from exc
    return {"submitted": True, "state": decision.state, "decision": decision, "order": result}


def main() -> int:
    import argparse
    import httpx

    parser = argparse.ArgumentParser(description="Fail-closed one-shot BTCUSDT spot position guardian")
    parser.add_argument("--entry-client-id", required=False, default=os.getenv("LIVE_ENTRY_CLIENT_ID", ""))
    args = parser.parse_args()
    entry_id = args.entry_client_id.strip()
    if not entry_id:
        raise Stage23Error("entry_client_id_required")

    env = merged_env()
    try:
        with httpx.Client(base_url=BASE_URL, timeout=12) as client:
            result = guard_once(env=env, entry_client_id=entry_id, client=client)
        d = result["decision"]
        assert isinstance(d, GuardDecision)
        print(f"STAGE23_STATE={result['state']}")
        print(f"ENTRY_PRICE={d.entry_price}")
        print(f"LAST_PRICE={d.last_price}")
        print(f"STOP_PRICE={d.stop_price}")
        print(f"TAKE_PRICE={d.take_price}")
        print(f"BTC_AVAILABLE={d.available_btc}")
        print(f"EXIT_SUBMITTED={'YES' if result['submitted'] else 'NO'}")
        return 0
    except (Stage22Error, Stage23Error) as exc:
        print(f"FAIL-CLOSED: {exc}")
        return 2


if __name__ == "__main__":
    raise SystemExit(main())

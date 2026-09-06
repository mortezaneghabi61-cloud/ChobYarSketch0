from __future__ import annotations

import argparse
import os
import re
from dataclasses import dataclass
from decimal import Decimal, InvalidOperation
from pathlib import Path
from typing import Any, Mapping

APP_DIR = Path(os.getenv("CHOBYAR_APP_DIR", "/opt/chobyar-trader"))
BASE_URL = "https://api.wallex.ir"
ORDER_PATH = "/v1/account/orders"
OPEN_ORDERS_PATH = "/v1/account/openOrders"
MARKETS_PATH = "/v1/markets"
ACTIVE_MARKETS_PATH = "/hector/web/v1/markets"
APPROVED_SYMBOL = "BTCUSDT"
APPROVED_QUOTE = "USDT"
APPROVED_MAX_ORDER_USDT = Decimal("10")
APPROVED_RISK = (
    Decimal("0.25"),
    Decimal("0.015"),
    Decimal("0.03"),
    Decimal("0.03"),
)


@dataclass(frozen=True)
class MarketRules:
    quantity_step: Decimal
    price_tick: Decimal
    min_notional: Decimal
    max_notional: Decimal | None


@dataclass(frozen=True)
class LiveOrderIntent:
    side: str
    quantity: Decimal
    price: Decimal
    client_id: str


class Stage22Error(RuntimeError):
    pass


def _read_env_file(path: Path) -> dict[str, str]:
    values: dict[str, str] = {}
    if not path.exists():
        raise Stage22Error("env_file_missing")
    for raw in path.read_text().splitlines():
        line = raw.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        values[key.strip()] = value.strip()
    return values


def merged_env() -> dict[str, str]:
    values = _read_env_file(APP_DIR / ".env")
    values.update({k: v for k, v in os.environ.items() if isinstance(v, str)})
    return values


def _dec(value: object) -> Decimal | None:
    try:
        out = Decimal(str(value).strip())
    except (InvalidOperation, TypeError, ValueError):
        return None
    return out if out.is_finite() else None


def _power10_precision(value: object) -> Decimal | None:
    try:
        places = int(str(value))
    except (TypeError, ValueError):
        return None
    if places < 0 or places > 18:
        return None
    return Decimal(1).scaleb(-places)


def validate_live_env(env: Mapping[str, str]) -> None:
    if (env.get("TRADING_MODE") or "").strip().lower() != "live":
        raise Stage22Error("trading_mode_live_required")
    if (env.get("LIVE_TRADING_ENABLED") or "").strip().lower() != "true":
        raise Stage22Error("live_gate_true_required")
    if (env.get("LIVE_EXECUTION_ARMED") or "").strip().lower() != "true":
        raise Stage22Error("live_execution_arm_true_required")
    if (env.get("SYMBOL") or "").strip().upper() != APPROVED_SYMBOL:
        raise Stage22Error("only_btcusdt_approved")
    if _dec(env.get("LIVE_MAX_ORDER_USDT")) != APPROVED_MAX_ORDER_USDT:
        raise Stage22Error("live_cap_must_equal_10_usdt")
    if (env.get("SPOT_ONLY") or "").strip().lower() != "true":
        raise Stage22Error("spot_only_required")
    if (env.get("WITHDRAWALS_ENABLED") or "").strip().lower() != "false":
        raise Stage22Error("withdrawals_must_remain_false")
    if (env.get("LEVERAGE_ENABLED") or "").strip().lower() != "false":
        raise Stage22Error("leverage_must_remain_false")
    for name in ("MARGIN_ENABLED", "FUTURES_ENABLED", "OTC_ENABLED"):
        if (env.get(name) or "false").strip().lower() != "false":
            raise Stage22Error("non_spot_authority_must_remain_false")
    risk = (
        _dec(env.get("MAX_POSITION_PCT")),
        _dec(env.get("STOP_LOSS_PCT")),
        _dec(env.get("TAKE_PROFIT_PCT")),
        _dec(env.get("MAX_DAILY_LOSS_PCT")),
    )
    if risk != APPROVED_RISK:
        raise Stage22Error("approved_risk_profile_mismatch")


def api_key(env: Mapping[str, str]) -> str:
    for name in ("WALLEX_API_KEY", "API_KEY", "WALLEX_KEY"):
        value = (env.get(name) or "").strip()
        if value:
            return value
    raise Stage22Error("wallex_api_key_missing")


def parse_market_rules(payload: object) -> MarketRules:
    if not isinstance(payload, Mapping) or payload.get("success") is not True:
        raise Stage22Error("market_rules_rejected")
    result = payload.get("result")
    symbols = result.get("symbols") if isinstance(result, Mapping) else None
    raw = symbols.get(APPROVED_SYMBOL) if isinstance(symbols, Mapping) else None
    if not isinstance(raw, Mapping):
        raise Stage22Error("btc_usdt_market_rules_missing")
    step = _power10_precision(raw.get("stepSize"))
    tick = _power10_precision(raw.get("tickSize"))
    min_notional = _dec(raw.get("minNotional"))
    max_notional = _dec(raw.get("maxNotional")) if raw.get("maxNotional") is not None else None
    if step is None or step <= 0 or tick is None or tick <= 0:
        raise Stage22Error("market_precision_invalid")
    if min_notional is None or min_notional <= 0:
        raise Stage22Error("market_min_notional_invalid")
    if max_notional is not None and max_notional < min_notional:
        raise Stage22Error("market_max_notional_invalid")
    return MarketRules(step, tick, min_notional, max_notional)


def ensure_active_spot(payload: object) -> None:
    if not isinstance(payload, Mapping):
        raise Stage22Error("active_markets_schema_invalid")
    result = payload.get("result")
    markets = result.get("markets") if isinstance(result, Mapping) else None
    if not isinstance(markets, list):
        raise Stage22Error("active_markets_missing")
    for row in markets:
        if not isinstance(row, Mapping):
            continue
        if str(row.get("symbol") or "").strip().upper() != APPROVED_SYMBOL:
            continue
        if row.get("is_spot") is not True:
            raise Stage22Error("btc_usdt_spot_not_active")
        if str(row.get("quote_asset") or "").strip().upper() != APPROVED_QUOTE:
            raise Stage22Error("btc_usdt_quote_mismatch")
        return
    raise Stage22Error("btc_usdt_active_market_missing")


def validate_intent(intent: LiveOrderIntent, rules: MarketRules) -> Decimal:
    side = intent.side.strip().upper()
    if side not in {"BUY", "SELL"}:
        raise Stage22Error("side_invalid")
    if intent.quantity <= 0 or intent.price <= 0:
        raise Stage22Error("quantity_and_price_must_be_positive")
    if intent.quantity % rules.quantity_step != 0:
        raise Stage22Error("quantity_step_mismatch")
    if intent.price % rules.price_tick != 0:
        raise Stage22Error("price_tick_mismatch")
    if not re.fullmatch(r"chobyar-[a-z0-9][a-z0-9-]{7,63}", intent.client_id):
        raise Stage22Error("client_id_invalid")
    notional = intent.quantity * intent.price
    if notional < rules.min_notional:
        raise Stage22Error("below_market_min_notional")
    if rules.max_notional is not None and notional > rules.max_notional:
        raise Stage22Error("above_market_max_notional")
    if notional > APPROVED_MAX_ORDER_USDT:
        raise Stage22Error("hard_cap_10_usdt_exceeded")
    return notional


def _safe_error_summary(response: Any) -> str:
    """Return bounded, whitelisted exchange validation detail without headers or secrets."""
    try:
        payload = response.json()
    except Exception:
        return "no_json_error_detail"
    if not isinstance(payload, Mapping):
        return "non_mapping_error_detail"
    parts: list[str] = []
    for key in ("message", "error", "code", "errors"):
        if key not in payload:
            continue
        text = str(payload.get(key)).replace("\n", " ").replace("\r", " ").strip()
        if text:
            parts.append(f"{key}={text[:240]}")
    return ";".join(parts)[:500] if parts else "no_whitelisted_error_detail"


def _json_ok(response: Any, expected_statuses: set[int], reason: str) -> Mapping[str, Any]:
    status = getattr(response, "status_code", None)
    if status not in expected_statuses:
        detail = _safe_error_summary(response)
        raise Stage22Error(f"{reason}_http_{status}:{detail}")
    try:
        payload = response.json()
    except Exception as exc:
        raise Stage22Error(f"{reason}_json_invalid") from exc
    if not isinstance(payload, Mapping) or payload.get("success") is not True:
        raise Stage22Error(f"{reason}_rejected")
    return payload


def submit_one_live_limit_order(*, env: Mapping[str, str], intent: LiveOrderIntent, client: Any) -> dict[str, object]:
    """Submit at most one explicitly identified BTCUSDT LIMIT order after fail-closed checks."""
    validate_live_env(env)
    key = api_key(env)
    headers = {"X-API-Key": key, "Accept": "application/json", "Content-Type": "application/json"}

    active_payload = _json_ok(client.get(ACTIVE_MARKETS_PATH, headers={"Accept": "application/json"}), {200}, "active_markets")
    ensure_active_spot(active_payload)
    rules_payload = _json_ok(client.get(MARKETS_PATH, headers={"Accept": "application/json"}), {200}, "market_rules")
    rules = parse_market_rules(rules_payload)
    notional = validate_intent(intent, rules)

    open_payload = _json_ok(
        client.get(OPEN_ORDERS_PATH, params={"symbol": APPROVED_SYMBOL}, headers=headers),
        {200},
        "open_orders",
    )
    result = open_payload.get("result")
    orders = result.get("orders") if isinstance(result, Mapping) else None
    if not isinstance(orders, list):
        raise Stage22Error("open_orders_schema_invalid")
    if orders:
        raise Stage22Error("existing_open_order_blocks_submission")

    prior = client.get(f"{ORDER_PATH}/{intent.client_id}", headers=headers)
    if getattr(prior, "status_code", None) == 200:
        raise Stage22Error("client_id_already_exists")
    if getattr(prior, "status_code", None) not in {404}:
        raise Stage22Error("client_id_precheck_ambiguous")

    body = {
        "symbol": APPROVED_SYMBOL,
        "type": "LIMIT",
        "side": intent.side.strip().upper(),
        "price": format(intent.price, "f"),
        "quantity": format(intent.quantity, "f"),
        "client_id": intent.client_id,
    }
    response = client.post(ORDER_PATH, json=body, headers=headers)
    payload = _json_ok(response, {201}, "order_submit")
    order = payload.get("result")
    if not isinstance(order, Mapping):
        raise Stage22Error("order_submit_schema_invalid")
    if str(order.get("symbol") or "").strip().upper() != APPROVED_SYMBOL:
        raise Stage22Error("submitted_symbol_mismatch")
    if str(order.get("side") or "").strip().upper() != body["side"]:
        raise Stage22Error("submitted_side_mismatch")
    if str(order.get("type") or "").strip().upper() != "LIMIT":
        raise Stage22Error("submitted_type_mismatch")
    server_id = str(order.get("clientOrderId") or "").strip()
    if not server_id:
        raise Stage22Error("submitted_client_order_id_missing")
    return {
        "submitted": True,
        "symbol": APPROVED_SYMBOL,
        "side": body["side"],
        "notional_usdt": str(notional),
        "client_order_id": server_id,
        "hard_cap_usdt": "10",
        "withdrawals_enabled": False,
        "leverage_enabled": False,
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Fail-closed ChobYar Stage-22 one-shot live LIMIT executor")
    parser.add_argument("--side", required=True, choices=["BUY", "SELL"])
    parser.add_argument("--quantity", required=True)
    parser.add_argument("--price", required=True)
    parser.add_argument("--client-id", required=True)
    args = parser.parse_args(argv)

    quantity = _dec(args.quantity)
    price = _dec(args.price)
    if quantity is None or price is None:
        raise SystemExit("FAIL-CLOSED: invalid decimal input")

    try:
        import httpx
    except ImportError as exc:
        raise SystemExit("FAIL-CLOSED: httpx unavailable in venv") from exc

    env = merged_env()
    intent = LiveOrderIntent(args.side, quantity, price, args.client_id)
    try:
        with httpx.Client(base_url=BASE_URL, timeout=12.0) as client:
            result = submit_one_live_limit_order(env=env, intent=intent, client=client)
    except Stage22Error as exc:
        raise SystemExit(f"FAIL-CLOSED: {exc}") from None

    print("STAGE22_LIVE_ORDER=SUBMITTED")
    print(f"SYMBOL={result['symbol']}")
    print(f"SIDE={result['side']}")
    print(f"NOTIONAL_USDT={result['notional_usdt']}")
    print(f"CLIENT_ORDER_ID={result['client_order_id']}")
    print("HARD_CAP_USDT=10")
    print("WITHDRAWALS=DISABLED")
    print("LEVERAGE=DISABLED")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

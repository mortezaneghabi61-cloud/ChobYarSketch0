from __future__ import annotations

from dataclasses import dataclass
from decimal import Decimal, InvalidOperation
from typing import Any, Mapping

from live_safety import evaluate_live_safety

OPEN_ORDERS_PATH = "/v1/account/openOrders"


@dataclass(frozen=True)
class OpenOrder:
    symbol: str
    order_type: str
    side: str
    price: Decimal
    original_quantity: Decimal
    executed_quantity: Decimal
    status: str
    active: bool
    client_order_id: str


def _decimal(value: object) -> Decimal:
    try:
        result = Decimal(str(value))
    except (InvalidOperation, TypeError, ValueError) as exc:
        raise RuntimeError("open_orders_invalid_number") from exc
    if not result.is_finite() or result < 0:
        raise RuntimeError("open_orders_invalid_number")
    return result


def fetch_open_orders_readonly(
    *,
    env: Mapping[str, str],
    api_key: str,
    client: Any,
    symbol: str,
) -> tuple[OpenOrder, ...]:
    """Stage-6 GET-only reader for the user's active spot orders.

    It cannot create, cancel, replace, withdraw, or otherwise mutate exchange state.
    """
    safety = evaluate_live_safety(env)
    if not safety.allowed:
        raise RuntimeError(f"stage1_blocked:{safety.reason}")
    if (env.get("LIVE_TRADING_ENABLED") or "").strip().lower() != "false":
        raise RuntimeError("live_execution_must_remain_disabled")
    if (env.get("SPOT_ONLY") or "").strip().lower() != "true":
        raise RuntimeError("spot_only_required")

    key = (api_key or "").strip()
    if not key:
        raise RuntimeError("wallex_api_key_missing")
    wanted = (symbol or "").strip().upper()
    if not wanted:
        raise RuntimeError("open_orders_symbol_missing")

    response = client.get(
        OPEN_ORDERS_PATH,
        params={"symbol": wanted},
        headers={"X-API-Key": key, "Accept": "application/json"},
    )
    response.raise_for_status()
    payload = response.json()
    if not isinstance(payload, Mapping) or payload.get("success") is not True:
        raise RuntimeError("open_orders_request_rejected")

    result = payload.get("result")
    orders = result.get("orders") if isinstance(result, Mapping) else None
    if not isinstance(orders, list):
        raise RuntimeError("open_orders_schema_invalid")

    parsed: list[OpenOrder] = []
    for raw in orders:
        if not isinstance(raw, Mapping):
            raise RuntimeError("open_orders_schema_invalid")
        returned_symbol = str(raw.get("symbol") or "").strip().upper()
        if returned_symbol != wanted:
            raise RuntimeError("open_orders_symbol_mismatch")

        order_type = str(raw.get("type") or "").strip().upper()
        side = str(raw.get("side") or "").strip().upper()
        status = str(raw.get("status") or "").strip().upper()
        client_order_id = str(raw.get("clientOrderId") or "").strip()
        active = raw.get("active")
        if order_type not in {"LIMIT", "MARKET"}:
            raise RuntimeError("open_orders_type_invalid")
        if side not in {"BUY", "SELL"}:
            raise RuntimeError("open_orders_side_invalid")
        if not status or not client_order_id or active is not True:
            raise RuntimeError("open_orders_state_invalid")

        parsed.append(
            OpenOrder(
                symbol=returned_symbol,
                order_type=order_type,
                side=side,
                price=_decimal(raw.get("price", 0)),
                original_quantity=_decimal(raw.get("origQty", 0)),
                executed_quantity=_decimal(raw.get("executedQty", 0)),
                status=status,
                active=True,
                client_order_id=client_order_id,
            )
        )

    return tuple(parsed)

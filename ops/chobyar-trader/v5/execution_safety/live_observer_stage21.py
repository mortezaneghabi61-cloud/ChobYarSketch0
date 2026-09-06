from __future__ import annotations

import os
from decimal import Decimal, InvalidOperation
from pathlib import Path
from typing import Any, Mapping

APP_DIR = Path(os.getenv("CHOBYAR_APP_DIR", "/opt/chobyar-trader"))
BALANCES_PATH = "/v1/account/balances"
OPEN_ORDERS_PATH = "/v1/account/openOrders"
MARKETS_PATH = "/hector/web/v1/markets"
APPROVED_SYMBOL = "BTCUSDT"
APPROVED_MAX_ORDER_USDT = Decimal("10")
APPROVED_RISK = (
    Decimal("0.25"),
    Decimal("0.015"),
    Decimal("0.03"),
    Decimal("0.03"),
)


def _read_env_file(path: Path) -> dict[str, str]:
    values: dict[str, str] = {}
    if not path.exists():
        raise RuntimeError("env_file_missing")
    for raw in path.read_text().splitlines():
        line = raw.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        values[key.strip()] = value.strip()
    return values


def _merged_env() -> dict[str, str]:
    values = _read_env_file(APP_DIR / ".env")
    values.update({k: v for k, v in os.environ.items() if isinstance(v, str)})
    return values


def _decimal(value: object) -> Decimal | None:
    try:
        result = Decimal(str(value).strip())
    except (InvalidOperation, TypeError, ValueError):
        return None
    return result if result.is_finite() else None


def validate_live_observer_env(env: Mapping[str, str]) -> None:
    if (env.get("TRADING_MODE") or "").strip().lower() != "live":
        raise RuntimeError("stage21_requires_trading_mode_live")
    if (env.get("LIVE_TRADING_ENABLED") or "").strip().lower() != "true":
        raise RuntimeError("stage21_requires_live_gate_true")
    if (env.get("LIVE_EXECUTION_ARMED") or "").strip().lower() != "false":
        raise RuntimeError("stage21_requires_execution_arm_false")
    if (env.get("SYMBOL") or "").strip().upper() != APPROVED_SYMBOL:
        raise RuntimeError("stage21_only_btcusdt")
    if _decimal(env.get("LIVE_MAX_ORDER_USDT")) != APPROVED_MAX_ORDER_USDT:
        raise RuntimeError("stage21_cap_must_equal_10_usdt")
    if (env.get("SPOT_ONLY") or "").strip().lower() != "true":
        raise RuntimeError("stage21_spot_only_required")
    if (env.get("WITHDRAWALS_ENABLED") or "").strip().lower() != "false":
        raise RuntimeError("stage21_withdrawals_must_be_false")
    if (env.get("LEVERAGE_ENABLED") or "").strip().lower() != "false":
        raise RuntimeError("stage21_leverage_must_be_false")
    risk = (
        _decimal(env.get("MAX_POSITION_PCT")),
        _decimal(env.get("STOP_LOSS_PCT")),
        _decimal(env.get("TAKE_PROFIT_PCT")),
        _decimal(env.get("MAX_DAILY_LOSS_PCT")),
    )
    if risk != APPROVED_RISK:
        raise RuntimeError("stage21_risk_profile_mismatch")


def _api_key(env: Mapping[str, str]) -> str:
    for name in ("WALLEX_API_KEY", "API_KEY", "WALLEX_KEY"):
        value = (env.get(name) or "").strip()
        if value:
            return value
    raise RuntimeError("wallex_api_key_missing")


def _require_success(response: Any, reason: str) -> object:
    if getattr(response, "status_code", None) != 200:
        raise RuntimeError(f"{reason}_http_{getattr(response, 'status_code', 'unknown')}")
    payload = response.json()
    if not isinstance(payload, dict) or payload.get("success") is not True:
        raise RuntimeError(f"{reason}_rejected")
    return payload


def _open_order_count(payload: object) -> int:
    if not isinstance(payload, dict):
        raise RuntimeError("open_orders_schema_invalid")
    result = payload.get("result")
    orders = result.get("orders") if isinstance(result, dict) else None
    if not isinstance(orders, list):
        raise RuntimeError("open_orders_schema_invalid")
    return len(orders)


def run_live_observer_once(*, env: Mapping[str, str] | None = None, client: Any | None = None) -> dict[str, object]:
    """GET-only live account observation. Never submits, cancels, or modifies orders."""
    effective = dict(env) if env is not None else _merged_env()
    validate_live_observer_env(effective)
    key = _api_key(effective)
    headers = {"X-API-Key": key, "Accept": "application/json", "User-Agent": "ChobYar-Trader/5-live-observer"}
    owns_client = client is None
    if client is None:
        import httpx
        http = httpx.Client(base_url="https://api.wallex.ir", timeout=12.0, headers=headers)
    else:
        http = client
    try:
        balances = _require_success(http.get(BALANCES_PATH, headers=headers), "balances")
        open_orders = _require_success(
            http.get(OPEN_ORDERS_PATH, params={"symbol": APPROVED_SYMBOL}, headers=headers),
            "open_orders",
        )
        markets_response = http.get(MARKETS_PATH, headers={"Accept": "application/json", "User-Agent": headers["User-Agent"]})
        if getattr(markets_response, "status_code", None) != 200:
            raise RuntimeError(f"markets_http_{getattr(markets_response, 'status_code', 'unknown')}")
        markets_payload = markets_response.json()
        if not isinstance(markets_payload, dict):
            raise RuntimeError("markets_schema_invalid")
        count = _open_order_count(open_orders)
        if count != 0:
            raise RuntimeError("stage21_requires_zero_open_orders")
        _ = balances, markets_payload
        return {
            "live_observer": "PASS",
            "symbol": APPROVED_SYMBOL,
            "open_orders_count": 0,
            "max_order_usdt": str(APPROVED_MAX_ORDER_USDT),
            "execution_armed": False,
            "execution_authority": False,
            "order_submission_authority": False,
        }
    finally:
        if owns_client:
            http.close()


def main() -> int:
    result = run_live_observer_once()
    print("LIVE_OBSERVER=PASS")
    print(f"SYMBOL={result['symbol']}")
    print("OPEN_ORDERS=0")
    print("LIVE_MAX_ORDER_USDT=10")
    print("EXECUTION_ARMED=FALSE")
    print("EXECUTION_AUTHORITY=NONE")
    print("ORDER_SUBMISSION=NOT_USED")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

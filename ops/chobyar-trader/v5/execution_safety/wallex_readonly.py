from __future__ import annotations

from dataclasses import dataclass
from typing import Any, Mapping

from v5_safety_core import evaluate_v5_safety

WALLEX_BASE_URL = "https://api.wallex.ir"
BALANCES_PATH = "/v1/account/balances"


@dataclass(frozen=True)
class BalanceRow:
    asset: str
    value: float
    locked: float


def _finite(value: Any) -> float:
    try:
        number = float(value)
    except (TypeError, ValueError) as exc:
        raise RuntimeError("invalid_balance_number") from exc
    if number != number or number in (float("inf"), float("-inf")):
        raise RuntimeError("invalid_balance_number")
    return number


def fetch_balances_readonly(*, env: Mapping[str, str], api_key: str, client: Any) -> tuple[BalanceRow, ...]:
    """GET-only Wallex balances behind the quote-neutral v5 safety gate."""
    decision = evaluate_v5_safety(env)
    if not decision.allowed:
        raise RuntimeError(f"v5_safety_blocked:{decision.reason}")
    key = api_key.strip()
    if not key:
        raise RuntimeError("wallex_api_key_missing")

    response = client.get(BALANCES_PATH, headers={"X-API-Key": key, "Accept": "application/json"})
    response.raise_for_status()
    data = response.json()
    if not isinstance(data, dict) or data.get("success") is not True:
        raise RuntimeError("wallex_balance_request_rejected")

    result = data.get("result")
    balances = result.get("balances") if isinstance(result, dict) else None
    if not isinstance(balances, dict):
        raise RuntimeError("wallex_balance_schema_invalid")

    rows: list[BalanceRow] = []
    for asset, raw in balances.items():
        if not isinstance(asset, str) or not asset or not isinstance(raw, dict):
            raise RuntimeError("wallex_balance_schema_invalid")
        value = _finite(raw.get("value", 0))
        locked = _finite(raw.get("locked", 0))
        if value < 0 or locked < 0:
            raise RuntimeError("wallex_balance_negative")
        rows.append(BalanceRow(asset=asset.upper(), value=value, locked=locked))

    return tuple(sorted(rows, key=lambda row: row.asset))

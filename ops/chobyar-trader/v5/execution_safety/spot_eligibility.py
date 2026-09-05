from __future__ import annotations

from dataclasses import dataclass
from typing import Mapping, Protocol

from v5_safety_core import evaluate_v5_safety

ACTIVE_MARKETS_PATH = "/hector/web/v1/markets"


class GetClient(Protocol):
    def get(self, path: str): ...


@dataclass(frozen=True)
class SpotEligibility:
    allowed: bool
    reason: str
    symbol: str
    is_spot: bool


def _find_market(payload: object, symbol: str) -> Mapping[str, object] | None:
    wanted = (symbol or "").strip().upper()
    if not wanted or not isinstance(payload, Mapping):
        return None

    candidates: list[object] = []
    for key in ("result", "data"):
        value = payload.get(key)
        if isinstance(value, list):
            candidates.extend(value)
        elif isinstance(value, Mapping):
            for nested_key in ("markets", "symbols", "items"):
                nested = value.get(nested_key)
                if isinstance(nested, list):
                    candidates.extend(nested)
                elif isinstance(nested, Mapping):
                    direct = nested.get(wanted)
                    if isinstance(direct, Mapping):
                        candidates.append(direct)

    for item in candidates:
        if not isinstance(item, Mapping):
            continue
        item_symbol = str(item.get("symbol") or item.get("name") or "").strip().upper()
        if item_symbol == wanted:
            return item
    return None


def parse_spot_eligibility(payload: object, symbol: str) -> SpotEligibility:
    wanted = (symbol or "").strip().upper()
    market = _find_market(payload, wanted)
    if market is None:
        return SpotEligibility(False, "active_market_symbol_missing", wanted, False)

    raw_spot = market.get("is_spot")
    if raw_spot is None:
        raw_spot = market.get("isSpot")
    is_spot = raw_spot is True or str(raw_spot).strip().lower() == "true"
    if not is_spot:
        return SpotEligibility(False, "market_not_spot", wanted, False)
    return SpotEligibility(True, "active_spot_market_confirmed_readonly", wanted, True)


def fetch_spot_eligibility(*, env: Mapping[str, str], client: GetClient, symbol: str) -> SpotEligibility:
    safety = evaluate_v5_safety(env)
    if not safety.allowed:
        raise RuntimeError(f"v5_safety_blocked:{safety.reason}")
    if not safety.spot_only:
        raise RuntimeError("spot_only_required")

    response = client.get(ACTIVE_MARKETS_PATH)
    if getattr(response, "status_code", None) != 200:
        raise RuntimeError("active_spot_market_http_failed")
    try:
        payload = response.json()
    except Exception as exc:
        raise RuntimeError("active_spot_market_json_failed") from exc

    result = parse_spot_eligibility(payload, symbol)
    if not result.allowed:
        raise RuntimeError(result.reason)
    return result

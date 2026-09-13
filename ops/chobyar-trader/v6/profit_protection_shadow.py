from __future__ import annotations

import json
import math
import os
import time
from dataclasses import asdict, dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

APP_DIR = Path(os.getenv("CHOBYAR_APP_DIR", "/opt/chobyar-trader"))
STATE_FILE = APP_DIR / "state" / "paper_state.json"
OUTPUT_FILE = APP_DIR / "logs" / "profit_protection_shadow.jsonl"
SYMBOL = os.getenv("SYMBOL", "BTCUSDT").strip().upper()
INTERVAL_SECONDS = float(os.getenv("SHADOW_SAMPLE_SECONDS", "5"))
ARM_PCT = 0.005
TRAIL_PCT = 0.003

if os.getenv("TRADING_MODE", "").strip().lower() != "paper":
    raise SystemExit("FAIL-CLOSED: shadow collector requires TRADING_MODE=paper")
if os.getenv("LIVE_TRADING_ENABLED", "").strip().lower() != "false":
    raise SystemExit("FAIL-CLOSED: shadow collector requires LIVE_TRADING_ENABLED=false")
if not math.isfinite(INTERVAL_SECONDS) or not 5 <= INTERVAL_SECONDS <= 10:
    raise SystemExit("FAIL-CLOSED: SHADOW_SAMPLE_SECONDS must be between 5 and 10")


def _positive(value: Any) -> float:
    try:
        number = float(value)
    except (TypeError, ValueError) as exc:
        raise ValueError("invalid numeric value") from exc
    if not math.isfinite(number) or number <= 0:
        raise ValueError("numeric value must be finite and positive")
    return number


def read_position(path: Path = STATE_FILE) -> tuple[float, float] | None:
    raw = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(raw, dict):
        raise ValueError("paper state must be an object")
    qty = float(raw.get("btc_qty", 0))
    if not math.isfinite(qty) or qty < 0:
        raise ValueError("invalid paper quantity")
    if qty == 0:
        return None
    return _positive(raw.get("entry_price")), qty


def fetch_public_mid(client: Any, symbol: str = SYMBOL) -> tuple[float, float, float]:
    response = client.get("/v1/depth", params={"symbol": symbol})
    response.raise_for_status()
    payload = response.json()
    if not isinstance(payload, dict) or payload.get("success") is False:
        raise ValueError("public depth response rejected")
    result = payload.get("result") or {}
    bids = result.get("bid") or result.get("bids") or []
    asks = result.get("ask") or result.get("asks") or []
    if not bids or not asks:
        raise ValueError("public depth is empty")
    price = lambda row: _positive(row.get("price") if isinstance(row, dict) else row[0])
    best_bid = max(price(row) for row in bids[:20])
    best_ask = min(price(row) for row in asks[:20])
    if best_ask <= best_bid:
        raise ValueError("crossed public depth")
    return best_bid, best_ask, (best_bid + best_ask) / 2


@dataclass(frozen=True)
class Observation:
    position_open: bool
    entry_price: float | None
    quantity: float
    best_bid: float | None
    best_ask: float | None
    mid: float | None
    return_pct: float | None
    peak_return_pct: float | None
    breakeven_armed: bool
    trailing_floor_pct: float | None
    breakeven_crossed: bool
    trailing_crossed: bool
    execution_authority: bool = False
    automatic_promotion: bool = False


class ShadowTracker:
    def __init__(self) -> None:
        self.entry_price: float | None = None
        self.peak_return_pct: float | None = None

    def observe(self, position: tuple[float, float] | None, market: tuple[float, float, float] | None) -> Observation:
        if position is None:
            self.entry_price = self.peak_return_pct = None
            return Observation(False, None, 0.0, None, None, None, None, None, False, None, False, False)
        entry_price, quantity = position
        if market is None:
            raise ValueError("market data required for an open position")
        best_bid, best_ask, mid = market
        if self.entry_price != entry_price:
            self.entry_price, self.peak_return_pct = entry_price, None
        current_return = best_bid / entry_price - 1.0
        self.peak_return_pct = current_return if self.peak_return_pct is None else max(self.peak_return_pct, current_return)
        armed = self.peak_return_pct >= ARM_PCT
        trailing_floor = self.peak_return_pct - TRAIL_PCT if armed else None
        return Observation(
            True, entry_price, quantity, best_bid, best_ask, mid, current_return,
            self.peak_return_pct, armed, trailing_floor,
            armed and current_return <= 0.0,
            trailing_floor is not None and current_return <= trailing_floor,
        )


def append_observation(path: Path, observation: Observation, *, event: str = "profit_protection_shadow_sample") -> None:
    record = {
        "ts": datetime.now(timezone.utc).isoformat(),
        "event": event,
        "mode": "shadow_observation_only",
        **asdict(observation),
    }
    path.parent.mkdir(parents=True, exist_ok=True)
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_APPEND, 0o600)
    with os.fdopen(fd, "a", encoding="utf-8") as stream:
        stream.write(json.dumps(record, separators=(",", ":")) + "\n")


def run_once(client: Any, tracker: ShadowTracker) -> Observation:
    position = read_position()
    market = fetch_public_mid(client) if position is not None else None
    observation = tracker.observe(position, market)
    append_observation(OUTPUT_FILE, observation)
    return observation


def main() -> None:
    import httpx

    tracker = ShadowTracker()
    with httpx.Client(
        base_url="https://api.wallex.ir",
        timeout=4.0,
        headers={"User-Agent": "ChobYar-Profit-Protection-Shadow/1"},
    ) as client:
        while True:
            started = time.monotonic()
            try:
                run_once(client, tracker)
            except Exception as exc:
                append_observation(
                    OUTPUT_FILE,
                    Observation(False, None, 0.0, None, None, None, None, None, False, None, False, False),
                    event="profit_protection_shadow_error_" + type(exc).__name__,
                )
            time.sleep(max(0.1, INTERVAL_SECONDS - (time.monotonic() - started)))


if __name__ == "__main__":
    main()

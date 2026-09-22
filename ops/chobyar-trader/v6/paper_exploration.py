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
AUDIT_FILE = APP_DIR / "logs" / "audit.jsonl"
STATE_FILE = APP_DIR / "state" / "paper_exploration_state.json"
OUTPUT_FILE = APP_DIR / "logs" / "paper_exploration.jsonl"
EXPLORATION_STRATEGY_VERSION = "v624-final-paper-candidate"
INTERVAL_SECONDS = 5.0
START_BALANCE = 10.0
POSITION_FRACTION = 0.25
FEE_RATE = 0.001
STOP_LOSS_PCT = 0.004
TAKE_PROFIT_PCT = 0.006
MAX_HOLD_SECONDS = 1800.0
EXIT_SCORE = -1.5
MIN_ENTRY_SCORE = 0.0
LOSS_COOLDOWN_SECONDS = 1800.0
STOP_LOSS_COOLDOWN_SECONDS = 3600.0
LOSS_STREAK_LIMIT = 2
LOSS_STREAK_COOLDOWN_SECONDS = 7200.0
MAX_ENTRY_SPREAD_PCT = 0.0012
MIN_ENTRY_ORDERBOOK_IMBALANCE = 0.0
MAX_ENTRY_ORDERBOOK_IMBALANCE = 0.20
MIN_ENTRY_TAPE_BUY_RATIO = 0.55
MAX_ENTRY_TAPE_BUY_RATIO = 0.85
LANE_THRESHOLDS = {"wide": -0.75, "balanced": 0.0, "selective": 0.25}

if os.getenv("TRADING_MODE", "").strip().lower() != "paper":
    raise SystemExit("FAIL-CLOSED: exploration requires TRADING_MODE=paper")
if os.getenv("LIVE_TRADING_ENABLED", "").strip().lower() != "false":
    raise SystemExit("FAIL-CLOSED: exploration requires LIVE_TRADING_ENABLED=false")


def _finite(value: Any) -> float | None:
    try:
        number = float(value)
    except (TypeError, ValueError):
        return None
    return number if math.isfinite(number) else None


@dataclass
class Lane:
    threshold: float
    cash: float = START_BALANCE
    quantity: float = 0.0
    entry_price: float | None = None
    entry_ts: float | None = None
    entry_score: float | None = None
    entry_spread_pct: float | None = None
    entry_orderbook_imbalance: float | None = None
    entry_tape_buy_ratio: float | None = None
    trades: int = 0
    cooldown_until: float | None = None
    loss_streak: int = 0
    last_exit_reason: str | None = None


def initial_state() -> dict[str, Any]:
    return {
        "last_ts": None,
        "last_score": None,
        "lanes": {name: asdict(Lane(value)) for name, value in LANE_THRESHOLDS.items()},
    }


def validate_cycle(row: dict[str, Any]) -> tuple[float, float, float] | None:
    if row.get("event") != "cycle":
        return None
    score, mid, epoch = _finite(row.get("score")), _finite(row.get("local_mid")), _finite(row.get("ts_epoch"))
    if epoch is None:
        raw_ts = row.get("ts")
        try:
            epoch = datetime.fromisoformat(str(raw_ts).replace("Z", "+00:00")).timestamp()
        except ValueError:
            return None
    if score is None or mid is None or mid <= 0:
        return None
    spread = _finite(row.get("spread_pct")) or 0.0
    if spread < 0 or spread > 0.02:
        return None
    return score, mid, epoch


def cooldown_for_exit(reason: str, pnl: float, next_loss_streak: int) -> float:
    if pnl > 0:
        return 0.0
    cooldown = STOP_LOSS_COOLDOWN_SECONDS if reason == "stop_loss" else LOSS_COOLDOWN_SECONDS
    if next_loss_streak >= LOSS_STREAK_LIMIT:
        cooldown = max(cooldown, LOSS_STREAK_COOLDOWN_SECONDS)
    return cooldown


def entry_quality(row: dict[str, Any], spread: float) -> dict[str, float | bool | None]:
    orderbook_imbalance = _finite(row.get("orderbook_imbalance"))
    tape_buy_ratio = _finite(row.get("tape_buy_ratio"))
    accepted = (
        spread <= MAX_ENTRY_SPREAD_PCT
        and orderbook_imbalance is not None
        and orderbook_imbalance >= MIN_ENTRY_ORDERBOOK_IMBALANCE
        and orderbook_imbalance <= MAX_ENTRY_ORDERBOOK_IMBALANCE
        and tape_buy_ratio is not None
        and tape_buy_ratio >= MIN_ENTRY_TAPE_BUY_RATIO
        and tape_buy_ratio <= MAX_ENTRY_TAPE_BUY_RATIO
    )
    return {
        "entry_quality_ok": accepted,
        "spread_pct": spread,
        "orderbook_imbalance": orderbook_imbalance,
        "tape_buy_ratio": tape_buy_ratio,
    }


def process_cycle(state: dict[str, Any], row: dict[str, Any]) -> list[dict[str, Any]]:
    values = validate_cycle(row)
    if values is None:
        return []
    score, mid, epoch = values
    last_ts = _finite(state.get("last_ts"))
    if last_ts is not None and epoch <= last_ts:
        return []
    last_score = _finite(state.get("last_score"))
    has_score_history = last_score is not None or last_ts is None
    state["last_ts"] = epoch
    spread = _finite(row.get("spread_pct")) or 0.0
    quality = entry_quality(row, spread)
    ask, bid = mid * (1 + spread / 2), mid * (1 - spread / 2)
    events: list[dict[str, Any]] = []
    for name, raw in state["lanes"].items():
        lane = Lane(**raw)
        cooldown_until = _finite(lane.cooldown_until)
        in_cooldown = cooldown_until is not None and epoch < cooldown_until
        crossed_threshold = score >= lane.threshold and (
            (last_score is None and has_score_history) or (last_score is not None and last_score < lane.threshold)
        )
        if (lane.quantity == 0 and crossed_threshold and score >= MIN_ENTRY_SCORE
                and not in_cooldown and quality["entry_quality_ok"]):
            notional = lane.cash * POSITION_FRACTION
            fee = notional * FEE_RATE
            lane.quantity = notional / ask
            lane.cash -= notional + fee
            lane.entry_price, lane.entry_ts, lane.entry_score = ask, epoch, score
            lane.entry_spread_pct = spread
            lane.entry_orderbook_imbalance = quality["orderbook_imbalance"]
            lane.entry_tape_buy_ratio = quality["tape_buy_ratio"]
            events.append({"event": "exploration_buy", "lane": name, "price": ask, "score": score, "fee": fee,
                           "cycle_ts": epoch, **quality})
        elif lane.quantity > 0 and lane.entry_price is not None and lane.entry_ts is not None:
            change = bid / lane.entry_price - 1
            reason = None
            if change <= -STOP_LOSS_PCT:
                reason = "stop_loss"
            elif change >= TAKE_PROFIT_PCT:
                reason = "take_profit"
            elif epoch - lane.entry_ts >= MAX_HOLD_SECONDS:
                reason = "max_hold"
            elif score <= EXIT_SCORE:
                reason = "score_exit"
            if reason:
                proceeds = lane.quantity * bid
                fee = proceeds * FEE_RATE
                cost = lane.quantity * lane.entry_price
                pnl = proceeds - fee - cost - cost * FEE_RATE
                entry_ts = lane.entry_ts
                entry_score = lane.entry_score
                entry_spread_pct = lane.entry_spread_pct
                entry_orderbook_imbalance = lane.entry_orderbook_imbalance
                entry_tape_buy_ratio = lane.entry_tape_buy_ratio
                lane.cash += proceeds - fee
                lane.quantity = 0.0
                lane.entry_price = lane.entry_ts = lane.entry_score = None
                lane.entry_spread_pct = lane.entry_orderbook_imbalance = lane.entry_tape_buy_ratio = None
                lane.trades += 1
                lane.loss_streak = lane.loss_streak + 1 if pnl <= 0 else 0
                cooldown_seconds = cooldown_for_exit(reason, pnl, lane.loss_streak)
                lane.cooldown_until = epoch + cooldown_seconds if cooldown_seconds else None
                lane.last_exit_reason = reason
                events.append({"event": "exploration_sell", "lane": name, "price": bid, "score": score,
                               "fee": fee, "pnl": pnl, "reason": reason,
                               "cycle_ts": epoch, "entry_ts": entry_ts, "entry_score": entry_score,
                               "entry_spread_pct": entry_spread_pct,
                               "entry_orderbook_imbalance": entry_orderbook_imbalance,
                               "entry_tape_buy_ratio": entry_tape_buy_ratio,
                               "cooldown_until": lane.cooldown_until, "loss_streak": lane.loss_streak})
        state["lanes"][name] = asdict(lane)
    state["last_score"] = score
    return events


def load_state(path: Path = STATE_FILE) -> dict[str, Any]:
    if not path.exists():
        return initial_state()
    data = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(data, dict) or set(data.get("lanes", {})) != set(LANE_THRESHOLDS):
        raise ValueError("invalid exploration state")
    data.setdefault("last_score", None)
    return data


def save_state(path: Path, state: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(".tmp")
    temporary.write_text(json.dumps(state, separators=(",", ":")), encoding="utf-8")
    os.chmod(temporary, 0o600)
    os.replace(temporary, path)


def append_events(path: Path, events: list[dict[str, Any]]) -> None:
    if not events:
        return
    path.parent.mkdir(parents=True, exist_ok=True)
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_APPEND, 0o600)
    with os.fdopen(fd, "a", encoding="utf-8") as stream:
        for event in events:
            record = {"ts": datetime.now(timezone.utc).isoformat(), "mode": "paper_exploration_only",
                      "strategy_version": EXPLORATION_STRATEGY_VERSION,
                      "execution_authority": False, "automatic_promotion": False, **event}
            stream.write(json.dumps(record, separators=(",", ":")) + "\n")


def run_once(state: dict[str, Any]) -> None:
    for line in AUDIT_FILE.read_text(encoding="utf-8").splitlines()[-600:]:
        row = json.loads(line)
        append_events(OUTPUT_FILE, process_cycle(state, row))
    save_state(STATE_FILE, state)


def main() -> None:
    state = load_state()
    while True:
        started = time.monotonic()
        try:
            run_once(state)
        except Exception as exc:
            append_events(OUTPUT_FILE, [{"event": "exploration_error", "error_type": type(exc).__name__}])
        time.sleep(max(0.1, INTERVAL_SECONDS - (time.monotonic() - started)))


if __name__ == "__main__":
    main()

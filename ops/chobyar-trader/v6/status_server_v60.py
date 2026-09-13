from __future__ import annotations

import json
import subprocess
import time
from pathlib import Path
from urllib.parse import urlparse

import status_server_v53 as v53

base = v53.base
_original_report = v53.public_report_payload
_original_handler = base.Handler

APP_DIR = Path("/opt/chobyar-trader")
STATE_FILE = APP_DIR / "state" / "paper_exploration_state.json"
LOG_FILE = APP_DIR / "logs" / "paper_exploration.jsonl"
AUDIT_FILE = APP_DIR / "logs" / "audit.jsonl"
ASSET_FILE = APP_DIR / "monitor" / "paper_exploration_monitor.js"


def _service_active(name: str) -> bool:
    result = subprocess.run(
        ["/usr/bin/systemctl", "is-active", "--quiet", name],
        stdin=subprocess.DEVNULL,
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
        timeout=2,
        check=False,
    )
    return result.returncode == 0


def _read_events() -> list[dict]:
    if not LOG_FILE.is_file():
        return []
    rows = []
    for line in LOG_FILE.read_text(encoding="utf-8", errors="replace").splitlines()[-5000:]:
        try:
            row = json.loads(line)
        except (TypeError, ValueError):
            continue
        if isinstance(row, dict) and row.get("lane") in {"wide", "balanced", "selective"}:
            rows.append(row)
    return rows


def _latest_mark_price() -> float | None:
    if not AUDIT_FILE.is_file():
        return None
    for line in reversed(AUDIT_FILE.read_text(encoding="utf-8", errors="replace").splitlines()[-600:]):
        try:
            row = json.loads(line)
            price = float(row.get("local_mid"))
        except (TypeError, ValueError, AttributeError):
            continue
        if row.get("event") == "cycle" and price > 0:
            return price
    return None


def public_exploration_projection() -> dict:
    now = time.time()
    projection = {
        "ok": False,
        "mode": "paper_exploration_only",
        "execution_authority": False,
        "automatic_promotion": False,
        "service_active": _service_active("chobyar-paper-exploration.service"),
        "stale": True,
        "age_seconds": None,
        "total_completed_trades": 0,
        "lanes": {},
    }
    try:
        state = json.loads(STATE_FILE.read_text(encoding="utf-8"))
        rows = _read_events()
        mark_price = _latest_mark_price()
        last_ts = float(state["last_ts"])
        projection["age_seconds"] = max(0.0, now - last_ts)
        projection["stale"] = projection["age_seconds"] > 120
        for name in ("wide", "balanced", "selective"):
            lane = state["lanes"][name]
            sells = [r for r in rows if r.get("lane") == name and r.get("event") == "exploration_sell"]
            wins = sum(1 for r in sells if float(r.get("pnl", 0)) > 0)
            losses = sum(1 for r in sells if float(r.get("pnl", 0)) <= 0)
            cash = float(lane["cash"])
            quantity = float(lane.get("quantity", 0))
            position_open = quantity > 0
            equity = cash if not position_open else (cash + quantity * mark_price if mark_price is not None else None)
            projection["lanes"][name] = {
                "threshold": float(lane["threshold"]),
                "cash": cash,
                "equity": equity,
                "mark_price": mark_price if position_open else None,
                "return_pct": (equity / 10.0 - 1.0) * 100.0 if equity is not None else None,
                "completed_trades": len(sells),
                "wins": wins,
                "losses": losses,
                "position_open": position_open,
            }
        projection["total_completed_trades"] = sum(
            lane["completed_trades"] for lane in projection["lanes"].values()
        )
        projection["ok"] = True
    except (OSError, KeyError, TypeError, ValueError, json.JSONDecodeError):
        pass
    return projection


def public_report_payload() -> dict:
    report = _original_report()
    report["paper_exploration"] = public_exploration_projection()
    report["report_version"] = 8
    return report


class Handler(_original_handler):
    def do_GET(self) -> None:
        if urlparse(self.path).path == "/monitor/paper_exploration_monitor.js":
            try:
                payload = ASSET_FILE.read_bytes()
            except OSError:
                self.send_json(503, {"ok": False, "error": "exploration_monitor_unavailable"})
                return
            self.send_response(200)
            self.send_header("Content-Type", "application/javascript; charset=utf-8")
            self.send_header("Content-Length", str(len(payload)))
            self.send_header("Cache-Control", "no-store")
            self.send_header("X-Content-Type-Options", "nosniff")
            self.end_headers()
            self.wfile.write(payload)
            return
        super().do_GET()


base.public_report_payload = public_report_payload
base.Handler = Handler


if __name__ == "__main__":
    env = base.read_auth_env()
    port = int(env.get("STATUS_PORT", "8787"))
    base.ThreadingHTTPServer(("0.0.0.0", port), Handler).serve_forever()

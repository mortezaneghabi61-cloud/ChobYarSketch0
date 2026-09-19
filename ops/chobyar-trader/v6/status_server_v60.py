from __future__ import annotations

import json
import html
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
CURRENT_EXPLORATION_STRATEGY_VERSION = "v622-quality-gates"


def _service_active(name: str) -> bool:
    try:
        result = subprocess.run(
            ["/usr/bin/systemctl", "is-active", "--quiet", name],
            stdin=subprocess.DEVNULL,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            timeout=2,
            check=False,
        )
    except (OSError, subprocess.SubprocessError):
        return False
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


def _cooldown_remaining(now: float, value) -> float:
    try:
        cooldown_until = float(value)
    except (TypeError, ValueError):
        return 0.0
    return max(0.0, cooldown_until - now)


def public_exploration_projection() -> dict:
    now = time.time()
    projection = {
        "ok": False,
        "mode": "paper_exploration_only",
        "strategy_version": CURRENT_EXPLORATION_STRATEGY_VERSION,
        "execution_authority": False,
        "automatic_promotion": False,
        "service_active": _service_active("chobyar-paper-exploration.service"),
        "stale": True,
        "age_seconds": None,
        "total_completed_trades": 0,
        "current_strategy_completed_trades": 0,
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
            current_sells = [
                r for r in sells if r.get("strategy_version") == CURRENT_EXPLORATION_STRATEGY_VERSION
            ]
            wins = sum(1 for r in sells if float(r.get("pnl", 0)) > 0)
            losses = sum(1 for r in sells if float(r.get("pnl", 0)) <= 0)
            current_wins = sum(1 for r in current_sells if float(r.get("pnl", 0)) > 0)
            current_losses = sum(1 for r in current_sells if float(r.get("pnl", 0)) <= 0)
            cash = float(lane["cash"])
            quantity = float(lane.get("quantity", 0))
            position_open = quantity > 0
            equity = cash if not position_open else (cash + quantity * mark_price if mark_price is not None else None)
            cooldown_remaining = _cooldown_remaining(now, lane.get("cooldown_until"))
            projection["lanes"][name] = {
                "threshold": float(lane["threshold"]),
                "cash": cash,
                "equity": equity,
                "mark_price": mark_price if position_open else None,
                "return_pct": (equity / 10.0 - 1.0) * 100.0 if equity is not None else None,
                "completed_trades": len(sells),
                "wins": wins,
                "losses": losses,
                "current_completed_trades": len(current_sells),
                "current_wins": current_wins,
                "current_losses": current_losses,
                "cooldown_remaining_seconds": cooldown_remaining,
                "loss_streak": int(lane.get("loss_streak", 0) or 0),
                "last_exit_reason": lane.get("last_exit_reason"),
                "position_open": position_open,
            }
        projection["total_completed_trades"] = sum(
            lane["completed_trades"] for lane in projection["lanes"].values()
        )
        projection["current_strategy_completed_trades"] = sum(
            lane["current_completed_trades"] for lane in projection["lanes"].values()
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


def exploration_html() -> bytes:
    data = public_exploration_projection()
    cards = []
    labels = {"wide": "گسترده", "balanced": "متعادل", "selective": "انتخابی"}
    for name in ("wide", "balanced", "selective"):
        lane = data.get("lanes", {}).get(name, {})
        value = lane.get("return_pct")
        return_text = "—" if value is None else f"{float(value):.3f}%"
        equity = lane.get("equity")
        equity_text = "—" if equity is None else f"{float(equity):.4f} USDT"
        cooldown_remaining = float(lane.get("cooldown_remaining_seconds") or 0)
        cooldown_text = "فعال" if cooldown_remaining > 0 else "آماده"
        cards.append(f'''<article><header><strong>{labels[name]}</strong><b>{"باز" if lane.get("position_open") else "بسته"}</b></header>
<h2 class="{'pos' if value is not None and value >= 0 else 'neg'}">{html.escape(return_text)}</h2>
<dl><div><dt>معامله کامل</dt><dd>{lane.get("completed_trades", "—")}</dd></div>
<div><dt>نسخه جدید</dt><dd>{lane.get("current_wins", "—")} / {lane.get("current_losses", "—")}</dd></div>
<div><dt>برد / باخت</dt><dd>{lane.get("wins", "—")} / {lane.get("losses", "—")}</dd></div>
<div><dt>ترمز ضرر</dt><dd>{html.escape(cooldown_text)}</dd></div>
<div><dt>ارزش کل مجازی</dt><dd>{html.escape(equity_text)}</dd></div>
<div><dt>وجه نقد</dt><dd>{float(lane.get("cash", 0)):.4f} USDT</dd></div></dl></article>''')
    health = "فعال و تازه" if data.get("service_active") and not data.get("stale") else "هشدار: سرویس یا داده کهنه"
    document = f'''<!doctype html><html lang="fa" dir="rtl"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><meta http-equiv="refresh" content="5"><style>
*{{box-sizing:border-box}}body{{margin:0;padding:16px;background:#101b3c;color:#eef4ff;font-family:system-ui,sans-serif}}.top{{display:flex;justify-content:space-between;gap:10px;align-items:center;flex-wrap:wrap}}.badge{{color:#63f0bb}}.grid{{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:12px;margin-top:14px}}article{{padding:14px;border:1px solid #ffffff25;border-radius:14px;background:#ffffff0b}}header,dl div{{display:flex;justify-content:space-between;gap:10px}}h1{{font-size:20px;margin:0}}h2{{font-size:27px}}.pos{{color:#61efb4}}.neg{{color:#ff8194}}dl{{margin:0}}dl div{{padding:7px 0;border-top:1px solid #ffffff16}}dt{{opacity:.72}}dd{{margin:0;font-weight:700}}footer{{text-align:center;opacity:.72;margin-top:14px}}@media(max-width:700px){{.grid{{grid-template-columns:1fr}}}}
</style></head><body><div class="top"><div><h1>معاملات آزمایشی سریع</h1><small>کاملاً مجازی و جدا از تریدر اصلی · {html.escape(str(data.get("strategy_version", "")))}</small></div><div class="badge">{html.escape(health)} · {data.get("current_strategy_completed_trades", 0)} معامله نسخه جدید / {data.get("total_completed_trades", 0)} کل</div></div><section class="grid">{"".join(cards)}</section><footer>SHADOW ONLY · بدون اختیار اجرای واقعی یا ارتقای خودکار</footer></body></html>'''
    return document.encode("utf-8")


class Handler(_original_handler):
    def do_GET(self) -> None:
        path = urlparse(self.path).path
        if path == "/monitor/paper-exploration/":
            payload = exploration_html()
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(payload)))
            self.send_header("Cache-Control", "no-store")
            self.send_header("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; frame-ancestors 'self'")
            self.send_header("X-Content-Type-Options", "nosniff")
            self.end_headers()
            self.wfile.write(payload)
            return
        if path == "/monitor/paper_exploration_monitor.js":
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

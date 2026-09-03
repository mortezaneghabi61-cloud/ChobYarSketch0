from __future__ import annotations

import importlib.util
import sys
from pathlib import Path

TRADER = Path('/opt/chobyar-trader/app/trader.py')
spec = importlib.util.spec_from_file_location('chobyar_trader_v3', TRADER)
assert spec and spec.loader
m = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = m
spec.loader.exec_module(m)

assert m.TRADING_MODE == 'paper'
assert m.LIVE_ENABLED is False
assert 0 < m.MAX_POSITION_PCT <= 0.40
assert 0 < m.STOP_LOSS_PCT < m.TAKE_PROFIT_PCT

local = m.LocalSnapshot(
    best_bid=100.0, best_ask=100.1, mid=100.05, spread_pct=0.001,
    imbalance=0.25, trade_prices=[101,101,101,101,100,100,100,100],
    tape_buy_ratio=0.70, tape_notional=1000.0,
)
global_ = m.GlobalSnapshot(price=100.0, change_24h=0.02, sources=['test-a','test-b'], dispersion_pct=0.001)
signal, score, votes = m.strategy(local, global_)
assert signal == 'BUY', (signal, score, votes)
assert score >= m.ENTRY_THRESHOLD
assert len(votes) == 5

class FakeBroker:
    def __init__(self):
        self.state = m.PaperState(cash_usdt=7.5, btc_qty=0.025, entry_price=100.0, entry_cost_usdt=2.5,
                                  day_start_equity=10.0, day_key='x', starting_equity=10.0, peak_equity=10.0)
    def roll_day_if_needed(self, price):
        self.state.day_key = m.datetime.now(m.timezone.utc).date().isoformat()
    def daily_loss_hit(self, price):
        return False
    def cooldown_active(self):
        return False

risk_local = m.LocalSnapshot(98.0, 98.1, 98.05, 0.001, 0.0, [98]*8, 0.5, 100.0)
action, reason = m.RiskManager.decide('WAIT', risk_local, FakeBroker())
assert action == 'SELL' and 'stop-loss' in reason, (action, reason)

import tempfile
with tempfile.TemporaryDirectory() as td:
    m.STATE_FILE = Path(td) / 'paper_state.json'
    m.AUDIT_FILE = Path(td) / 'audit.jsonl'
    b = m.PaperBroker()
    assert b.buy(100.0, 'selftest') is True
    assert b.state.btc_qty > 0 and b.state.trades == 1
    assert b.sell_all(103.0, 'selftest') is True
    assert b.state.closed_trades == 1 and b.state.wins == 1
    assert b.state.realized_pnl > 0
    b.mark_equity(100.0)
    assert 0.0 <= b.state.max_drawdown_pct < 1.0

print('SELFTEST_OK')

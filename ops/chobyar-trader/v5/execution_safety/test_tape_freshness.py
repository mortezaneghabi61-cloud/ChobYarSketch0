import importlib.util
import math
import os
import sys
import tempfile
import types
import unittest
from datetime import datetime, timezone
from pathlib import Path
from unittest.mock import patch


class TapeFreshnessTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.app = tempfile.TemporaryDirectory()
        fake = types.ModuleType('httpx')
        fake.Client = lambda *a, **kw: object()
        fake.Response = object
        env = {'CHOBYAR_APP_DIR':cls.app.name,'TRADING_MODE':'paper','LIVE_TRADING_ENABLED':'false'}
        spec = importlib.util.spec_from_file_location('freshness_trader_under_test',Path(__file__).with_name('trader.py'))
        cls.m = importlib.util.module_from_spec(spec)
        sys.modules[spec.name] = cls.m
        with patch.dict(os.environ,env,clear=True),patch.dict(sys.modules,{'httpx':fake}):
            spec.loader.exec_module(cls.m)

    @classmethod
    def tearDownClass(cls):
        cls.app.cleanup()

    def market(self, age=0, mid=100):
        return types.SimpleNamespace(best_bid=mid-.01,best_ask=mid+.01,mid=mid,
            spread_pct=.0002,imbalance=.5,prices=[101.0]*10+[100.0]*10,
            buy_ratio=.9,global_price=mid,global_change=.02,
            global_sources=['kucoin','gateio'],global_dispersion_pct=0,
            tape_age_seconds=age)

    def broker(self, qty=0):
        return types.SimpleNamespace(state=types.SimpleNamespace(btc_qty=qty,
            entry_price=100 if qty else None,day_start_equity=10),equity=lambda _:10)

    def trades(self, ages):
        return {'latestTrades':[{'symbol':'BTCUSDT','price':100+i/100,
            'isBuyOrder':bool(i%2),'timestamp':datetime.fromtimestamp(1000000-age,timezone.utc).isoformat()}
            for i,age in enumerate(ages)]}

    def test_stale_tape_cannot_create_buy_even_with_strong_consensus(self):
        votes=[{'available':True,'contribution':1.0} for _ in range(6)]
        self.assertEqual(self.m.supervise(self.market(age=646),self.broker(),votes)[0],'WAIT')

    def test_fresh_tape_preserves_existing_buy_path(self):
        votes=[{'available':True,'contribution':1.0} for _ in range(6)]
        self.assertEqual(self.m.supervise(self.market(),self.broker(),votes)[0],'BUY')

    def test_missing_invalid_future_and_boundary_ages(self):
        votes=[{'available':True,'contribution':1.0} for _ in range(6)]
        for age in [None,float('nan'),float('inf'),-6,301]:
            with self.subTest(age=age):
                self.assertEqual(self.m.supervise(self.market(age),self.broker(),votes)[0],'WAIT')
        for age in [-5,0,300]:
            with self.subTest(age=age):
                self.assertEqual(self.m.supervise(self.market(age),self.broker(),votes)[0],'BUY')

    def test_stale_tape_does_not_block_protective_exits(self):
        for mid,reason in [(98,'stop loss'),(104,'take profit')]:
            for age in [None,646,float('nan')]:
                with self.subTest(mid=mid,age=age):
                    market=self.market(age,mid);market.spread_pct=.01
                    result=self.m.supervise(market,self.broker(.025),[])
                    self.assertEqual(result[0],'SELL')
                    self.assertEqual(result[2],reason)

    def test_stale_momentum_and_flow_are_unavailable_not_bullish_votes(self):
        votes={v['agent']:v for v in self.m.agent_votes(self.market(646))}
        for name in ['momentum','tape_order_flow']:
            self.assertFalse(votes[name]['available'])
            self.assertEqual(votes[name]['contribution'],0)
        self.assertTrue(votes['order_book']['available'])

    def test_parser_sorts_by_time_and_filters_old_rows(self):
        source=self.trades([20,0,10]+list(range(30,120,10))+[500])
        prices,ratio,age=self.m.parse_recent_tape(source,1000000)
        self.assertEqual(age,0)
        self.assertEqual(prices[:3],[100.01,100.02,100.0])
        self.assertEqual(len(prices),12)
        self.assertEqual(ratio,.5)

    def test_one_fresh_trade_cannot_rehabilitate_old_signal_window(self):
        prices,ratio,age=self.m.parse_recent_tape(self.trades([0]+list(range(600,620))),1000000)
        market=self.market(age);market.prices=prices;market.buy_ratio=ratio
        self.assertEqual(len(prices),1)
        self.assertFalse(self.m.tape_current(market))

    def test_invalid_rows_fail_closed(self):
        for field,value in [('timestamp','bad'),('timestamp','2026-01-01T00:00:00'),
                            ('price','NaN'),('price',0),('isBuyOrder','false'),('symbol','ETHUSDT')]:
            with self.subTest(field=field,value=value):
                rows=self.trades(range(20));rows['latestTrades'][0][field]=value
                self.assertEqual(self.m.parse_recent_tape(rows,1000000),([],0.5,None))

    def test_no_recent_tape_keeps_quote_available_to_exit(self):
        class Client:
            def get(inner,path,params):
                data={'result':{'bid':[['97.9','1']],'ask':[['98.1','1']]}} if path=='/v1/depth' else {'result':self.trades(range(600,620))}
                return types.SimpleNamespace(raise_for_status=lambda:None,json=lambda:data)
        with patch.object(self.m,'LOCAL',Client()),patch.object(self.m.time,'time',return_value=1000000),patch.object(self.m,'global_snapshot',return_value=(98,0,['test'],0)):
            market=self.m.snapshot()
        votes=self.m.agent_votes(market)
        self.assertFalse(self.m.tape_current(market))
        self.assertEqual(self.m.supervise(market,self.broker(.025),votes)[2],'stop loss')


if __name__=='__main__':
    unittest.main()

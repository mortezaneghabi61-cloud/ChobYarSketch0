import importlib.util
import json
import os
import sys
import tempfile
import types
import unittest
from pathlib import Path
from unittest.mock import patch


class EntryEconomicsTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.app = tempfile.TemporaryDirectory()
        fake = types.ModuleType('httpx')
        fake.Client = lambda *a, **kw: object()
        fake.Response = object
        spec = importlib.util.spec_from_file_location(
            'economics_trader_under_test', Path(__file__).with_name('trader.py'))
        cls.m = importlib.util.module_from_spec(spec)
        sys.modules[spec.name] = cls.m
        env = {'CHOBYAR_APP_DIR': cls.app.name, 'TRADING_MODE': 'paper',
               'LIVE_TRADING_ENABLED': 'false'}
        with patch.dict(os.environ, env, clear=True), patch.dict(sys.modules, {'httpx': fake}):
            spec.loader.exec_module(cls.m)

    @classmethod
    def tearDownClass(cls):
        cls.app.cleanup()

    def market(self, mid=100, spread=.002):
        return self.m.Market(
            mid * (1-spread/2), mid * (1+spread/2), mid, spread,
            .5, [mid] * 20, .9, mid, .02, ['a', 'b'], 0, 0)

    def broker(self, qty=0):
        return types.SimpleNamespace(state=types.SimpleNamespace(
            btc_qty=qty, entry_price=100 if qty else None, day_start_equity=10),
            equity=lambda _: 10)

    def votes(self):
        return [{'available': True, 'contribution': 1} for _ in range(6)]

    def test_positive_gross_target_with_net_loss_cannot_open_position(self):
        # 100 + 2 paid to buy; only 103 - 2.06 returned at a 3% target,
        # even before spread. Both fee settings are accepted by this runtime.
        with patch.object(self.m, 'FEE_PCT', .02):
            action, _, reason = self.m.supervise(self.market(), self.broker(), self.votes())
        self.assertEqual(action, 'WAIT')
        self.assertEqual(reason, 'target does not cover modeled trading costs')

    def test_normal_fee_preserves_eligible_consensus_entry(self):
        with patch.object(self.m, 'FEE_PCT', .002):
            self.assertEqual(self.m.supervise(self.market(), self.broker(), self.votes())[0], 'BUY')

    def test_exact_break_even_is_not_a_positive_net_target(self):
        with patch.object(self.m, 'FEE_PCT', 3/203):
            action, _, reason = self.m.supervise(self.market(spread=0), self.broker(), self.votes())
        self.assertEqual(action, 'WAIT')
        self.assertEqual(reason, 'target does not cover modeled trading costs')

    def test_cost_guard_cannot_block_protective_or_consensus_exit(self):
        with patch.object(self.m, 'FEE_PCT', .02):
            for mid, votes, reason in [(98, [], 'stop loss'), (104, [], 'take profit'),
                                       (100, [{'available': True, 'contribution': -1}] * 6, 'consensus exit')]:
                with self.subTest(reason=reason):
                    result = self.m.supervise(self.market(mid), self.broker(.025), votes)
                    self.assertEqual((result[0], result[2]), ('SELL', reason))

    def test_estimate_matches_actual_paper_round_trip_at_each_exit_threshold(self):
        market = self.market()
        for fee in [0, .002, .02]:
            with self.subTest(fee=fee), patch.object(self.m, 'FEE_PCT', fee):
                estimated = self.m.entry_economics(market)
                for movement, key in [(self.m.TAKE_PROFIT_PCT, 'target_net_return'),
                                      (-self.m.STOP_LOSS_PCT, 'stop_net_return')]:
                    with tempfile.TemporaryDirectory() as tmp, \
                            patch.object(self.m, 'STATE_FILE', Path(tmp) / 'paper.json'), \
                            patch.object(self.m, 'FORWARD_FILE', Path(tmp) / 'forward.json'):
                        broker = self.m.Broker()
                        start = broker.state.cash_usdt
                        self.assertTrue(broker.buy(market.best_ask))
                        spent = start - broker.state.cash_usdt
                        exit_mid = market.best_ask * (1 + movement)
                        exit_bid = exit_mid * (market.best_bid / market.mid)
                        self.assertTrue(broker.sell(exit_bid, 'test'))
                        self.assertAlmostEqual(estimated[key], broker.state.realized_pnl / spent, places=12)

    def test_break_even_win_rate_is_payoff_threshold_not_predicted_probability(self):
        with patch.object(self.m, 'FEE_PCT', .002):
            estimate = self.m.entry_economics(self.market(spread=0))
        # Independent cash ledger: buy total 100.20; target receipt 102.794;
        # stop receipt 98.303. Gain 2.594; loss 1.897.
        self.assertAlmostEqual(estimate['target_net_return'], 2.594/100.2, places=12)
        self.assertAlmostEqual(estimate['stop_net_return'], -1.897/100.2, places=12)
        self.assertAlmostEqual(estimate['target_stop_break_even_win_rate'], 1.897/4.491, places=12)
        self.assertEqual(estimate['model'], 'paper_fee_and_unchanged_relative_spread_v1')
        self.assertNotIn('predicted_win_rate', estimate)

    def test_higher_fees_and_spread_lower_net_target(self):
        results = []
        for fee, spread in [(0, 0), (.002, 0), (.002, .006), (.02, .006)]:
            with patch.object(self.m, 'FEE_PCT', fee):
                results.append(self.m.entry_economics(self.market(spread=spread))['target_net_return'])
        self.assertEqual(results, sorted(results, reverse=True))
        self.assertLess(results[-1], 0)

    def test_invalid_input_does_not_create_finite_looking_estimate(self):
        for fee in [float('nan'), float('inf'), -.01, 1, True]:
            with self.subTest(fee=fee), patch.object(self.m, 'FEE_PCT', fee):
                self.assertFalse(self.m.entry_economics(self.market())['valid'])
                self.assertEqual(self.m.supervise(self.market(), self.broker(), self.votes())[0], 'WAIT')
        for field, value in [('best_bid', 0), ('mid', float('nan')), ('best_ask', 99)]:
            market = vars(self.market()).copy()
            market[field] = value
            self.assertFalse(self.m.entry_economics(types.SimpleNamespace(**market))['valid'])
        market = vars(self.market()).copy()
        market.update(best_bid=1e-300, mid=1e300, best_ask=1e300)
        self.assertFalse(self.m.entry_economics(types.SimpleNamespace(**market))['valid'])

    def test_cycle_log_exposes_costs_without_changing_state(self):
        with tempfile.TemporaryDirectory() as tmp, \
                patch.object(self.m, 'STATE_FILE', Path(tmp) / 'paper.json'), \
                patch.object(self.m, 'FORWARD_FILE', Path(tmp) / 'forward.json'), \
                patch.object(self.m, 'snapshot', return_value=self.market()), \
                patch.object(self.m, 'FEE_PCT', .02):
            broker = self.m.Broker()
            with patch.object(self.m, 'agent_votes', return_value=self.votes()), \
                    patch.object(self.m.AUDIT, 'write') as audit:
                self.m.run_once(broker)
            cycle = next(c.kwargs for c in audit.call_args_list if c.args[0] == 'cycle')
            self.assertEqual(cycle['action'], 'WAIT')
            self.assertFalse(cycle['executed'])
            self.assertLess(cycle['entry_economics']['target_net_return'], 0)
            self.assertEqual(broker.state.orders, 0)
            json.dumps(cycle, allow_nan=False)


if __name__ == '__main__':
    unittest.main()

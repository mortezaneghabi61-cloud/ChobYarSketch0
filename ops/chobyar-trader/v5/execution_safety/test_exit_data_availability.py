import importlib.util
import os
import sys
import tempfile
import types
import unittest
from pathlib import Path
from unittest.mock import patch


class PublicDataError(Exception):
    pass


class ExitDataAvailabilityTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.app = tempfile.TemporaryDirectory()
        fake = types.ModuleType('httpx')
        fake.Client = lambda *a, **kw: object()
        fake.Response = object
        fake.HTTPError = PublicDataError
        spec = importlib.util.spec_from_file_location(
            'exit_data_trader_under_test', Path(__file__).with_name('trader.py'))
        cls.m = importlib.util.module_from_spec(spec)
        sys.modules[spec.name] = cls.m
        env = {'CHOBYAR_APP_DIR': cls.app.name, 'TRADING_MODE': 'paper',
               'LIVE_TRADING_ENABLED': 'false'}
        with patch.dict(os.environ, env, clear=True), patch.dict(sys.modules, {'httpx': fake}):
            spec.loader.exec_module(cls.m)

    @classmethod
    def tearDownClass(cls):
        cls.app.cleanup()

    def client(self, failure=PublicDataError, bid='97.9', ask='98.1', depth_error=False):
        calls = []
        class Client:
            def get(inner, path, params):
                calls.append(path)
                if path == '/v1/trades':
                    raise failure('untrusted response detail must not be logged')
                if depth_error:
                    raise PublicDataError('book unavailable')
                return types.SimpleNamespace(raise_for_status=lambda: None,
                    json=lambda: {'result': {'bid': [[bid, '1']], 'ask': [[ask, '1']]}})
        return Client(), calls

    def test_tape_network_failure_still_allows_stop_loss_with_valid_book(self):
        client, _ = self.client()
        with patch.object(self.m, 'LOCAL', client), \
                patch.object(self.m, 'global_snapshot', return_value=(None, None, [], None)):
            market = self.m.snapshot()
        broker = types.SimpleNamespace(state=types.SimpleNamespace(btc_qty=.025, entry_price=100))
        action, _, reason = self.m.supervise(market, broker, [])
        self.assertEqual((action, reason), ('SELL', 'stop loss'))

    def test_tape_failure_keeps_take_profit_available_and_blocks_new_buy(self):
        client, _ = self.client(bid='103.9', ask='104.1')
        with patch.object(self.m, 'LOCAL', client), \
                patch.object(self.m, 'global_snapshot', return_value=(None, None, [], None)):
            market = self.m.snapshot()
        open_broker = types.SimpleNamespace(state=types.SimpleNamespace(btc_qty=.025, entry_price=100))
        flat_broker = types.SimpleNamespace(state=types.SimpleNamespace(btc_qty=0, entry_price=None))
        self.assertEqual(self.m.supervise(market, open_broker, [])[2], 'take profit')
        votes = [{'available': True, 'contribution': 1}] * 6
        self.assertEqual(self.m.supervise(market, flat_broker, votes)[0], 'WAIT')
        self.assertFalse(self.m.tape_current(market))

    def test_invalid_json_api_failure_and_http_failure_remain_tape_unavailable(self):
        for error in [PublicDataError, ValueError, RuntimeError]:
            client, calls = self.client(failure=error)
            with self.subTest(error=error), patch.object(self.m, 'LOCAL', client), \
                    patch.object(self.m.AUDIT, 'write') as audit:
                bid, ask, _, _, prices, ratio, age = self.m.local_snapshot()
            self.assertEqual((bid, ask), (97.9, 98.1))
            self.assertEqual((prices, ratio, age), ([], .5, None))
            self.assertEqual(calls, ['/v1/trades', '/v1/depth'])
            self.assertEqual(audit.call_args.args, ('local_tape_error',))
            self.assertEqual(audit.call_args.kwargs, {'error': error.__name__})

    def test_quote_is_fetched_after_successful_tape_request_too(self):
        calls = []
        class Client:
            def get(inner, path, params):
                calls.append(path)
                result = {'latestTrades': []} if path == '/v1/trades' else {
                    'bid': [['97.9', '1']], 'ask': [['98.1', '1']]}
                return types.SimpleNamespace(raise_for_status=lambda: None,
                                             json=lambda: {'result': result})
        with patch.object(self.m, 'LOCAL', Client()):
            self.m.local_snapshot()
        self.assertEqual(calls, ['/v1/trades', '/v1/depth'])

    def test_unavailable_or_crossed_book_never_becomes_executable_quote(self):
        for kwargs in [{'depth_error': True}, {'bid': '100', 'ask': '99'}]:
            client, _ = self.client(**kwargs)
            with self.subTest(kwargs=kwargs), patch.object(self.m, 'LOCAL', client):
                with self.assertRaises((PublicDataError, RuntimeError)):
                    self.m.local_snapshot()

    def test_real_paper_exit_executes_in_run_once_during_tape_outage(self):
        client, _ = self.client()
        with tempfile.TemporaryDirectory() as tmp, \
                patch.object(self.m, 'STATE_FILE', Path(tmp) / 'paper.json'), \
                patch.object(self.m, 'FORWARD_FILE', Path(tmp) / 'forward.json'), \
                patch.object(self.m, 'LOCAL', client), \
                patch.object(self.m, 'global_snapshot', return_value=(None, None, [], None)):
            broker = self.m.Broker()
            self.assertTrue(broker.buy(100))
            self.m.run_once(broker)
            self.assertEqual(broker.state.btc_qty, 0)
            self.assertEqual(broker.state.closed_trades, 1)
            self.assertLess(broker.state.realized_pnl, 0)


if __name__ == '__main__':
    unittest.main()

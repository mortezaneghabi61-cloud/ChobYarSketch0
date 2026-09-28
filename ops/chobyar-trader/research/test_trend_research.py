import contextlib
import io
import json
import math
import random
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import trend_research as m


def row(i,o=100,h=100,low=100,c=100):
    return [i*3600,o,h,low,c,1.0]


def payload(rows):
    return {'s':'ok',**{k:[r[i] for r in rows] for i,k in enumerate(('t','o','h','l','c','v'))}}


class DataTests(unittest.TestCase):
    def test_out_of_order_data_sorted_and_closed_window_clipped(self):
        rows=[row(2),row(0),row(1)]
        self.assertEqual(m.parse_payload(payload(rows),0,7200),[row(0),row(1)])

    def test_missing_hour_fails_closed(self):
        with self.assertRaises(m.DataError):m.parse_payload(payload([row(0),row(2)]),0,10800)

    def test_bad_data_rejected(self):
        for bad in [row(0,o=0),row(0,h=99),row(0,c=float('nan')),[0,True,100,100,100,1]]:
            with self.subTest(bad=bad),self.assertRaises(m.DataError):
                m.parse_payload(payload([bad]),0,3600)

    def test_conflicting_duplicate_and_wrong_array_length_rejected(self):
        with self.assertRaises(m.DataError):m.parse_payload(payload([row(0),row(0,h=101)]),0,3600)
        p=payload([row(0)]);p['c']=[]
        with self.assertRaises(m.DataError):m.parse_payload(p,0,3600)

    def test_fetch_uses_only_documented_public_history(self):
        urls=[]
        def request(url):
            urls.append(url)
            return payload([row(0),row(1)])
        with patch.object(m.time,'sleep'),contextlib.redirect_stdout(io.StringIO()):
            rows=m.fetch_candles(0,7200,request)
        self.assertEqual(len(rows),2)
        self.assertEqual(urls,['https://api.wallex.ir/v1/udf/history?symbol=BTCUSDT&resolution=60&from=0&to=7200'])


class TradingTests(unittest.TestCase):
    def test_signal_cannot_see_current_or_future_bars(self):
        rows=[row(i,o=100+i,h=100+i,low=100+i,c=100+i) for i in range(100)]
        first=m.trend_signals(rows)
        changed=rows[:70]+[row(i,o=1,h=1,low=1,c=1) for i in range(70,100)]
        self.assertEqual(first[:71],m.trend_signals(changed)[:71])
        self.assertTrue(first[70])

    def test_both_stop_and_target_touched_uses_stop(self):
        result=m.simulate([row(0,h=104,low=98)],[True],0,1,0,0)
        self.assertEqual(result['exits'][0]['reason'],'stop_loss')
        self.assertAlmostEqual(result['net_pnl'],-.0375)

    def test_gap_stop_fills_at_worse_open(self):
        rows=[row(0),row(1,o=90,h=91,low=89,c=90)]
        result=m.simulate(rows,[True,True],0,2,0,0)
        self.assertEqual(result['exits'][0]['reason'],'gap_stop')
        self.assertAlmostEqual(result['net_pnl'],-.25)

    def test_fees_on_both_sides_and_haircut(self):
        result=m.simulate([row(0),row(1)],[True,True],0,2,.002,.001,'hold')
        shares=2.5/100.1
        proceeds=shares*99.9
        expected=-2.5-.005+proceeds*.998
        self.assertAlmostEqual(result['net_pnl'],expected)
        self.assertAlmostEqual(result['fees'],.005+proceeds*.002)
        self.assertLess(result['net_pnl'],0)

    def test_cash_is_exactly_flat(self):
        result=m.simulate([row(0,h=120,low=80)],[True],0,1,.004,.002,'cash')
        self.assertEqual(result['ending_equity'],10)
        self.assertEqual(result['closed_trades'],0)

    def test_no_reentry_during_loss_cooldown(self):
        rows=[row(0,h=104,low=98)]+[row(i) for i in range(1,7)]
        result=m.simulate(rows,[True]*7,0,7,0,0)
        self.assertEqual(result['closed_trades'],1)

    def test_trend_exit_executes_at_open_not_future_close(self):
        rows=[row(0),row(1,o=101,h=150,low=100,c=140)]
        result=m.simulate(rows,[True,False],0,2,0,0)
        self.assertEqual(result['exits'][0]['reason'],'trend_exit')
        self.assertAlmostEqual(result['net_pnl'],.025)

    def test_ledger_reconciliation_across_varied_price_paths(self):
        rng=random.Random(20260928)
        rows=[];price=100
        for i in range(1000):
            close=price*math.exp(rng.gauss(0,.012))
            rows.append(row(i,price,max(price,close)*1.005,min(price,close)*.995,close))
            price=close
        signals=m.trend_signals(rows)
        for fee,slip in [(0,0),(.002,.001),(.004,.002)]:
            for strategy in ['cash','hold','trend']:
                result=m.simulate(rows,signals,51,len(rows),fee,slip,strategy)
                self.assertAlmostEqual(result['ending_equity']-10,sum(e['pnl'] for e in result['exits']))
                self.assertGreater(result['ending_equity'],0)


class RecoveryTests(unittest.TestCase):
    def test_narrow_hour_request_restores_only_missing_row(self):
        calls=[]
        def request(url):
            calls.append(url)
            return payload([row(0),row(1),row(2)])
        with tempfile.TemporaryDirectory() as tmp,contextlib.redirect_stdout(io.StringIO()):
            result=m.recover_window(payload([row(0),row(2)]),0,10800,request,Path(tmp))
            self.assertEqual(result,[row(0),row(1),row(2)])
            self.assertEqual(len(calls),1)
            self.assertIn('resolution=60&from=0&to=10800',calls[0])
            proof=json.loads((Path(tmp)/'recovery-3600-provenance.json').read_text())
            self.assertEqual(proof['method'],'hourly_small_window')
            self.assertFalse(proof['synthetic_prices'])

    def test_exact_60_real_minutes_aggregate_without_invented_prices(self):
        minutes=[[3600+j*60,100+j,101+j,99+j,100.5+j,1] for j in range(60)]
        responses=iter([payload([row(0),row(2)]),payload(minutes)])
        urls=[]
        def request(url):
            urls.append(url)
            return next(responses)
        with contextlib.redirect_stdout(io.StringIO()):
            result=m.recover_window(payload([row(0),row(2)]),0,10800,request)
        self.assertEqual(result[1],[3600,100,160,99,159.5,60])
        self.assertIn('resolution=1&from=3600&to=7200',urls[1])

    def test_one_missing_minute_still_blocks_recovery(self):
        minutes=[[3600+j*60,100,100,100,100,1] for j in range(60) if j!=23]
        responses=iter([{'s':'no_data'},payload(minutes)])
        with self.assertRaises(m.MissingCandles) as caught:
            m.recover_window(payload([row(0),row(2)]),0,10800,lambda _:next(responses))
        self.assertEqual(caught.exception.missing,[3600+23*60])

    def test_invalid_price_is_never_replaced_by_recovery(self):
        calls=[]
        with self.assertRaises(m.DataError):
            m.recover_window(payload([row(0,h=99)]),0,3600,lambda url:calls.append(url))
        self.assertEqual(calls,[])

    def test_preflight_first_but_final_rows_stay_chronological(self):
        calls=[]
        def request(url):
            query=m.urllib.parse.parse_qs(m.urllib.parse.urlparse(url).query)
            start,end=int(query['from'][0]),int(query['to'][0]);calls.append(start)
            return payload([row(t//3600) for t in range(start,end,3600)])
        with patch.object(m.time,'sleep'),contextlib.redirect_stdout(io.StringIO()):
            rows=m.fetch_candles(0,170*3600,request,preflight_chunk=2)
        self.assertEqual(calls,[168*3600,0])
        self.assertEqual([r[0] for r in rows],list(range(0,170*3600,3600)))

    def test_frozen_period_and_strategy_cannot_silently_change(self):
        with tempfile.TemporaryDirectory() as tmp:
            path=Path(tmp)
            end=(180*24+m.SLOW+1)*3600
            data={**m.PROTOCOL,'start_utc':'1970-01-01T00:00:00+00:00',
                  'end_exclusive_utc':m.datetime.fromtimestamp(end,m.timezone.utc).isoformat()}
            (path/'protocol.json').write_text(json.dumps(data))
            self.assertEqual(m.frozen_window(path),(0,end))
            data['fast_sma']=21
            (path/'protocol.json').write_text(json.dumps(data))
            with self.assertRaises(m.DataError):m.frozen_window(path)


class HistoricalWindowTests(unittest.TestCase):
    def test_explicit_historical_window_is_separate_and_cannot_promote(self):
        with tempfile.TemporaryDirectory() as tmp:
            path=Path(tmp)
            requested='2026-09-22T13:00:00+00:00'
            calls=[]
            def fake_fetch(start,end,**kwargs):
                calls.append((start,end))
                return [[t,100,100,100,100,1] for t in range(start,end,3600)]
            future=m.datetime(2026,9,28,tzinfo=m.timezone.utc).timestamp()
            def run(*args):
                with patch.object(sys,'argv',['tool','--app-dir',str(path/'missing-app'),'--output-root',str(path/'results'),*args]),patch.object(m.time,'time',return_value=future),patch.object(m,'fetch_candles',side_effect=fake_fetch),patch.object(m,'screen',return_value='FORWARD_PAPER_TEST_ONLY'),contextlib.redirect_stdout(io.StringIO()):
                    self.assertEqual(m.main(),0)
            run('--historical-end-utc',requested)
            first=next((path/'results').iterdir())
            before={p.name:p.read_bytes() for p in first.iterdir() if p.is_file()}
            report=json.loads((first/'report.json').read_text())
            self.assertEqual(report['protocol']['end_exclusive_utc'],requested)
            self.assertEqual(calls[0][1],1790082000)
            self.assertEqual(calls[0][1]-calls[0][0],4371*3600)
            self.assertEqual(report['screen'],'HISTORICAL_SCREEN_PASSED_NO_PROMOTION')
            self.assertEqual(report['protocol']['evaluation_scope'],'separate_historical_window')
            self.assertEqual(report['protocol']['not_evaluated_after_utc'],requested)
            self.assertFalse(report['live_ready'])
            run('--repeat-run',str(first))
            repeated=next(p for p in (path/'results').iterdir() if p!=first)
            self.assertEqual(json.loads((repeated/'report.json').read_text())['screen'],'HISTORICAL_SCREEN_PASSED_NO_PROMOTION')
            self.assertEqual(calls[0],calls[1])
            self.assertEqual(before,{p.name:p.read_bytes() for p in first.iterdir() if p.is_file()})

    def test_invalid_historical_end_rejected_before_network_or_files(self):
        future=m.datetime(2026,9,28,tzinfo=m.timezone.utc).timestamp()
        for value in ['2026-09-22T13:00:00','2026-09-22T13:01:00Z','2026-09-29T00:00:00Z','not-a-date']:
            with self.subTest(value=value),tempfile.TemporaryDirectory() as tmp:
                out=Path(tmp)/'results'
                with patch.object(sys,'argv',['tool','--output-root',str(out),'--historical-end-utc',value]),patch.object(m.time,'time',return_value=future),patch.object(m,'fetch_candles') as fetch,contextlib.redirect_stderr(io.StringIO()),self.assertRaises(SystemExit):
                    m.main()
                fetch.assert_not_called()
                self.assertFalse(out.exists())

    def test_repeat_cannot_change_frozen_period_with_historical_flag(self):
        with patch.object(sys,'argv',['tool','--repeat-run','old','--historical-end-utc','2020-01-01T00:00:00Z']),contextlib.redirect_stderr(io.StringIO()),self.assertRaises(SystemExit):
            m.main()


class ReportTests(unittest.TestCase):
    def test_small_sample_cannot_pass(self):
        stats={'natural_exits':2,'net_pnl':2,'max_drawdown_pct':0,'profit_factor':10}
        results={p:{c:{'trend':dict(stats)} for c in ['base','stress']} for p in ['validation','holdout']}
        self.assertEqual(m.screen(results),'INSUFFICIENT_EVIDENCE')
        for p in results.values():
            for c in p.values():c['trend']['natural_exits']=30
        self.assertEqual(m.screen(results),'FORWARD_PAPER_TEST_ONLY')
        results['holdout']['stress']['trend']['net_pnl']=-1
        self.assertEqual(m.screen(results),'REJECTED')

    def test_main_preserves_application_and_outputs_non_authorizing_report(self):
        with tempfile.TemporaryDirectory() as tmp:
            app=Path(tmp)/'app';app.mkdir()
            (app/'.env').write_text('API_KEY=PRIVATE_TEST_SECRET\n')
            (app/'state').mkdir();(app/'logs').mkdir()
            (app/'state/paper_state.json').write_text('{"closed_trades":0,"realized_pnl":0}')
            (app/'logs/audit.jsonl').write_text('{"event":"paper_sell","pnl":1}\n')
            before={str(p):p.read_bytes() for p in app.rglob('*') if p.is_file()}
            def fake_fetch(start,end,**kwargs):
                return [[t,100,100,100,100,1] for t in range(start,end,3600)]
            output=io.StringIO()
            with patch.object(sys,'argv',['tool','--app-dir',str(app),'--output-root',str(Path(tmp)/'research')]),patch.object(m,'fetch_candles',side_effect=fake_fetch),contextlib.redirect_stdout(output):
                self.assertEqual(m.main(),0)
            report=json.loads(next((Path(tmp)/'research').glob('*/report.json')).read_text())
            self.assertFalse(report['live_ready'])
            self.assertFalse(report['automatic_promotion'])
            self.assertFalse(report['current_bot_history']['ledger_reconciles'])
            self.assertEqual(before,{str(p):p.read_bytes() for p in app.rglob('*') if p.is_file()})
            self.assertNotIn('PRIVATE_TEST_SECRET',output.getvalue())
            self.assertEqual(report['screen'],'INSUFFICIENT_EVIDENCE')

    def test_network_failure_never_generates_strategy_approval(self):
        with tempfile.TemporaryDirectory() as tmp:
            with patch.object(sys,'argv',['tool','--output-root',tmp]),patch.object(m,'fetch_candles',side_effect=m.DataError('offline')),contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(m.main(),1)
            self.assertEqual(len(list(Path(tmp).glob('*/failure.json'))),1)
            self.assertEqual(len(list(Path(tmp).glob('*/report.json'))),0)

    def test_diagnostic_requests_only_selected_window_and_preserves_files(self):
        with tempfile.TemporaryDirectory() as tmp:
            path=Path(tmp)
            protocol={'symbol':'BTCUSDT','resolution_minutes':60,
                      'start_utc':'1970-01-01T00:00:00+00:00',
                      'end_exclusive_utc':'1970-01-15T00:00:00+00:00'}
            (path/'protocol.json').write_text(json.dumps(protocol))
            before=(path/'protocol.json').read_bytes()
            calls=[]
            def request(url):
                calls.append(url)
                return payload([row(i) for i in range(168,336) if i!=170])
            out=io.StringIO()
            with contextlib.redirect_stdout(out):
                self.assertEqual(m.diagnose_run(path,2,request),1)
            result=json.loads(out.getvalue())
            self.assertEqual(len(calls),1)
            self.assertIn('from=604800&to=1209600',calls[0])
            self.assertIn('count=1',result['reason'])
            self.assertIn('1970-01-08T02:00:00',result['reason'])
            self.assertFalse(result['live_ready'])
            self.assertEqual(before,(path/'protocol.json').read_bytes())
            self.assertEqual(len(list(path.iterdir())),1)

    def test_missing_window_is_preserved_and_named(self):
        with tempfile.TemporaryDirectory() as tmp:
            path=Path(tmp)
            with self.assertRaisesRegex(m.DataError,'chunk 1/1'),patch.object(m.time,'sleep'):
                m.fetch_candles(0,7200,lambda _:payload([row(0)]),cache_dir=path)
            saved=json.loads((path/'chunk-01.json').read_text())
            self.assertEqual(saved['t'],[0])


if __name__=='__main__':unittest.main()

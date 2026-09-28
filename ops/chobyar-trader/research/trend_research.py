#!/usr/bin/env python3
"""Read-only public-data research. No credentials, orders, or service changes."""
from __future__ import annotations

import argparse
import hashlib
import json
import math
import statistics
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
from collections import Counter, defaultdict
from datetime import datetime, timezone
from pathlib import Path

VERSION = 'trend-research-20260928-v1'
HOUR = 3600
FAST, SLOW = 20, 50
START_EQUITY = 10.0
POSITION_FRACTION = .25
STOP, TARGET, DAILY_LIMIT = .015, .03, .03
COOLDOWN_BARS = 6
ENDPOINT = 'https://api.wallex.ir/v1/udf/history'
PROTOCOL = {
    'version': VERSION, 'purpose': 'research_only', 'symbol': 'BTCUSDT',
    'exchange': 'Wallex', 'resolution_minutes': 60, 'days': 180,
    'split_days': [90, 45, 45], 'fast_sma': FAST, 'slow_sma': SLOW,
    'initial_equity_usdt': START_EQUITY, 'position_fraction': POSITION_FRACTION,
    'stop_loss_pct': STOP, 'take_profit_pct': TARGET, 'daily_loss_limit': DAILY_LIMIT,
    'loss_cooldown_hours': COOLDOWN_BARS,
    'cost_scenarios': {'base': {'fee': .002, 'execution_haircut': .001},
                       'stress': {'fee': .004, 'execution_haircut': .002}},
    'cost_note': 'Hypothetical per-side costs; actual account fees and fill quality are not verified.',
    'signal': 'previous close > SMA20 > SMA50 and SMA20 rising; exit on failed trend or fixed stop/target',
    'timing': 'Only completed previous bars form signals; fill at next bar open plus costs.',
    'ambiguous_bar': 'Stop before target if both touched; gaps through stops fill at worse open.',
    'selection': 'One fixed candidate; no parameter search or automatic selection.',
    'screen': {'minimum_natural_exits_each_later_period': 30, 'minimum_profit_factor': 1.2,
               'maximum_drawdown_pct': 5.0, 'positive_base_and_stress_pnl': True},
    'live_ready': False, 'automatic_promotion': False,
}


class DataError(Exception):
    pass


class MissingCandles(DataError):
    def __init__(self, missing, rows, step):
        self.missing, self.rows = missing, rows
        preview = ','.join(datetime.fromtimestamp(t,timezone.utc).isoformat() for t in missing[:5])
        super().__init__(f'missing candles: interval_seconds={step}, count={len(missing)}, first={preview}; no gap filling allowed')


def number(value):
    if isinstance(value, bool):
        raise DataError('boolean is not a price')
    result = float(value)
    if not math.isfinite(result):
        raise DataError('nonfinite data')
    return result


def parse_payload(payload, start, end, step=HOUR):
    if not isinstance(payload, dict) or payload.get('s') != 'ok':
        raise DataError('public candle response not OK')
    keys = ('t', 'o', 'h', 'l', 'c', 'v')
    if not all(isinstance(payload.get(k), list) for k in keys):
        raise DataError('missing candle arrays')
    lengths = {len(payload[k]) for k in keys}
    if len(lengths) != 1:
        raise DataError('candle array length mismatch')
    result = {}
    for raw in zip(*(payload[k] for k in keys)):
        t, o, h, low, c, volume = map(number, raw)
        if t != int(t) or int(t) % step:
            raise DataError('unaligned timestamp')
        t = int(t)
        if not start <= t < end:
            continue
        if min(o,h,low,c) <= 0 or volume < 0 or not low <= min(o,c) <= max(o,c) <= h:
            raise DataError('invalid OHLC candle at ' + datetime.fromtimestamp(t,timezone.utc).isoformat())
        row = [t,o,h,low,c,volume]
        if t in result and result[t] != row:
            raise DataError('conflicting duplicate candle')
        result[t] = row
    expected = list(range(start,end,step))
    if sorted(result) != expected:
        missing = [t for t in expected if t not in result]
        raise MissingCandles(missing,result,step)
    return [result[t] for t in expected]


def get_json(url):
    for attempt in range(3):
        try:
            request = urllib.request.Request(url, headers={'Accept':'application/json', 'User-Agent':VERSION})
            with urllib.request.urlopen(request, timeout=12) as response:
                body = response.read(2_000_001)
            if len(body) > 2_000_000:
                raise DataError('oversized response')
            return json.loads(body)
        except urllib.error.HTTPError as exc:
            if exc.code != 429 and exc.code < 500:
                raise DataError('public HTTP status ' + str(exc.code)) from None
            if attempt == 2:
                raise DataError('public HTTP retries exhausted') from None
        except (urllib.error.URLError, TimeoutError):
            if attempt == 2:
                raise DataError('public data connection unavailable') from None
        time.sleep(attempt+1)
    raise DataError('public data unavailable')


def recover_window(payload, start, end, request, cache_dir=None):
    try:
        return parse_payload(payload,start,end)
    except MissingCandles as exc:
        missing, merged = exc.missing, dict(exc.rows)
    if len(missing) > 12:
        raise DataError('too many missing hours for bounded recovery')
    for stamp in missing:
        query = urllib.parse.urlencode({'symbol':'BTCUSDT','resolution':'60',
                                       'from':stamp-HOUR,'to':stamp+2*HOUR})
        small = request(ENDPOINT+'?'+query)
        if cache_dir is not None:
            write_json(cache_dir/f'recovery-{stamp}-hourly.json',small)
        recovered = []
        if not (isinstance(small,dict) and small.get('s') == 'no_data'):
            try:
                recovered = parse_payload(small,stamp,stamp+HOUR)
            except MissingCandles:
                pass
        method = 'hourly_small_window'
        if not recovered:
            query = urllib.parse.urlencode({'symbol':'BTCUSDT','resolution':'1',
                                           'from':stamp,'to':stamp+HOUR})
            fine = request(ENDPOINT+'?'+query)
            if cache_dir is not None:
                write_json(cache_dir/f'recovery-{stamp}-minutes.json',fine)
            minutes = parse_payload(fine,stamp,stamp+HOUR,step=60)
            recovered = [[stamp,minutes[0][1],max(r[2] for r in minutes),
                          min(r[3] for r in minutes),minutes[-1][4],math.fsum(r[5] for r in minutes)]]
            method = 'aggregate_60_complete_wallex_minutes'
        merged[stamp] = recovered[0]
        if cache_dir is not None:
            write_json(cache_dir/f'recovery-{stamp}-provenance.json',
                       {'timestamp':stamp,'method':method,'row':recovered[0],
                        'synthetic_prices':False,'exchange':'Wallex','symbol':'BTCUSDT'})
        print('RECOVERED '+datetime.fromtimestamp(stamp,timezone.utc).isoformat()+' | '+method,flush=True)
    complete = {'s':'ok',**{k:[merged[t][i] for t in sorted(merged)]
                           for i,k in enumerate(('t','o','h','l','c','v'))}}
    return parse_payload(complete,start,end)


def fetch_candles(start, end, request=get_json, cache_dir=None, preflight_chunk=None):
    parts = {}
    windows = math.ceil((end-start)/(7*24*HOUR))
    order = list(range(1,windows+1))
    if preflight_chunk is not None:
        if preflight_chunk not in order:
            raise DataError('invalid preflight chunk')
        order.remove(preflight_chunk)
        order.insert(0,preflight_chunk)
    for progress,index in enumerate(order,1):
        cursor = start+(index-1)*7*24*HOUR
        until = min(cursor+7*24*HOUR,end)
        query = urllib.parse.urlencode({'symbol':'BTCUSDT','resolution':'60','from':cursor,'to':until})
        payload = request(ENDPOINT+'?'+query)
        if cache_dir is not None:
            cache_dir.mkdir(parents=True,exist_ok=True)
            write_json(cache_dir/f'chunk-{index:02d}.json',payload)
        try:
            parts[index] = recover_window(payload,cursor,until,request,cache_dir)
        except DataError as exc:
            raise DataError(f'chunk {index}/{windows}: {exc}') from None
        print(f'DATA {progress}/{windows} | WINDOW {index}/{windows}',flush=True)
        time.sleep(.2)
    return [row for index in range(1,windows+1) for row in parts[index]]


def trend_signals(rows):
    closes = [r[4] for r in rows]
    result = [False]*len(rows)
    for i in range(SLOW+1,len(rows)):
        fast = statistics.fmean(closes[i-FAST:i])
        previous_fast = statistics.fmean(closes[i-FAST-1:i-1])
        slow = statistics.fmean(closes[i-SLOW:i])
        result[i] = closes[i-1] > fast > slow and fast > previous_fast
    return result


def simulate(rows, signals, start, end, fee, haircut, strategy='trend'):
    if strategy not in ('trend','hold','cash') or not (0 <= fee < .1 and 0 <= haircut < .1):
        raise ValueError('invalid simulation setting')
    cash, qty, basis, entry = START_EQUITY, 0.0, 0.0, 0.0
    peak, max_dd, fees, cooldown = START_EQUITY, 0.0, 0.0, -1
    day, day_start, bought_once = None, START_EQUITY, False
    exits, equity_path = [], []

    def equity(px):
        return cash + qty*px*(1-haircut)*(1-fee)

    def close(px, index, reason):
        nonlocal cash, qty, basis, entry, fees, cooldown
        gross = qty*px*(1-haircut)
        exit_fee = gross*fee
        pnl = gross-exit_fee-basis
        cash += gross-exit_fee
        fees += exit_fee
        exits.append({'time':rows[index][0], 'reason':reason, 'pnl':pnl})
        if pnl < 0:
            cooldown = index+COOLDOWN_BARS
        qty, basis, entry = 0.0, 0.0, 0.0

    for i in range(start,end):
        ts,o,h,low,c,_ = rows[i]
        if ts//86400 != day:
            day, day_start = ts//86400, equity(o)
        exited = False
        if qty and strategy == 'trend':
            stop, target = entry*(1-STOP), entry*(1+TARGET)
            if o <= stop:
                close(o,i,'gap_stop'); exited = True
            elif not signals[i]:
                close(o,i,'trend_exit'); exited = True
        daily_ok = day_start > 0 and (day_start-equity(o))/day_start < DAILY_LIMIT
        want_buy = (strategy == 'hold' and not bought_once) or (strategy == 'trend' and signals[i])
        if not qty and not exited and want_buy and daily_ok and i > cooldown:
            notional = cash*POSITION_FRACTION
            entry = o*(1+haircut)
            qty = notional/entry
            entry_fee = notional*fee
            basis = notional+entry_fee
            cash -= basis
            fees += entry_fee
            bought_once = True
        if qty and strategy == 'trend':
            stop, target = entry*(1-STOP), entry*(1+TARGET)
            if low <= stop:
                close(min(o,stop),i,'stop_loss')
            elif h >= target:
                close(target,i,'take_profit')
        mark = equity(c)
        peak = max(peak,mark)
        max_dd = max(max_dd,(peak-mark)/peak)
        equity_path.append(mark)
    if qty:
        close(rows[end-1][4],end-1,'end_of_test')
    gains = sum(e['pnl'] for e in exits if e['pnl'] > 0)
    losses = -sum(e['pnl'] for e in exits if e['pnl'] < 0)
    pnl = cash-START_EQUITY
    if not math.isclose(pnl,sum(e['pnl'] for e in exits),abs_tol=1e-9):
        raise ArithmeticError('cash ledger does not reconcile')
    return {'net_pnl':pnl,'return_pct':pnl/START_EQUITY*100,'ending_equity':cash,
            'closed_trades':len(exits),'natural_exits':sum(e['reason']!='end_of_test' for e in exits),
            'wins':sum(e['pnl']>0 for e in exits),'losses':sum(e['pnl']<0 for e in exits),
            'profit_factor':gains/losses if losses else None,
            'max_drawdown_pct':max_dd*100,'fees':fees,'exits':exits,
            'drawdown_note':'Measured at hourly closes, not intrabar maximum drawdown.'}


def actual_evidence(app_dir):
    path = app_dir/'logs/audit.jsonl'
    result = {'available':False,'note':'Actual historical ledger; NOT a same-period reconstructed candle baseline.'}
    if not path.is_file():
        return result
    count, total, bad = 0, 0.0, 0
    with path.open(errors='replace') as stream:
        for line in stream:
            try:
                row = json.loads(line)
                if isinstance(row,dict) and row.get('event') == 'paper_sell':
                    total += number(row['pnl']); count += 1
            except (ValueError,TypeError,KeyError,DataError):
                bad += 1
    result.update(available=True,logged_exits=count,logged_net_pnl=total,invalid_rows=bad)
    try:
        state = json.loads((app_dir/'state/paper_state.json').read_text())
        state_count, state_pnl = int(state['closed_trades']), number(state['realized_pnl'])
        result.update(state_exits=state_count,state_net_pnl=state_pnl,
                      ledger_reconciles=(count==state_count and math.isclose(total,state_pnl,abs_tol=1e-7)))
    except (OSError,ValueError,TypeError,KeyError,DataError):
        result['ledger_reconciles'] = None
    return result


def screen(results):
    later = [results[p][cost]['trend'] for p in ('validation','holdout') for cost in ('base','stress')]
    if any(x['natural_exits'] < 30 for x in later):
        return 'INSUFFICIENT_EVIDENCE'
    if any(x['net_pnl'] <= 0 or x['max_drawdown_pct'] > 5 or
           (x['profit_factor'] is not None and x['profit_factor'] < 1.2) for x in later):
        return 'REJECTED'
    return 'FORWARD_PAPER_TEST_ONLY'


def write_json(path, data):
    path.write_text(json.dumps(data,indent=2,allow_nan=False)+'\n')


def diagnose_run(directory, chunk, request=get_json):
    """Repeat only a selected public-data window from an existing frozen protocol."""
    protocol = json.loads((directory/'protocol.json').read_text())
    start = int(datetime.fromisoformat(protocol['start_utc']).timestamp())
    end = int(datetime.fromisoformat(protocol['end_exclusive_utc']).timestamp())
    if protocol.get('symbol') != 'BTCUSDT' or protocol.get('resolution_minutes') != 60:
        raise DataError('unsupported frozen protocol')
    windows = math.ceil((end-start)/(7*24*HOUR))
    if not 1 <= chunk <= windows:
        raise DataError('invalid chunk number')
    cursor = start+(chunk-1)*7*24*HOUR
    until = min(cursor+7*24*HOUR,end)
    query = urllib.parse.urlencode({'symbol':'BTCUSDT','resolution':'60','from':cursor,'to':until})
    result = {'chunk':chunk,'total_chunks':windows,'expected_hours':(until-cursor)//HOUR,
              'start_utc':datetime.fromtimestamp(cursor,timezone.utc).isoformat(),
              'end_exclusive_utc':datetime.fromtimestamp(until,timezone.utc).isoformat(),
              'live_ready':False,'automatic_promotion':False}
    try:
        payload = request(ENDPOINT+'?'+query)
        result['schema'] = ({k:len(payload[k]) if isinstance(payload.get(k),list) else 'not_array'
                             for k in ('t','o','h','l','c','v')} if isinstance(payload,dict) else 'not_object')
        rows = parse_payload(payload,cursor,until)
        result.update(status='WINDOW_VALID_NOW',rows=len(rows),
                      note='This does not prove the original response was valid or approve any strategy.')
    except Exception as exc:
        result.update(status='WINDOW_FAILED',error_type=type(exc).__name__,
                      reason=str(exc) if isinstance(exc,DataError) else 'public request or response failed')
    print(json.dumps(result,indent=2,allow_nan=False),flush=True)
    return 0 if result['status']=='WINDOW_VALID_NOW' else 1


def frozen_window(directory):
    frozen = json.loads((directory/'protocol.json').read_text())
    if any(frozen.get(key) != value for key,value in PROTOCOL.items()):
        raise DataError('frozen strategy protocol differs; refusing to change the test')
    start = int(datetime.fromisoformat(frozen['start_utc']).timestamp())
    end = int(datetime.fromisoformat(frozen['end_exclusive_utc']).timestamp())
    if start % HOUR or end % HOUR or end-start != (180*24+SLOW+1)*HOUR:
        raise DataError('invalid frozen time range')
    return start,end


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--app-dir',type=Path,default=Path('/opt/chobyar-trader'))
    parser.add_argument('--output-root',type=Path,default=Path('/opt/chobyar-trader/research'))
    parser.add_argument('--diagnose-run',type=Path)
    parser.add_argument('--chunk',type=int,default=26)
    parser.add_argument('--repeat-run',type=Path)
    parser.add_argument('--preflight-chunk',type=int)
    args = parser.parse_args()
    if args.diagnose_run is not None:
        return diagnose_run(args.diagnose_run,args.chunk)
    args.output_root.mkdir(parents=True,exist_ok=True)
    out = Path(tempfile.mkdtemp(prefix='trend-study-',dir=args.output_root))
    end = int(time.time())//HOUR*HOUR
    start = end-(180*24+SLOW+1)*HOUR
    if args.repeat_run is not None:
        start,end = frozen_window(args.repeat_run)
    protocol = {**PROTOCOL,'start_utc':datetime.fromtimestamp(start,timezone.utc).isoformat(),
                'end_exclusive_utc':datetime.fromtimestamp(end,timezone.utc).isoformat(),
                'code_sha256':hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
                'data_recovery':'Retry missing hour in small window; otherwise aggregate only 60 complete same-market minute candles.'}
    if args.repeat_run is not None:
        protocol['repeated_from'] = str(args.repeat_run)
    write_json(out/'protocol.json',protocol)
    print('PROTOCOL_FROZEN | RESEARCH_ONLY | NO_ORDERS',flush=True)
    print('OUTPUT='+str(out),flush=True)
    try:
        rows = fetch_candles(start,end,cache_dir=out/'chunks',preflight_chunk=args.preflight_chunk)
        encoded = json.dumps(rows,separators=(',',':'),allow_nan=False).encode()
        (out/'candles.json').write_bytes(encoded)
        signals = trend_signals(rows)
        a = SLOW+1
        bounds = [('development',a,a+90*24),('validation',a+90*24,a+135*24),('holdout',a+135*24,len(rows))]
        results = {}
        for period, lo, hi in bounds:
            results[period] = {}
            for cost, params in PROTOCOL['cost_scenarios'].items():
                results[period][cost] = {}
                for strategy in ('trend','hold','cash'):
                    stats = simulate(rows,signals,lo,hi,params['fee'],params['execution_haircut'],strategy)
                    results[period][cost][strategy] = stats
                    pf = 'N/A' if stats['profit_factor'] is None else f"{stats['profit_factor']:.3f}"
                    print(f"{period:11} {cost:6} {strategy:5} pnl={stats['net_pnl']:+.6f} return={stats['return_pct']:+.3f}% trades={stats['closed_trades']} PF={pf} DD={stats['max_drawdown_pct']:.3f}%",flush=True)
        report = {'protocol':protocol,'data_sha256':hashlib.sha256(encoded).hexdigest(),
                  'results':results,'current_bot_history':actual_evidence(args.app_dir),
                  'screen':screen(results),'live_ready':False,'automatic_promotion':False,
                  'limitations':['Historical screening only; repeated tuning contaminates held-out evidence.',
                                 'Hourly OHLC cannot establish real fills, minimum order eligibility, or order-book capacity.',
                                 'The current bot cannot be reconstructed from candles alone.',
                                 'Each period resets simulated equity and positions; no compounded cross-period result.']}
        write_json(out/'report.json',report)
        print('CURRENT_BOT_HISTORY='+json.dumps(report['current_bot_history'],allow_nan=False),flush=True)
        print('RESEARCH_SCREEN='+report['screen'],flush=True)
        print('LIVE_READY=false | AUTOMATIC_PROMOTION=false',flush=True)
        print('REPORT='+str(out/'report.json'),flush=True)
    except Exception as exc:
        reason = str(exc) if isinstance(exc,DataError) else 'request or computation failed; inspect error_type'
        write_json(out/'failure.json',{'status':'DATA_OR_TEST_FAILED','error_type':type(exc).__name__,'reason':reason,'live_ready':False})
        print('RESEARCH_FAILED='+type(exc).__name__+' | '+reason+' | NO_STRATEGY_APPROVAL',flush=True)
        return 1
    return 0


if __name__ == '__main__':
    raise SystemExit(main())

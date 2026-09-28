import ast, fcntl, hashlib, json, math, os, shutil, subprocess, sys, tempfile, time
from datetime import datetime, timezone
from pathlib import Path
from types import SimpleNamespace as S

OLD = 'a0dc8f44afbb63349c9327205fb71ae8965c8dee6a523259ac5a940ef9be3697'
NEW = '30b53a14b8d15944ae9600576cd62036984d38580562842e8c79f981b0c73a66'
ENTRY = '6164a0393c4a22289320ef88c1a4ad3eb26d20a66e23cace33a131be2bb5795c'
ROOT = Path('/opt/chobyar-trader')
TARGET = ROOT / 'app/v5/execution_safety/trader.py'
UNIT = 'chobyar-trader.service'

def digest(data):
    return hashlib.sha256(data).hexdigest()

def require(ok, message):
    if not ok:
        raise RuntimeError(message)

BUNDLE = Path(__file__).resolve().parent

def candidate(raw):
    require(digest(raw) == OLD, 'STOP: source version differs; nothing changed')
    result = (BUNDLE / 'trader.py').read_bytes()
    require(digest(result) == NEW, 'STOP: candidate checksum failed')
    compile(result, 'trader.py', 'exec')
    return result

def verify(raw):
    require(digest(raw) == NEW, 'STOP: candidate differs from tested source')
    result = subprocess.run([sys.executable,'-B','-m','unittest','test_paper_runtime','test_tape_freshness','test_entry_economics','test_exit_data_availability'],
                            cwd=BUNDLE,capture_output=True,text=True,timeout=60)
    require(result.returncode == 0, 'STOP: runtime regression tests failed')
    return 34

def ctl(*args):
    return subprocess.run(['systemctl', *args], capture_output=True, text=True, timeout=40, check=True).stdout.strip()

def atomic_write(path, data, meta):
    fd, name = tempfile.mkstemp(prefix='.exit-data-availability-', dir=path.parent)
    try:
        with os.fdopen(fd, 'wb') as f:
            f.write(data)
            os.fchmod(f.fileno(), meta.st_mode & 0o777)
            os.fchown(f.fileno(), meta.st_uid, meta.st_gid)
            f.flush()
            os.fsync(f.fileno())
        os.replace(name, path)
    finally:
        if os.path.exists(name):
            os.unlink(name)

def health(since):
    deadline = time.monotonic() + 100
    while time.monotonic() < deadline:
        time.sleep(2)
        if ctl('is-active', UNIT) != 'active':
            return False
        output = subprocess.run(['tail','-n','80',str(ROOT / 'logs/audit.jsonl')], capture_output=True, text=True, timeout=5)
        for line in output.stdout.splitlines():
            try:
                row = json.loads(line)
                stamp = datetime.fromisoformat(row['ts'].replace('Z','+00:00')).timestamp()
                if row.get('event') == 'cycle' and stamp >= since and isinstance(row.get('tape_available'),bool) and 'tape_recent_rows' in row and isinstance(row.get('entry_economics'),dict) and row['entry_economics'].get('model') == 'paper_fee_and_unchanged_relative_spread_v1' and row.get('quote_policy') == 'book_after_optional_tape_v1':
                    pid = ctl('show', UNIT, '--property=MainPID', '--value')
                    time.sleep(3)
                    return ctl('is-active', UNIT) == 'active' and ctl('show', UNIT, '--property=MainPID', '--value') == pid
            except (ValueError, KeyError, TypeError):
                continue
    return False

def install():
    require(os.geteuid() == 0, 'Run with sudo')
    with open('/run/lock/chobyar-exit-data-availability.lock','w') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        raw = TARGET.read_bytes()
        if digest(raw) == NEW:
            print('ALREADY_INSTALLED; service=' + ctl('is-active', UNIT))
            return
        fixed = candidate(raw)
        print('EXIT_DATA_AVAILABILITY_TESTS=PASS (' + str(verify(fixed)) + ')', flush=True)
        entry = TARGET.with_name('trader_entry.py')
        require(digest(entry.read_bytes()) == ENTRY, 'STOP: entrypoint version differs')
        canonical = json.loads((BUNDLE / 'paper_runtime_manifest.json').read_text())
        for name, expected in canonical['files'].items():
            if name != 'trader.py':
                require(digest(TARGET.with_name(name).read_bytes()) == expected, 'STOP: installed dependency differs: ' + name)
        require(ctl('is-active',UNIT) == 'active', 'STOP: service is not active')
        pid = ctl('show',UNIT,'--property=MainPID','--value')
        require(str(entry).encode() in Path('/proc/'+pid+'/cmdline').read_bytes().split(b'\0'), 'STOP: different running entrypoint')
        config = {}
        for line in (ROOT / '.env').read_text().splitlines():
            k, sep, v = line.strip().removeprefix('export ').partition('=')
            if sep and k.strip() in ('TRADING_MODE','LIVE_TRADING_ENABLED'):
                config[k.strip()] = v.split('#',1)[0].strip().strip('\"\'')
        require(config == dict(TRADING_MODE='paper',LIVE_TRADING_ENABLED='false'), 'STOP: paper lock is not confirmed')
        running = dict(x.split(b'=',1) for x in Path('/proc/'+pid+'/environ').read_bytes().split(b'\0') if b'=' in x)
        require(all(running.get(k.encode(),v.encode()) == v.encode() for k,v in config.items()), 'STOP: running mode differs')
        changes = [(TARGET,raw,fixed,TARGET.stat())]
        manifest = TARGET.with_name('paper_runtime_manifest.json')
        if manifest.exists():
            before = manifest.read_bytes()
            data = json.loads(before)
            require(data.get('files',{}).get('trader.py') == OLD, 'STOP: manifest differs')
            data['files']['trader.py'] = NEW
            data['exit_guard_patch'] = '2026-09-28-paper-only'
            data['exit_data_availability_patch'] = '2026-09-28-paper-only'
            changes.append((manifest,before,(json.dumps(data,indent=2)+'\n').encode(),manifest.stat()))
        backup = ROOT / 'backups' / ('exit-data-availability-' + datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ'))
        backup.mkdir(parents=True, mode=0o700, exist_ok=False)
        for path,before,after,meta in changes:
            shutil.copy2(path, backup / path.name)
        print('BACKUP=' + str(backup), flush=True)
        attempted = False
        written = []
        try:
            require(TARGET.read_bytes() == raw, 'STOP: source changed during preparation')
            attempted = True
            ctl('stop',UNIT)
            for path,before,after,meta in changes:
                require(path.read_bytes() == before, 'STOP: concurrent source change')
                atomic_write(path,after,meta)
                written.append((path,before,after,meta))
            started = time.time()
            ctl('start',UNIT)
            print('Checking fresh trading cycle; up to 100 seconds...',flush=True)
            require(health(started), 'New runtime health check failed')
            require(digest(TARGET.read_bytes()) == NEW, 'Installed hash differs')
            print('UPGRADE_OK | paper only | fresh cycle verified | optional tape failure isolated')
        except BaseException:
            if attempted:
                ctl('stop',UNIT)
                for path,before,after,meta in reversed(written):
                    atomic_write(path,before,meta)
                ctl('start',UNIT)
                print('ROLLED_BACK | service=' + ctl('is-active',UNIT),flush=True)
            raise

if __name__ == '__main__':
    try:
        if len(sys.argv) == 3 and sys.argv[1] == '--verify-source':
            print('EXIT_DATA_AVAILABILITY_TESTS=PASS', verify(candidate(Path(sys.argv[2]).read_bytes())))
        else:
            install()
    except BaseException as error:
        print('STOPPED:',type(error).__name__, str(error) if isinstance(error, RuntimeError) else 'No secret details printed',flush=True)
        sys.exit(1)

import contextlib, importlib.util, io, json, tempfile, unittest
from pathlib import Path
from unittest.mock import patch

HERE = Path(__file__).parent
spec = importlib.util.spec_from_file_location('upgrade',HERE/'install_entry_economics.py')
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)

class InstallerTests(unittest.TestCase):
    def run_case(self, mode):
        original = b'# previous installed runtime fixture\n'
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            target = root/'app/v5/execution_safety/trader.py'
            target.parent.mkdir(parents=True)
            entry = target.with_name('trader_entry.py')
            entry.write_bytes((HERE/'trader_entry.py').read_bytes())
            for name in ['common.py','entry_gate_v55.py','global_sources.py']:
                target.with_name(name).write_bytes((HERE/name).read_bytes())
            target.write_bytes(original if mode != 'unknown' else b'custom source')
            manifest = target.with_name('paper_runtime_manifest.json')
            manifest_data = json.loads((HERE/'paper_runtime_manifest.json').read_text())
            manifest_data['files']['trader.py'] = m.digest(original)
            manifest.write_text(json.dumps(manifest_data))
            manifest_before = manifest.read_bytes()
            (root/'.env').write_text('TRADING_MODE=paper\nLIVE_TRADING_ENABLED=false\nAPI_KEY=do-not-print\n')
            state = root/'state'
            state.mkdir()
            (state/'paper_state.json').write_text('{"cash":9.92}')
            calls = []
            def ctl(*args):
                calls.append(args)
                if args[0]=='is-active':return 'active'
                if args[0]=='show':return '999999'
                return ''
            real_read = Path.read_bytes
            def read(p):
                if str(p)=='/proc/999999/cmdline':return str(entry).encode()+b'\0'
                if str(p)=='/proc/999999/environ':return b'TRADING_MODE=paper\0LIVE_TRADING_ENABLED='+ (b'true' if mode=='live' else b'false')+b'\0'
                return real_read(p)
            out = io.StringIO()
            with patch.object(m,'OLD',m.digest(original)), patch.object(m,'verify',return_value=28), patch.object(m,'ROOT',root), patch.object(m,'TARGET',target), patch.object(m,'ctl',ctl), patch.object(m,'health',return_value=(mode!='rollback')), patch.object(m.fcntl,'flock'), patch.object(Path,'read_bytes',read), patch.object(m.os,'geteuid',return_value=0), contextlib.redirect_stdout(out):
                if mode in ('rollback','unknown','live'):
                    with self.assertRaises(RuntimeError):m.install()
                else:
                    m.install()
                    m.install()
            self.assertNotIn('do-not-print',out.getvalue())
            self.assertEqual((state/'paper_state.json').read_text(),'{"cash":9.92}')
            if mode=='success':
                self.assertEqual(m.digest(target.read_bytes()),m.NEW)
                self.assertIn('UPGRADE_OK',out.getvalue())
                self.assertIn('ALREADY_INSTALLED',out.getvalue())
                self.assertEqual(json.loads(manifest.read_bytes())['files']['trader.py'],m.NEW)
                backups=list((root/'backups').glob('*/trader.py'))
                self.assertEqual(len(backups),1)
                self.assertEqual(backups[0].read_bytes(),original)
            elif mode=='rollback':
                self.assertEqual(target.read_bytes(),original)
                self.assertEqual(manifest.read_bytes(),manifest_before)
                self.assertIn('ROLLED_BACK',out.getvalue())
            else:
                self.assertFalse(any(c[0] in ('start','stop') for c in calls))
                self.assertFalse((root/'backups').exists())
    def test_success_backup_and_repeat(self):self.run_case('success')
    def test_failed_health_restores_code_manifest_and_keeps_state(self):self.run_case('rollback')
    def test_unknown_source_is_untouched(self):self.run_case('unknown')
    def test_live_mode_is_rejected(self):self.run_case('live')

if __name__ == '__main__':unittest.main()

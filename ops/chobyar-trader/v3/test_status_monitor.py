from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

import status_server_v45 as monitor


class MonitorRouteTests(unittest.TestCase):
    def test_only_explicit_monitor_assets_are_served(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            old = monitor.MONITOR_DIR
            try:
                monitor.MONITOR_DIR = Path(tmp)
                index, content_type = monitor.monitor_asset('/monitor/')
                self.assertEqual(index, Path(tmp) / 'index.html')
                self.assertEqual(content_type, 'text/html; charset=utf-8')
                self.assertIsNone(monitor.monitor_asset('/monitor/../.env'))
                self.assertIsNone(monitor.monitor_asset('/monitor/.env'))
                self.assertIsNone(monitor.monitor_asset('/status'))
                self.assertIsNone(monitor.monitor_asset('/public-report'))
            finally:
                monitor.MONITOR_DIR = old

    def test_client_is_read_only_and_same_origin(self) -> None:
        root = Path(__file__).resolve().parents[1] / 'monitor'
        html = (root / 'index.html').read_text(encoding='utf-8')
        js = (root / 'app.js').read_text(encoding='utf-8')
        sw = (root / 'sw.js').read_text(encoding='utf-8')
        manifest = (root / 'manifest.webmanifest').read_text(encoding='utf-8')
        client = '\n'.join([html, js, sw, manifest]).lower()

        self.assertIn('fetch("/public-report"', js)
        self.assertIn('report.mode !== "paper"', js)
        self.assertIn('report.live_locked !== true', js)
        self.assertIn('credentials: "omit"', js)
        self.assertIn('never cache /public-report', sw.lower())
        self.assertIn('"start_url": "/monitor/"', manifest)

        forbidden = (
            'http://109.122.247.214',
            'https://109.122.247.214',
            'status_hmac_secret',
            'api_key',
            'apikey',
            'submit_order',
            'create_order',
            'place_order',
            'enable_live',
            'live_trading_enabled=',
            'martingale',
            'leverage',
        )
        for marker in forbidden:
            self.assertNotIn(marker, client, marker)

        self.assertNotIn('fetch("/status"', js)
        self.assertNotIn("fetch('/status'", js)
        self.assertNotIn('<input', html.lower())
        self.assertNotIn('<form', html.lower())

    def test_csp_forbids_external_scripts_and_forms(self) -> None:
        self.assertIn("default-src 'self'", monitor.CSP)
        self.assertIn("script-src 'self'", monitor.CSP)
        self.assertIn("connect-src 'self'", monitor.CSP)
        self.assertIn("form-action 'none'", monitor.CSP)
        self.assertIn("frame-ancestors 'none'", monitor.CSP)


if __name__ == '__main__':
    unittest.main()

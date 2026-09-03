from __future__ import annotations

from pathlib import Path
from urllib.parse import urlparse

import status_server_v44 as v44

base = v44.base
MONITOR_DIR = Path('/opt/chobyar-trader/monitor')

MONITOR_ROUTES = {
    '/monitor/': ('index.html', 'text/html; charset=utf-8'),
    '/monitor/style.css': ('style.css', 'text/css; charset=utf-8'),
    '/monitor/app.js': ('app.js', 'application/javascript; charset=utf-8'),
    '/monitor/manifest.webmanifest': ('manifest.webmanifest', 'application/manifest+json; charset=utf-8'),
    '/monitor/icon.svg': ('icon.svg', 'image/svg+xml; charset=utf-8'),
    '/monitor/sw.js': ('sw.js', 'application/javascript; charset=utf-8'),
}

CSP = (
    "default-src 'self'; "
    "connect-src 'self'; "
    "img-src 'self' data:; "
    "style-src 'self'; "
    "script-src 'self'; "
    "manifest-src 'self'; "
    "object-src 'none'; "
    "base-uri 'none'; "
    "frame-ancestors 'none'; "
    "form-action 'none'"
)


def monitor_asset(path: str) -> tuple[Path, str] | None:
    route = MONITOR_ROUTES.get(path)
    if not route:
        return None
    filename, content_type = route
    return MONITOR_DIR / filename, content_type


def send_monitor_asset(handler, asset: Path, content_type: str) -> None:
    try:
        body = asset.read_bytes()
    except Exception:
        handler.send_json(503, {'ok': False, 'error': 'monitor_asset_unavailable'})
        return

    handler.send_response(200)
    handler.send_header('Content-Type', content_type)
    handler.send_header('Content-Length', str(len(body)))
    handler.send_header('Cache-Control', 'no-store')
    handler.send_header('X-Content-Type-Options', 'nosniff')
    handler.send_header('Referrer-Policy', 'no-referrer')
    handler.send_header('Content-Security-Policy', CSP)
    handler.send_header('Permissions-Policy', 'camera=(), microphone=(), geolocation=(), payment=()')
    if asset.name == 'sw.js':
        handler.send_header('Service-Worker-Allowed', '/monitor/')
    handler.end_headers()
    handler.wfile.write(body)


_original_do_GET = base.Handler.do_GET


def monitor_do_GET(self) -> None:
    path = urlparse(self.path).path
    if path == '/monitor':
        self.send_response(302)
        self.send_header('Location', '/monitor/')
        self.send_header('Cache-Control', 'no-store')
        self.send_header('X-Content-Type-Options', 'nosniff')
        self.end_headers()
        return

    route = monitor_asset(path)
    if route is not None:
        send_monitor_asset(self, *route)
        return

    _original_do_GET(self)


# Extend only GET routing. Existing /health, /public-report and HMAC /status
# behavior is delegated unchanged to the proven v4.4 status server.
base.Handler.do_GET = monitor_do_GET


if __name__ == '__main__':
    env = base.read_auth_env()
    port = int(env.get('STATUS_PORT', '8787'))
    base.ThreadingHTTPServer(('0.0.0.0', port), base.Handler).serve_forever()

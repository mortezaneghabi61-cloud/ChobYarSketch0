from __future__ import annotations

import status_server_v48 as v48

base = v48.base
v45 = v48.v47.v45

# Keep the original strict allow-list model and add only the two new read-only
# UI assets plus the v4.8.1 shell. No new API/control route is introduced.
v45.MONITOR_ROUTES.update({
    '/monitor/': ('index_v481.html', 'text/html; charset=utf-8'),
    '/monitor/position_detail.js': ('position_detail.js', 'application/javascript; charset=utf-8'),
    '/monitor/position_detail.css': ('position_detail.css', 'text/css; charset=utf-8'),
})

if __name__ == '__main__':
    env = base.read_auth_env()
    port = int(env.get('STATUS_PORT', '8787'))
    base.ThreadingHTTPServer(('0.0.0.0', port), base.Handler).serve_forever()

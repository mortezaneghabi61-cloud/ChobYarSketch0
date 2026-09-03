from __future__ import annotations

import hashlib
import hmac
import json
import os
import secrets
import sys
import time
from pathlib import Path

import httpx

ENV = Path('/opt/chobyar-trader/.env')

def read_env() -> dict[str, str]:
    out = {}
    for raw in ENV.read_text(encoding='utf-8').splitlines():
        if '=' in raw and not raw.lstrip().startswith('#'):
            k, v = raw.split('=', 1)
            key = k.strip()
            if key in {'STATUS_HMAC_SECRET', 'STATUS_PORT'}:
                out[key] = v.strip()
    return out


def main() -> int:
    env = read_env()
    secret = env.get('STATUS_HMAC_SECRET', '')
    port = env.get('STATUS_PORT', '8787')
    url = sys.argv[1] if len(sys.argv) > 1 else f'http://127.0.0.1:{port}/status'
    path = '/status'
    ts = str(int(time.time()))
    nonce = secrets.token_hex(16)
    msg = f'{ts}\n{nonce}\nGET\n{path}'.encode()
    signature = hmac.new(secret.encode(), msg, hashlib.sha256).hexdigest()
    r = httpx.get(url, timeout=10.0, headers={
        'X-ChobYar-Timestamp': ts,
        'X-ChobYar-Nonce': nonce,
        'X-ChobYar-Signature': signature,
    })
    r.raise_for_status()
    body = r.content
    expected_response = hmac.new(secret.encode(), body, hashlib.sha256).hexdigest()
    got_response = r.headers.get('X-ChobYar-Response-Signature', '')
    if not hmac.compare_digest(expected_response, got_response):
        raise SystemExit('FAIL-CLOSED: invalid status response signature')
    print(json.dumps(r.json(), ensure_ascii=False, indent=2))
    return 0

if __name__ == '__main__':
    raise SystemExit(main())

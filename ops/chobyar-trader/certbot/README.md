# Certificate deployment hook

This is the source-controlled copy of the existing VPS hook at
`/etc/letsencrypt/renewal-hooks/deploy/reload-nginx`. It is not an installer
and CI never deploys it. No Trader, Status, or shadow configuration changes.

## Proven incident and repair

On 2026-09-10 nginx served a certificate expiring at 08:06:35 UTC even
though Certbot had renewed the certificate on disk. The renewal configuration
used webroot authentication, had no installer or configured renewal hooks,
and the pre/deploy/post hook directories were empty. Reloading nginx made
strict HTTPS succeed. A deploy hook was then installed and tested through
`certbot renew --dry-run --run-deploy-hooks --non-interactive`.

The checked-in hook matches that user-supplied VPS evidence byte for byte:
`c5b67a43550d9e1e3cf1ba8db013cf40a95c962e3d1df85a74594132ebfba0a0`.
The deployed file has mode 0755. nginx configuration validation must succeed
before reload; validation or reload failure must return nonzero.

## Adoption and verification

After squash merge, fetch this file from the exact merged commit and compare
its SHA256 and bytes with the installed hook. Matching files need no rewrite
or additional reload. Unexpected differences require inspection before any
replacement. This PR does not authorize overwriting unrelated renewal hooks.

For a future installation, review the exact merged source and target first,
install only this hook as a root-owned executable without group/world write
permission, and record Trader/Status PID and start timestamps before/after.
Do not modify the auto-generated snap renewal service. Keep strict certificate
verification enabled; do not use insecure HTTPS as the monitoring fix.

Run tests locally with:

```sh
python3 -m unittest discover -s ops/chobyar-trader/tests -p test_certificate_hook.py -v
```

Tests use isolated command doubles and do not touch system services, ACME,
credentials, `.env`, trading, risk settings, or the network. They prove command
order, fail-closed exits, shell syntax and exact equivalence to the installed
hook. They do not replace a real Certbot integration test on the VPS.

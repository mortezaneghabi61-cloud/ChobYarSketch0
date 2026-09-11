import hashlib
from pathlib import Path
import subprocess
import tempfile
import unittest


HOOK = Path(__file__).resolve().parents[1] / "certbot/reload-nginx"
DEPLOYED_SHA256 = "c5b67a43550d9e1e3cf1ba8db013cf40a95c962e3d1df85a74594132ebfba0a0"


class CertificateHookTest(unittest.TestCase):
    def run_hook(self, validation_exit=0, reload_exit=0):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            log = root / "calls"
            commands = {
                "nginx": '#!/bin/sh\nprintf "nginx %s\\n" "$*" >> "$CALLS"\nexit "$VALIDATION_EXIT"\n',
                "systemctl": '#!/bin/sh\nprintf "systemctl %s\\n" "$*" >> "$CALLS"\nexit "$RELOAD_EXIT"\n',
            }
            for name, body in commands.items():
                path = root / name
                path.write_text(body, encoding="ascii")
                path.chmod(0o700)
            # Isolated command doubles: no production services or network used.
            env = {"PATH": str(root), "CALLS": str(log),
                   "VALIDATION_EXIT": str(validation_exit), "RELOAD_EXIT": str(reload_exit)}
            result = subprocess.run(["/bin/sh", str(HOOK)], env=env,
                                    capture_output=True, text=True, timeout=5)
            calls = log.read_text().splitlines() if log.exists() else []
            return result.returncode, calls

    def test_source_exists(self):
        self.assertTrue(HOOK.is_file(), "Deployed certificate hook is not versioned")

    def test_matches_verified_deployment(self):
        self.assertEqual(hashlib.sha256(HOOK.read_bytes()).hexdigest(), DEPLOYED_SHA256)

    def test_valid_config_reloads_nginx_only(self):
        self.assertEqual(self.run_hook(), (0, ["nginx -t", "systemctl reload nginx"]))

    def test_invalid_config_never_reloads(self):
        self.assertEqual(self.run_hook(validation_exit=1), (1, ["nginx -t"]))

    def test_reload_failure_is_not_hidden(self):
        self.assertEqual(self.run_hook(reload_exit=5),
                         (5, ["nginx -t", "systemctl reload nginx"]))

    def test_no_runtime_or_credentials_surface(self):
        commands = HOOK.read_text().splitlines()
        self.assertEqual(commands, ["#!/bin/sh", "set -eu", "nginx -t", "systemctl reload nginx"])

    def test_shell_syntax(self):
        result = subprocess.run(["/bin/sh", "-n", str(HOOK)], capture_output=True, timeout=5)
        self.assertEqual(result.returncode, 0)


if __name__ == "__main__":
    unittest.main()

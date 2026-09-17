import pathlib
import re
import unittest


ROOT = pathlib.Path(__file__).resolve().parent
SCRIPT = (ROOT / "setup-chobyar-monitor.ps1").read_text(encoding="utf-8")


class WindowsLaptopMonitorTests(unittest.TestCase):
    def test_requires_https_and_uses_read_only_report(self):
        self.assertIn('$uri.Scheme -ne "https"', SCRIPT)
        self.assertIn('$reportUrl = "$root/public-report"', SCRIPT)
        self.assertRegex(SCRIPT, r"Invoke-RestMethod.+-Method Get")
        self.assertNotRegex(SCRIPT, r"-Method\s+(Post|Put|Patch|Delete)")

    def test_fails_closed_on_runtime_safety_contract(self):
        required = [
            '$report.ok -ne $true',
            '$report.public_report -ne $true',
            '[string]$report.mode -ne "paper"',
            '$report.live_locked -ne $true',
            '[int]$report.report_version -lt 8',
            '$report.paper_exploration.execution_authority -ne $false',
        ]
        for check in required:
            with self.subTest(check=check):
                self.assertIn(check, SCRIPT)

    def test_contains_no_execution_or_secret_capability(self):
        forbidden = [
            "api_key",
            "apikey",
            "hmac_secret",
            "exchange_key",
            "submit_order",
            "create_order",
            "place_order",
            "cancel_order",
            "withdraw",
        ]
        lowered = SCRIPT.lower()
        for term in forbidden:
            with self.subTest(term=term):
                self.assertNotIn(term, lowered)

    def test_validates_before_creating_shortcut(self):
        safety_end = SCRIPT.index(
            '$report.paper_exploration.execution_authority -ne $false'
        )
        shortcut_creation = SCRIPT.index("$shell.CreateShortcut")
        self.assertLess(safety_end, shortcut_creation)

    def test_edge_app_is_monitor_only(self):
        self.assertIn('--app=', SCRIPT)
        self.assertIn('$monitorUrl = "$root/monitor/"', SCRIPT)
        self.assertIn('execution_controls = $false', SCRIPT)


if __name__ == "__main__":
    unittest.main()

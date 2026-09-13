import unittest
from pathlib import Path


class InstallerContractTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.text = Path(__file__).with_name("install-tablet-monitor-v60.sh").read_text()

    def test_only_status_service_is_restarted(self):
        self.assertEqual(self.text.count('systemctl restart "$STATUS_SERVICE"'), 2)
        self.assertNotIn('systemctl restart "$service"', self.text)

    def test_three_runtime_pids_are_protected(self):
        for name in ("chobyar-trader.service", "chobyar-profit-protection-shadow.service", "chobyar-paper-exploration.service"):
            self.assertIn(name, self.text)
        self.assertIn('PID changed', self.text)

    def test_installer_is_sha_pinned_and_rollback_capable(self):
        self.assertIn('exact 40-character commit SHA required', self.text)
        self.assertIn('rollback()', self.text)
        self.assertIn('downloaded commit mismatch', self.text)

    def test_cannot_replace_unknown_status_baseline(self):
        self.assertIn('unexpected status ExecStart; refusing blind replacement', self.text)


if __name__ == "__main__":
    unittest.main()

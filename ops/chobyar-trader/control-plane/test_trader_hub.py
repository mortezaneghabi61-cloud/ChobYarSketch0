import unittest
import pathlib
import sys
sys.path.insert(0,str(pathlib.Path(__file__).parent))
from trader_hub import validate_public, render_event

class HubTests(unittest.TestCase):
    def test_valid_sanitized_handoff(self):
        body=render_event("Codex","TR-HUB-001","Passed local tests","commit abcd1234",
                          "4 tests pass","Review pull request","2026-10-16T20:00:00Z")
        self.assertIn("TR-HUB-001",body)
        self.assertIn("PAPER ONLY",body)
    def test_reject_email(self):
        with self.assertRaisesRegex(ValueError,"REFUSE_PUBLICATION"):
            validate_public("Person a@example.com changed file")
    def test_reject_token_value(self):
        with self.assertRaisesRegex(ValueError,"REFUSE_PUBLICATION"):
            validate_public("api_key: test-suspected-credential")
    def test_reject_vps_ip(self):
        with self.assertRaisesRegex(ValueError,"REFUSE_PUBLICATION"):
            validate_public("VPS 123.45.67.89")
    def test_reject_empty_field(self):
        with self.assertRaises(ValueError):validate_public("  ")
    def test_reject_unscoped_task(self):
        with self.assertRaises(ValueError):
            render_event("Work","CAD-001","ok","ok","ok","ok","2026-10-16T20:00:00Z")

if __name__=="__main__":unittest.main(verbosity=2)

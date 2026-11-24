import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
TAGS = "-tags=with_clash_api,with_gvisor,with_quic,with_wireguard,with_utls"


class XHTTPInboundCI(unittest.TestCase):
    def test_all_builds_run_transport_and_tagged_root_race_tests(self):
        for file_name in ("verify-android.yml", "preview.yml", "release.yml"):
            with self.subTest(workflow=file_name):
                text = (ROOT / ".github/workflows" / file_name).read_text()
                commands = [line for line in text.splitlines() if "cd libcore && go test " in line]
                pkg = "./protocol/vless/xhttp"
                self.assertTrue(any(pkg in line and "-race" not in line for line in commands))
                self.assertTrue(any(pkg in line and "-race" in line for line in commands))
                self.assertTrue(any(f"-race {TAGS} .)" in line for line in commands))

    def test_pr_gate_checks_example_through_both_registries(self):
        workflow = (ROOT / ".github/workflows/verify-android.yml").read_text()
        self.assertIn("python3 tools/check_xhttp_inbound_example.py", workflow)
        self.assertIn('sing-box check -c "$xhttp_example"', workflow)
        self.assertIn("TestVLESSXHTTPInboundExample", (ROOT / "libcore/vless_xhttp_inbound_test.go").read_text())

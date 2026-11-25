import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
WORKFLOW = ROOT / ".github/workflows/verify-android.yml"
XRAY_ZIP_URL = "https://github.com/XTLS/Xray-core/releases/download/v26.11.9/Xray-linux-64.zip"
XRAY_ZIP_SHA256 = "b651f7e1d9966ee2c1b1ee1bf8b52c72771824d3065dce96bbc8bcc19ed95f82"
SCRIPT = ROOT / "tools/verify_xhttp_xray_interop.sh"
INTEROP_SUBTESTS = 12
GATE_STEP = ('      - name: Verify Xray v26.11.9 XHTTP interop matrix\n'
             '        run: bash tools/verify_xhttp_xray_interop.sh\n')


class XrayXHTTPInteropCI(unittest.TestCase):
    """PR 门禁必须真跑真实 Xray 互通矩阵：版本与 SHA-256 钉死，跳过与少跑都算失败。"""

    def setUp(self):
        self.workflow = WORKFLOW.read_text()

    def test_interop_step_is_part_of_the_libcore_job(self):
        self.assertIn("name: Verify Xray v26.11.9 XHTTP interop matrix", self.workflow)
        libcore_job = self.workflow.split("  app:", 1)[0]
        self.assertIn(GATE_STEP, libcore_job)

    def test_preview_and_formal_release_require_the_interop_gate(self):
        for filename in ("preview.yml", "release.yml"):
            with self.subTest(workflow=filename):
                workflow = (WORKFLOW.parent / filename).read_text()
                libcore = workflow.split("\n  libcore:\n", 1)[1].split("\n  build:\n", 1)[0]
                self.assertIn(GATE_STEP, libcore)
                publish = workflow.split("\n  publish:\n", 1)[1].split("\n    steps:\n", 1)[0]
                self.assertIn("\n      - libcore\n", publish)
                self.assertNotIn("always()", publish)

    def test_release_archive_is_pinned_by_url_and_sha256(self):
        step = SCRIPT.read_text()
        self.assertIn(XRAY_ZIP_URL, step)
        self.assertIn(XRAY_ZIP_SHA256, step)
        self.assertIn("sha256sum --check --strict -", step)

    def test_interop_test_runs_with_the_downloaded_binary(self):
        step = SCRIPT.read_text()
        self.assertIn("XRAY_BIN=", step)
        self.assertIn("-run '^TestRealXrayXHTTPInterop$'", step)
        self.assertIn("(cd libcore &&", step)
        self.assertIn("TestRealXrayXHTTPInterop", (ROOT / "libcore/vless_xhttp_xray_interop_test.go").read_text())

    def test_step_fails_closed_on_skip_or_missing_subtests(self):
        step = SCRIPT.read_text()
        self.assertIn("--- SKIP", step)
        self.assertIn(str(INTEROP_SUBTESTS), step)
        self.assertIn("exit 1", step)


if __name__ == "__main__":
    unittest.main()

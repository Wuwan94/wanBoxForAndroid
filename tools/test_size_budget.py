import contextlib
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import zipfile


SCRIPT = Path(__file__).with_name("size_budget.py")
SPEC = importlib.util.spec_from_file_location("size_budget", SCRIPT)
size_budget = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(size_budget)


class SizeBudgetLabelTest(unittest.TestCase):
    def test_default_limit_is_reported_as_26_mib(self):
        with tempfile.TemporaryDirectory() as temp:
            module = Path(temp) / "module.zip"
            apk = Path(temp) / "app.apk"
            module.write_bytes(b"module")
            apk.write_bytes(b"apk")
            output = io.StringIO()
            args = [
                "size_budget.py",
                "--module", str(module),
                "--apk", str(apk),
                "--enforce-limit",
            ]
            with patch("sys.argv", args), contextlib.redirect_stdout(output):
                result = size_budget.main()

        self.assertEqual(0, result)
        self.assertEqual(27_262_976, size_budget.LIMIT)
        self.assertIn("(26 MiB)", output.getvalue())
        self.assertNotIn("(25 MiB)", output.getvalue())


class SizeBudgetWebUITest(unittest.TestCase):
    files = (
        "index.html",
        "app.mjs",
        "controller.mjs",
        "style.css",
    )

    def run_budget(self, source: Path, module_zip: Path, apk: Path, json_out: Path | None = None) -> int:
        args = [
            "size_budget.py",
            "--module", str(module_zip),
            "--apk", str(apk),
            "--webui-source", str(source),
            "--enforce-limit",
        ]
        if json_out is not None:
            args.extend(["--json", str(json_out)])
        with patch("sys.argv", args), contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
            return size_budget.main()

    def setup_payloads(self, temp: Path, source_bytes: int = 1, packaged_bytes: int = 1):
        source = temp / "webroot"
        source.mkdir()
        for name in self.files:
            (source / name).write_bytes(b"x")
        (source / "index.html").write_bytes(b"x" * source_bytes)
        module_zip = temp / "module.zip"
        with zipfile.ZipFile(module_zip, "w", compression=zipfile.ZIP_DEFLATED) as archive:
            for name in self.files:
                data = b"x" * (packaged_bytes if name == "index.html" else 1)
                archive.writestr(f"webroot/{name}", data)
        apk = temp / "app.apk"
        apk.write_bytes(b"apk")
        return source, module_zip, apk

    def test_source_webui_over_one_mib_fails(self):
        with tempfile.TemporaryDirectory() as temp:
            source, module_zip, apk = self.setup_payloads(Path(temp), source_bytes=(1 << 20) + 1)
            result = self.run_budget(source, module_zip, apk)
        self.assertEqual(1, result)

    def test_packaged_webui_over_one_mib_fails(self):
        with tempfile.TemporaryDirectory() as temp:
            source, module_zip, apk = self.setup_payloads(Path(temp), packaged_bytes=(1 << 20) + 1)
            result = self.run_budget(source, module_zip, apk)
        self.assertEqual(1, result)

    def test_json_report_includes_source_and_packaged_webui_bytes(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            source, module_zip, apk = self.setup_payloads(root)
            report = root / "budget.json"
            result = self.run_budget(source, module_zip, apk, report)
            payload = json.loads(report.read_text())
        self.assertEqual(0, result)
        self.assertEqual(4, payload["source_webui"])
        self.assertEqual(4, payload["package_webui_uncompressed"])
        self.assertEqual(1 << 20, payload["webui_limit"])

    def test_webui_only_gate_runs_without_apk_or_download_total(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            source, module_zip, _apk = self.setup_payloads(root)
            report = root / "webui-budget.json"
            args = [
                "size_budget.py",
                "--module", str(module_zip),
                "--webui-source", str(source),
                "--webui-only",
                "--json", str(report),
            ]
            with patch("sys.argv", args), contextlib.redirect_stdout(io.StringIO()):
                result = size_budget.main()
            payload = json.loads(report.read_text())

        self.assertEqual(0, result)
        self.assertEqual(4, payload["source_webui"])
        self.assertEqual(4, payload["package_webui_uncompressed"])
        self.assertNotIn("total", payload)


if __name__ == "__main__":
    unittest.main()

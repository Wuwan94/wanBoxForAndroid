import contextlib
import importlib.util
import io
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch


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


if __name__ == "__main__":
    unittest.main()

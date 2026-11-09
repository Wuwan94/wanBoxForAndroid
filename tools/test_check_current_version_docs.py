# @author 雾晚
"""当前版本文档一致性检查的离线回归测试。

`test_current_tree_passes_check` 校验仓库当前发布状态；其余用例证明检查器能识别
人为注入的漂移，而不是永远返回通过。
"""
from __future__ import annotations

import importlib.util
import shutil
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
CHECKER_PATH = ROOT / "tools" / "check_current_version_docs.py"

spec = importlib.util.spec_from_file_location("check_current_version_docs", CHECKER_PATH)
assert spec and spec.loader
checker = importlib.util.module_from_spec(spec)
spec.loader.exec_module(checker)


def make_tree() -> tuple[Path, tempfile.TemporaryDirectory]:
    tmp = tempfile.TemporaryDirectory(prefix="wanbox-doc-check-")
    root = Path(tmp.name)
    for rel in checker.FILES.values():
        source = ROOT / rel
        target = root / rel
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source, target)
    return root, tmp


class CurrentVersionDocsTest(unittest.TestCase):
    def test_current_tree_passes_check(self):
        """仓库当前状态必须与「正式 / 已发布预览」快照一致。"""
        self.assertEqual([], checker.failures(ROOT))

    def test_checker_flags_readme_drift(self):
        root, tmp = make_tree()
        try:
            readme = root / checker.FILES["readme"]
            text = readme.read_text(encoding="utf-8")
            text = text.replace(
                "已发布预览版为 [v3.0.13-preview.2]",
                "已发布预览版为 [v3.0.13-preview.1]",
            )
            readme.write_text(text, encoding="utf-8")
            errors = checker.failures(root)
            self.assertTrue(any("README.md" in e for e in errors), errors)
        finally:
            tmp.cleanup()

    def test_checker_flags_stale_version_metadata(self):
        root, tmp = make_tree()
        try:
            props = root / checker.FILES["properties"]
            props.write_text(
                props.read_text(encoding="utf-8").replace(
                    "PRE_VERSION_NAME=3.0.13-preview.2", "PRE_VERSION_NAME=3.0.13-preview.1"
                ),
                encoding="utf-8",
            )
            errors = checker.failures(root)
            self.assertTrue(any("PRE_VERSION_NAME" in e for e in errors), errors)
        finally:
            tmp.cleanup()

    def test_checker_flags_release_commit_written_as_current_main(self):
        root, tmp = make_tree()
        try:
            current = root / checker.FILES["current"]
            current.write_text(
                current.read_text(encoding="utf-8").replace(
                    "| 发布基线 |", "| 当前源码基线 |"
                ),
                encoding="utf-8",
            )
            errors = checker.failures(root)
            self.assertTrue(any("当前源码基线" in e for e in errors), errors)
        finally:
            tmp.cleanup()

    def test_checker_flags_published_written_as_candidate(self):
        root, tmp = make_tree()
        try:
            notes = root / checker.FILES["notes"]
            notes.write_text(
                notes.read_text(encoding="utf-8").replace(
                    "**状态：稳定性预览版。**", "**状态：未发布候选。**"
                ),
                encoding="utf-8",
            )
            errors = checker.failures(root)
            self.assertTrue(any("PREVIEW_NOTES.md" in e for e in errors), errors)
        finally:
            tmp.cleanup()

    def test_checker_is_offline(self):
        source = CHECKER_PATH.read_text(encoding="utf-8")
        for forbidden in ("urllib", "requests.", "http.client", "socket.", "urlopen"):
            self.assertNotIn(forbidden, source)


if __name__ == "__main__":
    unittest.main()

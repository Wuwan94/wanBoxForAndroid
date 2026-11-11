import importlib.util
import unittest
from pathlib import Path


SCRIPT = Path(__file__).with_name("prepare_preview_release_notes.py")
spec = importlib.util.spec_from_file_location("prepare_preview_release_notes", SCRIPT)
module = importlib.util.module_from_spec(spec)
assert spec and spec.loader
spec.loader.exec_module(module)


class PreparePreviewReleaseNotesTest(unittest.TestCase):
    def test_strips_candidate_title_and_status_but_preserves_release_body(self):
        source = (
            "# v3.0.13-preview.4 发行说明（候选）\n\n"
            "**状态：未发布的稳定性预览版候选。** 仅源码状态。\n\n"
            "稳定性预览版，不替换稳定版 `v3.0.11`。\n\n"
            "## 更新\n\n- 修复过渡态切换。\n"
        )
        result = module.render_release_notes(source)
        self.assertTrue(result.startswith("# v3.0.13-preview.4 发行说明\n"))
        self.assertNotIn("# v3.0.13-preview.4 发行说明（候选）", result)
        self.assertNotIn(module.STATUS_PREFIX, result)
        self.assertIn("稳定性预览版，不替换稳定版 `v3.0.11`。", result)
        self.assertIn("## 更新", result)

    def test_current_source_notes_render_without_candidate_marker(self):
        root = SCRIPT.parents[1]
        source = (root / "PREVIEW_NOTES.md").read_text(encoding="utf-8")
        result = module.render_release_notes(source)
        self.assertTrue(result.startswith("# v3.0.13-preview.4 发行说明\n"))
        self.assertNotIn(module.STATUS_PREFIX, result)

    def test_rejects_notes_without_source_candidate_marker(self):
        source = "# v3.0.13-preview.4 发行说明\n\n## 更新\n\n- 内容\n"
        with self.assertRaisesRegex(ValueError, "title must be explicitly marked"):
            module.render_release_notes(source)

    def test_release_workflow_uses_transformer_not_raw_copy(self):
        workflow = SCRIPT.parents[1] / ".github/workflows/preview.yml"
        text = workflow.read_text(encoding="utf-8")
        self.assertIn("tools/prepare_preview_release_notes.py --input PREVIEW_NOTES.md", text)
        self.assertNotIn('cp PREVIEW_NOTES.md "$RUNNER_TEMP/preview-notes.md"', text)


if __name__ == "__main__":
    unittest.main()

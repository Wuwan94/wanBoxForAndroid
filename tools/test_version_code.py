# @author 雾晚
# SPDX-License-Identifier: GPL-3.0-or-later
"""tools/version_code.py 的规则单测（G5）。

覆盖《02-版本与升级方案》第 2 节的 7 类用例：两条渠道换算、覆盖升级
可行性、同版本拒绝、降级拒绝、模块版本方向、非法值。
"""
import json
import tempfile
import unittest
from pathlib import Path

from version_code import (
    APK_MULTIPLIER,
    VersionError,
    apk_version_code,
    check_kotlin_rule,
    check_upgrade,
    main,
    parse_code,
    parse_properties,
    resolve,
)


BASE = """
# comment
PACKAGE_NAME=com.lit008834.pixel.wanboxforandroid
VERSION_NAME=3.0.10
VERSION_CODE=2011
PRE_VERSION_NAME=3.0.10-preview.3
PRE_VERSION_CODE=2012
"""


class VersionCodeRuleTest(unittest.TestCase):
    def test_1_formal_channel_conversion(self):
        r = resolve(parse_properties(BASE), "formal")
        self.assertEqual("3.0.10", r["version_name"])
        self.assertEqual(2011, r["metadata_code"])
        self.assertEqual(10055, r["apk_version_code"])
        self.assertEqual(2011, r["module_version_code"])

    def test_2_preview_channel_conversion(self):
        r = resolve(parse_properties(BASE), "preview")
        self.assertEqual("3.0.10-preview.3", r["version_name"])
        self.assertEqual(2012, r["metadata_code"])
        self.assertEqual(10063, r["apk_version_code"])
        self.assertEqual(2012, r["module_version_code"])

    def test_3_upgrade_from_preview32_is_feasible(self):
        verdict = check_upgrade(10055, 2011, 10050, 2010)
        self.assertTrue(verdict["ok"], verdict["reasons"])
        self.assertEqual([], verdict["reasons"])

    def test_4_equal_version_is_rejected(self):
        verdict = check_upgrade(10050, 2010, 10050, 2010)
        self.assertFalse(verdict["ok"])
        self.assertEqual(2, len(verdict["reasons"]))

    def test_5_downgrade_is_rejected(self):
        verdict = check_upgrade(1810, 362, 10050, 2010)
        self.assertFalse(verdict["ok"])
        self.assertTrue(any("APK versionCode" in x for x in verdict["reasons"]))
        self.assertTrue(any("模块 versionCode" in x for x in verdict["reasons"]))

    def test_6_module_version_must_not_regress(self):
        # APK 递增但模块仍是旧值，必须整体判失败
        verdict = check_upgrade(10055, 2010, 10050, 2010)
        self.assertFalse(verdict["ok"])
        self.assertTrue(any("模块 versionCode" in x for x in verdict["reasons"]))

    def test_7_invalid_values_are_rejected(self):
        for bad in ("", "abc", "-1", "0", "02011", "4294967296", 3.5):
            with self.subTest(bad=bad):
                with self.assertRaises(VersionError):
                    parse_code(bad)
        with self.assertRaises(VersionError):
            parse_properties("VERSION_NAME=3.0.10\nVERSION_NAME=3.0.9\n")
        with self.assertRaises(VersionError):
            resolve(parse_properties(BASE), "nightly")

    def test_multiplier_is_the_single_rule(self):
        self.assertEqual(5, APK_MULTIPLIER)
        self.assertEqual(10055, apk_version_code(2011, "3.0.10"))
        self.assertEqual(10063, apk_version_code(2012, "3.0.10-preview.3"))
        self.assertEqual(1810, apk_version_code(362))

    def test_formal_upgrade_stays_above_published_preview(self):
        props = parse_properties("""
VERSION_NAME=3.0.11
VERSION_CODE=2015
PRE_VERSION_NAME=3.0.11-preview.1
PRE_VERSION_CODE=2015
""")
        formal = resolve(props, "formal")
        preview = resolve(props, "preview")
        self.assertEqual(10075, formal["apk_version_code"])
        self.assertEqual(10076, preview["apk_version_code"])
        self.assertGreater(formal["apk_version_code"], 10070)
        self.assertGreater(preview["apk_version_code"], 10070)
        props["PRE_VERSION_NAME"] = "3.0.11-preview.2"
        preview_two = resolve(props, "preview")
        self.assertEqual(10077, preview_two["apk_version_code"])

    def test_invalid_preview_suffix_is_rejected(self):
        with self.assertRaises(VersionError):
            apk_version_code(2015, "3.0.11-preview.5")

    def test_current_channels_advance_verified_release_baselines(self):
        root = Path(__file__).resolve().parents[1]
        properties = parse_properties((root / "nb4a.properties").read_text(encoding="utf-8"))
        evidence = json.loads(
            (root / "docs/preview3-version-evidence.json").read_text(encoding="utf-8")
        )
        for channel in ("formal", "preview"):
            with self.subTest(channel=channel):
                candidate = resolve(properties, channel)
                baseline = evidence["baselines"][channel]
                verdict = check_upgrade(
                    candidate["apk_version_code"],
                    candidate["module_version_code"],
                    baseline["apk_version_code"],
                    baseline["module_version_code"],
                )
                self.assertTrue(verdict["ok"], verdict["reasons"])

    def test_formal_channel_also_advances_verified_preview(self):
        root = Path(__file__).resolve().parents[1]
        formal = resolve(
            parse_properties((root / "nb4a.properties").read_text(encoding="utf-8")), "formal"
        )
        baseline = json.loads(
            (root / "docs/preview3-version-evidence.json").read_text(encoding="utf-8")
        )["baselines"]["preview"]
        verdict = check_upgrade(
            formal["apk_version_code"],
            formal["module_version_code"],
            baseline["apk_version_code"],
            baseline["module_version_code"],
        )
        self.assertTrue(verdict["ok"], verdict["reasons"])


class KotlinRuleConsistencyTest(unittest.TestCase):
    """构建实际执行的 Helpers.kt 复刻必须与规则一致，漂移要在 CI 直接失败。"""

    HELPERS = (
        Path(__file__).resolve().parents[1]
        / "buildSrc/src/main/kotlin/Helpers.kt"
    )

    def test_repo_helpers_match_the_rule(self):
        helpers = self.HELPERS.read_text(encoding="utf-8")
        self.assertEqual([], check_kotlin_rule(helpers))

    def test_multiplier_drift_is_reported(self):
        text = (
            r'private val PREVIEW_VERSION_SUFFIX = Regex("-preview\\.([1-4])$")'
            "\nfun apkVersionCode(code: Int, versionName: String): Int {\n"
            "    return code * 6 + 0\n}\n"
        )
        reasons = check_kotlin_rule(text)
        self.assertTrue(any("乘数" in reason for reason in reasons), reasons)

    def test_suffix_range_drift_is_reported(self):
        text = (
            r'private val PREVIEW_VERSION_SUFFIX = Regex("-preview\\.([1-9])$")'
            "\nfun apkVersionCode(code: Int, versionName: String): Int {\n"
            "    return code * 5 + 0\n}\n"
        )
        reasons = check_kotlin_rule(text)
        self.assertTrue(any("预览序号范围" in reason for reason in reasons), reasons)

    def test_missing_kotlin_rule_is_reported(self):
        self.assertEqual(2, len(check_kotlin_rule("fun unrelated() = 1")))

    def test_cli_check_kotlin_exit_codes(self):
        self.assertEqual(0, main(["--check-kotlin", str(self.HELPERS)]))
        with tempfile.TemporaryDirectory() as tmp:
            drifted = Path(tmp) / "Helpers.kt"
            drifted.write_text(
                self.HELPERS.read_text(encoding="utf-8").replace("code * 5", "code * 6"),
                encoding="utf-8",
            )
            self.assertEqual(1, main(["--check-kotlin", str(drifted)]))


if __name__ == "__main__":
    unittest.main()

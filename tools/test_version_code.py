# @author 雾晚
# SPDX-License-Identifier: GPL-3.0-or-later
"""tools/version_code.py 的规则单测（G5）。

覆盖《02-版本与升级方案》第 2 节的 7 类用例：两条渠道换算、覆盖升级
可行性、同版本拒绝、降级拒绝、模块版本方向、非法值。
"""
import unittest

from version_code import (
    APK_MULTIPLIER,
    VersionError,
    apk_version_code,
    check_upgrade,
    parse_code,
    parse_properties,
    resolve,
)


BASE = """
# comment
PACKAGE_NAME=com.lit008834.pixel.wanboxforandroid
VERSION_NAME=3.0.10
VERSION_CODE=2011
PRE_VERSION_NAME=3.0.10-preview.33
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
        self.assertEqual("3.0.10-preview.33", r["version_name"])
        self.assertEqual(2012, r["metadata_code"])
        self.assertEqual(10060, r["apk_version_code"])
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
        self.assertEqual(10055, apk_version_code(2011))
        self.assertEqual(10060, apk_version_code(2012))
        self.assertEqual(1810, apk_version_code(362))

    def test_preview_line_stays_above_formal_line(self):
        props = parse_properties(BASE)
        formal = resolve(props, "formal")
        preview = resolve(props, "preview")
        # preview.yml:154 的既有断言，改动 VERSION_CODE 后必须继续成立
        self.assertGreater(preview["metadata_code"], formal["metadata_code"])


if __name__ == "__main__":
    unittest.main()

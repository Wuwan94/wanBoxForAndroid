# @author 雾晚
# SPDX-License-Identifier: GPL-3.0-or-later
"""稳定化的源码契约测试（B3 / B4 / B6 / B7 / B8）。

这里检查的是**不会随重构漂移的静态契约**，不是行为验证：
- B3/B4：证书校验默认值、`--no-check` 的真实语义；
- B6/B7：主线程数据库访问与订阅失败路径的"现状记录"，用于把
  "建议优化"与"已复现缺陷"分开，避免把观察包装成修复；
- B8：归档上限修复确实在位（真正的行为测试见
  rootmodule/data_update_archive_test.go）。

真正的 TLS 握手行为由 rootmodule/tls_verification_test.go 覆盖，
Kotlin 侧默认值由 HysteriaFmtTest.kt 覆盖。
"""
import pathlib
import re
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]


def read(rel):
    return (ROOT / rel).read_text(encoding="utf-8")


class NoCheckSemanticsTest(unittest.TestCase):
    def test_no_check_remains_an_update_check_switch(self):
        """B4：--no-check 是关闭版本更新检查，不得被改作 TLS 开关。"""
        for rel in (
            "app/src/main/java/io/nekohasekai/sagernet/bg/proto/BoxInstance.kt",
            "rootmodule/supervisor.go",
        ):
            text = read(rel)
            self.assertIn('--no-check', text, f"{rel}: flag missing")
            self.assertIn(
                "disable update check",
                text.lower(),
                f"{rel}: upstream semantics not documented",
            )
            # 附近不得出现把它当作证书开关的表述
            window = text[max(0, text.index('--no-check') - 500): text.index('--no-check') + 300]
            self.assertNotIn(
                "insecure =", window, f"{rel}: --no-check must not be wired to insecure"
            )

    def test_certificate_verification_defaults_to_enabled(self):
        """B3：默认值必须是 false，且异常兜底不能意外打开跳过。"""
        bean = read("app/src/main/java/io/nekohasekai/sagernet/fmt/hysteria/HysteriaBean.java")
        self.assertIn("if (allowInsecure == null) allowInsecure = false;", bean)

        datastore = read("app/src/main/java/io/nekohasekai/sagernet/database/DataStore.kt")
        self.assertRegex(
            datastore,
            r"globalAllowInsecure\s+by\s+configurationStore\.boolean\([^)]*\)\s*\{\s*false\s*\}",
        )

        fmt = read("app/src/main/java/io/nekohasekai/sagernet/fmt/hysteria/HysteriaFmt.kt")
        self.assertEqual(
            2,
            fmt.count("runCatching { DataStore.globalAllowInsecure }.getOrDefault(false)"),
            "both Hysteria v1 and v2 must fall back to verification-enabled",
        )
        self.assertEqual(2, fmt.count("insecure = bean.allowInsecure == true ||"))

    def test_global_opt_in_is_a_real_setting(self):
        """B3：跳过选项是设置项中的显式动作，不是默认值或异常兜底。"""
        prefs = read("app/src/main/res/xml/global_preferences.xml")
        self.assertIn("GLOBAL_ALLOW_INSECURE", prefs.upper())
        settings = read("app/src/main/java/io/nekohasekai/sagernet/ui/SettingsPreferenceFragment.kt")
        self.assertIn("globalAllowInsecure", settings)


class BehaviourObservationTest(unittest.TestCase):
    """B5–B7：只记录现状。分类为「建议优化（未复现缺陷）」，本轮不改业务代码。"""

    def test_no_main_thread_queries_are_enabled(self):
        app = read("app/build.gradle.kts")
        self.assertNotIn("allowMainThreadQueries", app)

        ui = ROOT / "app/src/main/java/io/nekohasekai/sagernet/ui"
        hits = [
            p.relative_to(ROOT).as_posix()
            for p in ui.rglob("*.kt")
            if re.search(r"SagerDatabase|ProfileManager|GroupManager", p.read_text(encoding="utf-8"))
        ]
        # 记录：这些文件直接引用持久化层，需要逐处确认调用线程；
        # 在无 StrictMode/插桩数据前不下"有缺陷"结论。
        self.assertGreater(len(hits), 0, "expected known direct persistence references")

    def test_subscription_failure_paths_are_still_handled(self):
        updater = read("app/src/main/java/io/nekohasekai/sagernet/group/RawUpdater.kt")
        self.assertGreaterEqual(updater.count("catch"), 5)
        data_update = read("app/src/main/java/io/nekohasekai/sagernet/bg/RootModuleDataUpdate.kt")
        self.assertIn("OwnBox_backup_", data_update)

    def test_lifecycle_tests_exist(self):
        for rel in (
            "app/src/test/java/io/nekohasekai/sagernet/NetworkLifecyclePreservationTest.kt",
            "app/src/test/java/io/nekohasekai/sagernet/group/SubscriptionResolutionRunnerTest.kt",
        ):
            self.assertTrue((ROOT / rel).is_file(), f"missing: {rel}")


class ArchiveBoundContractTest(unittest.TestCase):
    def test_archive_pruning_is_present(self):
        """B8：已复现缺陷必须真的被修，而不只是写进文档。"""
        go = read("rootmodule/data_update.go")
        self.assertIn("const maxDataResetArchives", go)
        self.assertIn("func (r *Runtime) pruneDataResetArchives", go)
        self.assertNotIn("not automatically pruned or deleted", go)
        self.assertIn("pruneDataResetArchives(\"\")", go)
        tests = read("rootmodule/data_update_archive_test.go")
        self.assertIn("TestArchiveCountIsBoundedAcrossRepeatedUpdates", tests)

    def test_invalid_certificate_regression_tests_exist(self):
        tls = read("rootmodule/tls_verification_test.go")
        for name in (
            "TestSelfSignedCertificateFailsByDefaultAndOnlyWorksWithExplicitOptIn",
            "TestExpiredCertificateFailsByDefault",
            "TestHostnameMismatchFailsByDefault",
        ):
            self.assertIn(name, tls)


if __name__ == "__main__":
    unittest.main()

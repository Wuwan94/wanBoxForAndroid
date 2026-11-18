# @author 雾晚
# SPDX-License-Identifier: GPL-3.0-or-later
"""Thin delegation tests for rootmodule/package/customize.sh (B8/N7).

customize.sh is only 23 lines: it checks BOOTMODE/ARCH/API, sources
installer-options.sh, optionally installs the manager APK, and delegates the
real update to ``wanboxctl __internal schedule-install`` for background
application. These tests pin that delegation contract with a stubbed
wanboxctl and Magisk-provided helpers (abort/ui_print/set_perm*); they do
not re-test the Go hot-update logic. Fixture content is entirely fictional.
"""
import os
import pathlib
import shutil
import subprocess
import tempfile
import unittest

SHELL = os.environ.get("ROOT_MODULE_TEST_SHELL") or shutil.which("sh")

WANBOXCTL_STUB = """#!/bin/sh
echo "wanboxctl $*" >> "$CALLS"
case "$1" in
  __internal)
    case "$2" in
      volume-key)
        idx=0
        [ -f "$IDX_FILE" ] && idx=$(cat "$IDX_FILE")
        idx=$((idx + 1))
        printf '%s' "$idx" > "$IDX_FILE"
        key=$(sed -n "${idx}p" "$EVENTS_FILE")
        [ -n "$key" ] || key=timeout
        printf '%s\\n' "$key"
        ;;
      schedule-install)
        exit "$SCHEDULE_RESULT"
        ;;
      install-manager)
        exit "$INSTALL_MANAGER_RESULT"
        ;;
    esac
    ;;
esac
exit 0
"""

WRAPPER = r"""
MODPATH=$1
OUTPUT=$2
abort() { printf 'ABORT: %s\n' "$*" >> "$OUTPUT"; exit 1; }
ui_print() { printf '%s\n' "$*"; }
set_perm_recursive() { printf 'set_perm_recursive %s\n' "$*" >> "$CALLS"; }
set_perm() { printf 'set_perm %s\n' "$*" >> "$CALLS"; }
. "$MODPATH/customize.sh"
"""


@unittest.skipUnless(SHELL, "POSIX shell is required to execute customize.sh")
class CustomizeDelegateTest(unittest.TestCase):
    def run_customize(self, events, bootmode="true", arch="arm64", api="31",
                      schedule_result=0, install_manager_result=0, with_apk=True):
        """Run the real customize.sh in a fake module dir.

        Returns (returncode, stdout, calls, live_sentinel_intact).
        ``live`` is a separate directory standing in for the currently
        installed module: customize.sh must never touch it.
        """
        temp = pathlib.Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, temp, True)
        package = pathlib.Path(__file__).parent / "package"
        modpath = temp / "mod"
        (modpath / "bin").mkdir(parents=True)
        for name in ("customize.sh", "installer-options.sh"):
            shutil.copy(package / name, modpath / name)
        ctl = modpath / "bin" / "wanboxctl"
        ctl.write_text(WANBOXCTL_STUB)
        ctl.chmod(0o755)
        if with_apk:
            (modpath / "manager.apk").write_bytes(b"fixture")
        live = temp / "live"
        live.mkdir()
        sentinel = live / "keep-me"
        sentinel.write_text("current module\n")
        events_file = temp / "events"
        events_file.write_text("\n".join(events) + "\n")
        calls = temp / "calls"
        calls.write_text("")
        output = temp / "output"
        output.write_text("")
        env = dict(os.environ)
        env.update({
            "BOOTMODE": bootmode,
            "ARCH": arch,
            "API": api,
            "MODPATH": modpath.as_posix(),
            "CALLS": calls.as_posix(),
            "EVENTS_FILE": events_file.as_posix(),
            "IDX_FILE": (temp / "idx").as_posix(),
            "SCHEDULE_RESULT": str(schedule_result),
            "INSTALL_MANAGER_RESULT": str(install_manager_result),
        })
        result = subprocess.run(
            [SHELL, "-c", WRAPPER, "customize-test",
             modpath.as_posix(), output.as_posix()],
            text=True, encoding="utf-8", capture_output=True, timeout=15, env=env)
        return (result.returncode, output.read_text() + result.stdout,
                calls.read_text(), sentinel.read_text() == "current module\n")

    def schedule_calls(self, calls):
        return [line for line in calls.splitlines() if "schedule-install" in line]

    def test_bootmode_must_be_true(self):
        code, out, calls, live_ok = self.run_customize(
            ["timeout", "timeout"], bootmode="false")
        self.assertNotEqual(0, code)
        self.assertIn("请在 Magisk 或 KernelSU 管理器中安装", out)
        self.assertEqual([], self.schedule_calls(calls))
        self.assertTrue(live_ok)

    def test_arch_must_be_arm64(self):
        code, out, calls, live_ok = self.run_customize(
            ["timeout", "timeout"], arch="arm")
        self.assertNotEqual(0, code)
        self.assertIn("arm64-v8a", out)
        self.assertEqual([], self.schedule_calls(calls))
        self.assertTrue(live_ok)

    def test_api_below_31_aborts(self):
        code, out, calls, live_ok = self.run_customize(
            ["timeout", "timeout"], api="30")
        self.assertNotEqual(0, code)
        self.assertIn("Android 12", out)
        self.assertEqual([], self.schedule_calls(calls))
        self.assertTrue(live_ok)

    def test_default_preserve_schedules_background_update(self):
        code, out, calls, live_ok = self.run_customize(["timeout", "timeout"])
        self.assertEqual(0, code, out)
        self.assertIn("正在等待管理器完成安装", out)
        self.assertIn("热更新成功后无需重启手机", out)
        self.assertIn("set_perm_recursive", calls)
        self.assertIn("set_perm", calls)
        self.assertIn("bin/wanboxctl 0 0 0700", calls)
        self.assertIn("wanboxctl __internal install-manager", calls)
        scheduled = self.schedule_calls(calls)
        self.assertEqual(1, len(scheduled))
        self.assertTrue(scheduled[0].endswith(" preserve"), scheduled)
        self.assertTrue(live_ok)

    def test_nodes_choice_passes_mode_to_scheduler(self):
        code, out, calls, _ = self.run_customize(
            ["up", "down", "down", "down"])
        self.assertEqual(0, code, out)
        self.assertIn("仅保留节点和订阅", out)
        scheduled = self.schedule_calls(calls)
        self.assertEqual(1, len(scheduled))
        self.assertTrue(scheduled[0].endswith(" nodes"), scheduled)

    def test_fresh_choice_passes_mode_to_scheduler(self):
        code, out, calls, _ = self.run_customize(
            ["up", "up", "down", "down", "down"])
        self.assertEqual(0, code, out)
        self.assertIn("全新安装", out)
        scheduled = self.schedule_calls(calls)
        self.assertEqual(1, len(scheduled))
        self.assertTrue(scheduled[0].endswith(" fresh"), scheduled)

    def test_schedule_install_failure_aborts_and_keeps_current_module(self):
        code, out, calls, live_ok = self.run_customize(
            ["timeout"], schedule_result=1, with_apk=False)
        self.assertNotEqual(0, code)
        self.assertIn("安装校验或后台更新任务启动失败，未替换当前模块", out)
        self.assertEqual(1, len(self.schedule_calls(calls)))
        self.assertTrue(live_ok)

    def test_install_manager_failure_warns_but_still_schedules(self):
        code, out, calls, _ = self.run_customize(
            ["timeout", "timeout"], install_manager_result=1)
        self.assertEqual(0, code, out)
        self.assertIn("管理 APK 未自动安装，请手动安装", out)
        self.assertEqual(1, len(self.schedule_calls(calls)))

    def test_cancelled_choice_aborts_before_schedule(self):
        code, out, calls, live_ok = self.run_customize(["up", "timeout"])
        self.assertNotEqual(0, code)
        self.assertIn("未完成选择，安装取消，用户数据未修改", out)
        self.assertEqual([], self.schedule_calls(calls))
        self.assertTrue(live_ok)

    def test_without_apk_skips_install_manager(self):
        code, out, calls, _ = self.run_customize(["timeout"], with_apk=False)
        self.assertEqual(0, code, out)
        self.assertIn("此包未包含 APK", out)
        self.assertNotIn("install-manager", calls)
        scheduled = self.schedule_calls(calls)
        self.assertEqual(1, len(scheduled))
        self.assertTrue(scheduled[0].endswith(" preserve"), scheduled)


if __name__ == "__main__":
    unittest.main()

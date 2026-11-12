# @author 雾晚
# SPDX-License-Identifier: GPL-3.0-or-later
"""Execute the actual POSIX installer menus with controlled volume DOWN events."""
import os
import pathlib
import shutil
import subprocess
import tempfile
import unittest

SHELL = os.environ.get("ROOT_MODULE_TEST_SHELL") or shutil.which("sh")

@unittest.skipUnless(SHELL, "POSIX shell is required to execute installer menus")
class InstallerOptionsTest(unittest.TestCase):
    def choose(self, events, with_apk=True, installed=False):
        with tempfile.TemporaryDirectory() as temp:
            temp = pathlib.Path(temp)
            if with_apk:
                (temp / "manager.apk").write_bytes(b"fixture")
            events_file = temp / "events"
            events_file.write_text("\n".join(events) + "\n")
            script = pathlib.Path(__file__).parent / "package" / "installer-options.sh"
            command = r'''
MODPATH=$1
exec 7<"$2"
. "$3" || exit 90
ui_print() { printf '%s\n' "$*"; }
wanbox_volume_key() {
  local key
  IFS= read -r key <&7 || key=timeout
  printf '%s\n' "$key"
}
INSTALLED=$4
wanbox_manager_installed() { [ "$INSTALLED" = installed ]; }
if wanbox_install_options; then
 printf 'RESULT=%s,%s\n' "$WANBOX_DATA_MODE" "$WANBOX_APK_MODE"
else
 printf 'RESULT=cancelled\n'
fi
'''
            result = subprocess.run([SHELL, "-c", command, "installer-test", temp.as_posix(),
                events_file.as_posix(), script.resolve().as_posix(), "installed" if installed else "absent"],
                text=True, encoding="utf-8", capture_output=True, timeout=10)
            self.assertEqual(0, result.returncode, result.stderr)
            return result.stdout

    def test_default_preserve_and_install_or_update(self):
        self.assertIn("RESULT=preserve,install", self.choose(["timeout", "timeout"]))
        self.assertIn("覆盖更新已有管理 APK", self.choose(["down", "down"], installed=True))
        self.assertIn("安装管理 APK", self.choose(["down", "down"]))

    def test_up_cycles_down_confirms_and_apk_can_be_skipped(self):
        self.assertIn("RESULT=nodes,skip", self.choose(["up", "down", "down", "up", "down"]))
        self.assertIn("RESULT=fresh,install", self.choose(["up", "up", "down", "down", "down"]))
        self.assertIn("RESULT=preserve,install", self.choose(["up", "up", "up", "down", "down"]))

    def test_destructive_choices_need_second_down_and_never_time_out_to_confirmation(self):
        for events in (["up", "timeout"], ["up", "down", "timeout"], ["up", "up", "down", "up"], ["unavailable"]):
            self.assertIn("RESULT=cancelled", self.choose(events))

    def test_without_apk_never_attempts_install_and_still_allows_nodes_choice(self):
        result = self.choose(["up", "down", "down"], with_apk=False)
        self.assertIn("RESULT=nodes,skip", result)
        self.assertIn("手动更新配套管理 APK", result)

@unittest.skipUnless(SHELL, "POSIX shell is required to execute the uninstall script")
class UninstallScriptTest(unittest.TestCase):
    """uninstall.sh must exit 0 and clean the network stack even when the
    module binary is missing or broken (fail-safe uninstall, v3.0.15)."""

    def run_uninstall(self, ctl_mode):
        """Run uninstall.sh with stubbed ip/pgrep on PATH. The pgrep stub
        reports a real sleeper process as the orphan rootbox, so the script's
        own (builtin) kill is verified by the sleeper actually dying — a PATH
        stub for kill would be shadowed by the shell builtin."""
        temp = pathlib.Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, temp, True)
        sleeper = subprocess.Popen(["sleep", "60"])
        self.addCleanup(sleeper.kill)
        moddir = temp / "mod"
        (moddir / "bin").mkdir(parents=True)
        script_src = pathlib.Path(__file__).parent / "package" / "uninstall.sh"
        script = moddir / "uninstall.sh"
        script.write_bytes(script_src.read_bytes())
        if ctl_mode != "missing":
            ctl = moddir / "bin" / "wanboxctl"
            ctl.write_text("#!/bin/sh\necho \"wanboxctl $*\" >> \"$CALLS\"\n"
                           + ("exit 0\n" if ctl_mode == "ok" else "exit 1\n"))
            ctl.chmod(0o755)
        fakebin = temp / "fakebin"
        fakebin.mkdir()
        calls = temp / "calls"
        state = temp / "pgrep_count"
        stubs = {
            "ip": """#!/bin/sh
echo "ip $*" >> "$CALLS"
case "$*" in
  "link show wanbox-tun") exit 0 ;;
  "rule show") printf '0:\\tfrom all lookup main\\n9000:\\tfrom all lookup wanbox\\n' ;;
esac
exit 0
""",
            "pgrep": """#!/bin/sh
n=0; [ -f "$PGREP_STATE" ] && n=$(cat "$PGREP_STATE")
n=$((n+1)); echo "$n" > "$PGREP_STATE"
if [ "$n" -eq 1 ] && [ "$2" = "rootbox" ]; then echo "$ORPHAN_PID"; fi
exit 0
""",
            "sleep": "#!/bin/sh\nexit 0\n",
        }
        for name, body in stubs.items():
            stub = fakebin / name
            stub.write_text(body)
            stub.chmod(0o755)
        env = dict(os.environ)
        env["PATH"] = str(fakebin) + os.pathsep + env.get("PATH", "")
        env["CALLS"] = str(calls)
        env["PGREP_STATE"] = str(state)
        env["ORPHAN_PID"] = str(sleeper.pid)
        result = subprocess.run([SHELL, str(script)], text=True, encoding="utf-8",
                                capture_output=True, timeout=15, env=env)
        recorded = calls.read_text() if calls.exists() else ""
        fallback_used = state.exists()
        return result, recorded, sleeper, fallback_used

    def test_missing_binary_still_cleans_up_and_exits_zero(self):
        result, calls, sleeper, fallback_used = self.run_uninstall("missing")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertTrue(fallback_used)                     # pgrep fallback engaged
        self.assertIsNotNone(sleeper.poll())               # orphan really killed
        self.assertIn("ip link delete wanbox-tun", calls)  # TUN blackhole removed
        self.assertIn("ip rule del pref 9000", calls)      # policy route removed
        self.assertIn("ip rule del pref 9001", calls)      # hot-update guard removed

    def test_broken_binary_falls_back_to_native_kill(self):
        result, calls, sleeper, fallback_used = self.run_uninstall("failing")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertTrue(fallback_used)
        self.assertIsNotNone(sleeper.poll())

    def test_graceful_stop_skips_fallback_but_still_cleans_network(self):
        result, calls, sleeper, fallback_used = self.run_uninstall("ok")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("wanboxctl stop", calls)
        self.assertFalse(fallback_used)                    # no pgrep fallback
        self.assertIsNone(sleeper.poll())                  # orphan left alone
        self.assertIn("ip link delete wanbox-tun", calls)

    def test_user_data_preserved_unless_purge_flag(self):
        text = (pathlib.Path(__file__).parent / "package" / "uninstall.sh").read_text()
        self.assertIn(".purge_user_data", text)
        self.assertIn("/data/adb/wanbox/config/", text)

if __name__ == "__main__":
    unittest.main()

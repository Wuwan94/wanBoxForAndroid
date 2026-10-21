"""Prevent release-only launcher probes leaking into fork-safe verification."""
from pathlib import Path
import subprocess
import unittest

ROOT = Path(__file__).resolve().parents[1]


class ProbeStageTests(unittest.TestCase):
    def test_debug_probe_has_no_signed_release_dependency(self):
        text = (ROOT / 'tools/diagnostics/verify_external_control.sh').read_text()
        self.assertNotIn('apk/oss/release', text)
        self.assertNotIn('android.app.role.HOME', text)
        for assertion in ('PRIVATE_ACTIVITY_REJECTED', 'QUICK_ACTIVITY_REJECTED',
                          'External caller changed service state'):
            self.assertIn(assertion, text)

    def test_release_probe_keeps_all_launcher_checks_and_cleanup(self):
        text = (ROOT / 'tools/diagnostics/verify_launcher_shortcuts.sh').read_text()
        for assertion in ('test -s "$release_apk"', 'for shortcut in enable disable toggle',
                          'LAUNCHER_SHORTCUT_STARTED $shortcut', 'FATAL EXCEPTION',
                          'remove-role-holder', 'trap cleanup EXIT', 'adb install -r probe/probe.apk'):
            self.assertIn(assertion, text)

    def test_scripts_parse(self):
        for name in ('verify_external_control.sh', 'verify_launcher_shortcuts.sh'):
            subprocess.run(['bash', '-n', str(ROOT / 'tools/diagnostics' / name)], check=True)

    def test_screenshot_counts_are_variant_scoped_and_both_probes_run(self):
        text = (ROOT / '.github/workflows/verify-android.yml').read_text()
        debug, release = text.split('      - name: Validate release database migrations')
        self.assertIn('verify_external_control.sh', debug)
        self.assertNotIn('bash tools/diagnostics/verify_launcher_shortcuts.sh', debug)
        self.assertIn('ossDebugAndroidTest -name', debug)
        self.assertIn('ossReleaseAndroidTest -name', release)
        self.assertIn('bash tools/diagnostics/verify_launcher_shortcuts.sh', release)


if __name__ == '__main__':
    unittest.main()

"""Shipping and verification must agree on the no-JNI manager configuration."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]


class ThinBuildContractTest(unittest.TestCase):
    def test_default_manager_is_thin_with_explicit_full_diagnostic_override(self):
        gradle = (ROOT / 'app/build.gradle.kts').read_text()
        self.assertIn('providers.gradleProperty("wanboxThin").map { it.toBoolean() }.getOrElse(true)', gradle)
        self.assertIn('if (wanboxThin) excludes += "**/libgojni.so"', gradle)
        self.assertIn('buildConfigField("boolean", "WITH_CORE", (!wanboxThin).toString())', gradle)

    def test_ci_validates_actual_thin_payload_and_absolute_budget(self):
        workflow = (ROOT / '.github/workflows/verify-android.yml').read_text()
        self.assertIn('python3 tools/verify_apk_abi.py --thin public-apks/*.apk', workflow)
        self.assertIn('python3 tools/check_keep_features.py --apk public-apks/*arm64-v8a*.apk', workflow)
        self.assertIn('--enforce-limit --baseline docs/rebuild/size-baseline.json', workflow)
        self.assertIn('-PwanboxThin=false -Pandroid.testInstrumentationRunnerArguments.class=', workflow)
        self.assertIn('cp -a app/build/outputs/androidTest-results audit-thin/results', workflow)


if __name__ == '__main__':
    unittest.main()

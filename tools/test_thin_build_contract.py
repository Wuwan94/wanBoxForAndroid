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

    def test_release_instrumentation_retains_only_called_thin_http_facades(self):
        gradle = (ROOT / 'app/build.gradle.kts').read_text()
        rules = (ROOT / 'app/proguard-release-tests.pro').read_text()
        self.assertIn('providers.gradleProperty("wanboxReleaseTests").isPresent', gradle)
        self.assertIn('proguardFiles("proguard-release-tests.pro")', gradle)
        for signature in (
            'class io.nekohasekai.sagernet.core.CoreRuntime {',
            'public boolean getAvailable();',
            'public io.nekohasekai.sagernet.core.WanHttpClient httpClient();',
            'public io.nekohasekai.sagernet.core.WanHttpClient modernTLS();',
            'public io.nekohasekai.sagernet.core.WanHttpRequest newRequest();',
            'public io.nekohasekai.sagernet.core.WanHttpRequest setURL(java.lang.String);',
        ):
            self.assertIn(signature, rules)
        self.assertNotIn('class io.nekohasekai.sagernet.core.**', rules)

    def test_release_instrumentation_retains_editor_default_bridge_and_profile_delete(self):
        rules = (ROOT / 'app/proguard-release-tests.pro').read_text()
        for signature in (
            'public java.lang.Object deleteProfile(long, long, kotlin.coroutines.Continuation);',
            'public com.google.gson.JsonObject patchSnapshot(com.google.gson.JsonObject, io.nekohasekai.sagernet.group.editor.GroupDraft, java.lang.String, java.lang.String, java.lang.Long);',
            'public static com.google.gson.JsonObject patchSnapshot$default(io.nekohasekai.sagernet.group.editor.GroupEditor, com.google.gson.JsonObject, io.nekohasekai.sagernet.group.editor.GroupDraft, java.lang.String, java.lang.String, java.lang.Long, int, java.lang.Object);',
        ):
            self.assertIn(signature, rules)


if __name__ == '__main__':
    unittest.main()

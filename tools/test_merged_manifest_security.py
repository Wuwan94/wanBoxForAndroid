from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import check_android_security as security
from check_android_security import check_exported, check_built_variants

A = 'xmlns:android="http://schemas.android.com/apk/res/android"'
class MergedManifestTest(unittest.TestCase):
    def check(self, components):
        with tempfile.TemporaryDirectory() as d:
            p = Path(d)/'AndroidManifest.xml'
            p.write_text(f'<manifest {A}><application>{components}</application></manifest>')
            check_exported(p)
    def test_dependency_export_rejected(self):
        for tag in ('activity', 'activity-alias', 'provider', 'receiver', 'service'):
            with self.subTest(tag=tag), self.assertRaises(AssertionError):
                self.check(f'<{tag} android:name="dependency.Backdoor" android:exported="true"/>')
    def test_implicit_export_rejected(self):
        with self.assertRaises(AssertionError):
            self.check('<receiver android:name="dep.Receiver"><intent-filter><action android:name="unsafe"/></intent-filter></receiver>')
    def test_private_dependency_is_allowed(self):
        self.check('<provider android:name="dependency.Private" android:exported="false"/>')
    def test_known_dependency_requires_exact_permission(self):
        self.check('<service android:name="androidx.work.impl.background.systemjob.SystemJobService" android:exported="true" android:permission="android.permission.BIND_JOB_SERVICE"/>')
        with self.assertRaises(AssertionError):
            self.check('<service android:name="androidx.work.impl.background.systemjob.SystemJobService" android:exported="true"/>')
    def test_missing_final_manifest_fails_not_source_fallback(self):
        with self.assertRaises(AssertionError): check_built_variants(['nonexistentVariant'])

    def test_packaged_variant_discovery_rejects_dependency_export(self):
        with tempfile.TemporaryDirectory() as d:
            root = Path(d)
            p = root / 'app/build/intermediates/packaged_manifests/fixtureRelease/processFixtureReleaseManifestForPackage/AndroidManifest.xml'
            p.parent.mkdir(parents=True)
            p.write_text(f'<manifest {A}><application android:networkSecurityConfig="@xml/network_security_config"><service android:name="dependency.Backdoor" android:exported="true"/></application></manifest>')
            with patch.object(security, 'root', root), self.assertRaisesRegex(AssertionError, 'unauthorized exported'):
                security.check_built_variants(['fixtureRelease'])

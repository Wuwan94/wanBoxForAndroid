import io
import json
from pathlib import Path
import tempfile
import unittest
import zipfile

import verify_preview_candidate as candidate
from pack import manifest, module_properties


class CandidateAssetsTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.directory = Path(self.tmp.name) / "candidate"
        self.directory.mkdir()
        self.properties = candidate.ROOT / "nb4a.properties"
        text = self.properties.read_text(encoding="utf-8")
        props = candidate.parse_properties(text)
        self.current = candidate.resolve(props, "preview")
        self.apk = f"wanBoxForAndroid-{self.current['version_name']}-arm64-v8a-release.apk"
        data = io.BytesIO()
        with zipfile.ZipFile(data, "w") as archive:
            archive.writestr("AndroidManifest.xml", b"test fixture")
        self.apk_bytes = data.getvalue()
        (self.directory / self.apk).write_bytes(self.apk_bytes)
        self.module_props = module_properties(
            (candidate.ROOT / "rootmodule/package/module.prop").read_bytes(), text, channel="preview")
        self.module("wanbox-root-module-arm64-v8a.zip")
        self.module("wanbox-root-module-with-manager-arm64-v8a.zip", self.apk_bytes)
        self.evidence = Path(self.tmp.name) / "evidence.json"
        self.evidence.write_text(json.dumps({"baselines": {
            c: {"apk_version_code": 1, "module_version_code": 1}
            for c in ("formal", "preview")}}), encoding="utf-8")

    def module(self, name, apk=None, metadata=None):
        files = {"module.prop": self.module_props if metadata is None else metadata}
        if apk is not None:
            files["manager/manager.apk"] = apk
        files["package-manifest.json"] = manifest(files, apk is not None)
        with zipfile.ZipFile(self.directory / name, "w") as archive:
            for entry, data in files.items():
                archive.writestr(entry, data)

    def validate(self):
        return candidate.validate(self.directory, self.properties, self.evidence)

    def test_valid_assets_have_digests_and_both_upgrade_lines(self):
        report = self.validate()
        self.assertEqual(3, len(report["assets"]))
        self.assertEqual({"formal", "preview"}, set(report["channels"]))
        self.assertTrue(all(len(a["sha256"]) == 64 for a in report["assets"]))

    def test_extra_or_missing_asset_is_rejected(self):
        extra = self.directory / "extra.apk"
        extra.write_bytes(self.apk_bytes)
        with self.assertRaisesRegex(ValueError, "exactly"):
            self.validate()
        extra.unlink()
        (self.directory / self.apk).unlink()
        with self.assertRaisesRegex(ValueError, "exactly"):
            self.validate()

    def test_mismatched_bundled_manager_is_rejected(self):
        self.module("wanbox-root-module-with-manager-arm64-v8a.zip", b"different APK")
        with self.assertRaisesRegex(ValueError, "exact verified APK"):
            self.validate()

    def test_wrong_module_version_is_rejected(self):
        self.module("wanbox-root-module-arm64-v8a.zip", metadata=b"versionCode=1\n")
        with self.assertRaisesRegex(ValueError, "identity mismatch"):
            self.validate()

    def test_upgrade_failure_is_rejected(self):
        self.evidence.write_text(json.dumps({"baselines": {
            c: {"apk_version_code": 2147483647, "module_version_code": 1}
            for c in ("formal", "preview")}}), encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "formal"):
            self.validate()

    def test_truncated_archive_is_rejected(self):
        (self.directory / self.apk).write_bytes(self.apk_bytes[:8])
        with self.assertRaises(zipfile.BadZipFile):
            self.validate()

    def test_package_manifest_mismatch_is_rejected(self):
        path = self.directory / "wanbox-root-module-arm64-v8a.zip"
        with zipfile.ZipFile(path) as original:
            files = {n: original.read(n) for n in original.namelist()}
        metadata = json.loads(files["package-manifest.json"])
        metadata["files"]["module.prop"]["sha256"] = "0" * 64
        files["package-manifest.json"] = json.dumps(metadata).encode()
        with zipfile.ZipFile(path, "w") as output:
            for name, data in files.items():
                output.writestr(name, data)
        with self.assertRaisesRegex(ValueError, "checksum mismatch"):
            self.validate()

"""Container contract tests; Android SDK subprocess output is explicitly mocked."""
import io
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch
import zipfile

import verify_preview_candidate as candidate
from pack import manifest, module_properties


class CandidateAssetsTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.directory = Path(self.tmp.name) / "candidate"
        self.directory.mkdir()
        self.properties = Path(self.tmp.name) / "nb4a.properties"
        text = (candidate.ROOT / "nb4a.properties").read_text(encoding="utf-8")
        self.properties.write_text(text, encoding="utf-8")
        props = candidate.parse_properties(text)
        self.current = candidate.resolve(props, "preview")
        self.apk = f"wanBoxForAndroid-{self.current['version_name']}-arm64-v8a-release.apk"
        data = io.BytesIO()
        with zipfile.ZipFile(data, "w") as archive:
            archive.writestr("AndroidManifest.xml", b"SDK-mocked container fixture")
        self.apk_bytes = data.getvalue()
        (self.directory / self.apk).write_bytes(self.apk_bytes)
        self.module_props = module_properties(
            (candidate.ROOT / "rootmodule/package/module.prop").read_bytes(), text, channel="preview")
        self.module("wanbox-root-module-arm64-v8a.zip")
        self.module("wanbox-root-module-with-manager-arm64-v8a.zip", self.apk_bytes)
        self.evidence = Path(self.tmp.name) / "evidence.json"
        self.evidence_data = json.loads(
            (candidate.ROOT / "docs/preview3-version-evidence.json").read_text(encoding="utf-8"))
        self.evidence.write_text(json.dumps(self.evidence_data), encoding="utf-8")
        self.badging = (
            f"package: name='{props['PACKAGE_NAME']}' "
            f"versionCode='{self.current['apk_version_code']}' "
            f"versionName='{self.current['version_name']}' platformBuildVersionCode='35'\n"
        )
        self.signature = (
            "Verifies\nNumber of signers: 1\nSigner #1 certificate SHA-256 digest: "
            + self.evidence_data["certificate_sha256"] + "\n"
        )
        sdk_patch = patch.object(candidate.subprocess, "run", side_effect=self.sdk_result)
        self.sdk = sdk_patch.start()
        self.addCleanup(sdk_patch.stop)

    def sdk_result(self, command, **kwargs):
        if Path(command[0]).name == "sdk-aapt":
            self.assertEqual(["dump", "badging"], command[1:3])
            output = self.badging
        else:
            self.assertEqual("sdk-apksigner", Path(command[0]).name)
            self.assertEqual(["verify", "--verbose", "--print-certs"], command[1:4])
            output = self.signature
        self.assertTrue(kwargs["check"])
        return subprocess.CompletedProcess(command, 0, stdout=output, stderr="")

    def module(self, name, apk=None, metadata=None):
        files = {entry: b"module container fixture"
                 for entry in candidate.ALLOWED - {"package-manifest.json"}}
        files["module.prop"] = self.module_props if metadata is None else metadata
        if apk is not None:
            files["manager.apk"] = apk
        files["package-manifest.json"] = manifest(files, apk is not None)
        with zipfile.ZipFile(self.directory / name, "w") as archive:
            for entry, data in files.items():
                archive.writestr(entry, data)

    def rewrite_module(self, name, change):
        path = self.directory / name
        with zipfile.ZipFile(path) as archive:
            files = {entry: archive.read(entry) for entry in archive.namelist()
                     if entry != "package-manifest.json"}
        change(files)
        files["package-manifest.json"] = manifest(
            files, name == "wanbox-root-module-with-manager-arm64-v8a.zip")
        with zipfile.ZipFile(path, "w") as archive:
            for entry, data in files.items():
                archive.writestr(entry, data)

    def validate(self):
        return candidate.validate(self.directory, self.properties, self.evidence,
                                  aapt="sdk-aapt", apksigner="sdk-apksigner")

    def test_container_contract_and_report_use_mocked_sdk_observations(self):
        report = self.validate()
        self.assertEqual(3, len(report["assets"]))
        self.assertEqual({"formal", "preview"}, set(report["channels"]))
        self.assertTrue(all(len(asset["sha256"]) == 64 for asset in report["assets"]))
        self.assertEqual(self.current["apk_version_code"], report["apk"]["apk_version_code"])
        self.assertEqual(self.current["version_name"], report["apk"]["version_name"])
        self.assertEqual(self.evidence_data["package_name"], report["apk"]["package_name"])
        self.assertEqual(self.evidence_data["certificate_sha256"], report["apk"]["certificate_sha256"])
        self.assertEqual(2, self.sdk.call_count)

    def test_modern_v2_signer_output_format_is_accepted(self):
        self.signature = (
            "Verifies\nNumber of signers: 1\nV2 Signer: certificate SHA-256 digest: "
            + self.evidence_data["certificate_sha256"] + "\n"
        )
        report = self.validate()
        self.assertEqual(self.evidence_data["certificate_sha256"], report["apk"]["certificate_sha256"])

    def test_old_apk_code_or_name_is_rejected(self):
        original = self.badging
        for old, new in (
            (f"versionCode='{self.current['apk_version_code']}'", "versionCode='10077'"),
            (f"versionName='{self.current['version_name']}'", "versionName='3.0.11-preview.2'"),
        ):
            with self.subTest(field=old):
                self.badging = original.replace(old, new)
                with self.assertRaisesRegex(ValueError, "APK .* mismatch"):
                    self.validate()

    def test_wrong_package_is_rejected(self):
        self.badging = self.badging.replace(self.evidence_data["package_name"], "wrong.package")
        with self.assertRaisesRegex(ValueError, "APK package_name mismatch"):
            self.validate()

    def test_wrong_certificate_is_rejected(self):
        self.signature = self.signature.replace(
            self.evidence_data["certificate_sha256"], "0" * 64)
        with self.assertRaisesRegex(ValueError, "APK certificate_sha256 mismatch"):
            self.validate()

    def test_wrong_abi_is_rejected_before_sdk_probe(self):
        with zipfile.ZipFile(self.directory / self.apk, "a") as archive:
            archive.writestr("lib/x86_64/libfixture.so", b"wrong ABI fixture")
        with self.assertRaisesRegex(ValueError, "thin APK ABI"):
            self.validate()
        self.sdk.assert_not_called()

    def test_sdk_verification_failure_is_rejected(self):
        self.sdk.side_effect = subprocess.CalledProcessError(1, ["sdk-apksigner"])
        with self.assertRaisesRegex(ValueError, "SDK probe failed"):
            self.validate()

    def test_missing_sdk_package_or_extra_signer_is_rejected(self):
        original_badging, original_signature = self.badging, self.signature
        self.badging = "not package metadata\n"
        with self.assertRaisesRegex(ValueError, "invalid package metadata"):
            self.validate()
        self.badging = original_badging
        self.signature = original_signature.replace("Number of signers: 1", "Number of signers: 2")
        self.signature += "Signer #2 certificate SHA-256 digest: " + "0" * 64 + "\n"
        with self.assertRaisesRegex(ValueError, "exactly one"):
            self.validate()

    def test_missing_required_module_payload_is_rejected(self):
        name = "wanbox-root-module-arm64-v8a.zip"
        for entry in ("bin/rootbox", "bin/wanboxctl", "service.sh"):
            with self.subTest(entry=entry):
                self.module(name)
                self.rewrite_module(name, lambda files: files.pop(entry))
                with self.assertRaisesRegex(ValueError, "Unexpected module entries"):
                    self.validate()

    def test_manager_in_module_only_variant_is_rejected(self):
        self.rewrite_module("wanbox-root-module-arm64-v8a.zip",
                            lambda files: files.update({"manager.apk": self.apk_bytes}))
        with self.assertRaisesRegex(ValueError, "Unexpected module entries"):
            self.validate()

    def test_manager_must_use_exact_root_path(self):
        def relocate_manager(files):
            files["manager/manager.apk"] = files.pop("manager.apk")
        self.rewrite_module("wanbox-root-module-with-manager-arm64-v8a.zip", relocate_manager)
        with self.assertRaisesRegex(ValueError, "Unexpected module entries"):
            self.validate()

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
        for channel in ("formal", "preview"):
            self.evidence_data["baselines"][channel]["apk_version_code"] = 2147483647
        self.evidence.write_text(json.dumps(self.evidence_data), encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "formal"):
            self.validate()

    def test_truncated_archive_is_rejected(self):
        (self.directory / self.apk).write_bytes(self.apk_bytes[:8])
        with self.assertRaises(zipfile.BadZipFile):
            self.validate()

    def test_package_manifest_mismatch_is_rejected(self):
        path = self.directory / "wanbox-root-module-arm64-v8a.zip"
        with zipfile.ZipFile(path) as original:
            files = {name: original.read(name) for name in original.namelist()}
        metadata = json.loads(files["package-manifest.json"])
        metadata["files"]["module.prop"]["sha256"] = "0" * 64
        files["package-manifest.json"] = json.dumps(metadata).encode()
        with zipfile.ZipFile(path, "w") as output:
            for name, data in files.items():
                output.writestr(name, data)
        with self.assertRaisesRegex(ValueError, "checksum mismatch"):
            self.validate()

    def test_cli_requires_explicit_sdk_tools(self):
        argv = ["verify_preview_candidate.py", "--directory", "unused", "--report", "unused"]
        with patch.object(candidate.sys, "argv", argv), patch("sys.stderr", new_callable=io.StringIO):
            with self.assertRaises(SystemExit) as result:
                candidate.main()
        self.assertEqual(2, result.exception.code)

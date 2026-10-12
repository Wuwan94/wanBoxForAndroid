import hashlib
import io
import json
import pathlib
import struct
import subprocess
import sys
import tempfile
import unittest
import zipfile
from types import SimpleNamespace
from unittest.mock import patch

import prepare_release_packages as release


class ReleasePackagesTest(unittest.TestCase):
    def metadata(self):
        return dict(line.split("=", 1) for line in
                    (release.ROOT / "nb4a.properties").read_text(encoding="utf-8").splitlines()
                    if line and not line.startswith("#"))

    def outputs(self):
        metadata = self.metadata()
        badging = "package: name='%s' versionCode='%s' versionName='%s'" % (
            metadata["PACKAGE_NAME"], int(metadata["VERSION_CODE"]) * 5, metadata["VERSION_NAME"])
        badging += "\nsdkVersion:'31'"
        cert = "Signer #1 certificate SHA-256 digest: " + release.RELEASE_CERTIFICATE.lower()
        return badging, cert

    def test_only_original_signature_and_matching_version_are_accepted(self):
        badging, cert = self.outputs()
        release.validate_identity(badging, cert, self.metadata())
        for invalid in (cert.replace(release.RELEASE_CERTIFICATE.lower(), "0" * 64),
                        "", cert + "\n" + cert.replace("#1", "#2")):
            with self.assertRaises(ValueError):
                release.validate_identity(badging, invalid, self.metadata())
        for invalid in (badging.replace(self.metadata()["PACKAGE_NAME"], "test.debug"),
                        badging.replace("versionCode='", "versionCode='99"),
                        badging.replace(self.metadata()["VERSION_NAME"], "0.0.0")):
            with self.assertRaises(ValueError):
                release.validate_identity(invalid, cert, self.metadata())

    def test_upgrade_requires_same_identity_newer_code_and_existing_device_support(self):
        candidate, cert = self.outputs()
        previous = candidate.replace("versionCode='1810'", "versionCode='1805'").replace("3.0.9", "3.0.8")
        release.validate_upgrade(previous, cert, candidate, cert)
        invalid_cases = [
            (previous, cert, previous, cert),
            (candidate, cert, previous, cert),
            (previous, cert, candidate.replace(self.metadata()["PACKAGE_NAME"], "test.other"), cert),
            (previous, cert, candidate.replace("sdkVersion:'31'", "sdkVersion:'32'"), cert),
            (previous, cert, candidate.replace("sdkVersion:'31'", ""), cert),
            (previous, cert, candidate.replace("1810", "invalid"), cert),
            (previous, cert.replace(release.RELEASE_CERTIFICATE.lower(), "0" * 64), candidate, cert),
            (previous, cert, candidate, cert.replace(release.RELEASE_CERTIFICATE.lower(), "0" * 64)),
        ]
        for args in invalid_cases:
            with self.subTest(args=args), self.assertRaises(ValueError):
                release.validate_upgrade(*args)

    def test_previous_apk_download_must_match_pinned_checksum(self):
        with tempfile.TemporaryDirectory() as directory:
            target = pathlib.Path(directory) / "previous.apk"
            with patch.object(release.urllib.request, "urlopen", return_value=io.BytesIO(b"changed asset")):
                with self.assertRaisesRegex(ValueError, "checksum"):
                    release.download_previous_apk(target)
            payload = b"verified fixture"
            with patch.object(release.urllib.request, "urlopen", return_value=io.BytesIO(payload)), \
                    patch.object(release, "PREVIOUS_APK_SHA256", hashlib.sha256(payload).hexdigest()):
                self.assertEqual(target, release.download_previous_apk(target))
                self.assertEqual(payload, target.read_bytes())

    def test_three_packages_contain_identical_apk_and_valid_checksums(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            elf = bytearray(64)
            elf[:6] = b"\x7fELF\x02\x01"
            struct.pack_into("<HH", elf, 16, 3, 183)
            (root / "core").write_bytes(elf + b"core_config_invalid")
            (root / "cli").write_bytes(elf)
            module = root / "module.zip"
            subprocess.run([sys.executable, str(release.ROOT / "rootmodule/pack.py"),
                            "--abi", "arm64-v8a", "--core", str(root / "core"), "--cli", str(root / "cli"),
                            "--output", str(module)], check=True)
            apk = root / "manager.apk"
            with zipfile.ZipFile(apk, "w", zipfile.ZIP_DEFLATED) as archive:
                archive.writestr("AndroidManifest.xml", b"fixture")
                archive.writestr("lib/arm64-v8a/libgojni.so", elf)
            badging, cert = self.outputs()
            previous = badging.replace("versionCode='1810'", "versionCode='1805'").replace("3.0.9", "3.0.8")
            with patch.object(release, "download_previous_apk", return_value=apk) as baseline, \
                    patch.object(release.subprocess, "run", side_effect=[
                        SimpleNamespace(stdout=badging), SimpleNamespace(stdout=cert),
                        SimpleNamespace(stdout=previous), SimpleNamespace(stdout=cert)]):
                artifacts = release.prepare(apk, module, root / "output", "aapt", "apksigner")
                baseline.assert_called_once()
            self.assertEqual(3, len(artifacts))
            standalone, pure, combined = artifacts
            with zipfile.ZipFile(pure) as archive:
                self.assertNotIn("manager.apk", archive.namelist())
            with zipfile.ZipFile(combined) as archive:
                self.assertIsNone(archive.testzip())
                self.assertEqual(standalone.read_bytes(), archive.read("manager.apk"))
                manifest = json.loads(archive.read("package-manifest.json"))
                self.assertTrue(manifest["withManager"])
                for name, specification in manifest["files"].items():
                    data = archive.read(name)
                    self.assertEqual({"size": len(data), "sha256": hashlib.sha256(data).hexdigest()}, specification)
            checksums = (root / "output/SHA256SUMS").read_text().splitlines()
            self.assertEqual(3, len(checksums))
            for line in checksums:
                digest, name = line.split("  ")
                self.assertEqual(digest, hashlib.sha256((root / "output" / name).read_bytes()).hexdigest())


if __name__ == "__main__":
    unittest.main()

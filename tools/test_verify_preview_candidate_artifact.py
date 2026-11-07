import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest


spec = importlib.util.spec_from_file_location(
    "verify_artifact", Path(__file__).with_name("verify_preview_candidate_artifact.py")
)
verify_artifact = importlib.util.module_from_spec(spec)
spec.loader.exec_module(verify_artifact)


class CandidateArtifactTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.folder = Path(self.temp.name)
        self.source_sha = "a" * 40
        self.run_id = 123456
        self.tag = "v3.0.11-preview.3"
        self.assets = {
            "wanBoxForAndroid-3.0.11-preview.3-arm64-v8a-release.apk": b"apk-byte-payload",
            "wanbox-root-module-arm64-v8a.zip": b"module-byte-payload",
            "wanbox-root-module-with-manager-arm64-v8a.zip": b"combined-byte-payload",
        }
        for name, payload in self.assets.items():
            (self.folder / name).write_bytes(payload)
        asset_report = [
            {"name": name, "sha256": hashlib.sha256(payload).hexdigest(), "size": len(payload)}
            for name, payload in self.assets.items()
        ]
        report = {
            "source_sha": self.source_sha,
            "run_id": self.run_id,
            "candidate_tag": self.tag,
            "assets": asset_report,
            "apk": {
                "version_name": "3.0.11-preview.3",
                "apk_version_code": 10083,
                "signer_count": 1,
            },
            "channels": {"preview": {"apk_version_code": 10083}},
        }
        report_bytes = json.dumps(report, indent=2) + "\n"
        (self.folder / "candidate-validation.json").write_text(report_bytes, encoding="utf-8")
        (self.folder / "SHA256SUMS").write_text(
            "".join(f"{asset['sha256']}  {asset['name']}\n" for asset in asset_report),
            encoding="ascii",
        )

    def tearDown(self):
        self.temp.cleanup()

    def test_accepts_exact_candidate_and_manifest(self):
        report = verify_artifact.verify(
            self.folder, self.source_sha, self.run_id, self.tag
        )
        self.assertEqual(report["candidate_tag"], self.tag)
        self.assertEqual(len(report["assets"]), 3)

    def test_rejects_wrong_source_sha_run_id_or_tag(self):
        with self.assertRaisesRegex(ValueError, "source SHA"):
            verify_artifact.verify(self.folder, "b" * 40, self.run_id, self.tag)
        with self.assertRaisesRegex(ValueError, "run ID"):
            verify_artifact.verify(self.folder, self.source_sha, self.run_id + 1, self.tag)
        with self.assertRaisesRegex(ValueError, "release tag"):
            verify_artifact.verify(self.folder, self.source_sha, self.run_id, "v3.0.11-preview.4")

    def test_rejects_modified_asset_and_manifest(self):
        apk = self.folder / "wanBoxForAndroid-3.0.11-preview.3-arm64-v8a-release.apk"
        apk.write_bytes(b"modified")
        with self.assertRaisesRegex(ValueError, "(size|hash) mismatch"):
            verify_artifact.verify(self.folder, self.source_sha, self.run_id, self.tag)
        apk.write_bytes(self.assets[apk.name])
        (self.folder / "SHA256SUMS").write_text("0" * 64 + "  wrong.apk\n", encoding="ascii")
        with self.assertRaisesRegex(ValueError, "does not list exactly"):
            verify_artifact.verify(self.folder, self.source_sha, self.run_id, self.tag)

    def test_with_manager_uses_complete_filename_not_prefix_or_suffix(self):
        original = "wanbox-root-module-with-manager-arm64-v8a.zip"
        report_path = self.folder / "candidate-validation.json"
        sums_path = self.folder / "SHA256SUMS"
        original_report = report_path.read_text()
        original_sums = sums_path.read_text()
        for lookalike in (original + ".bak", "prefix-" + original, original.replace("arm64-v8a", "x86_64")):
            with self.subTest(lookalike=lookalike):
                (self.folder / original).rename(self.folder / lookalike)
                report_path.write_text(original_report.replace(original, lookalike))
                sums_path.write_text(original_sums.replace(original, lookalike))
                with self.assertRaisesRegex(ValueError, "allowlist"):
                    verify_artifact.verify(self.folder, self.source_sha, self.run_id, self.tag)
                (self.folder / lookalike).rename(self.folder / original)
                report_path.write_text(original_report)
                sums_path.write_text(original_sums)

    def test_missing_with_manager_is_not_substituted_by_plain_module(self):
        (self.folder / "wanbox-root-module-with-manager-arm64-v8a.zip").unlink()
        with self.assertRaisesRegex(ValueError, "missing or unexpected"):
            verify_artifact.verify(self.folder, self.source_sha, self.run_id, self.tag)

    def test_both_module_variants_are_independent_of_report_order(self):
        report_path = self.folder / "candidate-validation.json"
        report = json.loads(report_path.read_text())
        report["assets"].reverse()
        report_path.write_text(json.dumps(report))
        (self.folder / "SHA256SUMS").write_text("".join(
            f"{asset['sha256']}  {asset['name']}\n" for asset in report["assets"]
        ))
        actual = verify_artifact.verify(self.folder, self.source_sha, self.run_id, self.tag)
        self.assertEqual({asset["name"] for asset in report["assets"]},
                         {asset["name"] for asset in actual["assets"]})

    def test_rejects_extra_file(self):
        (self.folder / "unexpected.zip").write_bytes(b"extra")
        with self.assertRaisesRegex(ValueError, "unexpected files"):
            verify_artifact.verify(self.folder, self.source_sha, self.run_id, self.tag)


if __name__ == "__main__":
    unittest.main()

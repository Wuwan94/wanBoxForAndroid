import copy
import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import preview_replacement as p

TAG = "v3.0.16-preview.3"
SHA = "a" * 40
OLD = "b" * 40
NAMES = ["wanBoxForAndroid-3.0.16-preview.3-arm64-v8a-release.apk",
         "wanbox-root-module-arm64-v8a.zip", "wanbox-root-module-with-manager-arm64-v8a.zip"]


def manifest(values):
    return "".join(f"{hashlib.sha256(data).hexdigest()}  {name}\n" for name, data in values.items()).encode()


class FakeGitHub:
    repository = "Wuwan94/wanBoxForAndroid"

    def __init__(self):
        self.bytes = {name: f"old:{name}".encode() for name in NAMES}
        self.bytes["SHA256SUMS"] = manifest(self.bytes)
        self.metadata = {"id": 42, "tag_name": TAG, "name": "Preview", "body": "Original notes",
                         "draft": False, "prerelease": True, "target_commitish": OLD}
        self.tag_state = {"ref": {"type": "commit", "sha": OLD}, "commit": OLD}
        self.writes = []
        self.upload_failures = 0
        self.fail_publish = False
        self.generation = 0

    def release(self, tag):
        return copy.deepcopy(self.metadata) | {"assets": [
            {"id": i, "name": n, "size": len(b), "digest": None, "updated_at": self.generation}
            for i, (n, b) in enumerate(self.bytes.items())]}

    def release_by_id(self, release_id):
        assert release_id == 42
        return self.release(TAG)

    def tag(self, tag):
        return copy.deepcopy(self.tag_state)

    def candidate_run(self, run_id, source_sha):
        assert run_id == 123 and source_sha == SHA

    def download(self, asset, destination):
        Path(destination).write_bytes(self.bytes[asset["name"]])

    def edit(self, release_id, payload):
        self.writes.append(("edit", payload))
        if self.fail_publish and payload.get("draft") is False:
            self.fail_publish = False
            raise ValueError("publish network failure")
        self.metadata.update(payload)

    def upload(self, tag, paths):
        self.writes.append(("upload", [path.name for path in paths]))
        # Model a failure after some assets have already changed.
        for i, path in enumerate(paths):
            self.bytes[path.name] = path.read_bytes()
            self.generation += 1
            if self.upload_failures and i == 0:
                self.upload_failures -= 1
                raise ValueError("partial upload failure")


class ReplacementTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        self.candidate = self.root / "candidate"
        self.candidate.mkdir()
        data = {name: f"new:{name}".encode() for name in NAMES}
        data["SHA256SUMS"] = manifest(data)
        for name, value in data.items():
            (self.candidate / name).write_bytes(value)
        self.report = {"assets": [{"name": name, "size": len(data[name]),
                        "sha256": hashlib.sha256(data[name]).hexdigest()} for name in NAMES],
                       "apk": {"verified": True}}
        self.gh = FakeGitHub()
        self.old_bytes = copy.deepcopy(self.gh.bytes)
        self.old_metadata = copy.deepcopy(self.gh.metadata)
        self.backup = self.root / "rollback"
        self.check = patch.object(p, "check_candidate", return_value=self.report).start()
        self.probe = patch("verify_preview_candidate.probe_apk", return_value=self.report["apk"]).start()
        self.addCleanup(patch.stopall)
        self.addCleanup(self.tmp.cleanup)

    def prepare(self):
        return p.prepare(self.gh, self.candidate, self.backup, SHA, 123, TAG, OLD,
                         hashlib.sha256(self.old_bytes["SHA256SUMS"]).hexdigest(), "aapt", "apksigner")

    def execute(self):
        p.execute(self.gh, self.candidate, self.backup,
                  p.sha256(self.backup / "replacement-plan.json"), "aapt", "apksigner")

    def test_prepare_is_read_only_and_backs_up_all_four_exact_bytes(self):
        self.prepare()
        self.assertEqual([], self.gh.writes)
        for name, data in self.old_bytes.items():
            self.assertEqual(data, (self.backup / "assets" / name).read_bytes())

    def test_success_preserves_tag_version_and_release_identity(self):
        self.prepare()
        self.execute()
        self.assertEqual(False, self.gh.metadata["draft"])
        self.assertEqual(42, self.gh.metadata["id"])
        self.assertEqual(OLD, self.gh.tag(TAG)["commit"])
        self.assertTrue(self.gh.metadata["body"].startswith("Original notes"))
        self.assertIn("不会收到自动更新", self.gh.metadata["body"])
        for name in self.old_bytes:
            self.assertEqual((self.candidate / name).read_bytes(), self.gh.bytes[name])
        self.assertEqual("published", json.loads((self.backup / "replacement-result.json").read_text())["state"])

    def test_execute_requires_exact_plan_hash(self):
        self.prepare()
        with self.assertRaisesRegex(ValueError, "plan digest"):
            p.execute(self.gh, self.candidate, self.backup, "0" * 64, "a", "b")
        self.assertFalse(self.gh.writes)

    def test_changed_release_aborts_before_write(self):
        self.prepare()
        self.gh.metadata["body"] = "owner edit"
        with self.assertRaisesRegex(ValueError, "changed since"):
            self.execute()
        self.assertFalse(self.gh.writes)

    def test_changed_tag_aborts_before_write(self):
        self.prepare()
        self.gh.tag_state["commit"] = "c" * 40
        with self.assertRaisesRegex(ValueError, "changed since"):
            self.execute()
        self.assertFalse(self.gh.writes)

    def test_changed_candidate_aborts_before_write(self):
        self.prepare()
        (self.candidate / NAMES[0]).write_bytes(b"tampered")
        with self.assertRaisesRegex(ValueError, "payload"):
            self.execute()
        self.assertFalse(self.gh.writes)

    def test_changed_backup_aborts_before_write(self):
        self.prepare()
        (self.backup / "assets" / NAMES[0]).write_bytes(b"tampered")
        with self.assertRaisesRegex(ValueError, "rollback"):
            self.execute()
        self.assertFalse(self.gh.writes)

    def test_partial_upload_failure_restores_all_old_bytes_and_metadata(self):
        self.prepare()
        self.gh.upload_failures = 1
        with self.assertRaisesRegex(RuntimeError, "old bytes and metadata restored"):
            self.execute()
        self.assertEqual(self.old_bytes, self.gh.bytes)
        self.assertEqual(self.old_metadata, self.gh.metadata)

    def test_rollback_failure_retains_hidden_release_and_explicit_error(self):
        self.prepare()
        self.gh.upload_failures = 2
        with self.assertRaisesRegex(RuntimeError, "AND rollback failed"):
            self.execute()
        self.assertTrue(self.gh.metadata["draft"])
        self.assertEqual("rollback_failed", json.loads((self.backup / "replacement-result.json").read_text())["state"])

    def test_publish_failure_rolls_back_before_restoring_visibility(self):
        self.prepare()
        self.gh.fail_publish = True
        with self.assertRaisesRegex(RuntimeError, "restored"):
            self.execute()
        self.assertEqual(self.old_bytes, self.gh.bytes)
        self.assertEqual(self.old_metadata, self.gh.metadata)

    def test_missing_remote_asset_is_not_success(self):
        self.prepare()
        del self.gh.bytes[NAMES[0]]
        with self.assertRaises(ValueError):
            self.execute()
        self.assertFalse(self.gh.writes)

    def test_prepare_rejects_unexpected_old_asset(self):
        self.gh.bytes["other.zip"] = b"unknown"
        with self.assertRaisesRegex(ValueError, "unexpected"):
            self.prepare()
        self.assertFalse(self.gh.writes)

    def test_prepare_rejects_bad_original_manifest(self):
        self.gh.bytes[NAMES[0]] = b"changed"
        with self.assertRaisesRegex(ValueError, "fail their manifest"):
            self.prepare()
        self.assertFalse(self.gh.writes)

    def test_prepare_rejects_different_identity(self):
        self.probe.return_value = {"verified": False}
        with self.assertRaisesRegex(ValueError, "identity"):
            self.prepare()
        self.assertFalse(self.gh.writes)


class CandidateRunTest(unittest.TestCase):
    def test_successful_exact_main_preview_run_only(self):
        gh = p.GitHub("Wuwan94/wanBoxForAndroid")
        valid = {"conclusion": "success", "head_sha": SHA, "head_branch": "main",
                 "path": ".github/workflows/preview.yml"}
        for key, value in (("conclusion", "failure"), ("head_sha", OLD),
                           ("head_branch", "feature"), ("path", ".github/workflows/other.yml")):
            with self.subTest(key=key), patch.object(gh, "api", return_value=valid | {key: value}):
                with self.assertRaises(ValueError):
                    gh.candidate_run(123, SHA)
        with patch.object(gh, "api", side_effect=[valid, {"object": {"sha": SHA}}]):
            self.assertEqual(valid, gh.candidate_run(123, SHA))
        with patch.object(gh, "api", side_effect=[valid, {"object": {"sha": OLD}}]):
            with self.assertRaisesRegex(ValueError, "main changed"):
                gh.candidate_run(123, SHA)


if __name__ == "__main__":
    unittest.main()

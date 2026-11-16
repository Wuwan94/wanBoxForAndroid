#!/usr/bin/env python3
"""Prepare (default/read-only) or execute a reviewed, backed-up preview asset replacement.

Never moves a Git tag. Execute temporarily hides the Release as a draft, replaces all
four assets, verifies downloaded bytes, then republishes. On failure it restores the
old bytes and metadata; failed rollback leaves the Release hidden for manual recovery.
GitHub has no atomic multi-asset transaction; cached/direct URLs are not revoked.
"""
import argparse
import copy
import hashlib
import json
from pathlib import Path
import re
import subprocess
import tempfile

from verify_preview_candidate import validate
from verify_preview_candidate_artifact import verify, sha256, PREVIEW_TAG
from size_budget import LIMIT


def dump(path, value):
    Path(path).write_text(json.dumps(value, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def fingerprint(release):
    """Only mutation-relevant metadata; API download counters are deliberately excluded."""
    return {key: copy.deepcopy(release.get(key)) for key in
            ("id", "tag_name", "name", "body", "draft", "prerelease", "target_commitish")} | {
        "assets": sorted(({
            key: asset.get(key) for key in ("id", "name", "size", "digest", "updated_at")
        } for asset in release["assets"]), key=lambda a: a["name"])
    }


class GitHub:
    def __init__(self, repository):
        if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repository):
            raise ValueError("invalid repository")
        self.repository = repository
        self.prefix = f"repos/{repository}"

    def api(self, path, payload=None):
        command = ["gh", "api", f"{self.prefix}/{path}"]
        if payload is not None:
            command += ["--method", "PATCH", "--input", "-"]
        return json.loads(subprocess.run(command, input=json.dumps(payload) if payload is not None else None,
                                        capture_output=True, text=True, check=True).stdout)

    def release(self, tag):
        return self.api(f"releases/tags/{tag}")

    def release_by_id(self, release_id):
        # Tag lookup is specified for published releases; use the immutable ID while hidden.
        return self.api(f"releases/{int(release_id)}")

    def tag(self, tag):
        obj = self.api(f"git/ref/tags/{tag}")["object"]
        # Preserve both ref object identity and dereferenced commit for annotated tags.
        ref = copy.deepcopy(obj)
        for _ in range(5):
            if obj["type"] == "commit":
                return {"ref": ref, "commit": obj["sha"]}
            if obj["type"] != "tag":
                break
            obj = self.api(f"git/tags/{obj['sha']}")["object"]
        raise ValueError("tag does not resolve to a commit")

    def download(self, asset, destination):
        with Path(destination).open("wb") as output:
            subprocess.run(["gh", "api", "-H", "Accept: application/octet-stream",
                            f"{self.prefix}/releases/assets/{asset['id']}"], stdout=output, check=True)

    def edit(self, release_id, payload):
        return self.api(f"releases/{release_id}", payload)

    def upload(self, tag, paths):
        subprocess.run(["gh", "release", "upload", tag, "--repo", self.repository,
                        "--clobber", *map(str, paths)], check=True)

    def candidate_run(self, run_id, source_sha):
        run = self.api(f"actions/runs/{run_id}")
        if (run.get("conclusion") != "success" or run.get("head_sha") != source_sha
                or run.get("head_branch") != "main"
                or run.get("path", "").split("@")[0] != ".github/workflows/preview.yml"):
            raise ValueError("candidate must come from a successful reviewed main Preview Build")
        main = self.api("git/ref/heads/main")["object"]["sha"]
        if main != source_sha:
            raise ValueError("main changed; rebuild/review the new exact SHA")
        return run


def assert_release(release, tag, names):
    if (release.get("tag_name") != tag or release.get("draft") is not False
            or release.get("prerelease") is not True):
        raise ValueError("only the existing published preview release may be replaced")
    assets = release["assets"]
    if len(assets) != len(names) or {a["name"] for a in assets} != set(names):
        raise ValueError("existing release has missing, duplicate or unexpected assets")


def download_set(gh, release, folder, expected=None):
    folder = Path(folder)
    folder.mkdir(parents=True, exist_ok=True)
    hashes = {}
    if expected is not None and (len(release["assets"]) != len(expected) or
            {a["name"] for a in release["assets"]} != set(expected)):
        raise ValueError("remote asset set changed")
    for asset in release["assets"]:
        name = asset["name"]
        if Path(name).name != name or not re.fullmatch(r"[A-Za-z0-9._+-]+", name):
            raise ValueError("unsafe asset name")
        path = folder / name
        gh.download(asset, path)
        if path.stat().st_size != asset["size"]:
            raise ValueError(f"download size mismatch: {name}")
        hashes[name] = sha256(path)
        if expected is not None and hashes[name] != expected.get(name):
            raise ValueError(f"remote asset hash mismatch: {name}")
    return hashes


def check_manifest(folder, names):
    lines = (Path(folder) / "SHA256SUMS").read_text(encoding="ascii").splitlines()
    checks = {}
    for line in lines:
        match = re.fullmatch(r"([0-9a-f]{64})  ([A-Za-z0-9._+-]+)", line)
        if not match or match[2] in checks:
            raise ValueError("malformed original manifest")
        checks[match[2]] = match[1]
    if set(checks) != set(names) - {"SHA256SUMS"}:
        raise ValueError("original manifest does not describe the complete asset set")
    for name, digest in checks.items():
        if sha256(Path(folder) / name) != digest:
            raise ValueError("original assets fail their manifest")


def check_candidate(folder, source_sha, run_id, tag, aapt, apksigner):
    report = verify(folder, source_sha, run_id, tag)
    # Re-probe the actual APK, module metadata, embedded APK, ABI and existing certificate.
    # validate() requires only the three payloads, not the report/manifest files.
    with tempfile.TemporaryDirectory() as tmp:
        for asset in report["assets"]:
            (Path(tmp) / asset["name"]).symlink_to((Path(folder) / asset["name"]).resolve())
        actual = validate(tmp, "nb4a.properties", "docs/preview3-version-evidence.json",
                          aapt=aapt, apksigner=apksigner)
    if actual["assets"] != report["assets"] or actual["apk"] != report["apk"]:
        raise ValueError("candidate report disagrees with fresh SDK/module checks")
    plain = next(a["size"] for a in report["assets"] if a["name"] == "wanbox-root-module-arm64-v8a.zip")
    apk = next(a["size"] for a in report["assets"] if a["name"].endswith(".apk"))
    if plain + apk > LIMIT:
        raise ValueError("candidate exceeds 26 MiB download budget")
    return report


def prepare(gh, candidate, backup, source_sha, run_id, tag, old_tag_sha,
            old_manifest_sha256, aapt, apksigner):
    if not PREVIEW_TAG.fullmatch(tag):
        raise ValueError("invalid preview tag")
    gh.candidate_run(run_id, source_sha)
    report = check_candidate(candidate, source_sha, run_id, tag, aapt, apksigner)
    names = [a["name"] for a in report["assets"]] + ["SHA256SUMS"]
    release = gh.release(tag)
    assert_release(release, tag, names)
    tag_identity = gh.tag(tag)
    if tag_identity["commit"] != old_tag_sha:
        raise ValueError("old tag changed; review required")
    backup = Path(backup)
    backup.mkdir(parents=True, exist_ok=False)
    dump(backup / "release-before.json", release)
    hashes = download_set(gh, release, backup / "assets")
    if hashes["SHA256SUMS"] != old_manifest_sha256:
        raise ValueError("old manifest changed; review required")
    check_manifest(backup / "assets", names)
    # A same-tag replacement cannot silently change package version, signature or native module.
    from verify_preview_candidate import probe_apk
    old_apk = next(n for n in names if n.endswith(".apk"))
    if probe_apk(backup / "assets" / old_apk, aapt=aapt, apksigner=apksigner) != report["apk"]:
        raise ValueError("replacement changes APK identity/version/signature/ABI")
    if fingerprint(gh.release(tag)) != fingerprint(release) or gh.tag(tag) != tag_identity:
        raise ValueError("release changed during backup")
    plan = {"repository": gh.repository, "tag": tag, "source_sha": source_sha,
            "candidate_run_id": int(run_id), "tag_identity": tag_identity,
            "release_before": fingerprint(release), "old_hashes": hashes,
            "candidate_hashes": {a["name"]: a["sha256"] for a in report["assets"]} |
                                {"SHA256SUMS": sha256(Path(candidate) / "SHA256SUMS")},
            "candidate_manifest_sha256": sha256(Path(candidate) / "SHA256SUMS")}
    dump(backup / "replacement-plan.json", plan)
    return plan


def execute(gh, candidate, backup, approved_plan_sha256, aapt, apksigner):
    backup = Path(backup)
    plan_path = backup / "replacement-plan.json"
    if sha256(plan_path) != approved_plan_sha256:
        raise ValueError("reviewed plan digest mismatch")
    plan = json.loads(plan_path.read_text(encoding="utf-8"))
    if gh.repository != plan["repository"]:
        raise ValueError("wrong repository")
    tag = plan["tag"]
    gh.candidate_run(plan["candidate_run_id"], plan["source_sha"])
    check_candidate(candidate, plan["source_sha"], plan["candidate_run_id"], tag, aapt, apksigner)
    names = list(plan["candidate_hashes"])
    for folder, hashes in ((Path(candidate), plan["candidate_hashes"]),
                           (backup / "assets", plan["old_hashes"])):
        for name, digest in hashes.items():
            if sha256(folder / name) != digest:
                raise ValueError("local payload or rollback copy changed")
    old = gh.release(tag)
    if fingerprint(old) != plan["release_before"] or gh.tag(tag) != plan["tag_identity"]:
        raise ValueError("release/tag changed since reviewed backup")
    with tempfile.TemporaryDirectory() as tmp:
        download_set(gh, old, tmp, plan["old_hashes"])
    release_id = old["id"]
    old_fields = {k: old.get(k) for k in ("name", "body", "draft", "prerelease", "target_commitish")}
    marker = ("\n\n<!-- wanbox-preview-replacement -->\n"
              f"同标签修复重发：源提交 `{plan['source_sha']}`，候选构建 `{plan['candidate_run_id']}`。\n"
              "标签保留原指向；已安装同版本不会收到自动更新，请下载同签名 APK 手动覆盖安装。\n"
              f"SHA256SUMS SHA-256: `{plan['candidate_manifest_sha256']}`。\n")
    try:
        gh.edit(release_id, {"draft": True})
        if gh.release_by_id(release_id).get("draft") is not True:
            raise ValueError("release was not hidden; no uploads allowed")
        gh.upload(tag, [Path(candidate) / n for n in names])
        fresh = gh.release_by_id(release_id)
        with tempfile.TemporaryDirectory() as tmp:
            download_set(gh, fresh, tmp, plan["candidate_hashes"])
        if gh.tag(tag) != plan["tag_identity"]:
            raise ValueError("tag changed during replacement")
        gh.edit(release_id, old_fields | {"body": (old.get("body") or "") + marker})
        published = gh.release_by_id(release_id)
        if published.get("draft") is not False or published.get("prerelease") is not True:
            raise ValueError("replacement was not republished")
        dump(backup / "replacement-result.json", {"state": "published", "source_sha": plan["source_sha"],
             "release": fingerprint(published), "tag_identity": gh.tag(tag)})
    except Exception as original:
        try:
            gh.edit(release_id, {"draft": True})
            gh.upload(tag, [backup / "assets" / n for n in names])
            restored = gh.release_by_id(release_id)
            with tempfile.TemporaryDirectory() as tmp:
                download_set(gh, restored, tmp, plan["old_hashes"])
            if gh.tag(tag) != plan["tag_identity"]:
                raise ValueError("tag changed; restored assets remain hidden pending review")
            gh.edit(release_id, old_fields)
            if any(gh.release_by_id(release_id).get(k) != v for k, v in old_fields.items()):
                raise ValueError("rollback metadata differs")
            dump(backup / "replacement-result.json", {"state": "rolled_back", "error": str(original)})
        except Exception as rollback:
            # Best effort keep a partial asset set hidden; never claim rollback success.
            try:
                gh.edit(release_id, {"draft": True})
            except Exception:
                pass
            dump(backup / "replacement-result.json", {"state": "rollback_failed",
                 "error": str(original), "rollback_error": str(rollback)})
            raise RuntimeError("replacement AND rollback failed; inspect Release before retry") from rollback
        raise RuntimeError("replacement failed; old bytes and metadata restored") from original


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=("prepare", "execute"))
    parser.add_argument("--repo", required=True)
    parser.add_argument("--candidate", required=True)
    parser.add_argument("--backup", required=True)
    parser.add_argument("--aapt", required=True)
    parser.add_argument("--apksigner", required=True)
    parser.add_argument("--source-sha")
    parser.add_argument("--run-id", type=int)
    parser.add_argument("--tag")
    parser.add_argument("--old-tag-sha")
    parser.add_argument("--old-manifest-sha256")
    parser.add_argument("--approved-plan-sha256")
    args = parser.parse_args()
    gh = GitHub(args.repo)
    if args.mode == "prepare":
        if not all((args.source_sha, args.run_id, args.tag, args.old_tag_sha, args.old_manifest_sha256)):
            parser.error("prepare requires exact source/run/tag and old tag/manifest digests")
        prepare(gh, args.candidate, args.backup, args.source_sha, args.run_id, args.tag,
                args.old_tag_sha, args.old_manifest_sha256, args.aapt, args.apksigner)
        print(f"Read-only preparation complete. Plan SHA-256: {sha256(Path(args.backup) / 'replacement-plan.json')}")
    else:
        if not re.fullmatch(r"[0-9a-f]{64}", args.approved_plan_sha256 or ""):
            parser.error("execute requires the exact reviewed plan SHA-256")
        execute(gh, args.candidate, args.backup, args.approved_plan_sha256, args.aapt, args.apksigner)
        print("Replacement completed; see replacement-result.json")


if __name__ == "__main__":
    main()

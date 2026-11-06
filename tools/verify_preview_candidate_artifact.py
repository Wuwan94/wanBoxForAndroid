#!/usr/bin/env python3
"""Verify the exact validated preview artifact before publishing it."""
import argparse
import hashlib
import json
from pathlib import Path
import re

MODULES = {
    "wanbox-root-module-arm64-v8a.zip",
    "wanbox-root-module-with-manager-arm64-v8a.zip",
}
PREVIEW_TAG = re.compile(r"v[0-9]+\.[0-9]+\.[0-9]+-preview\.[0-9]+\Z")
SHA256_LINE = re.compile(r"([0-9a-f]{64})  ([A-Za-z0-9._+-]+)\Z")


def sha256(path):
    digest = hashlib.sha256()
    with Path(path).open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def verify(directory, source_sha, run_id, tag):
    folder = Path(directory)
    if not re.fullmatch(r"[0-9a-f]{40}", source_sha):
        raise ValueError("approved source SHA must be a full lowercase commit SHA")
    if not PREVIEW_TAG.fullmatch(tag):
        raise ValueError("invalid preview tag")

    report_path = folder / "candidate-validation.json"
    report = json.loads(report_path.read_text(encoding="utf-8"))
    if report.get("source_sha") != source_sha:
        raise ValueError("candidate source SHA does not match the approved SHA")
    if report.get("run_id") != int(run_id):
        raise ValueError("candidate run ID does not match the publishing run")
    if report.get("candidate_tag") != tag:
        raise ValueError("candidate tag does not match the requested release tag")

    version_name = tag.removeprefix("v")
    apk_name = f"wanBoxForAndroid-{version_name}-arm64-v8a-release.apk"
    expected_names = {apk_name} | MODULES
    assets = report.get("assets")
    if not isinstance(assets, list) or len(assets) != 3:
        raise ValueError("candidate report must list exactly three release assets")
    names = [asset.get("name") for asset in assets]
    if len(set(names)) != len(names) or set(names) != expected_names:
        raise ValueError("candidate report asset names do not match the release allowlist")

    apk = report.get("apk", {})
    preview = report.get("channels", {}).get("preview", {})
    if apk.get("version_name") != version_name:
        raise ValueError("APK version name in validation report does not match the tag")
    if apk.get("apk_version_code") != preview.get("apk_version_code"):
        raise ValueError("APK version code in validation report is inconsistent")
    if apk.get("signer_count") != 1:
        raise ValueError("candidate report does not confirm exactly one APK signer")

    expected_files = expected_names | {"SHA256SUMS", "candidate-validation.json"}
    if {path.name for path in folder.iterdir()} != expected_files:
        raise ValueError("candidate artifact contains missing or unexpected files")

    checksums = {}
    for line in (folder / "SHA256SUMS").read_text(encoding="ascii").splitlines():
        match = SHA256_LINE.fullmatch(line)
        if not match or match[2] in checksums:
            raise ValueError("candidate SHA256SUMS is malformed or duplicated")
        checksums[match[2]] = match[1]
    if set(checksums) != expected_names:
        raise ValueError("candidate SHA256SUMS does not list exactly the release assets")

    report_by_name = {asset["name"]: asset for asset in assets}
    for name in expected_names:
        path = folder / name
        asset = report_by_name[name]
        actual_hash = sha256(path)
        if path.stat().st_size != asset.get("size"):
            raise ValueError(f"candidate asset size mismatch: {name}")
        if actual_hash != asset.get("sha256") or actual_hash != checksums.get(name):
            raise ValueError(f"candidate asset hash mismatch: {name}")

    # The build-side validator writes one canonical checksum line per asset in report order.
    canonical_manifest = "".join(
        f"{asset['sha256']}  {asset['name']}\n" for asset in assets
    )
    if (folder / "SHA256SUMS").read_text(encoding="ascii") != canonical_manifest:
        raise ValueError("candidate manifest differs from the validated asset report")
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--directory", required=True)
    parser.add_argument("--source-sha", required=True)
    parser.add_argument("--run-id", required=True, type=int)
    parser.add_argument("--tag", required=True)
    args = parser.parse_args()
    report = verify(args.directory, args.source_sha, args.run_id, args.tag)
    print(json.dumps({
        "source_sha": report["source_sha"],
        "run_id": report["run_id"],
        "candidate_tag": report["candidate_tag"],
        "assets": report["assets"],
    }, indent=2))


if __name__ == "__main__":
    main()

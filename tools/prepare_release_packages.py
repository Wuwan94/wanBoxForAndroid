"""Prepare the three verified, matching ARM64 release packages."""
import argparse
import hashlib
import pathlib
import re
import shutil
import subprocess
import sys
import tempfile
import urllib.request
import zipfile

ROOT = pathlib.Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "rootmodule"))
from bundle_manager import bundle
from verify_version import verify as verify_module
from verify_apk_abi import verify as verify_abi

RELEASE_CERTIFICATE = "AEC62C7003FA515534817250D3CD0DC705E19F4CD578A7A9A1D22519596EA598"
PREVIOUS_APK_URL = "https://github.com/Wuwan94/wanBoxForAndroid/releases/download/v3.0.8/wanBoxForAndroid-3.0.8-arm64-v8a.apk"
PREVIOUS_APK_SHA256 = "2feb812a9309163b4d0e2d8e02eada93a03eb6932a85e919802ed3d7bdda08c1"


def apk_fields(badging):
    line = next((line for line in badging.splitlines() if line.startswith("package: ")), "")
    return dict(re.findall(r"([A-Za-z]+)='([^']*)'", line))


def verify_signer(certificates):
    digests = re.findall(r"Signer #[0-9]+ certificate SHA-256 digest:\s*([0-9a-fA-F:]+)", certificates)
    if len(digests) != 1 or digests[0].replace(":", "").upper() != RELEASE_CERTIFICATE:
        raise ValueError("APK must use the original release signing certificate")


def validate_upgrade(previous_badging, previous_certificates, candidate_badging, candidate_certificates):
    previous, candidate = apk_fields(previous_badging), apk_fields(candidate_badging)
    verify_signer(previous_certificates)
    verify_signer(candidate_certificates)
    if not previous.get("name") or candidate.get("name") != previous["name"]:
        raise ValueError("Upgrade must preserve the previous release package name")
    old_code, new_code = previous.get("versionCode", ""), candidate.get("versionCode", "")
    if not old_code.isdecimal() or not new_code.isdecimal() or int(new_code) <= int(old_code):
        raise ValueError("Upgrade versionCode must be greater than the previous release")
    old_sdk = re.search(r"^sdkVersion:'([0-9]+)'$", previous_badging, re.MULTILINE)
    new_sdk = re.search(r"^sdkVersion:'([0-9]+)'$", candidate_badging, re.MULTILINE)
    if not old_sdk or not new_sdk or int(new_sdk[1]) > int(old_sdk[1]):
        raise ValueError("Upgrade must not drop previously supported Android versions")


def download_previous_apk(destination):
    request = urllib.request.Request(PREVIOUS_APK_URL, headers={"User-Agent": "wanBox-release-verifier"})
    digest, size = hashlib.sha256(), 0
    with urllib.request.urlopen(request, timeout=60) as response, destination.open("wb") as output:
        while chunk := response.read(1024 * 1024):
            size += len(chunk)
            if size > 64 * 1024 * 1024:
                raise ValueError("Previous release APK exceeds download limit")
            digest.update(chunk)
            output.write(chunk)
    if digest.hexdigest() != PREVIOUS_APK_SHA256:
        raise ValueError("Previous release APK checksum differs from the pinned v3.0.8 asset")
    return destination


def validate_identity(badging, certificates, metadata):
    fields = apk_fields(badging)
    expected = {
        "name": metadata["PACKAGE_NAME"],
        "versionCode": str(int(metadata["VERSION_CODE"]) * 5),
        "versionName": metadata["VERSION_NAME"],
    }
    if any(fields.get(key) != value for key, value in expected.items()):
        raise ValueError("APK package/version does not match release metadata")
    verify_signer(certificates)


def prepare(apk, module, output, aapt, apksigner):
    metadata = {}
    for line in (ROOT / "nb4a.properties").read_text(encoding="utf-8").splitlines():
        if line.strip() and not line.lstrip().startswith("#"):
            key, value = line.split("=", 1)
            if key.strip() in metadata:
                raise ValueError("Duplicate release metadata")
            metadata[key.strip()] = value.strip()
    badging = subprocess.run([str(aapt), "dump", "badging", str(apk)], check=True,
                             capture_output=True, text=True, encoding="utf-8", errors="replace").stdout
    certificates = subprocess.run([str(apksigner), "verify", "--verbose", "--print-certs", str(apk)],
                                  check=True, capture_output=True, text=True, encoding="utf-8", errors="replace").stdout
    validate_identity(badging, certificates, metadata)
    verify_abi(apk)
    verify_module(module)
    with tempfile.TemporaryDirectory(prefix="wanbox-upgrade-") as directory:
        previous = download_previous_apk(pathlib.Path(directory) / "previous.apk")
        verify_abi(previous)
        previous_badging = subprocess.run([str(aapt), "dump", "badging", str(previous)], check=True,
                                         capture_output=True, text=True, encoding="utf-8", errors="replace").stdout
        previous_certificates = subprocess.run([str(apksigner), "verify", "--verbose", "--print-certs", str(previous)],
                                              check=True, capture_output=True, text=True, encoding="utf-8", errors="replace").stdout
        validate_upgrade(previous_badging, previous_certificates, badging, certificates)
    print("Verified in-place upgrade compatibility from pinned v3.0.8 APK")
    output.mkdir(parents=True, exist_ok=True)
    standalone = output / ("wanBoxForAndroid-" + metadata["VERSION_NAME"] + "-arm64-v8a.apk")
    module_only = output / "wanbox-root-module-arm64-v8a.zip"
    with_manager = output / "wanbox-root-module-with-manager-arm64-v8a.zip"
    if apk.resolve() != standalone.resolve():
        shutil.copyfile(apk, standalone)
    if module.resolve() != module_only.resolve():
        shutil.copyfile(module, module_only)
    bundle(module_only, standalone, with_manager)
    verify_module(with_manager)
    with zipfile.ZipFile(with_manager) as archive:
        if archive.testzip() is not None or archive.read("manager.apk") != standalone.read_bytes():
            raise ValueError("Bundled manager APK differs from standalone release APK")
    artifacts = (standalone, module_only, with_manager)
    checksums = "".join(hashlib.sha256(path.read_bytes()).hexdigest() + "  " + path.name + "\n"
                        for path in artifacts)
    (output / "SHA256SUMS").write_text(checksums, encoding="ascii")
    return artifacts


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    for name in ("apk", "module", "output", "aapt", "apksigner"):
        parser.add_argument("--" + name, type=pathlib.Path, required=True)
    args = parser.parse_args()
    for artifact in prepare(args.apk, args.module, args.output, args.aapt, args.apksigner):
        print("Verified release package:", artifact.name)

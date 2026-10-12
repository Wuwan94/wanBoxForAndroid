"""Prepare the three verified, matching ARM64 release packages."""
import argparse
import hashlib
import pathlib
import re
import shutil
import subprocess
import sys
import zipfile

ROOT = pathlib.Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "rootmodule"))
from bundle_manager import bundle
from verify_version import verify as verify_module
from verify_apk_abi import verify as verify_abi

RELEASE_CERTIFICATE = "AEC62C7003FA515534817250D3CD0DC705E19F4CD578A7A9A1D22519596EA598"


def validate_identity(badging, certificates, metadata):
    line = next((line for line in badging.splitlines() if line.startswith("package: ")), "")
    fields = dict(re.findall(r"([A-Za-z]+)='([^']*)'", line))
    expected = {
        "name": metadata["PACKAGE_NAME"],
        "versionCode": str(int(metadata["VERSION_CODE"]) * 5),
        "versionName": metadata["VERSION_NAME"],
    }
    if any(fields.get(key) != value for key, value in expected.items()):
        raise ValueError("APK package/version does not match release metadata")
    digests = re.findall(r"Signer #[0-9]+ certificate SHA-256 digest:\s*([0-9a-fA-F:]+)", certificates)
    if len(digests) != 1 or digests[0].replace(":", "").upper() != RELEASE_CERTIFICATE:
        raise ValueError("APK must use the original release signing certificate")


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

"""Validate the unpublished candidate using the same module/version contracts as release."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys
import zipfile

from verify_apk_abi import verify as verify_apk_abi
from version_code import check_upgrade, parse_properties, resolve

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "rootmodule"))
from bundle_manager import ALLOWED
from pack import module_properties


def probe_apk(path, *, aapt, apksigner):
    apk = Path(path).resolve()
    try:
        verify_apk_abi(apk, thin=True)
    except AssertionError as exc:
        raise ValueError(f"Invalid thin APK ABI: {exc}") from exc
    try:
        badging = subprocess.run(
            [str(Path(aapt).resolve()), "dump", "badging", apk.name],
            cwd=apk.parent, check=True, capture_output=True, text=True,
            encoding="utf-8", errors="replace",
        ).stdout
        signature = subprocess.run(
            [str(Path(apksigner).resolve()), "verify", "--verbose", "--print-certs", apk.name],
            cwd=apk.parent, check=True, capture_output=True, text=True,
            encoding="utf-8", errors="replace",
        ).stdout
    except (OSError, subprocess.CalledProcessError) as exc:
        raise ValueError(f"APK SDK probe failed: {exc}") from exc
    package = re.findall(
        r"^package: name='([^']+)' versionCode='([0-9]+)' versionName='([^']+)'(?: |$)",
        badging, re.MULTILINE,
    )
    if len(package) != 1:
        raise ValueError("APK SDK probe returned invalid package metadata")
    certificates = re.findall(
        r"^Signer #[0-9]+ certificate SHA-256 digest: ([0-9a-fA-F]{64})\s*$",
        signature, re.MULTILINE,
    )
    if len(certificates) != 1 or re.findall(
        r"^Number of signers: ([0-9]+)\s*$", signature, re.MULTILINE
    ) != ["1"]:
        raise ValueError("APK must have exactly one verified signing certificate")
    package_name, version_code, version_name = package[0]
    return {
        "package_name": package_name, "version_name": version_name,
        "apk_version_code": int(version_code),
        "certificate_sha256": certificates[0].lower(), "signer_count": 1, "thin": True,
    }


def validate(directory, properties, evidence, *, aapt, apksigner):
    property_text = Path(properties).read_text(encoding="utf-8")
    props = parse_properties(property_text)
    release_evidence = json.loads(Path(evidence).read_text(encoding="utf-8"))
    baseline = release_evidence["baselines"]
    channels = {}
    for channel in ("formal", "preview"):
        current = resolve(props, channel)
        previous = baseline[channel]
        verdict = check_upgrade(current["apk_version_code"], current["module_version_code"],
                                previous["apk_version_code"], previous["module_version_code"])
        if not verdict["ok"]:
            raise ValueError(f"{channel}: {verdict['reasons']}")
        channels[channel] = current
    folder = Path(directory)
    apk = f"wanBoxForAndroid-{channels['preview']['version_name']}-arm64-v8a-release.apk"
    names = [apk, "wanbox-root-module-arm64-v8a.zip", "wanbox-root-module-with-manager-arm64-v8a.zip"]
    if {p.name for p in folder.iterdir()} != set(names):
        raise ValueError("Candidate must contain exactly one ARM64 APK and the two module variants")
    actual_apk = probe_apk(folder / apk, aapt=aapt, apksigner=apksigner)
    expected_apk = {
        "package_name": release_evidence["package_name"],
        "version_name": channels["preview"]["version_name"],
        "apk_version_code": channels["preview"]["apk_version_code"],
        "certificate_sha256": release_evidence["certificate_sha256"].lower(),
    }
    if props.get("PACKAGE_NAME") != expected_apk["package_name"]:
        raise ValueError("Metadata package does not match release evidence")
    for field, expected in expected_apk.items():
        if actual_apk[field] != expected:
            raise ValueError(f"APK {field} mismatch: {actual_apk[field]!r} != {expected!r}")
    expected_module = module_properties(
        (ROOT / "rootmodule/package/module.prop").read_bytes(),
        property_text, channel="preview",
    )
    for name in names:
        with zipfile.ZipFile(folder / name) as archive:
            if archive.testzip() is not None:
                raise ValueError(f"Corrupt archive: {name}")
        if name.endswith(".zip"):
            with zipfile.ZipFile(folder / name) as module:
                names_in_zip = module.namelist()
                expected_entries = ALLOWED | ({"manager.apk"} if name == names[2] else set())
                if len(names_in_zip) != len(set(names_in_zip)) or set(names_in_zip) != expected_entries:
                    raise ValueError("Unexpected module entries")
                if module.read("module.prop") != expected_module:
                    raise ValueError("Module version/identity mismatch")
                metadata = json.loads(module.read("package-manifest.json"))
                files = set(names_in_zip) - {"package-manifest.json"}
                if len(names_in_zip) != len(set(names_in_zip)) or set(metadata["files"]) != files:
                    raise ValueError("Invalid module package manifest")
                if metadata["schemaVersion"] != 1 or metadata["withManager"] != (name == names[2]):
                    raise ValueError("Invalid module variant")
                for entry in files:
                    data = module.read(entry)
                    if metadata["files"][entry] != {"size": len(data), "sha256": hashlib.sha256(data).hexdigest()}:
                        raise ValueError("Module package checksum mismatch")
    with zipfile.ZipFile(folder / names[2]) as combined:
        if combined.read("manager.apk") != (folder / apk).read_bytes():
            raise ValueError("Combined module does not contain the exact verified APK")
    assets = [{"name": n, "sha256": hashlib.sha256((folder / n).read_bytes()).hexdigest(),
               "size": (folder / n).stat().st_size} for n in names]
    return {"channels": channels, "apk": actual_apk, "assets": assets,
            "runtime_regression": "not_verified"}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--directory", required=True)
    parser.add_argument("--properties", default="nb4a.properties")
    parser.add_argument("--evidence", default="docs/preview3-version-evidence.json")
    parser.add_argument("--aapt", required=True)
    parser.add_argument("--apksigner", required=True)
    parser.add_argument("--report", required=True)
    args = parser.parse_args()
    report = validate(args.directory, args.properties, args.evidence,
                      aapt=args.aapt, apksigner=args.apksigner)
    Path(args.report).write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    (Path(args.directory) / "SHA256SUMS").write_text(
        "".join(f"{a['sha256']}  {a['name']}\n" for a in report["assets"]), encoding="ascii")
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()

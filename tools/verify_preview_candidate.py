"""Validate the unpublished candidate using the same module/version contracts as release."""
import argparse
import hashlib
import json
from pathlib import Path
import sys
import zipfile

from version_code import check_upgrade, parse_properties, resolve

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "rootmodule"))
from verify_version import verify


def validate(directory, properties, evidence):
    props = parse_properties(Path(properties).read_text(encoding="utf-8"))
    baseline = json.loads(Path(evidence).read_text(encoding="utf-8"))["baselines"]
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
    for name in names:
        with zipfile.ZipFile(folder / name) as archive:
            if archive.testzip() is not None:
                raise ValueError(f"Corrupt archive: {name}")
        if name.endswith(".zip"):
            verify(folder / name, channel="preview")
            with zipfile.ZipFile(folder / name) as module:
                names_in_zip = module.namelist()
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
        managers = [n for n in combined.namelist() if n.endswith(".apk")]
        if len(managers) != 1 or combined.read(managers[0]) != (folder / apk).read_bytes():
            raise ValueError("Combined module does not contain the exact verified APK")
    assets = [{"name": n, "sha256": hashlib.sha256((folder / n).read_bytes()).hexdigest(),
               "size": (folder / n).stat().st_size} for n in names]
    return {"channels": channels, "assets": assets, "runtime_regression": "not_verified"}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--directory", required=True)
    parser.add_argument("--properties", default="nb4a.properties")
    parser.add_argument("--evidence", default="docs/preview3-version-evidence.json")
    parser.add_argument("--report", required=True)
    args = parser.parse_args()
    report = validate(args.directory, args.properties, args.evidence)
    Path(args.report).write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    (Path(args.directory) / "SHA256SUMS").write_text(
        "".join(f"{a['sha256']}  {a['name']}\n" for a in report["assets"]), encoding="ascii")
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()

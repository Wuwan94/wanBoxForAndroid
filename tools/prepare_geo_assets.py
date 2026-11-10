"""Fetch immutable geo inputs and verify their pinned SHA-256 before packaging.
No latest alias, runtime release discovery or unverified fallback is permitted.
"""
import hashlib
import json
import lzma
import os
from pathlib import Path
import re
import tempfile
import urllib.request

ROOT = Path(__file__).resolve().parents[1]


def validate_pins(pins):
    assert set(pins) == {'geoip', 'geosite'}, 'expected exactly geoip and geosite'
    for name, pin in pins.items():
        assert re.fullmatch(r'[0-9]+', pin['version']), 'pin must be an explicit upstream release'
        assert re.fullmatch(r'[0-9a-f]{64}', pin['sha256']), 'invalid digest'
        expected = f"https://github.com/SagerNet/sing-{name}/releases/download/{pin['version']}/{name}.db"
        assert pin['url'] == expected, 'pin source must match its exact release and asset'


def fetch(url):
    with urllib.request.urlopen(url, timeout=120) as response:
        # Strictly bounded downloads, no insecure TLS context.
        data = response.read(64 * 1024 * 1024 + 1)
    assert len(data) <= 64 * 1024 * 1024, 'oversized geo input'
    return data


def prepare(pins, output, download=fetch):
    validate_pins(pins)
    # Verify both inputs before replacing any packaged file. Failed preparation leaves old assets intact.
    verified = {}
    for name, pin in pins.items():
        data = download(pin['url'])
        digest = hashlib.sha256(data).hexdigest()
        assert data and digest == pin['sha256'], f'{name}: SHA-256 mismatch; refusing to package'
        verified[name] = data
    output = Path(output)
    output.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(dir=output.parent) as d:
        stage = Path(d)
        for name, data in verified.items():
            (stage / f'{name}.db.xz').write_bytes(lzma.compress(data, preset=9))
            (stage / f'{name}.version.txt').write_text(pins[name]['version'])
        (stage / 'geo-provenance.json').write_text(json.dumps(pins, ensure_ascii=False, indent=2) + '\n')
        for file in stage.iterdir(): file.replace(output / file.name)
    report = json.dumps(pins, ensure_ascii=False, indent=2)
    print(report)
    summary = os.environ.get('GITHUB_STEP_SUMMARY')
    if summary:
        with open(summary, 'a', encoding='utf-8') as f:
            f.write('\n### Verified GeoIP / GeoSite inputs\n\n```json\n' + report + '\n```\n')


if __name__ == '__main__':
    prepare(json.loads((ROOT / 'buildScript/lib/geo-assets.lock.json').read_text()),
            ROOT / 'app/src/main/assets/sing-box')

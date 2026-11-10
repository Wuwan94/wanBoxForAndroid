import hashlib
import json
import lzma
from pathlib import Path
import tempfile
import unittest
from prepare_geo_assets import prepare, validate_pins, ROOT

class GeoAssetsTest(unittest.TestCase):
    def pins(self):
        return {name: dict(version='20260101', url=f'https://github.com/SagerNet/sing-{name}/releases/download/20260101/{name}.db',
                           sha256=hashlib.sha256(name.encode()).hexdigest()) for name in ('geoip','geosite')}
    def test_provenance_and_deterministic_verified_content(self):
        with tempfile.TemporaryDirectory() as d:
            p = Path(d)/'out'
            pins = self.pins()
            prepare(pins, p, lambda url: url.rsplit('/',1)[-1].split('.')[0].encode())
            before = (p/'geoip.db.xz').read_bytes()
            self.assertEqual(b'geoip', lzma.decompress(before))
            self.assertEqual(pins, json.loads((p/'geo-provenance.json').read_text()))
            prepare(pins, p, lambda url: url.rsplit('/',1)[-1].split('.')[0].encode())
            self.assertEqual(before, (p/'geoip.db.xz').read_bytes())
    def test_corrupt_or_empty_download_does_not_replace_old_assets(self):
        with tempfile.TemporaryDirectory() as d:
            p = Path(d); (p/'geoip.db.xz').write_bytes(b'old')
            for bad in (b'', b'wrong'):
                with self.assertRaises(AssertionError): prepare(self.pins(), p, lambda _: bad)
                self.assertEqual(b'old', (p/'geoip.db.xz').read_bytes())
    def test_invalid_or_latest_pin_rejected_before_download(self):
        for field, value in [('version','latest'), ('sha256',''), ('url','https://example.invalid/db')]:
            pins = self.pins(); pins['geoip'][field] = value
            with self.assertRaises(AssertionError): validate_pins(pins)
    def test_repository_pins_are_valid(self):
        validate_pins(json.loads((ROOT/'buildScript/lib/geo-assets.lock.json').read_text()))

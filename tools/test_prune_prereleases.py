import hashlib
import importlib.util
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('prune', Path(__file__).with_name('prune_prereleases.py'))
prune = importlib.util.module_from_spec(spec)
spec.loader.exec_module(prune)


def release(id, prerelease=True, draft=False, published='2026-10-22T00:00:00Z'):
    return dict(id=id, prerelease=prerelease, draft=draft, published_at=published, tag_name=f'v3.0.10-preview.{id}')


class PruneTests(unittest.TestCase):
    def test_preserves_stable_drafts_current_and_tags(self):
        old, keep = release(1), release(2)
        self.assertEqual(prune.prune_candidates([release(3, False), release(4, draft=True), old, keep], keep), [old])
    def test_stale_run_and_missing_release_fail(self):
        with self.assertRaises(ValueError): prune.prune_candidates([release(1), release(2)], release(1))
        with self.assertRaises(ValueError): prune.prune_candidates([release(2)], release(1))
    def test_assets_checksum_and_publication_gate(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            payloads = prune.MODULES | {'manager.apk'}
            for name in payloads: (root / name).write_bytes(b'payload')
            sums = ''.join(f'{hashlib.sha256(b"payload").hexdigest()}  {name}\n' for name in sorted(payloads))
            (root / 'SHA256SUMS').write_text(sums)
            keep = release(2)
            keep['assets'] = [dict(name=n, size=(root / n).stat().st_size, state='uploaded') for n in payloads | {'SHA256SUMS'}]
            prune.validate_assets(keep, root)
            for key, val in [('draft', True), ('prerelease', False), ('published_at', None)]:
                with self.assertRaises(ValueError): prune.validate_assets(dict(keep, **{key: val}), root)
            (root / 'manager.apk').write_bytes(b'changed')
            with self.assertRaises(ValueError): prune.validate_assets(keep, root)
    def test_incomplete_assets_fail(self):
        keep = release(1); keep['assets'] = []
        with self.assertRaises(ValueError): prune.validate_assets(keep, Path('/unused'))

class WorkflowRetentionTests(unittest.TestCase):
    def test_cleanup_runs_only_after_publishing_and_uses_shared_serialization(self):
        workflows = Path(__file__).resolve().parents[1] / '.github/workflows'
        for name in ['preview.yml', 'release.yml']:
            text = (workflows / name).read_text()
            self.assertIn('group: wanbox-release-publication', text)
            self.assertIn('cancel-in-progress: false', text)
            self.assertLess(text.index('uses: softprops/action-gh-release@v2'), text.index('run: python3 tools/prune_prereleases.py'))
        formal = (workflows / 'release.yml').read_text()
        after = formal.split('- name: Verify published preview and retain only newest prerelease')[1]
        self.assertIn('if: contains(', after)

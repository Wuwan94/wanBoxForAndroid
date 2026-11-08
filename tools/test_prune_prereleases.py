import hashlib
import importlib.util
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('prune', Path(__file__).with_name('prune_prereleases.py'))
prune = importlib.util.module_from_spec(spec)
spec.loader.exec_module(prune)


def release(id, prerelease=True, draft=False, published='2026-10-22T00:00:00Z'):
    return dict(id=id, prerelease=prerelease, draft=draft, published_at=published, tag_name=f'v3.0.10-preview.{id}')


class PruneTests(unittest.TestCase):
    def test_selects_only_old_published_prereleases(self):
        old, keep = release(1), release(2)
        alpha = release(5)
        alpha['tag_name'] = 'v3.0.11-alpha.2'
        self.assertEqual(prune.prune_candidates([release(3, False), release(4, draft=True), alpha, old, keep], keep), [old])
    def test_stale_run_and_missing_release_fail(self):
        with self.assertRaises(ValueError): prune.prune_candidates([release(1), release(2)], release(1))
        with self.assertRaises(ValueError): prune.prune_candidates([release(2)], release(1))

    def test_exact_cleanup_target_preserves_other_old_previews(self):
        old_one, old_two, keep = release(1), release(2), release(3)
        old_one['tag_name'] = 'v3.0.11-preview.1'
        old_two['tag_name'] = 'v3.0.11-preview.2'
        keep['tag_name'] = 'v3.0.11-preview.3'
        releases = [old_one, old_two, keep]
        self.assertEqual(prune.prune_candidates(releases, keep, 'v3.0.11-preview.2'), [old_two])
        self.assertEqual(
            prune.orphan_preview_tags(
                ['v3.0.11-preview.1', 'v3.0.11-preview.2'],
                'v3.0.11-preview.3', [keep], 'v3.0.11-preview.2'),
            ['v3.0.11-preview.2'],
        )

    def test_old_preview_release_and_tag_deleted_only_after_live_checks(self):
        old = release(1)
        responses = [
            '{"id": 1, "tag_name": "v3.0.10-preview.1", "prerelease": true, "draft": false}',
            '{"ref": "refs/tags/v3.0.10-preview.1"}',
        ]
        calls = []
        def fake_gh(*args):
            calls.append(args)
            if '--method' in args:
                return ''
            return responses.pop(0)
        def fake_run(args, **kwargs):
            calls.append(tuple(args))
            return type('Result', (), {'returncode': 1, 'stdout': '', 'stderr': 'HTTP 404'})()
        with patch.object(prune, 'gh', side_effect=fake_gh), patch.object(prune.subprocess, 'run', side_effect=fake_run):
            prune.delete_old_preview(old, 'Wuwan94/wanBoxForAndroid')
        self.assertIn(('api', '--method', 'DELETE', 'repos/Wuwan94/wanBoxForAndroid/releases/1'), calls)
        self.assertIn(('api', '--method', 'DELETE', 'repos/Wuwan94/wanBoxForAndroid/git/refs/tags/v3.0.10-preview.1'), calls)
        self.assertLess(calls.index(('api', '--method', 'DELETE', 'repos/Wuwan94/wanBoxForAndroid/releases/1')),
                        calls.index(('api', '--method', 'DELETE', 'repos/Wuwan94/wanBoxForAndroid/git/refs/tags/v3.0.10-preview.1')))

    def test_refuses_non_preview_tag_deletion(self):
        stable = release(1)
        stable['tag_name'] = 'v3.0.11'
        with patch.object(prune, 'gh') as call:
            with self.assertRaises(ValueError): prune.delete_old_preview(stable, 'Wuwan94/wanBoxForAndroid')
            call.assert_not_called()
    def test_orphan_candidates_remove_only_older_preview_tags(self):
        draft = release(8, draft=True)
        draft['tag_name'] = 'v3.0.11-preview.8'
        tags = [
            'v3.0.10-preview.24', 'v3.0.10-preview.29', 'v3.0.10-preview.31',
            'v3.0.11-preview.2', 'v3.0.11-preview.8', 'v3.0.11-alpha.1',
            'v3.0.11-beta.1', 'v3.0.11',
        ]
        self.assertEqual(
            prune.orphan_preview_tags(tags, 'v3.0.11-preview.1', [draft]),
            ['v3.0.10-preview.24', 'v3.0.10-preview.29', 'v3.0.10-preview.31'],
        )

    def test_orphan_tag_deletion_checks_release_and_reference(self):
        calls = []
        def fake_run(args, **kwargs):
            calls.append(tuple(args))
            return type('Result', (), {'returncode': 1, 'stdout': '', 'stderr': 'HTTP 404'})()
        def fake_gh(*args):
            calls.append(args)
            if args[-1].endswith('/git/ref/tags/v3.0.10-preview.24'):
                return '{"ref": "refs/tags/v3.0.10-preview.24"}'
            return ''
        with patch.object(prune.subprocess, 'run', side_effect=fake_run), patch.object(prune, 'gh', side_effect=fake_gh):
            self.assertTrue(prune.delete_orphan_preview_tag(
                'v3.0.10-preview.24', 'Wuwan94/wanBoxForAndroid', 'v3.0.11-preview.1'))
        self.assertIn(('gh', 'api', 'repos/Wuwan94/wanBoxForAndroid/releases/tags/v3.0.10-preview.24'), calls)
        self.assertIn(('api', '--method', 'DELETE', 'repos/Wuwan94/wanBoxForAndroid/git/refs/tags/v3.0.10-preview.24'), calls)

    def test_orphan_tag_with_any_release_is_preserved(self):
        with patch.object(prune, 'release_exists', return_value=True), patch.object(prune, 'gh') as call:
            self.assertFalse(prune.delete_orphan_preview_tag(
                'v3.0.10-preview.24', 'Wuwan94/wanBoxForAndroid', 'v3.0.11-preview.1'))
            call.assert_not_called()

    def test_refuses_newer_or_non_preview_orphan_tag(self):
        with patch.object(prune, 'gh') as call:
            for tag in ['v3.0.11-preview.2', 'v3.0.11-alpha.1', 'v3.0.11']:
                with self.assertRaises(ValueError):
                    prune.delete_orphan_preview_tag(tag, 'Wuwan94/wanBoxForAndroid', 'v3.0.11-preview.1')
            call.assert_not_called()

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
        preview = (workflows / 'preview.yml').read_text()
        self.assertIn('name: wanBox ${{ env.PREVIEW_TAG }} — 稳定性预览', preview)
        self.assertIn("if: github.event_name == 'workflow_dispatch' && inputs.publish_release == true", preview)
        self.assertIn('default: false', preview)
        self.assertIn('approved_sha:', preview)
        self.assertIn('test "$GITHUB_REF" = refs/heads/main', preview)
        self.assertIn('test "$GITHUB_SHA" = "$APPROVED_SHA"', preview)
        self.assertIn('name: preview-candidate', preview)
        self.assertIn('tools/verify_preview_candidate_artifact.py', preview)
        self.assertIn('candidate/SHA256SUMS', preview)
        self.assertIn('run: python3 tools/prune_prereleases.py --repo "$GITHUB_REPOSITORY" --keep-tag "$PREVIEW_TAG"', preview)
        self.assertNotIn('--only-tag', preview)
        candidate_step = preview.split('name: Validate unpublished candidate assets', 1)[1].split(
            '- uses: actions/upload-artifact@v4', 1)[0]
        self.assertIn("python3 - <<'PY'", candidate_step)
        self.assertRegex(candidate_step, r"(?m)^[ \t]+PY\s*$")
        self.assertNotIn('sha256sum *.apk *.zip > SHA256SUMS', preview)
        self.assertLess(preview.index('name: Validate unpublished candidate assets'), preview.index('\n  publish:'))
        formal = (workflows / 'release.yml').read_text()
        after = formal.split('- name: Verify published preview and retain only newest prerelease')[1]
        self.assertIn('if: contains(', after)

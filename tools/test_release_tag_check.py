import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]

class TagCheckTest(unittest.TestCase):
    def run_check(self, code, message):
        with tempfile.TemporaryDirectory() as d:
            gh = Path(d) / 'gh'
            gh.write_text('#!/bin/sh\nprintf "%s\\n" "$FAKE_ERROR" >&2\nexit "$FAKE_CODE"\n')
            gh.chmod(0o755)
            env = dict(os.environ, PATH=d+':'+os.environ['PATH'], FAKE_CODE=str(code), FAKE_ERROR=message,
                       GITHUB_REPOSITORY='fixture/repo', RELEASE_TAG='v1.0.0')
            return subprocess.run(['bash', str(ROOT/'tools/check_release_tag.sh')], env=env,
                                  capture_output=True, text=True).returncode
    def test_existing_tag_refused(self):
        self.assertNotEqual(0, self.run_check(0, ''))
    def test_only_explicit_404_is_absent(self):
        self.assertEqual(0, self.run_check(1, 'gh: Not Found (HTTP 404)'))
    def test_all_other_errors_fail_closed(self):
        for error in ['gh: API rate limit exceeded (HTTP 403)', 'gh: Bad credentials (HTTP 401)',
                      'gh: timeout', 'gh: Too Many Requests (HTTP 429)', 'HTTP 404', 'gh: error (HTTP 500)', 'gh: dns error', '']:
            with self.subTest(error=error): self.assertNotEqual(0, self.run_check(1, error))
    def test_both_publish_guards_use_helper(self):
        s = (ROOT/'.github/workflows/release.yml').read_text()
        self.assertEqual(2, s.count('bash tools/check_release_tag.sh'))
        self.assertNotIn('if gh api "repos/$GITHUB_REPOSITORY/git/ref/tags/', s)

import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]

class TagCheckTest(unittest.TestCase):
    def run_check(self, code, response):
        with tempfile.TemporaryDirectory() as d:
            gh = Path(d) / 'gh'
            gh.write_text('#!/bin/sh\nprintf "%s" "$FAKE_RESPONSE"\nexit "$FAKE_CODE"\n')
            gh.chmod(0o755)
            env = dict(os.environ, PATH=d+':'+os.environ['PATH'], FAKE_CODE=str(code), FAKE_RESPONSE=response,
                       GITHUB_REPOSITORY='fixture/repo', RELEASE_TAG='v1.0.0')
            return subprocess.run(['bash', str(ROOT/'tools/check_release_tag.sh')], env=env,
                                  capture_output=True, text=True).returncode
    def test_existing_tag_refused(self):
        self.assertNotEqual(0, self.run_check(0, 'HTTP/2.0 200 OK\n{}'))
    def test_only_explicit_404_status_line_is_absent(self):
        self.assertEqual(0, self.run_check(1, 'HTTP/2.0 404 Not Found\n{"message":"Not Found"}'))
        self.assertEqual(0, self.run_check(1, 'HTTP/1.1 404 Not Found\n{"message":"Not Found"}'))
    def test_all_other_errors_fail_closed(self):
        for response in ['HTTP/2.0 403 Forbidden', 'HTTP/2.0 401 Unauthorized',
                         'HTTP/2.0 429 Too Many Requests', 'HTTP/2.0 500 Internal Server Error',
                         '', '{"message":"Not Found"}']:
            with self.subTest(response=response): self.assertNotEqual(0, self.run_check(1, response))
    def test_error_prose_is_not_trusted_as_status(self):
        # The old check regex-matched gh's stderr wording; a 404 mentioned only in
        # prose, without a real HTTP 404 status line, must fail closed.
        self.assertNotEqual(0, self.run_check(1, 'gh: Not Found (HTTP 404)'))
    def test_all_publish_guards_use_helper(self):
        s = (ROOT/'.github/workflows/release.yml').read_text()
        self.assertEqual(2, s.count('bash tools/check_release_tag.sh'))
        self.assertNotIn('if gh api "repos/$GITHUB_REPOSITORY/git/ref/tags/', s)

        preview = (ROOT/'.github/workflows/preview.yml').read_text()
        self.assertIn('bash tools/check_release_tag.sh', preview)
        self.assertNotIn('git ls-remote --exit-code --tags origin', preview)

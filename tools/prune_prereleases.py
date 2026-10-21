#!/usr/bin/env python3
"""Prune only published prerelease records after verifying the new preview assets.
Tags and all stable/draft releases are preserved. Uses the job's gh authentication.
"""
import argparse
import hashlib
import json
import pathlib
import re
import subprocess
import tempfile

MODULES = {'wanbox-root-module-arm64-v8a.zip', 'wanbox-root-module-with-manager-arm64-v8a.zip'}


def validate_assets(release, directory):
    if release.get('draft') or not release.get('prerelease') or not release.get('published_at'):
        raise ValueError('new release is not a published prerelease')
    assets = release['assets']
    names = [a['name'] for a in assets]
    apks = [n for n in names if n.endswith('.apk')]
    if len(names) != len(set(names)) or len(apks) != 1:
        raise ValueError('expected one unique public APK')
    expected = MODULES | {'SHA256SUMS', apks[0]}
    if set(names) != expected or any(a.get('state') != 'uploaded' or a.get('size', 0) <= 0 for a in assets):
        raise ValueError('release assets incomplete')
    checksums = {}
    for line in (directory / 'SHA256SUMS').read_text().splitlines():
        match = re.fullmatch(r'([0-9a-f]{64})  ([A-Za-z0-9._+-]+)', line)
        if not match or match[2] in checksums:
            raise ValueError('invalid checksum manifest')
        checksums[match[2]] = match[1]
    if set(checksums) != expected - {'SHA256SUMS'}:
        raise ValueError('incomplete checksum manifest')
    for asset in assets:
        path = directory / asset['name']
        if path.stat().st_size != asset['size']:
            raise ValueError('downloaded asset size mismatch')
        if asset['name'] in checksums and hashlib.sha256(path.read_bytes()).hexdigest() != checksums[asset['name']]:
            raise ValueError('downloaded asset checksum mismatch')


def prune_candidates(releases, keep):
    current = [r for r in releases if r['id'] == keep['id']]
    if len(current) != 1 or current[0]['tag_name'] != keep['tag_name']:
        raise ValueError('new release absent from complete listing')
    previews = [r for r in releases if r.get('prerelease') and not r.get('draft') and r.get('published_at')]
    newest = max(previews, key=lambda r: (r['published_at'], r['id']))
    if newest['id'] != keep['id']:
        raise ValueError('newer preview exists; refusing stale cleanup')
    return [r for r in previews if r['id'] != keep['id']]


def gh(*args):
    return subprocess.check_output(['gh', *args], text=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--repo', required=True)
    parser.add_argument('--keep-tag', required=True)
    args = parser.parse_args()
    if not re.fullmatch(r'[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+', args.repo):
        raise ValueError('invalid repository')
    if not re.fullmatch(r'v[0-9]+\.[0-9]+\.[0-9]+-(?:preview|alpha|beta)\.[0-9]+', args.keep_tag):
        raise ValueError('not an explicit preview tag')
    base = f'repos/{args.repo}/releases'
    keep = json.loads(gh('api', f'{base}/tags/{args.keep_tag}'))
    with tempfile.TemporaryDirectory() as temporary:
        directory = pathlib.Path(temporary)
        gh('release', 'download', args.keep_tag, '--repo', args.repo, '--dir', temporary)
        validate_assets(keep, directory)
    # The publication workflows share a concurrency group; also refuse stale runs.
    pages = json.loads(gh('api', '--paginate', '--slurp', f'{base}?per_page=100'))
    candidates = prune_candidates([r for page in pages for r in page], keep)
    for release in candidates:
        live = json.loads(gh('api', f"{base}/{release['id']}"))
        if live.get('prerelease') is not True or live.get('draft') or live['id'] == keep['id']:
            raise ValueError('release changed before deletion; stopping')
        gh('api', '--method', 'DELETE', f"{base}/{live['id']}")
        print(f"Deleted older prerelease record: {live['tag_name']} ({live['id']}); preserved its tag")
    print(f"Verified and retained preview: {keep['html_url']}; stable releases and all tags untouched")


if __name__ == '__main__':
    main()

# @author 雾晚
# SPDX-License-Identifier: GPL-3.0-or-later
"""Reject mismatched published module versions before creating a Release. @author 雾晚"""
import argparse
import pathlib
import sys
import zipfile
from pack import module_properties, resolve_channel

def verify(path, channel=None):
    root = pathlib.Path(__file__).parent
    resolved = resolve_channel(explicit=channel)
    expected = module_properties((root / 'package/module.prop').read_bytes(),
        (root.parent / 'nb4a.properties').read_text(encoding='utf-8'),
        channel=resolved)
    with zipfile.ZipFile(path) as bundle:
        entries = [i for i in bundle.infolist() if i.filename == 'module.prop']
        if len(entries) != 1 or entries[0].file_size > 4096:
            raise ValueError('invalid module metadata')
        if bundle.read(entries[0]) != expected: raise ValueError('module version/identity mismatch')

if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--channel', choices=['formal', 'preview'], default=None)
    parser.add_argument('files', nargs='*')
    args = parser.parse_args()
    for filename in args.files:
        verify(filename, channel=args.channel)
        print('Verified module version and identity:', filename)

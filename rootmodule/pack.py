# @author 雾晚
# SPDX-License-Identifier: GPL-3.0-or-later
"""Package only explicit ARM64 Android ELF inputs; no stale/generated binaries tracked."""
import argparse
import os
import pathlib
import struct
import zipfile
import hashlib
import json
import re

VALID_CHANNELS = {'formal', 'preview'}

def resolve_channel(env=None, explicit=None):
    """Resolve build channel with fail-closed validation for packaging and verification."""
    if explicit is not None:
        channel = explicit.strip().lower()
        if channel in VALID_CHANNELS:
            return channel
        raise ValueError(f'unknown channel: {explicit}')

    source_env = os.environ if env is None else env
    if 'WANBOX_BUILD_CHANNEL' in source_env:
        configured = source_env['WANBOX_BUILD_CHANNEL']
        if not configured or not configured.strip():
            raise ValueError('empty WANBOX_BUILD_CHANNEL')
        channel = configured.strip().lower()
        if channel in VALID_CHANNELS:
            return channel
        raise ValueError(f'unknown WANBOX_BUILD_CHANNEL: {configured}')

    if source_env.get('GITHUB_WORKFLOW') == 'Preview Build':
        return 'preview'
    return 'formal'

def module_properties(template, project_properties, channel=None):
    """Derive module identity/version from the same metadata as the APK. @author 雾晚"""
    values = {}
    for line in project_properties.splitlines():
        if not line.strip() or line.lstrip().startswith('#'): continue
        key, separator, value = line.partition('=')
        if not separator or key.strip() in values: raise ValueError('invalid project properties')
        values[key.strip()] = value.strip()

    normalized_channel = None if channel is None else channel.strip().lower()
    if normalized_channel is not None and normalized_channel not in VALID_CHANNELS:
        raise ValueError(f'unknown channel: {channel}')

    if normalized_channel == 'formal':
        version, code = values.get('VERSION_NAME', ''), values.get('VERSION_CODE', '')
    elif normalized_channel == 'preview':
        version, code = values.get('PRE_VERSION_NAME', ''), values.get('PRE_VERSION_CODE', '')
    else:
        # 兼容无显式 channel 的历史测试契约：缺少 VERSION_NAME 时回退使用 PRE 前缀版本
        version_name = values.get('VERSION_NAME', '')
        is_official = version_name and not any(x in version_name.lower() for x in ['preview', 'alpha', 'beta'])
        if is_official:
            version, code = values.get('VERSION_NAME', ''), values.get('VERSION_CODE', '')
        else:
            version, code = values.get('PRE_VERSION_NAME', ''), values.get('PRE_VERSION_CODE', '')

    if not re.fullmatch(r'[0-9][0-9A-Za-z.+_-]{0,79}', version): raise ValueError('invalid version')
    if not re.fullmatch(r'[1-9][0-9]{0,9}', code) or int(code) > 2147483647:
        raise ValueError('invalid version code')

    replacements = {'version': version, 'versionCode': code}
    seen, lines = set(), []
    for line in template.decode('utf-8').splitlines():
        key = line.partition('=')[0]
        if key in replacements:
            if key in seen: raise ValueError('duplicate module version')
            seen.add(key); line = key + '=' + replacements[key]
        lines.append(line)
    if seen != set(replacements): raise ValueError('missing module version')
    return ('\n'.join(lines) + '\n').encode('utf-8')

def check_elf(path, abi):
    data = path.read_bytes()
    if len(data) < 64 or data[:4] != b'\x7fELF' or data[4:6] != b'\x02\x01':
        raise ValueError('expected 64-bit little-endian ELF')
    if abi != 'arm64-v8a' or struct.unpack_from('<H', data, 18)[0] != 183:
        raise ValueError('ABI mismatch')
    if struct.unpack_from('<H', data, 16)[0] != 3:
        raise ValueError('Android executable must be PIE')
    return data

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--abi', choices=['arm64-v8a'], required=True)
    parser.add_argument('--channel', choices=['formal', 'preview'], default=None)
    for name in ('core', 'cli', 'output'):
        parser.add_argument('--' + name, type=pathlib.Path, required=True)
    args = parser.parse_args()
    channel = resolve_channel(explicit=args.channel)
    core, cli = check_elf(args.core, args.abi), check_elf(args.cli, args.abi)
    if b'core_config_invalid' not in core:
        raise ValueError('rootbox predates module validation; rebuild native core first')
    args.output.parent.mkdir(parents=True, exist_ok=True)
    files = {item.name: item.read_bytes().replace(b"\r\n", b"\n")
        for item in sorted((pathlib.Path(__file__).parent / 'package').iterdir())}
    files['module.prop'] = module_properties(files['module.prop'],
        (pathlib.Path(__file__).parent.parent / 'nb4a.properties').read_text(encoding='utf-8'),
        channel=channel)
    files['LICENSE'] = (pathlib.Path(__file__).parent.parent / 'LICENSE').read_bytes()
    files['LIBCORE-LICENSE'] = (pathlib.Path(__file__).parent.parent / 'libcore' / 'LICENSE').read_bytes()
    files['bin/rootbox'], files['bin/wanboxctl'] = core, cli
    files['package-manifest.json'] = manifest(files, False)
    with zipfile.ZipFile(args.output, 'w', zipfile.ZIP_DEFLATED) as out:
        for name, data in files.items(): out.writestr(name, data)

def manifest(files, with_manager):
    return json.dumps({'schemaVersion': 1, 'withManager': with_manager, 'files': {
        name: {'size': len(data), 'sha256': hashlib.sha256(data).hexdigest()}
        for name, data in files.items() if name != 'package-manifest.json'
    }}, separators=(',', ':')).encode()

if __name__ == '__main__':
    main()

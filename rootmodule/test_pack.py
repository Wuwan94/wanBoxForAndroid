# @author 雾晚
import pathlib
import struct
import tempfile
import unittest
import subprocess
import sys
import zipfile
import json
import hashlib
import shutil
from bundle_manager import bundle
from pack import check_elf, module_properties, collect_package_files
from verify_version import verify

class PackageTest(unittest.TestCase):
    def test_preview_metadata_replaces_stale_template_and_rejects_invalid_input(self):
        template = b'id=wanbox\r\nversion=old\r\nversionCode=1\r\nauthor=fixture\r\n'
        props = 'PRE_VERSION_NAME=3.0.7-preview.99\nPRE_VERSION_CODE=999\n'
        result = module_properties(template, props)
        self.assertEqual(b'id=wanbox\nversion=3.0.7-preview.99\nversionCode=999\nauthor=fixture\n', result)
        for invalid in [props.replace('999', '0'), props.replace('999', '2147483648'),
                        props.replace('3.0.7-preview.99', '../bad'), props + 'PRE_VERSION_CODE=2\n', '']:
            with self.assertRaises(ValueError): module_properties(template, invalid)
        with self.assertRaises(ValueError): module_properties(b'version=old\n', props)
    def test_abi_and_pie_are_checked(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = pathlib.Path(tmp) / 'core'
            data = bytearray(64)
            data[:6] = b'\x7fELF\x02\x01'
            struct.pack_into('<HH', data, 16, 3, 183)
            path.write_bytes(data)
            self.assertEqual(bytes(data), check_elf(path, 'arm64-v8a'))
            struct.pack_into('<H', data, 18, 62)
            path.write_bytes(data)
            with self.assertRaises(ValueError): check_elf(path, 'arm64-v8a')
            data[:4] = b'nope'; path.write_bytes(data)
            with self.assertRaises(ValueError): check_elf(path, 'arm64-v8a')

    def test_scripts_are_module_owned_and_do_not_flush_other_routes(self):
        source = pathlib.Path(__file__).parent / 'package'
        service = (source / 'service.sh').read_text()
        self.assertIn('__internal boot', service)
        self.assertNotIn('/data/adb/service.d', service)
        for file in source.glob('*.sh'):
            text = file.read_text()
            self.assertNotIn('iptables -F', text)
            self.assertNotIn('ip rule flush', text)
            if file.name != 'uninstall.sh':
                self.assertNotIn('rm -rf /data/adb/wanbox', text)
        # v3.0.15: uninstall.sh may wipe the data root ONLY behind the
        # explicit .purge_user_data flag (user data is otherwise preserved).
        uninstall = (source / 'uninstall.sh').read_text()
        # Exact data-root wipe (the run/* cleanup line does not count).
        self.assertEqual(1, uninstall.count('rm -rf /data/adb/wanbox 2>'))
        flag = uninstall.index('.purge_user_data')
        self.assertLess(flag, uninstall.index('rm -rf /data/adb/wanbox 2>'))
        self.assertIn('ARCH" = arm64', (source / 'customize.sh').read_text())
        installer = (source / 'customize.sh').read_text()
        self.assertIn('__internal schedule-install "$$"', installer)
        self.assertNotIn('__internal activate', installer)
        self.assertIn('install-manager', installer)
        self.assertIn('"$WANBOX_DATA_MODE"', installer)
        self.assertIn('"$WANBOX_APK_MODE" = install', installer)
        self.assertIn('wanbox_install_options || abort', installer)

    def test_actual_packager_preserves_scripts_licenses_and_non_applied_example(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp = pathlib.Path(tmp)
            data = bytearray(64)
            data[:6] = b'\x7fELF\x02\x01'
            struct.pack_into('<HH', data, 16, 3, 183)
            core, cli, output = tmp / 'core', tmp / 'cli', tmp / 'out.zip'
            core.write_bytes(data + b'core_config_invalid'); cli.write_bytes(data)
            subprocess.run([sys.executable, str(pathlib.Path(__file__).with_name('pack.py')),
                '--abi', 'arm64-v8a', '--core', str(core), '--cli', str(cli), '--output', str(output)], check=True)
            verify(output)
            with zipfile.ZipFile(output) as z:
                self.assertIsNone(z.testzip())
                for name in ('LICENSE', 'LIBCORE-LICENSE', 'README.md', 'installer-options.sh', 'example.snapshot.json',
                             'bin/rootbox', 'bin/wanboxctl', 'webroot/index.html', 'webroot/app.mjs',
                             'webroot/controller.mjs', 'webroot/style.css'):
                    self.assertIn(name, z.namelist())
                for name in ('service.sh', 'customize.sh', 'uninstall.sh'):
                    self.assertNotIn(b'\r', z.read(name))
                    self.assertNotIn(b'example.snapshot.json', z.read(name))
                m = json.loads(z.read('package-manifest.json'))
                self.assertFalse(m['withManager'])
                for name, spec in m['files'].items():
                    data = z.read(name)
                    self.assertEqual({'size': len(data), 'sha256': hashlib.sha256(data).hexdigest()}, spec)
            apk, with_apk = tmp / 'manager.apk', tmp / 'with-manager.zip'
            with zipfile.ZipFile(apk, 'w') as z:
                z.writestr('AndroidManifest.xml', b'fixture')
                z.writestr('lib/arm64-v8a/libfixture.so', data)
            bundle(output, apk, with_apk)
            verify(with_apk)
            with zipfile.ZipFile(with_apk) as z:
                self.assertTrue(json.loads(z.read('package-manifest.json'))['withManager'])
                self.assertEqual(apk.read_bytes(), z.read('manager.apk'))
            with zipfile.ZipFile(output, 'a') as z: z.writestr('../escape', b'x')
            with self.assertRaises(ValueError): bundle(output, apk, with_apk)

    def test_webroot_files_are_collected_recursively_and_unknown_files_are_rejected(self):
        source = pathlib.Path(__file__).parent / 'package'
        files = collect_package_files(source)
        expected = {'webroot/index.html', 'webroot/app.mjs', 'webroot/controller.mjs', 'webroot/style.css'}
        self.assertEqual(expected, {name for name in files if name.startswith('webroot/')})

        with tempfile.TemporaryDirectory() as tmp:
            copied = pathlib.Path(tmp) / 'package'
            shutil.copytree(source, copied)
            (copied / 'webroot' / 'unexpected.js').write_text('not allowlisted', encoding='utf-8')
            with self.assertRaises(ValueError):
                collect_package_files(copied)

    def test_initial_install_sets_every_webui_asset_readable(self):
        customize = (pathlib.Path(__file__).parent / 'package' / 'customize.sh').read_text()
        for name in ('index.html', 'app.mjs', 'controller.mjs', 'style.css'):
            with self.subTest(name=name):
                self.assertIn(f'set_perm "$MODPATH/webroot/{name}" 0 0 0644', customize)

if __name__ == '__main__': unittest.main()

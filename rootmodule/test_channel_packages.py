# @author 雾晚
import os, sys, json, struct, hashlib, pathlib, zipfile, tempfile, unittest, subprocess
from unittest.mock import patch
from bundle_manager import bundle
from verify_version import verify

class ChannelPackageIntegrationTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.dir = pathlib.Path(self.tmp.name)
        data = bytearray(64)
        data[:6] = b'\x7fELF\x02\x01'
        struct.pack_into('<HH', data, 16, 3, 183)
        self.core = self.dir / 'core'; self.core.write_bytes(data + b'core_config_invalid')
        self.cli = self.dir / 'cli'; self.cli.write_bytes(data)
        self.apk = self.dir / 'manager.apk'
        with zipfile.ZipFile(self.apk, 'w') as z:
            z.writestr('AndroidManifest.xml', b'test')
            z.writestr('lib/arm64-v8a/libtest.so', data)
        self.pack_py = str(pathlib.Path(__file__).with_name('pack.py'))

    def tearDown(self): self.tmp.cleanup()

    def build(self, out_name, extra_args=(), env_dict=None):
        out = self.dir / out_name
        env = os.environ.copy()
        for k in ('WANBOX_BUILD_CHANNEL', 'GITHUB_WORKFLOW'): env.pop(k, None)
        if env_dict: env.update(env_dict)
        cmd = [sys.executable, self.pack_py, '--abi', 'arm64-v8a', '--core', str(self.core),
               '--cli', str(self.cli), '--output', str(out)] + list(extra_args)
        subprocess.run(cmd, env=env, check=True)
        return out

    def test_formal_and_preview_packaging_and_verification(self):
        # 1. 显式 formal 渠道生成与双向校验
        pure_f = self.build('pure_f.zip', extra_args=['--channel', 'formal'])
        bnd_f = self.dir / 'bnd_f.zip'; bundle(pure_f, self.apk, bnd_f)
        verify(pure_f, channel='formal'); verify(bnd_f, channel='formal')
        with self.assertRaises(ValueError): verify(pure_f, channel='preview')
        with self.assertRaises(ValueError): verify(pure_f, channel='unknown')

        # 2. 显式 preview 渠道生成与双向校验
        pure_p = self.build('pure_p.zip', extra_args=['--channel', 'preview'])
        bnd_p = self.dir / 'bnd_p.zip'; bundle(pure_p, self.apk, bnd_p)
        verify(pure_p, channel='preview'); verify(bnd_p, channel='preview')
        with self.assertRaises(ValueError): verify(pure_p, channel='formal')

        # 3. 环境变量 GITHUB_WORKFLOW="Preview Build" 自动推导
        env_prev = {'GITHUB_WORKFLOW': 'Preview Build'}
        pure_env = self.build('pure_env.zip', env_dict=env_prev)
        with patch.dict(os.environ, env_prev, clear=True):
            verify(pure_env)

        # 4. 校验集成包内置 APK 字节一致性及 manifest 哈希
        for bnd in (bnd_f, bnd_p):
            with zipfile.ZipFile(bnd) as z:
                self.assertEqual(self.apk.read_bytes(), z.read('manager.apk'))
                m = json.loads(z.read('package-manifest.json'))
                self.assertTrue(m['withManager'])
                for name, spec in m['files'].items():
                    data = z.read(name)
                    self.assertEqual({'size': len(data), 'sha256': hashlib.sha256(data).hexdigest()}, spec)

if __name__ == '__main__':
    unittest.main()

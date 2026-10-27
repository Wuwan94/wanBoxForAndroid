# @author 雾晚
import os
import unittest
from unittest.mock import patch
from pack import module_properties, resolve_channel

class BuildChannelUnitTest(unittest.TestCase):
    def setUp(self):
        self.tpl = b'id=wanbox\r\nversion=old\r\nversionCode=1\r\nauthor=test\r\n'
        self.nb4a = 'VERSION_NAME=3.0.9\nVERSION_CODE=362\nPRE_VERSION_NAME=3.0.10-preview.1\nPRE_VERSION_CODE=363\n'

    def test_resolve_channel_env_and_explicit(self):
        self.assertEqual('formal', resolve_channel(env={}))
        self.assertEqual('formal', resolve_channel(env={'GITHUB_WORKFLOW': 'Release'}))
        self.assertEqual('preview', resolve_channel(env={'GITHUB_WORKFLOW': 'Preview Build'}))
        self.assertEqual('formal', resolve_channel(env={'GITHUB_WORKFLOW': 'Preview Build 2'}))
        self.assertEqual('preview', resolve_channel(env={'WANBOX_BUILD_CHANNEL': 'preview'}))
        self.assertEqual('formal', resolve_channel(env={'WANBOX_BUILD_CHANNEL': 'formal'}))
        self.assertEqual('formal', resolve_channel(env={'GITHUB_WORKFLOW': 'Preview Build'}, explicit='formal'))
        self.assertEqual('preview', resolve_channel(env={'WANBOX_BUILD_CHANNEL': 'formal'}, explicit='preview'))
        for bad in ('', '   '):
            with self.assertRaises(ValueError): resolve_channel(env={'WANBOX_BUILD_CHANNEL': bad})
        for invalid in ('nightly', 'bad'):
            with self.assertRaises(ValueError): resolve_channel(env={'WANBOX_BUILD_CHANNEL': invalid})
            with self.assertRaises(ValueError): resolve_channel(explicit=invalid)

    def test_module_properties_channel_selection_and_fallback(self):
        res_formal = module_properties(self.tpl, self.nb4a, channel='formal')
        self.assertIn(b'version=3.0.9\nversionCode=362\n', res_formal)
        res_prev = module_properties(self.tpl, self.nb4a, channel='preview')
        self.assertIn(b'version=3.0.10-preview.1\nversionCode=363\n', res_prev)
        with self.assertRaises(ValueError):
            module_properties(self.tpl, self.nb4a, channel='invalid')

        # 验证纯函数：传入显式 channel 时不受外界环境影响
        env_fake = {'GITHUB_WORKFLOW': 'Preview Build', 'WANBOX_BUILD_CHANNEL': 'preview'}
        with patch.dict(os.environ, env_fake, clear=True):
            self.assertIn(b'version=3.0.9\n', module_properties(self.tpl, self.nb4a, channel='formal'))
            self.assertIn(b'version=3.0.9\n', module_properties(self.tpl, self.nb4a))

        # 兼容旧契约：缺 VERSION_NAME 时回退使用 PRE 前缀版本
        legacy = 'PRE_VERSION_NAME=3.0.7-preview.1\nPRE_VERSION_CODE=100\n'
        self.assertIn(b'version=3.0.7-preview.1\nversionCode=100\n', module_properties(self.tpl, legacy))

if __name__ == '__main__':
    unittest.main()

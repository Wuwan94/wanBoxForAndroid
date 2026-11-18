#!/usr/bin/env python3
"""Tests for verify_diagnostics_package.py (B5, N2).

Builds synthetic clean/leaking tarballs in a temp dir and asserts
the verifier passes or fails accordingly. Run from the repo root:

    uv run tools/diagnostics/test_verify_diagnostics_package.py
"""

from __future__ import annotations

import io
import tarfile
import tempfile
import unittest
from pathlib import Path

import verify_diagnostics_package as verifier

CLEAN_README = (
    "wanBox 诊断包\n"
    "正式版: 3.0.14（版本码 2026）\n"
    "预览版: （版本码 unknown）\n"
    "内核版本: v1.15.0-alpha.13-reF1nd\n"
    "生成时间: 2026-11-18T12:34:56+08:00\n"
    "敏感信息已脱敏：订阅地址、节点链接、凭据、服务器地址、Wi-Fi 名称、MAC 地址、IP 地址已按规则打码。\n"
    "残留风险：路由规则的域名与规则集名会原样保留，可能反映分流意图；请检查后再对外分享。\n"
    "远程规则集只导出元数据，不含规则原文。\n"
    "缺失或已省略的文件: 无\n"
)

CLEAN_CONFIG = (
    '{"outbounds": [{"type": "trojan", "server": "***", "password": "***"}], '
    '"route": {"rules": [{"domain": ["example.com"]}]}}'
)

CLEAN_LOG = (
    "probe ok delay=42ms peer=203.0.x.x:443 ssid=*** mac=**:**:**:**:**:**\n"
    "dns 8.8.x.x query example.com\n"
)


def build_package(files: dict[str, bytes]) -> Path:
    """Write files into a temp tar.gz; caller keeps the temp dir alive."""
    tmp = tempfile.NamedTemporaryFile(suffix=".tar.gz", delete=False)
    tmp.close()
    with tarfile.open(tmp.name, "w:gz") as tar:
        for name, content in files.items():
            data = io.BytesIO(content)
            info = tarfile.TarInfo(name)
            info.size = len(content)
            tar.addfile(info, data)
    return Path(tmp.name)


class VerifyDiagnosticsPackageTest(unittest.TestCase):
    def test_clean_package_passes(self) -> None:
        path = build_package({
            "README.txt": CLEAN_README.encode(),
            "config/module-status.json": b'{"phase": "stopped"}',
            "rules/local/route-rules.json": CLEAN_CONFIG.encode(),
            "logs/service.log": CLEAN_LOG.encode(),
        })
        self.assertEqual([], verifier.verify(path))

    def test_missing_readme_fails(self) -> None:
        path = build_package({"logs/service.log": b"nothing\n"})
        findings = verifier.verify(path)
        self.assertTrue(any("README.txt missing" in f for f in findings))

    def test_readme_without_redaction_statement_fails(self) -> None:
        path = build_package({"README.txt": b"wanBox diagnostics\nno statement here\n"})
        findings = verifier.verify(path)
        self.assertTrue(any("no redaction statement" in f for f in findings))

    def test_node_link_fails(self) -> None:
        path = build_package({
            "README.txt": CLEAN_README.encode(),
            "logs/service.log": b"node vmess://eyJ2IjoiMiJ9 imported\n",
        })
        findings = verifier.verify(path)
        self.assertTrue(any("node link present" in f for f in findings))

    def test_subscription_secret_query_fails(self) -> None:
        path = build_package({
            "README.txt": CLEAN_README.encode(),
            "logs/service.log": b"fetch https://example.com/sub?token=abc123&ok=1\n",
        })
        findings = verifier.verify(path)
        self.assertTrue(any("unredacted secret query value" in f for f in findings))

    def test_redacted_secret_query_passes(self) -> None:
        path = build_package({
            "README.txt": CLEAN_README.encode(),
            "logs/service.log": b"fetch https://example.com/sub?token=***&ok=1\n",
        })
        self.assertEqual([], verifier.verify(path))

    def test_unmasked_mac_and_ipv4_fail(self) -> None:
        path = build_package({
            "README.txt": CLEAN_README.encode(),
            "logs/service.log": (
                b"mac 02:42:ac:11:00:02 peer 203.0.113.7:443\n"
            ),
        })
        findings = verifier.verify(path)
        self.assertTrue(any("unmasked MAC" in f for f in findings))
        self.assertTrue(any("unmasked IPv4" in f for f in findings))

    def test_unredacted_sensitive_json_key_fails(self) -> None:
        path = build_package({
            "README.txt": CLEAN_README.encode(),
            "config/module-status.json": b'{"server": "203.0.113.7", "password": "hunter2"}',
        })
        findings = verifier.verify(path)
        self.assertTrue(any("unredacted sensitive JSON key" in f for f in findings))

    def test_remote_rules_and_cache_and_certs_fail(self) -> None:
        path = build_package({
            "README.txt": CLEAN_README.encode(),
            "rules/remote/example.srs": b"binary",
            "files/cache.db": b"binary",
            "files/client.crt": b"binary",
        })
        findings = verifier.verify(path)
        self.assertTrue(any("remote rule content" in f for f in findings))
        self.assertTrue(any("cache.db" in f for f in findings))
        self.assertTrue(any("certificate/key" in f for f in findings))

    def test_invalid_gzip_fails(self) -> None:
        with tempfile.NamedTemporaryFile(suffix=".tar.gz", delete=False) as tmp:
            tmp.write(b"not a gzip at all")
            path = Path(tmp.name)
        findings = verifier.verify(path)
        self.assertTrue(any("not a valid tar.gz" in f for f in findings))


if __name__ == "__main__":
    unittest.main()

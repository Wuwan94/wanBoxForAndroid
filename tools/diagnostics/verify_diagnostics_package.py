#!/usr/bin/env python3
"""Verify a wanBox diagnostics package (B5, N2).

Checks a tar.gz produced by ``wanboxctl diagnostics export`` for
desensitization leaks and forbidden entries:

- valid tar.gz, ``README.txt`` present and stating redaction,
- no node links (vmess/vless/trojan/ss/hysteria2/tuic/juicity),
- no subscription URL carrying a non-redacted secret query value,
- no unmasked MAC address and no full dotted-quad IPv4 address,
- no sensitive JSON key with a non-``***`` value,
- no ``rules/remote/`` entries, no ``cache.db``, no certificate/key files.

This is a best-effort leak detector, not a proof of redaction:
it only sees what the package contains. Run from the repo root:

    uv run tools/diagnostics/verify_diagnostics_package.py <package.tar.gz>

Exit code 0 means no finding; 1 means at least one finding
(printed one per line as ``<entry>: <finding>``).
"""

from __future__ import annotations

import re
import sys
import tarfile
from pathlib import Path

MAX_MEMBER_BYTES = 64 * 1024 * 1024

NODE_SCHEMES = ("vmess", "vless", "trojan", "ss", "ssr", "hysteria", "hysteria2", "tuic", "juicity")
SECRET_QUERY_KEYS = ("token", "key", "secret", "password", "auth", "uuid", "hwid")
SECRET_JSON_KEYS = (
    "token", "key", "secret", "password", "passwd", "auth", "authorization",
    "uuid", "hwid", "api-key", "api_key", "apikey", "access-token", "access_token",
    "refresh-token", "refresh_token", "private-key", "private_key", "seed",
    "server", "address", "server-name", "server_name", "servername", "sni", "ssid",
)

NODE_LINK_RE = re.compile(
    r"\b(?:" + "|".join(NODE_SCHEMES) + r")://\S+", re.IGNORECASE
)
SECRET_QUERY_RE = re.compile(
    r"[?&](?:" + "|".join(SECRET_QUERY_KEYS) + r")=([^\"'\r\n\s,;}&]+)", re.IGNORECASE
)
SECRET_JSON_RE = re.compile(
    r'"((?:' + "|".join(re.escape(k) for k in SECRET_JSON_KEYS) + r'))"\s*:\s*"([^"]*)"',
    re.IGNORECASE,
)
MAC_RE = re.compile(r"\b[0-9a-fA-F]{2}(?::[0-9a-fA-F]{2}){5}\b")
IPV4_RE = re.compile(r"\b\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}\b")
MASKED_MAC = "**:**:**:**:**:**"


def check_member_text(name: str, text: str) -> list[str]:
    """Return findings for one decoded text member."""
    findings: list[str] = []
    for match in NODE_LINK_RE.finditer(text):
        findings.append(f"node link present: {match.group(0)[:48]}")
    for match in SECRET_QUERY_RE.finditer(text):
        if match.group(1) != "***":
            findings.append(f"unredacted secret query value: {match.group(0)[:48]}")
    for match in SECRET_JSON_RE.finditer(text):
        if match.group(2) != "***":
            findings.append(f"unredacted sensitive JSON key: {match.group(1)}")
    for match in MAC_RE.finditer(text):
        if match.group(0) != MASKED_MAC:
            findings.append(f"unmasked MAC address: {match.group(0)}")
    for match in IPV4_RE.finditer(text):
        findings.append(f"unmasked IPv4 address: {match.group(0)}")
    return findings


def check_member_name(name: str) -> list[str]:
    """Return findings for one member path."""
    lowered = name.lower()
    findings: list[str] = []
    if lowered.startswith("rules/remote/"):
        findings.append("remote rule content exported (only metadata is allowed)")
    if lowered.endswith("/cache.db") or lowered == "cache.db":
        findings.append("cache.db exported")
    if lowered.endswith((".crt", ".pem", ".key")):
        findings.append("certificate/key file exported")
    if "cert" in lowered or "private" in lowered:
        findings.append("certificate/private file exported")
    return findings


def verify(path: Path) -> list[str]:
    """Return a list of findings; empty means the package passes."""
    findings: list[str] = []
    try:
        tar = tarfile.open(path, "r:gz")
    except (tarfile.TarError, OSError) as error:
        return [f"not a valid tar.gz: {error}"]
    with tar:
        members = tar.getmembers()
        names = [m.name for m in members]
        if "README.txt" not in names:
            findings.append("README.txt missing")
        for member in members:
            for finding in check_member_name(member.name):
                findings.append(f"{member.name}: {finding}")
            if not member.isfile() or member.size > MAX_MEMBER_BYTES:
                continue
            extracted = tar.extractfile(member)
            if extracted is None:
                continue
            try:
                text = extracted.read().decode("utf-8")
            except UnicodeDecodeError:
                continue  # binary member (e.g. a rule-set blob); name checks apply
            for finding in check_member_text(member.name, text):
                findings.append(f"{member.name}: {finding}")
        readme = next((m for m in members if m.name == "README.txt"), None)
        if readme is not None and readme.isfile():
            extracted = tar.extractfile(readme)
            if extracted is not None:
                try:
                    readme_text = extracted.read().decode("utf-8")
                except UnicodeDecodeError:
                    readme_text = ""
                if "脱敏" not in readme_text and "redact" not in readme_text.lower():
                    findings.append("README.txt: no redaction statement")
    return findings


def main(argv: list[str]) -> int:
    if len(argv) != 2:
        print(f"usage: {argv[0]} <package.tar.gz>", file=sys.stderr)
        return 2
    findings = verify(Path(argv[1]))
    for finding in findings:
        print(finding)
    if findings:
        print(f"{len(findings)} finding(s)", file=sys.stderr)
        return 1
    print("diagnostics package OK")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))

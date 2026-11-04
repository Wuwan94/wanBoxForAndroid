# @author 雾晚
# SPDX-License-Identifier: GPL-3.0-or-later
"""wanBox 版本码的唯一判定规则。

正式线与预览线共用 `APK versionCode = 元数据版本码 × 5`（见
buildSrc/src/main/kotlin/Helpers.kt:154 与 :190）。把这条规则做成纯函数，
让 release.yml / preview.yml 不再各自用 shell 算术推断，避免两条渠道漂移。

CLI:
    python3 tools/version_code.py --properties nb4a.properties --channel formal
    python3 tools/version_code.py --properties nb4a.properties --channel formal \
        --baseline-apk-code 10050 --baseline-module-code 2010 --enforce
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path
from typing import Mapping

APK_MULTIPLIER = 5
MAX_ANDROID_VERSION_CODE = 2147483647
VALID_CODE = re.compile(r"^[1-9][0-9]{0,9}$")
VALID_CHANNELS = ("formal", "preview")


class VersionError(ValueError):
    """版本元数据不合法或升级方向不成立。"""


def parse_properties(text: str) -> dict[str, str]:
    """解析 .properties；拒绝重复键与没有等号的行。"""
    values: dict[str, str] = {}
    for lineno, raw in enumerate(text.splitlines(), 1):
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        key, sep, value = line.partition("=")
        if not sep:
            raise VersionError(f"第 {lineno} 行缺少 '=': {line!r}")
        key = key.strip()
        if not key:
            raise VersionError(f"第 {lineno} 行键为空")
        if key in values:
            raise VersionError(f"重复键: {key}")
        values[key] = value.strip()
    return values


def parse_code(raw: str | int, field: str = "code") -> int:
    """元数据版本码必须是正整数，且 ×5 后仍在 Android int 范围内。"""
    text = str(raw).strip()
    if not VALID_CODE.fullmatch(text):
        raise VersionError(f"{field} 必须是正整数且不以 0 开头: {text!r}")
    code = int(text)
    if code * APK_MULTIPLIER > MAX_ANDROID_VERSION_CODE:
        raise VersionError(f"{field} × {APK_MULTIPLIER} 超出 Android versionCode 上限")
    return code


def apk_version_code(metadata_code: int) -> int:
    """元数据版本码 → APK versionCode。"""
    return parse_code(metadata_code) * APK_MULTIPLIER


def resolve(properties: Mapping[str, str], channel: str) -> dict[str, object]:
    """按渠道给出 versionName / 元数据码 / APK 码 / 模块码。"""
    if channel not in VALID_CHANNELS:
        raise VersionError(f"未知渠道: {channel!r}（可选 formal / preview）")
    prefix = "" if channel == "formal" else "PRE_"
    name = properties.get(f"{prefix}VERSION_NAME", "").strip()
    raw_code = properties.get(f"{prefix}VERSION_CODE", "").strip()
    if not name:
        raise VersionError(f"缺少 {prefix}VERSION_NAME")
    if not raw_code:
        raise VersionError(f"缺少 {prefix}VERSION_CODE")
    code = parse_code(raw_code, f"{prefix}VERSION_CODE")
    return {
        "channel": channel,
        "version_name": name,
        "metadata_code": code,
        "apk_version_code": code * APK_MULTIPLIER,
        "module_version_code": code,
    }


def check_upgrade(
    formal_apk_code: int,
    formal_module_code: int,
    baseline_apk_code: int,
    baseline_module_code: int,
) -> dict[str, object]:
    """覆盖升级的静态前提：APK 与模块版本码都必须严格大于已安装基线。

    注意：这只证明 Android 不会因为降级拒绝安装，**不证明**数据保留、
    Root 模块状态或 TUN 重启正常；后者必须真机验证。
    """
    reasons: list[str] = []
    if formal_apk_code <= baseline_apk_code:
        reasons.append(
            f"APK versionCode {formal_apk_code} 未严格大于基线 {baseline_apk_code}"
        )
    if formal_module_code <= baseline_module_code:
        reasons.append(
            f"模块 versionCode {formal_module_code} 未严格大于基线 {baseline_module_code}"
        )
    return {"ok": not reasons, "reasons": reasons}


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--properties", default="nb4a.properties")
    parser.add_argument("--channel", choices=VALID_CHANNELS, default="formal")
    parser.add_argument("--baseline-apk-code", type=int)
    parser.add_argument("--baseline-module-code", type=int)
    parser.add_argument(
        "--enforce", action="store_true", help="校验升级方向，失败时非零退出"
    )
    parser.add_argument("--json", action="store_true", help="只输出 JSON")
    args = parser.parse_args(argv)

    try:
        text = Path(args.properties).read_text(encoding="utf-8")
        resolved = resolve(parse_properties(text), args.channel)
        if args.enforce:
            if args.baseline_apk_code is None or args.baseline_module_code is None:
                raise VersionError("--enforce 需要同时给出两个基线版本码")
            verdict = check_upgrade(
                int(resolved["apk_version_code"]),
                int(resolved["module_version_code"]),
                args.baseline_apk_code,
                args.baseline_module_code,
            )
            resolved["upgrade"] = verdict
    except (OSError, VersionError) as exc:
        print(f"version_code: {exc}", file=sys.stderr)
        return 2

    if args.json:
        print(json.dumps(resolved, ensure_ascii=False, indent=2))
    else:
        print(f"channel              : {resolved['channel']}")
        print(f"versionName          : {resolved['version_name']}")
        print(f"metadata versionCode : {resolved['metadata_code']}")
        print(f"APK versionCode      : {resolved['apk_version_code']}")
        print(f"module versionCode   : {resolved['module_version_code']}")
        if "upgrade" in resolved:
            verdict = resolved["upgrade"]
            print(f"upgrade feasible     : {verdict['ok']}")
            for reason in verdict["reasons"]:
                print(f"  - {reason}")

    if args.enforce and not resolved["upgrade"]["ok"]:
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

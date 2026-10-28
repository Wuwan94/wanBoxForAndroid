#!/usr/bin/env python3
# @author 雾晚
# SPDX-License-Identifier: GPL-3.0-or-later
"""摊开 Root 模块 zip 与 App APK 的内部构成。

工作词 7.1 只给总量，压体积必须知道分量：哪一项占了多少未压缩字节、
压进 zip 后还剩多少。本脚本只做读取与算术，不修改任何产物。

用法：
    python3 tools/size_breakdown.py <产物.zip|产物.apk> [<另一个产物>] [--top N]
"""
from __future__ import annotations

import argparse
import collections
import os
import sys
import zipfile


def group_of(name: str) -> str:
    """按内容类型归组，避免把 500 个 res/ 小文件混在一张表里。"""
    if name.startswith("lib/"):
        parts = name.split("/")
        return "/".join(parts[:2]) + "/ (*.so)" if len(parts) > 2 else "lib/"
    if name.startswith("classes") and name.endswith(".dex"):
        return "classes*.dex"
    if name == "resources.arsc":
        return "resources.arsc"
    if name.startswith(("assets/", "res/", "META-INF/")):
        return name.split("/", 1)[0] + "/"
    if name.startswith("bin/"):
        return "bin/（Go 二进制）"
    return "其他"


def analyze(path: str, top: int) -> None:
    if not os.path.isfile(path):
        print(f"找不到产物：{path}", file=sys.stderr)
        sys.exit(2)

    with zipfile.ZipFile(path) as archive:
        infos = archive.infolist()

    total_raw = sum(item.file_size for item in infos)
    total_zip = sum(item.compress_size for item in infos)
    on_disk = os.stat(path).st_size

    print(f"\n=== {path} ===")
    print(f"条目 {len(infos)} | 未压缩合计 {total_raw:,} | 压缩后合计 {total_zip:,} | 实际文件 {on_disk:,}")
    if total_raw:
        print(f"压缩率 {total_zip / total_raw:.1%}")

    groups: dict[str, list[int]] = collections.defaultdict(lambda: [0, 0, 0])
    for item in infos:
        slot = groups[group_of(item.filename)]
        slot[0] += 1
        slot[1] += item.file_size
        slot[2] += item.compress_size

    print("\n按类别（压缩后字节降序）")
    print(f"{'类别':<24}{'条目':>6}{'未压缩':>14}{'压缩后':>14}{'占比':>9}")
    for name, (count, raw, packed) in sorted(groups.items(), key=lambda kv: -kv[1][2]):
        share = packed / total_zip if total_zip else 0.0
        print(f"{name:<24}{count:>6d}{raw:>14,d}{packed:>14,d}{share:>8.1%}")

    print(f"\n最大的 {top} 个条目")
    print(f"{'路径':<56}{'未压缩':>14}{'压缩后':>14}")
    for item in sorted(infos, key=lambda x: -x.file_size)[:top]:
        print(f"{item.filename:<56}{item.file_size:>14,d}{item.compress_size:>14,d}")


def main() -> int:
    parser = argparse.ArgumentParser(description="摊开 zip / APK 内部构成")
    parser.add_argument("paths", nargs="+", help="模块 zip 或 APK 路径")
    parser.add_argument("--top", type=int, default=12, help="列出最大的 N 个条目，默认 12")
    args = parser.parse_args()
    for path in args.paths:
        analyze(path, args.top)
    return 0


if __name__ == "__main__":
    sys.exit(main())

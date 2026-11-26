#!/usr/bin/env python3
# @author 雾晚
# SPDX-License-Identifier: GPL-3.0-or-later
"""测量用户实际下载量：Root 模块 zip + App APK。

当前候选的硬门禁口径：
  合计 = 模块 zip 字节数 + App APK 字节数，判据 sum <= 27,262,976（26 MiB）。
只认 stat 读到的真实字节数，不接受估算或历史数字。

三种用法：
  1) 回归门禁（默认）：与本仓库记录的基线比较，增量超过 512 KB 即失败。
     基线随代码演进更新，避免把「尚未达标的绝对值」变成每次构建都红的噪声。
  2) 终态判定：加 --enforce-limit，直接按 27,262,976 判定，供 P7 验收使用。
  3) WebUI-only：加 --webui-only，只测源文件与模块 ZIP 内 WebUI 1 MiB 门禁；不计算 APK+模块总量。

退出码：0 通过；1 超限或相对基线回退超阈；2 文件缺失或参数错误。
"""
from __future__ import annotations

import argparse
import json
import os
import sys
from pathlib import Path
import zipfile

LIMIT = 27_262_976
REGRESSION_TOLERANCE = 512 * 1024
WEBUI_LIMIT = 1 << 20
WEBUI_FILES = {
    "index.html",
    "app.mjs",
    "controller.mjs",
    "style.css",
}


def file_size(path: str) -> int:
    """返回真实字节数；文件不存在直接退出，不允许用 0 或估算顶替。"""
    if not os.path.isfile(path):
        print(f"错误：找不到产物 {path}", file=sys.stderr)
        sys.exit(2)
    return os.stat(path).st_size


def human(n: int) -> str:
    return f"{n / 1048576:.2f} MiB"


def webui_source_size(root: str) -> int:
    """Validate and total the four fixed source assets; reject symlinks/extras."""
    directory = Path(root)
    if not directory.is_dir() or directory.is_symlink():
        raise ValueError(f"WebUI 源目录无效：{root}")
    files = set()
    for path in directory.rglob("*"):
        if path.is_symlink():
            raise ValueError(f"WebUI 不允许符号链接：{path}")
        if path.is_file():
            files.add(path.relative_to(directory).as_posix())
    if files != WEBUI_FILES:
        raise ValueError(f"WebUI 源文件集不匹配：缺少 {sorted(WEBUI_FILES - files)}，多出 {sorted(files - WEBUI_FILES)}")
    return sum((directory / name).stat().st_size for name in WEBUI_FILES)


def webui_package_size(module_zip: str) -> int:
    """Return uncompressed WebUI payload bytes from the exact fixed ZIP entry set."""
    with zipfile.ZipFile(module_zip) as archive:
        entries = [info for info in archive.infolist() if info.filename.startswith("webroot/") and not info.is_dir()]
    names = [info.filename.removeprefix("webroot/") for info in entries]
    if len(names) != len(set(names)) or set(names) != WEBUI_FILES:
        raise ValueError(f"模块 ZIP 内 WebUI 文件集不匹配：{sorted(names)}")
    return sum(info.file_size for info in entries)


def main() -> int:
    parser = argparse.ArgumentParser(
        description="测量模块 zip 与 App APK 的合计下载量（工作词 7.1 口径）"
    )
    parser.add_argument("--module", required=True, help="Root 模块 zip 路径")
    parser.add_argument("--apk", help="App APK 路径（除 --webui-only 外必填）")
    parser.add_argument("--limit", type=int, default=LIMIT, help=f"硬预算字节数，默认 {LIMIT}")
    parser.add_argument("--enforce-limit", action="store_true", help="按硬预算判定（P7 验收用）")
    parser.add_argument("--baseline", help="基线 JSON 路径；给出时做回归判定")
    parser.add_argument("--update-baseline", help="把本次实测写入该 JSON，并跳过判定")
    parser.add_argument("--json", dest="json_out", help="把结果写入该 JSON")
    parser.add_argument("--webui-source", help="静态 WebUI 源目录；启用源与 ZIP 内 WebUI 1 MiB 门禁")
    parser.add_argument("--webui-only", action="store_true", help="只测 WebUI 1 MiB 门禁，不计算下载总量")
    args = parser.parse_args()

    if args.webui_only:
        if not args.webui_source:
            parser.error("--webui-only requires --webui-source")
        if args.apk or args.enforce_limit or args.baseline or args.update_baseline:
            parser.error("--webui-only cannot be combined with APK or total-budget options")
    elif not args.apk:
        parser.error("--apk is required unless --webui-only is used")

    module = file_size(args.module)
    result = {"module": module}
    if args.webui_only:
        print(f"模块 zip\t{args.module}\t{module}")
        print("下载总量\t未计算（WebUI-only 模式）")
    else:
        apk = file_size(args.apk)
        total = module + apk
        gap = args.limit - total
        print(f"模块 zip\t{args.module}\t{module}")
        print(f"App APK\t{args.apk}\t{apk}")
        print(f"合计\t{total}\t上限 {args.limit}\t差额 {gap}")
        print()
        print(f"模块 zip : {module:>10d} 字节  ({human(module)})")
        print(f"App APK  : {apk:>10d} 字节  ({human(apk)})")
        print(f"合计     : {total:>10d} 字节  ({human(total)})")
        print(f"预算上限 : {args.limit:>10d} 字节  (26 MiB)")
        if gap >= 0:
            print(f"余量     : {gap:>10d} 字节  ({human(gap)})")
        else:
            print(f"超出     : {-gap:>10d} 字节  ({human(-gap)}，{(total / args.limit - 1) * 100:.1f}%)")
        result.update({
            "apk": apk,
            "total": total,
            "limit": args.limit,
            "gap": gap,
            "within_limit": total <= args.limit,
        })

    failed = []

    if args.webui_source:
        try:
            source_webui = webui_source_size(args.webui_source)
            package_webui = webui_package_size(args.module)
        except (OSError, ValueError, zipfile.BadZipFile) as error:
            print(f"错误：WebUI 体积检查失败：{error}", file=sys.stderr)
            return 2
        result["source_webui"] = source_webui
        result["package_webui_uncompressed"] = package_webui
        result["webui_limit"] = WEBUI_LIMIT
        result["webui_within_limit"] = source_webui <= WEBUI_LIMIT and package_webui <= WEBUI_LIMIT
        print(f"源 WebUI\t{args.webui_source}\t{source_webui}")
        print(f"ZIP WebUI（未压缩）	{args.module}	{package_webui}")
        for label, size in (("源 WebUI", source_webui), ("ZIP WebUI", package_webui)):
            if size > WEBUI_LIMIT:
                failed.append(f"{label} {size} 字节超过 1 MiB 门禁 {WEBUI_LIMIT} 字节")
            else:
                print(f"{label} 余量	{WEBUI_LIMIT - size} 字节（1 MiB 上限）")

    if args.update_baseline:
        payload = {
            "note": "体积回归基线：只随真实构建产物更新，禁止手填估算值",
            "limit": args.limit,
            "tolerance": REGRESSION_TOLERANCE,
            "module": module,
            "apk": apk,
            "total": total,
        }
        with open(args.update_baseline, "w", encoding="utf-8", newline="\n") as handle:
            json.dump(payload, handle, ensure_ascii=False, indent=2)
            handle.write("\n")
        print(f"\n基线已更新：{args.update_baseline}")
    elif args.baseline:
        if not os.path.isfile(args.baseline):
            print(f"错误：找不到基线文件 {args.baseline}", file=sys.stderr)
            return 2
        with open(args.baseline, encoding="utf-8") as handle:
            base = json.load(handle)
        delta = total - int(base.get("total", 0))
        result["baseline_total"] = int(base.get("total", 0))
        result["delta"] = delta
        if delta > REGRESSION_TOLERANCE:
            failed.append(
                f"相对基线增加 {delta} 字节（{(delta) / 1024:.1f} KiB），"
                f"超过 {REGRESSION_TOLERANCE // 1024} KiB 阈值"
            )
        else:
            print(f"相对基线   : {delta:+d} 字节（阈值 +{REGRESSION_TOLERANCE // 1024} KiB）")

    if args.enforce_limit and total > args.limit:
        failed.append(f"合计 {total} 字节超过硬预算 {args.limit} 字节")

    if args.json_out:
        with open(args.json_out, "w", encoding="utf-8", newline="\n") as handle:
            json.dump(result, handle, ensure_ascii=False, indent=2)
            handle.write("\n")

    if failed:
        print("\n判定：FAIL", file=sys.stderr)
        for item in failed:
            print(f"  - {item}", file=sys.stderr)
        return 1

    print("\n判定：PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())

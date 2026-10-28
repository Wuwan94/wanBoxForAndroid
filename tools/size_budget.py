#!/usr/bin/env python3
# @author 雾晚
# SPDX-License-Identifier: GPL-3.0-or-later
"""测量用户实际下载量：Root 模块 zip + App APK。

口径由《wanBoxForAndroid 全量重构工作词》7.1 节固定，不得改动：
  合计 = 模块 zip 字节数 + App APK 字节数，判据 sum <= 26,214,400。
只认 stat 读到的真实字节数，不接受估算或历史数字。

两种用法：
  1) 回归门禁（默认）：与本仓库记录的基线比较，增量超过 512 KB 即失败。
     基线随代码演进更新，避免把「尚未达标的绝对值」变成每次构建都红的噪声。
  2) 终态判定：加 --enforce-limit，直接按 26,214,400 判定，供 P7 验收使用。

退出码：0 通过；1 超限或相对基线回退超阈；2 文件缺失或参数错误。
"""
from __future__ import annotations

import argparse
import json
import os
import sys

LIMIT = 26_214_400
REGRESSION_TOLERANCE = 512 * 1024


def file_size(path: str) -> int:
    """返回真实字节数；文件不存在直接退出，不允许用 0 或估算顶替。"""
    if not os.path.isfile(path):
        print(f"错误：找不到产物 {path}", file=sys.stderr)
        sys.exit(2)
    return os.stat(path).st_size


def human(n: int) -> str:
    return f"{n / 1048576:.2f} MiB"


def main() -> int:
    parser = argparse.ArgumentParser(
        description="测量模块 zip 与 App APK 的合计下载量（工作词 7.1 口径）"
    )
    parser.add_argument("--module", required=True, help="Root 模块 zip 路径")
    parser.add_argument("--apk", required=True, help="App APK 路径")
    parser.add_argument("--limit", type=int, default=LIMIT, help=f"硬预算字节数，默认 {LIMIT}")
    parser.add_argument("--enforce-limit", action="store_true", help="按硬预算判定（P7 验收用）")
    parser.add_argument("--baseline", help="基线 JSON 路径；给出时做回归判定")
    parser.add_argument("--update-baseline", help="把本次实测写入该 JSON，并跳过判定")
    parser.add_argument("--json", dest="json_out", help="把结果写入该 JSON")
    args = parser.parse_args()

    module = file_size(args.module)
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

    result = {
        "module": module,
        "apk": apk,
        "total": total,
        "limit": args.limit,
        "gap": gap,
        "within_limit": total <= args.limit,
    }

    failed = []

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

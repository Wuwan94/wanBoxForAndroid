#!/usr/bin/env python3
"""保留项守卫（B 路线终态契约）。

体积优化最容易踩的不是"删多了"，而是"删穿了依赖链"。本脚本把 B 路线定稿后的
契约固化成 CI 断言：

  必须保留
    · with_clash_api           —— 首页仪表（DashboardFragment）的速率/流量/连接数
                                  遥测链路：模块侧 queryClashStats → wanboxctl dashboard
                                  → RootTunService.cbSpeedUpdate → lastSpeedStats。
    · with_quic / with_utls / with_ebpf
                              —— hysteria/tuic/H3-DNS、TLS 指纹、root 分应用 inbound。
    · assets/sing-box/geoip.db.xz + geosite.db.xz
                              —— 离线规则库；模块侧 CheckSnapshot 对 geoip:/geosite:
                                 规则做硬校验，缺了直接 config_resource_missing。
    · core/CoreAssets.kt       —— 瘦管理器没有 gojni，InitCore→extractAssets 不执行，
                                  规则库落地必须由它补上。
    · ConfigBuilder 的 external_controller = 127.0.0.1:9090（遥测入口）
    · TUN stack = "system"     —— 因为 with_gvisor 已裁。

  必须消失（防止被"顺手加回来"）
    · with_gvisor（默认 tags）
    · external_ui = "../files/yacd"、assets/yacd.zip、yacd.version.txt、
      ui/WebviewFragment.kt、ui/LocalYacdDashboard.kt
    · 模块侧对 files/yacd/index.html 的硬校验

用法：python3 tools/check_keep_features.py [--apk <瘦 APK 路径>]
"""
import argparse
import re
import sys
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
failures: list[str] = []
notes: list[str] = []

REQUIRED_TAGS = ("with_clash_api", "with_quic", "with_utls", "with_ebpf")
# with_clash_api 是仪表遥测的唯一来源，裁掉首页仪表就哑了，因此是保留项而非可裁项。
FORBIDDEN_FILES = (
    "app/src/main/assets/yacd.zip",
    "app/src/main/assets/yacd.version.txt",
    "app/src/main/assets/yacd-bootstrap.js",
    "app/src/main/java/io/nekohasekai/sagernet/ui/WebviewFragment.kt",
    "app/src/main/java/io/nekohasekai/sagernet/ui/LocalYacdDashboard.kt",
    "app/src/main/res/menu/yacd_menu.xml",
)
GEO_ASSETS = (
    "app/src/main/assets/sing-box/geoip.db.xz",
    "app/src/main/assets/sing-box/geosite.db.xz",
)


def fail(msg: str) -> None:
    failures.append(msg)


def read(rel: str) -> str:
    path = ROOT / rel
    if not path.is_file():
        fail(f"缺少文件：{rel}")
        return ""
    return path.read_text(encoding="utf-8", errors="replace")


def check_core_tags() -> None:
    script = read("libcore/build.sh")
    match = re.search(r'CORE_TAGS="\$\{WANBOX_CORE_TAGS:-([^}]*)\}"', script)
    if not match:
        fail("libcore/build.sh: 找不到 CORE_TAGS 默认值")
        return
    tags = [t.strip() for t in match.group(1).split(",") if t.strip()]
    for required in REQUIRED_TAGS:
        if required not in tags:
            fail(f"libcore/build.sh: 默认 CORE_TAGS 缺少 {required}")
    if "with_gvisor" in tags:
        fail("libcore/build.sh: 默认 CORE_TAGS 又带回了 with_gvisor（已裁项，TUN 走 system 栈）")
    notes.append("默认 CORE_TAGS = " + ",".join(tags))

    if 'tags="rootmodule,$CORE_TAGS"' not in script:
        fail("libcore/build.sh: rootbox 未复用 CORE_TAGS，模块侧与 APK 侧 tag 会不一致")


def check_yacd_removed() -> None:
    for rel in FORBIDDEN_FILES:
        if (ROOT / rel).exists():
            fail(f"yacd 残留：{rel}（面板已整体移除，不应再出现在仓库里）")
    builder = read("app/src/main/java/io/nekohasekai/sagernet/fmt/ConfigBuilder.kt")
    # 只认真正下发的赋值语句；注释里提到 external_ui 用来解释"为什么不再下发"，是允许的。
    code_only = "\n".join(
        line for line in builder.splitlines() if not line.lstrip().startswith("//")
    )
    if re.search(r'^\s*external_ui\s*=', code_only, re.MULTILINE):
        fail("ConfigBuilder.kt: 仍在配置 external_ui（yacd 已移除，会把静态目录要求带回来）")
    runtime = read("rootmodule/runtime.go")
    if '"files/yacd/index.html"' in runtime:
        fail("rootmodule/runtime.go: 仍在硬校验 files/yacd/index.html，升级后的旧快照会被拒绝")
    notes.append("yacd 面板移除状态：一致")


def check_config_wiring() -> None:
    builder = read("app/src/main/java/io/nekohasekai/sagernet/fmt/ConfigBuilder.kt")
    if 'external_controller = "127.0.0.1:9090"' not in builder:
        fail("ConfigBuilder.kt: 缺少 external_controller 监听，首页仪表拿不到遥测")
    if 'TUN_STACK' not in builder:
        fail("ConfigBuilder.kt: TUN 栈未使用 TUN_STACK 常量")
    if re.search(r'TUN_STACK\s*=\s*"gvisor"', builder):
        fail('ConfigBuilder.kt: TUN_STACK 指回 gvisor，但 with_gvisor 已裁，运行时会起不来')
    notes.append("Clash API / TUN 栈接线：正常")


def check_geo_assets() -> None:
    present = [rel for rel in GEO_ASSETS if (ROOT / rel).is_file()]
    if not present:
        # 离线规则库由 buildScript/lib/assets.sh 在构建前落盘；源码树里允许为空，
        # 但此时必须给出明确提示，避免 CI 静默产出缺规则库的包。
        notes.append("提示：assets/sing-box/*.db.xz 尚未落盘（先跑 buildScript/lib/assets.sh）")
        return
    for rel in GEO_ASSETS:
        if not (ROOT / rel).is_file():
            fail(f"缺少离线规则库：{rel}")
    notes.append(f"离线规则库：{len(present)}/2 已落盘")


def check_asset_bootstrap() -> None:
    assets_kt = ROOT / "app/src/main/java/io/nekohasekai/sagernet/core/CoreAssets.kt"
    if not assets_kt.is_file():
        fail("缺少 core/CoreAssets.kt：瘦管理器将无法落地 geoip/geosite，内核会被拒绝启动")
        return
    body = assets_kt.read_text(encoding="utf-8", errors="replace")
    if "geoip" not in body or "geosite" not in body:
        fail("core/CoreAssets.kt: 没有 geoip/geosite 落地逻辑")
    if "yacd" in body and "已整体移除" not in body:
        fail("core/CoreAssets.kt: 仍在落地 yacd，与移除决定冲突")

    for rel in (
        "app/src/main/java/io/nekohasekai/sagernet/SagerNet.kt",
        "app/src/main/java/io/nekohasekai/sagernet/bg/RootModuleClient.kt",
    ):
        if "CoreAssets.ensure(" not in read(rel):
            fail(f"{rel}: 未调用 CoreAssets.ensure()，瘦版启动时会 config_resource_missing")
    notes.append("CoreAssets 落地链：SagerNet + RootModuleClient 均已挂载")


def check_thin_apk(path: Path) -> None:
    if not path.is_file():
        fail(f"瘦 APK 不存在：{path}")
        return
    with zipfile.ZipFile(path) as zf:
        names = zf.namelist()
    for needle in (
        "assets/sing-box/geoip.db.xz",
        "assets/sing-box/geosite.db.xz",
    ):
        if needle not in names:
            fail(f"瘦 APK 缺少 {needle}：离线规则库不可用")
    for needle in ("assets/yacd.zip", "assets/yacd.version.txt", "assets/yacd-bootstrap.js"):
        if needle in names:
            fail(f"瘦 APK 仍包含 {needle}：yacd 已移除，资源未清干净")
    if "lib/arm64-v8a/libgojni.so" in names:
        fail("瘦 APK 仍包含 libgojni，-PwanboxThin 未生效")
    if any(n.startswith("lib/") for n in names):
        fail("瘦 APK 不应包含任何 native 库，实际：" + ", ".join(n for n in names if n.startswith("lib/")))
    notes.append(f"瘦 APK 校验通过：{path.name}（{path.stat().st_size:,} B）")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--apk", help="可选：对已构建的瘦 APK 做资产断言")
    args = parser.parse_args()

    check_core_tags()
    check_yacd_removed()
    check_config_wiring()
    check_geo_assets()
    check_asset_bootstrap()
    if args.apk:
        check_thin_apk(Path(args.apk))

    for note in notes:
        print("  · " + note)
    if failures:
        print("\n保留项检查失败：")
        for item in failures:
            print("  ✗ " + item)
        return 1
    print("\n保留项检查通过：首页仪表遥测链 + 离线规则库 + yacd 已移除")
    return 0


if __name__ == "__main__":
    sys.exit(main())

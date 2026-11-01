#!/usr/bin/env python3
# 用法：在仓库根目录执行 python3 tools/measure_b_route_size.py
"""从已发布产物重建「B 路线」交付物，用于体积实测（流式读取，内存占用 <10MB）。

输入全部是真实发布物（v3.0.10-preview.26），只把 B 路线构建产生的差异逐项应用：
  1) 移除 lib/arm64-v8a/libgojni.so（瘦管理器不再携带内嵌核心）
  2) classes.dex 由 Stored 改为 Deflate（AGP useLegacyPackaging 修复）
  3) 启动图 PNG -> WebP（本仓库已转换，按真实字节替换；APK 内 res 名被混淆，
     因此按「像素尺寸 + 原始字节数最近」精确配对）
  4) 模块 zip 用 pack.py 的 level-9 重新打包
产物写入 .workbuddy/tmp/b_route/。
"""
import os
import struct
import zipfile

SRC = "release-artifacts/wanBoxForAndroid-3.0.10-preview.26-arm64-v8a-release.apk"
MOD = "release-artifacts/wanbox-root-module-arm64-v8a.zip"
OUT = ".workbuddy/tmp/b_route"
RES = "app/src/main/res"
BACKUP = ".workbuddy/tmp/res_backup"
MIB = 1048576

os.makedirs(OUT, exist_ok=True)


def png_dims(b):
    if b[:8] == b"\x89PNG\r\n\x1a\n":
        return struct.unpack(">II", b[16:24])
    return None


SOURCES = [
    ("drawable-nodpi/wanbox_launcher_art.png", "drawable-nodpi/wanbox_launcher_art.webp"),
    ("drawable/ic_launcher_foreground.png", "drawable/ic_launcher_foreground.webp"),
    ("drawable/ic_launcher_monochrome.png", "drawable/ic_launcher_monochrome.webp"),
]
targets = {}
for src_rel, webp_rel in SOURCES:
    data = open(os.path.join(BACKUP, src_rel), "rb").read()
    webp_path = os.path.join(RES, webp_rel)
    if not os.path.exists(webp_path):
        raise SystemExit(f"缺少已转换的 WebP: {webp_path}")
    targets.setdefault(png_dims(data), []).append((len(data), open(webp_path, "rb").read()))

thin = os.path.join(OUT, "wanbox-thin-manager.apk")
with zipfile.ZipFile(SRC) as zin:
    entries = zin.infolist()
    # 第一遍：为每个目标图找「同尺寸 + 字节数最接近」的 res 条目
    drop = {}
    for t_dims, cands in targets.items():
        for i in entries:
            if i.filename in drop or not i.filename.endswith(".png"):
                continue
            if png_dims(zin.read(i.filename)) != t_dims:
                continue
            best = min(cands, key=lambda c: abs(i.file_size - c[0]))
            drop[i.filename] = best[1]

    png_before = sum(zin.getinfo(k).file_size for k in drop)
    webp_after = sum(len(v) for v in drop.values())

    with zipfile.ZipFile(thin, "w") as zout:
        for i in entries:
            name = i.filename
            if name == "lib/arm64-v8a/libgojni.so" or name in drop:
                continue
            if name == "classes.dex" or i.compress_type != zipfile.ZIP_STORED:
                zout.writestr(name, zin.read(name), compress_type=zipfile.ZIP_DEFLATED, compresslevel=9)
            else:
                zout.writestr(name, zin.read(name), compress_type=zipfile.ZIP_STORED)
        for idx, data in enumerate(drop.values()):
            zout.writestr(f"res/ic_{idx}.webp", data, compress_type=zipfile.ZIP_STORED)

mod_out = os.path.join(OUT, "wanbox-root-module-arm64-v8a.zip")
with zipfile.ZipFile(MOD) as zin, zipfile.ZipFile(mod_out, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as zout:
    for i in zin.infolist():
        zout.writestr(i.filename, zin.read(i.filename))

bundle = os.path.join(OUT, "wanbox-bundle-thin-arm64-v8a.zip")
with zipfile.ZipFile(mod_out) as zin, zipfile.ZipFile(bundle, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as zout:
    for i in zin.infolist():
        zout.writestr(i, zin.read(i.filename))
    zout.write(thin, "manager.apk")

sz = os.path.getsize
base, t, m, b = sz(SRC), sz(thin), sz(mod_out), sz(bundle)
print(f"图标配对: {len(drop)} 个 res 条目 -> {len(drop)} 个 WebP")
print(f"  PNG {png_before:,} B -> WebP {webp_after:,} B  (省 {(png_before-webp_after)/MIB:.2f} MiB)")
print(f"基线 APK (preview.26)     {base:>12,} B  {base/MIB:7.2f} MiB")
print(f"瘦管理器 APK              {t:>12,} B  {t/MIB:7.2f} MiB   ({(t-base)/MIB:+.2f})")
print(f"模块 zip (deflate-9)      {m:>12,} B  {m/MIB:7.2f} MiB")
print(f"两附件合计                {t+m:>12,} B  {(t+m)/MIB:7.2f} MiB")
print(f"合体包 (模块+APK)         {b:>12,} B  {b/MIB:7.2f} MiB   <= GitHub 显示口径")
print(f"26 MiB 预算 = 27,262,976 B，余量 {(27_262_976-b)/MIB:.2f} MiB")

#!/bin/bash
# @author 雾晚 (size-budget)
# 体积账本一键实测脚本：APK + root 模块 zip 合计（对照 26MB 预算口径）。
# 前置：需要可访问 proxy.golang.org / maven.google.com / services.gradle.org 的网络，
#       以及 ~/tools/{jdk17,go-sdk}、~/Android/Sdk(platforms;35, build-tools;35.0.0, ndk;25.0.8775105)、
#       ~/go/bin/{gomobile-matsuri,gobind-matsuri}（libcore/init.sh 已执行）。
#       Gradle release 构建需要签名：设置 WANBOX_KEYSTORE_FILE / WANBOX_KEYSTORE_PASSWORD
#       或在 local.properties 提供（仅供本地实测，不影响发布签名配置）。
# 用法：./buildScript/size-verify.sh [--tags-matrix | --b-route]
#   默认          S1 实测（双核心架构，当前默认 tags）
#   --tags-matrix 额外做 gvisor 裁剪对照（A 路线 S2 数据）
#   --b-route     B 路线实测：瘦管理器 APK（-PwanboxThin=true）+ 模块 zip 及合体包
set -euo pipefail
cd "$(dirname "$0")/.."
ROOT=$(pwd)
OUT="$ROOT/.workbuddy/tmp/size-measure"
mkdir -p "$OUT"

export JAVA_HOME="${JAVA_HOME:-$HOME/tools/jdk17}"
export PATH="$JAVA_HOME/bin:$HOME/tools/go-sdk/bin:$HOME/go/bin:$PATH"
source ./buildScript/init/env_ndk.sh

log() { printf '\n===== %s =====\n' "$*"; }
sz()  { du -b "$1" | awk -v n="$2" '{printf "%-46s %12d B  %8.2f MiB\n", n, $1, $1/1048576}'; }

############################################
# 1) Go 核心：libcore.aar（默认 tags）
############################################
log "build libcore.aar (default tags)"
( cd libcore && ./build.sh )            # 产出 libcore.aar 并安装到 app/libs
sz libcore/libcore.aar "libcore.aar (default tags)"
unzip -o -q libcore/libcore.aar jni/arm64-v8a/libgojni.so -d "$OUT/def"
sz "$OUT/def/jni/arm64-v8a/libgojni.so" "libgojni.so raw (default tags)"
gzip -9 -c "$OUT/def/jni/arm64-v8a/libgojni.so" | wc -c | awk '{printf "%-46s %12d B  %8.2f MiB (deflate 近似)\n", "libgojni.so ~apk-compressed", $1, $1/1048576}'

############################################
# 2) 模块 zip（默认 tags）
############################################
log "build root module zip (default tags)"
( cd rootmodule && ./build.sh )         # 依赖 app/executableSo/arm64-v8a/librootbox.so（build.sh 第1步已产出）
sz rootmodule/build/wanbox-root-module-arm64-v8a.zip "module zip (default tags)"

############################################
# 3) APK（当前工作区改动已含 dex 压缩 + WebP 图标）
############################################
log "gradle assemblePreviewRelease"
./gradlew :app:assemblePreviewRelease --no-daemon || {
  echo ">> Gradle 构建失败：请检查签名环境变量与网络（首次需拉取 Gradle 发行版与全部依赖）"; exit 1; }
APK=$(ls -t app/build/outputs/apk/preview/release/*.apk | head -1)
sz "$APK" "APK (preview release, arm64)"

############################################
# 4) 合计
############################################
log "S1 ledger (current tags)"
python3 - "$APK" rootmodule/build/wanbox-root-module-arm64-v8a.zip <<'PY'
import sys, os
a, m = (os.path.getsize(p) for p in sys.argv[1:3])
t = a + m
print(f"APK        {a:>12,} B  {a/1048576:8.2f} MiB")
print(f"module zip {m:>12,} B  {m/1048576:8.2f} MiB")
print(f"TOTAL      {t:>12,} B  {t/1048576:8.2f} MiB   (budget 26.00 MiB)")
PY

############################################
# 5) 可选：gvisor 裁剪对照（S2 数据）
############################################
if [ "${1:-}" = "--tags-matrix" ]; then
  log "build libcore.aar (no-gvisor tags, measurement only)"
  ( cd libcore && WANBOX_CORE_TAGS='with_conntrack,with_quic,with_wireguard,with_utls,with_clash_api,with_ebpf' ./build.sh )
  unzip -o -q libcore/libcore.aar jni/arm64-v8a/libgojni.so -d "$OUT/nogvisor"
  sz "$OUT/nogvisor/jni/arm64-v8a/libgojni.so" "libgojni.so raw (no gvisor)"
  gzip -9 -c "$OUT/nogvisor/jni/arm64-v8a/libgojni.so" | wc -c | awk '{printf "%-46s %12d B  %8.2f MiB (deflate 近似)\n", "libgojni.so ~apk-compressed", $1, $1/1048576}'
  log "build root module zip (no-gvisor tags)"
  ( cd rootmodule && CORE_TAGS_OVERRIDE='with_conntrack,with_quic,with_wireguard,with_utls,with_clash_api,with_ebpf' bash -c '
      # rootbox 由 libcore/build.sh 产出；上一行已用 no-gvisor tags 重编并安装到 executableSo
      ./build.sh' )
  sz rootmodule/build/wanbox-root-module-arm64-v8a.zip "module zip (no gvisor)"
  log "restore default tags build"
  ( cd libcore && ./build.sh )
  echo ">> 已恢复默认 tags 产物（app/libs/libcore.aar + executableSo/librootbox.so）"
  echo ">> 注意：no-gvisor APK 需同时应用 ConfigBuilder stack=\"system\" 配套改动后才能打包实测。"
fi

############################################
# 6) B 路线：瘦管理器（核心只随模块分发）
############################################
if [ "${1:-}" = "--b-route" ]; then
  log "build thin manager APK (-PwanboxThin=true, no libgojni.so)"
  ./gradlew :app:assemblePreviewRelease -PwanboxThin=true --no-daemon || {
    echo ">> 瘦管理器构建失败：检查签名环境变量与网络"; exit 1; }
  THIN=$(ls -t app/build/outputs/apk/preview/release/*.apk | head -1)
  sz "$THIN" "APK (thin manager, arm64)"
  if unzip -l "$THIN" | grep -q 'lib/arm64-v8a/libgojni.so'; then
    echo ">> 警告：瘦 APK 仍包含 libgojni.so，请检查 -PwanboxThin 是否生效"; fi

  # 保留项守卫：yacd 仪表盘 / with_clash_api / CoreAssets 资产落地链
  log "retention guard (dashboard + clash_api + asset bootstrap)"
  python3 tools/check_core_gate.py || exit 1
  python3 tools/check_keep_features.py --apk "$THIN" || exit 1

  log "single bundle: module zip + thin APK (NetProxy 同构)"
  python3 rootmodule/bundle_manager.py \
    --source rootmodule/build/wanbox-root-module-arm64-v8a.zip \
    --apk "$THIN" --output "$OUT/bundle-thin.zip"
  sz "$OUT/bundle-thin.zip" "bundle (module + thin APK)"

  log "B-route ledger"
  python3 - "$THIN" rootmodule/build/wanbox-root-module-arm64-v8a.zip "$OUT/bundle-thin.zip" <<'PY'
import sys, os
thin, mod, bundle = (os.path.getsize(p) for p in sys.argv[1:4])
print(f"thin APK            {thin:>12,} B  {thin/1048576:8.2f} MiB")
print(f"module zip          {mod:>12,} B  {mod/1048576:8.2f} MiB")
print(f"APK+zip (两附件)     {thin+mod:>12,} B  {(thin+mod)/1048576:8.2f} MiB")
print(f"单一合体包           {bundle:>12,} B  {bundle/1048576:8.2f} MiB   <= GitHub 显示口径")
PY

  log "optional: rootbox.xz payload estimate (installer must decode xz)"
  python3 - rootmodule/build/wanbox-root-module-arm64-v8a.zip <<'PY'
import sys, zipfile, subprocess, tempfile, os
src = sys.argv[1]
with zipfile.ZipFile(src) as z:
    info = z.getinfo('bin/rootbox')
    data = z.read('bin/rootbox')
with tempfile.NamedTemporaryFile(delete=False) as f:
    f.write(data); tmp = f.name
try:
    xz_size = len(subprocess.run(['xz', '-9', '-T0', '-c', tmp], capture_output=True, check=True).stdout)
finally:
    os.unlink(tmp)
delta = info.compress_size - xz_size
print(f"rootbox deflate9 {info.compress_size:>12,} B")
print(f"rootbox xz -9    {xz_size:>12,} B   (delta {delta:+,} B = {delta/1048576:+.2f} MiB)")
PY
fi

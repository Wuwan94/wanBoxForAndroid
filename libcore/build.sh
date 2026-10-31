#!/bin/bash

source ./env_java.sh || true
source ../buildScript/init/env_ndk.sh

BUILD=".build"

rm -rf $BUILD/android \
  $BUILD/java \
  $BUILD/javac-output \
  $BUILD/src

if [ -z "$GOPATH" ]; then
  GOPATH=$(go env GOPATH)
fi

# 在编译时锁定singbox侧依赖，带重试防御 sum.golang.org 瞬态网络抖动
for i in 1 2 3; do
  go mod tidy && break || {
    if [ "$i" -eq 3 ]; then
      echo ">> ERROR: go mod tidy failed after 3 attempts" >&2
      exit 1
    fi
    echo ">> go mod tidy transient network error, retrying ($i/3)..." >&2
    sleep 3
  }
done

# 官方 sing-box 的 constant.Version 默认为 "unknown"，需经 ldflags -X 在链接期注入；
# 版本号取自 nb4a.properties 的 SINGBOX_VERSION（与 get_source.sh 克隆的源码版本一致）
SINGBOX_VERSION=$(grep '^SINGBOX_VERSION=' ../nb4a.properties | head -n1 | cut -d'=' -f2 | tr -d '\r[:space:]')
if [ -z "$SINGBOX_VERSION" ]; then
  echo ">> ERROR: SINGBOX_VERSION not found in nb4a.properties" >&2
  exit 1
fi

export GOBIND=gobind-matsuri

# @author 雾晚 (B 路线 · size-budget): 默认已裁掉 with_gvisor，可用环境变量整体覆盖：
#   WANBOX_CORE_TAGS='with_conntrack,with_gvisor,...' ./build.sh   # 恢复 gVisor
#
# 裁剪依据是实测（.gopclntab 逐函数切字节 + 真实 deflate-9，见 docs/apk-size-report-20261028.md），
# 不是估算：
#   - with_gvisor     **已裁**。实测每端 .text 1.31MiB / 压缩后 1.04MiB。TUN 改走 sing-box 的
#                     system 栈（ConfigBuilder 显式 stack="system"），TCP/UDP 与 auto_route 全部保留，
#                     仅失去 gVisor 用户态栈特有的行为（如 gVisor 自己的 UDP NAT 会话时序）。
#                     注：root 模式走 ebpf inbound，本就不经过 gVisor，此裁剪对 root 渠道零影响。
#   - with_ebpf       不可裁：root 模式的分应用代理 inbound 依赖它（实测 0.98MiB/端，但动了就断功能）。
#   - with_quic       不可裁：hysteria/hysteria2/tuic/DNS-over-H3。
#   - with_utls       不可裁：TLS 指纹伪装（订阅方 UA/指纹依赖）。
#   - with_clash_api  保留：仪表盘、流量统计、节点切换（实测仅 0.06MiB/端）。
#   - with_conntrack / with_wireguard  保留：连接跟踪与 WireGuard endpoint。
CORE_TAGS="${WANBOX_CORE_TAGS:-with_conntrack,with_quic,with_wireguard,with_utls,with_clash_api,with_ebpf}"

# @author 雾晚 (B 路线 · 构建提速): 发布只出 arm64-v8a（见 release-artifacts 的
# -arm64-v8a-release.apk），本地量体积时没必要编另外三个 ABI。
# CI 不设该变量时保持原行为（gomobile 默认四架构 + 四个 rootbox）。
BIND_TARGET="$WANBOX_BIND_TARGET"
BIND_TARGET_ARG=""
if [ -n "$BIND_TARGET" ]; then
  BIND_TARGET_ARG="-target=$BIND_TARGET"
fi

"$GOPATH"/bin/gomobile-matsuri bind -v $BIND_TARGET_ARG -androidapi 21 -cache "$(realpath $BUILD)" -trimpath -ldflags="-s -w -X github.com/sagernet/sing-box/constant.Version=$SINGBOX_VERSION" -tags="$CORE_TAGS" . || exit 1
rm -r libcore-sources.jar

proj=../app/libs
mkdir -p $proj
cp -f libcore.aar $proj
echo ">> install $(realpath $proj)/libcore.aar"

# @author 雾晚: package a separate PIE executable for genuine root TUN.
NDK_BIN="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin"
# 显式指定 WANBOX_ROOTBOX_ABIS 可只编需要的架构（如 arm64-v8a）；不设则四架构全编。
ROOTBOX_ABIS="${WANBOX_ROOTBOX_ABIS:-armeabi-v7a arm64-v8a x86 x86_64}"
for target in "armeabi-v7a:arm:armv7a-linux-androideabi21-clang" \
              "arm64-v8a:arm64:aarch64-linux-android21-clang" \
              "x86:386:i686-linux-android21-clang" \
              "x86_64:amd64:x86_64-linux-android21-clang"; do
  IFS=: read -r abi arch compiler <<< "$target"
  case " $ROOTBOX_ABIS " in
    *" $abi "*) ;;
    *) continue ;;
  esac
  output="../app/executableSo/$abi/librootbox.so"
  mkdir -p "$(dirname "$output")"
  CGO_ENABLED=1 GOOS=android GOARCH="$arch" CC="$NDK_BIN/$compiler" \
    go build -buildmode=pie -trimpath \
      -ldflags="-s -w -X github.com/sagernet/sing-box/constant.Version=$SINGBOX_VERSION" \
      -tags="rootmodule,$CORE_TAGS" \
      -o "$output" ./cmd/rootbox || exit 1
  test -s "$output" || exit 1
done

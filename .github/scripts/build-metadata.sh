#!/usr/bin/env sh
# 文件: .github/scripts/build-metadata.sh
# 功能: 从当前提交生成模块与管理器共用的构建元信息
# 用法: 在仓库根目录执行 sh .github/scripts/build-metadata.sh
# 依赖: POSIX sh、git、sed、tr、cut

set -eu

test "$(git rev-parse --is-shallow-repository)" = false
COMMIT_COUNT="$(git rev-list --count HEAD)"
VERSION="$(sed -n 's/^version=//p' src/module/module.prop | tr -d '\r')"
test -n "$VERSION"
SHORT_SHA="$(git rev-parse HEAD | cut -c1-7)"

# 历史 APK versionCode 峰值：preview.13 = 1875（旧公式 VERSION_CODE×5，模块 code 375）。
# 新架构改用提交数，必须抬到该峰值之上，否则 pm install -r 会因版本降级失败。
# 与 src/android/app/build.gradle.kts 的 legacyVersionCodeFloor 必须保持一致。
LEGACY_VERSION_CODE_FLOOR=1875
VERSION_CODE=$((LEGACY_VERSION_CODE_FLOOR + COMMIT_COUNT))

printf 'version=%s\nmanager_version=%s\nshort_sha=%s\ncommit_count=%s\nversion_code=%s\n' \
  "$VERSION" "${VERSION#v}" "$SHORT_SHA" "$COMMIT_COUNT" "$VERSION_CODE"
printf 'module_name=wanBox_%s_%s.zip\n' "$VERSION" "$COMMIT_COUNT"

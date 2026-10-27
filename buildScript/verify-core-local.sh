#!/usr/bin/env bash
# @author 雾晚
# SPDX-License-Identifier: GPL-3.0-or-later
#
# 本地构建模拟：在没有 Android SDK / Go 工具链的机器上验证 core 三个纯 JVM 模块。
#
# 做：编译 core、编译并运行 JUnit4 用例、静态检查（无平台引用 / GPL 头 / 依赖方向 / settings）。
# 不做：:app 与 :adapter-android 的 Android 编译、Room schema、真机行为——这些交给 CI。
#
# 用法：
#   WANBOX_DEPS=<目录> [JAVA_HOME=<jdk17>] [KOTLIN_LIB=<目录>] bash buildScript/verify-core-local.sh
#
# WANBOX_DEPS 需含：kotlin-stdlib*.jar、coroutines-*.jar 或 kotlinx-coroutines-core-jvm-*.jar、junit4.jar / junit-4*.jar、hamcrest*.jar
# KOTLIN_LIB 需含 kotlin-compiler-embeddable.jar（PATH 里有 kotlinc 时可省略）

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# Git Bash 的 $PWD 是 /c/... 形式，java.exe 不认；归一化成 C:/... 再传给编译器
if command -v cygpath >/dev/null 2>&1; then
  ROOT="$(cygpath -m "$ROOT")"
fi
cd "$ROOT" || exit 2

FAIL=0
step() { printf '\n== %s ==\n' "$1"; }
ok()   { printf '  PASS  %s\n' "$1"; }
bad()  { printf '  FAIL  %s\n' "$1"; FAIL=1; }

DEPS="${WANBOX_DEPS:-}"
if [ -z "$DEPS" ]; then
  echo "请设置 WANBOX_DEPS（见脚本头部）"
  exit 2
fi

JAVA_BIN="java"
if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
  JAVA_BIN="$JAVA_HOME/bin/java"
fi

first_jar() {
  for pattern in "$@"; do
    local hit
    hit=$(ls -1 "$DEPS"/$pattern 2>/dev/null | head -n 1)
    if [ -n "$hit" ]; then printf '%s' "$hit"; return 0; fi
  done
  return 1
}

STDLIB=$(first_jar 'kotlin-stdlib.jar' 'kotlin-stdlib-*.jar') || true
COROUTINES=$(first_jar 'coroutines-*.jar' 'kotlinx-coroutines-core-jvm-*.jar') || true
JUNIT=$(first_jar 'junit4.jar' 'junit-4*.jar') || true
HAMCREST=$(first_jar 'hamcrest*.jar') || true

for item in "kotlin-stdlib:$STDLIB" "coroutines:$COROUTINES" "junit4:$JUNIT" "hamcrest:$HAMCREST"; do
  name=${item%%:*}
  path=${item#*:}
  if [ -z "$path" ]; then echo "缺少依赖 $name（目录 $DEPS）"; exit 2; fi
done

OUT="$ROOT/build/local-core"
rm -rf "$OUT"
mkdir -p "$OUT/main" "$OUT/test"

compile() {
  local out=$1
  local cp=$2
  shift 2
  local log="$OUT/compile.log"
  local rc=0
  if command -v kotlinc >/dev/null 2>&1; then
    kotlinc "$@" -no-stdlib -no-reflect -jvm-target 17 -classpath "$cp" -d "$out" >"$log" 2>&1 || rc=$?
  else
    local lib="${KOTLIN_LIB:-}"
    if [ -z "$lib" ]; then
      echo "未找到 kotlinc，且未设置 KOTLIN_LIB"
      return 127
    fi
    local compiler_cp="$lib/kotlin-compiler-embeddable.jar;$lib/kotlin-stdlib.jar;$lib/kotlin-script-runtime.jar;$lib/kotlin-reflect.jar;$lib/kotlin-daemon-embeddable.jar;$lib/trove4j.jar;$lib/annotations.jar;$COROUTINES"
    "$JAVA_BIN" -cp "$compiler_cp" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
      -no-stdlib -no-reflect -jvm-target 17 -classpath "$cp" -d "$out" "$@" >"$log" 2>&1 || rc=$?
  fi
  if [ "$rc" -ne 0 ]; then
    grep -E "error:|exception:" "$log" | head -15
    tail -4 "$log" | sed 's/^/    /'
  fi
  return "$rc"
}

step "1/5 编译 core 三个模块（对应 compileKotlin）"
compile "$OUT/main" "$STDLIB;$COROUTINES" \
  core/contracts/src/main/kotlin core/testing/src/main/kotlin core/domain/src/main/kotlin
MAIN_RC=$?
MAIN_CLASSES=$(find "$OUT/main" -name '*.class' 2>/dev/null | wc -l | tr -d ' ')
if [ "$MAIN_RC" -eq 0 ] && [ "$MAIN_CLASSES" -gt 0 ]; then
  ok "编译产出 $MAIN_CLASSES 个 class"
else
  bad "编译失败（rc=$MAIN_RC）"
fi

step "2/5 编译测试（对应 compileTestKotlin）"
compile "$OUT/test" "$STDLIB;$COROUTINES;$JUNIT;$OUT/main" core/domain/src/test/kotlin
TEST_RC=$?
TEST_CLASSES=$(find "$OUT/test" -name '*.class' 2>/dev/null | wc -l | tr -d ' ')
if [ "$TEST_RC" -eq 0 ] && [ "$TEST_CLASSES" -gt 0 ]; then
  ok "测试产出 $TEST_CLASSES 个 class"
else
  bad "测试编译失败（rc=$TEST_RC）"
fi

step "3/5 运行用例（对应 :core-domain:test）"
CLASSES=$(cd "$OUT/test" && find . -name '*Test.class' | sed 's|^\./||; s|/|.|g; s|\.class$||' | tr '\n' ' ')
if [ -n "$CLASSES" ]; then
  RESULT=$("$JAVA_BIN" -Dfile.encoding=UTF-8 \
    -cp "$OUT/main;$OUT/test;$STDLIB;$COROUTINES;$JUNIT;$HAMCREST" \
    org.junit.runner.JUnitCore $CLASSES 2>&1)
  printf '%s\n' "$RESULT" | grep -E "^(OK|Tests run|FAILURES)" | sed 's/^/  /'
  if printf '%s\n' "$RESULT" | grep -q '^OK'; then
    ok "全部用例通过"
  else
    bad "存在失败用例"
  fi
else
  bad "没有找到测试类"
fi

step "4/5 静态检查：core 不得依赖平台"
PLATFORM_HITS=$(grep -rInE "^import (android\.|androidx\.|io\.nekohasekai\.sagernet\.(ui|bg|database|fmt))" core 2>/dev/null || true)
if [ -z "$PLATFORM_HITS" ]; then
  ok "core 无 Android / 历史包引用"
else
  bad "core 出现平台引用"
  printf '%s\n' "$PLATFORM_HITS" | head -10
fi

NO_HEADER=""
while IFS= read -r file; do
  if ! head -n 3 "$file" | grep -q 'SPDX-License-Identifier: GPL-3.0-or-later'; then
    NO_HEADER="$NO_HEADER $file"
  fi
done < <(find core -name '*.kt')
if [ -z "$NO_HEADER" ]; then
  ok "core 文件头合规"
else
  bad "缺少 GPL 头：$NO_HEADER"
fi

step "5/5 静态检查：模块依赖方向"
for module in core-contracts core-domain core-testing; do
  if grep -q "\":$module\"" settings.gradle.kts; then
    ok "settings 已 include $module"
  else
    bad "settings 缺少 $module"
  fi
done

grep -q 'api(project(":core-contracts"))' core/domain/build.gradle.kts \
  && ok "domain -> contracts" || bad "domain 未依赖 contracts"
grep -q 'api(project(":core-contracts"))' core/testing/build.gradle.kts \
  && ok "testing -> contracts" || bad "testing 未依赖 contracts"
if grep -q 'project(":core-domain")' core/contracts/build.gradle.kts 2>/dev/null; then
  bad "contracts 反向依赖 domain"
else
  ok "contracts 无反向依赖"
fi

printf '\n'
if [ "$FAIL" -eq 0 ]; then
  echo "LOCAL BUILD: PASS（:app / :adapter-android 仍需 CI 的 Android 构建验证）"
else
  echo "LOCAL BUILD: FAIL"
fi
exit "$FAIL"

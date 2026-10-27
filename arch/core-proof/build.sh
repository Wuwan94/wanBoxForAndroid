#!/usr/bin/env bash
# core-proof 一键构建与验收（不需要 Gradle / Android SDK）
# 用法：bash build.sh
# 环境：便携 JDK17 + Maven Central 的 kotlin-compiler-embeddable（见 README 依赖清单）
set -euo pipefail
cd "$(dirname "$0")"

# Git Bash 的 $HOME 是 /c/Users/xxx 形式，java.exe 只认 Windows 路径，先归一化
win_path() { if command -v cygpath >/dev/null 2>&1; then cygpath -m "$1" 2>/dev/null; else printf '%s' "$1"; fi; }
if [ -n "${WANBOX_BIN:-}" ]; then BIN="$WANBOX_BIN"; else BIN="$(win_path "${HOME:-.}")/.workbuddy/binaries"; fi
JAVA="$BIN/jdk17/jdk-17.0.13+11/bin/java.exe"
[ -f "$JAVA" ] || JAVA="$(win_path "${JAVA_HOME:-}")/bin/java.exe"
M="$BIN/m2"

for f in kotlin-compiler-embeddable kotlin-stdlib kotlin-script-runtime kotlin-reflect \
         kotlin-daemon-embeddable trove4j annotations coroutines junit-console; do
  [ -f "$M/$f.jar" ] || { echo "缺少 $M/$f.jar，见 README 的依赖清单"; exit 2; }
done

COMPILER_CP="$M/kotlin-compiler-embeddable.jar;$M/kotlin-stdlib.jar;$M/kotlin-script-runtime.jar;$M/kotlin-reflect.jar;$M/kotlin-daemon-embeddable.jar;$M/trove4j.jar;$M/annotations.jar;$M/coroutines.jar"
LIBS="$M/kotlin-stdlib.jar;$M/coroutines.jar"
RUNTIME_CP="out/main;$LIBS"

MAIN_SRC="core/contracts/src/main/kotlin core/domain/src/main/kotlin adapters/memory/src/main/kotlin cli/src/main/kotlin"
TEST_SRC="tests/src/kotlin"

# Git Bash/MSYS 会把 -cp 里的 ; 分隔当成路径列表再改写一次，必须关掉
export MSYS2_ARG_CONV_EXCL='*'

echo "== 1/5 编译 contracts + domain + adapters:memory + cli =="
rm -rf out && mkdir -p out/main out/test
"$JAVA" -cp "$COMPILER_CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -no-stdlib -no-reflect -jvm-target 17 \
  -classpath "$LIBS" \
  -d out/main $MAIN_SRC
echo "   class 数：$(find out/main -name '*.class' | wc -l | tr -d ' ')"

echo "== 2/5 编译测试 =="
"$JAVA" -cp "$COMPILER_CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -no-stdlib -no-reflect -jvm-target 17 \
  -classpath "$LIBS;$M/junit-console.jar;out/main" \
  -d out/test $TEST_SRC
echo "   test class 数：$(find out/test -name '*.class' | wc -l | tr -d ' ')"

echo "== 3/5 跑用例测试 =="
"$JAVA" -Dfile.encoding=UTF-8 -cp "out/main;out/test;$LIBS;$M/junit-console.jar" \
  org.junit.platform.console.ConsoleLauncher --scan-class-path --details=summary --disable-ansi-colors 2>&1 \
  | grep -E "tests (found|successful|failed)"

echo "== 4/5 跑 CLI（第二个入口） =="
"$JAVA" -Dfile.encoding=UTF-8 -cp "$RUNTIME_CP" io.wanbox.cli.MainKt | tail -3

echo "== 5/5 架构守卫 =="
bash ../wanbox/arch-check.sh . | tail -6

echo
echo "BUILD: OK"

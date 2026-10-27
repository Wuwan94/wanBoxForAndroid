#!/usr/bin/env bash
# 架构守卫：依赖方向、跨层引用、循环依赖、用例可追溯。可在 CI 与本地跑。
# 用法：bash arch/wanbox/arch-check.sh <repo-root>
#   <repo-root> 可以是 wanBox 仓库根，也可以是 arch/core-proof
set -u

ROOT="${1:-.}"
cd "$ROOT" || { echo "无法进入 $ROOT"; exit 2; }

fail=0
pass() { printf '  PASS  %s\n' "$1"; }
bad()  { printf '  FAIL  %s\n' "$1"; fail=1; }
info() { printf '  INFO  %s\n' "$1"; }

# 找 python：Git Bash 里通常是 python（Windows 版），CI 里可能是 python3
python_bin=""
for c in python3 python; do command -v "$c" >/dev/null 2>&1 && python_bin="$c" && break; done
if [ -z "$python_bin" ] && [ -x "${WANBOX_PY:-}" ]; then python_bin="$WANBOX_PY"; fi

echo "== 1) core 不得依赖 Android / UI =="
if [ -d core/contracts ] || [ -d core/domain ]; then
  hits=$(grep -rInE "^\s*import (android|androidx|io\.nekohasekai\.sagernet\.(ui|bg|database))" core/ 2>/dev/null | grep -v "//" || true)
  [ -z "$hits" ] && pass "core 无 android/androidx/ui/bg/database 引用" || { bad "core 出现平台引用"; echo "$hits" | head -10; }

  # 平台类型直接出现在签名里也要拦：Context / Uri / Bitmap / Parcelable
  typehits=$(grep -rInE "\b(Context|Uri|Bitmap|Parcelable)\b" core/ 2>/dev/null | grep -vE "^\S+:[0-9]+:\s*(//|\*)" || true)
  [ -z "$typehits" ] && pass "core 未出现 Context/Uri/Bitmap/Parcelable 类型" || { bad "core 出现平台类型"; echo "$typehits" | head -10; }
else
  printf '  SKIP  core/ 尚未建立\n'
fi

echo "== 2) adapters / cli / tests 不得引用 Android 与历史包 =="
for d in adapters cli tests; do
  [ -d "$d" ] || continue
  hits=$(grep -rInE "^\s*import (android|androidx|io\.nekohasekai)" "$d"/ 2>/dev/null || true)
  [ -z "$hits" ] && pass "$d 无平台/历史包引用" || { bad "$d 出现平台或历史包引用"; echo "$hits" | head -6; }
done

echo "== 3) ui 不得直接引用数据层与服务层 =="
if [ -d ui ]; then
  hits=$(grep -rInE "^\s*import io\.nekohasekai\.sagernet\.(database|bg|group|fmt)" ui/ 2>/dev/null || true)
  [ -z "$hits" ] && pass "ui 只依赖 contracts" || { bad "ui 直接引用 database/bg/group/fmt"; echo "$hits" | head -10; }
else
  printf '  SKIP  ui/ 尚未建立（现为 app/src/main/java/.../ui）\n'
fi

echo "== 4) 模块依赖方向 + 循环依赖（解析 build.gradle.kts） =="
if [ -n "$python_bin" ]; then
  "$python_bin" - <<'PY'
import glob, os, re, sys

ALLOWED = {
    ':core:contracts': set(),
    ':core:domain': {':core:contracts'},
    ':adapters:memory': {':core:contracts'},
    ':ui': {':core:contracts'},
    ':cli': {':core:domain', ':adapters:memory'},
    ':tests': {':core:domain', ':adapters:memory'},
}

DEP_RE = re.compile(r'(?:implementation|api|compileOnly|runtimeOnly|testImplementation)\s*\(\s*project\(\s*"([^"]+)"')
edges, violations = {}, []

for f in sorted(glob.glob('**/build.gradle.kts', recursive=True)):
    norm = f.replace('\\', '/')
    rel = norm.rsplit('/build.gradle.kts', 1)[0]
    if not rel or rel == norm:
        continue  # 根工程，不是子模块
    name = ':' + rel.replace('/', ':')
    txt = open(f, encoding='utf-8', errors='ignore').read()
    deps = set(DEP_RE.findall(txt))
    edges[name] = deps
    allowed = ALLOWED.get(name)
    if allowed is None:
        print('  INFO  未登记模块 %s（依赖：%s）' % (name, ', '.join(sorted(deps)) or '无'))
        continue
    for d in sorted(deps - allowed):
        violations.append('%s -> %s（不在允许方向内）' % (name, d))

if violations:
    print('  FAIL  依赖方向违规：')
    for v in violations:
        print('        ', v)
    sys.exit(1)

# 拓扑检测环
state, cycles = {}, []
def dfs(n, stack):
    state[n] = 1
    for m in edges.get(n, ()):
        if state.get(m) == 1:
            cycles.append(' -> '.join(stack + [n, m]))
        elif state.get(m, 0) == 0:
            dfs(m, stack + [n])
    state[n] = 2

for n in list(edges):
    if state.get(n, 0) == 0:
        dfs(n, [])

if cycles:
    print('  FAIL  发现循环依赖：')
    for c in sorted(set(cycles))[:5]:
        print('        ', c)
    sys.exit(1)

print('  PASS  依赖方向合规，无循环依赖（模块 %d 个 / 边 %d 条）'
      % (len(edges), sum(len(v) for v in edges.values())))
PY
  [ $? -ne 0 ] && fail=1
else
  printf '  SKIP  未找到 python，跳过依赖图检测\n'
fi

echo "== 5) 用例可追溯（USE-CASES.yaml 的每条 ID 都要在代码里落地） =="
CASES=""
for p in USE-CASES.yaml ../wanbox/USE-CASES.yaml ../../wanbox/USE-CASES.yaml ../arch/wanbox/USE-CASES.yaml; do
  [ -f "$p" ] && CASES="$p" && break
done
if [ -z "$CASES" ]; then
  printf '  SKIP  未找到 USE-CASES.yaml\n'
elif [ -d core ] && [ -d tests ]; then
  total=0; missing=""
  for id in $(grep -oE 'id: *(UC-[A-Z0-9-]+)' "$CASES" | sed 's/id: *//' | sort -u); do
    total=$((total + 1))
    if ! grep -rq "$id" core adapters cli tests 2>/dev/null; then
      missing="$missing $id"
    fi
  done
  if [ -n "$missing" ]; then
    bad "以下用例没有落地：$missing"
  else
    pass "用例基线 $total 条，全部在代码中可追溯"
  fi
else
  printf '  SKIP  core-proof 未在当前目录下\n'
fi

echo "== 6) 现状耦合债（仅报告，不判失败） =="
ui_dir="app/src/main/java/io/nekohasekai/sagernet/ui"
widget_dir="app/src/main/java/io/nekohasekai/sagernet/widget"
if [ -d "$ui_dir" ]; then
  n=$(grep -rIlE "ProfileManager\.|DataStore\.|BaseService\.|SagerNet\.(start|stop)Service" "$ui_dir" "$widget_dir" 2>/dev/null | wc -l | tr -d ' ')
  printf '  INFO  ui+widget 中直接触碰数据层/服务层的文件：%s 个\n' "$n"
  printf '  INFO  该数字应随 S3 逐步下降，S4 归零\n'
fi

echo
if [ "$fail" -eq 0 ]; then echo "ARCH CHECK: PASS"; else echo "ARCH CHECK: FAIL"; fi
exit "$fail"

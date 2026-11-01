#!/usr/bin/env python3
"""B 路线静态守卫：拦截绕过 CoreRuntime 的内嵌核心直调。

瘦管理器构建（-PwanboxThin=true）不含 libgojni.so，任何 go.Seq / libcore.Libcore
调用都会在类初始化阶段抛 UnsatisfiedLinkError，且只在用户手里才炸。
所以这里把所有裸调用挡在 CI：

  python3 tools/check_core_gate.py            # 检查 app/src/main
  python3 tools/check_core_gate.py --all      # 连测试代码一起检查

放行条件（满足其一）：
  1. 位于 CoreRuntime.kt 内部（闸门实现本身）；
  2. 该行自身带 `CoreRuntime.available` / `CoreRuntime.` 门控；
  3. 向前 12 行内存在 `CoreRuntime.requireAvailable(` 或 `if (CoreRuntime.available) {`；
  4. 注释行。
"""
import argparse
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
GATE_FILE = 'core/CoreRuntime.kt'
PATTERNS = [
    re.compile(r'\bLibcore\.'),
    re.compile(r'\blibcore\.Libcore\b'),
    re.compile(r'\bSeq\.setContext\b'),
    re.compile(r'\bgo\.Seq\b'),
]
GUARD_LOOKBACK = 12


def is_comment(line: str) -> bool:
    s = line.strip()
    return s.startswith('//') or s.startswith('*') or s.startswith('/*')


def scan(path: pathlib.Path):
    bad = []
    lines = path.read_text(encoding='utf-8', errors='replace').splitlines()
    for idx, line in enumerate(lines):
        if is_comment(line) or line.lstrip().startswith('import '):
            continue
        if not any(p.search(line) for p in PATTERNS):
            continue
        if 'CoreRuntime.available' in line or 'CoreRuntime.' in line:
            continue
        window = lines[max(0, idx - GUARD_LOOKBACK):idx]
        if any('CoreRuntime.requireAvailable(' in w for w in window):
            continue
        if any('if (CoreRuntime.available' in w for w in window):
            continue
        bad.append((idx + 1, line.strip()))
    return bad


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument('--all', action='store_true', help='同时检查测试源码')
    args = ap.parse_args()

    roots = [ROOT / 'app/src/main']
    if args.all:
        roots.append(ROOT / 'app/src/test')
    files = []
    for r in roots:
        files += [p for p in r.rglob('*') if p.suffix in ('.kt', '.java')]

    violations = 0
    for f in sorted(files):
        if f.as_posix().endswith(GATE_FILE):
            continue
        for ln, text in scan(f):
            violations += 1
            print(f'{f.relative_to(ROOT)}:{ln}: {text}')

    if violations:
        print(f'\nFAIL: {violations} 处内嵌核心直调绕过了 CoreRuntime')
        return 1
    print(f'OK: {len(files)} 个源文件，未发现绕过 CoreRuntime 的内嵌核心直调')
    return 0


if __name__ == '__main__':
    sys.exit(main())

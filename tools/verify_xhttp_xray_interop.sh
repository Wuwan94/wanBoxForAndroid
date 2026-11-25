#!/usr/bin/env bash
set -euo pipefail

# Resolve the repository relative to this script, independent of the caller's cwd.
cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.."
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
curl --fail --silent --show-error --location --retry 3 --retry-delay 5 \
  -o "$work/Xray-linux-64.zip" \
  https://github.com/XTLS/Xray-core/releases/download/v26.11.9/Xray-linux-64.zip
printf '%s  %s\n' b651f7e1d9966ee2c1b1ee1bf8b52c72771824d3065dce96bbc8bcc19ed95f82 "$work/Xray-linux-64.zip" \
  | sha256sum --check --strict -
python3 - "$work" <<'PY'
import stat
import sys
import zipfile
from pathlib import Path

work = Path(sys.argv[1])
with zipfile.ZipFile(work / "Xray-linux-64.zip") as archive:
    archive.extract("xray", work)
binary = work / "xray"
binary.chmod(binary.stat().st_mode | stat.S_IXUSR | stat.S_IXGRP | stat.S_IXOTH)
PY
"$work/xray" version
log="$work/interop.log"
set +e
(cd libcore && XRAY_BIN="$work/xray" go test -count=1 -run '^TestRealXrayXHTTPInterop$' -v .) > "$log" 2>&1
status=$?
set -e
cat "$log"
if [ "$status" -ne 0 ]; then
  echo "::error::Xray XHTTP 互通矩阵失败（go test 退出码 $status）"
  exit "$status"
fi
if grep -nE -- '--- SKIP|set XRAY_BIN to an official' "$log"; then
  echo "::error::Xray 互通用例被跳过：门禁必须真跑，不接受 SKIP"
  exit 1
fi
passed=$(grep -cE '^[[:space:]]+--- PASS: TestRealXrayXHTTPInterop/' "$log" || true)
echo "Xray 互通子用例通过数：$passed"
if [ "$passed" -ne 12 ]; then
  echo "::error::Xray 互通子用例应有 12 项，实际 $passed 项"
  exit 1
fi

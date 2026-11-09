#!/bin/bash

set -e

DIR=app/src/main/assets/sing-box
rm -rf $DIR
mkdir -p $DIR
cd $DIR

get_latest_release() {
  local repo="$1"
  local tag=""
  # Try redirect location first (no API rate limiting)
  tag=$(curl -sI "https://github.com/$repo/releases/latest" | grep -i '^location:' | sed -E 's/.*\/tag\/([^\r\n]+).*/\1/' | tr -d '\r\n')
  # If empty, try GitHub API with token if available
  if [ -z "$tag" ]; then
    if [ -n "$GITHUB_TOKEN" ]; then
      tag=$(curl -s -H "Authorization: Bearer $GITHUB_TOKEN" "https://api.github.com/repos/$repo/releases/latest" | grep '"tag_name":' | sed -E 's/.*"([^"]+)".*/\1/' | tr -d '\r\n')
    else
      tag=$(curl -s "https://api.github.com/repos/$repo/releases/latest" | grep '"tag_name":' | sed -E 's/.*"([^"]+)".*/\1/' | tr -d '\r\n')
    fi
  fi
  echo "$tag"
}

# GitHub release CDN 偶发对固定的 browser_download_url 返回 HTTP 504，且 latest 别名
# 会重定向回同一个坏缓存。按以下顺序取同一资产，任何一步成功即止：
# 1) 精确 tag 原始 URL；2) 同一 URL 加变化的查询参数绕开坏缓存；
# 3) latest 别名加查询参数；4) releases API 的 asset 端点（重定向到带签名的对象存储）。
# 每一步都要求输出文件非空，避免把 504 的错误页当成数据库参与后续 xz 压缩。
download_release_asset() {
  local repo="$1" tag="$2" name="$3" out="$4"
  local cb="w$(date +%s)$$"
  local candidates=()
  if [ -n "$tag" ]; then
    candidates+=("https://github.com/$repo/releases/download/$tag/$name")
    candidates+=("https://github.com/$repo/releases/download/$tag/$name?cb=$cb")
  fi
  candidates+=("https://github.com/$repo/releases/latest/download/$name?cb=$cb-l")

  local url
  for url in "${candidates[@]}"; do
    rm -f "$out"
    if curl -fLSs --retry 3 --retry-delay 2 --max-time 300 -o "$out" "$url" && [ -s "$out" ]; then
      echo "downloaded $name via $url"
      return 0
    fi
  done

  local api_url="https://api.github.com/repos/$repo/releases/latest"
  if [ -n "$tag" ]; then
    api_url="https://api.github.com/repos/$repo/releases/tags/$tag"
  fi
  local auth=()
  if [ -n "$GITHUB_TOKEN" ]; then
    auth=(-H "Authorization: Bearer $GITHUB_TOKEN")
  fi
  local py=python3
  command -v python3 >/dev/null 2>&1 || py=python
  local aid=""
  aid=$(curl -sSL --max-time 60 "${auth[@]}" -H "Accept: application/vnd.github+json" "$api_url" \
    | "$py" -c 'import json,sys; d=json.load(sys.stdin); print(next((str(a["id"]) for a in d.get("assets", []) if a.get("name") == sys.argv[1]), ""))' "$name" 2>/dev/null || true)
  if [ -n "$aid" ]; then
    rm -f "$out"
    if curl -fLSs --retry 3 --retry-delay 2 --max-time 300 "${auth[@]}" \
      -H "Accept: application/octet-stream" -o "$out" \
      "https://api.github.com/repos/$repo/releases/assets/$aid" && [ -s "$out" ]; then
      echo "downloaded $name via releases API asset $aid"
      return 0
    fi
  fi
  echo "failed to download $name after all fallbacks" >&2
  return 1
}

####
VERSION_GEOIP=$(get_latest_release "SagerNet/sing-geoip")
echo VERSION_GEOIP=$VERSION_GEOIP
if [ -n "$VERSION_GEOIP" ]; then
  echo -n "$VERSION_GEOIP" > geoip.version.txt
else
  echo -n "latest" > geoip.version.txt
fi
download_release_asset "SagerNet/sing-geoip" "$VERSION_GEOIP" "geoip.db" "geoip.db"
xz -9 geoip.db

####
VERSION_GEOSITE=$(get_latest_release "SagerNet/sing-geosite")
echo VERSION_GEOSITE=$VERSION_GEOSITE
if [ -n "$VERSION_GEOSITE" ]; then
  echo -n "$VERSION_GEOSITE" > geosite.version.txt
else
  echo -n "latest" > geosite.version.txt
fi
download_release_asset "SagerNet/sing-geosite" "$VERSION_GEOSITE" "geosite.db" "geosite.db"
xz -9 geosite.db

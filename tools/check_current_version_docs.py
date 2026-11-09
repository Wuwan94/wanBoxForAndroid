#!/usr/bin/env python3
# @author 雾晚
"""离线核对「当前发布状态」文档，防止 README / 版本对照 / 发布清单 / 发行说明漂移。

本检查只读仓库内的文本文件，不访问 GitHub、不依赖 Release API、不解析网络响应。
历史归档段落按标记跳过：历史 SHA、测试数量与旧版数值保留原始语境，不做全局改名。
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

STABLE_TAG = "v3.0.11"
PUBLISHED_PREVIEW = "v3.0.13-preview.1"
PUBLISHED_PREVIEW_COMMIT = "7b21b3d87e4f080b05fd1a109bd19b71c66fad23"
CANDIDATE = "v3.0.13-preview.2"
CANDIDATE_CODE = "2022"
HISTORY_MARKER = "历史归档"

FILES = {
    "readme": "README.md",
    "current": "docs/current-version.zh-CN.md",
    "checklist": "docs/release-checklist.zh-CN.md",
    "notes": "PREVIEW_NOTES.md",
    "next": "docs/release-notes-next.zh-CN.md",
    "changelog": "CHANGELOG.md",
    "properties": "nb4a.properties",
}


def read(root: Path, key: str) -> str:
    path = root / FILES[key]
    if not path.is_file():
        raise FileNotFoundError(f"缺少文件: {path}")
    return path.read_text(encoding="utf-8")


def current_section(text: str) -> str:
    """取「当前状态」区段；历史归档之后的内容不参与本轮断言。"""
    marker = text.find(HISTORY_MARKER)
    return text[:marker] if marker >= 0 else text


def parse_properties(text: str) -> dict[str, str]:
    values: dict[str, str] = {}
    for raw in text.splitlines():
        line = raw.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, _, value = line.partition("=")
        values[key.strip()] = value.strip()
    return values


def failures(root: Path = ROOT) -> list[str]:
    """返回所有不一致点；空列表表示当前文档快照一致。"""
    errors: list[str] = []
    try:
        readme = read(root, "readme")
        current = current_section(read(root, "current"))
        checklist = current_section(read(root, "checklist"))
        notes = read(root, "notes")
        next_notes = read(root, "next")
        changelog = read(root, "changelog")
        props = parse_properties(read(root, "properties"))
    except (FileNotFoundError, UnicodeDecodeError) as exc:
        return [f"读取当前版本文档失败: {exc}"]

    # 1. 源码元数据：正式线不动，预览线指向未发布候选。
    if props.get("VERSION_NAME") != "3.0.11":
        errors.append(f"nb4a.properties: VERSION_NAME 应为 3.0.11，实际 {props.get('VERSION_NAME')!r}")
    if props.get("VERSION_CODE") != "2017":
        errors.append(f"nb4a.properties: VERSION_CODE 应为 2017（正式线不改），实际 {props.get('VERSION_CODE')!r}")
    if props.get("PRE_VERSION_NAME") != CANDIDATE[1:]:
        errors.append(f"nb4a.properties: PRE_VERSION_NAME 应为 {CANDIDATE[1:]}，实际 {props.get('PRE_VERSION_NAME')!r}")
    if props.get("PRE_VERSION_CODE") != CANDIDATE_CODE:
        errors.append(f"nb4a.properties: PRE_VERSION_CODE 应为 {CANDIDATE_CODE}，实际 {props.get('PRE_VERSION_CODE')!r}")

    # 2. README 中英版本快照与徽章：三条状态必须同时成立。
    head = "\n".join(readme.splitlines()[:45])
    for label, token in (
        ("已发布正式版", STABLE_TAG),
        ("已发布预览版", PUBLISHED_PREVIEW),
        ("未发布候选", CANDIDATE),
    ):
        if token not in head:
            errors.append(f"README.md 当前版本快照缺少{label} {token}")
    if re.search(r"已发布预览(?:版)?为\s*\[?v?3\.0\.12-preview\.3", head):
        errors.append("README.md 仍把 v3.0.12-preview.3 写成已发布预览")
    if re.search(r"本轮目标为\s*\*\*v3\.0\.12-preview\.4", head):
        errors.append("README.md 仍把 v3.0.12-preview.4 写成本轮目标")
    if f"releases/tag/{PUBLISHED_PREVIEW}" not in head:
        errors.append("README.md 预览徽章/link 未指向已发布的 v3.0.13-preview.1")
    if not re.search(r"Next-[^\"]*3\.0\.13--preview\.2[^\"]*unreleased", head):
        errors.append("README.md 未发布徽章应标注 3.0.13-preview.2 且注明 unreleased")
    en = [line for line in head.splitlines() if line.startswith("Published releases:")]
    if not en or not all(t in en[0] for t in (STABLE_TAG, PUBLISHED_PREVIEW, CANDIDATE)):
        errors.append("README.md 英文快照未同时列出正式版 / 已发布预览 / 未发布候选")

    # 3. 版本对照：三行状态 + 已发布预览的精确来源提交。
    table = "\n".join(current.splitlines()[:14])
    for label in ("已发布正式版", "已发布预览版", "未发布候选"):
        if label not in table:
            errors.append(f"docs/current-version.zh-CN.md 表格缺少「{label}」行")
    if PUBLISHED_PREVIEW not in table or PUBLISHED_PREVIEW_COMMIT not in table:
        errors.append("docs/current-version.zh-CN.md 已发布预览行缺少 v3.0.13-preview.1 或其精确提交")
    if CANDIDATE not in table:
        errors.append("docs/current-version.zh-CN.md 未标出未发布候选 v3.0.13-preview.2")
    if "v3.0.12-preview.4" in table and "历史" not in table:
        errors.append("docs/current-version.zh-CN.md 当前表格仍以 v3.0.12-preview.4 为未发布目标")
    if "发布页" in current and "releases/tag/v3.0.12-preview.3" in current:
        errors.append("docs/current-version.zh-CN.md 仍把已清理的 preview.3 链接当作当前下载入口")

    # 4. 发布清单：标题与首段指向本轮候选，旧 preview.3 只能留在历史归档。
    if not checklist.lstrip().startswith(f"# {CANDIDATE}"):
        errors.append("docs/release-checklist.zh-CN.md 标题未对齐 v3.0.13-preview.2")
    if "未发布候选" not in "\n".join(checklist.splitlines()[:6]):
        errors.append("docs/release-checklist.zh-CN.md 首段未声明「未发布候选」")
    if re.search(r"本轮目标仅为\s*\*\*v3\.0\.12-preview\.3", checklist):
        errors.append("docs/release-checklist.zh-CN.md 当前区段仍以 v3.0.12-preview.3 为本轮目标")

    # 5. 发行说明：候选稿不得写成已发布；preview.1 已发布说明保留归档语境。
    for name, text in (("PREVIEW_NOTES.md", notes), ("docs/release-notes-next.zh-CN.md", next_notes)):
        title = text.splitlines()[0] if text.splitlines() else ""
        if not title.startswith(f"# {CANDIDATE}"):
            errors.append(f"{name} 标题未对齐未发布候选 {CANDIDATE}")
        if "未发布候选" not in text:
            errors.append(f"{name} 未标注「未发布候选」")
        if "已发布说明" not in text or PUBLISHED_PREVIEW not in text:
            errors.append(f"{name} 缺少 {PUBLISHED_PREVIEW} 已发布说明的历史归档段落")

    # 6. CHANGELOG：已发布 preview.1 与未发布 preview.2 分别记账。
    changelog_head = "\n".join(changelog.splitlines()[:40])
    if PUBLISHED_PREVIEW not in changelog_head:
        errors.append("CHANGELOG.md 缺少已发布的 v3.0.13-preview.1 记录")
    if CANDIDATE not in changelog_head or "未发布" not in changelog_head:
        errors.append("CHANGELOG.md 缺少标注为未发布的 v3.0.13-preview.2 候选记录")

    return errors


def main() -> int:
    errors = failures()
    if errors:
        print("当前版本文档一致性检查失败：")
        for item in errors:
            print(f"  - {item}")
        return 1
    print("当前版本文档一致性检查通过（离线，未访问网络）")
    return 0


if __name__ == "__main__":
    sys.exit(main())

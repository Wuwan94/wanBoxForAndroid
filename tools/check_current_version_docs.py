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

STABLE_TAG = "v3.0.14"
PUBLISHED_PREVIEW = "v3.0.14-preview.1"
PUBLISHED_PREVIEW_COMMIT = "8b4e60893dc4d783caeab1b0f5ddb6667aa0549d"
SOURCE_PREVIEW = "v3.0.15-preview.1"
SOURCE_PREVIEW_CODE = "2027"
ARCHIVED_PREVIEW = "v3.0.13-preview.4"
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

    # 1. 源码元数据：正式线为已晋升的 3.0.14，预览线为下一线占位候选。
    if props.get("VERSION_NAME") != "3.0.14":
        errors.append(f"nb4a.properties: VERSION_NAME 应为 3.0.14，实际 {props.get('VERSION_NAME')!r}")
    if props.get("VERSION_CODE") != "2026":
        errors.append(f"nb4a.properties: VERSION_CODE 应为 2026（正式线），实际 {props.get('VERSION_CODE')!r}")
    if props.get("PRE_VERSION_NAME") != SOURCE_PREVIEW[1:]:
        errors.append(f"nb4a.properties: PRE_VERSION_NAME 应为 {SOURCE_PREVIEW[1:]}，实际 {props.get('PRE_VERSION_NAME')!r}")
    if props.get("PRE_VERSION_CODE") != SOURCE_PREVIEW_CODE:
        errors.append(f"nb4a.properties: PRE_VERSION_CODE 应为 {SOURCE_PREVIEW_CODE}，实际 {props.get('PRE_VERSION_CODE')!r}")

    # 2. README 中英版本快照与徽章：只有两个已发布状态，不留未发布候选徽章。
    head = "\n".join(readme.splitlines()[:45])
    for label, token in (
        ("已发布正式版", STABLE_TAG),
        ("已发布预览版", PUBLISHED_PREVIEW),
    ):
        if token not in head:
            errors.append(f"README.md 当前版本快照缺少{label} {token}")
    if f"releases/tag/{PUBLISHED_PREVIEW}" not in head:
        errors.append(f"README.md 预览徽章/link 未指向已发布的 {PUBLISHED_PREVIEW}")
    if f"已发布预览版为 [{PUBLISHED_PREVIEW}]" not in head:
        errors.append(f"README.md 中文快照未把 {PUBLISHED_PREVIEW} 标为已发布预览")
    if re.search(r'Next-[^"]*unreleased', head):
        errors.append("README.md 仍保留未发布候选徽章")
    en = [line for line in head.splitlines() if line.startswith("Published releases:")]
    if not en or not all(t in en[0] for t in (STABLE_TAG, PUBLISHED_PREVIEW)):
        errors.append("README.md 英文快照未同时列出正式版与已发布预览")
    if en and "unreleased candidate" in en[0]:
        errors.append("README.md 英文快照仍把本版写成未发布候选")

    # 3. 版本对照：两行状态 + 已发布预览的精确来源提交。
    table = "\n".join(current.splitlines()[:14])
    for label in ("已发布正式版", "已发布预览版"):
        if label not in table:
            errors.append(f"docs/current-version.zh-CN.md 表格缺少「{label}」行")
    if PUBLISHED_PREVIEW not in table or PUBLISHED_PREVIEW_COMMIT not in table:
        errors.append(f"docs/current-version.zh-CN.md 已发布预览行缺少 {PUBLISHED_PREVIEW} 或其精确提交")
    if "未发布候选" in table or "未发布的稳定性预览版候选" in table:
        errors.append("docs/current-version.zh-CN.md 当前表格仍标有未发布候选")
    if "当前源码基线" in table:
        errors.append(
            "docs/current-version.zh-CN.md 仍把发布 tag 提交写成「当前源码基线」；"
            "发布后的文档同步会移动 main HEAD，只能记为固定发布基线"
        )

    # 4. 发布清单：标题与首段指向已发布版本。
    if not checklist.lstrip().startswith(f"# {PUBLISHED_PREVIEW}"):
        errors.append("docs/release-checklist.zh-CN.md 标题未对齐已发布预览")
    lead = "\n".join(checklist.splitlines()[:6])
    if "已发布" not in lead:
        errors.append("docs/release-checklist.zh-CN.md 首段未声明「已发布」")
    if "未发布候选" in lead:
        errors.append("docs/release-checklist.zh-CN.md 首段仍写「未发布候选」")

    # 5. 发行说明：当前区段跟随源码预览并如实标记发布状态；归档预览保留。
    for name, text in (("PREVIEW_NOTES.md", notes), ("docs/release-notes-next.zh-CN.md", next_notes)):
        section = current_section(text)
        title = section.splitlines()[0] if section.splitlines() else ""
        if not title.startswith(f"# {SOURCE_PREVIEW}"):
            errors.append(f"{name} 标题未对齐源码预览 {SOURCE_PREVIEW}")
        if "稳定性预览版" not in section:
            errors.append(f"{name} 当前区段未声明「稳定性预览版」")
        if SOURCE_PREVIEW == PUBLISHED_PREVIEW and (
            "未发布的稳定性预览版候选" in section or title.endswith("（候选）")
        ):
            errors.append(f"{name} 当前区段仍把已发布版本写成「未发布候选」")
        if SOURCE_PREVIEW != PUBLISHED_PREVIEW and "状态：未发布的稳定性预览版候选" not in section:
            errors.append(f"{name} 当前源码候选必须明确标为「未发布候选」")
        if "已发布说明" not in text or ARCHIVED_PREVIEW not in text:
            errors.append(f"{name} 缺少 {ARCHIVED_PREVIEW} 已发布说明的历史归档段落")

    # 6. CHANGELOG：已发布本版与归档预览分别记账。
    changelog_head = "\n".join(changelog.splitlines()[:40])
    if PUBLISHED_PREVIEW not in changelog_head:
        errors.append(f"CHANGELOG.md 缺少已发布的 {PUBLISHED_PREVIEW} 记录")
    if ARCHIVED_PREVIEW not in changelog_head:
        errors.append(f"CHANGELOG.md 缺少归档预览 {ARCHIVED_PREVIEW} 记录")
    if "（未发布候选，未发布）" in changelog_head:
        errors.append("CHANGELOG.md 仍把本版标题写成未发布候选")

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

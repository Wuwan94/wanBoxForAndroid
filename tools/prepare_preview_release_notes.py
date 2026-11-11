#!/usr/bin/env python3
"""Render the marked source candidate notes as the public preview release body."""
from __future__ import annotations

import argparse
from pathlib import Path

CANDIDATE_SUFFIX = "（候选）"
STATUS_PREFIX = "**状态：未发布的稳定性预览版候选。**"


def render_release_notes(text: str) -> str:
    """Strip only the source-candidate title suffix and status paragraph."""
    lines = text.splitlines()
    if len(lines) < 5:
        raise ValueError("preview notes are too short to contain a release body")
    title = lines[0]
    if not title.startswith("# v") or not title.endswith(CANDIDATE_SUFFIX):
        raise ValueError("preview notes title must be explicitly marked as a candidate")
    if lines[1] != "" or not lines[2].startswith(STATUS_PREFIX) or lines[3] != "":
        raise ValueError("preview notes must begin with the required unpublished-candidate status paragraph")

    release_title = title[: -len(CANDIDATE_SUFFIX)].rstrip()
    body = "\n".join([release_title, "", *lines[4:]]).rstrip() + "\n"
    if body.splitlines()[0] != release_title or STATUS_PREFIX in body:
        raise ValueError("candidate-only status leaked into the public release body")
    return body


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    rendered = render_release_notes(args.input.read_text(encoding="utf-8"))
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(rendered, encoding="utf-8")
    print(f"Prepared public preview release notes: {args.output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

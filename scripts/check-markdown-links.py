#!/usr/bin/env python3
from __future__ import annotations

import argparse
import re
from dataclasses import dataclass
from pathlib import Path
from urllib.parse import unquote


REPO_ROOT = Path(__file__).resolve().parents[1]

MARKDOWN_LINK_RE = re.compile(r"\[[^\]]+\]\(([^)]+)\)")
CODE_SPAN_RE = re.compile(r"`([^`]+)`")


def is_external_link(target: str) -> bool:
    t = target.strip()
    return (
        t.startswith("#")
        or t.startswith("http://")
        or t.startswith("https://")
        or t.startswith("mailto:")
        or t.startswith("tel:")
    )


def strip_fragment_and_query(target: str) -> str:
    t = target.split("#", 1)[0]
    t = t.split("?", 1)[0]
    return t


def looks_like_repo_path(code_span: str) -> bool:
    s = code_span.strip()
    if "://" in s:
        return False
    if s.startswith("/") or s.startswith("~"):
        return False
    if " " in s or "\t" in s:
        return False
    return any(
        s.startswith(prefix)
        for prefix in (
            "applications/",
            "docs/",
            "libraries/",
            "compose/",
            "scripts/",
            "docker/",
        )
    )


def normalize_code_span_path(code_span: str) -> str:
    s = code_span.strip()
    # Ignore templates, globs, and ellipsis placeholders used in docs.
    if any(token in s for token in ("{", "}", "<", ">", "*")):
        return ""
    if "..." in s:
        return ""
    s = s.rstrip("()[],.;:")
    return s


@dataclass(frozen=True)
class BrokenRef:
    kind: str  # "md-link" | "code-path"
    file: Path
    line: int
    raw: str
    resolved: Path


def iter_target_files() -> list[Path]:
    candidates: list[Path] = []
    # Scope: root README.md, docs/**/*.md, and per-module README.md under
    # applications/**/README.md.
    for p in ("README.md", "docs"):
        path = REPO_ROOT / p
        if path.is_file():
            candidates.append(path)
        elif path.is_dir():
            candidates.extend(path.rglob("*.md"))

    apps_dir = REPO_ROOT / "applications"
    if apps_dir.is_dir():
        candidates.extend(apps_dir.rglob("README.md"))

    candidates = [c for c in candidates if c.is_file()]
    # Avoid scanning vendor/build directories
    pruned: list[Path] = []
    for c in candidates:
        parts = set(c.parts)
        # node_modules and Angular's build cache arrived with applications/webapp; their
        # vendored READMEs are not ours to keep link-clean.
        if {"target", ".git", "package", "node_modules", ".angular", "dist"} & parts:
            continue
        pruned.append(c)
    return sorted(set(pruned))


def check_file(path: Path) -> list[BrokenRef]:
    broken: list[BrokenRef] = []
    base_dir = path.parent
    try:
        text = path.read_text(encoding="utf-8")
    except UnicodeDecodeError:
        text = path.read_text(encoding="utf-8", errors="replace")

    for idx, line in enumerate(text.splitlines(), start=1):
        # Markdown links [text](target)
        for m in MARKDOWN_LINK_RE.finditer(line):
            raw_target = m.group(1).strip()
            if is_external_link(raw_target):
                continue
            target = strip_fragment_and_query(raw_target)
            if not target:
                continue
            # ignore angle-bracket links like <path>
            target = target.strip("<>")
            target = unquote(target)
            # Ignore template placeholders like "{user.avatar}" or "(URL)" in vendored docs.
            if any(token in target for token in ("{", "}", "<", ">")):
                continue
            # Only check local file-ish targets; ignore generic placeholders.
            if "/" not in target and "." not in target:
                continue
            # allow root-relative: /foo/bar (treat as repo-relative)
            if target.startswith("/"):
                resolved = (REPO_ROOT / target.removeprefix("/")).resolve()
            else:
                resolved = (base_dir / target).resolve()
            if not resolved.exists():
                broken.append(
                    BrokenRef(
                        kind="md-link",
                        file=path,
                        line=idx,
                        raw=raw_target,
                        resolved=resolved,
                    )
                )

        # Backticked code paths like `applications/...`
        for m in CODE_SPAN_RE.finditer(line):
            raw_span = m.group(1)
            if not looks_like_repo_path(raw_span):
                continue
            candidate = normalize_code_span_path(raw_span)
            if not candidate:
                continue
            resolved = (REPO_ROOT / candidate).resolve()
            if not resolved.exists():
                broken.append(
                    BrokenRef(
                        kind="code-path",
                        file=path,
                        line=idx,
                        raw=raw_span,
                        resolved=resolved,
                    )
                )

    return broken


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Check local markdown links and repo-path code pointers."
    )
    parser.add_argument(
        "--json",
        action="store_true",
        help="Print machine-readable JSON lines (one per broken ref).",
    )
    args = parser.parse_args()

    files = iter_target_files()
    broken: list[BrokenRef] = []
    for f in files:
        broken.extend(check_file(f))

    if not broken:
        print("OK: no broken markdown links or repo-path code pointers found.")
        return 0

    if args.json:
        import json

        for b in broken:
            print(
                json.dumps(
                    {
                        "kind": b.kind,
                        "file": str(b.file.relative_to(REPO_ROOT)),
                        "line": b.line,
                        "raw": b.raw,
                        "resolved": str(b.resolved),
                    }
                )
            )
    else:
        print(f"Found {len(broken)} broken references:\n")
        for b in broken:
            rel = b.file.relative_to(REPO_ROOT)
            print(f"- {b.kind}: {rel}:{b.line}: `{b.raw}` -> {b.resolved}")
    return 1


if __name__ == "__main__":
    raise SystemExit(main())


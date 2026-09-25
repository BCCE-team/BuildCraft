#!/usr/bin/env python3
"""Source-level API v2 surface snapshot helpers.

The snapshot intentionally hashes normalized Java tokens for every effective public API source.
It is stricter than a binary ABI check: default-method bodies and other executable public API
code are frozen too. Comments and formatting are ignored.
"""
from __future__ import annotations

import hashlib
import re
import tempfile
from pathlib import Path

from source_layout import load_properties, materialize_target, target_ids

ROOT = Path(__file__).resolve().parents[1]
API_PREFIX = Path("src/main/java/buildcraft/api/v2")
TOKEN_RE = re.compile(
    r'"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'|'
    r'[A-Za-z_$][A-Za-z0-9_$]*|'
    r'0[xX][0-9A-Fa-f_]+|\d+(?:\.\d+)?(?:[eE][+-]?\d+)?[fFdDlL]?|'
    r'>>>|>>|<<|::|->|==|!=|<=|>=|&&|\|\||\+\+|--|\+=|-=|\*=|/=|%=|&=|\|=|\^=|'
    r'[{}()\[\];,.<>?:~!%^&*+=|/-]'
)


def strip_comments(text: str) -> str:
    out: list[str] = []
    i = 0
    state = "code"
    while i < len(text):
        ch = text[i]
        nxt = text[i + 1] if i + 1 < len(text) else ""
        if state == "code":
            if ch == '"':
                out.append(ch)
                state = "string"
            elif ch == "'":
                out.append(ch)
                state = "char"
            elif ch == "/" and nxt == "/":
                state = "line_comment"
                i += 1
            elif ch == "/" and nxt == "*":
                state = "block_comment"
                i += 1
            else:
                out.append(ch)
        elif state == "string":
            out.append(ch)
            if ch == "\\" and i + 1 < len(text):
                i += 1
                out.append(text[i])
            elif ch == '"':
                state = "code"
        elif state == "char":
            out.append(ch)
            if ch == "\\" and i + 1 < len(text):
                i += 1
                out.append(text[i])
            elif ch == "'":
                state = "code"
        elif state == "line_comment":
            if ch in "\r\n":
                out.append("\n")
                state = "code"
        elif state == "block_comment":
            if ch == "*" and nxt == "/":
                state = "code"
                i += 1
            elif ch in "\r\n":
                out.append("\n")
        i += 1
    return "".join(out)


def normalized_tokens(text: str) -> tuple[str, ...]:
    return tuple(TOKEN_RE.findall(strip_comments(text)))


def source_entry(path: Path) -> dict[str, object]:
    tokens = normalized_tokens(path.read_text(encoding="utf-8"))
    payload = "\n".join(tokens).encode("utf-8")
    return {
        "sha256": hashlib.sha256(payload).hexdigest(),
        "tokens": len(tokens),
    }


def snapshot() -> dict[str, object]:
    props = load_properties()
    targets: dict[str, object] = {}
    with tempfile.TemporaryDirectory(prefix="bcce-api-v2-surface-") as temp_dir:
        temp = Path(temp_dir)
        for target in target_ids(props):
            root = materialize_target(target, temp / target, props)
            api_root = root / API_PREFIX
            files: dict[str, object] = {}
            if api_root.is_dir():
                for path in sorted(api_root.rglob("*.java")):
                    rel = path.relative_to(root).as_posix()
                    files[rel] = source_entry(path)
            targets[target] = {
                "file_count": len(files),
                "files": files,
            }
    return {
        "schema_version": 1,
        "kind": "normalized_api_v2_source_tokens",
        "targets": targets,
    }

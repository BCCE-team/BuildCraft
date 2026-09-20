#!/usr/bin/env python3
"""Small Stonecutter-style conditional preprocessor for maintained source files."""
from __future__ import annotations

import ast
from pathlib import Path
import re

_VERSION_RE = re.compile(r"^\d+(?:\.\d+)*$")
_COMPARISON_RE = re.compile(r"(?<![\w.])(>=|<=|==|!=|>|<)\s*(\d+(?:\.\d+)*)")


def _version_tuple(value: str, width: int = 6) -> tuple[int, ...]:
    if not _VERSION_RE.fullmatch(value):
        raise ValueError(f"Invalid Minecraft version in condition: {value!r}")
    parts = [int(part) for part in value.split(".")]
    return tuple((parts + [0] * width)[:width])


def evaluate_condition(condition: str, *, minecraft: str, family: str, platform: str) -> bool:
    def version_cmp(operator: str, other: str) -> bool:
        left = _version_tuple(minecraft)
        right = _version_tuple(other)
        return {
            ">=": left >= right,
            "<=": left <= right,
            ">": left > right,
            "<": left < right,
            "==": left == right,
            "!=": left != right,
        }[operator]

    expression = condition.strip().replace("&&", " and ").replace("||", " or ")
    expression = re.sub(r"!(?!=)", " not ", expression)
    expression = _COMPARISON_RE.sub(lambda m: f'version_cmp("{m.group(1)}", "{m.group(2)}")', expression)
    names = {
        "forge": platform == "forge",
        "neoforge": platform == "neoforge",
        "fabric": platform == "fabric",
        "legacy": family == "legacy",
        "modern": family == "modern",
        "true": True,
        "false": False,
        "version_cmp": version_cmp,
    }
    tree = ast.parse(expression, mode="eval")
    allowed_nodes = (
        ast.Expression, ast.BoolOp, ast.And, ast.Or, ast.UnaryOp, ast.Not,
        ast.Call, ast.Name, ast.Load, ast.Constant,
    )
    for node in ast.walk(tree):
        if not isinstance(node, allowed_nodes):
            raise ValueError(f"Unsupported condition syntax {condition!r}: {type(node).__name__}")
        if isinstance(node, ast.Name) and node.id not in names:
            raise ValueError(f"Unknown condition name {node.id!r} in {condition!r}")
        if isinstance(node, ast.Call):
            if not isinstance(node.func, ast.Name) or node.func.id != "version_cmp":
                raise ValueError(f"Unsupported function in condition {condition!r}")
    return bool(eval(compile(tree, "<stonecutter-condition>", "eval"), {"__builtins__": {}}, names))



_SOURCE_SELECTOR_RE = re.compile(r"^//\?\s*source\s+if\s+(.+?)\s*$")


def source_condition(text: str) -> str | None:
    """Return an optional whole-file source selector from the first line.

    A source selector is a structural alternative to a target overlay for a
    complete implementation that only exists on a Minecraft version band.
    Example::

        //? source if >=1.21.11

    The directive is stripped from the effective source. Disabled higher-layer
    candidates fall back to the next lower layer with the same logical path.
    Loader predicates are intentionally not a use case: loader ownership belongs
    in source-platforms/source-family-platforms.
    """
    if not text:
        return None
    first = text.splitlines()[0] if text.splitlines() else ""
    match = _SOURCE_SELECTOR_RE.fullmatch(first.rstrip("\r\n"))
    return match.group(1).strip() if match else None


def strip_source_condition(text: str) -> tuple[str, str | None]:
    """Strip a leading source selector while preserving the original bytes after it."""
    condition = source_condition(text)
    if condition is None:
        return text, None
    newline = text.find("\n")
    if newline < 0:
        return "", condition
    return text[newline + 1 :], condition


def source_is_enabled(
    text: str, *, minecraft: str, family: str, platform: str
) -> bool:
    condition = source_condition(text)
    if condition is None:
        return True
    return evaluate_condition(condition, minecraft=minecraft, family=family, platform=platform)

_IF_RE = re.compile(r"^\s*(?://\?|/\*\?)\s*if\s+(.+?)\s*\{\s*(?:\*/)?\s*$")
_ELSE_IF_RE = re.compile(r"^\s*(?://\?|/\*\?)\s*}\s*else\s+if\s+(.+?)\s*\{\s*(?:\*/)?\s*$")
_ELSE_RE = re.compile(r"^\s*(?://\?|/\*\?)\s*}\s*else\s*\{\s*(?:\*/)?\s*$")
_END_RE = re.compile(r"^\s*(?://\?|/\*\?)\s*}\s*(?:\*/)?\s*$")


def _directive(line: str) -> tuple[str, str | None] | None:
    text = line.rstrip("\r\n")
    if match := _IF_RE.match(text):
        return "if", match.group(1)
    if match := _ELSE_IF_RE.match(text):
        return "else_if", match.group(1)
    if _ELSE_RE.match(text):
        return "else", None
    if _END_RE.match(text):
        return "end", None
    return None


def _activate_branch(lines: list[str]) -> list[str]:
    nonblank = [i for i, line in enumerate(lines) if line.strip()]
    if not nonblank:
        return lines
    first, last = nonblank[0], nonblank[-1]
    first_text = lines[first].strip()
    last_text = lines[last].strip()

    # Preferred BCCE marker for a completely commented alternative branch.
    if first_text == "/*?" and last_text == "?*/":
        return lines[:first] + lines[first + 1:last] + lines[last + 1:]

    # Also accept the style used by existing Stonecutter projects, where the
    # inactive alternative is wrapped in one ordinary outer block comment.
    if first_text.startswith("/*") and not first_text.startswith("/**") and last_text.endswith("*/"):
        result = list(lines)
        start = result[first].find("/*")
        result[first] = result[first][:start] + result[first][start + 2:]
        end = result[last].rfind("*/")
        result[last] = result[last][:end] + result[last][end + 2:]
        return result
    return lines


def preprocess_text(text: str, *, minecraft: str, family: str, platform: str, source: str = "<memory>") -> str:
    lines = text.splitlines(keepends=True)

    def parse_sequence(index: int, stop_at_branch: bool) -> tuple[list[str], int, tuple[str, str | None] | None]:
        output: list[str] = []
        while index < len(lines):
            marker = _directive(lines[index])
            if marker is None:
                output.append(lines[index])
                index += 1
                continue
            kind, value = marker
            if kind == "if":
                selected, index = parse_conditional(index, value or "")
                output.extend(selected)
                continue
            if stop_at_branch and kind in {"else_if", "else", "end"}:
                return output, index, marker
            raise ValueError(f"{source}:{index + 1}: unexpected Stonecutter directive {lines[index].strip()!r}")
        if stop_at_branch:
            raise ValueError(f"{source}: unterminated Stonecutter conditional")
        return output, index, None

    def parse_conditional(index: int, first_condition: str) -> tuple[list[str], int]:
        branches: list[tuple[str | None, list[str]]] = []
        condition: str | None = first_condition
        index += 1
        while True:
            body, marker_index, marker = parse_sequence(index, True)
            branches.append((condition, body))
            if marker is None:
                raise ValueError(f"{source}: unterminated Stonecutter conditional")
            kind, value = marker
            if kind == "end":
                index = marker_index + 1
                break
            if kind == "else_if":
                condition = value or ""
                index = marker_index + 1
                continue
            if kind == "else":
                condition = None
                index = marker_index + 1
                body, marker_index, marker = parse_sequence(index, True)
                branches.append((None, body))
                if marker is None or marker[0] != "end":
                    line_no = marker_index + 1
                    raise ValueError(f"{source}:{line_no}: else branch must end with //?}}")
                index = marker_index + 1
                break
            raise AssertionError(kind)

        for branch_condition, body in branches:
            if branch_condition is None or evaluate_condition(
                branch_condition, minecraft=minecraft, family=family, platform=platform
            ):
                return _activate_branch(body), index
        return [], index

    output, index, marker = parse_sequence(0, False)
    if index != len(lines) or marker is not None:
        raise ValueError(f"{source}: failed to consume conditional source")
    return "".join(output)


_CONDITIONAL_TEXT_SUFFIXES = {
    ".java", ".kt", ".kts", ".gradle", ".json", ".mcmeta", ".toml",
    ".properties", ".yml", ".yaml", ".md", ".txt", ".cfg", ".xml",
    ".sh", ".bat", ".ps1",
}


def _is_conditional_text_path(path: Path) -> bool:
    return path.suffix.lower() in _CONDITIONAL_TEXT_SUFFIXES



# Public, non-underscored names for transform/config modules.
version_tuple = _version_tuple
is_conditional_text_path = _is_conditional_text_path

__all__ = [
    "evaluate_condition",
    "preprocess_text",
    "source_condition",
    "strip_source_condition",
    "source_is_enabled",
    "version_tuple",
    "is_conditional_text_path",
]

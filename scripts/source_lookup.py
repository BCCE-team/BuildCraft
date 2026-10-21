#!/usr/bin/env python3
"""Resolve maintained source paths without hard-coding their ownership layer.

Architecture migrations are allowed to promote a logical source file from a target/platform
layer into a family/shared layer. Validators should follow the effective source for the
representative target instead of pinning the previous physical owner.
"""
from __future__ import annotations

from pathlib import Path
import tempfile
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
from source_config import load_properties, target_layout
from source_layout import _materialize_text_file, resolve_effective_source

_PROPS = None


def _props():
    global _PROPS
    if _PROPS is None:
        _PROPS = load_properties()
    return _PROPS


def _representative_target(kind: str, name: str) -> str | None:
    if kind == "platform":
        return {"forge": "1.20.1-forge", "neoforge": "1.21.1-neoforge"}.get(name)
    if kind == "family":
        return {"old": "1.20.1-forge", "1.21.X": "1.21.1-neoforge", "26.X": "26.3-neoforge"}.get(name)
    return None


def resolve_source_path(rel: str | Path) -> Path:
    """Return the maintained file that supplies ``rel`` to the intended target.

    Existing paths are returned verbatim. Missing compatibility platform/family/target paths
    are resolved through the five-layer source model so regression checks survive source
    promotion without weakening the check itself.
    """
    rel_s = Path(rel).as_posix()
    direct = ROOT / rel_s
    if direct.is_file():
        return direct

    target = None
    logical = None

    m = re.fullmatch(r"source-platforms/([^/]+)/(src/.+)", rel_s)
    if m:
        target = _representative_target("platform", m.group(1))
        logical = m.group(2)

    if target is None:
        m = re.fullmatch(r"source-families/([^/]+)/(src/.+)", rel_s)
        if m:
            target = _representative_target("family", m.group(1))
            logical = m.group(2)

    if target is None:
        m = re.fullmatch(r"source-family-platforms/([^/]+)/([^/]+)/(src/.+)", rel_s)
        if m:
            family, platform, logical = m.groups()
            candidates = {
                ("old", "forge"): "1.20.1-forge",
                ("1.21.X", "neoforge"): "1.21.1-neoforge",
                ("26.X", "neoforge"): "26.3-neoforge",
            }
            target = candidates.get((family, platform))

    if target is None:
        m = re.fullmatch(r"version-src/([^/]+)/(src/.+)", rel_s)
        if m:
            target, logical = m.groups()

    if target and logical:
        props = _props()
        if f"target.{target}.source.family" in props:
            path = resolve_effective_source(target_layout(target, props), props, logical)
            if path is not None and path.is_file():
                return path

    return direct


def resolve_target_source(target: str, logical: str | Path) -> Path:
    """Resolve a logical ``src/...`` path for one concrete target.

    This is the target-aware companion to :func:`resolve_source_path`.  Use it in
    validators when two supported versions intentionally select different source
    views (for example 1.21.X canonical 1.21.11 versus the 1.21.1 downport).
    """
    logical_s = Path(logical).as_posix()
    props = _props()
    if f"target.{target}.source.family" not in props:
        return ROOT / logical_s
    path = resolve_effective_source(target_layout(target, props), props, logical_s)
    return path if path is not None else ROOT / logical_s


def read_source(rel: str | Path, *, encoding: str = "utf-8") -> str:
    return resolve_source_path(rel).read_text(encoding=encoding)


def read_materialized_java(target: str, logical: str | Path) -> str:
    """Read exactly the Java source emitted by Stonecutter for one target.

    A maintained source can contain alternative Minecraft/loader branches. Java
    fixtures and assertions must inspect the *selected* branch rather than raw
    source text, especially after consolidating formerly separate Java files.
    """
    logical_s = Path(logical).as_posix()
    if not logical_s.startswith("src/main/java/") or not logical_s.endswith(".java"):
        raise ValueError(f"Expected src/main/java/...java, got {logical_s!r}")
    props = _props()
    layout = target_layout(target, props)
    source = resolve_effective_source(layout, props, logical_s)
    if source is None:
        raise FileNotFoundError(f"{target}: no effective Java source for {logical_s}")
    downports = (layout.family_downport_root, layout.family_platform_downport_root)
    native = any(source.is_relative_to(root) for root in downports if root is not None)
    with tempfile.TemporaryDirectory(prefix="bcce-selected-java-") as directory:
        result = Path(directory) / logical_s
        _materialize_text_file(
            source, result, logical_relative=logical_s,
            minecraft=props[f"target.{target}.deps.minecraft"],
            family=layout.family, platform=layout.platform,
            preprocess=True, native_source=native,
        )
        return result.read_text(encoding="utf-8")

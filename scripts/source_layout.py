#!/usr/bin/env python3
"""Materialize BuildCraft targets from the maintained five-layer source layout.

The materializer intentionally knows only source ownership and preprocessing:

    source-shared
      < source-families/<family>
      < optional family downport view
      < source-platforms/<loader>
      < source-family-platforms/<family>/<loader>
      < optional family-platform downport view
      < version-src/<target>

Minecraft/API mechanical transforms live under ``scripts/transforms`` and
Stonecutter-style ``//?`` handling lives in ``source_preprocessor.py``.  This
file must not contain BuildCraft-class-specific rewrite logic. Canonical family
Java targets the newest configured Minecraft API; explicit downport views carry
older complete implementations when a small compatibility facade is insufficient.
"""
from __future__ import annotations

from pathlib import Path
import shutil
import sys

SCRIPT_DIR = Path(__file__).resolve().parent
if str(SCRIPT_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPT_DIR))

from source_config import (
    ROOT,
    COMMON_PROPERTIES,
    TARGETS_PROPERTIES,
    GENERATIONS_PROPERTIES,
    TargetLayout,
    configured_layer_paths,
    family_platform_targets,
    family_targets,
    generation_config_paths,
    generation_targets,
    gameplay_target_ids,
    load_generation_properties,
    load_properties,
    platform_targets,
    read_properties,
    target_build_root,
    target_ids,
    target_layout,
)
from source_preprocessor import (
    evaluate_condition,
    is_conditional_text_path,
    preprocess_text,
    source_is_enabled,
    strip_source_condition,
    version_tuple,
)
from transforms import apply_text_transforms, generate_target_files


def _copy_source(source: Path, destination: Path) -> None:
    """Copy source bytes without sharing writable inodes with maintained files."""
    destination.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(source, destination)


def _materialize_text_file(
    source_path: Path,
    output: Path,
    *,
    logical_relative: str,
    minecraft: str,
    family: str,
    platform: str,
    preprocess: bool,
) -> None:
    raw = source_path.read_bytes()
    native_source = False

    if is_conditional_text_path(source_path):
        try:
            decoded = raw.decode("utf-8")
        except UnicodeDecodeError:
            decoded = None
        if decoded is not None:
            decoded, selector = strip_source_condition(decoded)
            native_source = selector is not None
            raw = decoded.encode("utf-8")

    has_directive = b"//?" in raw or b"/*?" in raw

    if preprocess and is_conditional_text_path(source_path) and has_directive:
        try:
            text = raw.decode("utf-8")
        except UnicodeDecodeError as exc:
            raise ValueError(f"{source_path}: conditional file is not UTF-8") from exc
        text = preprocess_text(
            text,
            minecraft=minecraft,
            family=family,
            platform=platform,
            source=str(source_path.relative_to(ROOT)),
        )
        text = apply_text_transforms(
            text, minecraft=minecraft, relative=logical_relative, native_source=native_source
        )
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(text, encoding="utf-8", newline="")
        shutil.copymode(source_path, output)
        return

    # Resource compatibility is a materialization concern across every supported
    # version. Java bootstrap rewrites still begin at 1.21.11, but canonical
    # resources may downport into legacy targets as well.
    is_resource = "/resources/" in f"/{logical_relative.replace(chr(92), '/')}"
    if is_conditional_text_path(source_path) and (
        is_resource or version_tuple(minecraft) >= version_tuple("1.21.11")
    ):
        try:
            text = raw.decode("utf-8")
        except UnicodeDecodeError:
            _copy_source(source_path, output)
            return
        text = apply_text_transforms(
            text, minecraft=minecraft, relative=logical_relative, native_source=native_source
        )
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(text, encoding="utf-8", newline="")
        shutil.copymode(source_path, output)
        return

    # A source selector is metadata and must never leak into the compiled tree,
    # even on older targets where no compatibility transform runs.
    if native_source:
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_bytes(raw)
        shutil.copymode(source_path, output)
        return

    _copy_source(source_path, output)


def _source_enabled_for_target(
    source_path: Path,
    logical_relative: str,
    *,
    minecraft: str,
    family: str,
    platform: str,
) -> bool:
    if not is_conditional_text_path(source_path):
        return True
    raw = source_path.read_bytes()
    if b"//? source" not in raw[:512]:
        return True
    try:
        text = raw.decode("utf-8")
    except UnicodeDecodeError as exc:
        raise ValueError(f"{source_path}: source selector file is not UTF-8") from exc
    return source_is_enabled(text, minecraft=minecraft, family=family, platform=platform)


def effective_source_files(
    layout: TargetLayout,
    properties: dict[str, str],
    relative: str | Path = ".",
) -> dict[str, Path]:
    """Resolve effective files with whole-file source selectors and fallback."""
    minecraft = properties.get(f"target.{layout.target}.deps.minecraft", "").strip()
    if not minecraft:
        raise ValueError(f"{layout.target}: missing deps.minecraft")
    return layout.effective_files(
        relative,
        source_predicate=lambda source_path, logical_relative: _source_enabled_for_target(
            source_path,
            logical_relative,
            minecraft=minecraft,
            family=layout.family,
            platform=layout.platform,
        ),
    )


def resolve_effective_source(
    layout: TargetLayout,
    properties: dict[str, str],
    relative: str | Path,
) -> Path | None:
    # Resolve one logical source without rescanning every file in every layer.
    # The same selector predicate and reverse precedence retain fallback/downports.
    relative_path = Path(relative)
    if relative_path.is_absolute() or ".." in relative_path.parts or relative_path.name == ".bc-source-layer":
        return None
    minecraft = properties.get(f"target.{layout.target}.deps.minecraft", "").strip()
    if not minecraft:
        raise ValueError(f"{layout.target}: missing deps.minecraft")
    rel = relative_path.as_posix()
    for layer in reversed(layout.layers):
        candidate = layer / relative_path
        if candidate.is_file() and _source_enabled_for_target(
            candidate, rel, minecraft=minecraft, family=layout.family, platform=layout.platform
        ):
            return candidate
    return None


def _materialize_extra_text_tree(
    source_root: Path,
    destination_root: Path,
    *,
    minecraft: str,
    family: str,
    platform: str,
    preprocess: bool,
    relative_prefix: str,
) -> None:
    """Materialize an auxiliary source tree through the same preprocess pipeline."""
    if not source_root.exists():
        return
    for source_path in sorted(path for path in source_root.rglob("*") if path.is_file()):
        relative_path = source_path.relative_to(source_root)
        logical_relative = f"{relative_prefix}/{relative_path.as_posix()}"
        _materialize_text_file(
            source_path,
            destination_root / relative_path,
            logical_relative=logical_relative,
            minecraft=minecraft,
            family=family,
            platform=platform,
            preprocess=preprocess,
        )


def materialize_target(
    target: str,
    destination: Path | None = None,
    properties: dict[str, str] | None = None,
    *,
    preprocess: bool = True,
) -> Path:
    """Create one conventional effective source tree for ``target``."""
    props = properties or load_properties()
    layout = target_layout(target, props)
    dest = (destination or (ROOT / "build" / "effective-sources" / target)).resolve()
    if dest.exists():
        shutil.rmtree(dest)
    dest.mkdir(parents=True, exist_ok=True)

    minecraft = props.get(f"target.{target}.deps.minecraft", "").strip()
    if not minecraft:
        raise ValueError(f"{target}: missing deps.minecraft")

    _materialize_extra_text_tree(
        ROOT / "addon-fixture" / "src" / "main" / "java",
        dest / "addon-fixture" / "src" / "main" / "java",
        minecraft=minecraft,
        family=layout.family,
        platform=layout.platform,
        preprocess=preprocess,
        relative_prefix="addon-fixture/src/main/java",
    )

    for relative, source_path in sorted(effective_source_files(layout, props).items()):
        _materialize_text_file(
            source_path,
            dest / relative,
            logical_relative=relative,
            minecraft=minecraft,
            family=layout.family,
            platform=layout.platform,
            preprocess=preprocess,
        )
    generate_target_files(
        dest, minecraft=minecraft, family=layout.family, platform=layout.platform
    )
    return dest


def validate_all_directives(properties: dict[str, str] | None = None) -> None:
    props = properties or load_properties()
    for target in target_ids(props):
        layout = target_layout(target, props)
        minecraft = props[f"target.{target}.deps.minecraft"]
        for relative, source in effective_source_files(layout, props).items():
            raw = source.read_bytes()
            if not is_conditional_text_path(source):
                continue
            try:
                decoded = raw.decode("utf-8")
            except UnicodeDecodeError:
                continue
            decoded, _selector = strip_source_condition(decoded)
            if "//?" not in decoded and "/*?" not in decoded:
                continue
            preprocess_text(
                decoded,
                minecraft=minecraft,
                family=layout.family,
                platform=layout.platform,
                source=f"{target}:{relative}",
            )


if __name__ == "__main__":
    import argparse

    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("target", nargs="?")
    parser.add_argument("--output", type=Path)
    parser.add_argument("--config", type=Path, help="select one independent build generation")
    parser.add_argument("--no-preprocess", action="store_true")
    parser.add_argument("--validate-directives", action="store_true")
    parser.add_argument("--list-targets", action="store_true")
    parser.add_argument("--generation", choices=sorted(generation_config_paths()))
    args = parser.parse_args()

    properties = load_properties(args.config) if args.config else load_properties()
    configured_targets = target_ids(properties)

    if args.target and args.target not in configured_targets:
        parser.error(f"target {args.target!r} is not present in the selected configuration")

    if args.validate_directives:
        validate_all_directives(properties)
        print("Stonecutter-style source directives OK")
    elif args.list_targets:
        targets = generation_targets(properties).get(args.generation, []) if args.generation else configured_targets
        print("\n".join(targets))
    elif args.target:
        print(materialize_target(args.target, args.output, properties, preprocess=not args.no_preprocess))
    else:
        parser.error("target, --list-targets, or --validate-directives is required")

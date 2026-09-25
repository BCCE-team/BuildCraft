#!/usr/bin/env python3
"""Canonical target registry and five-layer source-layout configuration."""
from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
from collections.abc import Callable

ROOT = Path(__file__).resolve().parents[1]
COMMON_PROPERTIES = ROOT / "build-config" / "common.properties"
TARGETS_PROPERTIES = ROOT / "build-config" / "targets.properties"
GENERATIONS_PROPERTIES = ROOT / "build-config" / "generations.properties"
SOURCE_LAYER_MARKER = ".bc-source-layer"


def read_properties(path: Path) -> dict[str, str]:
    result: dict[str, str] = {}
    pending = ""
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = pending + raw
        if line.endswith("\\") and not line.endswith("\\\\"):
            pending = line[:-1]
            continue
        pending = ""
        stripped = line.strip()
        if not stripped or stripped.startswith(("#", "!")):
            continue
        if "=" not in line:
            raise ValueError(f"Invalid property line in {path}: {raw!r}")
        key, value = line.split("=", 1)
        key = key.strip()
        if key in result:
            raise ValueError(f"Duplicate property {key!r} in {path}")
        result[key] = value.strip()
    if pending:
        raise ValueError(f"Dangling property continuation in {path}")
    return result


def _merge_properties(*sources: tuple[Path, dict[str, str]]) -> dict[str, str]:
    result: dict[str, str] = {}
    owners: dict[str, Path] = {}
    for path, values in sources:
        for key, value in values.items():
            if key in result and result[key] != value:
                raise ValueError(
                    f"Conflicting property {key!r}: {owners[key]}={result[key]!r}, {path}={value!r}"
                )
            result[key] = value
            owners[key] = path
    return result


def generation_config_paths() -> dict[str, Path]:
    index = read_properties(GENERATIONS_PROPERTIES)
    generations = [x.strip() for x in index.get("generations", "").split(",") if x.strip()]
    if not generations:
        raise ValueError(f"No generations configured in {GENERATIONS_PROPERTIES}")
    result: dict[str, Path] = {}
    for generation in generations:
        raw = index.get(f"generation.{generation}.config", "").strip()
        if not raw:
            raise ValueError(f"Missing generation.{generation}.config in {GENERATIONS_PROPERTIES}")
        path = (ROOT / raw).resolve()
        if not path.is_file():
            raise ValueError(f"Missing {generation} build selector: {path}")
        result[generation] = path
    return result


def _canonical_properties() -> dict[str, str]:
    if not TARGETS_PROPERTIES.is_file():
        raise ValueError(f"Missing canonical target registry: {TARGETS_PROPERTIES}")
    return _merge_properties(
        (COMMON_PROPERTIES, read_properties(COMMON_PROPERTIES)),
        (TARGETS_PROPERTIES, read_properties(TARGETS_PROPERTIES)),
    )


def _all_target_ids(properties: dict[str, str]) -> list[str]:
    return [item.strip() for item in properties.get("targets", "").split(",") if item.strip()]


def load_generation_properties(path: Path) -> dict[str, str]:
    """Load the canonical registry narrowed to one independent build root."""
    config_path = path.resolve()
    local = read_properties(config_path)
    unexpected = sorted(set(local) - {"generation", "vcsTarget"})
    if unexpected:
        raise ValueError(
            f"{config_path} is a build-root selector, not a target registry; move {unexpected[0]!r} "
            f"to {TARGETS_PROPERTIES.relative_to(ROOT)}"
        )
    generation = local.get("generation", "").strip()
    if not generation:
        raise ValueError(f"Missing generation in {config_path}")
    configs = generation_config_paths()
    if generation not in configs:
        raise ValueError(f"Unknown build generation {generation!r} in {config_path}")
    if configs[generation] != config_path:
        raise ValueError(
            f"{config_path} declares {generation!r}, but {GENERATIONS_PROPERTIES.relative_to(ROOT)} "
            f"points to {configs[generation]}"
        )

    combined = _canonical_properties()
    all_targets = _all_target_ids(combined)
    targets = [
        target for target in all_targets
        if combined.get(f"target.{target}.build.generation", "").strip() == generation
    ]
    if not targets:
        raise ValueError(f"{generation}: canonical target registry contains no targets")

    vcs_target = local.get("vcsTarget", "").strip()
    if not vcs_target:
        raise ValueError(f"Missing vcsTarget in {config_path}")
    if vcs_target not in targets:
        raise ValueError(f"{generation}: vcsTarget {vcs_target!r} is not in {targets}")

    combined["targets"] = ",".join(targets)
    combined["vcsTarget"] = vcs_target
    combined[f"generation.{generation}.targets"] = ",".join(targets)
    combined[f"generation.{generation}.vcsTarget"] = vcs_target
    return combined


def load_properties(path: Path | None = None) -> dict[str, str]:
    """Load one build generation or the complete canonical repository matrix."""
    if path is not None:
        return load_generation_properties(path)

    combined = _canonical_properties()
    all_targets = _all_target_ids(combined)
    if not all_targets:
        raise ValueError(f"No targets configured in {TARGETS_PROPERTIES}")

    seen: set[str] = set()
    for generation, config_path in generation_config_paths().items():
        local = read_properties(config_path)
        if local.get("generation", "").strip() != generation:
            raise ValueError(f"{config_path}: generation must be {generation!r}")
        unexpected = sorted(set(local) - {"generation", "vcsTarget"})
        if unexpected:
            raise ValueError(
                f"{config_path}: per-target property {unexpected[0]!r} belongs in "
                f"{TARGETS_PROPERTIES.relative_to(ROOT)}"
            )
        targets = [
            target for target in all_targets
            if combined.get(f"target.{target}.build.generation", "").strip() == generation
        ]
        if not targets:
            raise ValueError(f"{generation}: canonical target registry contains no targets")
        seen.update(targets)
        vcs_target = local.get("vcsTarget", "").strip()
        if vcs_target not in targets:
            raise ValueError(f"{generation}: vcsTarget {vcs_target!r} is not in {targets}")
        combined[f"generation.{generation}.targets"] = ",".join(targets)
        combined[f"generation.{generation}.vcsTarget"] = vcs_target

    unassigned = [target for target in all_targets if target not in seen]
    if unassigned:
        raise ValueError(f"Targets reference unknown build generations: {unassigned}")
    combined["targets"] = ",".join(all_targets)
    combined["vcsTarget"] = combined.get("behaviorReference", all_targets[0])
    return combined


@dataclass(frozen=True)
class TargetMetadata:
    target: str
    generation: str
    family: str
    platform: str
    minecraft: str
    java_version: int


@dataclass(frozen=True)
class TargetLayout:
    target: str
    generation: str
    family: str
    platform: str
    shared_root: Path
    family_root: Path
    family_downport_root: Path | None
    platform_root: Path
    family_platform_root: Path
    family_platform_downport_root: Path | None
    overlay_root: Path

    @property
    def layers(self) -> tuple[Path, ...]:
        """Resolve the five ownership layers plus optional older-version downport views.

        Canonical family/family-platform sources are written for the newest Minecraft API
        supported by that family. Older targets may insert an explicit downport view after
        the canonical owner, without turning ``version-src`` into the primary source tree.
        """
        roots: list[Path] = [self.shared_root, self.family_root]
        if self.family_downport_root is not None:
            roots.append(self.family_downport_root)
        roots.append(self.platform_root)
        roots.append(self.family_platform_root)
        if self.family_platform_downport_root is not None:
            roots.append(self.family_platform_downport_root)
        roots.append(self.overlay_root)
        return tuple(roots)

    def resolve(self, relative: str | Path) -> Path | None:
        rel = Path(relative)
        for root in reversed(self.layers):
            path = root / rel
            if path.is_file():
                return path
        return None

    def effective_files(
        self,
        relative: str | Path = ".",
        *,
        source_predicate: Callable[[Path, str], bool] | None = None,
    ) -> dict[str, Path]:
        """Return effective logical paths, optionally skipping disabled source variants.

        Layers are visited from least to most specific. If a higher-layer file
        is rejected by ``source_predicate``, the already selected lower-layer
        implementation remains active. This is what lets a maintained
        family/family-platform source carry a whole-file Minecraft-version
        selector while keeping ``version-src`` outside the primary ownership
        mechanism.
        """
        rel = Path(relative)
        result: dict[str, Path] = {}
        for layer in self.layers:
            base = layer / rel
            if not base.exists():
                continue
            for path in base.rglob("*"):
                if not path.is_file() or path.name == SOURCE_LAYER_MARKER:
                    continue
                key = path.relative_to(layer).as_posix()
                if source_predicate is not None and not source_predicate(path, key):
                    continue
                result[key] = path
        return result

    def source_candidates(self, relative: str | Path) -> tuple[Path, ...]:
        rel = Path(relative)
        return tuple(path for layer in self.layers if (path := layer / rel).is_file())


def target_ids(properties: dict[str, str] | None = None) -> list[str]:
    props = properties or load_properties()
    return _all_target_ids(props)


def target_build_profile(target: str, properties: dict[str, str] | None = None) -> str:
    props = properties or load_properties()
    return props.get(f"target.{target}.build.profile", "production").strip() or "production"


def gameplay_target_ids(properties: dict[str, str] | None = None) -> list[str]:
    props = properties or load_properties()
    return [target for target in target_ids(props) if target_build_profile(target, props) not in {"skeleton", "server_foundation"}]


def generation_targets(properties: dict[str, str] | None = None) -> dict[str, list[str]]:
    props = properties or load_properties()
    result: dict[str, list[str]] = {}
    for target in target_ids(props):
        generation = props.get(f"target.{target}.build.generation", "").strip()
        if not generation:
            raise ValueError(f"{target}: missing build generation")
        result.setdefault(generation, []).append(target)
    return result


def target_metadata(target: str, properties: dict[str, str] | None = None) -> TargetMetadata:
    props = properties or load_properties()
    prefix = f"target.{target}."
    generation = props.get(prefix + "build.generation", "").strip()
    family = props.get(prefix + "source.family", "").strip()
    platform = props.get(prefix + "source.platform", "").strip()
    minecraft = props.get(prefix + "deps.minecraft", "").strip()
    raw_java = props.get(prefix + "java.version", "").strip()
    if not all((generation, family, platform, minecraft, raw_java)):
        raise ValueError(
            f"{target}: incomplete target registry metadata; require generation/family/platform/minecraft/java"
        )
    return TargetMetadata(
        target=target,
        generation=generation,
        family=family,
        platform=platform,
        minecraft=minecraft,
        java_version=int(raw_java),
    )


def target_registry(properties: dict[str, str] | None = None) -> tuple[TargetMetadata, ...]:
    props = properties or load_properties()
    return tuple(target_metadata(target, props) for target in target_ids(props))


def target_build_root(target: str, properties: dict[str, str] | None = None) -> Path:
    props = properties or load_properties()
    generation = props.get(f"target.{target}.build.generation", "").strip()
    configs = generation_config_paths()
    if generation not in configs:
        raise ValueError(f"{target}: unknown build generation {generation!r}")
    return configs[generation].parent.resolve()


def target_layout(target: str, properties: dict[str, str] | None = None) -> TargetLayout:
    props = properties or load_properties()
    prefix = f"target.{target}."
    generation = props.get(prefix + "build.generation", "").strip()
    family = props.get(prefix + "source.family", "").strip()
    platform = props.get(prefix + "source.platform", "").strip()
    shared_root = props.get(prefix + "source.shared_root", props.get("common.source.shared_root", "")).strip()
    family_root = props.get(prefix + "source.root", props.get(f"source.family.{family}.root", "")).strip()
    family_downport_root = props.get(prefix + "source.family_downport_root", "").strip()
    platform_root = props.get(prefix + "source.platform_root", props.get(f"source.platform.{platform}.root", "")).strip()
    family_platform_root = props.get(
        prefix + "source.family_platform_root",
        props.get(f"source.family_platform.{family}.{platform}.root", ""),
    ).strip()
    family_platform_downport_root = props.get(prefix + "source.family_platform_downport_root", "").strip()
    overlay_root = props.get(prefix + "source.overlay_root", "").strip()
    if not all((generation, family, platform, shared_root, family_root, platform_root, family_platform_root, overlay_root)):
        raise ValueError(
            f"{target}: incomplete source layout; require generation/family/platform and "
            "shared/family/platform/family-platform/target roots"
        )
    return TargetLayout(
        target=target,
        generation=generation,
        family=family,
        platform=platform,
        shared_root=(ROOT / shared_root).resolve(),
        family_root=(ROOT / family_root).resolve(),
        family_downport_root=(ROOT / family_downport_root).resolve() if family_downport_root else None,
        platform_root=(ROOT / platform_root).resolve(),
        family_platform_root=(ROOT / family_platform_root).resolve(),
        family_platform_downport_root=(ROOT / family_platform_downport_root).resolve() if family_platform_downport_root else None,
        overlay_root=(ROOT / overlay_root).resolve(),
    )


def configured_layer_paths(target: str, properties: dict[str, str] | None = None) -> tuple[Path, ...]:
    return tuple(path.resolve() for path in target_layout(target, properties).layers)


def family_targets(properties: dict[str, str] | None = None) -> dict[str, list[str]]:
    props = properties or load_properties()
    result: dict[str, list[str]] = {}
    for target in target_ids(props):
        result.setdefault(target_layout(target, props).family, []).append(target)
    return result


def platform_targets(properties: dict[str, str] | None = None) -> dict[str, list[str]]:
    props = properties or load_properties()
    result: dict[str, list[str]] = {}
    for target in target_ids(props):
        result.setdefault(target_layout(target, props).platform, []).append(target)
    return result


def family_platform_targets(properties: dict[str, str] | None = None) -> dict[tuple[str, str], list[str]]:
    props = properties or load_properties()
    result: dict[tuple[str, str], list[str]] = {}
    for target in target_ids(props):
        layout = target_layout(target, props)
        result.setdefault((layout.family, layout.platform), []).append(target)
    return result


__all__ = [
    "ROOT", "COMMON_PROPERTIES", "TARGETS_PROPERTIES", "GENERATIONS_PROPERTIES",
    "SOURCE_LAYER_MARKER", "TargetMetadata", "TargetLayout", "read_properties", "generation_config_paths",
    "load_generation_properties", "load_properties", "target_ids", "target_build_profile", "gameplay_target_ids", "target_metadata", "target_registry", "generation_targets",
    "target_build_root", "target_layout", "configured_layer_paths", "family_targets",
    "platform_targets", "family_platform_targets",
]

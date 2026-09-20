"""Target text-transform pipeline used by the generic source materializer."""
from __future__ import annotations

from pathlib import Path

from .java_compat import upgrade_symbols
from .java_symbols import downport_symbols
from .resources import apply_resource_transforms, generate_target_resources


def apply_text_transforms(
    text: str, *, minecraft: str, relative: str, native_source: bool = False
) -> str:
    # Whole-file source variants are maintained directly against the selected
    # Minecraft API and therefore bypass mechanical upgrade transforms.
    # Resource-format transforms remain deterministic and also apply to
    # source-selected resource files.
    if not native_source:
        text = upgrade_symbols(text, minecraft=minecraft, relative=relative)
    else:
        text = downport_symbols(text, minecraft=minecraft, relative=relative)
    return apply_resource_transforms(text, minecraft=minecraft, relative=relative)


def generate_target_files(
    destination_root: Path, *, minecraft: str, family: str, platform: str
) -> int:
    return generate_target_resources(
        destination_root, minecraft=minecraft, family=family, platform=platform
    )


__all__ = ["apply_text_transforms", "generate_target_files"]

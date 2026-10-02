#!/usr/bin/env python3
"""Structural guard for the first 26.1.2 port slice: API + library."""
from pathlib import Path
import sys


ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts"))
from source_config import load_properties, target_layout  # noqa: E402
SHARED_API = ROOT / "source-shared/src/main/java/buildcraft/api"
PORT_ROOT = ROOT / "source-families/26.X"
PORT_LIB = PORT_ROOT / "src/main/java/buildcraft/lib"
TARGETS = ROOT / "build-config/targets.properties"
GENERATION = ROOT / "builds/26.X/targets.properties"
NEOFORGE_BUILD = ROOT / "builds/26.X/build.neoforge.gradle"


def fail(message: str) -> None:
    raise SystemExit(f"26.1.2 API/lib port: {message}")


def main() -> None:
    if not SHARED_API.is_dir():
        fail("the shared public buildcraft.api surface is missing")
    api_overrides = list((PORT_ROOT / "src/main/java/buildcraft/api").rglob("*.java"))
    expected_api_override = PORT_ROOT / "src/main/java/buildcraft/api/v2/recipe/CountedIngredient.java"
    if api_overrides != [expected_api_override]:
        fail("26.X may override only CountedIngredient for the vanilla 26.1.2 Ingredient API")
    if not (PORT_ROOT / "README.md").is_file():
        fail("missing 26.X API/lib ownership note")
    if "target.26.1.2-neoforge.deps.minecraft=26.1.2" not in TARGETS.read_text(encoding="utf-8"):
        fail("26.1.2 NeoForge target is not registered")
    if "generation=26.X" not in GENERATION.read_text(encoding="utf-8"):
        fail("26.X target generation is not registered")
    if "moddev-gradle" not in NEOFORGE_BUILD.read_text(encoding="utf-8"):
        fail("26.X NeoForge build does not use ModDevGradle")

    layout = target_layout("26.1.2-neoforge", load_properties(GENERATION))
    effective = layout.effective_files("src/main/java")
    files = sorted(
        path for relative, path in effective.items()
        if relative.startswith("src/main/java/buildcraft/lib/") and path.suffix == ".java"
    )
    if not files:
        fail("missing 26.X buildcraft.lib baseline")
    for path in files:
        source = path.read_text(encoding="utf-8")
        if "121111" in path.as_posix() or "121111" in source:
            fail(f"stale 1.21.11 compatibility namespace in effective {path.relative_to(ROOT)}")
    for namespace in ("buildcraft/lib/compat/mc2612", "buildcraft/lib/compat/neoforge2612"):
        if not (PORT_LIB / namespace.removeprefix("buildcraft/lib/")).is_dir():
            fail(f"missing versioned compatibility namespace {namespace}")

    print(f"26.1.2 API/lib port OK: shared API, {len(files)} 26.X library files")


if __name__ == "__main__":
    main()

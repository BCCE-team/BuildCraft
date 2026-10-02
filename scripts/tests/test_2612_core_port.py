#!/usr/bin/env python3
"""Structural guard for the 26.1.2 core gameplay port."""
from pathlib import Path
import sys


ROOT = Path(__file__).resolve().parents[2]
PORT_ROOT = ROOT / "source-families/26.X/src/main/java"
TARGET_BUILD = ROOT / "build-logic/loaders/neoforge-target.gradle"
CORE_STUBS = ROOT / "version-src/26.1.2-neoforge/core-port-stubs/src/main/java"


def fail(message: str) -> None:
    raise SystemExit(f"26.1.2 core port: {message}")


def require_source(relative: str, fragment: str) -> None:
    path = PORT_ROOT / relative
    if not path.is_file():
        fail(f"missing 26.X override {relative}")
    if fragment not in path.read_text(encoding="utf-8"):
        fail(f"{relative} does not contain the 26.1.2 adaptation")


def main() -> None:
    require_source("buildcraft/core/list/GuiList.java", "GuiGraphicsExtractor")
    require_source("buildcraft/core/client/render/RenderEngine_BC8.java", "state.level.CameraRenderState")
    require_source("buildcraft/core/BCCoreRecipes.java", "protected void buildRecipes()")
    require_source("buildcraft/core/marker/PathSavedData.java", "Identifier.withDefaultNamespace(NAME)")
    require_source("buildcraft/core/marker/VolumeSavedData.java", "Identifier.withDefaultNamespace(NAME)")
    require_source("buildcraft/core/marker/volume/WorldSavedDataVolumeBoxes.java", "Identifier.withDefaultNamespace(DATA_NAME)")
    require_source("buildcraft/core/blockEntity/TileEngineCreative.java", "sendOverlayMessage")
    require_source("buildcraft/robotics/zone/ZonePlan.java", "chunkPos.x()")

    required_stubs = (
        "buildcraft/energy/BCEnergyFluids.java",
        "buildcraft/energy/tile/TileSpringOil.java",
        "buildcraft/energy/tile/TileEngineStone_BC8.java",
        "buildcraft/energy/tile/TileEngineIron_BC8.java",
        "buildcraft/energy/tile/TileEngineFE.java",
    )
    for relative in required_stubs:
        if not (CORE_STUBS / relative).is_file():
            fail(f"missing compile-only bridge {relative}")

    build = TARGET_BUILD.read_text(encoding="utf-8")
    for fragment in ("corePortStubs", "buildcraft/core/**", "buildcraft/robotics/zone/ZonePlan.java", "verifyCorePort"):
        if fragment not in build:
            fail(f"Gradle core verifier is missing {fragment}")

    print("26.1.2 core port OK: target overrides, zones and compile-only boundaries")


if __name__ == "__main__":
    main()

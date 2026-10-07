#!/usr/bin/env python3
"""Validate Zone Planner terrain-preview behaviour on every maintained target."""
from __future__ import annotations

from pathlib import Path
import tempfile

from source_layout import load_properties, materialize_target
from source_lookup import resolve_target_source

ROOT = Path(__file__).resolve().parents[1]
TARGETS = (
    "1.19.2-forge",
    "1.20.1-forge",
    "1.21.1-neoforge",
    "1.21.11-neoforge",
    "26.1.2-neoforge",
)
MODERN_CAPTURE_TARGETS = {"1.21.11-neoforge", "26.1.2-neoforge"}


def read(target: str, relative: str) -> str:
    return resolve_target_source(target, relative).read_text(encoding="utf-8")


def require(target: str, source: str, *needles: str) -> None:
    missing = [needle for needle in needles if needle not in source]
    if missing:
        raise AssertionError(f"{target}: Zone Planner preview lost {missing}")


def validate_target(target: str) -> None:
    renderer = read(target, "src/main/java/buildcraft/robotics/client/render/RenderZonePlanner.java")
    require(
        target,
        renderer,
        "TEXTURE_WIDTH = 10",
        "TEXTURE_HEIGHT = 8",
        "BLOCKS_PER_PIXEL = 4",
        "new ZonePlannerMapChunk(",
        ".maximumSize(256)",
        "private final Level level;",
        "level == other.level",
        "System.identityHashCode(key.level)",
        "colours[textureY * TEXTURE_WIDTH + textureX] = MAP_BACKGROUND_COLOUR;",
    )
    if "expireAfterAccess(" in renderer:
        raise AssertionError(f"{target}: live preview texture must not expire underneath a cached render state")
    if "ZonePlannerMapDataClient" in renderer or "MessageZoneMapRequest" in renderer:
        raise AssertionError(f"{target}: block preview must not use remote GUI-map requests")

    if target == "26.1.2-neoforge":
        require(target, renderer, ".setLight(0xF000F0)")
    else:
        require(target, renderer, "LightTexture.FULL_BRIGHT")

    if target in MODERN_CAPTURE_TARGETS:
        require(
            target,
            renderer,
            "RenderCompat.entityCutoutNoCull(texture)",
            "collector.submitCustomGeometry",
            "BlockEntityRenderer<TileZonePlanner, RenderZonePlanner.ZonePlannerRenderState>",
            "pixels.setPixel(x, y, colours[",
        )
        if "argbToAbgr" in renderer:
            raise AssertionError(f"{target}: block preview must write native ARGB pixels directly")
    else:
        require(target, renderer, "RenderType.entityCutoutNoCull(preview.location)")

    if target == "1.21.11-neoforge":
        require(target, renderer, "level.dimension().identifier().toString()")
    else:
        require(target, renderer, "level.dimension().location().hashCode()")

    bootstrap = read(target, "src/main/java/buildcraft/robotics/BCRobotics.java")
    require(target, bootstrap, "BCRoboticsClientRenderers.register(PlatformClientRegistration.renderers(event));")
    renderers = read(target, "src/main/java/buildcraft/robotics/BCRoboticsClientRenderers.java")
    require(
        target,
        renderers,
        "registry.registerBlockEntityRenderer(BCRoboticsBlocks.ZONE_PLANNER_TILE.get(), RenderZonePlanner::new);",
    )



def validate_1_21_11_effective_source() -> None:
    """Keep the effective 1.21.11 ARGB/BER parity assertions from the old dedicated check."""
    props = load_properties()
    with tempfile.TemporaryDirectory(prefix="bcce-zone-planner-1.21.11-") as tmp:
        out = Path(tmp) / "effective"
        materialize_target("1.21.11-neoforge", out, props)
        src = out / "src/main/java"
        chunk = (src / "buildcraft/robotics/zone/ZonePlannerMapChunk.java").read_text(encoding="utf-8")
        gui = (src / "buildcraft/robotics/gui/GuiZonePlanner.java").read_text(encoding="utf-8")
        renderer = (src / "buildcraft/robotics/client/render/RenderZonePlanner.java").read_text(encoding="utf-8")

        require(
            "1.21.11-neoforge",
            chunk,
            "calculateARGBColor(brightness)",
            "new MapColourData(current.posY, mapColour)",
        )
        if "int nativeMapColour = 0" in chunk or "calculateRGBColor" in chunk:
            raise AssertionError("1.21.11-neoforge: map colours regressed from native ARGB")
        require(
            "1.21.11-neoforge",
            gui,
            "fillNativeImage(",
            "mapTexture.upload()",
            "RenderCompat.blit(guiGraphics, TEXTURE_MAP",
        )
        if "argbToAbgr(colour)" in gui:
            raise AssertionError("1.21.11-neoforge: GUI map path must write native ARGB directly")
        require(
            "1.21.11-neoforge",
            renderer,
            "BlockEntityRenderer<TileZonePlanner, RenderZonePlanner.ZonePlannerRenderState>",
            "collector.submitCustomGeometry",
            "pixels.setPixel(x, y, colours[",
            "TEXTURE_WIDTH = 10",
            "TEXTURE_HEIGHT = 8",
        )
        if "argbToAbgr" in renderer:
            raise AssertionError("1.21.11-neoforge: block preview must write native ARGB pixels directly")


def main() -> int:
    errors: list[str] = []
    for target in TARGETS:
        try:
            validate_target(target)
        except (AssertionError, FileNotFoundError) as exc:
            errors.append(str(exc))

    try:
        validate_1_21_11_effective_source()
    except (AssertionError, FileNotFoundError) as exc:
        errors.append(str(exc))

    if errors:
        print("Zone Planner preview validation FAILED:")
        for error in errors:
            print(f" - {error}")
        return 1

    print(f"Zone Planner preview parity OK: local 10x8 terrain preview validated on {len(TARGETS)} targets")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

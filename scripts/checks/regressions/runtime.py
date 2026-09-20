#!/usr/bin/env python3
"""Guard gameplay and rendering invariants across the API2 runtime boundary."""
from __future__ import annotations

import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
SCRIPT_DIR = ROOT / "scripts"
if str(SCRIPT_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPT_DIR))
from source_lookup import resolve_source_path, resolve_target_source


def fail(message: str) -> None:
    print(f"ERROR: {message}", file=sys.stderr)
    raise SystemExit(1)


def read(rel: str) -> str:
    path = resolve_source_path(rel)
    if not path.is_file():
        fail(f"missing required file: {rel}")
    return path.read_text(encoding="utf-8")


def require(text: str, needle: str, rel: str) -> None:
    if needle not in text:
        fail(f"missing {needle!r} in {rel}")


def reject(text: str, needle: str, rel: str) -> None:
    if needle in text:
        fail(f"forbidden regression marker {needle!r} remains in {rel}")


def validate_zone_planner() -> None:
    variants = [
        "version-src/1.19.2-forge/src/main/java/buildcraft/robotics/zone/ZonePlannerMapChunk.java",
        "source-shared/src/main/java/buildcraft/robotics/zone/ZonePlannerMapChunk.java",
    ]
    guis = [
        "version-src/1.19.2-forge/src/main/java/buildcraft/robotics/gui/GuiZonePlanner.java",
        "version-src/1.20.1-forge/src/main/java/buildcraft/robotics/gui/GuiZonePlanner.java",
    ]
    for rel in variants:
        text = read(rel)
        require(text, "new MapColourData(current.posY, mapNativeToArgb(nativeMapColour))", rel)
        require(text, "chunk.getHeight(Heightmap.Types.WORLD_SURFACE, localX, localZ)", rel)
        require(text, "getChunkNow(key.chunkPos.x, key.chunkPos.z - 1)", rel)
        require(text, "mapNativeToArgb(int nativeMapColour)", rel)
        reject(text, "chunk.getHeight(Heightmap.Types.WORLD_SURFACE, localX, localZ) - 1", rel)
        reject(text, "new MapColourData(current.posY, nativeMapColour)", rel)
    for rel in guis:
        require(read(rel), "argbToAbgr(colour)", rel)

    modern_logical = "src/main/java/buildcraft/robotics/gui/GuiZonePlanner.java"
    modern_1211_path = resolve_target_source("1.21.1-neoforge", modern_logical)
    modern_1211 = modern_1211_path.read_text(encoding="utf-8")
    require(modern_1211, "argbToAbgr(colour)", "1.21.1 effective GuiZonePlanner")

    modern_12111_path = resolve_target_source("1.21.11-neoforge", modern_logical)
    modern_12111 = modern_12111_path.read_text(encoding="utf-8")
    require(modern_12111, "fillNativeImage(", "1.21.11 effective GuiZonePlanner")
    reject(modern_12111, "argbToAbgr(colour)", "1.21.11 effective GuiZonePlanner")


def validate_gui_gears() -> None:
    # 1.19.2 already uses the original item-render + blend sequence; 1.20.1/modern use GuiGraphics.
    checks = [
        (
            "version-src/1.19.2-forge/src/main/java/buildcraft/energy/client/gui/GuiEngineFE.java",
            "itemRenderer.renderAndDecorateItem(new ItemStack(BCCoreItems.GEAR_IRON.get()), leftPos + 78, topPos + 22)",
            "itemRenderer.renderAndDecorateItem(new ItemStack(BCCoreItems.GEAR_GOLD.get()), leftPos + 101, topPos + 22)",
        ),
        (
            "version-src/1.19.2-forge/src/main/java/buildcraft/energy/client/gui/GuiDynamoMJ.java",
            "itemRenderer.renderAndDecorateItem(new ItemStack(BCCoreItems.GEAR_IRON.get()), leftPos + 60, topPos + 22)",
            "itemRenderer.renderAndDecorateItem(new ItemStack(BCCoreItems.GEAR_GOLD.get()), leftPos + 83, topPos + 22)",
        ),
        (
            "version-src/1.20.1-forge/src/main/java/buildcraft/energy/client/gui/GuiEngineFE.java",
            "guiGraphics.renderItem(new ItemStack(BCCoreItems.GEAR_IRON.get()), leftPos + 78, topPos + 22)",
            "guiGraphics.renderItem(new ItemStack(BCCoreItems.GEAR_GOLD.get()), leftPos + 101, topPos + 22)",
        ),
        (
            "version-src/1.20.1-forge/src/main/java/buildcraft/energy/client/gui/GuiDynamoMJ.java",
            "guiGraphics.renderItem(new ItemStack(BCCoreItems.GEAR_IRON.get()), leftPos + 60, topPos + 22)",
            "guiGraphics.renderItem(new ItemStack(BCCoreItems.GEAR_GOLD.get()), leftPos + 83, topPos + 22)",
        ),
        (
            "source-families/modern/src/main/java/buildcraft/energy/client/gui/GuiEngineFE.java",
            "guiGraphics.renderItem(new ItemStack(BCCoreItems.GEAR_IRON.get()), leftPos + 78, topPos + 22)",
            "guiGraphics.renderItem(new ItemStack(BCCoreItems.GEAR_GOLD.get()), leftPos + 101, topPos + 22)",
        ),
        (
            "source-families/modern/src/main/java/buildcraft/energy/client/gui/GuiDynamoMJ.java",
            "guiGraphics.renderItem(new ItemStack(BCCoreItems.GEAR_IRON.get()), leftPos + 60, topPos + 22)",
            "guiGraphics.renderItem(new ItemStack(BCCoreItems.GEAR_GOLD.get()), leftPos + 83, topPos + 22)",
        ),
    ]
    for rel, iron, gold in checks:
        text = read(rel)
        require(text, iron, rel)
        require(text, gold, rel)
        for state in (
            "RenderSystem.enableDepthTest();",
            "RenderSystem.enableBlend();",
            "RenderSystem.defaultBlendFunc();",
            "RenderSystem.disableDepthTest();",
            "RenderSystem.disableBlend();",
        ):
            require(text, state, rel)
        gear_pos = min(text.index(iron), text.index(gold))
        blend_pos = text.index("RenderSystem.enableBlend();", gear_pos)
        overlay_pos = text.index("OVERLAY.drawAt", blend_pos)
        if not (gear_pos < blend_pos < overlay_pos):
            fail(f"gear/overlay render order regressed in {rel}")


def atlas_resources(rel: str) -> set[str]:
    data = json.loads(read(rel))
    if not isinstance(data, dict) or not isinstance(data.get("sources"), list):
        fail(f"invalid atlas structure: {rel}")
    return {
        source.get("resource")
        for source in data["sources"]
        if isinstance(source, dict) and isinstance(source.get("resource"), str)
    }


def validate_texture_stitching() -> None:
    pipe_dir = ROOT / "source-shared/src/main/resources/assets/buildcrafttransport/textures/pipes"
    expected_pipe = {
        f"buildcrafttransport:pipes/{path.stem}"
        for path in pipe_dir.glob("*fe*.png")
    }
    if not expected_pipe:
        fail("no FE pipe textures found in source-shared resources")
    expected_energy = {
        "buildcraftenergy:blocks/engine/fe/back",
        "buildcraftenergy:blocks/engine/fe/side",
        "buildcraftenergy:blocks/mj_dynamo/back",
        "buildcraftenergy:blocks/mj_dynamo/front",
        "buildcraftenergy:blocks/mj_dynamo/side",
    }

    for rel in (
        "source-families/modern/src/main/resources/assets/minecraft/atlases/blocks.json",
    ):
        resources = atlas_resources(rel)
        missing = sorted((expected_pipe | expected_energy) - resources)
        if missing:
            fail(f"{rel} is missing static block-atlas resources: {missing}")

    # 1.19.2 uses Forge's dynamic stitch event instead of atlases/blocks.json.
    transport = read("version-src/1.19.2-forge/src/main/java/buildcraft/transport/BCTransportSprites.java")
    require(transport, "PipeBaseModelGenStandard.INSTANCE.onTextureStitchPre(event);", "1.19.2 BCTransportSprites")
    require(transport, 'event.addSprite(new ResourceLocation("buildcrafttransport:pipes/fe_flow"));', "1.19.2 BCTransportSprites")
    require(transport, 'event.addSprite(new ResourceLocation("buildcrafttransport:pipes/fe_top"));', "1.19.2 BCTransportSprites")
    standard = read("version-src/1.19.2-forge/src/main/java/buildcraft/transport/client/model/PipeBaseModelGenStandard.java")
    require(standard, "event.addSprite(name);", "PipeBaseModelGenStandard")

    energy = read("version-src/1.19.2-forge/src/main/java/buildcraft/energy/BCEnergySprites.java")
    for symbol in ("FE_BACK_R", "FE_SIDE_R", "DYNAMO_BACK_R", "DYNAMO_FRONT_R", "DYNAMO_SIDE_R"):
        require(energy, f"event.addSprite({symbol});", "1.19.2 BCEnergySprites")


def validate_engine_pipe_handshake() -> None:
    for rel in (
        "source-platforms/forge/src/main/java/buildcraft/lib/engine/TileEngineBase_BC8.java",
        "source-platforms/neoforge/src/main/java/buildcraft/lib/engine/TileEngineBase_BC8.java",
    ):
        text = read(rel)
        for needle in (
            "Direction previousDirection = currentDirection;",
            "level.neighborChanged(worldPosition.relative(previousDirection), sourceBlock, worldPosition);",
            "level.neighborChanged(worldPosition.relative(current), sourceBlock, worldPosition);",
            "protected boolean isFacingReceiver(Direction dir)",
            "return getPortToPower(dir) != null;",
        ):
            require(text, needle, rel)
    shared_block = read("source-shared/src/main/java/buildcraft/lib/engine/BlockEngineBase_BC8.java")
    require(shared_block, "engine.rotateIfInvalid();", "BlockEngineBase_BC8")


def main() -> None:
    validate_zone_planner()
    validate_gui_gears()
    validate_texture_stitching()
    validate_engine_pipe_handshake()
    print("Runtime regression batch OK: 1.19.2 Forge, 1.20.1 Forge, 1.21.1 NeoForge")


if __name__ == "__main__":
    main()

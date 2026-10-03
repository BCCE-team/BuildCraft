#!/usr/bin/env python3
"""Structural guard for the 26.1.2 BuildCraft robotics primary port."""

from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
OVERLAY = ROOT / "version-src" / "26.1.2-neoforge" / "src" / "main" / "java"
BUILD = ROOT / "build-logic" / "loaders" / "neoforge-target.gradle"


def fail(message: str) -> None:
    raise SystemExit(f"26.1.2 robotics port: {message}")


def require(relative: str, fragment: str) -> None:
    path = OVERLAY / relative
    if not path.is_file():
        fail(f"missing 26.1.2 variant {relative}")
    if fragment not in path.read_text(encoding="utf-8"):
        fail(f"{relative} does not contain the 26.1.2 adaptation")


def main() -> None:
    require("buildcraft/robotics/SimpleRobotRegistryProvider.java", "SavedDataCompat.migrateLegacyFlatFile")
    require("buildcraft/robotics/SimpleRobotRegistryProvider.java", "Identifier.withDefaultNamespace(DATA_NAME)")
    require("buildcraft/robotics/SimpleRobotRegistryProvider.java", "new ChunkPos(station.x() >> 4, station.z() >> 4)")
    require("buildcraft/robotics/container/ContainerRequester.java", "ContainerInput")
    require("buildcraft/robotics/ai/AIRobotSearchBlock.java", "chunkPos.x()")
    require("buildcraft/robotics/tile/TileZonePlanner.java", "getChunkNow(chunkPos.x(), chunkPos.z())")
    require("buildcraft/robotics/zone/MessageZoneMapRequest.java", "chunkPos.x()")
    require("buildcraft/robotics/zone/MessageZoneMapRequest.java", "getBlockPos().getX() >> 4")
    require("buildcraft/robotics/zone/ZonePlannerMapChunk.java", "chunkPos.x()")
    require("buildcraft/robotics/zone/ZonePlannerMapDataServer.java", "chunkPos.x()")
    require("buildcraft/robotics/client/render/RenderRobot.java", "state.level.CameraRenderState")
    require("buildcraft/robotics/client/render/RenderRobot.java", "0xF000F0")
    require("buildcraft/robotics/client/render/RenderZonePlanner.java", "state.level.CameraRenderState")
    require("buildcraft/robotics/gui/GuiRequester.java", "inv, title, SIZE_X, SIZE_Y")
    require("buildcraft/robotics/gui/GuiZonePlanner.java", "GuiGraphicsExtractor")
    require("buildcraft/robotics/gui/GuiZonePlanner.java", "BCGuiInput.character")
    require("buildcraft/robotics/gui/GuiZonePlanner.java", "BCGraphics.text")
    require("buildcraft/robotics/gui/GuiZonePlanner.java", "GuiUtil.drawItemStackAt")
    require("buildcraft/robotics/gui/GuiZonePlanner.java", "extractContents")

    build = BUILD.read_text(encoding="utf-8")
    for fragment in (
        "roboticsPort",
        "buildcraft/robotics/**",
        "sourceSets.siliconPort.output",
        "verifyRoboticsPort",
    ):
        if fragment not in build:
            fail(f"Gradle robotics verifier is missing {fragment}")

    robotics_source_set = build.split("roboticsPort {", 1)[1].split("\n    }", 1)[0]
    if "sourceSets.siliconPortStubs.output" in robotics_source_set:
        fail("temporary Silicon robotics stubs leaked into the Robotics runtime graph")

    print("26.1.2 robotics primary-port structure OK: SavedData migration, zone planner, GUI extraction and real Silicon linkage")


if __name__ == "__main__":
    main()

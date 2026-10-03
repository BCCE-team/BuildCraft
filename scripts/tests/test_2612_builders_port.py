#!/usr/bin/env python3
"""Structural guard for the 26.1.2 BuildCraft builders gameplay port."""
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
FAMILY = ROOT / "source-families/26.X/src/main/java"
PLATFORM = ROOT / "source-family-platforms/26.X/neoforge/src/main/java"
STUBS = ROOT / "version-src/26.1.2-neoforge/builders-port-stubs/src/main/java"
BUILD = ROOT / "build-logic/loaders/neoforge-target.gradle"


def fail(message: str) -> None:
    raise SystemExit(f"26.1.2 builders port: {message}")


def require(root: Path, relative: str, fragment: str) -> None:
    path = root / relative
    if not path.is_file():
        fail(f"missing 26.1.2 variant {relative}")
    if fragment not in path.read_text(encoding="utf-8"):
        fail(f"{relative} does not contain the 26.1.2 adaptation")


def main() -> None:
    require(FAMILY, "buildcraft/builders/gui/GuiArchitectTable.java", "GuiGraphicsExtractor")
    require(FAMILY, "buildcraft/builders/gui/GuiArchitectTable.java", "extractWidgetRenderState")
    require(FAMILY, "buildcraft/builders/gui/GuiFiller.java", "inv, title, 176, 241")
    require(FAMILY, "buildcraft/builders/gui/GuiBuilder.java", "SIZE_BLUEPRINT_X, SIZE_Y")
    require(FAMILY, "buildcraft/builders/gui/GuiElectronicLibrary.java", "SIZE_X, SIZE_Y")
    require(FAMILY, "buildcraft/builders/gui/ScreenReplacer.java", "extends GuiBC8<MenuReplacer>")
    require(FAMILY, "buildcraft/builders/gui/ScreenReplacer.java", "extractTooltip(GuiGraphicsExtractor")
    require(FAMILY, "buildcraft/builders/gui/ScreenReplacer.java", "shouldAddOwnerLedger")
    require(FAMILY, "buildcraft/builders/client/render/RenderBuilder.java", "state.level.CameraRenderState")
    require(FAMILY, "buildcraft/builders/snapshot/FakeWorld.java", "dayTimeFraction = 0.0F")
    require(FAMILY, "buildcraft/builders/BCBuildersSchematics.java", "BuiltInRegistries.ITEM.get")

    require(PLATFORM, "buildcraft/builders/menu/ContainerBuilder.java", "ContainerInput")
    require(PLATFORM, "buildcraft/builders/item/ItemSchematicSingle.java", "sendOverlayMessage")
    require(PLATFORM, "buildcraft/builders/tile/TileQuarry.java", "minChunkPos.x()")
    require(PLATFORM, "buildcraft/builders/snapshot/SchematicEntityDefault.java", "TagValueInput.create")
    require(PLATFORM, "buildcraft/builders/snapshot/SchematicEntityDefault.java", "BlockGetter")

    blueprint = ROOT / "source-platforms/neoforge/src/main/java/buildcraft/builders/snapshot/BlueprintBuilder.java"
    blueprint_text = blueprint.read_text(encoding="utf-8")
    for fragment in ("AutomationPermissionUtil.mayBlock", "SOURCE_ROBOT", "FakePlayerProvider.INSTANCE", "RobotBuildResult.NOOP"):
        if fragment not in blueprint_text:
            fail(f"robot construction lost owner-aware permission path: {fragment}")

    for relative in (
        "buildcraft/robotics/internal/legacy/robots/EntityRobotBase.java",
        "buildcraft/robotics/internal/legacy/robots/IRobotRegistry.java",
        "buildcraft/robotics/internal/legacy/robots/ResourceIdBlock.java",
        "buildcraft/robotics/entity/EntityRobot.java",
    ):
        if not (STUBS / relative).is_file():
            fail(f"missing robotics compile-only bridge {relative}")

    build = BUILD.read_text(encoding="utf-8")
    for fragment in (
        "buildersPortStubs",
        "buildersPort",
        "buildcraft/builders/**",
        "sourceSets.factoryPort.output",
        "verifyBuildersPort",
    ):
        if fragment not in build:
            fail(f"Gradle builders verifier is missing {fragment}")

    print("26.1.2 builders primary-port structure OK: GUI extraction, snapshots, robots boundary and factory linkage")


if __name__ == "__main__":
    main()

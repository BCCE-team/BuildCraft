#!/usr/bin/env python3
"""Structural guard for the 26.1.2 BuildCraft factory gameplay port."""
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
FAMILY = ROOT / "source-families/26.X/src/main/java"
PLATFORM = ROOT / "source-family-platforms/26.X/neoforge/src/main/java"
BUILD = ROOT / "build-logic/loaders/neoforge-target.gradle"


def fail(message: str) -> None:
    raise SystemExit(f"26.1.2 factory port: {message}")


def require(root: Path, relative: str, fragment: str) -> None:
    path = root / relative
    if not path.is_file():
        fail(f"missing 26.X override {relative}")
    if fragment not in path.read_text(encoding="utf-8"):
        fail(f"{relative} does not contain the 26.1.2 adaptation")


def main() -> None:
    require(FAMILY, "buildcraft/factory/client/gui/ScreenHeatExchange.java", "GuiGraphicsExtractor")
    require(FAMILY, "buildcraft/factory/client/gui/ScreenHeatExchange.java", "extractLabels")
    require(FAMILY, "buildcraft/factory/gui/GuiAutoCraftItems.java", "ContainerInput")
    require(FAMILY, "buildcraft/factory/gui/GuiAutoCraftItems.java", "extractContents")
    require(FAMILY, "buildcraft/factory/gui/GuiAutoCraftItems.java", "fakeItem(filterStack")
    require(FAMILY, "buildcraft/factory/gui/GuiChute.java", "title, SIZE_X, SIZE_Y")

    require(PLATFORM, "buildcraft/factory/client/render/RenderTank.java", "state.level.CameraRenderState")
    require(PLATFORM, "buildcraft/factory/gui/GuiTank.java", "GuiGraphicsExtractor")
    require(PLATFORM, "buildcraft/factory/gui/GuiTank.java", "extractTooltip")
    require(PLATFORM, "buildcraft/factory/tile/TileMiner.java", "level.getRandom().nextInt(10)")
    require(PLATFORM, "buildcraft/factory/block/BlockWaterGel.java", "world.getRandom().nextDouble()")

    tank_gui = PLATFORM / "buildcraft/factory/gui/GuiTank.java"
    if "RenderSystem" in tank_gui.read_text(encoding="utf-8"):
        fail("tank GUI still mutates immediate render state")

    build = BUILD.read_text(encoding="utf-8")
    for fragment in (
        "factoryPort",
        "buildcraft/factory/**",
        "sourceSets.transportPort.output",
        "BCFactoryRecipesProvider.java",
        "verifyFactoryPort",
    ):
        if fragment not in build:
            fail(f"Gradle factory verifier is missing {fragment}")

    print("26.1.2 factory primary-port structure OK: machines, GUI extraction, tank renderer and transport linkage")


if __name__ == "__main__":
    main()

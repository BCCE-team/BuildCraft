#!/usr/bin/env python3
"""Structural guard for the 26.1.2 BuildCraft energy gameplay port."""
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
FAMILY = ROOT / "source-families/26.X/src/main/java"
PLATFORM = ROOT / "source-family-platforms/26.X/neoforge/src/main/java"
BUILD = ROOT / "build-logic/loaders/neoforge-target.gradle"
STUBS = ROOT / "version-src/26.1.2-neoforge/energy-port-stubs/src/main/java"


def fail(message: str) -> None:
    raise SystemExit(f"26.1.2 energy port: {message}")


def require(root: Path, relative: str, fragment: str) -> None:
    path = root / relative
    if not path.is_file():
        fail(f"missing 26.X override {relative}")
    if fragment not in path.read_text(encoding="utf-8"):
        fail(f"{relative} does not contain the 26.1.2 adaptation")


def main() -> None:
    require(FAMILY, "buildcraft/energy/client/gui/GuiDynamoMJ.java", "GuiGraphicsExtractor")
    require(FAMILY, "buildcraft/energy/client/gui/GuiEngineFE.java", "GuiGraphicsExtractor")
    require(FAMILY, "buildcraft/energy/client/gui/GuiEngineIron_BC8.java", "SIZE_X, SIZE_Y")
    require(FAMILY, "buildcraft/energy/client/gui/GuiEngineStone_BC8.java", "SIZE_X, SIZE_Y")
    require(FAMILY, "buildcraft/energy/client/gui/LedgerDynamoMJ.java", "GuiGraphicsExtractor")
    require(FAMILY, "buildcraft/energy/client/render/RenderDynamoMJ.java", "state.level.CameraRenderState")
    require(FAMILY, "buildcraft/energy/generation/features/OilGenFeature.java", "chunkPos.x()")
    require(PLATFORM, "buildcraft/energy/BCEnergyClientProxy.java", "fluidFogColor.set")

    for relative in (
        "buildcraft/energy/client/gui/GuiDynamoMJ.java",
        "buildcraft/energy/client/gui/GuiEngineFE.java",
    ):
        contents = (FAMILY / relative).read_text(encoding="utf-8")
        if "RenderPipelines.GUI_TEXTURED" not in contents or "0xA6FFFFFF" not in contents:
            fail(f"{relative} does not queue the translucent overlay")
        if "import com.mojang.blaze3d.systems.RenderSystem" in contents:
            fail(f"{relative} mutates immediate render state instead of the extractor")

    marker = STUBS / "buildcraft/transport/internal/pipe/IItemPipe.java"
    if not marker.is_file() or "interface IItemPipe" not in marker.read_text(encoding="utf-8"):
        fail("missing compile-only transport item-pipe marker")

    build = BUILD.read_text(encoding="utf-8")
    for fragment in (
        "energyPortStubs",
        "energyPort",
        "buildcraft/energy/**",
        "sourceSets.corePort.output",
        "verifyEnergyPort",
    ):
        if fragment not in build:
            fail(f"Gradle energy verifier is missing {fragment}")

    print("26.1.2 energy primary-port structure OK: GUI, renderer, worldgen, fog and transport boundary")


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""Structural guard for the 26.1.2 BuildCraft transport gameplay port."""
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
FAMILY = ROOT / "source-families/26.X/src/main/java"
PLATFORM = ROOT / "source-family-platforms/26.X/neoforge/src/main/java"
BUILD = ROOT / "build-logic/loaders/neoforge-target.gradle"
STUBS = ROOT / "version-src/26.1.2-neoforge/transport-port-stubs/src/main/java"


def fail(message: str) -> None:
    raise SystemExit(f"26.1.2 transport port: {message}")


def require(root: Path, relative: str, fragment: str) -> None:
    path = root / relative
    if not path.is_file():
        fail(f"missing 26.X override {relative}")
    if fragment not in path.read_text(encoding="utf-8"):
        fail(f"{relative} does not contain the 26.1.2 adaptation")


def main() -> None:
    require(FAMILY, "buildcraft/transport/client/PipeBlockColours.java", "BlockTintSource")
    require(FAMILY, "buildcraft/transport/client/render/RenderPipeHolder.java", "state.level.CameraRenderState")
    require(FAMILY, "buildcraft/transport/recipe/PipeRecipe.java", "assemble(CraftingInput input)")
    require(FAMILY, "buildcraft/transport/wire/WorldSavedDataWireSystems.java", "Identifier.withDefaultNamespace(DATA_NAME)")
    require(FAMILY, "buildcraft/transport/wire/WireSystem.java", "element.blockPos.getX() >> 4")
    require(FAMILY, "buildcraft/transport/pipe/behaviour/PipeBehaviourLimiter.java", "sendOverlayMessage")

    require(PLATFORM, "buildcraft/transport/BCTransportEventDist.java", "RegisterColorHandlersEvent.BlockTintSources")
    require(PLATFORM, "buildcraft/transport/tile/TilePipeHolderModelData.java", "ModelPipeNative2612.buildModelData")
    require(PLATFORM, "buildcraft/transport/client/model/ModelPipeNative2612.java", "BlockStateModelPart")
    require(PLATFORM, "buildcraft/transport/client/model/ModelPipeNative2612.java", "BakedQuad.MaterialInfo")

    native_model = PLATFORM / "buildcraft/transport/client/model/ModelPipeNative2612.java"
    if "mc121111" in native_model.read_text(encoding="utf-8"):
        fail("native pipe model still references the pre-26 quad facade")

    for relative in (
        "buildcraft/builders/internal/schematic/legacy/ISchematicBlock.java",
        "buildcraft/builders/internal/schematic/legacy/SchematicBlockContext.java",
        "buildcraft/builders/internal/schematic/legacy/SchematicBlockFactoryRegistry.java",
        "buildcraft/silicon/plug/FilterEventHandler.java",
        "buildcraft/silicon/plug/PluggableFacade.java",
    ):
        if not (STUBS / relative).is_file():
            fail(f"missing compile-only bridge {relative}")

    build = BUILD.read_text(encoding="utf-8")
    for fragment in (
        "transportPortStubs",
        "transportPort",
        "buildcraft/transport/**",
        "ModelPipeNative121111.java",
        "verifyTransportPort",
        "sourceSets.energyPort.output",
    ):
        if fragment not in build:
            fail(f"Gradle transport verifier is missing {fragment}")

    print("26.1.2 transport port OK: gameplay, terrain model, recipes, wires and later-module boundaries")


if __name__ == "__main__":
    main()

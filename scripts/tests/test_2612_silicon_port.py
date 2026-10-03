#!/usr/bin/env python3
"""Structural guard for the primary 26.1.2 NeoForge Silicon port."""

from pathlib import Path
import sys


ROOT = Path(__file__).resolve().parents[2]
FAMILY = ROOT / "source-families" / "26.X" / "src" / "main" / "java" / "buildcraft" / "silicon"
PLATFORM = ROOT / "source-family-platforms" / "26.X" / "neoforge" / "src" / "main" / "java" / "buildcraft" / "silicon"
STUBS = ROOT / "version-src" / "26.1.2-neoforge" / "silicon-port-stubs" / "src" / "main" / "java"


def read(path: Path) -> str:
    if not path.is_file():
        raise AssertionError(f"missing required target source: {path.relative_to(ROOT)}")
    return path.read_text(encoding="utf-8")


def require(path: Path, *needles: str) -> None:
    text = read(path)
    for needle in needles:
        if needle not in text:
            raise AssertionError(f"{path.relative_to(ROOT)} is missing: {needle}")


def forbid(path: Path, *needles: str) -> None:
    text = read(path)
    for needle in needles:
        if needle in text:
            raise AssertionError(f"{path.relative_to(ROOT)} still contains stale API: {needle}")


def main() -> int:
    try:
        require(FAMILY / "gui" / "GuiAdvancedCraftingTable.java",
                "GuiGraphicsExtractor", "ContainerInput", "recipeBook.mouseClicked", "recipeBookCharTyped")
        require(FAMILY / "gui" / "GuiGate.java",
                "117 + Math.max(1, container.slotHeight) * 18", "new BuildCraftJsonGui")
        require(FAMILY / "BCSiliconModels.java", "NativePluggableItemModels2612.install")
        require(FAMILY / "BCSiliconRecipes.java", "AssemblyRecipe.SERIALIZER", "GateLogicChangeRecipe.SERIALIZER")
        require(FAMILY / "recipe" / "FacadeSwapRecipe.java", "new RecipeSerializer<>(CODEC, STREAM_CODEC)",
                "assemble(CraftingInput inventory)")
        require(FAMILY / "recipe" / "GateLogicChangeRecipe.java", "GateLogicChangeRecipe()", "super()")
        require(FAMILY / "client" / "FacadeItemColours.java", "getTintSource", ".color(state.stateInfo.state)")
        require(FAMILY / "client" / "model" / "plug" / "PlugBakerFacade.java",
                "BlockStateModelPart", "getBlockStateModelSet().get", "materialInfo()")
        require(PLATFORM / "client" / "model" / "NativePluggableItemModels2612.java",
                "NativeItemModelBuilder", "FacadeItemColours.INSTANCE", "CompositeModel")
        require(PLATFORM / "plug" / "PluggableFacade.java", "BlockTintSource", "getTintSource")
        require(PLATFORM / "recipe" / "FacadeAssemblyRecipes.java", "new RecipeSerializer<>(CODEC, STREAM_CODEC)")
        require(STUBS / "buildcraft" / "robotics" / "BCRoboticsBoards.java", "Compile-only Silicon boundary")
        require(STUBS / "buildcraft" / "robotics" / "BCRoboticsItems.java", "Compile-only Robotics item boundary")
        require(STUBS / "buildcraft" / "robotics" / "item" / "ItemRedstoneBoard.java", "Compile-only bridge")

        for path in [FAMILY / "BCSiliconModels.java", FAMILY / "client" / "model" / "plug" / "PlugBakerFacade.java",
                     PLATFORM / "plug" / "PluggableFacade.java"]:
            forbid(path, "mc121111", "neoforge121111")

        gradle = read(ROOT / "build-logic" / "loaders" / "neoforge-target.gradle")
        for needle in ["siliconPortStubs", "siliconPort", "compileSiliconPortJava", "verifySiliconPort",
                       "buildersPort.output", "NativePluggableItemModels121111.java"]:
            if needle not in gradle:
                raise AssertionError(f"target build graph is missing: {needle}")
    except AssertionError as exc:
        print(f"26.1.2 silicon port guard failed: {exc}", file=sys.stderr)
        return 1

    print("26.1.2 silicon primary-port structure OK: tables, recipes, facades, native item models and robotics boundary")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

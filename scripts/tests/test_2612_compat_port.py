#!/usr/bin/env python3
"""Structural guard for the primary 26.1.2 NeoForge compatibility port."""

from pathlib import Path
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts"))

from source_layout import load_properties, materialize_target
from materialize_project import _apply_compile_exclusions

TARGET = "26.1.2-neoforge"
JEI = ROOT / "source-family-platforms" / "26.X" / "neoforge" / "src" / "main" / "java" / "buildcraft" / "compat" / "jei" / "BuildCraftJeiPlugin.java"
JADE = ROOT / "source-family-platforms" / "1.21.X" / "neoforge" / "src" / "main" / "java" / "buildcraft" / "compat" / "jade" / "BuildCraftJadePlugin.java"
BUILD = ROOT / "build-logic" / "loaders" / "neoforge-target.gradle"


def fail(message: str) -> None:
    raise SystemExit(f"26.1.2 compat port: {message}")


def require(path: Path, *needles: str) -> None:
    if not path.is_file():
        fail(f"missing required source {path.relative_to(ROOT)}")
    text = path.read_text(encoding="utf-8")
    for needle in needles:
        if needle not in text:
            fail(f"{path.relative_to(ROOT)} is missing {needle!r}")


def main() -> None:
    props = load_properties()
    prefix = f"target.{TARGET}."
    for compat in ("jei", "jade"):
        if props.get(prefix + f"compat.{compat}.enabled", "").lower() != "true":
            fail(f"{compat} must be enabled for the 26.1.2 primary port")
        if not props.get(prefix + f"deps.{compat}", "").strip():
            fail(f"{compat} is enabled without a target dependency")
        if not props.get(prefix + f"compat.{compat}.range", "").strip():
            fail(f"{compat} is enabled without a metadata version range")

    # These integrations are intentionally unavailable in the current modern baseline.
    # Do not fabricate a 26.1 port without a compatible upstream dependency.
    for compat in ("create", "forestry", "ic2"):
        if props.get(prefix + f"compat.{compat}.enabled", "false").lower() == "true":
            fail(f"{compat} was enabled without a supported 26.1 dependency")

    require(
        JEI,
        "GuiGraphicsExtractor",
        "public class BuildCraftJeiPlugin implements IModPlugin",
        "public void registerRecipes(IRecipeRegistration registration)",
        "public void registerRecipeTransferHandlers(IRecipeTransferRegistration registration)",
        "guiGraphics.text(",
    )
    jei_text = JEI.read_text(encoding="utf-8")
    if "import net.minecraft.client.gui.GuiGraphics;" in jei_text or "GuiGraphics guiGraphics" in jei_text:
        fail("JEI recipe rendering still uses the removed pre-26.1 GuiGraphics callback")

    require(
        JADE,
        "implements snownee.jade.api.IWailaPlugin",
        "registerBlockDataProvider",
        "registerEntityDataProvider",
        "registerItemStorage",
        "registerFluidStorage",
        "registerEnergyStorage",
        "registerProgress",
    )

    build = BUILD.read_text(encoding="utf-8")
    for needle in (
        "compatPort {",
        "java.include 'buildcraft/compat/**'",
        "sourceSets.roboticsPort.output",
        "sourceSets.siliconPort.output",
        "sourceSets.buildersPort.output",
        "sourceSets.factoryPort.output",
        "sourceSets.transportPort.output",
        "sourceSets.energyPort.output",
        "sourceSets.corePort.output",
        "sourceSets.apiLibPort.output",
        "verifyCompatPort",
        "compileCompatPortJava",
    ):
        if needle not in build:
            fail(f"Gradle compat verifier is missing {needle!r}")

    with tempfile.TemporaryDirectory(prefix="bc-2612-compat-") as temp:
        out = Path(temp)
        materialize_target(TARGET, out, props)
        _apply_compile_exclusions(out, props, TARGET)
        java = out / "src" / "main" / "java"
        required = (
            "buildcraft/compat/BuildCraftCompat.java",
            "buildcraft/compat/CompatCapTransfromer.java",
            "buildcraft/compat/jei/BuildCraftJeiPlugin.java",
            "buildcraft/compat/jei/AdvancedCraftingRecipeTransferHandler.java",
            "buildcraft/compat/jei/AutoWorkbenchRecipeTransferHandler.java",
            "buildcraft/compat/jei/CraftingPhantomTransfer.java",
            "buildcraft/compat/jei/PipeCraftingCategoryExtension.java",
            "buildcraft/compat/jade/BuildCraftJadePlugin.java",
            "buildcraft/compat/jade/JadeViewData.java",
        )
        for rel in required:
            if not (java / rel).is_file():
                fail(f"effective 26.1.2 source is missing {rel}")

        for rel in (
            "buildcraft/compat/create",
            "buildcraft/compat/forestry",
            "buildcraft/compat/ic2",
        ):
            path = java / rel
            if path.exists() and any(path.rglob("*.java")):
                fail(f"disabled unsupported integration leaked into effective source: {rel}")

        old_silicon = java / "buildcraft/silicon/client/model/NativePluggableItemModels121111.java"
        if old_silicon.exists():
            fail("old 1.21.11 Silicon item-model adapter still leaks into the real 26.1.2 main graph")

        effective_jei = (java / "buildcraft/compat/jei/BuildCraftJeiPlugin.java").read_text(encoding="utf-8")
        for needle in ("GuiGraphicsExtractor", "guiGraphics.text("):
            if needle not in effective_jei:
                fail(f"materialized JEI plugin lost {needle!r}")
        if "GuiGraphics guiGraphics" in effective_jei:
            fail("materialized JEI plugin regressed to the pre-26.1 render callback")

    print("26.1.2 compat primary-port structure OK: JEI/Jade enabled, 26.1 render callbacks adapted, unsupported integrations disabled, real module linkage preserved")


if __name__ == "__main__":
    main()

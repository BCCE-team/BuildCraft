#!/usr/bin/env python3
"""Structural guard for the Minecraft 26.1.2 NeoForge target."""
from __future__ import annotations

from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts"))

from source_config import load_properties, target_layout  # noqa: E402
from transforms.java_compat import upgrade_symbols  # noqa: E402

TARGET = "26.1.2-neoforge"
TARGETS = ROOT / "build-config/targets.properties"
BUILD = ROOT / "builds/26.X/build.neoforge.gradle"
LOADER_BUILD = ROOT / "build-logic/loaders/neoforge-target.gradle"
STONECUTTER = ROOT / "builds/26.X/stonecutter.gradle.kts"
FAMILY = ROOT / "source-families/26.X/src/main/java"
PLATFORM = ROOT / "source-family-platforms/26.X/neoforge/src/main/java"


def fail(message: str) -> None:
    raise AssertionError(f"{TARGET}: {message}")


def require(path: Path, *fragments: str) -> None:
    if not path.is_file():
        fail(f"missing {path.relative_to(ROOT)}")
    text = path.read_text(encoding="utf-8")
    for fragment in fragments:
        if fragment not in text:
            fail(f"{path.relative_to(ROOT)} is missing {fragment!r}")


def main() -> int:
    props = load_properties()
    if props.get(f"target.{TARGET}.deps.minecraft") != "26.1.2":
        fail("target registry has the wrong Minecraft version")
    if props.get(f"target.{TARGET}.java.version") != "25":
        fail("target registry has the wrong Java version")
    if props.get(f"target.{TARGET}.build.generation") != "26.X":
        fail("target registry has the wrong build generation")
    if props.get(f"target.{TARGET}.compat.jei.enabled") != "true":
        fail("JEI integration is not enabled")
    if props.get(f"target.{TARGET}.compat.jade.enabled") != "true":
        fail("Jade integration is not enabled")
    if props.get(f"target.{TARGET}.ci.runtime.enabled", "true").lower() == "false":
        fail("runtime CI is disabled")

    require(BUILD, "net.neoforged.moddev")
    require(STONECUTTER, 'stonecutter active "26.1.2-neoforge"', '"mc_26_x" to (generation == "26.X")')
    require(ROOT / "builds/26.X/settings.gradle.kts", 'id("dev.kikugie.stonecutter") version "0.7.11"', 'rootProject.name = "BuildCraft-26.X"')
    require(ROOT / "builds/26.X/gradle/wrapper/gradle-wrapper.properties", "gradle-9.1.0-bin.zip")

    layout = target_layout(TARGET, props)
    effective = layout.effective_files("src/main/java")
    retired = (
        "src/main/java/buildcraft/transport/client/model/ModelPipe.java",
        "src/main/java/buildcraft/transport/client/model/ModelPipeNative121111.java",
        "src/main/java/buildcraft/silicon/client/model/NativePluggableItemModels121111.java",
    )
    for relative in retired:
        if relative in effective:
            fail(f"retired implementation is still present: {relative}")

    for relative in (
        "src/main/java/buildcraft/compat/jei/BuildCraftJeiPlugin.java",
        "src/main/java/buildcraft/compat/jade/BuildCraftJadePlugin.java",
    ):
        if relative not in effective:
            fail(f"enabled integration is missing: {relative}")
    build_text = LOADER_BUILD.read_text(encoding="utf-8")
    for integration in ("create", "forestry", "ic2"):
        if props.get(f"target.{TARGET}.compat.{integration}.enabled") != "false":
            fail(f"{integration} integration should be explicitly disabled in target metadata")
        exclude = f"sourceSets.main.java.exclude 'buildcraft/compat/{integration}/**'"
        if exclude not in build_text:
            fail(f"disabled integration is not excluded from the compile graph: {integration}")

    compat_cases = (
        (
            PLATFORM / "buildcraft/lib/compat/RenderCompat.java",
            (
                "widget.mouseClicked(event, doubleClick)",
                "widget.keyPressed(event)",
                "RenderTypes.translucentMovingBlock()",
            ),
            (
                "RenderCompat.mouseClicked(widget, event, doubleClick)",
                "RenderCompat.keyPressed(widget, event)",
                "return RenderCompat.translucent();",
            ),
        ),
        (
            PLATFORM / "buildcraft/lib/compat/minecraft/gui/BCGuiInput.java",
            ("widget.mouseClicked(new MouseButtonEvent", "widget.keyPressed(new KeyEvent"),
            ("RenderCompat.mouseClicked(widget", "RenderCompat.keyPressed(widget"),
        ),
    )
    for path, required, forbidden in compat_cases:
        generated = upgrade_symbols(
            path.read_text(encoding="utf-8"),
            minecraft="26.1.2",
            relative=path.relative_to(ROOT).as_posix(),
        )
        for fragment in required:
            if fragment not in generated:
                fail(f"materializer changed native call in {path.relative_to(ROOT)}: {fragment}")
        for fragment in forbidden:
            if fragment in generated:
                fail(f"materializer introduced compatibility recursion in {path.relative_to(ROOT)}: {fragment}")

    container = PLATFORM / "buildcraft/lib/compat/minecraft/gui/BCContainerScreen.java"
    generated = upgrade_symbols(
        container.read_text(encoding="utf-8"),
        minecraft="26.1.2",
        relative=container.relative_to(ROOT).as_posix(),
    )
    for fragment in (
        "super.mouseClicked(event, doubleClick)",
        "super.mouseDragged(event, dragX, dragY)",
        "super.mouseReleased(event)",
    ):
        if fragment not in generated:
            fail(f"vanilla container fallback is missing: {fragment}")

    require(
        FAMILY / "buildcraft/api/v2/recipe/CountedIngredient.java",
        "ItemStack.isSameItemSameComponents",
        "stack.is(tag)",
    )
    require(PLATFORM / "buildcraft/transport/client/model/ModelPipeNative2612.java", "BlockStateModelPart")
    require(PLATFORM / "buildcraft/silicon/client/model/NativePluggableItemModels2612.java", "NativeItemModelBuilder")
    require(FAMILY / "buildcraft/core/marker/VolumeSubCache.java", "SavedDataCompat.migrateLegacyFlatFile")
    require(FAMILY / "buildcraft/core/marker/volume/WorldSavedDataVolumeBoxes.java", "SavedDataCompat.migrateLegacyFlatFile")
    require(FAMILY / "buildcraft/transport/wire/WorldSavedDataWireSystems.java", "SavedDataCompat.migrateLegacyFlatFile")
    require(PLATFORM / "buildcraft/compat/jei/BuildCraftJeiPlugin.java", "GuiGraphicsExtractor")

    print("26.1.2 NeoForge target structure OK")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

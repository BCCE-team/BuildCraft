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
MATERIALIZER = ROOT / "scripts/materialize_project.py"
STONECUTTER = ROOT / "builds/26.X/stonecutter.gradle.kts"
FAMILY = ROOT / "source-families/26.X/src/main/java"
PLATFORM = ROOT / "source-family-platforms/26.X/neoforge/src/main/java"
MODERN_PLATFORM = ROOT / "source-family-platforms/1.21.X/neoforge/src/main/java"


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
    if props.get(f"target.{TARGET}.loader.version_range") != "[11,)":
        fail("target registry has the wrong javafml loader range")
    if props.get(f"target.{TARGET}.build.generation") != "26.X":
        fail("target registry has the wrong build generation")
    if props.get(f"target.{TARGET}.compat.jei.enabled") != "true":
        fail("JEI integration is not enabled")
    if props.get(f"target.{TARGET}.compat.jade.enabled") != "true":
        fail("Jade integration is not enabled")
    if props.get(f"target.{TARGET}.ci.runtime.enabled", "true").lower() == "false":
        fail("runtime CI is disabled")

    require(BUILD, "net.neoforged.moddev")
    require(
        LOADER_BUILD,
        'testRuntimeOnly platform("org.junit:junit-bom:${requiredProperty(\'deps.junit\')}")',
        'testRuntimeOnly "org.junit.platform:junit-platform-launcher"',
    )
    require(
        MATERIALIZER,
        'testRuntimeOnly platform("org.junit:junit-bom:{junit}")',
        'testRuntimeOnly "org.junit.platform:junit-platform-launcher"',
    )
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
    require(
        PLATFORM / "buildcraft/lib/net/cache/NetworkedObjectCache.java",
        "Supplier<? extends T> defaultObjectFactory",
        "private T getDefaultObject()",
    )
    fluid_cache = PLATFORM / "buildcraft/lib/net/cache/NetworkedFluidStackCache.java"
    require(fluid_cache, "super(() -> new FluidStack(Fluids.WATER, FLUID_AMOUNT))")
    if "super(new FluidStack(" in fluid_cache.read_text(encoding="utf-8"):
        fail("fluid cache eagerly creates a FluidStack during mod construction")
    energy_client = PLATFORM / "buildcraft/energy/BCEnergyClientProxy.java"
    require(
        energy_client,
        "public static void registerFluidModels(RegisterFluidModelsEvent event)",
        "new FluidModel.Unbaked(",
        "FluidTintSources.constant(type.getFluidTintColor())",
        "event.register(model, source, source.getFlowing())",
    )
    energy_client_text = energy_client.read_text(encoding="utf-8")
    if "RegisterFluidModelsEvent" not in energy_client_text:
        fail("BuildCraft fluids are not registered in the 26.1 FluidStateModelSet")

    energy_recipes = ROOT / "source-families/1.21.X/src/main/java/buildcraft/energy/BCEnergyRecipes.java"
    require(
        energy_recipes,
        "BuiltInRegistries.FLUID.getKey(fluid)",
        "private record FluidRecipeValue",
    )
    if "new FluidStack(" in energy_recipes.read_text(encoding="utf-8"):
        fail("energy built-in recipes create FluidStacks before default components are bound")
    silicon = MODERN_PLATFORM / "buildcraft/silicon/BCSilicon.java"
    silicon_text = silicon.read_text(encoding="utf-8")
    common_start = silicon_text.index("public static void commonSetup")
    post_start = silicon_text.index("public static void postInit")
    level_start = silicon_text.index("private static void onLevelLoad")
    common_body = silicon_text[common_start:post_start]
    post_body = silicon_text[post_start:level_start]
    level_body = silicon_text[level_start:]
    if "FacadeStateManager.init();" in common_body or "FacadeStateManager.init();" in post_body:
        fail("facade discovery creates ItemStacks before a world is loaded")
    for fragment in (
        "NeoForge.EVENT_BUS.addListener(BCSilicon::onLevelLoad)",
        "private static void onLevelLoad(LevelEvent.Load event)",
        "FacadeStateManager.init();",
    ):
        if fragment not in silicon_text:
            fail(f"26.1 facade world-load initialization is missing: {fragment}")
    if "FacadeStateManager.init();" not in level_body:
        fail("facade discovery is not tied to world load")
    facade_manager = PLATFORM / "buildcraft/silicon/plug/FacadeStateManager.java"
    require(
        facade_manager,
        "private static volatile boolean initialized;",
        "synchronized (FacadeStateManager.class)",
        "public static boolean isInitialized()",
    )
    for creative_tabs in (
        FAMILY / "buildcraft/lib/CreativeTabManager.java",
        PLATFORM / "buildcraft/lib/CreativeTabManager.java",
    ):
        require(
            creative_tabs,
            "private Supplier<? extends ItemStack> iconFactory",
            "iconFactory = item::getDefaultInstance;",
            "ItemStack stack = iconFactory.get();",
        )
        creative_tab_text = creative_tabs.read_text(encoding="utf-8")
        if "private ItemStack icon = new ItemStack(" in creative_tab_text:
            fail("creative-tab descriptor eagerly creates an ItemStack during mod construction")
        if "setItem(item.getDefaultInstance())" in creative_tab_text:
            fail("creative-tab item selection eagerly creates an ItemStack during common setup")
        if "setItemStack(name, item.getDefaultInstance())" in creative_tab_text:
            fail("named creative-tab item selection eagerly creates an ItemStack during common setup")
    pipe_recipe = FAMILY / "buildcraft/transport/recipe/PipeRecipe.java"
    pipe_recipe_text = pipe_recipe.read_text(encoding="utf-8")
    require(
        pipe_recipe,
        "private static final Codec<Result> RESULT_CODEC",
        "private record Result(Item item, int count)",
        "new ItemStackTemplate(item, count)",
    )
    if "Codec<ItemStack> RESULT_CODEC" in pipe_recipe_text or "PipeRecipe::resultStack" in pipe_recipe_text:
        fail("pipe recipe codec creates ItemStacks while recipes are decoded")

    for assembly_recipe in (
        FAMILY / "buildcraft/lib/recipe/AssemblyRecipe.java",
        PLATFORM / "buildcraft/lib/recipe/AssemblyRecipe.java",
    ):
        assembly_text = assembly_recipe.read_text(encoding="utf-8")
        require(assembly_recipe, "Codec<LegacyResult> LEGACY_RESULT_CODEC", "private record LegacyResult")
        if "Codec<ItemStack> LEGACY_RESULT_CODEC" in assembly_text or "AssemblyRecipe::legacyStack" in assembly_text:
            fail(f"assembly recipe codec creates ItemStacks while recipes are decoded: {assembly_recipe.relative_to(ROOT)}")

    for strict_nbt in (
        FAMILY / "buildcraft/lib/recipe/LegacyStrictNbtIngredient.java",
        PLATFORM / "buildcraft/lib/recipe/LegacyStrictNbtIngredient.java",
    ):
        require(strict_nbt, "private volatile ItemStack displayStack;", "private ItemStack displayStack()")
        constructor = strict_nbt.read_text(encoding="utf-8").split("private LegacyStrictNbtIngredient", 1)[1].split("private ItemStack displayStack()", 1)[0]
        if "new ItemStack(" in constructor:
            fail(f"strict-NBT ingredient eagerly creates an ItemStack during recipe decode: {strict_nbt.relative_to(ROOT)}")

    gate_recipe = FAMILY / "buildcraft/silicon/recipe/GateLogicChangeRecipe.java"
    require(
        gate_recipe,
        "private static final GateLogicChangeRecipe INSTANCE = new GateLogicChangeRecipe();",
        "MapCodec.unit(INSTANCE)",
        "StreamCodec.unit(INSTANCE)",
    )
    gate_recipe_text = gate_recipe.read_text(encoding="utf-8")
    if "MapCodec.unit(new GateLogicChangeRecipe())" in gate_recipe_text or "StreamCodec.unit(new GateLogicChangeRecipe())" in gate_recipe_text:
        fail("gate logic recipe JSON and network codecs use different unit instances")

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

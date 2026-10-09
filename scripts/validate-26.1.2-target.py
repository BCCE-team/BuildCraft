#!/usr/bin/env python3
"""Structural guard for the Minecraft 26.1.2 NeoForge target."""
from __future__ import annotations

from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
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

    wrench = ROOT / "source-families/1.21.X/src/main/java/buildcraft/core/item/ItemWrench.java"
    require(
        wrench,
        "//? if mc_26_x {",
        "return false;",
        "level.isClientSide() && level.getBlockEntity(pos) instanceof TileEngineBase_BC8",
    )

    engine_item = FAMILY / "buildcraft/lib/item/MultiBlockItem.java"
    require(
        engine_item,
        "Direction preferredDirection = context.getClickedFace().getOpposite();",
        "engine.preferDirectionOnPlacement(preferredDirection);",
    )
    engine_tile = FAMILY / "buildcraft/lib/engine/TileEngineBase_BC8.java"
    require(
        engine_tile,
        "public void preferDirectionOnPlacement(Direction preferredDirection)",
        "!isFacingReceiver(preferredDirection)",
    )

    require(
        FAMILY / "buildcraft/api/v2/recipe/CountedIngredient.java",
        "ItemStack.isSameItemSameComponents",
        "stack.is(tag)",
        "return new CountedIngredient(null, Objects.requireNonNull(tag, \"tag\"), null, count);",
        "public Ingredient ingredient()",
        "BuiltInRegistries.ITEM.get(tag)",
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
        require(
            assembly_recipe,
            "Codec<LegacyResult> LEGACY_RESULT_CODEC",
            "private record LegacyResult",
            "final ItemStackTemplate output;",
            "ItemStackTemplate.fromNonEmptyStack(output.copy())",
            "new SlotDisplay.ItemStackSlotDisplay(output)",
            "ItemStack.STREAM_CODEC.decode(buffer)",
            "ItemStack.STREAM_CODEC.encode(buffer, output.create())",
        )
        if "Codec<ItemStack> LEGACY_RESULT_CODEC" in assembly_text or "AssemblyRecipe::legacyStack" in assembly_text:
            fail(f"assembly recipe codec creates ItemStacks while recipes are decoded: {assembly_recipe.relative_to(ROOT)}")
        if "LegacyResult.fromStack(output)" in assembly_text:
            fail(f"assembly recipe constructor still discards arbitrary data components: {assembly_recipe.relative_to(ROOT)}")

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
    pipe_native = PLATFORM / "buildcraft/transport/client/model/ModelPipeNative2612.java"
    require(
        pipe_native,
        "material(sprite, translucentLayer, quad.getTint(), quad.isShade(), lightEmission(quad))",
        "BakedColors.of(argb(quad.vertex_0), argb(quad.vertex_1), argb(quad.vertex_2), argb(quad.vertex_3))",
        "convertPluggables(legacyPlugTranslucent, true)",
    )
    pipe_native_text = pipe_native.read_text(encoding="utf-8")
    if "PluggableFacade.isGlass(" in pipe_native_text:
        fail("26.1 native pipe model still excludes glass facades from terrain translucent rendering")
    silicon_models_26_1_2 = FAMILY / "buildcraft/silicon/BCSiliconModels.java"
    silicon_models_26_1_2_text = silicon_models_26_1_2.read_text(encoding="utf-8")
    if "registerRenderer(PluggableFacade.class, PlugFacadeRenderer.INSTANCE)" in silicon_models_26_1_2_text:
        fail("26.1 still renders glass facades through the dynamic pluggable pass")
    pipe_colours = FAMILY / "buildcraft/transport/BCTransportModels.java"
    if "event.register(PipeBlockColours.INSTANCE" in pipe_colours.read_text(encoding="utf-8"):
        fail("26.1 pipe block still registers a fixed tint-source list instead of dynamic facade tints")
    require(
        PLATFORM / "buildcraft/transport/block/BlockPipeHolderClientExtensions2612.java",
        "collectDynamicTintValues",
        "blockColors.getTintSources(sourceState)",
        "source.colorInWorld(sourceState, level, pos)",
        "blockTintIndex * Direction.values().length + side.ordinal()",
    )
    facade_pluggable = PLATFORM / "buildcraft/silicon/plug/PluggableFacade.java"
    require(
        facade_pluggable,
        "FacadeTintClient2612.getBlockColor(states.phasedStates[activeState], holder, tintIndex)",
    )
    facade_pluggable_text = facade_pluggable.read_text(encoding="utf-8")
    for client_only in ("BlockTintSource", "ClientLevel", "colorInWorld("):
        if client_only in facade_pluggable_text:
            fail(f"26.1 common facade pluggable still directly references client-only tint API {client_only!r}")
    require(
        FAMILY / "buildcraft/silicon/client/FacadeTintClient2612.java",
        "BlockTintSource tintSource = Minecraft.getInstance().getBlockColors()",
        "holder.getPipeWorld() instanceof ClientLevel clientLevel",
        "tintSource.colorInWorld(state.stateInfo.state, clientLevel, holder.getPipePos())",
        "return tintSource.color(state.stateInfo.state);",
    )
    require(PLATFORM / "buildcraft/silicon/client/model/NativePluggableItemModels2612.java", "NativeItemModelBuilder")
    native_plugs = (PLATFORM / "buildcraft/silicon/client/model/NativePluggableItemModels2612.java").read_text(encoding="utf-8")
    for forbidden in ("PLUG_LIGHT_SENSOR_ITEM", "PLUG_TIMER_ITEM"):
        if forbidden in native_plugs:
            fail(f"26.1 native plug model still eagerly overrides {forbidden}")
    for required in (
        "new PulsarItemModel()",
        "PluggablePulsar.setModelVariablesForItem();",
        "ModelItemSimple.TRANSFORM_PLUG_AS_ITEM",
        "int sourceTint = tint / Direction.values().length;",
        "FacadeItemColours.INSTANCE.getColor(stack, sourceTint)",
    ):
        if required not in native_plugs:
            fail(f"26.1 pulsar item lazy model is missing {required!r}")
    for required in (
        "BCModules.TRANSPORT.isLoaded()",
        "key.state.isSolidRender()",
        "!key.isHollow",
        "BCTransportModels.BLOCKER.getCutoutQuads()",
    ):
        if required not in native_plugs:
            fail(f"26.1 solid facade item backing is missing {required!r}")

    engine_tile_text = engine_tile.read_text(encoding="utf-8")
    pulse_start = engine_tile_text.index("private boolean isPulsedPowerReceiver")
    pulse_end = engine_tile_text.index("public MjPort getPortToPower", pulse_start)
    pulse_body = engine_tile_text[pulse_start:pulse_end]
    for required in (
        "BlockPos targetPos = engine.worldPosition.relative(side);",
        "level.getBlockEntity(targetPos)",
        ".descriptor(level, targetPos, side.getOpposite())",
    ):
        if required not in pulse_body:
            fail(f"26.1 positional MJ pulse-role lookup is missing {required!r}")
    if "getTileBuffer(side).getTile()" in pulse_body:
        fail("26.1 pulse-role lookup still requires a BlockEntity at the final positional MJ endpoint")

    renderer = FAMILY / "buildcraft/core/client/render/RenderEngine_BC8.java"
    renderer_text = renderer.read_text(encoding="utf-8")
    moving_start = renderer_text.index("private static void renderMovingHeadCaps")
    moving_end = renderer_text.index("private static void renderDynamoMovingHead", moving_start)
    moving_body = renderer_text[moving_start:moving_end]
    if "0.90F" in moving_body or "0.70F" in moving_body:
        fail("26.1 engine moving head still uses non-legacy per-face shading")
    head_box_start = renderer_text.index("private static void renderHeadBox")
    head_box_end = renderer_text.index("private static void renderChamber", head_box_start)
    head_box = renderer_text[head_box_start:head_box_end]
    if "0.90F" in head_box or "0.70F" in head_box:
        fail("26.1 MJ Dynamo moving head still uses non-legacy per-face shading")
    lights_start = renderer_text.index("private static void renderStageLights")
    lights_end = renderer_text.index("private static void quad", lights_start)
    lights = renderer_text[lights_start:lights_end]
    if "FULL_BRIGHT,overlay" not in lights or "1.00F" in lights:
        fail("26.1 engine stage lights do not match legacy 0.8 full-bright shading")

    gui = PLATFORM / "buildcraft/lib/gui/GuiBC8.java"
    require(
        gui,
        "this(container, jsonGuiDef, inventory, title, 10, 10);",
        "protected GuiBC8(C container, Identifier jsonGuiDef, Inventory inventory, Component title,",
        "super(container, inventory, title, imageWidth, imageHeight);",
        "protected void extractTooltip(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY)",
        "mainGui.currentMenu == null || !mainGui.currentMenu.shouldFullyOverride()",
    )
    require(
        ROOT / "source-families/1.21.X/src/main/java/buildcraft/factory/block/BlockTube.java",
        "protected BlockState updateShape",
        "direction == Direction.DOWN",
        "notifyPumpOfShaftChange",
        "pump.neighbourBlockChanged",
    )
    require(
        MODERN_PLATFORM / "buildcraft/core/item/FragileFluidResourceHandler.java",
        "FluidCompatRegistry.areEquivalent(fluid, resource.toStack(1))",
    )
    require(
        PLATFORM / "buildcraft/lib/client/render/fluid/FluidRenderer.java",
        "private static TextureAtlasSprite fluidSprite(Fluid fluid, boolean flowing)",
        "case FLOWING -> fluidSprite(fluid, true)",
        "case STILL, FROZEN -> fluidSprite(fluid, false)",
    )
    dynamo_model = ROOT / "resource-src/26.X/26.1.2/assets/buildcraftenergy/models/block/mj_dynamo.json"
    require(dynamo_model, '"particle": "buildcraftenergy:blocks/mj_dynamo/back"')
    dynamo_text = dynamo_model.read_text(encoding="utf-8")
    if '"parent"' in dynamo_text or '"elements"' in dynamo_text:
        fail("26.1 MJ Dynamo block model still contains geometry rendered again by the BER")
    marker_connector = ROOT / "source-families/1.21.X/src/main/java/buildcraft/core/item/ItemMarkerConnector.java"
    marker_text = marker_connector.read_text(encoding="utf-8")
    use_start = marker_text.index("public InteractionResult use(Level world, Player player, InteractionHand hand)")
    use_end = marker_text.index("private static <S extends MarkerSubCache", use_start)
    use_body = marker_text[use_start:use_end]
    for fragment in (
        "if (!world.isClientSide())",
        "return onItemRightClickVolumeBoxes(world, player);",
    ):
        if fragment not in use_body:
            fail(f"26.1 Marker Connector lost legacy interaction-result parity: {fragment}")
    if "return InteractionResult.SUCCESS;" in use_body or "markerConnected" in use_body:
        fail("26.1 Marker Connector still upgrades client/marker-line use results to SUCCESS")

    core_client_events = MODERN_PLATFORM / "buildcraft/core/client/BCCoreClientModEvents.java"
    require(
        core_client_events,
        "RegisterDebugEntriesEvent",
        "DebugScreenEntryStatus.IN_OVERLAY",
        "RenderTickListener.renderDebugInfo(displayer)",
    )
    debug_listener = MODERN_PLATFORM / "buildcraft/core/client/RenderTickListener.java"
    require(
        debug_listener,
        "public static void renderDebugInfo(DebugScreenDisplayer displayer)",
        "ClientDebuggables.getDebuggableObject(mc.hitResult)",
        "ClientDebuggables.SERVER_LEFT",
        "ClientDebuggables.SERVER_RIGHT",
        "mc.getCameraEntity()",
        "debuggable.getClientDebugInfo(extraLeft, extraRight, face)",
        "displayer.addLine(line)",
    )
    if "mc.cameraEntity" in debug_listener.read_text(encoding="utf-8"):
        fail("26.1 debug overlay still accesses the removed Minecraft.cameraEntity field")

    require(FAMILY / "buildcraft/core/marker/VolumeSubCache.java", "SavedDataCompat.migrateLegacyFlatFile")
    require(FAMILY / "buildcraft/core/marker/volume/WorldSavedDataVolumeBoxes.java", "SavedDataCompat.migrateLegacyFlatFile")
    require(FAMILY / "buildcraft/transport/wire/WorldSavedDataWireSystems.java", "SavedDataCompat.migrateLegacyFlatFile")
    require(PLATFORM / "buildcraft/compat/jei/BuildCraftJeiPlugin.java", "GuiGraphicsExtractor")

    print("26.1.2 NeoForge target structure OK")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

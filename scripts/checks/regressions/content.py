#!/usr/bin/env python3
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[3]
SCRIPT_DIR = ROOT / "scripts"
if str(SCRIPT_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPT_DIR))
from source_lookup import resolve_source_path
sys.path.insert(0, str(ROOT / "scripts"))
from source_layout import effective_source_files, load_properties, target_layout

errors = []


def text(rel):
    path = resolve_source_path(rel)
    if not path.is_file():
        errors.append(f"missing {rel}")
        return ""
    return path.read_text(encoding="utf-8")


def require(rel, *tokens):
    source = text(rel)
    for token in tokens:
        if token not in source:
            errors.append(f"{rel}: missing content regression guard {token!r}")


def require_effective(target, rel, *tokens):
    props = load_properties()
    layout = target_layout(target, props)
    effective = effective_source_files(layout, props)
    path = effective.get(rel)
    if path is None:
        errors.append(f"{target}: missing effective {rel}")
        source = ""
    else:
        source = path.read_text(encoding="utf-8")
    for token in tokens:
        if token not in source:
            errors.append(f"{target}:{rel}: missing content regression guard {token!r}")


for platform in ("forge", "neoforge"):
    base = f"source-platforms/{platform}/src/main/java"
    require(
        f"{base}/buildcraft/lib/tile/craft/WorkbenchCrafting.java",
        "if (!clearInventory())",
        "matchingRecipes",
        "selectRecipe(int delta)",
        "writeSelection(CompoundTag nbt)",
        "readSelection(CompoundTag nbt)",
        "selectedCraftingRecipe",
    )
    if platform == "forge":
        require(
            f"{base}/buildcraft/lib/tile/craft/WorkbenchCrafting.java",
            "List<Recipe<CraftingContainer>> matchingRecipes",
            "for (Recipe<CraftingContainer> recipe : world.getRecipeManager().getAllRecipesFor(RecipeType.CRAFTING))",
        )
    require(
        f"{base}/buildcraft/factory/tile/TileAutoWorkbenchBase.java",
        "crafting.writeSelection(nbt)",
        "crafting.readSelection(nbt)",
        "cycleRecipe(int delta)",
        "getRecipeSelectionCount()",
    )
    require(
        f"{base}/buildcraft/silicon/tile/TileAdvancedCraftingTable.java",
        "crafting.writeSelection(nbt)",
        "crafting.readSelection(nbt)",
        "cycleRecipe(int delta)",
        "getRecipeSelectionCount()",
    )
    require(
        f"{base}/buildcraft/factory/container/ContainerAutoCraftItems.java",
        "BUTTON_PREVIOUS_RECIPE",
        "BUTTON_NEXT_RECIPE",
        "tile.cycleRecipe(-1)",
        "tile.cycleRecipe(1)",
    )
    require(
        f"{base}/buildcraft/silicon/container/ContainerAdvancedCraftingTable.java",
        "BUTTON_PREVIOUS_RECIPE",
        "BUTTON_NEXT_RECIPE",
        "tile.cycleRecipe(-1)",
        "tile.cycleRecipe(1)",
    )
    require(
        f"{base}/buildcraft/silicon/container/ContainerProgrammingTable.java",
        "NET_SELECT_OPTION = NET_DATA;",
        "sendMessage(NET_SELECT_OPTION",
        "id == NET_SELECT_OPTION",
        "tile.selectOption(buffer.readVarInt())",
    )
    require(
        f"source-platforms/{platform}/src/gametest/java/buildcraft/gametest/WorkbenchRollbackGameTests.java",
        "failedGridClearNeverOverwritesTransientItems",
        "crafting.setItem(0, new ItemStack(Items.DIAMOND))",
        "!crafted",
    )

require(
    "source-families/legacy/src/main/java/buildcraft/builders/block/BlockConstructionMarker.java",
    "allowed to continue to their own Item#useOn implementation",
    "return InteractionResult.PASS;",
)
require(
    "source-families/legacy/src/main/java/buildcraft/factory/block/BlockFloodGate.java",
    "A wrench interaction belongs to the Flood Gate",
    "return InteractionResult.SUCCESS;",
)

for rel in (
    "version-src/1.19.2-forge/src/main/java/buildcraft/factory/gui/GuiAutoCraftItems.java",
    "version-src/1.20.1-forge/src/main/java/buildcraft/factory/gui/GuiAutoCraftItems.java",
    "source-families/modern/src/main/java/buildcraft/factory/gui/GuiAutoCraftItems.java",
    "version-src/1.19.2-forge/src/main/java/buildcraft/silicon/gui/GuiAdvancedCraftingTable.java",
    "version-src/1.20.1-forge/src/main/java/buildcraft/silicon/gui/GuiAdvancedCraftingTable.java",
    "source-families/modern/src/main/java/buildcraft/silicon/gui/GuiAdvancedCraftingTable.java",
):
    require(rel, "slot instanceof SlotDisplay", "handleInventoryButtonClick")

# Oil world generation uses biome-modifier eligibility tags rather than dedicated oil biomes.
# Keep the eligibility tag and reject obsolete oil-biome registry/API surface everywhere.
for root in ("source-families", "source-platforms", "source-family-platforms", "source-shared", "version-src"):
    for path in (ROOT / root).rglob("*"):
        if not path.is_file() or path.suffix not in {".java", ".json"}:
            continue
        source = path.read_text(encoding="utf-8", errors="ignore")
        for obsolete in ("oil_desert", "oil_deep_ocean", "OIL_DESERT_KEY", "OIL_DEEP_OCEAN_KEY"):
            if obsolete in source:
                errors.append(f"{path.relative_to(ROOT)}: obsolete custom oil biome reference {obsolete!r}")

for rel in (
    "source-families/legacy/src/main/resources/data/forge/tags/worldgen/biome/is_desert.json",
    "source-families/legacy/src/main/resources/data/forge/tags/worldgen/biome/is_sandy.json",
):
    if (ROOT / rel).exists():
        errors.append(f"{rel}: obsolete tag extension for removed oil biome still exists")

require(
    "version-src/1.19.2-forge/src/main/java/buildcraft/energy/BCEnergyWorldGen.java",
    "BCEnergyBiomeModifiers.register(modEventBus)",
    "FEATURE_REGISTER.register(modEventBus)",
)
require(
    "source-shared/src/main/resources/data/buildcraftenergy/tags/worldgen/biome/is_oil_biome.json",
    "minecraft:desert",
    "minecraft:deep_ocean",
    "minecraft:deep_cold_ocean",
)

# Every NeoForge module that owns a native config must expose NeoForge's generated
# config screen. Keep client GUI references out of ConfigBinding: that boundary is
# exercised by the dedicated platform config contract and is server-safe by design.
for target in ("1.21.1-neoforge", "1.21.11-neoforge"):
    require_effective(
        target,
        "src/main/java/buildcraft/lib/platform/config/ConfigScreenRegistration.java",
        "FMLEnvironment",
        "Dist.CLIENT",
        "IConfigScreenFactory.class",
        "ConfigurationScreen::new",
    )
    for module_rel in (
        "buildcraft/core/BCCore.java",
        "buildcraft/transport/BCTransport.java",
        "buildcraft/energy/BCEnergy.java",
        "buildcraft/builders/BCBuilders.java",
        "buildcraft/silicon/BCSilicon.java",
    ):
        require_effective(
            target,
            f"src/main/java/{module_rel}",
            "registerConfig(Type.COMMON, ConfigBinding.bind(",
            "ConfigScreenRegistration.register(modContainer);",
        )

# NeoForge committed resources must stay aligned with the current datagen provider.
# 1.21.1 and 1.21.11 must use the same corrected recipe ingredients.
for target in ("1.21.1-neoforge", "1.21.11-neoforge"):
    base = "src/main/resources/data/buildcraftsilicon/recipe"
    require_effective(
        target,
        f"{base}/plug_gate_create/clay_brick_no_modifier.json",
        '"item": "minecraft:brick"',
    )
    require_effective(
        target,
        f"{base}/plug_gate_create/nether_brick_no_modifier.json",
        '"item": "minecraft:nether_brick"',
    )
    for rel in (
        f"{base}/assembly/lens/lens_regular.json",
        f"{base}/assembly/lens/lens_filter.json",
    ):
        require_effective(target, rel, '"tag": "c:glass_blocks"')

# Assembly-table values use the original BC8 balance; programming and integration
# of robots retain their BC7 costs. These values are player-visible laser energy.
for rel in (
    "version-src/1.19.2-forge/src/main/java/buildcraft/silicon/BCSiliconRecipesProvider.java",
    "version-src/1.20.1-forge/src/main/java/buildcraft/silicon/BCSiliconRecipesProvider.java",
    "source-platforms/neoforge/src/main/java/buildcraft/silicon/BCSiliconRecipesProvider.java",
):
    require(rel, "return wholeMj * MjAmount.MICRO_MJ_PER_MJ;")

for family in ("legacy", "modern"):
    require(
        f"source-families/{family}/src/main/java/buildcraft/robotics/BCRoboticsBoards.java",
        '"robot_delivery", 128000',
        '"robot_knight", 128000',
        '"robot_bomber", 128000',
        '"robot_stripes", 128000',
        '"robot_builder", 512000',
    )
    require(
        f"source-families/{family}/src/main/java/buildcraft/robotics/recipes/RobotIntegrationRecipe.java",
        "return 50_000L * MjAmount.MICRO_MJ_PER_MJ;",
    )


if errors:
    for error in errors:
        print("ERROR:", error)
    sys.exit(1)

print("Content regression guards OK")
print(" - Workbench rollback cannot overwrite transient crafting items")
print(" - conflicting crafting outputs have a persistent GUI selector without unsafe Forge recipe casts")
print(" - Construction Marker and Flood Gate interaction parity is guarded")
print(" - dead custom oil biomes and their legacy Forge tag hooks are removed")
print(" - Programming Table selection packets use an allocated container message ID")
print(" - NeoForge config-owning modules expose the native generated config screen")
print(" - 1.21.1/1.21.11 gate and clear-lens recipes use live NeoForge ingredients/tags")
print(" - BC8 assembly and BC7 robotics laser-energy balances are preserved")

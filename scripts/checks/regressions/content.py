#!/usr/bin/env python3
from pathlib import Path
import json
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

# Energy-priced crafting keeps the original source balance in BCCE's MJ units.
# BuildCraft 8.0.0 Assembly Table values were already expressed in MJ and therefore stay unchanged.
# BuildCraft 7.1.27 Robotics used RF, so its Programming/Integration Table costs are converted at 10 RF = 1 MJ.
for rel in (
    "version-src/1.19.2-forge/src/main/java/buildcraft/silicon/BCSiliconRecipesProvider.java",
    "version-src/1.20.1-forge/src/main/java/buildcraft/silicon/BCSiliconRecipesProvider.java",
    "source-platforms/neoforge/src/main/java/buildcraft/silicon/BCSiliconRecipesProvider.java",
):
    require(
        rel,
        "return wholeMj * MjAmount.MICRO_MJ_PER_MJ;",
        "assemblyCost(1000)",
        "assemblyCost(500)",
    )

# The remaining BC8 Assembly Table balance is represented by the original gate/chipset tiers.
# Underscores differ between source generations, so guard stable surrounding recipe calls instead of spelling every literal twice.
for rel in (
    "version-src/1.19.2-forge/src/main/java/buildcraft/silicon/BCSiliconRecipesProvider.java",
    "version-src/1.20.1-forge/src/main/java/buildcraft/silicon/BCSiliconRecipesProvider.java",
    "source-platforms/neoforge/src/main/java/buildcraft/silicon/BCSiliconRecipesProvider.java",
):
    source = text(rel).replace("_", "")
    for token in (
        "makeGateAssembly(writer, 20000",
        "makeGateAssembly(writer, 40000",
        "makeGateAssembly(writer, 80000",
        "makeGateModifierAssembly(writer, 60000",
        "makeGateModifierAssembly(writer, 100000",
        "makeGateModifierAssembly(writer, 120000",
        "makeGateModifierAssembly(writer, 140000",
        "makeGateModifierAssembly(writer, 180000",
        "assemblyCost(10000)",
        "assemblyCost(20000)",
        "assemblyCost(40000)",
        "assemblyCost(60000)",
        "assemblyCost(80000)",
    ):
        if token not in source:
            errors.append(f"{rel}: missing BC8 8.0.0 assembly-energy balance token {token!r}")

for rel in (
    "version-src/1.19.2-forge/src/main/java/buildcraft/silicon/recipe/FacadeAssemblyRecipes.java",
    "version-src/1.20.1-forge/src/main/java/buildcraft/silicon/recipe/FacadeAssemblyRecipes.java",
    "source-family-platforms/modern/neoforge/src/main/java/buildcraft/silicon/recipe/FacadeAssemblyRecipes.java",
    "source-downports/modern/1.21.1/neoforge/src/main/java/buildcraft/silicon/recipe/FacadeAssemblyRecipes.java",
):
    require(rel, "MJ_COST = 64 * MjAmount.MICRO_MJ_PER_MJ")

# Committed Assembly recipe JSON is loaded at runtime, so it must match the restored BC8 provider balance.
# These values intentionally cover every price tier that the provider emits; family/platform copies must agree.
ASSEMBLY_JSON_COSTS = {
    "redstone_chipset.json": 10_000,
    "iron_chipset.json": 20_000,
    "gold_chipset.json": 40_000,
    "quartz_chipset.json": 60_000,
    "diamond_chipset.json": 80_000,
    "redstone_crystal.json": 100_000,
    "plug_pulsar.json": 1_000,
    "plug_timer.json": 500,
    "light_sensor.json": 500,
    "gate_copier.json": 500,
}


def expected_assembly_cost_mj(path: Path):
    name = path.name
    if name in ASSEMBLY_JSON_COSTS:
        return ASSEMBLY_JSON_COSTS[name]
    parts = path.parts
    if "wire" in parts:
        return 5_000
    if "lens" in parts:
        return 500
    if "gate" in parts:
        material = next((part for part in ("iron", "nether_brick", "gold") if f"_{part}_" in name), None)
        if material is None:
            return None
        base = {"iron": 20_000, "nether_brick": 40_000, "gold": 80_000}[material]
        if "modifier" not in parts:
            return base
        modifier = next((part for part in ("lapis", "quartz", "diamond") if name.endswith(f"_{part}.json")), None)
        if modifier is None:
            return None
        return {
            ("iron", "lapis"): 40_000,
            ("iron", "quartz"): 60_000,
            ("iron", "diamond"): 80_000,
            ("nether_brick", "lapis"): 80_000,
            ("nether_brick", "quartz"): 100_000,
            ("nether_brick", "diamond"): 120_000,
            ("gold", "lapis"): 100_000,
            ("gold", "quartz"): 140_000,
            ("gold", "diamond"): 180_000,
        }[(material, modifier)]
    return None


assembly_json_count = 0
for base in (
    ROOT / "source-families",
    ROOT / "source-family-platforms",
):
    for path in base.rglob("*.json"):
        try:
            data = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError):
            continue
        if data.get("type") != "buildcraftsilicon:assembly" or "MJ" not in data:
            continue
        assembly_json_count += 1
        expected_mj = expected_assembly_cost_mj(path)
        if expected_mj is None:
            errors.append(f"{path.relative_to(ROOT)}: unclassified Assembly recipe price")
            continue
        expected_micro_mj = expected_mj * 1_000_000
        if data["MJ"] != expected_micro_mj:
            errors.append(
                f"{path.relative_to(ROOT)}: stale Assembly recipe price {data['MJ']}, "
                f"expected {expected_micro_mj} micro-MJ ({expected_mj} MJ)"
            )

if assembly_json_count != 170:
    errors.append(f"expected 170 committed Assembly recipe JSON files, found {assembly_json_count}")

for family in ("legacy", "modern"):
    require(
        f"source-families/{family}/src/main/java/buildcraft/robotics/BCRoboticsBoards.java",
        "LEGACY_RF_PER_MJ = 10",
        '"robot_picker", legacyRfToMj(8_000)',
        '"robot_lumberjack", legacyRfToMj(32_000)',
        '"robot_delivery", legacyRfToMj(128_000)',
        '"robot_builder", legacyRfToMj(512_000)',
        "legacyRfToMj(Math.round(160000 / probability))",
    )
    require(
        f"source-families/{family}/src/main/java/buildcraft/robotics/recipes/RobotIntegrationRecipe.java",
        "BuildCraft 7.1.27 charged 50,000 RF",
        "return 5_000L * MjAmount.MICRO_MJ_PER_MJ;",
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
print(" - BC8 assembly JSON/provider prices and BC7 robotics laser-energy balances are preserved")

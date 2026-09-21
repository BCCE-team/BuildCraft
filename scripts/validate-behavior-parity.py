#!/usr/bin/env python3
"""Offline guards for player-visible parity with the 1.19.2 Forge target."""
from __future__ import annotations

import json
import re
import sys
from pathlib import Path
from typing import Any

from source_layout import materialize_target

ROOT = Path(__file__).resolve().parents[1]
TARGETS = {
    target: materialize_target(target)
    for target in ("1.19.2-forge", "1.20.1-forge", "1.21.1-neoforge")
}
NEWER = ("1.20.1-forge", "1.21.1-neoforge")
ERRORS: list[str] = []


def fail(message: str) -> None:
    ERRORS.append(message)


def source(target: str, relative: str) -> Path:
    return TARGETS[target] / relative


def text(target: str, relative: str) -> str:
    path = source(target, relative)
    if not path.is_file():
        fail(f"{target}: missing {relative}")
        return ""
    return path.read_text(encoding="utf-8")


def require(target: str, relative: str, *needles: str) -> str:
    value = text(target, relative)
    for needle in needles:
        if needle not in value:
            fail(f"{target}: missing {needle!r} in {relative}")
    return value


def forbid(target: str, relative: str, *needles: str) -> str:
    value = text(target, relative)
    for needle in needles:
        if needle in value:
            fail(f"{target}: forbidden {needle!r} remains in {relative}")
    return value


def compact(value: str) -> str:
    return re.sub(r"\s+", " ", value).strip()


def require_compact(target: str, relative: str, *needles: str) -> str:
    value = compact(text(target, relative))
    for needle in needles:
        if compact(needle) not in value:
            fail(f"{target}: missing normalized fragment {needle!r} in {relative}")
    return value


def recipe_inventory(target: str) -> dict[str, tuple[str | None, int]]:
    data = TARGETS[target] / "src/main/resources/data"
    directory = "recipe" if target.startswith("1.21.1") else "recipes"
    result: dict[str, tuple[str | None, int]] = {}
    for namespace in sorted(p for p in data.iterdir() if p.is_dir()):
        root = namespace / directory
        if not root.is_dir():
            continue
        for path in sorted(root.rglob("*.json")):
            recipe_id = f"{namespace.name}:{path.relative_to(root).with_suffix('').as_posix()}"
            try:
                document = json.loads(path.read_text(encoding="utf-8"))
            except Exception as exc:
                fail(f"{target}: invalid recipe JSON {path.relative_to(TARGETS[target])}: {exc}")
                continue
            raw = document.get("result") if isinstance(document, dict) else None
            item: str | None = None
            count = 1
            if isinstance(raw, str):
                item = raw
            elif isinstance(raw, dict):
                candidate = raw.get("id", raw.get("item"))
                if isinstance(candidate, str):
                    item = candidate
                raw_count = raw.get("count", 1)
                if isinstance(raw_count, int):
                    count = raw_count
            result[recipe_id] = (item, count)
    return result


def loot_inventory(target: str) -> dict[str, Any]:
    data = TARGETS[target] / "src/main/resources/data"
    directory = "loot_table" if target.startswith("1.21.1") else "loot_tables"
    result: dict[str, Any] = {}
    for namespace in sorted(p for p in data.iterdir() if p.is_dir()):
        root = namespace / directory
        if not root.is_dir():
            continue
        for path in sorted(root.rglob("*.json")):
            loot_id = f"{namespace.name}:{path.relative_to(root).with_suffix('').as_posix()}"
            try:
                result[loot_id] = json.loads(path.read_text(encoding="utf-8"))
            except Exception as exc:
                fail(f"{target}: invalid loot-table JSON {path.relative_to(TARGETS[target])}: {exc}")
    return result


def validate_tick_cadence() -> None:
    rel = "src/main/java/buildcraft/transport/BCTransportEventDist.java"
    value = require("1.20.1-forge", rel, "WorldSavedDataWireSystems.get(event.level()).tick();", "PipeItemMessageQueue.serverTick();")
    if "event.phase" in value:
        fail("1.20.1-forge: transport ticks must not filter START/END phases")
    for target in NEWER:
        require(target, rel,
                "onWorldTick(BCEvents.LevelTick event)", "onServerTick(BCEvents.ServerTick event)",
                "PlatformEvents.levelTick(BCEvents.Phase.START", "PlatformEvents.levelTick(BCEvents.Phase.END",
                "PlatformEvents.serverTick(BCEvents.Phase.START", "PlatformEvents.serverTick(BCEvents.Phase.END")


def validate_java_invariants() -> None:
    engine = "src/main/java/buildcraft/energy/tile/TileEngineIron_BC8.java"
    gate = "src/main/java/buildcraft/silicon/recipe/GateLogicChangeRecipe.java"
    mining = "src/main/java/buildcraft/factory/tile/TileMiningWell.java"
    wrench = "src/main/java/buildcraft/core/item/ItemWrench.java"
    paint = "src/main/java/buildcraft/core/item/ItemPaintbrush_BC8.java"
    snapshot = "src/main/java/buildcraft/builders/snapshot/ClientSnapshots.java"
    wire = "src/main/java/buildcraft/transport/wire/WireSystem.java"
    oil_generator = "src/main/java/buildcraft/energy/generation/features/OilGenerator.java"
    oil_structure = "src/main/java/buildcraft/energy/generation/features/OilStructure.java"
    fluid = "src/main/java/buildcraft/lib/fluid/BCFluid.java"
    frame = "src/main/java/buildcraft/builders/block/BlockFrame.java"

    # The Iron Engine exposes three real tanks through IFluidHandler: fuel, coolant and residue.
    # Assert that contract independently on every maintained loader/version.
    for target in TARGETS:
        engine_text = require(target, engine,
            "public int getTanks()",
            "tankFuel.getFluid()", "tankCoolant.getFluid()", "tankResidue.getFluid()",
            "tankFuel.getCapacity()", "tankCoolant.getCapacity()", "tankResidue.getCapacity()",
            "isValidFuel(stack)", "isValidCoolant(stack)", "isResidue(stack)")
        normalized_engine = compact(engine_text)
        if "return 3;" not in normalized_engine:
            fail(f"{target}: Iron Engine fluid handler must expose exactly three tanks")
        for index, tank_name in ((0, "tankFuel"), (1, "tankCoolant"), (2, "tankResidue")):
            if not re.search(rf"case\s+{index}(?:\s*->|\s*:).*?{tank_name}\.getFluid\(\)", engine_text, re.DOTALL):
                fail(f"{target}: Iron Engine tank {index} must expose {tank_name}")
        if re.search(r"case\s+3(?:\s*->|\s*:)", engine_text):
            fail(f"{target}: unreachable Iron Engine tank index 3 remains")
        if "getTankCapacity(int tank) { return 0; }" in normalized_engine:
            fail(f"{target}: Iron Engine tank capacity must not be hard-coded to zero")
        if "isFluidValid(int tank, @NotNull FluidStack stack) { return false; }" in normalized_engine:
            fail(f"{target}: Iron Engine fluid validity must reflect the addressed tank")

    for target in NEWER:
        gate_text = require(target, gate, "if (!stack.isEmpty() && stack.getItem() instanceof ItemPluggableGate)")
        if "else if (!stack.isEmpty())" in gate_text:
            fail(f"{target}: gate-logic recipe rejects unrelated occupied slots")
        require_compact(target, mining,
            "int limit = BCCoreConfig.miningMaxDepth; int high = worldPosition.getY() + 64; return high < limit ? high : limit;")
        require(target, wrench,
            "result == InteractionResult.SUCCESS && player != null",
            "player.swingingArm = hand;")
        forbid(target, wrench, "result.consumesAction()", "player.swing(hand, true)")
        require(target, paint,
            "result != InteractionResult.SUCCESS",
            "return InteractionResult.FAIL;",
            "return InteractionResult.SUCCESS;")
        forbid(target, paint, "result.consumesAction()", "InteractionResult.sidedSuccess")
        snapshot_text = require(
            target,
            snapshot,
            "3D blueprint/template previews are intentionally disabled",
            "Intentionally disabled on all maintained Minecraft versions.",
            "public void renderSnapshot",
        )
        for token in ("RenderSystem.", "FakeWorld", "renderBlocks(", "renderBlockEntities(", "renderEntities("):
            if token in snapshot_text:
                fail(f"{target}: disabled snapshot preview still contains live renderer token {token!r}")
        require(target, wire, "chunkMap.getPlayers(chunkPos, false).contains(serverPlayer)")
        forbid(target, wire, "inBlockTickingRange", "isPlayerWatchingChunk")
        forbid(target, oil_generator, "genOilInEveryVanillaBiomes", "genOilInEveryModBiomes")
        require(target, oil_structure, "state.blocksMotion()")
        forbid(target, oil_structure, "OilGenerator.createTube(start, Math.max(0")
        require(target, fluid,
            ".is(Blocks.NETHER_PORTAL)",
            ".is(Blocks.END_PORTAL)",
            ".is(Blocks.END_GATEWAY)",
            ".is(Blocks.STRUCTURE_VOID)",
            ".getFluidState().isEmpty()")
        require(target, frame, "Direction.fromDelta")
        forbid(target, frame, "if (d != null)")

    legacy_snapshot = require(
        "1.19.2-forge",
        snapshot,
        "3D blueprint/template previews are intentionally disabled",
        "Intentionally disabled on all maintained Minecraft versions.",
        "public void renderSnapshot",
    )
    for token in ("RenderSystem.", "FakeWorld", "BufferUploader.drawWithShader"):
        if token in legacy_snapshot:
            fail(f"1.19.2-forge: disabled snapshot preview still contains live renderer token {token!r}")

    for target in NEWER:
        if target != "1.21.1-neoforge":
            require(target, "src/main/java/buildcraft/core/BCCoreItems.java",
                    'MARKER_CONNECTOR = ITEMS.register("marker_connector"')
        core_items = text(target, "src/main/java/buildcraft/core/BCCoreItems.java")
        marker_line = next((line for line in core_items.splitlines() if "MARKER_CONNECTOR" in line and "register" in line), "")
        if ".stacksTo(1)" not in marker_line:
            fail(f"{target}: marker connector must stack to one")

    require_compact("1.21.1-neoforge", "src/main/java/buildcraft/lib/recipe/AssemblyRecipe.java",
                    "public Set<ItemStack> getOutputPreviews() { return ImmutableSet.of(); }")

    for target in TARGETS:
        require(target, "src/main/java/buildcraft/builders/block/BlockConstructionMarker.java",
                "WrenchUtil.isWrench(held)")
    require("1.21.1-neoforge", "src/main/java/buildcraft/transport/pipe/SchematicBlockPipe.java",
            "worldPipe.getDefinition().identifier.toString()",
            "NBTUtilBC.writeEnum(worldPipe.getColour())")



def validate_fluid_filter_safety() -> None:
    array_filter = "src/main/java/buildcraft/lib/inventory/filter/ArrayFluidFilter.java"
    diamond_fluid = "src/main/java/buildcraft/transport/pipe/behaviour/PipeBehaviourDiamondFluid.java"
    diamond_wood = "src/main/java/buildcraft/transport/pipe/behaviour/PipeBehaviourWoodDiamond.java"

    for target in TARGETS:
        require(target, array_filter,
                "FluidUtil.getFluidContained(stacks.get(i)).orElse(FluidStack.EMPTY)",
                "if (!fluid.isEmpty() && fluid.getAmount() > 0)")
        array_text = text(target, array_filter)
        if re.search(r"getFluidContained\(stacks\.get\(i\)\).*?else\s+return;", array_text, re.DOTALL):
            fail(f"{target}: ArrayFluidFilter must skip empty/non-fluid slots instead of stopping at the first gap")

        require(target, diamond_fluid,
                "FluidUtil.getFluidContained(compareTo).orElse(FluidStack.EMPTY)",
                "if (target.isEmpty() || target.getAmount() <= 0)")
        forbid(target, diamond_fluid, "FluidUtil.getFluidContained(compareTo).get()")

        require(target, diamond_wood,
                "ArrayFluidFilter fluidFilter = new ArrayFluidFilter(filters.stacks);",
                "if (!fluidFilter.hasFilter())",
                "FluidUtil.getFluidContained(stack).orElse(FluidStack.EMPTY)",
                "return FluidStack.EMPTY;")
        forbid(target, diamond_wood,
               "FluidUtil.getFluidContained(stack).get()",
               "return null;")

def validate_protection_invariants() -> None:
    stripes = "src/main/java/buildcraft/transport/stripes/StripesHandlerShears.java"
    require("1.21.1-neoforge", stripes,
            "import net.minecraft.server.level.ServerLevel;",
            "BlockUtil.canBreakBlock(serverLevel, pos, player)",
            "shearableBlock.onSheared(player, stack, world, pos)")
    forbid("1.21.1-neoforge", stripes, "shearableBlock.onSheared(null")


def validate_robot_invariants() -> None:
    rel = "src/main/java/buildcraft/robotics/entity/EntityRobot.java"
    for target in ("1.20.1-forge", "1.21.1-neoforge"):
        value = text(target, rel)
        dock = re.search(r"public void dock\(DockingStation station\)\s*\{(.*?)\n\s*}", value, re.DOTALL)
        if not dock:
            fail(f"{target}: cannot locate robot dock method")
        elif "mainStationReleasedManually = false" in dock.group(1):
            fail(f"{target}: docking must not clear the manual-release state")
    require("1.21.1-neoforge", rel,
            'tag.put("owner", writeOwnerProfile(owner));',
            'owner = tag.contains("owner") ? readOwnerProfile',
            "private static GameProfile readOwnerProfile",
            "private static CompoundTag writeOwnerProfile",
            "FakePlayerProvider.INSTANCE.getFakePlayer(",
            "serverLevel, getOwnerProfile(), blockPosition()")
    attack = re.search(
        r"public void attackTargetEntityWithCurrentItem\(Entity target\)\s*\{(.*?)\n\s*private static float applyAttributeModifier",
        text("1.21.1-neoforge", rel),
        re.DOTALL,
    )
    if not attack:
        fail("1.21.1-neoforge: cannot locate robot attack method")
    elif "getBuildCraftPlayer(serverLevel)" in attack.group(1):
        fail("1.21.1-neoforge: robot attacks must use the owner-aware fake player")
    forbid("1.21.1-neoforge", rel, "amount = damageEvent.getAmount();")


def validate_target_specific_persistence() -> None:
    tank_rel = "src/main/java/buildcraft/lib/fluid/Tank.java"
    tank = require("1.21.1-neoforge", tank_rel,
                   "FluidStack fluid = FluidCompatRegistry.canonicalize(FluidStackUtil.parseOptional(nbt));",
                   "this.fluid = fluid;")
    outer = tank.find("FluidStack fluid = FluidCompatRegistry.canonicalize(FluidStackUtil.parseOptional(nbt));")
    named = tank.find("if (nbt.contains(name")
    if outer < 0 or named < 0 or outer > named:
        fail("1.21.1-neoforge: tank fluid must be parsed from the outer tag before the named child")

    pipe_rel = "src/main/java/buildcraft/transport/pipe/flow/PipeFlowItems.java"
    require("1.21.1-neoforge", pipe_rel,
            "private ListTag pendingItems;",
            "private final ListTag unreadableItems = new ListTag();",
            "pendingItems = nbt.getList(\"items\", Tag.TAG_COMPOUND).copy();",
            "private void loadPendingItems()",
            "if (level == null)",
            "pendingItems.copy()",
            "new TravellingItem(itemTag, tickNow, level.registryAccess())")


def validate_legacy_itemstack_migration() -> None:
    rel = "src/main/java/buildcraft/lib/misc/ItemStackUtil.java"
    require("1.21.1-neoforge", rel,
            "CompoundTag normalized = normalizeLegacyNbt(tag);",
            'normalized.contains("Count", Tag.TAG_ANY_NUMERIC)',
            'components.put("minecraft:custom_data", legacyData.copy());',
            'fallback.set(DataComponents.DAMAGE, Math.max(0, legacyData.getInt("Damage")));')


def validate_persistence_and_reload_invariants() -> None:
    # Saved Builder/Filler tasks already own reserved resources. Reload must cancel them through the
    # normal virtual cancellation path before rescanning the live world.
    snapshot_rel = "src/main/java/buildcraft/builders/snapshot/SnapshotBuilder.java"
    snapshot = require("1.21.1-neoforge", snapshot_rel,
                       'NBTUtilBC.readCompoundList(nbt.get("breakTasks"))',
                       ".forEach(this::cancelBreakTask);",
                       'NBTUtilBC.readCompoundList(nbt.get("placeTasks"))',
                       ".map(tag -> new PlaceTask(tag, registries))",
                       ".forEach(this::cancelPlaceTask);",
                       "forceRecheckCurrentTask();",
                       "flushPendingPowerRefund();")
    refund_pos = snapshot.find('NBTUtilBC.readCompoundList(nbt.get("breakTasks"))')
    recheck_pos = snapshot.find("forceRecheckCurrentTask();", refund_pos)
    if refund_pos < 0 or recheck_pos < 0 or refund_pos > recheck_pos:
        fail("1.21.1-neoforge: saved builder tasks must be refunded before forcing a rescan")

    # Builder/Filler/Quarry work is transactional: reserve exact physical power/materials, mutate the world,
    # then commit or refund. These guards catch progress-as-MJ duplication and partial-reservation losses.
    blueprint_rel = "src/main/java/buildcraft/builders/snapshot/BlueprintBuilder.java"
    quarry_rel = "src/main/java/buildcraft/builders/tile/TileQuarry.java"
    quarry_render_rel = "src/main/java/buildcraft/builders/client/render/RenderQuarry.java"
    template_rel = "src/main/java/buildcraft/builders/snapshot/TemplateBuilder.java"
    for target in TARGETS:
        require(target, snapshot_rel,
                "reservedPower",
                "queuePowerRefund(breakTask.reservedPower)",
                "queuePowerRefund(placeTask.reservedPower)",
                "legacyReservedPowerForProgress",
                "capturePlacementRollback(placeTask.pos)",
                "rollbackPlacement(rollback, placeTask.pos)",
                "keeping reservations consumed",
                "if (!isBlockCorrect(placeTask.pos))")
        require(target, blueprint_rel,
                "rollbackReservation(reserved, drained)",
                "refundReservedItem",
                "if (!tile.needMeterial() || placeTask.items == null)",
                "FluidAction.SIMULATE",
                "FluidAction.EXECUTE",
                "boolean committed = isBlockCorrect(blockPos)",
                "isSchematicEntityPresent(schematicEntity, level)")
        require(target, template_rel,
                "!tile.getInvResources().extract(PLACEABLE_BLOCK_FILTER, 1, 1, true).isEmpty()",
                "!tile.needMeterial()",
                "Collections.singletonList(reserved.copy())")
        quarry = require(target, quarry_rel,
                "pendingTaskPowerRefund",
                "reservedPower",
                "addPower(added, withdrawn)",
                "queueTaskPowerRefund(reservedPower)",
                "cancelCurrentTaskWithRefund()",
                "fluid.getFluidType().getViscosity() <= 1000",
                "rescanVisitedMiningColumns()",
                "MINING_COLUMN_WATCHDOG_SCANS_PER_TICK",
                "Heightmap.Types.WORLD_SURFACE",
                "if (!frameBreakBlockPoses.contains(blockPos))",
                "canMoveThrough(pos) || !canMine(pos) || !canMoveDownTo(pos)",
                "deniedBreakUntil.remove(breakPos)")
        frame_break_start = quarry.find("if (!frameBreakBlockPoses.isEmpty())")
        frame_place_start = quarry.find("if (!framePlaceFramePoses.isEmpty())", frame_break_start)
        if frame_break_start < 0 or frame_place_start < 0:
            fail(f"{target}: quarry frame-break scheduling section is missing")
        frame_break_section = quarry[frame_break_start:frame_place_start]
        check_pos = frame_break_section.find("check(blockPos);")
        permission_pos = frame_break_section.find("mayStartBreakTask(blockPos)")
        if check_pos < 0 or permission_pos < 0 or check_pos > permission_pos:
            fail(f"{target}: quarry must reconcile a queued frame-break position before permission probing")

        block_util = require(target, "src/main/java/buildcraft/lib/misc/BlockUtil.java",
                "if (!harvestBlock(world, pos, tool, owner))",
                "if (!destroyBlock(world, pos, tool, owner))")
        harvest_fallback = block_util.find("if (!harvestBlock(world, pos, tool, owner))")
        destroy_fallback = block_util.find("if (!destroyBlock(world, pos, tool, owner))", harvest_fallback)
        if harvest_fallback < 0 or destroy_fallback < 0:
            fail(f"{target}: block break/drop helper lost the BC8 non-harvest destroy fallback")

        require(target, quarry_render_rel,
                "if (!aabb.isEmpty())",
                "Keep the normal idle offset for such transient/stale tasks")

    # Unknown/custom pipe payloads must survive missing registrations and unchecked migration/codec failures.
    holder_rel = "src/main/java/buildcraft/transport/tile/TilePipeHolder.java"
    require("1.21.1-neoforge", holder_rel,
            "else if (unknownData != null)",
            'nbt.put("pipe", unknownData.copy());',
            'CompoundTag pipeData = bcData.readCompound("pipe");',
            "catch (InvalidInputDataException | RuntimeException e)",
            "unknownData = pipeData.copy();")
    forbid("1.21.1-neoforge", holder_rel, "unknownData = nbt.copy();")

    # Deferred blueprint inventory slots are local to each block entity. Combined double-chest capabilities
    # would merge both halves and make local slots 0-26 ambiguous.
    schematic_rel = "src/main/java/buildcraft/builders/snapshot/SchematicBlockDefault.java"
    for target in NEWER:
        schematic = require(target, schematic_rel,
                            "blockEntity instanceof Container container",
                            "PlatformStorage.localInventory(container)",
                            "getDeferredInventoryHandler")
        if schematic.count("getDeferredInventoryHandler(") < 3:
            fail(f"{target}: deferred blueprint inventory helper must be used by both missing-item and insert paths")

    # BC8 gives fluid-container interaction priority over the held item's world-use path. The item handler owns the
    # container transition: BuildCraft fuel buckets must become empty buckets and fragile shards must disappear.
    # Do not manually fill the machine and then synthesize inventory state as a separate transaction.
    fluid_rel = "src/main/java/buildcraft/lib/misc/FluidUtilBC.java"
    for target in ("1.19.2-forge", "1.20.1-forge"):
        require(target, fluid_rel,
                "ItemStack transferStack = replace && single ? held : held.copy();",
                "getCapability(ForgeCapabilities.FLUID_HANDLER_ITEM)",
                "new FluidBucketWrapper(transferStack)",
                "fragile.new FragileFluidHandler(transferStack)",
                "ItemStack result = flItem.getContainer().copy();",
                "broadcastFullState()",
                "BC8 consumed the interaction as soon as the held item was a fluid container")

    require("1.21.1-neoforge", fluid_rel,
            "FluidUtil.getFluidHandler(held.copy()).isPresent()",
            "FluidUtil.interactWithFluidHandler(player, hand, fluidHandler)",
            "broadcastFullState()",
            "Keep BC8's interaction-claim behaviour")

    # GUI insertion uses the same item-handler-owned remainder. The explicit bucket/shard branches are only
    # capability-registration fallbacks on NeoForge; Forge uses deterministic wrapper fallbacks.
    tank_rel = "src/main/java/buildcraft/lib/fluid/Tank.java"
    for target in ("1.19.2-forge", "1.20.1-forge"):
        require(target, tank_rel,
                "ItemStack probe = stack.copy();",
                "getCapability(ForgeCapabilities.FLUID_HANDLER_ITEM)",
                "new FluidBucketWrapper(probe)",
                "fluidHandler.getContainer().copy()",
                "broadcastFullState()")
    require("1.21.1-neoforge", tank_rel,
            "ItemStack probe = stack.copy();",
            "FluidUtil.getFluidHandler(probe).orElse(null)",
            "fluidHandler.getContainer().copy()",
            "Defensive fallbacks",
            "broadcastFullState()")

    # These GameTests use an actual BuildCraft light-fuel bucket/shard and the combustion-engine GUI cursor path.
    # They validate observable behavior rather than implementation shape.
    engine_test_rel = "src/gametest/java/buildcraft/gametest/BuildCraftLogicGameTests.java"
    for target in TARGETS:
        require(target, engine_test_rel,
                "combustionEngineConsumesBuildCraftFuelContainersOnDirectUse",
                "BuildCraft fuel bucket survived direct combustion-engine insertion in survival",
                "BuildCraft fuel shard survived direct combustion-engine insertion in survival",
                "combustionEngineGuiReturnsEmptyBucketForBuildCraftFuel",
                "GUI BuildCraft fuel insertion deleted the bucket instead of returning an empty bucket")

    # Combustion-engine shift-click indices are menu-slot indices. Writing playerInventory.getItem(index) directly
    # aliases hotbar/main-inventory slots incorrectly; all targets must resolve through slots.get(index).
    engine_menu_rel = "src/main/java/buildcraft/energy/menu/ContainerEngineIron_BC8.java"
    for target in TARGETS:
        menu = require(target, engine_menu_rel, "Slot slot = slots.get(index);", "broadcastFullState()")
        if "playerInventory.getItem(index)" in menu or "playerInventory.setItem(index" in menu:
            fail(f"{target}: combustion-engine quick move uses raw inventory indices instead of menu slots")

    # Combustion engines intentionally pass pipe items through to item-use, exactly like BC8, so a pipe can be placed
    # against the engine instead of the GUI stealing the click.
    engine_rel = "src/main/java/buildcraft/energy/tile/TileEngineIron_BC8.java"
    for target in TARGETS:
        engine = require(target, engine_rel, "instanceof IItemPipe", "InteractionResult.PASS")
        if engine.find("FluidUtilBC.onTankActivated") > engine.find("instanceof IItemPipe"):
            fail(f"{target}: combustion-engine fluid containers must be handled before pipe-item pass-through")

    # Distiller refunds and progress are persistent state, including corruption-safe non-negative clamps.
    distiller_rel = "src/main/java/buildcraft/factory/tile/TileDistiller.java"
    require("1.21.1-neoforge", distiller_rel,
            'distillPower = Math.max(0, bcData.readLong("distillPower"));',
            'pendingPowerRefund = Math.max(0, bcData.readLong("pendingPowerRefund"));')

    # Resource reloads invalidate expression nodes before reparsing variable models.
    event_rel = "src/main/java/buildcraft/lib/BCLibEventDist.java"
    for target in ("1.20.1-forge",):
        event_text = compact(text(target, event_rel))
        sequence = compact("ModelVariableData.onModelBake(); ModelHolderRegistry.reloadVariableModels();")
        if sequence not in event_text:
            fail(f"{target}: variable-model generation must advance before variable models are reparsed")

    # NeoForge's stitched-atlas event must invalidate sprite objects cached across resource generations.
    require("1.21.1-neoforge", event_rel,
            "SpriteUtil.clearAtlasCache();",
            "DebugRenderHelper.clearTextureCache();")


def validate_worldgen_resources() -> None:
    relatives = (
        "src/main/resources/data/buildcraftenergy/worldgen/configured_feature/oil_configured_feature.json",
        "src/main/resources/data/buildcraftenergy/worldgen/placed_feature/oil_placed_feature.json",
    )
    for target in NEWER:
        for relative in relatives:
            path = source(target, relative)
            try:
                document = json.loads(path.read_text(encoding="utf-8"))
            except Exception as exc:
                fail(f"{target}: invalid oil worldgen JSON {relative}: {exc}")
                continue
            serialized = json.dumps(document, sort_keys=True)
            if '"genOilInEveryModBiomes": false' not in serialized:
                fail(f"{target}: genOilInEveryModBiomes must stay false in {relative}")


def validate_advancements() -> None:
    for target in ("1.21.1-neoforge",):
        data = TARGETS[target] / "src/main/resources/data"
        all_files = sorted(data.glob("*/advancement/**/*.json"))
        recipe_files = sorted(data.glob("*/advancement/recipe/**/*.json"))
        if len(all_files) != 191:
            fail(f"{target}: expected 191 advancements, found {len(all_files)}")
        if len(recipe_files) != 147:
            fail(f"{target}: expected 147 recipe advancements, found {len(recipe_files)}")
        recipe_ids = set(recipe_inventory(target))
        referenced_recipes: set[str] = set()
        for path in all_files:
            try:
                document = json.loads(path.read_text(encoding="utf-8"))
            except Exception as exc:
                fail(f"{target}: invalid advancement JSON {path.relative_to(TARGETS[target])}: {exc}")
                continue
            if not isinstance(document, dict):
                fail(f"{target}: advancement root is not an object: {path.relative_to(TARGETS[target])}")
                continue
            rewards = document.get("rewards")
            if isinstance(rewards, dict) and isinstance(rewards.get("recipes"), list):
                referenced_recipes.update(item for item in rewards["recipes"] if isinstance(item, str))
        missing_references = sorted(referenced_recipes - recipe_ids)
        if missing_references:
            fail(f"{target}: advancement rewards reference missing recipes: {missing_references[:8]}")


def validate_resource_parity() -> None:
    recipes = {target: recipe_inventory(target) for target in TARGETS}
    if recipes["1.20.1-forge"] != recipes["1.19.2-forge"]:
        missing = sorted(set(recipes["1.19.2-forge"]) - set(recipes["1.20.1-forge"]))
        extra = sorted(set(recipes["1.20.1-forge"]) - set(recipes["1.19.2-forge"]))
        changed = sorted(k for k in recipes["1.19.2-forge"].keys() & recipes["1.20.1-forge"].keys()
                         if recipes["1.19.2-forge"][k] != recipes["1.20.1-forge"][k])
        fail(f"1.20.1-forge: recipe output inventory differs; missing={missing[:4]}, extra={extra[:4]}, changed={changed[:4]}")

    expected_121 = dict(recipes["1.19.2-forge"])
    expected_121.pop("buildcraftcompat:pipe_item_propolis", None)
    for target in ("1.21.1-neoforge",):
        if recipes[target] != expected_121:
            missing = sorted(set(expected_121) - set(recipes[target]))
            extra = sorted(set(recipes[target]) - set(expected_121))
            changed = sorted(k for k in expected_121.keys() & recipes[target].keys()
                             if expected_121[k] != recipes[target][k])
            fail(f"{target}: recipe output inventory differs; missing={missing[:4]}, extra={extra[:4]}, changed={changed[:4]}")

    loot = {target: loot_inventory(target) for target in TARGETS}
    baseline_ids = set(loot["1.19.2-forge"])
    if len(baseline_ids) != 31:
        fail(f"1.19.2-forge: expected 31 loot tables, found {len(baseline_ids)}")
    for target in NEWER:
        if set(loot[target]) != baseline_ids:
            fail(f"{target}: loot-table ID inventory differs from 1.19.2")
        elif loot[target] != loot["1.19.2-forge"]:
            fail(f"{target}: loot-table contents differ from 1.19.2")





def validate_gameplay_gap_fixes() -> None:
    pump = "src/main/java/buildcraft/factory/tile/TilePump.java"
    snapshot = "src/main/java/buildcraft/builders/snapshot/SnapshotBuilder.java"
    template = "src/main/java/buildcraft/builders/snapshot/TemplateBuilder.java"
    block_util = "src/main/java/buildcraft/lib/misc/BlockUtil.java"
    frame = "src/main/java/buildcraft/builders/block/BlockFrame.java"
    schematic = "src/main/java/buildcraft/builders/snapshot/SchematicBlockDefault.java"

    for target in TARGETS:
        require(target, pump,
                "INFINITE_WATER_NEIGHBORS",
                "isInfiniteWaterSourceAt(posToCheck)",
                "adjacentSources >= 2")
        pump_text = text(target, pump)
        for needle in (
            "isWaterSource(neighbour)",
            "private static boolean isWaterSource(FluidState state)",
            "fluid == Fluids.WATER || (state.isSource() && isWater(state))",
            "FluidTags.WATER",
        ):
            if needle not in pump_text:
                fail(f"{target}: missing {needle!r} in {pump}")
        unloaded_guard = re.search(
            r"if\s*\(\s*!level\.hasChunkAt\(neighbourPos\)\s*\)\s*\{\s*(?P<body>.*?)\s*\}",
            pump_text,
            re.DOTALL,
        )
        if unloaded_guard is None or "continue;" not in unloaded_guard.group("body"):
            fail(f"{target}: unloaded infinite-water neighbour must be skipped, not reject the source")
        neighbor_decl = re.search(
            r"INFINITE_WATER_NEIGHBORS\s*=\s*new Direction\[\]\s*\{([^}]*)\}",
            pump_text,
            re.DOTALL,
        )
        if neighbor_decl is None:
            fail(f"{target}: cannot inspect infinite-water neighbour set")
        else:
            directions = neighbor_decl.group(1)
            for direction in ("Direction.NORTH", "Direction.SOUTH", "Direction.WEST", "Direction.EAST"):
                if direction not in directions:
                    fail(f"{target}: infinite-water detection is missing horizontal neighbour {direction}")
            for direction in ("Direction.UP", "Direction.DOWN"):
                if direction in directions:
                    fail(f"{target}: infinite-water detection must not count vertical neighbour {direction}")

        require(target, snapshot,
                "else if (canPlace(blockPos))",
                "checkResults[i] = CHECK_RESULT_TO_PLACE;")
        snapshot_text = text(target, snapshot)
        place_index = snapshot_text.find("else if (canPlace(blockPos))")
        break_index = snapshot_text.find("else if (!tile.getWorldBC().isEmptyBlock(blockPos))")
        if place_index < 0 or break_index < 0 or place_index > break_index:
            fail(f"{target}: replaceable placement must be classified before excavation")

        require(target, template, "BlockUtil.isReplaceable(tile.getWorldBC(), blockPos)")
        require(target, block_util, "public static boolean isReplaceable(Level world, BlockPos pos)")
        require(target, schematic,
                "getPlacementState(Level level, BlockPos blockPos)",
                ".canSurvive(level, blockPos)",
                "BlockStateProperties.PERSISTENT",
                "newBlockState.setValue(BlockStateProperties.PERSISTENT, true)",
                "if (!newBlockState.canSurvive(level, blockPos))",
                "BlockState expectedState = getPlacementState(world, blockPos);",
                "BlockUtil.blockStatesWithoutBlockEqual(expectedState, blockState2, ignoredProperties)")
        forbid(target, schematic,
               "newBlockState = newBlockState.rotate(level, blockPos, tileRotation);")

    for target in ("1.20.1-forge", "1.21.1-neoforge"):
        require(target, frame, ".forceSolidOn()")


def validate_modpack_interop_fixes() -> None:
    wrench_util = "src/main/java/buildcraft/lib/misc/WrenchUtil.java"
    wrench_service = "src/main/java/buildcraft/lib/internal/api/v2/WrenchServiceImpl.java"
    core_config = "src/main/java/buildcraft/core/BCCoreConfig.java"
    pipe_holder = "src/main/java/buildcraft/transport/block/BlockPipeHolder.java"

    for target in TARGETS:
        require(target, wrench_util, "BuildCraftServices.WRENCHES", ".isWrench(stack)")
        wrench_text = require(target, wrench_service,
                'WRENCH_TAG_NAMESPACE = "c"',
                'WRENCH_TAG_PATH = "tools/wrench"',
                "stack.getItem() instanceof IToolWrench",
                "BCLibConfig.useWrenchTag && stack.getTags().anyMatch")
        legacy_wrench = wrench_text.find("stack.getItem() instanceof IToolWrench")
        tagged_wrench = wrench_text.find("BCLibConfig.useWrenchTag && stack.getTags().anyMatch")
        if legacy_wrench < 0 or tagged_wrench < 0 or legacy_wrench > tagged_wrench:
            fail(f"{target}: internal IToolWrench fallback must remain independent of the common-tag config toggle")
        require(target, core_config,
                '.define("useWrenchTag", true)',
                "BCLibConfig.useWrenchTag = propUseWrenchTag.get();")
        require(target, pipe_holder,
                "implements ICustomPaintHandler, EntityBlock, SimpleWaterloggedBlock",
                "BlockStateProperties.WATERLOGGED",
                "builder.add(WATERLOGGED)",
                "fluid.getType() == Fluids.WATER",
                "Fluids.WATER.getSource(false)",
                "world.scheduleTick(pos, Fluids.WATER, Fluids.WATER.getTickDelay(world))")

    legacy_tag = ROOT / "source-families/legacy/src/main/resources/data/c/tags/items/tools/wrench.json"
    modern_tag = ROOT / "source-families/modern/src/main/resources/data/c/tags/item/tools/wrench.json"
    for tag_path in (legacy_tag, modern_tag):
        if not tag_path.is_file():
            fail(f"missing common wrench tag: {tag_path.relative_to(ROOT)}")
        elif '"buildcraftcore:wrench"' not in tag_path.read_text(encoding="utf-8"):
            fail(f"common wrench tag does not contain BuildCraft wrench: {tag_path.relative_to(ROOT)}")

    blockstate = ROOT / "source-shared/src/main/resources/assets/buildcrafttransport/blockstates/pipe_holder.json"
    if blockstate.is_file():
        blockstate_text = blockstate.read_text(encoding="utf-8")
        for variant in ('"waterlogged=false"', '"waterlogged=true"'):
            if variant not in blockstate_text:
                fail(f"pipe holder blockstate is missing {variant}")
    else:
        fail("missing shared pipe holder blockstate")

    # Waterlogging changes the baked block-model keys. Every target must replace the
    # two real blockstate variants with ModelPipe; replacing only the old empty
    # variant leaves the JSON fallback model in-world and breaks pipe/pluggable rendering.
    transport_models = "src/main/java/buildcraft/transport/BCTransportModels.java"
    for target in TARGETS:
        require(target, transport_models,
                'putModel(event, "pipe_holder#waterlogged=false", ModelPipe.INSTANCE);',
                'putModel(event, "pipe_holder#waterlogged=true", ModelPipe.INSTANCE);')
        forbid(target, transport_models,
               'putModel(event, "pipe_holder", ModelPipe.INSTANCE);')

    # Double-chest pairing is runtime-managed. The first half can temporarily revert to
    # SINGLE before the neighbour exists, but placement must still preserve the captured
    # LEFT/RIGHT state so the final pair can form. Therefore this exception belongs only
    # in structural equality, not in the generic ignoredProperties rule list.
    schematic = "src/main/java/buildcraft/builders/snapshot/SchematicBlockDefault.java"
    for target in TARGETS:
        require(target, schematic,
                "expectedState.hasProperty(BlockStateProperties.CHEST_TYPE)",
                "blockState2.hasProperty(BlockStateProperties.CHEST_TYPE)",
                "comparisonIgnored.add(BlockStateProperties.CHEST_TYPE)",
                "BlockUtil.blockStatesWithoutBlockEqual(expectedState, blockState2, comparisonIgnored)")

    inventory_rules = ROOT / (
        "source-shared/src/main/resources/assets/buildcraftbuilders/compat/buildcraft/"
        "builders/vanilla/blocks_simple_inventories.json"
    )
    if not inventory_rules.is_file():
        fail("missing shared simple-inventory builder rules")
    else:
        try:
            inventory_doc = json.loads(inventory_rules.read_text(encoding="utf-8"))
        except Exception as exc:
            fail(f"invalid simple-inventory builder rules: {exc}")
        else:
            selectors = inventory_doc[0].get("selectors", []) if isinstance(inventory_doc, list) and inventory_doc and isinstance(inventory_doc[0], dict) else []
            for chest in ("minecraft:chest", "minecraft:trapped_chest"):
                if chest not in selectors:
                    fail(f"simple-inventory builder rules are missing {chest}")
            ignored = inventory_doc[0].get("ignoredProperties", []) if isinstance(inventory_doc, list) and inventory_doc and isinstance(inventory_doc[0], dict) else []
            if "type" in ignored:
                fail("chest 'type' must not be a generic ignoredProperties entry because that also rewrites placement state")


def validate_jei_facade_scalability() -> None:
    plugin = "src/main/java/buildcraft/compat/jei/BuildCraftJeiPlugin.java"
    creative = "src/main/java/buildcraft/lib/CreativeTabManager.java"
    for target in TARGETS:
        require(target, plugin,
                'return data.getCompound("facade").toString();',
                "collectJeiFacadeInfos()",
                "unique.putIfAbsent(new ItemStackKey(required), info)",
                "recipe.inputStacks()",
                "recipe.solidOutputs()",
                "recipe.hollowOutputs()",
                "builder.createFocusLink(facadeInputSlot, solidOutputSlot, hollowOutputSlot)")
        forbid(target, plugin,
               'return instance.isHollow() ? "hollow" : "solid";',
               'return instance.isHollow() ? "phased_hollow" : "phased_solid";',
               "getFocusedFacadeInputInfos",
               "getRepresentativeFacadeInfo()")
        require(target, creative,
                "Set<ItemStackKey> seen = new HashSet<>();",
                "seen.add(new ItemStackKey(normalized))")
        forbid(target, creative,
               "for (ItemStack existing : items) {\n                if (ItemStack.isSameItemSame")

    hidden_tags = (
        ROOT / "source-families/legacy/src/main/resources/data/c/tags/items/hidden_from_recipe_viewers.json",
        ROOT / "source-families/modern/src/main/resources/data/c/tags/item/hidden_from_recipe_viewers.json",
    )
    for tag_path in hidden_tags:
        if not tag_path.is_file():
            fail(f"missing facade recipe-viewer visibility tag: {tag_path.relative_to(ROOT)}")
        elif '"buildcraftsilicon:plug/facade"' not in tag_path.read_text(encoding="utf-8"):
            fail(f"facade item missing from recipe-viewer visibility tag: {tag_path.relative_to(ROOT)}")


def validate_forestry_model_bake_mutation() -> None:
    rel = "src/main/java/buildcraft/compat/forestry/pipe/client/ForestryCompatClient.java"
    require("1.20.1-forge", rel,
            "private static void onModelBake(ModelEvent.ModifyBakingResult event)",
            "event.getModels().put(")
    forbid("1.20.1-forge", rel,
           "private static void onModelBake(ModelEvent.BakingCompleted event)")


def validate_forge_atlas_reload_caches() -> None:
    transport = "src/main/java/buildcraft/transport/BCTransportEventDist.java"
    silicon = "src/main/java/buildcraft/silicon/BCSilicon.java"

    # Forge's stitch event hands us the atlas generation that has just been rebuilt.
    # Dynamic BuildCraft quads cache absolute atlas UVs, so never repopulate the pipe
    # sprite table by querying Minecraft's global ModelManager from BakingCompleted.
    for target in ("1.20.1-forge",):
        require(target, transport,
                "TextureStitchEvent.Post",
                "InventoryMenu.BLOCK_ATLAS.equals(event.getAtlas().location())",
                "PipeBaseModelGenStandard.loadSpritesCache(event.getAtlas())",
                "clearAtlasDependentPipeCaches();")
        forbid(target, transport, "PipeBaseModelGenStandard.loadSpritesCache();")

        # Gates, lenses, facades and other pluggables also bake atlas-relative UVs.
        # Their caches must be invalidated for every fresh block-atlas generation.
        require(target, silicon,
                "TextureStitchEvent.Post",
                "InventoryMenu.BLOCK_ATLAS.equals(event.getAtlas().location())",
                "BCSiliconModels.clearAtlasDependentCaches();")

    # Assert the same atlas-cache invalidation invariant explicitly on the reference targets.
    require("1.19.2-forge", transport,
            "TextureStitchEvent.Post",
            "PipeBaseModelGenStandard.loadSpritesCache(event.getAtlas())")
    require("1.19.2-forge", silicon,
            "TextureStitchEvent.Post",
            "BCSiliconModels.clearAtlasDependentCaches();")
    require("1.21.1-neoforge", transport,
            "TextureAtlasStitchedEvent",
            "PipeBaseModelGenStandard.loadSpritesCache(event.getAtlas())")
    require("1.21.1-neoforge", silicon,
            "TextureAtlasStitchedEvent",
            "BCSiliconModels.clearAtlasDependentCaches();")


def validate_pipe_pluggable_contract() -> None:
    pluggable = "src/main/java/buildcraft/transport/internal/pluggable/PipePluggable.java"
    for target in TARGETS:
        require(target, pluggable,
                "public boolean isSideSolid()",
                "public float getExplosionResistance(@Nullable Entity exploder, Explosion explosion)",
                "public boolean canConnectToRedstone(@Nullable Direction to)")

    plugin = "src/main/java/buildcraft/compat/jei/BuildCraftJeiPlugin.java"
    require("1.19.2-forge", plugin, "return super.getResultItem();")
    forbid("1.19.2-forge", plugin, "super.getResultItem(registryAccess)")
    require("1.21.1-neoforge", plugin, "BCTransportItems.PIPE_STRUCTURE.isBound()")
    forbid("1.21.1-neoforge", plugin, "BCTransportItems.PIPE_STRUCTURE.isPresent()")


def validate_network_hardening() -> None:
    network_security = "src/main/java/buildcraft/lib/net/NetworkSecurity.java"
    packet_buffer = "src/main/java/buildcraft/lib/net/PacketBufferBC.java"
    permission_util = "src/main/java/buildcraft/lib/misc/PermissionUtil.java"
    container_message = "src/main/java/buildcraft/lib/net/MessageContainer.java"
    tile_message = "src/main/java/buildcraft/lib/net/MessageUpdateTile.java"
    cache_request = "src/main/java/buildcraft/lib/net/cache/MessageObjectCacheRequest.java"
    cache_response = "src/main/java/buildcraft/lib/net/cache/MessageObjectCacheResponse.java"
    marker_message = "src/main/java/buildcraft/lib/net/MessageMarker.java"
    debug_request = "src/main/java/buildcraft/lib/net/MessageDebugRequest.java"
    debug_response = "src/main/java/buildcraft/lib/net/MessageDebugResponse.java"
    zone_plan = "src/main/java/buildcraft/robotics/zone/ZonePlan.java"
    zone_chunk = "src/main/java/buildcraft/robotics/zone/ZoneChunk.java"
    zone_tile = "src/main/java/buildcraft/robotics/tile/TileZonePlanner.java"
    snapshot = "src/main/java/buildcraft/builders/snapshot/Snapshot.java"
    electronic_library = "src/main/java/buildcraft/builders/tile/TileElectronicLibrary.java"
    assembly_table = "src/main/java/buildcraft/silicon/tile/TileAssemblyTable.java"
    nbt_squisher = "src/main/java/buildcraft/lib/nbt/NbtSquisher.java"
    nbt_map_reader = "src/main/java/buildcraft/lib/nbt/NbtSquishMapReader.java"

    for target in TARGETS:
        require(target, network_security,
                "requireRange", "requireCount", "requireReadable", "requireFullyRead",
                "ownership is execution attribution")
        require(target, packet_buffer,
                "MAX_STRING_BYTES = 32 * 1024",
                "Invalid \" + enumClass.getSimpleName() + \" ordinal",
                "NetworkSecurity.requireReadable(this, length, \"BuildCraft string\")")

        # Player-facing access is authenticated by the actual sender/context, never by BuildCraft ownership.
        require(target, permission_util,
                "BuildCraft ownership is attribution and an execution identity, not an internal claim system",
                "public static boolean hasPermission(Object type, Player attempting, PermissionBlock target)",
                "return true;")
        for rel in (container_message, tile_message):
            forbid(target, rel, "getKnownOwner", "getOwnerProfile")

        require(target, container_message,
                "container.stillValid(player)",
                "container.getIdAllocator().isAllocated(message.msgId)",
                "NetworkSecurity.requireFullyRead")
        require(target, tile_message,
                "NetworkSecurity.requireFullyRead")
        require(target, cache_request,
                "MAX_IDS = 256",
                "MessageObjectCacheResponse.MAX_TOTAL_VALUE_BYTES")
        require(target, cache_response,
                "MAX_TOTAL_VALUE_BYTES = 2 * 1024 * 1024",
                "MAX_VALUE_SIZE = 0xFFFF")
        require(target, marker_message, "MAX_POSITIONS = 8192", "NetworkSecurity.requireCount")
        require(target, debug_request, "MAX_INTERACTION_DISTANCE_SQR = 64.0D")
        require(target, debug_response, "MAX_LINES = 1024", "MAX_LINE_LENGTH = 1024")
        require(target, zone_plan, "MAX_SERIALIZED_CHUNKS = 4096", "NetworkSecurity.requireCount")
        require(target, zone_chunk, "MAX_NETWORK_BYTES = 32", "NetworkSecurity.requireReadable")
        require(target, zone_tile, "MAX_MAP_NAME_LENGTH = 64", "readUtf(MAX_MAP_NAME_LENGTH)")
        require(target, snapshot,
                "HashUtil.DIGEST_LENGTH, HashUtil.DIGEST_LENGTH, \"snapshot key hash length\"",
                "MAX_OWNER_NAME_LENGTH = 64",
                "MAX_BLUEPRINT_NAME_LENGTH = 256")
        require(target, electronic_library,
                "MAX_UPLOAD_PART_BYTES = 4 * 1024",
                "MAX_UPLOAD_TOTAL_BYTES = 8 * 1024 * 1024",
                "MAX_CONCURRENT_UPLOADS = 64",
                "MAX_UPLOAD_EXPANDED_BYTES = 64L * 1024 * 1024",
                "NetworkSecurity.requireReadable(buffer, partLength",
                "sender.containerMenu instanceof ContainerElectronicLibrary menu",
                "menu.tile != this || !menu.stillValid(sender)",
                "expandBuildCraftV1Limited",
                "ownership in BuildCraft is attribution for automated")
        require(target, assembly_table,
                "MAX_RECIPE_ID_LENGTH = 256",
                "MAX_RECIPE_STATES = 4096",
                "NetworkSecurity.requireRange")
        require(target, nbt_squisher,
                "expandBuildCraftV1Limited",
                "LimitedInputStream",
                "maxDecodeBudget")
        require(target, nbt_map_reader,
                "remainingBudget",
                "packed list entries",
                "Packed list has entries but no dictionary")

        manager = "src/main/java/buildcraft/lib/net/MessageManager.java"
        require(target, manager, "PROTOCOL_VERSION = BuildCraftTarget.NETWORK_PROTOCOL")
        if target == "1.21.1-neoforge":
            require(target, manager,
                    "MAX_MESSAGE_CLASS_NAME = 256",
                    "buffer.readUtf(MAX_MESSAGE_CLASS_NAME)",
                    "NetworkSecurity.requireFullyRead(buffer, className)")

    packet_tests = ROOT / "source-shared/src/test/java/buildcraft/lib/net/PacketBufferBCTester.java"
    if not packet_tests.is_file():
        fail("missing PacketBufferBC malformed-input regression tests")
    else:
        packet_test_text = packet_tests.read_text(encoding="utf-8")
        for method in ("invalidPackedEnumOrdinalIsRejected", "oversizedLegacyStringIsRejectedBeforeAllocation",
                       "truncatedLegacyStringIsRejected"):
            if method not in packet_test_text:
                fail(f"missing PacketBufferBC regression test {method}")


    nbt_tests = ROOT / "source-shared/src/test/java/buildcraft/lib/nbt/NbtSquisherTester.java"
    if not nbt_tests.is_file():
        fail("missing bounded network NBT regression tests")
    else:
        nbt_test_text = nbt_tests.read_text(encoding="utf-8")
        for method in ("limitedNetworkDecodeAcceptsNormalBuildCraftNbt",
                       "limitedNetworkDecodeRejectsExpansionAndComplexityBombs",
                       "limitedNetworkDecodeRejectsVanillaFormatNegotiation"):
            if method not in nbt_test_text:
                fail(f"missing bounded NBT regression test {method}")


    zone_tests = ROOT / "source-shared/src/test/java/buildcraft/robotics/zone/ZoneNetworkSecurityTester.java"
    if not zone_tests.is_file():
        fail("missing zone network malformed-input regression tests")
    else:
        zone_test_text = zone_tests.read_text(encoding="utf-8")
        for method in ("oversizedZonePlanChunkCountIsRejected", "oversizedZoneChunkBitsetIsRejectedBeforeAllocation",
                       "unknownZoneChunkFlagsAreRejected"):
            if method not in zone_test_text:
                fail(f"missing zone network regression test {method}")


def validate_gametest_runtime_guards() -> None:
    expected_tests = {
        "1.19.2-forge": 94,
        "1.20.1-forge": 94,
        "1.21.1-neoforge": 95,
    }
    for target in TARGETS:
        test_root = TARGETS[target] / "src/gametest/java"
        count = 0
        if test_root.is_dir():
            for path in test_root.rglob("*.java"):
                count += path.read_text(encoding="utf-8").count("@GameTest(")
        expected = expected_tests[target]
        if count != expected:
            fail(f"{target}: expected {expected} @GameTest methods, found {count}")

        core_integrity_suite = text(target, "src/gametest/java/buildcraft/gametest/CoreIntegrityGameTests.java")
        for method in (
            "unavailableBlueprintElementsRoundTripLosslessly",
            "relatedGateActionVariantsTargetOneSetting",
            "jsonInlineCopiesAreIndependent",
        ):
            if method not in core_integrity_suite:
                fail(f"{target}: missing core integrity GameTest {method}")

        regression_suite = text(target, "src/gametest/java/buildcraft/gametest/BuildCraftLogicGameTests.java")
        for method in (
            "pumpPreservesDetectedInfiniteWaterSource",
            "fillerTreatsReplaceableBlocksAsPlacementTargets",
            "quarryFluidTraversalMatchesBc8ViscosityRules",
            "quarryFramePlannerReplacesFluidsAndExcavatesSolidObstacles",
            "schematicPlacementHonoursVanillaCanSurvive",
            "schematicLeavesBecomePersistentAfterSerializationRoundTrip",
            "commonWrenchTagToggleControlsExternalTaggedItemsOnly",
            "pipeHolderWaterloggingPreservesTheWaterFluidState",
            "feMjConverterRoundTripConservesEnergy",
            "feMjConverterSimulationDoesNotMutateMachineBuffers",
            "converterMachineStateSurvivesPersistenceRoundTrip",
            "builderResourceReservationRollsBackAfterMidTransactionFailure",
            "creativeBuilderCancellationDoesNotMintDisplayRequirements",
            "quarryCancelledTaskRefundsExactWithdrawnPower",
            "bulkItemTransactorInsertionCarriesRemainderAcrossSlots",
            "sugarCaneAdapterHarvestsOnlyGrowthAboveTheBase",
        ):
            if method not in regression_suite:
                fail(f"{target}: missing gameplay regression GameTest {method}")

        # The Quarry fluid test is intentionally a unit-style fixture around private traversal helpers. Keep source
        # water and lava separated so vanilla fluid reactions cannot invalidate the assertion, and initialize only
        # the temporary mining column needed by canMoveDownTo(). The temporary Box must always be reset before the
        # next server tick so the live Quarry cannot observe a half-configured frame/mining state.
        for required in (
            "import buildcraft.lib.misc.data.Box;",
            "BlockPos water = new BlockPos(0, 1, 0);",
            "BlockPos lava = new BlockPos(2, 1, 0);",
            "BlockPos waterlogged = new BlockPos(0, 1, 2);",
            "quarry fluid test fixture converted lava before the traversal checks",
            'Box miningBox = (Box) readField(quarry, "miningBox");',
            "miningBox.setMin(belowLava);",
            "miningBox.setMax(absoluteLava);",
            "miningBox.reset();",
        ):
            if required not in regression_suite:
                fail(f"{target}: Quarry fluid GameTest lost fixture/isolation safeguard {required!r}")



        fe_adversarial_suite = text(target, "src/gametest/java/buildcraft/gametest/FeMjAdversarialGameTests.java")
        for method in (
            "automaticFeCompatibilityConfigActuallyGatesAdapters",
            "supportedMjPerFeRatiosRemainConservative",
            "chainedAutomaticConvertersPreserveWholeFeAndMjRemainder",
        ):
            if method not in fe_adversarial_suite:
                fail(f"{target}: missing adversarial FE/MJ GameTest {method}")

        fe_pipe_suite = text(target, "src/gametest/java/buildcraft/transport/pipe/flow/PipeForgeEnergyGameTests.java")
        for method in (
            "feReceiverRejectsUndemandedEnergyAndPersistsBoundedBuffer",
            "bufferlessConsumersCreateDemandOnMultipleSides",
            "woodenFeExtractionUsesSimulationAndNeverOverfills",
            "feLimiterModesClampPersistAndDisableTransfer",
            "feOverflowRequestsAreSaturatedInsteadOfWrapping",
        ):
            if method not in fe_pipe_suite:
                fail(f"{target}: missing FE-pipe adversarial GameTest {method}")

        fluid_pipe_suite = text(target, "src/gametest/java/buildcraft/transport/pipe/flow/PipeFluidPowerGameTests.java")
        for method in (
            "fullForceExtractionThenRefillDoesNotGhostJamPipe",
        ):
            if method not in fluid_pipe_suite:
                fail(f"{target}: missing fluid-pipe adversarial GameTest {method}")

        performance_suite = text(target, "src/gametest/java/buildcraft/gametest/PerformanceSmokeGameTests.java")
        for method in (
            "thousandPipeStateRoundTripsStayIdleAndBounded",
            "largeGateNetworkStateRoundTripsStayLinear",
            "idleBuilderAndQuarryMachineTicksRemainPowerNeutral",
            "manyIdleRobotsKeepChargingStateStable",
            "largeZoneAndChunkStyleRoundTripStaysBounded",
        ):
            if method not in performance_suite:
                fail(f"{target}: missing deterministic performance smoke GameTest {method}")

        permission_suite = text(target, "src/gametest/java/buildcraft/gametest/PermissionOwnerGameTests.java")
        for method in (
            "buildCraftAutomationPlayerUsesPlatformFakePlayer",
            "machineOwnerIdentitySurvivesPersistenceRoundTrip",
            "robotOwnerIdentitySurvivesPersistenceAndFeedsApi2Actor",
            "api2PermissionProviderSeesOwnerAcrossWorldOperationKinds",
            "platformProtectionHooksReceiveMachineOwnerForBreakAndPlace",
            "manualInteractionIsNotOwnerLocked",
            "robotDismantleIsNotOwnerLocked",
        ):
            if method not in permission_suite:
                fail(f"{target}: missing permission/owner GameTest {method}")

        wrench_tag_rel = (
            "src/gametest/resources/data/c/tags/item/tools/wrench.json"
            if target.startswith("1.21.1")
            else "src/gametest/resources/data/c/tags/items/tools/wrench.json"
        )
        wrench_tag_path = source(target, wrench_tag_rel)
        if not wrench_tag_path.is_file():
            fail(f"{target}: missing GameTest-only common wrench tag fixture {wrench_tag_rel}")
        else:
            try:
                wrench_tag = json.loads(wrench_tag_path.read_text(encoding="utf-8"))
                if "minecraft:stick" not in wrench_tag.get("values", []):
                    fail(f"{target}: GameTest wrench tag fixture no longer contains minecraft:stick")
            except Exception as exc:
                fail(f"{target}: invalid GameTest wrench tag fixture {wrench_tag_rel}: {exc}")

    # GameTest classes must be part of the same exploded module as main during
    # runGameTestServer. Separate main/gameTest modules create split packages on
    # modern loader runtimes, while the reflection registrar cannot see the second
    # module on older Forge. The Gradle files are checked directly below.
    forge_path = ROOT / "build-logic/loaders/forge-target.gradle"
    forge_gradle = forge_path.read_text(encoding="utf-8")
    require_tokens = (
        "def gameTestRunRequested",
        "java.srcDir(new File(effectiveSourceDir, 'src/gametest/java'))",
        "resources.srcDir(new File(effectiveSourceDir, 'src/gametest/resources'))",
        "prepareEffectiveSource",
    )
    for token in require_tokens:
        if token not in forge_gradle:
            fail(f"{forge_path.relative_to(ROOT)}: missing GameTest one-module guard: {token}")
    if "source sourceSets.gameTest" in forge_gradle:
        fail(f"{forge_path.relative_to(ROOT)}: GameTest runtime still loads sourceSets.gameTest as a second module")

    neoforge_path = ROOT / "build-logic/loaders/neoforge-target.gradle"
    neoforge_gradle = neoforge_path.read_text(encoding="utf-8")
    for token in (
        "def gameTestRunRequested",
        "java.srcDir(new File(effectiveSourceDir, 'src/gametest/java'))",
        "resources.srcDir(new File(effectiveSourceDir, 'src/gametest/resources'))",
        "sourceSet = sourceSets.main",
        "prepareEffectiveSource",
    ):
        if token not in neoforge_gradle:
            fail(f"{neoforge_path.relative_to(ROOT)}: missing GameTest one-module guard: {token}")
    if "sourceSet(sourceSets.gameTest)" in neoforge_gradle:
        fail(f"{neoforge_path.relative_to(ROOT)}: mod still exposes gameTest as a second source set")

    for target in ("1.19.2-forge", "1.20.1-forge"):
        bclib = text(target, "src/main/java/buildcraft/lib/BCLib.java")
        if "BuildCraftGameTestRegistrar" in bclib:
            fail(f"{target}: obsolete reflection GameTest registrar remains wired into BCLib")
        registrar = TARGETS[target] / "src/main/java/buildcraft/lib/BuildCraftGameTestRegistrar.java"
        if registrar.exists():
            fail(f"{target}: obsolete reflection GameTest registrar source still exists")

    for target in TARGETS:
        silicon = text(target, "src/main/java/buildcraft/silicon/BCSilicon.java")
        for required in (
            "BCSiliconConfig::onLoadConfig",
            "BCSiliconConfig::onReloadConfig",
            "BCSiliconConfig.preInit()",
            "registerConfig(Type.COMMON, ConfigBinding.bind(BCSiliconConfig.config))",
            "BCSiliconConfig.reloadConfig(MODID)",
        ):
            if required not in silicon:
                fail(f"{target}: silicon facade config is not wired end-to-end: {required}")

        silicon_config = text(target, "src/main/java/buildcraft/silicon/BCSiliconConfig.java")
        for required in (
            "enableFacades = true",
            'define("enable", true)',
            "existing facades remain loaded",
        ):
            if required not in silicon_config:
                fail(f"{target}: facade enable config invariant missing: {required}")

        energy_config = text(target, "src/main/java/buildcraft/energy/BCEnergyConfig.java")
        for required in (
            "enableStirlingEngineExplosion = false",
            'define("stirlingExplosion", false)',
        ):
            if required not in energy_config:
                fail(f"{target}: Stirling explosion config invariant missing: {required}")

        stirling = text(target, "src/main/java/buildcraft/energy/tile/TileEngineStone_BC8.java")
        if "return BCEnergyConfig.enableStirlingEngineExplosion;" not in stirling:
            fail(f"{target}: Stirling engine does not honour the explosion config")

    for target in ("1.21.1-neoforge",):
        base = TARGETS[target] / "src/gametest/resources/data/buildcraftlib"
        modern = base / "structure"
        legacy = base / "structures"
        for name in ("empty3x3x3.nbt", "empty7x3x7.nbt"):
            if not (modern / name).is_file():
                fail(f"{target}: missing modern GameTest structure data/buildcraftlib/structure/{name}")
        if legacy.exists():
            fail(f"{target}: obsolete plural GameTest structure directory remains: {legacy.relative_to(TARGETS[target])}")

    neo = TARGETS["1.21.1-neoforge"]
    resources = neo / "src/main/resources"
    strict_count = 0
    for path in resources.rglob("*.json"):
        try:
            document = json.loads(path.read_text(encoding="utf-8"))
        except Exception:
            continue

        def walk(value: Any) -> None:
            nonlocal strict_count
            if isinstance(value, dict):
                ingredient_type = value.get("type")
                if ingredient_type == "c:nbt":
                    fail(f"1.21.1-neoforge: unsupported c:nbt ingredient remains in {path.relative_to(neo)}")
                if ingredient_type == "buildcraftlib:strict_nbt":
                    strict_count += 1
                    if not isinstance(value.get("item"), str):
                        fail(f"1.21.1-neoforge: strict-NBT ingredient has no item in {path.relative_to(neo)}")
                    if "nbt" in value and not isinstance(value.get("nbt"), str):
                        fail(f"1.21.1-neoforge: strict-NBT ingredient has non-string NBT in {path.relative_to(neo)}")
                for child in value.values():
                    walk(child)
            elif isinstance(value, list):
                for child in value:
                    walk(child)

        walk(document)
        if isinstance(document, dict) and document.get("type") == "buildcraftlib:assembly":
            ingredients = document.get("ingredients")
            counts = document.get("ingredient_counts")
            if isinstance(ingredients, list) and isinstance(counts, list) and len(ingredients) != len(counts):
                fail(f"1.21.1-neoforge: assembly ingredient/count length mismatch in {path.relative_to(neo)}")

    if strict_count != 26:
        fail(f"1.21.1-neoforge: expected 26 legacy strict-NBT recipe ingredients, found {strict_count}")

    require("1.21.1-neoforge", "src/main/java/buildcraft/lib/recipe/LegacyStrictNbtIngredient.java",
            "ItemStack.isSameItemSameComponents(input, displayStack)",
            "ItemStack count is deliberately ignored")
    require("1.21.1-neoforge", "src/main/java/buildcraft/lib/recipe/BCLibIngredientTypes.java",
            'INGREDIENT_TYPES.register("strict_nbt"',
            "NeoForgeRegistries.Keys.INGREDIENT_TYPES")


def strip_java_comments(value: str) -> str:
    value = re.sub(r"/\*.*?\*/", "", value, flags=re.DOTALL)
    return re.sub(r"//[^\n]*", "", value)


def validate_id_allocator_contracts() -> None:
    """A class that allocates child wire IDs must expose that child allocator to packet validation."""
    child_pattern = re.compile(
        r"\bIdAllocator\s+(\w+)\s*=\s*[^;\n]*\.makeChild\s*\(", re.MULTILINE
    )
    for target, target_root in TARGETS.items():
        java_root = target_root / "src/main/java"
        for path in sorted(java_root.rglob("*.java")):
            try:
                raw = path.read_text(encoding="utf-8")
            except OSError as exc:
                fail(f"{target}: failed reading {path.relative_to(target_root)}: {exc}")
                continue
            value = strip_java_comments(raw)
            for match in child_pattern.finditer(value):
                allocator = match.group(1)
                if re.search(rf"\b{re.escape(allocator)}\.allocId\s*\(", value) is None:
                    continue
                override = re.search(
                    rf"\bgetIdAllocator\s*\([^)]*\)\s*\{{[^{{}}]*\breturn\s+{re.escape(allocator)}\s*;[^{{}}]*\}}",
                    value,
                    flags=re.DOTALL,
                )
                if override is None:
                    fail(
                        f"{target}: {path.relative_to(target_root)} allocates wire IDs from child allocator "
                        f"{allocator} but does not return it from getIdAllocator()"
                    )


def validate_transactional_side_effect_ordering() -> None:
    """Cosmetic fluid sounds must not sit between fluid mutation and authoritative inventory/menu state."""
    for target in TARGETS:
        sound = compact(text(target, "src/main/java/buildcraft/lib/misc/SoundUtil.java"))
        for required in (
            "private static void playFluidActionSound",
            "catch (RuntimeException exception)",
            "using vanilla fallback",
            "Failed to play cosmetic fluid",
        ):
            if required not in sound:
                fail(f"{target}: fluid sounds are not non-fatal/best-effort: missing {required!r}")

        tank_raw = text(target, "src/main/java/buildcraft/lib/fluid/Tank.java")
        transfer_match = re.search(
            r"public ItemStack transferStackToTank\(Player player, ItemStack stack\)\s*\{(.*?)\n    \}",
            tank_raw,
            flags=re.DOTALL,
        )
        if transfer_match is None:
            fail(f"{target}: could not locate Tank.transferStackToTank for transaction guard")
        else:
            transfer = transfer_match.group(1)
            if "SoundUtil.playBucketEmpty" in transfer or "SoundUtil.playBucketFill" in transfer:
                fail(f"{target}: Tank.transferStackToTank still performs cosmetic sound inside the transaction")

        if "private static FluidStack copyFluidForSound" not in tank_raw:
            fail(f"{target}: GUI fluid sound delta is not copied through the cross-loader helper")
        if target == "1.19.2-forge" and "Level world = player.level;" not in tank_raw:
            fail("1.19.2-forge: GUI fluid sound must use the pre-1.20 Player.level field")
        if target == "1.20.1-forge" and "Level world = player.level();" not in tank_raw:
            fail("1.20.1-forge: GUI fluid sound must use Player.level() on 1.20+")
        for incompatible in ("new FluidStack(after,", "new FluidStack(before,"):
            if incompatible in tank_raw:
                fail(
                    f"{target}: GUI fluid sound uses loader-specific FluidStack copy constructor: {incompatible!r}"
                )

        tank = compact(tank_raw)
        for needle in (
            "menu.setCarried(stack); menu.broadcastFullState(); player.inventoryMenu.broadcastFullState(); playCommittedGuiTransferSound(player, before);",
            "container.setCarried(stack); container.broadcastFullState(); player.inventoryMenu.broadcastFullState(); playCommittedGuiTransferSound(player, before);",
        ):
            if compact(needle) not in tank:
                fail(f"{target}: GUI fluid sound is not post-commit: missing normalized fragment {needle!r}")

    forge = compact(text("1.19.2-forge", "src/main/java/buildcraft/lib/misc/FluidUtilBC.java"))
    direct_commit = compact(
        "if (changed && replace) { ItemStack result = flItem.getContainer().copy();"
    )
    direct_sound = compact("if (changed) { if (emptiedItem) { SoundUtil.playBucketEmpty")
    commit_index = forge.find(direct_commit)
    sound_index = forge.find(direct_sound)
    if commit_index < 0 or sound_index < 0 or sound_index <= commit_index:
        fail("1.19.2-forge: direct fluid interaction sound is not ordered after inventory commit")

    forge_120 = compact(text("1.20.1-forge", "src/main/java/buildcraft/lib/misc/FluidUtilBC.java"))
    commit_index = forge_120.find(direct_commit)
    sound_index = forge_120.find(direct_sound)
    if commit_index < 0 or sound_index < 0 or sound_index <= commit_index:
        fail("1.20.1-forge: direct fluid interaction sound is not ordered after inventory commit")

def main() -> None:
    for target, root in TARGETS.items():
        if not (root / "src/main/java").is_dir():
            fail(f"{target}: invalid target source root {root}")

    validate_tick_cadence()
    validate_java_invariants()
    validate_fluid_filter_safety()
    validate_protection_invariants()
    validate_robot_invariants()
    validate_target_specific_persistence()
    validate_legacy_itemstack_migration()
    validate_persistence_and_reload_invariants()
    validate_worldgen_resources()
    validate_advancements()
    validate_resource_parity()
    validate_gameplay_gap_fixes()
    validate_modpack_interop_fixes()
    validate_jei_facade_scalability()
    validate_forestry_model_bake_mutation()
    validate_forge_atlas_reload_caches()
    validate_pipe_pluggable_contract()
    validate_id_allocator_contracts()
    validate_transactional_side_effect_ordering()
    validate_network_hardening()
    validate_gametest_runtime_guards()

    if ERRORS:
        for error in ERRORS:
            print(f"ERROR: {error}", file=sys.stderr)
        raise SystemExit(1)

    print(
        "Behavior parity OK: 2 newer targets locked to the 1.19.2 reference; "
        "228/227 recipes, 31 loot tables and 191 advancements validated"
    )


if __name__ == "__main__":
    main()

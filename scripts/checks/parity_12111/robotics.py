#!/usr/bin/env python3
"""BuildCraft Robotics 1.21.1 -> 1.21.11 gameplay parity guard.

This validates the complete robot-local surface that has regressed during the port:
registry/bootstrap/persistence, placement, entity state/damage/bounds, docking, stations,
board/profession registration, AI/board source coverage, tool compatibility, Stripes fake-player
semantics, held-item ticking, legacy robot NBT positions, Zone Planner modifier input, and the
native 1.21.11 robot renderer. External subsystem dependencies (for example Builders blueprint
entity construction) are intentionally validated by their owning module, not duplicated here.
"""
from __future__ import annotations

import importlib.util
from pathlib import Path
import re
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[3]


def load_layout():
    spec = importlib.util.spec_from_file_location("robotics_source_layout", ROOT / "scripts/source_layout.py")
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


def method(source: str, signature: str) -> str:
    start = source.index(signature)
    brace = source.index("{", start)
    level = 1
    i = brace + 1
    while level:
        if source[i] == "{":
            level += 1
        elif source[i] == "}":
            level -= 1
        i += 1
    return source[start:i]


def normalize_port_api(text: str) -> str:
    text = text.replace(".isClientSide()", ".isClientSide")
    text = text.replace("net.minecraft.resources.Identifier", "net.minecraft.resources.ResourceLocation")
    text = text.replace("Identifier", "ResourceLocation")
    text = text.replace("LevelCompat.getMinBuildHeight(level())", "level().getMinBuildHeight()")
    # 1.21.11 CompoundTag primitive/list getters are routed through the compatibility shim.
    text = re.sub(r"NbtCompat\.contains\(([^,]+),\s*", r"\1.contains(", text)
    text = re.sub(r"NbtCompat\.get(Long|Int|String|Boolean|Byte|Short|Float|Double)\(NbtCompat,\s*([^,]+),\s*", r"\2.get\1(", text)
    text = re.sub(r"NbtCompat\.get(Long|Int|String|Boolean|Byte|Short|Float|Double)\(([^,]+),\s*", r"\2.get\1(", text)
    text = re.sub(r"NbtCompat\.getCompound\(NbtCompat,\s*([^,]+),\s*", r"\1.getCompound(", text)
    text = re.sub(r"NbtCompat\.getCompound\(([^,]+),\s*", r"\1.getCompound(", text)
    text = re.sub(r"NbtCompat\.getList\(([^,]+),\s*([^\)]+)\)", r"\1.getList(\2, Tag.TAG_COMPOUND)", text)
    text = re.sub(r"\s+", " ", text).strip()
    return text


def require(condition: bool, message: str) -> None:
    if not condition:
        raise AssertionError(message)


def same_method(old_source: str, new_source: str, signature: str, label: str | None = None) -> None:
    require(
        normalize_port_api(method(old_source, signature)) == normalize_port_api(method(new_source, signature)),
        f"Robotics behaviour diverged from 1.21.1 in {label or signature}",
    )


def main() -> int:
    layout = load_layout()
    props = layout.load_properties(ROOT / "builds/modern/targets.properties")
    with tempfile.TemporaryDirectory(prefix="bc-robotics-parity-") as tmp:
        tmp = Path(tmp)
        old = tmp / "1.21.1"
        new = tmp / "1.21.11"
        layout.materialize_target("1.21.1-neoforge", old, props)
        layout.materialize_target("1.21.11-neoforge", new, props)

        def src(root: Path, rel: str) -> str:
            return (root / "src/main/java/buildcraft" / rel).read_text()

        # Module bootstrap must install the registry provider before any ItemRobot placement can succeed.
        provider_file = new / "src/main/java/buildcraft/robotics/SimpleRobotRegistryProvider.java"
        require(provider_file.is_file(),
                "1.21.11 effective source is missing SimpleRobotRegistryProvider.java")
        modern_build = (ROOT / "builds/modern/build.neoforge.gradle").read_text()
        require("exclude 'buildcraft/robotics/SimpleRobotRegistryProvider.java'" not in modern_build,
                "Gradle excludes the runtime robot registry provider from sourceSets.main")
        provider_decl = provider_file.read_text()
        require("public enum SimpleRobotRegistryProvider implements IRobotRegistryProvider" in provider_decl,
                "1.21.11 robot registry provider source is malformed or wrong")

        bootstrap = src(new, "robotics/BCRobotics.java")
        require("RobotManager.registryProvider = SimpleRobotRegistryProvider.INSTANCE;" in bootstrap,
                "1.21.11 robot registry provider is not installed")
        require("SimpleRobotRegistryProvider.registerGameplayEvents();" in bootstrap,
                "1.21.11 robot registry provider has no lifecycle subscription")
        require("PlatformEvents.chunkUnload(INSTANCE::onChunkUnload)" in provider_decl
                and "if (gameplayEventsRegistered) return;" in provider_decl,
                "Robot registry chunk lifecycle must be subscribed exactly once through lib")
        event_binding = src(new, "lib/platform/events/PlatformEvents.java")
        require("ChunkEvent.Unload event" in event_binding and "event.getChunk().getPos()" in event_binding,
                "Native chunk unload does not forward the real chunk position to the robot registry")
        require("RobotManager.registerDockingStation(DockingStationPipe.class, \"pipe\");" in bootstrap,
                "DockingStationPipe registry type is not registered")

        # Placement must match the known-good 1.21.1 order exactly after mechanical API renames.
        old_item = src(old, "robotics/item/ItemRobot.java")
        new_item = src(new, "robotics/item/ItemRobot.java")
        same_method(old_item, new_item, "public static InteractionResult placeOnStation(", "ItemRobot.placeOnStation")
        place = method(new_item, "public static InteractionResult placeOnStation(")
        for token in (
            "pluggable.getStation()", "station.isTaken()", "robot.getRegistry() == null",
            "robot.setOwner(player.getGameProfile())", "getNextRobotId()", "station.takeAsMain(robot)",
            "robot.dock(robot.getLinkedStation())", "level.addFreshEntity(robot)", "currentItem.shrink(1)",
        ):
            require(token in place, f"Robot placement lost required step: {token}")

        # A placed robot immediately enters the client entity-render pipeline. In 1.21.11 the dispatcher
        # selects the submit renderer from EntityRenderState.entityType, which is populated by
        # EntityRenderer.extractRenderState(). The old compatibility bridge used an empty extractor and
        # therefore turned successful placement into a client crash on the first rendered frame.
        renderer = src(new, "robotics/client/render/RenderRobot.java")
        require("extends EntityRenderer<EntityRobot, RenderRobot.RobotRenderState>" in renderer,
                "Robot renderer is still routed through the dead pre-1.21.11 direct-render bridge")
        require("super.extractRenderState(robot, state, partialTick);" in renderer,
                "Robot renderer does not populate the mandatory vanilla EntityRenderState fields")
        require("state.texture = robot.getTexture();" in renderer,
                "Robot renderer does not snapshot the profession texture into render state")
        require("state.asleep = robot.isAsleepForRendering();" in renderer and
                "state.energy = robot.getEnergyForRendering();" in renderer,
                "Robot active-overlay state is not extracted from the entity")
        require("super.submit(state, poseStack, collector, cameraState);" in renderer,
                "Robot renderer dropped vanilla entity submit features")
        require(renderer.count("collector.submitCustomGeometry(") >= 3,
                "Robot renderer is not submitting base + active overlay geometry through 1.21.11")
        require("public static final class RobotRenderState extends EntityRenderState" in renderer,
                "Robot renderer has no native 1.21.11 render-state type")

        # 1.21.11 entityCutoutNoCull/entityTranslucent use PER_FACE_LIGHTING and gl_FrontFacing.
        # The legacy 1->2->3->4 cube order is wound opposite to the explicit outward normals; in the new shader
        # that makes the visible outside of every robot face use the back-face light vector. Preserve UVs, but
        # submit the corners as 1->4->3->2 so geometric winding and the declared normals agree.
        quad_method = method(renderer, "private static void quad(")
        expected_winding = (
            "vertex(builder, pose, light, x1, y1, z1, u1 / TEX_SIZE, v2 / TEX_SIZE",
            "vertex(builder, pose, light, x4, y4, z4, u1 / TEX_SIZE, v1 / TEX_SIZE",
            "vertex(builder, pose, light, x3, y3, z3, u2 / TEX_SIZE, v1 / TEX_SIZE",
            "vertex(builder, pose, light, x2, y2, z2, u2 / TEX_SIZE, v2 / TEX_SIZE",
        )
        winding_positions = [quad_method.find(token) for token in expected_winding]
        require(all(pos >= 0 for pos in winding_positions) and winding_positions == sorted(winding_positions),
                "Robot cube winding no longer matches its outward normals; 1.21.11 per-face lighting will invert")

        legacy_renderer = src(new, "lib/client/render/compat/LegacyEntityRenderer.java")
        require("super.extractRenderState(entity, state, partialTick);" in legacy_renderer,
                "Legacy entity bridge can still create render states with null entityType")
        require("super.submit(state, poseStack, collector, cameraState);" in legacy_renderer,
                "Legacy entity bridge drops vanilla submit state")

        # Entity persistence must use the native 1.21.11 ValueInput/ValueOutput hooks, not dead legacy methods.
        robot = src(new, "robotics/entity/EntityRobot.java")
        require("protected void addAdditionalSaveData(ValueOutput output)" in robot,
                "EntityRobot is not overriding the 1.21.11 save hook")
        require("protected void readAdditionalSaveData(ValueInput input)" in robot,
                "EntityRobot is not overriding the 1.21.11 load hook")
        require("ContainerHelper.saveAllItems(output, inventory);" in robot,
                "Robot transfer inventory is not persisted")
        require("ContainerHelper.loadAllItems(input, inventory);" in robot,
                "Robot transfer inventory is not restored")
        for key in (
            '"boardId"', '"robotId"', '"battery"', '"owner"', '"itemInUse"', '"linkedStation"',
            '"lastMainStation"', '"currentStation"', '"wearables"', '"mainAI"', '"tank"',
        ):
            require(key in robot, f"Robot persistence key disappeared: {key}")
        require(re.search(r"NbtCompat\.[A-Za-z0-9_]+\(NbtCompat,", robot) is None,
                "EntityRobot contains a double-wrapped NbtCompat call after materialization")

        # Native gameplay hooks: bounding box, incoming damage, and attack success semantics.
        require("protected AABB makeBoundingBox(Vec3 position)" in robot,
                "Robot custom collision bounds are not wired to the 1.21.11 Entity hook")
        require("makeRobotBoundingBox" not in robot,
                "Dead robot bounding-box helper survived materialization")
        require("public boolean hurtServer(ServerLevel serverLevel, DamageSource source, float amount)" in robot,
                "Robot server damage/energy armour hook is not wired")
        require("hurtRobot(" not in robot, "Dead robot damage helper survived materialization")
        require("target.hurtOrSimulate(damageSource, attackDamage[0])" in robot,
                "Robot attack post-hit logic no longer depends on a successful hit")

        old_robot = src(old, "robotics/entity/EntityRobot.java")
        for signature in (
            "public void tick()",
            "private void resolveStations()",
            "public void dock(",
            "public void undock()",
            "public void releaseMainStationForPlayer(",
            "public void setMainStation(",
        ):
            same_method(old_robot, robot, signature, f"EntityRobot {signature}")

        # 1.21.11 station/reservation SavedData must remain persistent across server restart.
        registry = src(new, "robotics/SimpleRobotRegistryProvider.java")
        require("SavedDataType<SimpleRobotRegistry>" in registry,
                "Robot registry is not using the 1.21.11 SavedDataType API")
        require("CompoundTag.CODEC.xmap(" in registry,
                "Robot registry does not preserve the legacy NBT payload through a codec")
        require("storage.computeIfAbsent(TYPE)" in registry,
                "Robot registry SavedData is not loaded/created through its SavedDataType")
        require("SavedData.Factory" not in registry,
                "Removed SavedData.Factory API survived in 1.21.11")
        require(re.search(r"NbtCompat\.[A-Za-z0-9_]+\(NbtCompat,", registry) is None,
                "Robot registry contains a double-wrapped NbtCompat call after materialization")
        for key in ('"nextRobotId"', '"resourceList"', '"stationList"'):
            require(key in registry, f"Robot registry persistent field disappeared: {key}")

        old_registry = src(old, "robotics/SimpleRobotRegistryProvider.java")
        for signature in (
            "public long getNextRobotId()",
            "public void registerRobot(",
            "public void killRobot(",
            "public void unloadRobot(",
            "public boolean take(ResourceId resourceId, long robotId)",
            "public void release(ResourceId resourceId)",
            "public void registerStation(",
            "public void removeStation(",
            "public void take(DockingStation station, long robotId)",
            "public void release(DockingStation station, long robotId)",
            "public void writeToNBT(",
            "public void registryMarkDirty()",
        ):
            same_method(old_registry, registry, signature, f"robot registry {signature}")

        # Generic docking ownership/reservation lifecycle is expected to be the same as 1.21.1.
        old_station = src(old, "robotics/internal/legacy/robots/DockingStation.java")
        station = src(new, "robotics/internal/legacy/robots/DockingStation.java")
        for signature in (
            "public EntityRobotBase robotTaking()", "public void invalidateRobotTakingEntity()",
            "public boolean isMainStation()", "public long linkedId()", "public boolean takeAsMain(",
            "public boolean take(", "public void release(", "public long forceRelease()",
            "public void unsafeRelease(", "public void writeToNBT(", "public void readFromNBT(",
            "public boolean isTaken()", "public long robotIdTaking()", "public boolean linkIsDocked()",
            "public boolean canRelease()", "public boolean isInitialized()",
        ):
            same_method(old_station, station, signature, f"DockingStation {signature}")
        for key in ('"index"', '"side"', '"isMain"', '"robotId"', '"manuallyReleasedRobotId"'):
            require(key in station, f"Docking station NBT/state field missing: {key}")

        # Station item/definition registration must remain equivalent too; otherwise no pluggable exists to host
        # the otherwise-correct DockingStation lifecycle.
        old_station_item = src(old, "robotics/item/ItemRobotStation.java")
        station_item = src(new, "robotics/item/ItemRobotStation.java")
        same_method(old_station_item, station_item, "public @Nonnull PipePluggable onPlace(", "ItemRobotStation.onPlace")
        old_plugs = src(old, "robotics/BCRoboticsPlugs.java")
        plugs = src(new, "robotics/BCRoboticsPlugs.java")
        same_method(old_plugs, plugs, "public static void preInit()", "BCRoboticsPlugs.preInit")

        # Pipe pluggable owns station creation/removal, manual release, render-state sync, and MJ charging.
        old_plug = src(old, "robotics/plug/RobotStationPluggable.java")
        plug = src(new, "robotics/plug/RobotStationPluggable.java")
        for signature in (
            "public <T> @Nullable T getCapability(", "public <T> @Nullable T getInternalCapability(",
            "public boolean canConnect(", "public long getPowerRequested()", "public long receivePower(",
            "public boolean canReceive()", "public long getStored()", "public long getCapacity()",
            "private void validateStation()", "public void onPlacedBy(", "public void onTick()",
            "public void onRemove()", "public InteractionResult onPluggableActivate(",
            "private void refreshRenderState()", "private boolean isClientSide()",
            "public RobotStationState getRenderState()", "public void writeCreationPayload(",
            "public void writePayload(", "private void readState(",
            "public static RobotStationPluggable readFromNbt(",
            "public static RobotStationPluggable loadFromBuffer(",
        ):
            same_method(old_plug, plug, signature, f"RobotStationPluggable {signature}")
        activate = method(plug, "public InteractionResult onPluggableActivate(")
        require("station.forceRelease()" in activate and "releaseMainStationForPlayer(station)" in activate,
                "Manual wrench release no longer clears both station and robot home-station state")

        # Full DockingStationPipe runtime I/O / gate / requester behaviour should remain 1.21.1-equivalent.
        old_pipe = src(old, "robotics/DockingStationPipe.java")
        pipe = src(new, "robotics/DockingStationPipe.java")
        for signature in (
            "private void removeInvalidStation(", "private void scheduleRenderUpdateIfLoaded()",
            "private Direction normalizeOutputSide(", "public Iterable<StatementSlot> getActiveActions()",
            "public boolean isInitialized()", "public boolean take(", "public boolean takeAsMain(",
            "public void unsafeRelease(", "public IInjectable getItemOutput()", "public boolean isWoodenItemPipe()",
            "public boolean isItemOutputBusy()", "public Direction getItemOutputSide()",
            "public Container getItemInput()", "private static Container getNeighbourItemContainer(",
            "public Direction getItemInputSide()", "public FluidStorage<FluidStack> getFluidOutput()",
            "public Direction getFluidOutputSide()", "public FluidStorage<FluidStack> getFluidInput()",
            "public Direction getFluidInputSide()", "private Direction getItemInputPipeSide()",
            "private Direction getFluidInputPipeSide()", "public boolean providesPower()",
            "private ItemStack getRequest(", "public Collection<ItemRequest> requests()",
            "public ItemTransferResult offer(", "private List<ItemStack> getActiveItemRequests()",
            "public RequestProvider getRequestProvider()", "public void onChunkUnload()",
        ):
            same_method(old_pipe, pipe, signature, f"DockingStationPipe {signature}")

        # Every board and AI class present in the 1.21.1 reference must still exist in the 1.21.11 runtime.
        def java_set(root: Path, rel: str) -> set[str]:
            return {p.name for p in (root / "src/main/java/buildcraft/robotics" / rel).glob("*.java")}

        old_boards = java_set(old, "boards")
        new_boards = java_set(new, "boards")
        old_ai = java_set(old, "ai")
        new_ai = java_set(new, "ai")
        require(old_boards == new_boards and len(new_boards) == 20,
                f"Robot board class set diverged: old={len(old_boards)} new={len(new_boards)}")
        require(old_ai == new_ai and len(new_ai) == 41,
                f"Robot AI class set diverged: old={len(old_ai)} new={len(new_ai)}")

        # Registration order, profession IDs, textures and programming costs drive item variants and board creation.
        def board_registrations(source: str) -> list[tuple[str, str, str, str, int]]:
            rows: list[tuple[str, str, str, str, int]] = []
            for legacy_id, key, color, texture, raw_cost in re.findall(
                r'board\("([^"]+)",\s*"([^"]+)",\s*"([^"]+)",\s*"([^"]+)",\s*'
                r'(legacyRfToMj\(\s*[\d_]+\s*\)|[\d_]+)\)',
                source,
            ):
                if raw_cost.startswith("legacyRfToMj"):
                    legacy_rf = int(re.search(r'[\d_]+', raw_cost).group(0).replace("_", ""))
                    cost_mj = (legacy_rf + 5) // 10
                else:
                    cost_mj = int(raw_cost.replace("_", ""))
                rows.append((legacy_id, key, color, texture, cost_mj))
            return rows

        old_boards_registry = src(old, "robotics/BCRoboticsBoards.java")
        new_boards_registry = src(new, "robotics/BCRoboticsBoards.java")
        old_regs = board_registrations(old_boards_registry)
        new_regs = board_registrations(new_boards_registry)
        require(old_regs == new_regs and len(new_regs) == 18,
                "Robot profession registration/order/texture/effective cost diverged from 1.21.1")
        require(len([entry for entry in new_regs if entry[1] != "empty"]) == 17,
                "Expected all 17 playable robot professions")

        def ai_registrations(source: str) -> list[tuple[str, str, str | None]]:
            rows: list[tuple[str, str, str | None]] = []
            for cls, name, legacy in re.findall(
                r'RobotManager\.registerAIRobot\((\w+)\.class,\s*"([^"]+)"(?:,\s*"([^"]+)")?\);',
                source,
            ):
                rows.append((cls, name, legacy or None))
            return rows

        # Compare directly against the 1.21.1 bootstrap so registration order and aliases remain exact.
        old_bootstrap = src(old, "robotics/BCRobotics.java")
        old_ai_regs = ai_registrations(old_bootstrap)
        new_ai_regs = ai_registrations(bootstrap)
        require(old_ai_regs == new_ai_regs and len(new_ai_regs) >= 40,
                "Robot board/AI serialization registration names/order diverged from 1.21.1")

        # Tool workers must retain the 1.21.1 NeoForge ItemAbility fallback, not just vanilla concrete classes/tags.
        item_compat = src(new, "lib/compat/ItemCompat.java")
        for ability in ("axe_dig", "pickaxe_dig", "shovel_dig", "sword_dig"):
            require(f'ItemAbility.get("{ability}")' in item_compat,
                    f"Modern robot tool compatibility lost {ability}")
        require("stack.canPerformAction(AXE_DIG)" in item_compat,
                "Lumberjack-compatible axes no longer include ItemAbility tools")
        require("stack.canPerformAction(PICKAXE_DIG)" in item_compat,
                "Miner-compatible pickaxes no longer include ItemAbility tools")
        require("stack.canPerformAction(SHOVEL_DIG)" in item_compat,
                "Shovelman-compatible shovels no longer include ItemAbility tools")
        require("stack.canPerformAction(SWORD_DIG)" in item_compat,
                "Knight/Butcher-compatible swords no longer include ItemAbility tools")
        for rel, helper in (
            ("boards/BoardRobotLumberjack.java", "ItemCompat.isAxe(stack)"),
            ("boards/BoardRobotMiner.java", "ItemCompat.isPickaxe(stack)"),
            ("boards/BoardRobotShovelman.java", "ItemCompat.isShovel(stack)"),
        ):
            require(helper in src(new, "robotics/" + rel), f"{rel} lost modern tool parity helper")
        for rel in ("boards/BoardRobotButcher.java", "boards/BoardRobotKnight.java"):
            require("ItemCompat.isSword(" in src(new, "robotics/" + rel),
                    f"{rel} lost sword compatibility")

        # Stripes must use the fake player's actual selected slot. Hard-coding slot 0 silently changes right-click
        # behaviour for items/mods that inspect the active hand/hotbar slot.
        stripes = src(new, "robotics/ai/AIRobotStripesHandler.java")
        require("player.getInventory().getSelectedSlot()" in stripes,
                "Stripes robot no longer places its working item in the selected fake-player slot")
        require("setItem(0, working)" not in stripes,
                "Stripes robot still hard-codes fake-player slot 0")

        # The modern inventory tick carries the active equipment slot instead of the old integer+held pair.
        require("stack.inventoryTick(level(), this, held ? EquipmentSlot.MAINHAND : null);" in robot,
                "Robot held items no longer receive MAINHAND inventoryTick semantics")
        require("stack.inventoryTick(level(), this, null);" not in robot,
                "Robot held-item tick still drops the old held=true information")

        # 1.21.11 widened fall distance to double. Keep the no-fall-damage robot behaviour on the actual override.
        require("public boolean causeFallDamage(double distance, float damageMultiplier, DamageSource source)" in robot,
                "Robot no-fall-damage callback is not using the 1.21.11 signature")
        require("public boolean causeFallDamage(float distance" not in robot,
                "Dead pre-1.21.11 fall callback survived materialization")

        # Builder-board/AI save compatibility accepts the supported legacy BlockPos layouts.
        robotics_nbt = src(new, "robotics/RoboticsNbtUtil.java")
        require("tryReadBlockPos(parent.get(key)).orElse(BlockPos.ZERO)" in robotics_nbt,
                "Robot legacy BlockPos decoder was collapsed to the modern-only representation")
        for coords in ('"X", "Y", "Z"', '"x", "y", "z"', '"i", "j", "k"'):
            require(coords in robotics_nbt, f"Robot legacy BlockPos decoder lost coordinate form {coords}")
        require('compound.contains("pos")' in robotics_nbt and "IntArrayTag" in robotics_nbt,
                "Robot legacy BlockPos decoder lost nested/int-array compatibility")

        # Zone Planner modifier input is part of robot work-area behaviour; Shift+M must use live key state.
        zone_gui = src(new, "robotics/gui/GuiZonePlanner.java")
        require("RenderCompat.hasInputShiftDown()" in zone_gui,
                "Zone Planner Shift modifier is not bridged on 1.21.11")
        require("if (false)" not in zone_gui,
                "Zone Planner still contains a constant-folded modifier check")
        gui_bc8 = src(new, "lib/gui/GuiBC8.java")
        require("extends BCContainerScreen<" in gui_bc8, "Robot GUI bypasses the native input boundary")
        bridge = src(new, "lib/compat/minecraft/gui/BCContainerScreen.java")
        key_bridge = method(bridge, "public boolean keyPressed(KeyEvent event)")
        require("BCInputState.pushShift(event.hasShiftDown())" in key_bridge and "try (" in key_bridge
                and "keyPressed(event.key(), event.scancode(), event.modifiers())" in key_bridge,
                "Robot screens lost scoped keyboard modifiers or legacy callback dispatch")


    print("Robot core + boards/jobs + docking + UI 1.21.11 parity validation: OK")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

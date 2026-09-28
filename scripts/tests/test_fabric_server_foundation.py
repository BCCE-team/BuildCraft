from __future__ import annotations

from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
FABRIC = ROOT / "source-platforms/fabric/src/main/java"


class FabricServerFoundationTests(unittest.TestCase):
    def read(self, relative: str) -> str:
        return (ROOT / relative).read_text(encoding="utf-8")

    def test_bootstrap_installs_loader_neutral_boundaries(self) -> None:
        text = self.read("source-platforms/fabric/src/main/java/buildcraft/fabric/BuildCraftFabric.java")
        for token in (
            "PlatformRuntime.install(FabricRuntimePlatform.INSTANCE)",
            "PlatformApi2Bootstrap.install()",
            "MjApi2PlatformBridge.install()",
            "FabricNetworkManager.installServerReceivers()",
            "ServerLifecycleEvents.SERVER_STARTED",
            "ServerLifecycleEvents.SERVER_STOPPED",
        ):
            self.assertIn(token, text)

    def test_network_transport_has_all_common_send_paths(self) -> None:
        text = self.read("source-platforms/fabric/src/main/java/buildcraft/lib/net/FabricNetworkManager.java")
        for method in (
            "sendToAll(Object message)",
            "sendToPlayer(Object message, ServerPlayer player)",
            "sendToServer(Object message)",
            "sendToTrackingChunk(Object message, LevelChunk chunk)",
            "sendToDimension(Object message, ResourceKey<Level> dimension)",
        ):
            self.assertIn(method, text)
        self.assertIn("if (serverReceiversInstalled && direction != BCMessageDirection.CLIENTBOUND)", text)
        self.assertIn("if (clientBridge != ClientBridge.UNAVAILABLE)", text)
        self.assertNotIn("net.minecraftforge", text)
        self.assertNotIn("net.neoforged", text)

    def test_transfer_bridges_cover_item_fluid_and_energy(self) -> None:
        text = self.read("source-platforms/fabric/src/main/java/buildcraft/lib/platform/storage/PlatformStorage.java")
        self.assertIn("ItemStorage.SIDED.find", text)
        self.assertIn("FluidStorage.SIDED.find", text)
        self.assertIn("team.reborn.energy.api.EnergyStorage.SIDED.find", text)
        self.assertIn("Transaction.openOuter()", text)

    def test_stage_5_1_generic_fabric_item_storage_stays_slotless(self) -> None:
        platform = self.read("source-platforms/fabric/src/main/java/buildcraft/lib/platform/storage/PlatformStorage.java")
        bootstrap = self.read(
            "source-platforms/fabric/src/main/java/buildcraft/lib/internal/api/v2/platform/PlatformApi2Bootstrap.java"
        )
        storage_contract = self.read("source-shared/src/main/java/buildcraft/lib/platform/storage/ItemStorage.java")

        self.assertIn("storage instanceof SlottedStorage<?> rawSlotted", platform)
        self.assertIn("public static ItemTransferAccess itemTransfer(Level level, BlockPos pos, Direction face)", platform)
        self.assertIn("return Optional.ofNullable(PlatformStorage.itemTransfer(level, pos, side));", bootstrap)
        self.assertNotIn("TransferAdapters.items(storage)", bootstrap)
        self.assertIn("Internal <em>slotted</em> item-storage view", storage_contract)
        self.assertIn("must use the slotless transfer", storage_contract)

    def test_stage_5_1_slotted_adapter_targets_the_requested_native_slot(self) -> None:
        text = self.read("source-platforms/fabric/src/main/java/buildcraft/lib/platform/storage/PlatformStorage.java")
        start = text.index("private static final class FabricSlottedItems")
        end = text.index("private static final class FabricSlottedFluids", start)
        slotted = text[start:end]

        self.assertIn("slotted.getSlot(slot)", slotted)
        self.assertIn("target.insert(ItemVariant.of(stack)", slotted)
        self.assertIn("target.extract(variant, amount", slotted)
        self.assertNotIn("storage.insert(ItemVariant.of(stack)", slotted)
        self.assertNotIn("storage.extract(variant, amount", slotted)

    def test_stage_5_1_operation_scope_owns_one_fabric_item_transaction(self) -> None:
        platform = self.read("source-platforms/fabric/src/main/java/buildcraft/lib/platform/storage/PlatformStorage.java")
        transactions = self.read(
            "source-platforms/fabric/src/main/java/buildcraft/lib/internal/transfer/FabricTransferTransactions.java"
        )
        operation = self.read("source-shared/src/main/java/buildcraft/lib/internal/transfer/OperationScope.java")

        self.assertIn("FabricTransferTransactions.with(scope", platform)
        for token in (
            "scope.sharedAttachment(TRANSACTION_KEY",
            "scope.onRootClose(commit ->",
            "if (commit) transaction.commit();",
            "else transaction.abort();",
            "Transaction.openNested(root)",
            "scope.rootMode() == OperationMode.EXECUTE",
            "scope.markFailed();",
        ):
            self.assertIn(token, transactions)

        for token in (
            "public OperationMode rootMode()",
            "public void onRootClose(RootCloseListener listener)",
            "public void markFailed()",
            "boolean commit = root.mode == OperationMode.EXECUTE && !root.failed",
        ):
            self.assertIn(token, operation)

    def test_stage_5_2_generic_fabric_fluid_storage_stays_slotless(self) -> None:
        platform = self.read("source-platforms/fabric/src/main/java/buildcraft/lib/platform/storage/PlatformStorage.java")
        bootstrap = self.read(
            "source-platforms/fabric/src/main/java/buildcraft/lib/internal/api/v2/platform/PlatformApi2Bootstrap.java"
        )
        storage_contract = self.read("source-shared/src/main/java/buildcraft/lib/platform/storage/FluidStorage.java")

        self.assertIn("storage instanceof SlottedStorage<?> rawSlotted", platform)
        self.assertIn("public static FluidTransferAccess fluidTransfer(Level level, BlockPos pos, Direction face)", platform)
        self.assertIn("return Optional.ofNullable(PlatformStorage.fluidTransfer(level, pos, side));", bootstrap)
        self.assertNotIn("TransferAdapters.fluids(storage", bootstrap)
        self.assertIn("tank-indexed", storage_contract)
        self.assertIn("must stay on the slotless", storage_contract)

    def test_stage_5_2_fabric_fluid_transfer_uses_operation_scope_transaction(self) -> None:
        access = self.read(
            "source-platforms/fabric/src/main/java/buildcraft/lib/internal/transfer/FabricFluidTransferAccess.java"
        )
        transactions = self.read(
            "source-platforms/fabric/src/main/java/buildcraft/lib/internal/transfer/FabricTransferTransactions.java"
        )
        platform = self.read("source-platforms/fabric/src/main/java/buildcraft/lib/platform/storage/PlatformStorage.java")

        self.assertIn("FabricTransferTransactions.with(scope", access)
        self.assertIn("FabricTransferTransactions.probe(", access)
        self.assertNotIn("Transaction.openOuter()", access)
        self.assertIn("FabricTransferTransactions.with(scope", platform)
        self.assertIn("private static final Object TRANSACTION_KEY", transactions)
        self.assertIn("Transaction.openNested(parent)", transactions)

    def test_stage_5_2_fabric_fluid_amounts_commit_only_whole_millibuckets(self) -> None:
        access = self.read(
            "source-platforms/fabric/src/main/java/buildcraft/lib/internal/transfer/FabricFluidTransferAccess.java"
        )
        for token in (
            "long aligned = alignDroplets(probed);",
            "return Math.max(0L, droplets) / DROPLETS_PER_MB * DROPLETS_PER_MB;",
            "storage.insert(variant, aligned, transaction)",
            "storage.extract(variant, aligned, transaction)",
            'requireExact(scope, "insert", aligned, executed)',
            'requireExact(scope, "extract", aligned, executed)',
            "scope.markFailed();",
        ):
            self.assertIn(token, access)

    def test_stage_5_2_fabric_fluid_variant_nbt_is_lossless_or_rejected(self) -> None:
        variants = self.read(
            "source-platforms/fabric/src/main/java/buildcraft/lib/internal/transfer/FabricFluidVariants.java"
        )
        for token in (
            "nativeVariant.copyNbt()",
            "FluidComponentPayload.of(NBT_FORMAT, encode(nbt))",
            "components.copyCanonicalBytes()",
            "new StringTagVisitor().visit(tag)",
            "TagParser.parseTag(new String(bytes, StandardCharsets.UTF_8))",
            "if (!NBT_FORMAT.equals(format) && !FORGE_NBT_FORMAT.equals(format))",
            "catch (IllegalArgumentException malformedPayload)",
            "return Optional.empty();",
        ):
            self.assertIn(token, variants)

    def test_stage_5_2_sided_fluid_lookup_and_stable_tank_view(self) -> None:
        platform = self.read("source-platforms/fabric/src/main/java/buildcraft/lib/platform/storage/PlatformStorage.java")
        self.assertIn("FluidStorage.SIDED.find(", platform)
        self.assertIn("level, pos, state, blockEntity, face", platform)
        self.assertIn("slotted.getSlotCount()", platform)
        self.assertIn("slotted.getSlot(tank)", platform)
        self.assertIn("implements FilteredFluidStorage<FluidVolume>", platform)

    def test_stage_5_3_fabric_energy_transfer_uses_operation_scope_transaction(self) -> None:
        access = self.read(
            "source-platforms/fabric/src/main/java/buildcraft/lib/internal/transfer/FabricEnergyTransferAccess.java"
        )
        transactions = self.read(
            "source-platforms/fabric/src/main/java/buildcraft/lib/internal/transfer/FabricTransferTransactions.java"
        )
        platform = self.read("source-platforms/fabric/src/main/java/buildcraft/lib/platform/storage/PlatformStorage.java")
        bootstrap = self.read(
            "source-platforms/fabric/src/main/java/buildcraft/lib/internal/api/v2/platform/PlatformApi2Bootstrap.java"
        )

        for token in (
            "FabricTransferTransactions.with(scope",
            "scope.enter(storage)",
            "storage.insert(offered, transaction)",
            "storage.extract(requested, transaction)",
        ):
            self.assertIn(token, access)
        self.assertNotIn("Transaction.openOuter()", access)
        self.assertIn("private static final Object TRANSACTION_KEY", transactions)
        self.assertIn("public static EnergyTransferAccess energyTransfer(Level level, BlockPos pos, Direction face)", platform)
        self.assertIn("return Optional.ofNullable(PlatformStorage.energyTransfer(level, pos, side));", bootstrap)
        self.assertNotIn("TransferAdapters.energy(storage)", bootstrap)

    def test_stage_5_3_external_energy_lookup_is_sided_and_item_aware(self) -> None:
        platform = self.read("source-platforms/fabric/src/main/java/buildcraft/lib/platform/storage/PlatformStorage.java")
        for token in (
            "team.reborn.energy.api.EnergyStorage.SIDED.find(level, pos, state, blockEntity, face)",
            "ContainerItemContext.withConstant(stack)",
            "context.find(team.reborn.energy.api.EnergyStorage.ITEM)",
            "public static EnergyTransferAccess energyTransfer(ItemStack stack)",
        ):
            self.assertIn(token, platform)

        energy_start = platform.index("private static final class FabricEnergy")
        energy = platform[energy_start:]
        self.assertIn("OperationScope.open(simulate ? OperationMode.SIMULATE : OperationMode.EXECUTE)", energy)
        self.assertIn("transfer.insert(amount, scope)", energy)
        self.assertIn("transfer.extract(amount, scope)", energy)

    def test_stage_5_3_mj_external_bridge_is_conservative_and_transaction_native(self) -> None:
        bridge = self.read("source-platforms/fabric/src/main/java/buildcraft/lib/internal/mj/MjApi2PlatformBridge.java")
        for token in (
            "PlatformStorage.energyTransfer(level, pos, side)",
            "conversion.microMjToWholeFe(offered.microMj())",
            "conversion.microMjToWholeFe(requested.microMj())",
            "try (OperationScope scope = OperationScope.open(mode))",
            "storage.insert(external, scope)",
            "storage.extract(external, scope)",
            "conversion.feToMicroMj(external)",
            "if (external > Long.MAX_VALUE / ratio)",
            "return MjAmount.ofMicro(Long.MAX_VALUE);",
        ):
            self.assertIn(token, bridge)
        self.assertNotIn("Transaction.openOuter()", bridge)
        self.assertNotIn("team.reborn.energy.api.EnergyStorage", bridge)

    def test_stage_5_4_item_flow_contract_is_loader_neutral(self) -> None:
        flow_contract = self.read(
            "source-shared/src/main/java/buildcraft/transport/internal/pipe/IFlowItems.java"
        )
        legacy_flow = self.read(
            "source-families/legacy/src/main/java/buildcraft/transport/pipe/flow/PipeFlowItems.java"
        )

        self.assertIn("OperationMode mode", flow_contract)
        self.assertNotRegex(flow_contract, r"net\.(?:minecraftforge|neoforged|fabricmc)\.")
        self.assertIn("PlatformItemPipeTransfer.extract(", legacy_flow)
        self.assertIn("PlatformItemPipeTransfer.insert(", legacy_flow)
        self.assertNotRegex(legacy_flow, r"net\.(?:minecraftforge|neoforged|fabricmc)\.")
        self.assertNotIn("IItemTransactor", legacy_flow)
        self.assertNotIn("ItemTransactorHelper", legacy_flow)
        self.assertNotIn("FluidAction", legacy_flow)

    def test_stage_5_4_fabric_item_pipe_endpoint_is_transaction_native(self) -> None:
        endpoint = self.read(
            "source-platforms/fabric/src/main/java/buildcraft/lib/platform/storage/PlatformItemPipeTransfer.java"
        )
        platform = self.read(
            "source-platforms/fabric/src/main/java/buildcraft/lib/platform/storage/PlatformStorage.java"
        )

        self.assertIn("PlatformStorage.itemTransfer(level, pos, side)", endpoint)
        self.assertIn("OperationScope.open(OperationMode.SIMULATE)", endpoint)
        self.assertIn("OperationScope.open(OperationMode.EXECUTE)", endpoint)
        self.assertIn("scope.markFailed();", endpoint)
        self.assertIn("ItemStorage.SIDED.find(level, pos, state, blockEntity, face)", platform)
        self.assertNotIn("getStackInSlot", endpoint)
        self.assertNotIn("SlottedStorage", endpoint)

    def test_stage_5_4_does_not_add_fabric_pipeflow_or_behaviour_forks(self) -> None:
        item_flow = FABRIC / "buildcraft/transport/pipe/flow/PipeFlowItems.java"
        behaviours = FABRIC / "buildcraft/transport/pipe/behaviour"
        self.assertFalse(item_flow.exists())
        self.assertFalse(behaviours.exists())

    def test_stage_5_5_fluid_flow_contract_is_loader_neutral(self) -> None:
        flow_contract = self.read(
            "source-families/legacy/src/main/java/buildcraft/transport/internal/pipe/IFlowFluid.java"
        )
        legacy_flow = self.read(
            "source-families/legacy/src/main/java/buildcraft/transport/pipe/flow/PipeFlowFluids.java"
        )
        fluid_event = self.read(
            "source-families/legacy/src/main/java/buildcraft/transport/internal/pipe/PipeEventFluid.java"
        )

        for text in (flow_contract, legacy_flow, fluid_event):
            self.assertNotRegex(text, r"net\.(?:minecraftforge|neoforged|fabricmc)\.")
        self.assertIn("FluidVolume tryExtractFluid(", flow_contract)
        self.assertIn("OperationMode mode", flow_contract)
        self.assertIn("PlatformFluidPipeTransfer.extract(", legacy_flow)
        self.assertIn("PlatformFluidPipeTransfer.insert(", legacy_flow)
        self.assertIn("runtimePipe.applyFluidIngress", legacy_flow)
        self.assertIn("runtimePipe.applyFluidRouting", legacy_flow)
        self.assertNotIn("FluidStack", flow_contract)
        self.assertNotIn("IFluidHandler", legacy_flow)
        self.assertNotIn("LazyOptional", legacy_flow)

    def test_stage_5_5_fabric_fluid_endpoint_uses_transfer_api_and_skips_unloaded_chunks(self) -> None:
        endpoint = self.read(
            "source-platforms/fabric/src/main/java/buildcraft/lib/platform/storage/PlatformFluidPipeTransfer.java"
        )
        flow = self.read(
            "source-families/legacy/src/main/java/buildcraft/transport/pipe/flow/PipeFlowFluids.java"
        )

        self.assertIn("PlatformStorage.fluidTransfer(level, pos, side)", endpoint)
        self.assertIn("OperationScope.open(OperationMode.SIMULATE)", endpoint)
        self.assertIn("OperationScope.open(OperationMode.EXECUTE)", endpoint)
        self.assertIn("scope.markFailed();", endpoint)
        self.assertIn("!level.hasChunkAt(pos)", endpoint)
        self.assertGreaterEqual(flow.count("hasChunkAt(targetPos)"), 3)
        self.assertNotIn("net.minecraftforge", endpoint)
        self.assertNotIn("IFluidHandler", endpoint)

    def test_stage_5_5_fluid_save_and_cross_loader_variant_data_are_lossless(self) -> None:
        pipe_data = self.read(
            "source-families/legacy/src/main/java/buildcraft/transport/pipe/flow/FluidPipeData.java"
        )
        variants = self.read(
            "source-platforms/fabric/src/main/java/buildcraft/lib/internal/transfer/FabricFluidVariants.java"
        )
        forge_endpoint = self.read(
            "source-platforms/forge/src/main/java/buildcraft/lib/platform/storage/PlatformFluidPipeTransfer.java"
        )

        for token in ("bcFluidVolume", "componentFormat", "componentData", "copyCanonicalBytes()"):
            self.assertIn(token, pipe_data)
        self.assertIn("forge_fluid_stack_snbt_v1", variants)
        self.assertIn("fabric_transfer_snbt_v1", forge_endpoint)
        self.assertIn("readLegacyNbt", forge_endpoint)

    def test_stage_5_5_forge_capability_is_only_a_platform_shell(self) -> None:
        pipe = self.read("source-platforms/forge/src/main/java/buildcraft/transport/pipe/Pipe.java")
        endpoint = self.read(
            "source-platforms/forge/src/main/java/buildcraft/lib/platform/storage/PlatformFluidPipeTransfer.java"
        )
        self.assertIn("PlatformFluidPipeTransfer.expose(fluidFlow, facing)", pipe)
        self.assertIn("public static IFluidHandler expose(IFlowFluid flow, Direction side)", endpoint)
        self.assertIn("flow.insertFluidsExternal(", endpoint)
        self.assertNotIn("getCapability", self.read(
            "source-families/legacy/src/main/java/buildcraft/transport/pipe/flow/PipeFlowFluids.java"
        ))

    def test_stage_5_5_does_not_add_fabric_fluid_pipe_gameplay_forks(self) -> None:
        self.assertFalse((FABRIC / "buildcraft/transport/pipe/flow/PipeFlowFluids.java").exists())
        self.assertFalse((FABRIC / "buildcraft/transport/internal/pipe/IFlowFluid.java").exists())
        self.assertFalse((FABRIC / "buildcraft/transport/internal/pipe/PipeEventFluid.java").exists())
        self.assertFalse((FABRIC / "buildcraft/transport/pipe/behaviour").exists())

    def test_stage_5_6_machine_world_actions_use_fabric_protection_hook(self) -> None:
        actions = self.read(
            "source-platforms/fabric/src/main/java/buildcraft/lib/platform/permission/PlatformWorldActions.java"
        )
        automation = self.read(
            "source-shared/src/main/java/buildcraft/robotics/internal/api2/RoboticsApi2Bootstrap.java"
        )

        # Machines must be able to share the same operation boundary as robotics rather than grow
        # a Fabric-only Tile implementation. Break permissions go through Fabric's public hook;
        # placement also keeps vanilla's state-survival invariant for raw construction actions.
        for token in (
            "PlayerBlockBreakEvents.BEFORE.invoker().beforeBlockBreak(",
            "actor.mayBuild()",
            "actor.mayUseItemAt(pos, Direction.UP, ItemStack.EMPTY)",
            "world.getWorldBorder().isWithinBounds(pos)",
            "state.canSurvive(level, pos)",
        ):
            self.assertIn(token, actions)
        self.assertNotRegex(actions, r"net\.(?:minecraftforge|neoforged)\.")

        for token in (
            "WorldOperationKind.BLOCK_BREAK",
            "WorldOperationKind.BLOCK_PLACE",
            "BlockUtil.canBreakBlock(level, request.target(), player)",
            "BlockUtil.placeBlock(level, request.target(), request.state(), player, Direction.UP, 3)",
        ):
            self.assertIn(token, automation)

    def test_stage_5_6_does_not_add_fabric_machine_tile_forks(self) -> None:
        machine_packages = (
            "buildcraft/factory/tile",
            "buildcraft/builders/tile",
            "buildcraft/silicon/tile",
        )
        for package in machine_packages:
            self.assertFalse((FABRIC / package).exists(), package)

    def test_stage_5_7_robotics_actor_lifecycle_is_a_platform_service(self) -> None:
        bootstrap = self.read("source-platforms/fabric/src/main/java/buildcraft/fabric/BuildCraftFabric.java")
        actors = self.read(
            "source-platforms/fabric/src/main/java/buildcraft/lib/platform/actor/PlatformActors.java"
        )
        shared_actors = self.read("source-shared/src/main/java/buildcraft/lib/platform/actor/BCActors.java")

        # Robot ownership must use the existing actor cache, but never make a Fabric-only robot
        # entity, task or AI. World unload and server stop release the cached detached players.
        for token in (
            "ServerWorldEvents.UNLOAD.register((server, level) -> BCActors.unloadWorld(level));",
            "BCActors.stopServer();",
            "FabricServerState.stopped(server);",
        ):
            self.assertIn(token, bootstrap)
        for token in (
            "new ServerPlayer(level.getServer(), level, profile)",
            "public static void afterAcquire",
        ):
            self.assertIn(token, actors)
        self.assertNotRegex(actors, r"net\.(?:minecraftforge|neoforged)\.")
        self.assertIn("private static final ActorCache<ServerLevel, GameProfile, ServerPlayer> PLAYERS", shared_actors)
        self.assertIn("public static void unloadWorld(ServerLevel level)", shared_actors)
        self.assertIn("public static void stopServer()", shared_actors)

    def test_stage_5_7_does_not_add_fabric_robot_gameplay_forks(self) -> None:
        forbidden = (
            "buildcraft/robotics/ai",
            "buildcraft/robotics/entity",
            "buildcraft/robotics/internal/legacy/robots",
        )
        for package in forbidden:
            self.assertFalse((FABRIC / package).exists(), package)

    def test_fabric_platform_does_not_fork_gameplay(self) -> None:
        forbidden_prefixes = ("Tile", "PipeFlow", "PipeBehaviour", "Robot", "BoardRobot", "EntityRobot")
        offenders = []
        for path in FABRIC.rglob("*.java"):
            if path.name.startswith(forbidden_prefixes):
                offenders.append(path.relative_to(ROOT).as_posix())
        self.assertEqual([], offenders)

    def test_gameplay_parity_metadata_does_not_claim_module_bootstrap(self) -> None:
        text = self.read("source-platforms/fabric/src/main/resources/fabric.mod.json")
        self.assertIn('"buildcraft:gameplay_parity": true', text)
        self.assertNotIn('"buildcraft:server_foundation"', text)
        self.assertNotIn('"buildcraft:skeleton"', text)
        self.assertNotIn('"provides"', text)


if __name__ == "__main__":
    unittest.main()

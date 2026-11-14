/* Copyright (c) 2016 SpaceToad and the BuildCraft team; BCCE Fabric port. MPL-2.0. */
package buildcraft.builders;

import buildcraft.builders.snapshot.MessageSnapshotRequest;
import buildcraft.builders.snapshot.MessageSnapshotResponse;
import buildcraft.builders.snapshot.MessageSnapshotResponseClientHandler;
import buildcraft.builders.snapshot.RulesLoader;
import buildcraft.builders.tile.TileArchitectTable;
import buildcraft.builders.tile.TileBuilder;
import buildcraft.builders.tile.TileConstructionMarker;
import buildcraft.builders.tile.TileElectronicLibrary;
import buildcraft.builders.tile.TileFiller;
import buildcraft.builders.tile.TileQuarry;
import buildcraft.builders.tile.QuarryFrameCleanup;
import buildcraft.builders.tile.TileReplacer;
import buildcraft.core.BCCore;
import buildcraft.lib.internal.capabilities.BCCapabilityRegistration;
import buildcraft.lib.internal.capabilities.IBCCapabilityProvider;
import buildcraft.lib.internal.mj.MjCapabilities;
import buildcraft.lib.internal.module.BCModules;
import buildcraft.lib.internal.tiles.TilesAPI;
import buildcraft.lib.internal.core.BuildCraftAPI;
import buildcraft.lib.misc.CapUtil;
import buildcraft.lib.net.BCNetworkSide;
import buildcraft.lib.net.BCPacketContext;
import buildcraft.lib.net.MessageManager;
import buildcraft.lib.platform.registry.RegistryBinding;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;

/** Fabric 26.3 bootstrap for the complete Builders module. */
public final class BCBuilders {
    public static final String MODID = "buildcraftbuilders";
    private static boolean bootstrapped;

    private BCBuilders() {}

    public static synchronized void bootstrap() {
        if (bootstrapped) return;
        BuildCraftAPI.withModule(MODID, BCBuilders::bootstrapInternal);
        bootstrapped = true;
    }

    private static void bootstrapInternal() {
        // Blocks must be bound before block items and block-entity types are created.
        BCBuildersBlocks.registry(RegistryBinding.direct());
        BCBuildersItems.registry(RegistryBinding.direct());
        BCCore.BUILDCRAFT_TAB.addItemProvider(BCBuildersItems::getCreativeTabItems);
        BCBuildersGuis.preInit(RegistryBinding.direct());

        BCBuildersSchematics.preInit();
        BCBuildersConfig.preInit();
        BCBuildersRegistries.preInit();
        BCBuildersStatements.preInit();
        QuarryFrameCleanup.init();
        registerCapabilities();

        MessageManager.registerMessageClass(
            BCModules.BUILDERS,
            MessageSnapshotRequest.class,
            MessageSnapshotRequest.HANDLER,
            MessageSnapshotRequest::toBytes,
            MessageSnapshotRequest::new,
            BCNetworkSide.SERVER
        );
        MessageManager.registerClientboundMessageClass(
            BCModules.BUILDERS,
            MessageSnapshotResponse.class,
            null,
            MessageSnapshotResponse::toBytes,
            MessageSnapshotResponse::new
        );

        BCBuildersRegistries.init();
        RulesLoader.loadAll();
    }

    private static void registerCapabilities() {
        BlockEntityType<TileArchitectTable> architect = BCBuildersBlocks.ARCHITECT_TILE_BC8.get();
        BlockEntityType<TileBuilder> builder = BCBuildersBlocks.BUILDER_TILE_BC8.get();
        BlockEntityType<TileConstructionMarker> marker = BCBuildersBlocks.CONSTRUCTION_MARKER_TILE_BC8.get();
        BlockEntityType<TileElectronicLibrary> library = BCBuildersBlocks.LIBRARY_TILE_BC8.get();
        BlockEntityType<TileFiller> filler = BCBuildersBlocks.FILLER_TILE_BC8.get();
        BlockEntityType<TileQuarry> quarry = BCBuildersBlocks.QUARRY_TILE_BC8.get();
        BlockEntityType<TileReplacer> replacer = BCBuildersBlocks.REPLACER_TILE_BC8.get();

        registerMachineCapabilities(builder);
        registerMachineCapabilities(filler);
        registerMachineCapabilities(quarry);

        BCCapabilityRegistration.registerBlockEntity(CapUtil.CAP_ITEMS, architect);
        BCCapabilityRegistration.registerBlockEntity(CapUtil.CAP_ITEMS, builder);
        BCCapabilityRegistration.registerBlockEntity(CapUtil.CAP_ITEMS, marker);
        BCCapabilityRegistration.registerBlockEntity(CapUtil.CAP_ITEMS, library);
        BCCapabilityRegistration.registerBlockEntity(CapUtil.CAP_ITEMS, filler);
        BCCapabilityRegistration.registerBlockEntity(CapUtil.CAP_ITEMS, replacer);

        BCCapabilityRegistration.registerBlockEntity(CapUtil.CAP_FLUIDS, builder);
        BCCapabilityRegistration.registerBlockEntity(TilesAPI.CAP_HAS_WORK, builder);
        BCCapabilityRegistration.registerBlockEntity(TilesAPI.CAP_CONTROLLABLE, filler);
        BCCapabilityRegistration.registerBlockEntity(TilesAPI.CAP_HAS_WORK, quarry);
        BCCapabilityRegistration.registerBlockEntity(CapUtil.CAP_ITEM_TRANSACTOR, quarry);
    }

    private static <BE extends BlockEntity & IBCCapabilityProvider> void registerMachineCapabilities(
        BlockEntityType<BE> type
    ) {
        BCCapabilityRegistration.registerBlockEntity(MjCapabilities.CAP_CONNECTOR, type);
        BCCapabilityRegistration.registerBlockEntity(MjCapabilities.CAP_RECEIVER, type);
        BCCapabilityRegistration.registerBlockEntity(MjCapabilities.CAP_REDSTONE_RECEIVER, type);
        BCCapabilityRegistration.registerBlockEntity(MjCapabilities.CAP_READABLE, type);
        BCCapabilityRegistration.registerBlockEntity(MjCapabilities.CAP_PASSIVE_PROVIDER, type);
    }

    /** Installs the client-only packet handler after the common payload type is registered. */
    public static void bootstrapClientNetwork() {
        MessageManager.setHandler(MessageSnapshotResponse.class, (message, contextSupplier) -> {
            BCPacketContext context = contextSupplier.get();
            context.enqueueWork(() -> MessageSnapshotResponseClientHandler.handle(message));
        }, BCNetworkSide.CLIENT);
    }
}

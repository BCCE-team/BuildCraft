/* Fabric 26.3 module bootstrap for BuildCraft Factory. MPL-2.0. */
package buildcraft.factory;

import buildcraft.core.BCCore;
import buildcraft.factory.tile.TileDistiller;
import buildcraft.factory.tile.TileTank;
import buildcraft.lib.internal.capabilities.BCCapabilityRegistration;
import buildcraft.lib.internal.capabilities.IBCCapabilityProvider;
import buildcraft.lib.internal.core.BuildCraftAPI;
import buildcraft.lib.internal.mj.MjCapabilities;
import buildcraft.lib.internal.tiles.TilesAPI;
import buildcraft.lib.misc.CapUtil;
import buildcraft.lib.platform.registry.RegistryBinding;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;

/**
 * Registers all Factory gameplay content in the native Fabric registries.
 * Internal tile, recipe, and transfer logic stays shared with NeoForge 26.3.
 */
public final class BCFactory {
    public static final String MODID = "buildcraftfactory";
    private static boolean bootstrapped;

    private BCFactory() {}

    public static synchronized void bootstrap() {
        if (bootstrapped) return;
        BuildCraftAPI.withModule(MODID, BCFactory::bootstrapInternal);
        bootstrapped = true;
    }

    private static void bootstrapInternal() {
        // Fabric registers immediately: BlockEntity constructors cannot resolve
        // their block until the block registry has been populated.
        BCFactoryBlocks.BLOCKS.register(RegistryBinding.direct());
        BCFactoryItems.registry(RegistryBinding.direct());
        BCFactoryBlocks.BLOCK_ENTITYS.register(RegistryBinding.direct());
        BCFactoryGuis.registry(RegistryBinding.direct());
        BCFactoryRecipeSerializers.registry(RegistryBinding.direct());

        BCCore.BUILDCRAFT_TAB.addItemProvider(BCFactoryItems::getCreativeTabItems);
        registerCapabilities();
        validateNetworkIds();
    }

    private static void registerCapabilities() {
        var tank = BCFactoryBlocks.ENTITYBLOCKTANK.get();
        var pump = BCFactoryBlocks.ENTITYBLOCKPUMP.get();
        var floodGate = BCFactoryBlocks.ENTITYBLOCKFLOODGATE.get();
        var miningWell = BCFactoryBlocks.ENTITYBLOCKMININGWELL.get();
        var distiller = BCFactoryBlocks.ENTITYBLOCKDISTILLER.get();
        var heatExchange = BCFactoryBlocks.ENTITYBLOCKHEATEXCHANGE.get();
        var autoWorkbench = BCFactoryBlocks.ENTITYBLOCKAUTOBENCH.get();
        var chute = BCFactoryBlocks.ENTITYBLOCKCHUTE.get();

        BCCapabilityRegistration.registerBlockEntity(CapUtil.CAP_FLUIDS, tank);
        BCCapabilityRegistration.registerBlockEntity(CapUtil.CAP_FLUIDS, pump);
        BCCapabilityRegistration.registerBlockEntity(CapUtil.CAP_FLUIDS, floodGate);
        BCCapabilityRegistration.registerBlockEntity(CapUtil.CAP_FLUIDS, distiller);
        BCCapabilityRegistration.registerBlockEntity(CapUtil.CAP_FLUIDS, heatExchange);

        BCCapabilityRegistration.registerBlockEntity(CapUtil.CAP_ITEMS, autoWorkbench);
        BCCapabilityRegistration.registerBlockEntity(CapUtil.CAP_ITEMS, chute);
        BCCapabilityRegistration.registerBlockEntity(CapUtil.CAP_ITEM_TRANSACTOR, miningWell);

        registerMachineCapabilities(pump);
        registerMachineCapabilities(miningWell);
        registerMachineCapabilities(distiller);
        registerMachineCapabilities(autoWorkbench);
        registerMachineCapabilities(chute);

        BCCapabilityRegistration.registerBlockEntity(TilesAPI.CAP_HAS_WORK, pump);
        BCCapabilityRegistration.registerBlockEntity(TilesAPI.CAP_HAS_WORK, miningWell);
        BCCapabilityRegistration.registerBlockEntity(TilesAPI.CAP_HAS_WORK, distiller);
        BCCapabilityRegistration.registerBlockEntity(TilesAPI.CAP_HAS_WORK, autoWorkbench);
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

    private static void validateNetworkIds() {
        int tankFluid = TileTank.NET_FLUID_DELTA;
        int gasOutput = TileDistiller.NET_TANK_GAS_OUT;
        int input = TileDistiller.NET_TANK_IN;
        int liquidOutput = TileDistiller.NET_TANK_LIQUID_OUT;
    }
}

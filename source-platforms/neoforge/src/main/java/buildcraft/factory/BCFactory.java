package buildcraft.factory;

import buildcraft.factory.BCFactoryClientRenderers;
import buildcraft.lib.platform.client.PlatformClientRegistration;
import buildcraft.lib.platform.registry.RegistryBinding;
import buildcraft.lib.internal.mj.MjCapabilities;

import buildcraft.lib.internal.capabilities.BCCapabilityRegistration;
import buildcraft.lib.internal.tiles.TilesAPI;
import buildcraft.core.BCCore;
import buildcraft.factory.client.render.RenderDistiller;
import buildcraft.factory.client.render.RenderHeatExchange;
import buildcraft.factory.client.render.RenderMiningWell;
import buildcraft.factory.client.render.RenderPump;
import buildcraft.factory.client.render.RenderTank;
import buildcraft.factory.tile.TileDistiller;
import buildcraft.factory.tile.TileTank;
import buildcraft.lib.misc.CapUtil;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.neoforged.neoforge.data.event.GatherDataEvent;

@Mod(BCFactory.MODID)
public class BCFactory {
    public static final String MODID = "buildcraftfactory";

    public BCFactory(IEventBus modEventBus, ModContainer modContainer) {
        modEventBus.addListener(this::gatherData);
        modEventBus.addListener(this::registerCapabilities);

        BCFactoryBlocks.registry(RegistryBinding.on(modEventBus));
        BCFactoryItems.registry(RegistryBinding.on(modEventBus));
        BCFactoryGuis.registry(RegistryBinding.on(modEventBus));
        BCCore.BUILDCRAFT_TAB.addItemProvider(BCFactoryItems::getCreativeTabItems);

        validateNetworkIds();
    }

    public void gatherData(GatherDataEvent event) {
        event.getGenerator().addProvider(
            event.includeServer(),
            new BCFactoryRecipesProvider(event.getGenerator().getPackOutput(), event.getLookupProvider())
        );
    }

    private void registerCapabilities(RegisterCapabilitiesEvent event) {
        BlockEntityType<buildcraft.factory.tile.TileTank> tank = BCFactoryBlocks.ENTITYBLOCKTANK.get();
        BlockEntityType<buildcraft.factory.tile.TilePump> pump = BCFactoryBlocks.ENTITYBLOCKPUMP.get();
        BlockEntityType<buildcraft.factory.tile.TileFloodGate> floodGate = BCFactoryBlocks.ENTITYBLOCKFLOODGATE.get();
        BlockEntityType<buildcraft.factory.tile.TileMiningWell> miningWell = BCFactoryBlocks.ENTITYBLOCKMININGWELL.get();
        BlockEntityType<buildcraft.factory.tile.TileDistiller_BC8> distiller = BCFactoryBlocks.ENTITYBLOCKDISTILLER.get();
        BlockEntityType<buildcraft.factory.tile.TileHeatExchange> heatExchange = BCFactoryBlocks.ENTITYBLOCKHEATEXCHANGE.get();
        BlockEntityType<buildcraft.factory.tile.TileAutoWorkbenchItems> autoWorkbench = BCFactoryBlocks.ENTITYBLOCKAUTOBENCH.get();
        BlockEntityType<buildcraft.factory.tile.TileChute> chute = BCFactoryBlocks.ENTITYBLOCKCHUTE.get();

        BCCapabilityRegistration.registerBlockEntity(event, CapUtil.CAP_FLUIDS, tank);
        BCCapabilityRegistration.registerBlockEntity(event, CapUtil.CAP_FLUIDS, pump);
        BCCapabilityRegistration.registerBlockEntity(event, CapUtil.CAP_FLUIDS, floodGate);
        BCCapabilityRegistration.registerBlockEntity(event, CapUtil.CAP_FLUIDS, distiller);
        BCCapabilityRegistration.registerBlockEntity(event, CapUtil.CAP_FLUIDS, heatExchange);

        BCCapabilityRegistration.registerBlockEntity(event, Capabilities.ItemHandler.BLOCK, autoWorkbench);
        BCCapabilityRegistration.registerBlockEntity(event, Capabilities.ItemHandler.BLOCK, chute);
        BCCapabilityRegistration.registerBlockEntity(event, CapUtil.CAP_ITEM_TRANSACTOR, miningWell);

        registerMinerCapabilities(event, pump);
        registerMinerCapabilities(event, miningWell);
        registerMachineCapabilities(event, distiller);
        registerMachineCapabilities(event, autoWorkbench);
        registerMachineCapabilities(event, chute);

        BCCapabilityRegistration.registerBlockEntity(event, TilesAPI.CAP_HAS_WORK, pump);
        BCCapabilityRegistration.registerBlockEntity(event, TilesAPI.CAP_HAS_WORK, miningWell);
        BCCapabilityRegistration.registerBlockEntity(event, TilesAPI.CAP_HAS_WORK, distiller);
        BCCapabilityRegistration.registerBlockEntity(event, TilesAPI.CAP_HAS_WORK, autoWorkbench);
    }

    private static <BE extends net.minecraft.world.level.block.entity.BlockEntity & buildcraft.lib.internal.capabilities.IBCCapabilityProvider>
    void registerMinerCapabilities(RegisterCapabilitiesEvent event, BlockEntityType<BE> type) {
        registerMachineCapabilities(event, type);
    }

    private static <BE extends net.minecraft.world.level.block.entity.BlockEntity & buildcraft.lib.internal.capabilities.IBCCapabilityProvider>
    void registerMachineCapabilities(RegisterCapabilitiesEvent event, BlockEntityType<BE> type) {
        BCCapabilityRegistration.registerBlockEntity(event, MjCapabilities.CAP_CONNECTOR, type);
        BCCapabilityRegistration.registerBlockEntity(event, MjCapabilities.CAP_RECEIVER, type);
        BCCapabilityRegistration.registerBlockEntity(event, MjCapabilities.CAP_REDSTONE_RECEIVER, type);
        BCCapabilityRegistration.registerBlockEntity(event, MjCapabilities.CAP_READABLE, type);
        BCCapabilityRegistration.registerBlockEntity(event, MjCapabilities.CAP_PASSIVE_PROVIDER, type);
    }

    @SuppressWarnings("unused")
    private static void validateNetworkIds() {
        int tankFluid = TileTank.NET_FLUID_DELTA;
        int gasOutput = TileDistiller.NET_TANK_GAS_OUT;
        int input = TileDistiller.NET_TANK_IN;
        int liquidOutput = TileDistiller.NET_TANK_LIQUID_OUT;
    }

    @EventBusSubscriber(modid = MODID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static class ClientModEvents {
        static {
            BCFactorySprites.init();
            BCFactoryModels.init();
        }

        @SubscribeEvent
        public static void registerMenuScreens(RegisterMenuScreensEvent event) {
            BCFactoryClientGuis.clientInit(PlatformClientRegistration.screens(event));
        }

        @SubscribeEvent
    public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        BCFactoryClientRenderers.register(PlatformClientRegistration.renderers(event));
    }
    }
}

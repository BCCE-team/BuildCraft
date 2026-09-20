package buildcraft.factory;

import buildcraft.factory.BCFactoryClientRenderers;
import buildcraft.lib.platform.client.PlatformClientRegistration;
import buildcraft.lib.platform.registry.RegistryBinding;
import buildcraft.core.BCCore;
import buildcraft.factory.client.render.RenderDistiller;
import buildcraft.factory.client.render.RenderHeatExchange;
import buildcraft.factory.client.render.RenderMiningWell;
import buildcraft.factory.client.render.RenderPump;
import buildcraft.factory.client.render.RenderTank;
import buildcraft.factory.tile.TileDistiller;
import buildcraft.factory.tile.TileTank;
import net.minecraft.client.renderer.RenderType;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.data.event.GatherDataEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

@Mod(BCFactory.MODID)
public class BCFactory {
    public static final String MODID = "buildcraftfactory";

    public BCFactory() {
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();
        modEventBus.addListener(this::gatherData);

        BCFactoryBlocks.registry(RegistryBinding.on(modEventBus));
        BCFactoryItems.registry(RegistryBinding.on(modEventBus));
        BCFactoryGuis.registry(RegistryBinding.on(modEventBus));
        BCCore.BUILDCRAFT_TAB.addItemProvider(BCFactoryItems::getCreativeTabItems);

        MinecraftForge.EVENT_BUS.register(this);
        validateNetworkIds();
    }

    public void gatherData(GatherDataEvent event) {
        event.getGenerator().addProvider(
            event.includeServer(),
            new BCFactoryRecipesProvider(event.getGenerator().getPackOutput())
        );
    }

    @SuppressWarnings("unused")
    private static void validateNetworkIds() {
        int tankFluid = TileTank.NET_FLUID_DELTA;
        int gasOutput = TileDistiller.NET_TANK_GAS_OUT;
        int input = TileDistiller.NET_TANK_IN;
        int liquidOutput = TileDistiller.NET_TANK_LIQUID_OUT;
    }

    @Mod.EventBusSubscriber(modid = MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static class ClientModEvents {
        static {
            BCFactorySprites.init();
            BCFactoryModels.init();
        }

        @SubscribeEvent
        public static void onClientSetup(FMLClientSetupEvent event) {
            PlatformClientRegistration.screens(event, BCFactoryClientGuis::clientInit);
            event.enqueueWork(() -> {
                BCFactoryClientRenderers.layers(PlatformClientRegistration.layers());
            });
        }

        @SubscribeEvent
    public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        BCFactoryClientRenderers.register(PlatformClientRegistration.renderers(event));
    }
    }
}

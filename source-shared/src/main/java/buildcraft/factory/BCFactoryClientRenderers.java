package buildcraft.factory;

import buildcraft.lib.platform.client.ClientRegistration;
import buildcraft.factory.client.render.RenderDistiller;
import buildcraft.factory.client.render.RenderHeatExchange;
import buildcraft.factory.client.render.RenderMiningWell;
import buildcraft.factory.client.render.RenderPump;
import buildcraft.factory.client.render.RenderTank;

/** Descriptor catalogue; no loader event or render implementation is duplicated here. */
public final class BCFactoryClientRenderers {
    private BCFactoryClientRenderers() {}
    public static void register(ClientRegistration.Renderers registry) {
        registry.registerBlockEntityRenderer(BCFactoryBlocks.ENTITYBLOCKTANK.get(), RenderTank::new);
        registry.registerBlockEntityRenderer(BCFactoryBlocks.ENTITYBLOCKPUMP.get(), RenderPump::new);
        registry.registerBlockEntityRenderer(BCFactoryBlocks.ENTITYBLOCKMININGWELL.get(), RenderMiningWell::new);
        registry.registerBlockEntityRenderer(BCFactoryBlocks.ENTITYBLOCKDISTILLER.get(), RenderDistiller::new);
        registry.registerBlockEntityRenderer(BCFactoryBlocks.ENTITYBLOCKHEATEXCHANGE.get(), RenderHeatExchange::new);
    }
    public static void layers(ClientRegistration.Layers registry) {
        registry.set(BCFactoryBlocks.TANK_BLOCK.get(), ClientRegistration.BlockLayer.CUTOUT);
        registry.set(BCFactoryBlocks.DISTILLER_BLOCK.get(), ClientRegistration.BlockLayer.CUTOUT);
        registry.set(BCFactoryBlocks.HEATEXCHANGE_BLOCK.get(), ClientRegistration.BlockLayer.CUTOUT);
        registry.set(BCFactoryBlocks.CHUTE_BLOCK.get(), ClientRegistration.BlockLayer.CUTOUT);
    }
}

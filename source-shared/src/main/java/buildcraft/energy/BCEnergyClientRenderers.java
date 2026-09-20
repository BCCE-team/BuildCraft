package buildcraft.energy;

import buildcraft.lib.platform.client.ClientRegistration;
import buildcraft.core.client.render.RenderEngine_BC8;
import buildcraft.energy.client.render.RenderDynamoMJ;

/** Descriptor catalogue; no loader event or render implementation is duplicated here. */
public final class BCEnergyClientRenderers {
    private BCEnergyClientRenderers() {}
    public static void register(ClientRegistration.Renderers registry) {
        registry.registerBlockEntityRenderer(BCEnergyBlocks.ENGINE_IRON_TILE_BC8.get(), RenderEngine_BC8::new);
        registry.registerBlockEntityRenderer(BCEnergyBlocks.ENGINE_STONE_TILE_BC8.get(), RenderEngine_BC8::new);
        registry.registerBlockEntityRenderer(BCEnergyBlocks.ENGINE_FE_TILE_BC8.get(), RenderEngine_BC8::new);
        registry.registerBlockEntityRenderer(BCEnergyBlocks.DYNAMO_MJ_TILE.get(), RenderDynamoMJ::new);
    }
}

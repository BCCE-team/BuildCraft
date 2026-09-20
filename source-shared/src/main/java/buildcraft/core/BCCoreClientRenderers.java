package buildcraft.core;

import buildcraft.lib.platform.client.ClientRegistration;
import buildcraft.core.BCCoreBlocks;
import buildcraft.core.client.render.RenderEngine_BC8;
import buildcraft.core.client.render.RenderMarkerVolume;

/** Descriptor catalogue; no loader event or render implementation is duplicated here. */
public final class BCCoreClientRenderers {
    private BCCoreClientRenderers() {}
    public static void register(ClientRegistration.Renderers registry) {
        registry.registerBlockEntityRenderer(BCCoreBlocks.ENGINE_REDSTONE_TILE_BC8.get(), RenderEngine_BC8::new);
        registry.registerBlockEntityRenderer(BCCoreBlocks.ENGINE_CREATIVE_TILE_BC8.get(), RenderEngine_BC8::new);
        registry.registerBlockEntityRenderer(BCCoreBlocks.MARKER_VOLUME_TILE_BC8.get(), RenderMarkerVolume::new);
    }
}

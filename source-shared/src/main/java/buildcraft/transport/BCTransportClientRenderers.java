package buildcraft.transport;

import buildcraft.lib.platform.client.ClientRegistration;
import buildcraft.transport.client.render.RenderPipeHolder;

/** Descriptor catalogue; no loader event or render implementation is duplicated here. */
public final class BCTransportClientRenderers {
    private BCTransportClientRenderers() {}
    public static void register(ClientRegistration.Renderers registry) {
        registry.registerBlockEntityRenderer(BCTransportBlocks.PIPE_HOLDER_BE.get(), RenderPipeHolder::new);
    }
}

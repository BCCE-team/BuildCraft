/* BuildCraft Transport client bootstrap for Fabric 26.3. MPL-2.0. */
package buildcraft.transport.client;

import buildcraft.lib.net.BCNetworkSide;
import buildcraft.lib.net.MessageManager;
import buildcraft.lib.platform.client.PlatformClientRegistration;
import buildcraft.transport.BCTransportBlocks;
import buildcraft.transport.BCTransportClientGuis;
import buildcraft.transport.BCTransportModels;
import buildcraft.transport.client.model.ModelPipeItem;
import buildcraft.transport.client.model.ModelPipeNative2612;
import buildcraft.transport.internal.pipe.IItemPipe;
import buildcraft.transport.internal.pipe.PipeApiClient;
import buildcraft.transport.net.MessageMultiPipeItem;
import buildcraft.transport.net.MessageMultiPipeItemClientHandler;
import buildcraft.transport.wire.MessageWireSystems;
import buildcraft.transport.wire.MessageWireSystemsPowered;
import buildcraft.transport.wire.MessageWireSystemsPoweredClientHandler;
import net.fabricmc.fabric.api.client.model.loading.v1.ModelLoadingPlugin;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;

/**
 * Install model modifiers before first resource reload, and use the same
 * payload routing on client and server as Core/Energy.
 */
public final class FabricTransportClient {
    private static boolean bootstrapped;
    private FabricTransportClient() { }

    public static synchronized void bootstrap() {
        if (bootstrapped) return;
        bootstrapped = true;
        PipeApiClient.registry = PipeRegistryClient.INSTANCE;
        BCTransportModels.init();
        BCTransportClientGuis.clientInit(PlatformClientRegistration.screens());
        BCTransportModels.onBlockEntityRender(PlatformClientRegistration.renderers());

        MessageManager.setHandler(MessageWireSystems.class,
            MessageWireSystems.HANDLER, BCNetworkSide.CLIENT);
        MessageManager.setHandler(MessageWireSystemsPowered.class,
            MessageWireSystemsPoweredClientHandler::handle, BCNetworkSide.CLIENT);
        MessageManager.setHandler(MessageMultiPipeItem.class,
            MessageMultiPipeItemClientHandler::handle, BCNetworkSide.CLIENT);

        ModelLoadingPlugin.register(context -> {
            context.modifyBlockModelAfterBake().register((model, info) ->
                info.state().getBlock() == BCTransportBlocks.pipeHolder.get()
                    ? ModelPipeNative2612.INSTANCE : model);
            context.modifyItemModelAfterBake().register((model, info) -> {
                Item item = BuiltInRegistries.ITEM.getValue(info.itemId());
                if (item instanceof IItemPipe pipe) {
                    return new ModelPipeItem(pipe.getDefinition(), model);
                }
                return model;
            });
        });
        // Block model cache is intentionally rebuilt on each chunk meshing
        // request; no NeoForge ModelData listener is required here.
    }
}
